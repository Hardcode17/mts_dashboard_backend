package ru.hardcode.dashboard.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import ru.hardcode.dashboard.dto.NotificationConnectionRequest;
import ru.hardcode.dashboard.dto.NotificationConnectionResponse;
import ru.hardcode.dashboard.service.NotificationChannelAdapter.Destination;

@Repository
public class NotificationConnectionRepository {

	private static final String SETTINGS = """
			SELECT c.id, t.code, c.connection_metadata ->> 'address' AS address, c.is_active,
				ARRAY(SELECT p.priority_level_id FROM notification_connection_priorities p
					WHERE p.notification_connection_id = c.id ORDER BY p.priority_level_id) AS priority_ids
			FROM notification_connections c
			JOIN notification_types t ON t.id = c.notification_type_id
			WHERE c.user_id = ?
			""";

	private final JdbcClient jdbc;

	public NotificationConnectionRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public Optional<Long> findTypeId(String code) {
		return jdbc.sql("SELECT id FROM notification_types WHERE code = ?")
				.param(code).query(Long.class).optional();
	}

	public List<NotificationConnectionResponse> findAll(long userId) {
		return settings(SETTINGS + "ORDER BY c.id", userId).list();
	}

	public Optional<NotificationConnectionResponse> find(long userId, long id) {
		return settings(SETTINGS + "AND c.id = ?", userId, id).optional();
	}

	public long create(long userId, long typeId, NotificationConnectionRequest request) {
		return jdbc.sql("""
				INSERT INTO notification_connections (user_id, notification_type_id, connection_metadata, is_active)
				VALUES (?, ?, jsonb_strip_nulls(jsonb_build_object('address', CAST(? AS text),
					'secretRef', CAST(? AS text))), ?)
				RETURNING id
				""").param(userId).param(typeId).param(request.address()).param(request.secretRef())
				.param(request.active()).query(Long.class).single();
	}

	public boolean update(long userId, long id, long typeId, NotificationConnectionRequest request) {
		return jdbc.sql("""
				UPDATE notification_connections
				SET notification_type_id = ?, is_active = ?, updated_at = now(),
					connection_metadata = jsonb_strip_nulls(jsonb_build_object('address', CAST(? AS text),
						'secretRef', CASE WHEN ? OR notification_type_id <> ? THEN CAST(? AS text)
							ELSE connection_metadata ->> 'secretRef' END))
				WHERE user_id = ? AND id = ?
				""").param(typeId).param(request.active()).param(request.address()).param(request.secretRefProvided())
				.param(typeId).param(request.secretRef()).param(userId).param(id).update() == 1;
	}

	public void replacePriorities(long id, List<Integer> priorities) {
		jdbc.sql("DELETE FROM notification_connection_priorities WHERE notification_connection_id = ?")
				.param(id).update();
		for (int priorityId : priorities) {
			jdbc.sql("""
					INSERT INTO notification_connection_priorities (notification_connection_id, priority_level_id)
					VALUES (?, ?)
					""").param(id).param(priorityId).update();
		}
	}

	public List<Destination> findDestinations(long userId, int priorityId) {
		return jdbc.sql("""
				SELECT c.id, t.code, c.connection_metadata ->> 'address' AS address,
					c.connection_metadata ->> 'secretRef' AS secret_ref
				FROM notification_connections c
				JOIN notification_types t ON t.id = c.notification_type_id
				JOIN users u ON u.id = c.user_id
				JOIN notification_connection_priorities p ON p.notification_connection_id = c.id
				WHERE c.user_id = ? AND u.is_active AND c.is_active AND p.priority_level_id = ?
				ORDER BY c.id
				""").param(userId).param(priorityId)
				.query((row, index) -> new Destination(row.getLong("id"), row.getString("code"),
						row.getString("address"), row.getString("secret_ref")))
				.list();
	}

	private JdbcClient.MappedQuerySpec<NotificationConnectionResponse> settings(String sql, Object... params) {
		return jdbc.sql(sql).params(params).query((row, index) -> {
			var priorities = row.getArray("priority_ids");
			try {
				return new NotificationConnectionResponse(row.getLong("id"), row.getString("code"),
						row.getString("address"), row.getBoolean("is_active"),
						List.of((Integer[]) priorities.getArray()));
			} finally {
				priorities.free();
			}
		});
	}
}
