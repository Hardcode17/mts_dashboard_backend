package ru.hardcode.dashboard.repository;

import java.time.Instant;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class CoordinationTaskRepository {

	private final JdbcTemplate jdbc;

	public CoordinationTaskRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/** Shared insertion point for observed entries and future official transfers (KAN-32). */
	public boolean insertOpen(String incidentId) {
		return jdbc.update("""
				INSERT INTO coordination_tasks (incident_id) VALUES (?)
				ON CONFLICT (incident_id) WHERE status = 'open' DO NOTHING
				""", incidentId) == 1;
	}

	public List<String> enteredIncidents(String sourceId, long revision, String group) {
		return jdbc.queryForList("""
				SELECT incident_id FROM itsm_incident_change
				WHERE source_id = ? AND change_revision = ?
				  AND change_kind IN ('ADDED', 'RETURNED', 'UPDATED')
				  AND after_data->>'assignedGroupName' = ?
				  AND (change_kind <> 'UPDATED'
				       OR before_data->>'assignedGroupName' IS DISTINCT FROM ?)
				ORDER BY incident_id
				""", String.class, sourceId, revision, group, group);
	}

	public List<Task> listOpen() {
		// KAN-85: add AND coordinator_id IS NULL here after V14 introduces ownership.
		return jdbc.query("""
				SELECT id, incident_id, created_at FROM coordination_tasks
				WHERE status = 'open' ORDER BY created_at, id
				""", (rs, n) -> new Task(rs.getLong("id"), rs.getString("incident_id"),
				rs.getTimestamp("created_at").toInstant()));
	}

	public record Task(long taskId, String incidentId, Instant createdAt) {
	}
}
