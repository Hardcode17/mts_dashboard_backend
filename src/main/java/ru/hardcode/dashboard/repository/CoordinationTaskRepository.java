package ru.hardcode.dashboard.repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class CoordinationTaskRepository {

	private final JdbcClient jdbc;

	public CoordinationTaskRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public Optional<OpenTask> lock(long taskId) {
		return jdbc.sql("""
				SELECT t.id, t.incident_id, t.coordinator_id, t.accepted_at, i.priority_id,
				       i.present_in_source AND i.state IN ('Assigned', 'WorkInProgress') AS active
				FROM coordination_tasks t JOIN itsm_incident i ON i.id = t.incident_id
				WHERE t.id = :taskId AND t.status = 'open'
				FOR UPDATE OF t
				""").param("taskId", taskId).query(OpenTask.class).optional();
	}

	public List<Assignment> mine(long actor) {
		return jdbc.sql("""
				SELECT id, incident_id, coordinator_id, accepted_at
				FROM coordination_tasks WHERE status = 'open' AND coordinator_id = :actor
				ORDER BY id
				""").param("actor", actor).query(Assignment.class).list();
	}

	public Assignment claim(OpenTask task, long actor, OffsetDateTime now, OffsetDateTime acceptedAt) {
		jdbc.sql("""
				UPDATE coordination_tasks SET coordinator_id = :actor, accepted_at = :acceptedAt, updated_at = :now
				WHERE id = :taskId
				""").param("actor", actor).param("acceptedAt", acceptedAt).param("now", now)
				.param("taskId", task.id()).update();
		return new Assignment(task.id(), task.incidentId(), actor, acceptedAt);
	}

	public Optional<Window> window(long taskId, long viewer) {
		return jdbc.sql("""
				SELECT window_id, expires_at FROM coordination_task_window
				WHERE task_id = :taskId AND viewer_id = :viewer
				""").param("taskId", taskId).param("viewer", viewer).query(Window.class).optional();
	}

	public Window openWindow(long taskId, long viewer, OffsetDateTime expiresAt) {
		var window = new Window(UUID.randomUUID(), expiresAt);
		jdbc.sql("""
				INSERT INTO coordination_task_window(task_id, viewer_id, window_id, expires_at)
				VALUES (:taskId, :viewer, :windowId, :expiresAt)
				""").param("taskId", taskId).param("viewer", viewer).param("windowId", window.windowId())
				.param("expiresAt", expiresAt).update();
		return window;
	}

	public record Assignment(long id, String incidentId, Long coordinatorId, OffsetDateTime acceptedAt) {
	}

	public record OpenTask(long id, String incidentId, Long coordinatorId, OffsetDateTime acceptedAt,
			int priorityId, boolean active) {
		public Assignment assignment() {
			return new Assignment(id, incidentId, coordinatorId, acceptedAt);
		}
	}

	public record Window(UUID windowId, OffsetDateTime expiresAt) {
	}
}
