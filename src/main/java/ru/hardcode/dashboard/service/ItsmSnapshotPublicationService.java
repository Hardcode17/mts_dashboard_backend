package ru.hardcode.dashboard.service;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import ru.hardcode.dashboard.dto.ItsmIncidentRecord;
import ru.hardcode.dashboard.dto.ItsmIncidentSnapshot;
import ru.hardcode.dashboard.exception.AppException;
import ru.hardcode.dashboard.exception.AppErrors;
import ru.hardcode.dashboard.exception.ItsmRecordValidationException;
import ru.hardcode.dashboard.repository.ItsmBaselineRepository;
import ru.hardcode.dashboard.repository.ItsmBatchSqlRepository;
import ru.hardcode.dashboard.repository.ItsmSnapshotPublicationRepository;
import ru.hardcode.dashboard.repository.ItsmSnapshotPublicationRepository.Change;
import ru.hardcode.dashboard.worker.ItsmBatchProcessor;
import ru.hardcode.dashboard.worker.ItsmBatchWork;
import tools.jackson.databind.ObjectMapper;

@Service
@ConditionalOnProperty(name = "itsm.publication.enabled", matchIfMissing = true, havingValue = "true")
public class ItsmSnapshotPublicationService implements ItsmBatchProcessor {

	private static final Logger log = LoggerFactory.getLogger(ItsmSnapshotPublicationService.class);
	private static final Map<String, Integer> DOMAIN_LENGTHS = Map.ofEntries(
			Map.entry("id", 128), Map.entry("sysId", 128),
			Map.entry("productName", 300), Map.entry("productId", 128),
			Map.entry("productMasterId", 128), Map.entry("productUrl", 1024),
			Map.entry("service", 300), Map.entry("serviceId", 128), Map.entry("scenarioName", 300),
			Map.entry("assignedGroupName", 300), Map.entry("assignedEmployee", 300),
			Map.entry("assignedEmployeeLogin", 128), Map.entry("initiatorName", 300), Map.entry("initiatorLogin", 128),
			Map.entry(ItsmIncidentSnapshotParser.FIELD_PARENT_ID, 128));

	private final ItsmIncidentSnapshotParser parser;
	private final ItsmIncidentHierarchyResolver hierarchyResolver;
	private final ItsmSnapshotPublicationRepository repository;
	private final ItsmBatchSqlRepository batches;
	private final ItsmBaselineRepository domain;
	private final ItsmSnapshotMapper mapper;
	private final ObjectMapper objectMapper;
	private final ApplicationEventPublisher events;
	private final DashboardMonitoringService monitoring;
	private final CoordinationTaskService coordinationTasks;
	private final Clock clock;

	public ItsmSnapshotPublicationService(ItsmIncidentSnapshotParser parser,
			ItsmIncidentHierarchyResolver hierarchyResolver,
			ItsmSnapshotPublicationRepository repository, ItsmBatchSqlRepository batches,
			ItsmBaselineRepository domain, ItsmSnapshotMapper mapper,
			ObjectMapper objectMapper, ApplicationEventPublisher events, DashboardMonitoringService monitoring,
			CoordinationTaskService coordinationTasks,
			Clock clock) {
		this.parser = parser;
		this.hierarchyResolver = hierarchyResolver;
		this.repository = repository;
		this.batches = batches;
		this.domain = domain;
		this.mapper = mapper;
		this.objectMapper = objectMapper;
		this.events = events;
		this.monitoring = monitoring;
		this.coordinationTasks = coordinationTasks;
		this.clock = clock;
	}

	@Override
	public boolean requiresSequentialVersions() {
		return true;
	}

