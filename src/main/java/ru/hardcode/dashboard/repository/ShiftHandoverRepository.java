package ru.hardcode.dashboard.repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import ru.hardcode.dashboard.dto.ShiftHandoverResponse.Item;
import ru.hardcode.dashboard.dto.ShiftHandoverResponse.Recipient;

@Repository
public class ShiftHandoverRepository {

	private static final String ITEM_FIELDS = """
			t.id AS task_id, i.id AS incident_id, i.priority_id AS priority, i.deadline,
			coalesce(i.source_data->>'header', i.id) AS title,
			i.source_data->>'assignedGroupName' AS team
			""";
	private final JdbcClient jdbc;

	public ShiftHandoverRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public void lockSender(long sender) {
		jdbc.sql("SELECT id FROM users WHERE id = :sender FOR UPDATE")
				.param("sender", sender).query(Long.class).single();
	}

	public List<Recipient> recipients(long sender) {
		return jdbc.sql("""
				SELECT DISTINCT u.id, u.display_name AS name FROM coordinator_group_members mine
				JOIN coordinator_groups g ON g.id = mine.coordinator_group_id AND g.is_active
				JOIN coordinator_group_members others ON others.coordinator_group_id = g.id AND others.is_active
				JOIN users u ON u.id = others.user_id AND u.is_active
				WHERE mine.user_id = :sender AND mine.is_active AND u.id <> :sender
				ORDER BY name, u.id
				""").param("sender", sender).query(Recipient.class).list();
	}

	public List<Item> owned(long owner) {
		return jdbc.sql("""
				SELECT %s, NULL::text AS situation_key, NULL::timestamptz AS claimed_at, NOT EXISTS (
				    SELECT 1 FROM shift_handover_item h WHERE h.task_id = t.id AND h.claimed_at IS NULL
					      AND h.revoked_at IS NULL AND h.task_updated_at = t.updated_at
				) AS available
				FROM coordination_tasks t JOIN itsm_incident i ON i.id = t.incident_id
				WHERE t.coordinator_id = :owner AND t.status = 'open' AND i.present_in_source
				ORDER BY i.priority_id DESC, i.deadline NULLS LAST, t.id
				""".formatted(ITEM_FIELDS)).param("owner", owner).query(Item.class).list();
	}

	public Optional<Header> replay(long sender, UUID requestId) {
		return jdbc.sql("""
				SELECT h.id, h.sender_id, s.display_name AS sender_name, h.recipient_id,
				       r.display_name AS recipient_name, h.created_at, h.summary
				FROM shift_handover h JOIN users s ON s.id = h.sender_id JOIN users r ON r.id = h.recipient_id
				WHERE h.sender_id = :sender AND h.request_id = :request
				""").param("sender", sender).param("request", requestId).query(Header.class).optional();
	}

	public List<Header> headers(long actor) {
		return jdbc.sql("""
				SELECT h.id, h.sender_id, s.display_name AS sender_name, h.recipient_id,
				       r.display_name AS recipient_name, h.created_at, h.summary
				FROM shift_handover h JOIN users s ON s.id = h.sender_id JOIN users r ON r.id = h.recipient_id
				WHERE h.sender_id = :actor OR h.recipient_id = :actor ORDER BY h.id DESC
				""").param("actor", actor).query(Header.class).list();
	}

	public List<Item> items(long handover) {
		return jdbc.sql("""
				SELECT %s, a.situation_key, a.claimed_at,
				       t.status = 'open' AND i.present_in_source AND t.coordinator_id = h.sender_id
				           AND a.claimed_at IS NULL AND a.revoked_at IS NULL
					       AND a.task_updated_at = t.updated_at AS available
				FROM shift_handover_item a JOIN shift_handover h ON h.id = a.handover_id
				JOIN coordination_tasks t ON t.id = a.task_id JOIN itsm_incident i ON i.id = t.incident_id
				WHERE h.id = :handover ORDER BY i.priority_id DESC, i.deadline NULLS LAST, t.id
				""".formatted(ITEM_FIELDS)).param("handover", handover).query(Item.class).list();
	}

	public List<Long> lockOwned(List<Long> ids, long owner) {
		return jdbc.sql("""
				SELECT t.id FROM coordination_tasks t JOIN itsm_incident i ON i.id = t.incident_id
				WHERE t.id IN (:ids) AND t.coordinator_id = :owner AND t.status = 'open' AND i.present_in_source
				ORDER BY t.id FOR UPDATE OF t
				""").param("ids", ids).param("owner", owner).query(Long.class).list();
	}

