package ru.hardcode.dashboard.service.notes;

import java.time.Clock;
import java.util.Map;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.hardcode.dashboard.dto.CreateIncidentNoteRequest;
import ru.hardcode.dashboard.dto.IncidentNoteResponse;
import ru.hardcode.dashboard.dto.IncidentNotesPage;
import ru.hardcode.dashboard.entity.CoordinatorUser;
import ru.hardcode.dashboard.exception.AppErrors;
import ru.hardcode.dashboard.exception.AppException;
import ru.hardcode.dashboard.exception.DashboardIncidentNotFoundException;
import ru.hardcode.dashboard.repository.DashboardIncidentRepository;
import ru.hardcode.dashboard.repository.IncidentNoteRepository;
import ru.hardcode.dashboard.repository.IncidentNoteRepository.NoteRow;
import ru.hardcode.dashboard.service.LocalIncidentEventWriter;

/**
 * Shift notes of coordinators (KAN-43). A note is a local chronology event: it is written
 * through the shared {@link LocalIncidentEventWriter} port, advances the shared KAN-28
 * {@code eventSeq} and never touches the published snapshot {@code revision}. The author is
 * always the authenticated local coordinator; a claimed foreign author, an empty text or an
 * unknown INC are rejected before the writer is called, so nothing is written and no sequence
 * value is burned.
 */
@Service
public class IncidentNoteService {

	private static final String NOTE_ADDED = "NOTE_ADDED";
	private static final Pattern INCIDENT_ID = Pattern.compile("INC[0-9]{6,18}");
	private static final int MAX_TEXT_LENGTH = 4000;
	private static final int MAX_PAGE_SIZE = 100;

	private final LocalIncidentEventWriter events;
	private final IncidentNoteRepository notes;
	private final DashboardIncidentRepository dashboards;
	private final Clock clock;

	public IncidentNoteService(LocalIncidentEventWriter events, IncidentNoteRepository notes,
			DashboardIncidentRepository dashboards, Clock clock) {
		this.events = events;
		this.notes = notes;
		this.dashboards = dashboards;
		this.clock = clock;
	}

	/**
	 * Saves one note of the authenticated coordinator. The note row and its local event commit
	 * together in one transaction.
	 */
	@Transactional
	public IncidentNoteResponse create(String incidentId, CreateIncidentNoteRequest request,
			CoordinatorUser author) {
		requireIncidentId(incidentId);
		String text = request.text();
		if (text == null || text.isBlank() || text.length() > MAX_TEXT_LENGTH) {
			throw new AppException(AppErrors.INCIDENT_NOTE_TEXT_INVALID);
		}
		if (request.authorId() != null && request.authorId().longValue() != author.id()) {
			throw new AppException(AppErrors.INCIDENT_NOTE_AUTHOR_MISMATCH);
		}
		if (!notes.existsIncident(incidentId)) {
			var publication = dashboards.publication();
			throw new DashboardIncidentNotFoundException(incidentId, publication.eventSeq(), publication.revision());
		}
		var observedAt = request.observedAt() == null ? clock.instant() : request.observedAt();
		String noteId = UUID.randomUUID().toString();
		OptionalLong eventSeq = events.append(incidentId, NOTE_ADDED, noteId,
				observedAt, Map.of("noteId", noteId, "authorId", author.id(), "text", text));
		if (eventSeq.isEmpty()) {
			// A freshly generated note id cannot repeat a recorded key; this is a broken writer.
			throw new IllegalStateException("Note event " + noteId + " already exists for " + incidentId);
		}
		long sequence = eventSeq.getAsLong();
		notes.insert(noteId, incidentId, author.id(), observedAt, text, sequence);
		return new IncidentNoteResponse(noteId, incidentId, author.id(), author.login(), author.displayName(),
				observedAt, sequence, text);
	}

	/**
	 * The note history of one INC, paged by the shared event sequence. The next shift reads
	 * every note with its author and observation time; only an INC unknown to the dashboard
	 * is a 404.
	 */
	@Transactional(readOnly = true)
	public IncidentNotesPage history(String incidentId, long afterEventSeq, int limit) {
		requireIncidentId(incidentId);
		if (afterEventSeq < 0) {
			throw new AppException(AppErrors.INVALID_EVENT_SEQ);
		}
		if (limit < 1 || limit > MAX_PAGE_SIZE) {
			throw new AppException(AppErrors.INVALID_LIMIT);
		}
		var rows = notes.incidentNotesAfterEventSeq(incidentId, afterEventSeq, limit + 1);
		boolean hasMore = rows.size() > limit;
		var page = rows.stream().limit(limit).toList();
		if (page.isEmpty() && !notes.existsIncident(incidentId)) {
			var publication = dashboards.publication();
			throw new DashboardIncidentNotFoundException(incidentId, publication.eventSeq(), publication.revision());
		}
		return new IncidentNotesPage(incidentId, page.stream().map(IncidentNoteService::note).toList(), hasMore,
				page.isEmpty() ? afterEventSeq : page.getLast().eventSeq());
	}

	private static void requireIncidentId(String incidentId) {
		if (incidentId == null || !INCIDENT_ID.matcher(incidentId).matches()) {
			throw new AppException(AppErrors.INVALID_INCIDENT_ID);
		}
	}

	private static IncidentNoteResponse note(NoteRow row) {
		return new IncidentNoteResponse(row.id(), row.incidentId(), row.coordinatorId(), row.authorLogin(),
				row.authorDisplayName(), row.observedAt(), row.eventSeq(), row.text());
	}
}
