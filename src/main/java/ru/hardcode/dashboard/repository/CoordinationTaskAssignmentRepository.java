package ru.hardcode.dashboard.repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import ru.hardcode.dashboard.dto.CoordinationTaskAssignmentResponse;

@Repository
public class CoordinationTaskAssignmentRepository {

	private final JdbcClient jdbc;

	public CoordinationTaskAssignmentRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public Optional<Assignment> active(long task) {
		return jdbc.sql("""
				SELECT id, coordinator_group_id FROM coordination_task_assignments
				WHERE coordination_task_id = :task AND ended_at IS NULL
				""").param("task", task).query(Assignment.class).optional();
	}

	public boolean isMember(long group, long actor) {
		return jdbc.sql("""
				SELECT EXISTS (SELECT 1 FROM coordinator_group_members m
				    JOIN coordinator_groups g ON g.id = m.coordinator_group_id
				    WHERE g.id = :group AND m.user_id = :actor AND m.is_active AND g.is_active)
				""").param("group", group).param("actor", actor).query(Boolean.class).single();
	}

	public boolean canReadHistory(long task, long actor) {
		return jdbc.sql("""
				SELECT EXISTS (SELECT 1 FROM coordination_task_assignments a
				    JOIN coordinator_group_members m ON m.coordinator_group_id = a.coordinator_group_id
				    JOIN coordinator_groups g ON g.id = m.coordinator_group_id
				    WHERE a.coordination_task_id = :task AND m.user_id = :actor AND m.is_active AND g.is_active)
				   OR EXISTS (SELECT 1 FROM coordination_tasks t WHERE t.id = :task AND t.coordinator_id = :actor)
				""").param("task", task).param("actor", actor).query(Boolean.class).single();
	}

	public boolean lockActiveGroup(long group) {
		return jdbc.sql("SELECT id FROM coordinator_groups WHERE id = :group AND is_active FOR SHARE")
				.param("group", group).query(Long.class).optional().isPresent();
	}

	public long start(long task, long group, long actor, OffsetDateTime now) {
		return jdbc.sql("""
				INSERT INTO coordination_task_assignments(coordination_task_id, coordinator_group_id,
				    assigned_by_user_id, assigned_at, status)
				VALUES (:task, :group, :actor, :now, 'assigned') RETURNING id
				""").param("task", task).param("group", group).param("actor", actor).param("now", now)
				.query(Long.class).single();
	}

	public void closeForRedirect(long assignment, long actor, String reason, OffsetDateTime now) {
		jdbc.sql("""
				UPDATE coordination_task_assignments
				SET status = 'redirected', ended_at = greatest(assigned_at, :now),
				    redirected_by_user_id = :actor, redirect_reason = :reason
				WHERE id = :assignment
				""").param("assignment", assignment).param("actor", actor).param("reason", reason).param("now", now)
				.update();
	}

	public void release(long task, OffsetDateTime now) {
		jdbc.sql("""
				UPDATE coordination_tasks SET coordinator_id = NULL, accepted_at = NULL, updated_at = :now
				WHERE id = :task
				""").param("task", task).param("now", now).update();
		jdbc.sql("DELETE FROM coordination_task_window WHERE task_id = :task").param("task", task).update();
	}

	public List<CoordinationTaskAssignmentResponse> history(long task) {
		return jdbc.sql("""
				SELECT a.id AS assignment_id, g.id AS group_id, g.name AS group_name, a.status,
				       a.assigned_by_user_id, a.assigned_at, a.ended_at, a.redirected_by_user_id, a.redirect_reason
				FROM coordination_task_assignments a JOIN coordinator_groups g ON g.id = a.coordinator_group_id
				WHERE a.coordination_task_id = :task ORDER BY a.id
				""").param("task", task).query(CoordinationTaskAssignmentResponse.class).list();
	}

	public record Assignment(long id, long coordinatorGroupId) {
	}
}