	public boolean hasPending(List<Long> ids) {
		return jdbc.sql("""
				SELECT EXISTS (SELECT 1 FROM shift_handover_item WHERE task_id IN (:ids) AND claimed_at IS NULL AND revoked_at IS NULL)
				""").param("ids", ids).query(Boolean.class).single();
	}

	public void revokeStale(List<Long> tasks, OffsetDateTime now) {
		jdbc.sql("""
				UPDATE shift_handover_item a SET revoked_at = :now
				FROM coordination_tasks t, shift_handover h
				WHERE t.id = a.task_id AND h.id = a.handover_id AND a.task_id IN (:tasks)
				  AND a.claimed_at IS NULL AND a.revoked_at IS NULL
				  AND (t.coordinator_id IS DISTINCT FROM h.sender_id OR a.task_updated_at <> t.updated_at)
				""").param("now", now).param("tasks", tasks).update();
	}

	public boolean anyOutsideGroups(List<Long> tasks, long recipient) {
		return jdbc.sql("""
				SELECT EXISTS (SELECT 1 FROM coordination_task_assignments a
				    WHERE a.coordination_task_id IN (:tasks) AND a.ended_at IS NULL
				      AND NOT EXISTS (
				          SELECT 1 FROM coordinator_group_members m JOIN coordinator_groups g
				            ON g.id = m.coordinator_group_id AND g.is_active
				          WHERE g.id = a.coordinator_group_id AND m.user_id = :recipient AND m.is_active))
				""").param("tasks", tasks).param("recipient", recipient).query(Boolean.class).single();
	}

	public long create(long sender, long recipient, UUID request, Map<Long, String> situations,
			String summary, OffsetDateTime now) {
		long id = jdbc.sql("""
				INSERT INTO shift_handover(sender_id,recipient_id,request_id,summary,created_at)
				VALUES (:sender,:recipient,:request,:summary,:now) RETURNING id
				""").param("sender", sender).param("recipient", recipient).param("request", request)
				.param("summary", summary).param("now", now).query(Long.class).single();
		var tasks = situations.keySet().toArray(Long[]::new);
		var groups = situations.values().toArray(String[]::new);
		jdbc.sql("""
				INSERT INTO shift_handover_item(handover_id, task_id, situation_key, task_updated_at)
				SELECT :id, t.id, chosen.situation_key, t.updated_at
				FROM unnest(CAST(:tasks AS bigint[]), CAST(:groups AS text[])) AS chosen(task_id, situation_key)
				JOIN coordination_tasks t ON t.id = chosen.task_id
				""").param("id", id).param("tasks", tasks).param("groups", groups).update();
		return id;
	}

	public List<Claim> lockSituation(long handover, long task) {
		return jdbc.sql("""
				SELECT t.id AS task_id, h.sender_id, h.recipient_id, a.claimed_at, t.coordinator_id,
				       t.status = 'open' AND i.present_in_source AND a.revoked_at IS NULL
				         AND a.task_updated_at = t.updated_at AS available
				FROM shift_handover h JOIN shift_handover_item a ON a.handover_id = h.id
				JOIN coordination_tasks t ON t.id = a.task_id JOIN itsm_incident i ON i.id = t.incident_id
				WHERE h.id = :handover AND a.situation_key = (
				    SELECT situation_key FROM shift_handover_item WHERE handover_id = :handover AND task_id = :task)
				ORDER BY t.id FOR UPDATE OF t, a
				""").param("handover", handover).param("task", task).query(Claim.class).list();
	}

	public void accept(long handover, List<Long> tasks, long recipient, OffsetDateTime now) {
		jdbc.sql("""
				UPDATE coordination_tasks SET coordinator_id = :recipient, accepted_at = :now, updated_at = :now
				WHERE id IN (:tasks)
				""").param("recipient", recipient).param("tasks", tasks).param("now", now).update();
		jdbc.sql("DELETE FROM coordination_task_window WHERE task_id IN (:tasks)").param("tasks", tasks).update();
		jdbc.sql("UPDATE shift_handover_item SET claimed_at = :now WHERE handover_id = :handover AND task_id IN (:tasks)")
				.param("now", now).param("handover", handover).param("tasks", tasks).update();
	}

	public record Header(long id, long senderId, String senderName, long recipientId, String recipientName,
			OffsetDateTime createdAt, String summary) {
	}

	public record Claim(long taskId, long senderId, long recipientId, OffsetDateTime claimedAt, Long coordinatorId,
			boolean available) {
	}
}
