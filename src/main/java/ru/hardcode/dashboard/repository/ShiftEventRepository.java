package ru.hardcode.dashboard.repository;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import ru.hardcode.dashboard.service.ShiftEventReason;

/**
 * Durable state of the KAN-25 shift event queue: the singleton detection cursor, the
 * per-source baseline revision and the deduplicated event rows.
 */
@Repository
public class ShiftEventRepository {

	private final JdbcTemplate jdbc;

	public ShiftEventRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/** Locks and reads the singleton detection cursor; the lock serializes concurrent processing. */
	public long lockProgress() {
		Long revision = jdbc.queryForObject(
				"SELECT last_revision FROM shift_event_progress WHERE singleton FOR UPDATE", Long.class);
		return revision == null ? 0 : revision;
	}

	/**
	 * Records the baseline revision of a source on first sight and returns the effective
	 * baseline; rows at or below it are the source's first full snapshot import and raise no
	 * shift signals.
	 */
	public long ensureBaseline(String sourceId, long revision) {
		jdbc.update("INSERT INTO shift_event_source (source_id, baseline_revision) VALUES (?, ?) "
				+ "ON CONFLICT (source_id) DO NOTHING", sourceId, revision);
		return jdbc.queryForObject("SELECT baseline_revision FROM shift_event_source WHERE source_id = ?",
				Long.class, sourceId);
	}

	/**
	 * Inserts one queue event with a sequence-generated eventId and does nothing when the same
	 * (source, incident, revision) change was already recorded — a replay of the same revision
	 * keeps the original eventId.
	 *
	 * @return true when a new event row was inserted
	 */
	public boolean insertEvent(String sourceId, long sourceVersion, String incidentId, long revision,
			List<ShiftEventReason> reasons, boolean ackRequired, String payloadJson) {
		String[] codes = reasons.stream().map(ShiftEventReason::code).toArray(String[]::new);
		String eventId = jdbc.query(connection -> {
			PreparedStatement statement = connection.prepareStatement("""
					INSERT INTO shift_event (event_id, source_id, incident_id, change_revision, source_version,
					                        reasons, ack_required, payload)
					VALUES ('evt-' || lpad(nextval('shift_event_id_seq')::text, 4, '0'), ?, ?, ?, ?, ?, ?, ?::jsonb)
					ON CONFLICT (source_id, incident_id, change_revision) DO NOTHING
					RETURNING event_id
					""");
			statement.setString(1, sourceId);
			statement.setString(2, incidentId);
			statement.setLong(3, revision);
			statement.setLong(4, sourceVersion);
			statement.setArray(5, connection.createArrayOf("text", codes));
			statement.setBoolean(6, ackRequired);
			statement.setString(7, payloadJson);
			return statement;
		}, rs -> rs.next() ? rs.getString(1) : null);
		return eventId != null;
	}

	/** Advances the cursor monotonically; a lost race never rewinds detection. */
	public void advanceProgress(long revision) {
		jdbc.update("UPDATE shift_event_progress SET last_revision = ? WHERE singleton AND last_revision < ?",
				revision, revision);
	}

	/** Queue events with {@code eventSeq > sinceEventSeq} in ascending order. */
	public List<StoredEvent> listEvents(long sinceEventSeq, int limit) {
		return jdbc.query("""
				SELECT id, event_id, incident_id, change_revision, source_id, reasons, ack_required,
				       payload, created_at
				FROM shift_event
				WHERE id > ?
				ORDER BY id
				LIMIT ?
				""", (rs, n) -> {
			var codes = rs.getArray("reasons");
			return new StoredEvent(
					rs.getLong("id"),
					rs.getString("event_id"),
					rs.getString("incident_id"),
					rs.getLong("change_revision"),
					rs.getString("source_id"),
					List.of((String[]) codes.getArray()),
					rs.getBoolean("ack_required"),
					rs.getString("payload"),
					rs.getObject("created_at", Timestamp.class).toInstant());
		}, sinceEventSeq, limit);
	}

	/** One stored queue event; {@code payload} stays the raw JSONB document of the notification data. */
	public record StoredEvent(long eventSeq, String eventId, String incidentId, long changeRevision,
			String sourceId, List<String> reasons, boolean ackRequired, String payload, Instant createdAt) {
	}
}
