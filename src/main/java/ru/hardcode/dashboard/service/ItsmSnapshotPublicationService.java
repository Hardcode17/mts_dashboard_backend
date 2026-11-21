package ru.hardcode.dashboard.service;

import java.time.Clock;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import ru.hardcode.dashboard.dto.ItsmIncidentRecord;
import ru.hardcode.dashboard.dto.ItsmIncidentSnapshot;
import ru.hardcode.dashboard.exception.AppException;
import ru.hardcode.dashboard.exception.AppErrors;
import ru.hardcode.dashboard.exception.ItsmRecordValidationException;
import ru.hardcode.dashboard.repository.ItsmBaselineRepository;
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
			Map.entry("assignedEmployeeLogin", 128), Map.entry("initiatorName", 300), Map.entry("initiatorLogin", 128));

	private final ItsmIncidentSnapshotParser parser;
	private final ItsmSnapshotPublicationRepository repository;
	private final ItsmBaselineRepository domain;
	private final ItsmSnapshotMapper mapper;
	private final ObjectMapper objectMapper;
	private final ApplicationEventPublisher events;
	private final Clock clock;

	public ItsmSnapshotPublicationService(ItsmIncidentSnapshotParser parser,
			ItsmSnapshotPublicationRepository repository, ItsmBaselineRepository domain, ItsmSnapshotMapper mapper,
			ObjectMapper objectMapper, ApplicationEventPublisher events, Clock clock) {
		this.parser = parser;
		this.repository = repository;
		this.domain = domain;
		this.mapper = mapper;
		this.objectMapper = objectMapper;
		this.events = events;
		this.clock = clock;
	}

	@Override
	public boolean requiresSequentialVersions() {
		return true;
	}

	@Override
	public void process(ItsmBatchWork work, PublicationContext publication) {
		// Prepare independently; only the ordering decision and publication serialize per source.
		ItsmIncidentSnapshot normalized;
		try {
			normalized = parser.parse(work.payload());
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
		String snapshot = objectMapper.writeValueAsString(normalized.records().stream().map(ItsmIncidentRecord::raw).toList());
		if (!repository.isStorableSnapshot(snapshot)) {
			publication.rejectInvalid(AppErrors.ITSM_UNSUPPORTED_SNAPSHOT_VALUE.getCode() + ": "
					+ AppErrors.ITSM_UNSUPPORTED_SNAPSHOT_VALUE.getMessage());
			return;
		}

		var mapping = mapper.map(normalized);
		// KAN-17 catalog and public snapshot metadata are shared by sources.
		domain.lockInstallation();
		var head = repository.lockSource(work.sourceId());
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

		var changes = repository.journalChanges(work.sourceId(), work.sourceVersion(), snapshot);
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
		domain.upsertDomain(mapping, work.sourceId(), present, missing);
		repository.updateObservation(work.sourceId(), work.sourceVersion(), revision, snapshot);
		domain.updateSnapshot(work.batchId(), work.sourceId(), work.sourceVersion(),
				ItsmBatchIntakeService.sha256(work.payload()), clock.instant());
		if (!changes.isEmpty()) {
			publishAfterCommit(new ItsmSnapshotPublished(work.sourceId(), work.sourceVersion(), revision,
					changes.stream().map(Change::incidentId).collect(Collectors.toSet())));
		}
		publication.markSucceeded(revision, changes.size() + " incident(s) changed");
	}

	private static void validateDomainFields(ItsmIncidentSnapshot snapshot) {
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
