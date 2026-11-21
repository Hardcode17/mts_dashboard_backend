package ru.hardcode.dashboard.service;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import ru.hardcode.dashboard.dto.DashboardIncidentCardResponse;
import ru.hardcode.dashboard.dto.DashboardIncidentListResponse;
import ru.hardcode.dashboard.dto.DashboardIncidentListResponse.Summary;
import ru.hardcode.dashboard.dto.DashboardIncidentRelationshipResponse;
import ru.hardcode.dashboard.dto.DashboardIncidentRelationshipResponse.Edge;
import ru.hardcode.dashboard.dto.DashboardIncidentRelationshipResponse.Node;
import ru.hardcode.dashboard.dto.ItsmIncidentSnapshot;
import ru.hardcode.dashboard.entity.ItsmIncidentState;
import ru.hardcode.dashboard.exception.AppErrors;
import ru.hardcode.dashboard.exception.AppException;
import ru.hardcode.dashboard.exception.DashboardIncidentNotFoundException;
import ru.hardcode.dashboard.repository.DashboardIncidentRepository;
import ru.hardcode.dashboard.repository.DashboardIncidentRepository.IncidentRow;
import ru.hardcode.dashboard.repository.ItsmBaselineRepository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import ru.hardcode.dashboard.repository.DashboardIncidentRepository.RelationshipNode;

@Service
public class DashboardIncidentService {

	private static final Pattern INCIDENT_ID = Pattern.compile("INC[0-9]{6,18}");
	private static final TypeReference<Map<String, Object>> ROW = new TypeReference<>() {};
	private static final String COORDINATORS = "КЦ ИТ Координаторы Массовых Инцидентов";
	private static final Comparator<IncidentRow> INCIDENT_ORDER =
			Comparator.comparing(DashboardIncidentService::updatedAt).reversed()
					.thenComparing(row -> row.summary().id());

	private final DashboardIncidentRepository repository;
	private final DashboardItsmLink itsmLink;
	private final Clock clock;
	private final JdbcTemplate db;
	private final ObjectMapper json;
	private final ItsmApiClient itsm;
	private final ItsmBaselineRepository domain;
	private final ItsmIncidentSnapshotParser parser;
	private final ItsmSnapshotMapper mapper;

	public DashboardIncidentService(DashboardIncidentRepository repository, DashboardItsmLink itsmLink, Clock clock,
			JdbcTemplate db, ObjectMapper json, ItsmApiClient itsm, ItsmBaselineRepository domain,
			ItsmIncidentSnapshotParser parser, ItsmSnapshotMapper mapper) {
		this.repository = repository;
		this.itsmLink = itsmLink;
		this.clock = clock;
		this.db = db;
		this.json = json;
		this.itsm = itsm;
		this.domain = domain;
		this.parser = parser;
		this.mapper = mapper;
	}

	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public DashboardIncidentCardResponse card(String incidentId) {
		if (!INCIDENT_ID.matcher(incidentId).matches()) {
			throw new AppException(AppErrors.INVALID_INCIDENT_ID);
		}
		var published = repository.publication();
		if (published.eventSeq() == 0) {
			throw new DashboardIncidentNotFoundException(incidentId, 0, 0);
		}
		var card = repository.findCard(incidentId).orElseThrow(() ->
				new DashboardIncidentNotFoundException(incidentId, published.eventSeq(), published.revision()));
		var incident = card.summary();
		Duration remaining = !incident.state().isCompleted() && incident.deadline() != null
				? Duration.between(clock.instant(), incident.deadline()) : null;
		return new DashboardIncidentCardResponse(incident.id(), card.header(), card.description(), incident.productName(),
				card.productMasterId(), card.service(), card.serviceType(), incident.assignedGroupName(),
				card.assignedEmployeeLogin(), card.assignedEmployee(), incident.state(), card.stateReason(),
				incident.priorityId(), incident.submitDate(),
				card.lastAssignmentToTeamDateTime(), incident.deadline(), incident.completionDate(), card.solution(),
				remaining == null ? null : remaining.getSeconds(), remaining != null && remaining.isNegative(),
				itsmLink.forIncident(incident.id()), itsmLink.demo(), DashboardIncidentGroup.memberships(incident),
				published.eventSeq(), published.revision());
	}