	@Override
	public void process(ItsmBatchWork work, PublicationContext publication) {
		// Prepare independently; only the ordering decision and publication serialize per source.
		byte[] payload = work.payload();
		ItsmIncidentSnapshot normalized;
		try {
			normalized = parser.parse(payload);
			validateDomainFields(normalized);
		} catch (AppException exception) {
			if (exception.getAppError() == AppErrors.ITSM_INVALID_RECORD
					|| exception.getAppError() == AppErrors.ITSM_DUPLICATE_RECORD
					|| exception.getAppError() == AppErrors.ITSM_INVALID_PAYLOAD) {
				publication.rejectInvalid(exception.getAppError().getCode() + ": " + exception.getMessage());
				return;
			}
			throw exception;
		}
		String snapshot = toJson(normalized);
		if (!repository.isStorableSnapshot(snapshot)) {
			publication.rejectInvalid(AppErrors.ITSM_UNSUPPORTED_SNAPSHOT_VALUE.getCode() + ": "
					+ AppErrors.ITSM_UNSUPPORTED_SNAPSHOT_VALUE.getMessage());
			return;
		}

		// KAN-17 catalog and public snapshot metadata are shared by sources.
		domain.lockInstallation();
		var head = repository.lockSource(work.sourceId());
		if (head.observedVersion() == null) {
			batches.lockSourceForBaseline(work.sourceId());
		}
		if (head.observedVersion() != null && work.sourceVersion() <= head.observedVersion()) {
			publication.markSkipped(head.changeRevision(), "Snapshot version is older than the current observation");
			return;
		}
		Long expected = repository.firstUnsettledVersion(work.sourceId(), head.observedVersion(), work.sourceVersion());
		if (expected != null) {
			publication.deferUntil(expected);
			return;
		}
		if (repository.hasForeignIncident(work.sourceId(), snapshot)) {
			publication.rejectInvalid(AppErrors.ITSM_INCIDENT_SOURCE_CONFLICT.getCode() + ": "
					+ AppErrors.ITSM_INCIDENT_SOURCE_CONFLICT.getMessage());
			return;
		}

		// Validate the whole candidate graph before any write. A rejected hierarchy therefore
		// leaves the last published snapshot, journal and relations untouched.
		ItsmIncidentHierarchyResolver.Hierarchy hierarchy;
		try {
			hierarchy = hierarchyResolver.resolve(normalized, domain.findImportedHierarchy(work.sourceId()));
		} catch (AppException exception) {
			if (exception.getAppError() == AppErrors.ITSM_INVALID_HIERARCHY) {
				publication.rejectInvalid(exception.getAppError().getCode() + ": " + exception.getMessage());
				return;
			}
			throw exception;
		}

		restoreLegacyBaseline(work.sourceId(), head.observedVersion());
		var changes = repository.journalChanges(work.sourceId(), work.sourceVersion(), snapshot,
				clock.instant(), objectMapper.writeValueAsString(sourceTimes(normalized)));
		long revision;
		if (!changes.isEmpty()) {
			revision = changes.getFirst().revision();
		} else if (head.changeRevision() == null) {
			revision = repository.nextRevision();
		} else {
			revision = head.changeRevision();
		}
		var present = changes.stream().filter(change -> !change.missing()).map(Change::incidentId)
				.collect(Collectors.toSet());
		var missing = changes.stream().filter(Change::missing).map(Change::incidentId).collect(Collectors.toSet());
		var changedRecords = normalized.records().stream().filter(record -> present.contains(record.id()))
				.sorted(Comparator.comparing(ItsmIncidentRecord::id)).toList();
		var mapping = mapper.map(new ItsmIncidentSnapshot(changedRecords));
		domain.upsertDomain(mapping, work.sourceId(), present, missing);
		if (head.observedVersion() != null && !changes.isEmpty()) {
			coordinationTasks.observeEntries(work.sourceId(), revision);
		}
		domain.replaceImportedHierarchy(work.sourceId(), hierarchy.linksByChildId());
		repository.updateObservation(work.sourceId(), work.sourceVersion(), revision, snapshot);
		Instant appliedAt = clock.instant();
		domain.updateSnapshot(work.batchId(), work.sourceId(), work.sourceVersion(),
				ItsmBatchIntakeService.sha256(payload), appliedAt);
		monitoring.recordPublishedSnapshot(work.batchId(), appliedAt);
		if (!changes.isEmpty()) {
			publishAfterCommit(new ItsmSnapshotPublished(work.sourceId(), work.sourceVersion(), revision,
					changes.stream().map(Change::incidentId).collect(Collectors.toSet())));
		}
		publication.markSucceeded(revision, changes.size() + " incident(s) changed");
	}

	private String toJson(ItsmIncidentSnapshot normalized) {
		return objectMapper.writeValueAsString(normalized.records().stream().map(ItsmIncidentRecord::raw).toList());
	}

	/** Source-side time of each record, when the ITSM provides one. */
	private static Map<String, String> sourceTimes(ItsmIncidentSnapshot normalized) {
		var times = new java.util.LinkedHashMap<String, String>();
		for (var record : normalized.records()) {
			var sourceTime = ItsmSourceTimes.of(record.raw().get("updatedAt"));
			if (sourceTime != null) {
				times.put(record.id(), sourceTime.toString());
			}
		}
		return times;
	}

	private void restoreLegacyBaseline(String sourceId, Long observedVersion) {
		if (observedVersion == null) {
			return;
		}
		byte[] legacyPayload = repository.legacyPayload(sourceId, observedVersion);
		if (legacyPayload == null) {
			return;
		}
		String legacy;
		try {
			legacy = toJson(parser.parse(legacyPayload));
		} catch (AppException unparseableLegacy) {
			// Nothing to hydrate: the journal can still capture the old persisted projection.
			log.warn("Could not normalize the legacy ITSM observation");
			return;
		}
		if (repository.isStorableSnapshot(legacy)) {
			repository.restoreLegacySourceData(sourceId, legacy);
		}
	}

	/** Durable, ordered replay for read models. A local Spring event is only a wake-up signal. */
	@Transactional(readOnly = true)
	public List<ItsmSnapshotPublished> changesAfter(long revision, int limit) {
		if (revision < 0 || limit < 1 || limit > 500) {
			throw new IllegalArgumentException("Expected revision >= 0 and limit from 1 to 500");
		}
		return repository.findChangesAfter(revision, limit).stream()
				.map(change -> new ItsmSnapshotPublished(change.sourceId(), change.sourceVersion(),
						change.revision(), change.incidentIds()))
				.toList();
	}
	static void validateDomainFields(ItsmIncidentSnapshot snapshot) {
		for (int i = 0; i < snapshot.size(); i++) {
			var raw = snapshot.records().get(i).raw();
			if (!(raw.get("productName") instanceof String productName) || productName.isBlank()) {
				throw new ItsmRecordValidationException(i, "productName", "must be a non-blank string", null);
			}
			for (var field : DOMAIN_LENGTHS.entrySet()) {
				if (raw.get(field.getKey()) instanceof String value
						&& value.codePointCount(0, value.length()) > field.getValue()) {
					throw new ItsmRecordValidationException(i, field.getKey(),
							"must not exceed " + field.getValue() + " characters", null);
				}
			}
			if (raw.keySet().stream().anyMatch(key -> key.codePointCount(0, key.length()) > 128)) {
				throw new ItsmRecordValidationException(i, "attributes", "attribute name exceeds 128 characters", null);
			}
		}
	}

	private void publishAfterCommit(ItsmSnapshotPublished event) {
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCommit() {
				try {
					events.publishEvent(event);
				} catch (RuntimeException failure) {
					log.error("ITSM post-commit listener failed at revision {}: {}", event.changeRevision(),
							failure.getClass().getSimpleName());
				}
			}
		});
	}
}
