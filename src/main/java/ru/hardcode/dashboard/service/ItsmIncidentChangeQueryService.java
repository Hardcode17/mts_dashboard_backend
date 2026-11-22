package ru.hardcode.dashboard.service;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.hardcode.dashboard.dto.IncidentChangeEvent;
import ru.hardcode.dashboard.dto.IncidentChangeField;
import ru.hardcode.dashboard.dto.IncidentChangesPage;
import ru.hardcode.dashboard.dto.IncidentHistoryResponse;
import ru.hardcode.dashboard.exception.AppErrors;
import ru.hardcode.dashboard.exception.AppException;
import ru.hardcode.dashboard.exception.DashboardIncidentNotFoundException;
import ru.hardcode.dashboard.repository.DashboardIncidentRepository;
import ru.hardcode.dashboard.repository.ItsmIncidentChangeRepository;
import ru.hardcode.dashboard.repository.ItsmIncidentChangeRepository.JournalRow;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Service
public class ItsmIncidentChangeQueryService implements ItsmIncidentChangeReader {

	private static final Pattern INCIDENT_ID = Pattern.compile("INC[0-9]{6,18}");
	private static final TypeReference<Map<String, Object>> DOCUMENT = new TypeReference<>() {};

	/** The six tracked fields whose previous and current values every event carries. */
	public static final List<String> TRACKED_FIELDS =
			List.of("stateEnum", "assignedGroupName", "assignedEmployee", "priorityId", "deadline", "solution");

	private final ItsmIncidentChangeRepository journal;
	private final DashboardIncidentRepository dashboards;
	private final ObjectMapper objectMapper;

	public ItsmIncidentChangeQueryService(ItsmIncidentChangeRepository journal,
			DashboardIncidentRepository dashboards, ObjectMapper objectMapper) {
		this.journal = journal;
		this.dashboards = dashboards;
		this.objectMapper = objectMapper;
	}

	@Override
	@Transactional(readOnly = true)
	public IncidentChangesPage changesAfter(long afterEventSeq, int limit) {
		var rows = journal.afterEventSeq(afterEventSeq, limit + 1);
		boolean hasMore = rows.size() > limit;
		var events = rows.stream().limit(limit).map(this::event).toList();
		long lastEventSeq = events.isEmpty() ? afterEventSeq : events.getLast().eventSeq();
		return new IncidentChangesPage(events, hasMore, lastEventSeq);
	}

	@Override
	@Transactional(readOnly = true)
	public List<IncidentChangeEvent> incidentHistory(String incidentId) {
		return journal.incidentHistory(incidentId).stream().map(this::event).toList();
	}

	/**
	 * HTTP-facing read of the cursor feed: validates the cursor and limit and returns one page.
	 */
	@Transactional(readOnly = true)
	public IncidentChangesPage changesFeed(long afterEventSeq, int limit) {
		if (afterEventSeq < 0) {
			throw new AppException(AppErrors.INVALID_EVENT_SEQ);
		}
		if (limit < 1 || limit > 100) {
			throw new AppException(AppErrors.INVALID_LIMIT);
		}
		return changesAfter(afterEventSeq, limit);
	}

	/**
	 * HTTP-facing read of one incident's journal. A disappeared incident stays readable with
	 * the time of its last observation; only an incident unknown to the dashboard is a 404.
	 */
	@Transactional(readOnly = true)
	public IncidentHistoryResponse history(String incidentId) {
		if (!INCIDENT_ID.matcher(incidentId).matches()) {
			throw new AppException(AppErrors.INVALID_INCIDENT_ID);
		}
		var events = incidentHistory(incidentId);
		if (events.isEmpty() && !journal.existsIncident(incidentId)) {
			var publication = dashboards.publication();
			throw new DashboardIncidentNotFoundException(incidentId, publication.eventSeq(), publication.revision());
		}
		var lastObservedAt = events.reversed().stream().filter(event -> event.details() == null)
				.findFirst().map(IncidentChangeEvent::observedAt).orElse(null);
		return new IncidentHistoryResponse(incidentId, lastObservedAt, events);
	}

	private IncidentChangeEvent event(JournalRow row) {
		Map<String, Object> details = row.details() == null ? null : readDocument(row.details());
		var fields = details == null ? changedFields(row) : List.<IncidentChangeField>of();
		return new IncidentChangeEvent(row.eventSeq(), row.sourceId(), row.incidentId(), row.sourceVersion(),
				row.changeRevision(), row.changeKind(), row.observedAt(), row.sourceTime(), fields,
					details == null ? null : "local-" + row.eventSeq(), details);
	}

	private List<IncidentChangeField> changedFields(JournalRow row) {
		Map<String, Object> before = readDocument(row.beforeData());
		Map<String, Object> after = readDocument(row.afterData());
		return TRACKED_FIELDS.stream()
				.map(field -> new IncidentChangeField(field, before.get(field), after.get(field)))
				.toList();
	}

	private Map<String, Object> readDocument(String json) {
		if (json == null) {
			return Map.of();
		}
		return objectMapper.readValue(json, DOCUMENT);
	}
}