	/**
	 * The published source record of an incident with the publication counters, read through the
	 * same repeatable snapshot as the card itself.
	 */
	private record PublishedRow(Map<String, Object> row, String source) {
	}

	private PublishedRow publishedRow(String incidentId) {
		if (!INCIDENT_ID.matcher(incidentId).matches()) {
			throw new AppException(AppErrors.INVALID_INCIDENT_ID);
		}
		var publication = repository.publication();
		return db.query("""
				SELECT i.source_data, i.source_id FROM itsm_incident i
				WHERE i.id=? AND i.present_in_source AND i.source_data IS NOT NULL
				""", (rs, n) -> new PublishedRow(json.readValue(rs.getString(1), ROW), rs.getString(2)), incidentId)
				.stream().findFirst()
				.orElseThrow(() -> new DashboardIncidentNotFoundException(incidentId,
						publication.eventSeq(), publication.revision()));
	}

	private static void validatePatch(Map<String, Object> changes) {
		if (changes == null || changes.isEmpty()
				|| !Set.of("assignedEmployeeLogin", "assignedEmployee", "stateEnum", "assignedGroupName", "solution")
						.containsAll(changes.keySet())) {
			throw new AppException(AppErrors.INCIDENT_PATCH_INVALID);
		}
		for (var entry : changes.entrySet()) {
			String key = entry.getKey();
			Object value = entry.getValue();
			// A null login or employee name explicitly clears the assignee.
			if (value == null && (key.equals("assignedEmployeeLogin") || key.equals("assignedEmployee"))) {
				continue;
			}
			if (!(value instanceof String text) || text.isBlank()) {
				throw new AppException(AppErrors.INCIDENT_PATCH_INVALID);
			}
			int limit = switch (key) {
				case "assignedEmployeeLogin" -> 128;
				case "assignedGroupName", "assignedEmployee" -> 300;
				default -> Integer.MAX_VALUE;
			};
			if (text.codePointCount(0, text.length()) > limit) {
				throw new AppException(AppErrors.INCIDENT_PATCH_INVALID);
			}
		}
		if (changes.containsKey("stateEnum")
				&& !Set.of("Assigned", "WorkInProgress", "Research", "Resolved", "Closed").contains(changes.get("stateEnum"))) {
			throw new AppException(AppErrors.INCIDENT_PATCH_INVALID);
		}
		if ((changes.containsKey("assignedEmployee") && !changes.containsKey("assignedEmployeeLogin"))
				|| (changes.containsKey("solution")
						&& !Set.of("Research", "Resolved", "Closed").contains(changes.getOrDefault("stateEnum", "")))) {
			throw new AppException(AppErrors.INCIDENT_PATCH_INVALID);
		}
	}

