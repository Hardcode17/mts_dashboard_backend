package ru.hardcode.dashboard.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Ordered reads of the KAN-43 note journal. The monotonic {@code event_seq} is shared with the
 * KAN-28 INC change journal, so notes and ITSM changes interleave in one chronology; a page
 * orders by {@code event_seq}, therefore parallel notes with one observation time still get
 * distinct positions and one cursor read returns both.
 */
@Repository
public class IncidentNoteRepository {

	private static final String COLUMNS = """
			SELECT note.id, note.incident_id, note.coordinator_id, author.login AS author_login,
			       author.display_name AS author_display_name, note.observed_at, note.note_text,
			       note.event_seq, note.created_at
			FROM incident_note AS note
			JOIN users AS author ON author.id = note.coordinator_id
			""";

	private final JdbcTemplate jdbc;

	public IncidentNoteRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public void insert(String noteId, String incidentId, long authorId, Instant observedAt, String text,
			long eventSeq) {
		jdbc.update("""
				INSERT INTO incident_note (id, incident_id, coordinator_id, observed_at, note_text, event_seq)
				VALUES (?, ?, ?, ?, ?, ?)
				""", noteId, incidentId, authorId, Timestamp.from(observedAt), text, eventSeq);
	}

	public List<NoteRow> incidentNotesAfterEventSeq(String incidentId, long afterEventSeq, int limit) {
		return jdbc.query(COLUMNS + """
				WHERE note.incident_id = ? AND note.event_seq > ?
				ORDER BY note.event_seq
				LIMIT ?
				""", IncidentNoteRepository::row, incidentId, afterEventSeq, limit);
	}

	/** An incident that disappeared from the source stays known and keeps its history readable. */
	public boolean existsIncident(String incidentId) {
		return Boolean.TRUE.equals(jdbc.queryForObject(
				"SELECT EXISTS (SELECT 1 FROM itsm_incident WHERE id = ?)", Boolean.class, incidentId));
	}

	private static NoteRow row(ResultSet rs, int rowNum) throws SQLException {
		return new NoteRow(rs.getString("id"), rs.getString("incident_id"), rs.getLong("coordinator_id"),
				rs.getString("author_login"), rs.getString("author_display_name"),
				instant(rs, "observed_at"), rs.getString("note_text"), rs.getLong("event_seq"),
				instant(rs, "created_at"));
	}

	private static Instant instant(ResultSet rs, String column) throws SQLException {
		Timestamp value = rs.getTimestamp(column);
		return value == null ? null : value.toInstant();
	}

	public record NoteRow(String id, String incidentId, long coordinatorId, String authorLogin,
			String authorDisplayName, Instant observedAt, String text, long eventSeq, Instant createdAt) {
	}
}
