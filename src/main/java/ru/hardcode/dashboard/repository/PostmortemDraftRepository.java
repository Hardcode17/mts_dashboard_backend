package ru.hardcode.dashboard.repository;

import java.time.Instant;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import ru.hardcode.dashboard.dto.PostmortemDraftResponse.EvidenceFact;
import ru.hardcode.dashboard.dto.PostmortemDraftResponse.ShiftNote;
import ru.hardcode.dashboard.dto.PostmortemDraftResponse.TimelineFact;

@Repository
public class PostmortemDraftRepository {

	private final JdbcTemplate jdbc;

	public PostmortemDraftRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public record Resolution(long eventSeq, String incidentId, Instant observedAt, String text) {
	}

	public List<Resolution> resolutions(long revision) {
		return jdbc.query("""
				SELECT e.event_seq, e.incident_id, e.observed_at, e.after_data->>'solution' AS solution
				FROM itsm_incident_change e
				WHERE e.change_revision = ? AND e.change_kind = 'UPDATED'
				  AND e.before_data->>'stateEnum' IN ('Assigned', 'WorkInProgress', 'Research')
				  AND e.after_data->>'stateEnum' = 'Resolved'
				  AND NOT EXISTS (SELECT 1 FROM incident_postmortem_draft p WHERE p.resolution_event_seq = e.event_seq)
				ORDER BY e.event_seq
				""", (rs, n) -> new Resolution(rs.getLong(1), rs.getString(2),
				rs.getTimestamp(3).toInstant(), rs.getString(4)), revision);
	}

	public List<TimelineFact> timeline(Resolution resolution, String incidentPath) {
		return jdbc.query("""
				SELECT * FROM (
				    SELECT event_seq, observed_at, change_kind, before_data->>'stateEnum' AS before_state,
				           after_data->>'stateEnum' AS after_state
				    FROM itsm_incident_change
				    WHERE incident_id = ? AND event_seq <= ?
				    ORDER BY event_seq DESC LIMIT 100
				) selected ORDER BY event_seq
				""", (rs, n) -> new TimelineFact(rs.getLong(1), rs.getTimestamp(2).toInstant(),
				rs.getString(3), rs.getString(4), rs.getString(5), incidentPath + "/history"),
				resolution.incidentId(), resolution.eventSeq());
	}

	public List<ShiftNote> notes(Resolution resolution, String incidentPath) {
		if (!hasTable("incident_note")) {
			return List.of();
		}
		return jdbc.query("""
				SELECT id, event_seq, observed_at, note_text
				FROM (SELECT id, event_seq, observed_at, note_text FROM incident_note
				      WHERE incident_id = ? AND event_seq < ?
				      ORDER BY event_seq DESC LIMIT 100) selected
				ORDER BY event_seq
				""", (rs, n) -> new ShiftNote(rs.getString(1), rs.getLong(2), rs.getTimestamp(3).toInstant(),
				rs.getString(4), incidentPath + "/notes?afterEventSeq=" + (rs.getLong(2) - 1) + "&limit=1"),
				resolution.incidentId(), resolution.eventSeq());
	}

	public Optional<UUID> latestRun(Resolution resolution) {
		if (!hasTable("ai_investigation_run")) {
			return Optional.empty();
		}
		return jdbc.query("""
				SELECT run_id FROM ai_investigation_run WHERE incident_id = ? AND created_at <= ?
				ORDER BY created_at DESC, run_id DESC LIMIT 1
				""", (rs, n) -> rs.getObject(1, UUID.class),
				resolution.incidentId(), Timestamp.from(resolution.observedAt())).stream().findFirst();
	}

	public List<EvidenceFact> evidence(UUID run, Resolution resolution) {
		String href = "/api/incidents/" + resolution.incidentId() + "/investigations/" + run;
		return jdbc.query("""
				SELECT evidence_id, source, service_key, observed_at, message, value, record_count, service_state
				FROM ai_investigation_fact WHERE run_id = ? AND observed_at <= ?
				ORDER BY fact_index LIMIT 100
				""", (rs, n) -> new EvidenceFact(run, rs.getString(1), rs.getString(2), rs.getString(3),
				rs.getTimestamp(4).toInstant(), rs.getString(5), rs.getObject(6, Long.class),
				rs.getObject(7, Long.class), rs.getString(8), href), run, Timestamp.from(resolution.observedAt()));
	}

	public List<String> unavailableSources(UUID run) {
		return jdbc.queryForList("""
				SELECT DISTINCT source FROM ai_investigation_unavailable_source WHERE run_id = ? ORDER BY source
				""", String.class, run);
	}

	public void insert(Resolution resolution, String draft) {
		jdbc.update("""
				INSERT INTO incident_postmortem_draft(resolution_event_seq, incident_id, draft)
				VALUES (?, ?, ?::jsonb) ON CONFLICT (resolution_event_seq) DO NOTHING
				""", resolution.eventSeq(), resolution.incidentId(), draft);
	}

	public Optional<String> latest(String incidentId) {
		return jdbc.queryForList("""
				SELECT draft::text FROM incident_postmortem_draft WHERE incident_id = ?
				ORDER BY resolution_event_seq DESC LIMIT 1
				""", String.class, incidentId).stream().findFirst();
	}

	private boolean hasTable(String name) {
		return Boolean.TRUE.equals(jdbc.queryForObject("SELECT to_regclass(?) IS NOT NULL", Boolean.class, name));
	}
}