	/**
	 * Applies a coordinator edit to the external ITSM first; only after a confirmed response is
	 * the normalized record stored locally, journaled with the coordinator identity, and the
	 * publication counters advanced. Any ITSM failure leaves the local state untouched.
	 */
	@Transactional
	public DashboardIncidentCardResponse patch(String incidentId, Map<String, Object> changes, long actor) {
		validatePatch(changes);
		domain.lockInstallation();
		var before = publishedRow(incidentId);
		Object sysId = before.row().get("sysId");
		if (!(sysId instanceof String externalId) || externalId.isBlank()) {
			throw new AppException(AppErrors.ITSM_BAD_RESPONSE);
		}
		var response = itsm.patch(externalId, changes);
		if (!incidentId.equals(response.get("id")) || !sysId.equals(response.get("sysId"))) {
			throw new AppException(AppErrors.ITSM_BAD_RESPONSE);
		}
		ItsmIncidentSnapshot normalized;
		try {
			normalized = parser.parse(json.writeValueAsBytes(List.of(response)));
			ItsmSnapshotPublicationService.validateDomainFields(normalized);
		} catch (AppException invalidResponse) {
			throw new AppException(AppErrors.ITSM_BAD_RESPONSE, invalidResponse);
		}
		var row = normalized.records().getFirst().raw();
		String updated = json.writeValueAsString(row);
		java.sql.Timestamp observedAt = java.sql.Timestamp.from(clock.instant());
		Instant sourceTime = ItsmSourceTimes.of(row.get("updatedAt"));
		Long revision = db.query("""
				INSERT INTO itsm_incident_change(source_id,incident_id,source_version,change_revision,event_seq,
				    change_kind,before_data,after_data,coordinator_id,observed_at,source_time)
				SELECT source_id,id,last_seen_version,nextval('itsm_snapshot_change_revision'),
				    nextval('itsm_incident_change_event_seq'),'UPDATED',
				    source_data,?::jsonb,?,?,?
				FROM itsm_incident WHERE id=? AND source_data IS DISTINCT FROM ?::jsonb
				RETURNING change_revision
				""", (rs, n) -> rs.getLong(1), updated, actor, observedAt,
				sourceTime == null ? null : java.sql.Timestamp.from(sourceTime), incidentId, updated)
				.stream().findFirst().orElse(null);
		if (revision != null) {
			domain.upsertDomain(mapper.map(normalized), before.source(), Set.of(incidentId), Set.of());
			db.update("UPDATE itsm_incident SET source_data=?::jsonb,last_change_revision=? WHERE id=?",
					updated, revision, incidentId);
			db.update("UPDATE itsm_snapshot_source SET change_revision=? WHERE source_id=?", revision, before.source());
			db.update("UPDATE dashboard_publication SET revision=?,event_seq=event_seq+1 WHERE singleton",
					revision);
			// Once an incident leaves the coordinators' queue, its open internal task is completed.
			boolean handedOff = !COORDINATORS.equals(row.get("assignedGroupName"))
					|| (changes.containsKey("assignedEmployeeLogin") && row.get("assignedEmployeeLogin") != null);
			if (handedOff) {
				db.update("""
						UPDATE coordination_tasks SET status='completed',finished_at=now(),updated_at=now()
						WHERE incident_id=? AND status='open'
						""", incidentId);
			}
		}
		return card(incidentId);
	}

	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public DashboardIncidentRelationshipResponse relationships(String incidentId) {
		if (!INCIDENT_ID.matcher(incidentId).matches()) {
			throw new AppException(AppErrors.INVALID_INCIDENT_ID);
		}
		var published = repository.publication();
		if (published.eventSeq() == 0) {
			throw new DashboardIncidentNotFoundException(incidentId, 0, 0);
		}
		var focus = repository.findRelationshipNodes(List.of(incidentId)).stream().findFirst()
				.orElseThrow(() -> new DashboardIncidentNotFoundException(
						incidentId, published.eventSeq(), published.revision()));

		var nodes = new LinkedHashMap<String, Node>();
		nodes.put(focus.id(), toNode(focus));
		String parentId = repository.findDirectParentId(incidentId).orElse(null);
		if (parentId != null) {
			repository.findRelationshipNodes(List.of(parentId)).stream().findFirst()
					.ifPresent(parent -> nodes.put(parent.id(), toNode(parent)));
		}
		List<String> childIds = repository.findDirectChildIds(incidentId);
		var childRows = new LinkedHashMap<String, RelationshipNode>();
		repository.findRelationshipNodes(childIds).forEach(child -> childRows.put(child.id(), child));
		for (String childId : childIds) {
			RelationshipNode child = childRows.get(childId);
			if (child != null) {
				nodes.put(child.id(), toNode(child));
			}
		}

		var edges = new ArrayList<Edge>(childIds.size() + 1);
		if (parentId != null && nodes.containsKey(parentId)) {
			edges.add(new Edge(parentId, incidentId));
		}
		for (String childId : childIds) {
			if (nodes.containsKey(childId)) {
				edges.add(new Edge(incidentId, childId));
			}
		}
		return new DashboardIncidentRelationshipResponse(incidentId, List.copyOf(nodes.values()), edges);
	}

