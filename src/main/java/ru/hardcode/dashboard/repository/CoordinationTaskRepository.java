package ru.hardcode.dashboard.repository;

import java.time.Instant;
import java.sql.Timestamp;
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

	public void observeHandoffs(String sourceId, long version, String snapshot, String group, Instant observedAt) {
		Timestamp time = Timestamp.from(observedAt);
		jdbc.update("""
				WITH incoming AS (
				    SELECT doc->>'id' AS id, nullif(btrim(doc->>'assignedGroupName'), '') AS team,
				           nullif(btrim(doc->>'assignedEmployeeLogin'), '') AS login,
				           nullif(btrim(doc->>'assignedEmployee'), '') AS employee
				    FROM jsonb_array_elements(?::jsonb) doc
				), transfers AS (
				    SELECT incoming.id,
				           CASE WHEN incoming.team IS DISTINCT FROM old.team THEN 'TEAM'
				                ELSE 'EXECUTOR' END AS kind
				    FROM incoming JOIN coordination_official_assignment old
				      ON old.source_id = ? AND old.incident_id = incoming.id
				    WHERE old.team = ? AND incoming.team IS NOT NULL AND (
				        incoming.team IS DISTINCT FROM old.team OR
				        (incoming.login IS NOT NULL AND incoming.login IS DISTINCT FROM old.login) OR
				        (incoming.login IS NULL AND incoming.employee IS NOT NULL
				         AND incoming.employee IS DISTINCT FROM old.employee))
				)
				UPDATE coordination_tasks task
				SET status='completed', finished_at=?, updated_at=?, handoff_source_id=?,
				    handoff_source_version=?, handoff_observed_at=?, handoff_type=transfers.kind
				FROM transfers WHERE task.incident_id=transfers.id AND task.status='open'
				""", snapshot, sourceId, group, time, time, sourceId, version, time);
		jdbc.update("""
				INSERT INTO coordination_official_assignment(source_id, incident_id, team, login, employee)
				SELECT ?, doc->>'id', nullif(btrim(doc->>'assignedGroupName'), ''),
				       nullif(btrim(doc->>'assignedEmployeeLogin'), ''),
				       nullif(btrim(doc->>'assignedEmployee'), '')
				FROM jsonb_array_elements(?::jsonb) doc
				ON CONFLICT (source_id, incident_id) DO UPDATE
				SET team=excluded.team, login=excluded.login, employee=excluded.employee
				""", sourceId, snapshot);
	}

	public List<Task> listOpen() {
		return jdbc.query("""
				SELECT id, incident_id, created_at FROM coordination_tasks
				WHERE status = 'open' AND coordinator_id IS NULL ORDER BY created_at, id
				""", (rs, n) -> new Task(rs.getLong("id"), rs.getString("incident_id"),
				rs.getTimestamp("created_at").toInstant()));
	}

	public record Task(long taskId, String incidentId, Instant createdAt) {
	}
}
