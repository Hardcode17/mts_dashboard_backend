package ru.hardcode.dashboard.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Ordered reads of the durable INC change journal (KAN-28). The monotonic {@code event_seq}
 * is independent of the snapshot change revision, so consumers page by event sequence even
 * when several events share one observation time or one revision.
 */
@Repository
public class ItsmIncidentChangeRepository {

	private static final String COLUMNS = """
			SELECT event_seq, source_id, incident_id, source_version, change_revision, change_kind,
			       observed_at, source_time, before_data, after_data
			FROM itsm_incident_change
			""";

	private final JdbcTemplate jdbc;

	public ItsmIncidentChangeRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public List<JournalRow> afterEventSeq(long afterEventSeq, int limit) {
		return jdbc.query(COLUMNS + " WHERE event_seq > ? ORDER BY event_seq LIMIT ?",
				ItsmIncidentChangeRepository::row, afterEventSeq, limit);
	}

	public List<JournalRow> incidentHistory(String incidentId) {
		return jdbc.query(COLUMNS + " WHERE incident_id = ? ORDER BY event_seq",
				ItsmIncidentChangeRepository::row, incidentId);
	}

	public boolean existsIncident(String incidentId) {
		return Boolean.TRUE.equals(jdbc.queryForObject(
				"SELECT EXISTS (SELECT 1 FROM itsm_incident WHERE id = ?)", Boolean.class, incidentId));
	}

	private static JournalRow row(ResultSet rs, int rowNum) throws SQLException {
		return new JournalRow(rs.getLong("event_seq"), rs.getString("source_id"), rs.getString("incident_id"),
				rs.getLong("source_version"), rs.getLong("change_revision"), rs.getString("change_kind"),
				instant(rs, "observed_at"), instant(rs, "source_time"),
				rs.getString("before_data"), rs.getString("after_data"));
	}

	private static Instant instant(ResultSet rs, String column) throws SQLException {
		Timestamp value = rs.getTimestamp(column);
		return value == null ? null : value.toInstant();
	}

	public record JournalRow(long eventSeq, String sourceId, String incidentId, long sourceVersion,
			long changeRevision, String changeKind, Instant observedAt, Instant sourceTime,
			String beforeData, String afterData) {
	}
}