	private static Node toNode(RelationshipNode node) {
		return new Node(node.id(), node.title(), node.stateEnum(), node.priorityId());
	}

	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public ListPage list(List<String> groups, boolean includeCompleted, String cursor, int limit) {
		if (limit < 1 || limit > 100) {
			throw new AppException(AppErrors.INVALID_LIMIT);
		}
		var selection = selection(groups, includeCompleted);
		var publication = repository.publication();
		String variant = publication.eventSeq() + "-" + selection.key() + "-" + limit;
		int offset = cursor == null ? 0 : decodeOffset(cursor, variant, limit);
		if (publication.eventSeq() == 0) {
			if (cursor != null) {
				throw new AppException(AppErrors.INVALID_CURSOR);
			}
			return new ListPage(new DashboardIncidentListResponse(0, 0, true, List.of(), List.of(), null),
					variant + "-0");
		}

		var selected = selectItems(selection);
		if (cursor != null && offset >= selected.size()) {
			throw new AppException(AppErrors.INVALID_CURSOR);
		}
		int end = offset + Math.min(limit, selected.size() - offset);
		var items = selected.subList(offset, end);
		var sections = offset == 0 ? Arrays.stream(DashboardIncidentGroup.values())
				.map(group -> selection.includes(group) ? group.summarize(selected, items)
						: group.summarize(List.of(), List.of())).toList()
				: List.<DashboardIncidentListResponse.Group>of();
		var body = new DashboardIncidentListResponse(publication.eventSeq(), publication.revision(), false,
				sections, items, end < selected.size() ? encodeOffset(variant, end) : null);
		return new ListPage(body, variant + "-" + offset);
	}

	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public ListPage export(List<String> groups, boolean includeCompleted) {
		var selection = selection(groups, includeCompleted);
		var publication = repository.publication();
		boolean empty = publication.eventSeq() == 0;
		var body = new DashboardIncidentListResponse(publication.eventSeq(), publication.revision(), empty,
				List.of(), empty ? List.of() : selectItems(selection), null);
		return new ListPage(body, publication.eventSeq() + "-" + selection.key() + "-csv");
	}

	private List<Summary> selectItems(Selection selection) {
		return repository.list().stream()
				.filter(row -> selection.includeCompleted() || !row.summary().state().isCompleted()
						|| row.summary().state() == ItsmIncidentState.Research)
				.sorted(INCIDENT_ORDER)
				.map(DashboardIncidentService::toSummary)
				.filter(item -> selection.groups().isEmpty() || selection.groups().stream().anyMatch(g -> g.contains(item)))
				.toList();
	}

	private static Summary toSummary(IncidentRow row) {
		var inc = row.summary();
		return new Summary(inc.id(), inc.productName(), inc.assignedGroupName(), inc.state(), inc.priorityId(),
				inc.submitDate(), inc.deadline(), inc.completionDate(), DashboardIncidentGroup.memberships(inc));
	}

	private static Instant updatedAt(IncidentRow row) {
		if (row.sourceUpdatedAt() != null) {
			try {
				return Instant.parse(row.sourceUpdatedAt());
			} catch (DateTimeParseException ignored) {
				// updatedAt is not a required or validated field of the incoming snapshot.
			}
		}
		return row.changedAt() != null ? row.changedAt() : row.latestSourceDate();
	}

	private static Selection selection(List<String> groups, boolean includeCompleted) {
		var selected = EnumSet.noneOf(DashboardIncidentGroup.class);
		groups.forEach(key -> selected.add(DashboardIncidentGroup.fromKey(key)));
		return new Selection(selected, includeCompleted);
	}

	private static String encodeOffset(String variant, int offset) {
		return Base64.getUrlEncoder().withoutPadding()
				.encodeToString(("1:" + variant + ":" + offset).getBytes(StandardCharsets.US_ASCII));
	}

	private static int decodeOffset(String cursor, String variant, int limit) {
		try {
			if (cursor.length() > 96) {
				throw new IllegalArgumentException();
			}
			String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.US_ASCII);
			String prefix = "1:" + variant + ":";
			if (decoded.startsWith(prefix)) {
				int offset = Integer.parseInt(decoded.substring(prefix.length()));
				if (offset > 0 && offset % limit == 0 && encodeOffset(variant, offset).equals(cursor)) {
					return offset;
				}
			}
		} catch (IllegalArgumentException ignored) {
			// Malformed encoding or offset.
		}
		throw new AppException(AppErrors.INVALID_CURSOR);
	}

	private record Selection(EnumSet<DashboardIncidentGroup> groups, boolean includeCompleted) {
		boolean includes(DashboardIncidentGroup group) {
			return groups.isEmpty() || groups.contains(group);
		}

		String key() {
			int mask = groups.stream().mapToInt(group -> 1 << group.ordinal()).sum();
			return mask + "-" + (includeCompleted ? 1 : 0);
		}
	}

	public record ListPage(DashboardIncidentListResponse body, String etag) {
	}
}
