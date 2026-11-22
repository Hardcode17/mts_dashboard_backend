package ru.hardcode.dashboard.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import ru.hardcode.dashboard.dto.DashboardNotificationResponse;
import ru.hardcode.dashboard.dto.NotificationDeliveryResponse;
import ru.hardcode.dashboard.service.NotificationChannelAdapter;
import ru.hardcode.dashboard.service.NotificationChannelAdapter.Destination;

/**
 * Durable notification deliveries of KAN-35: one {@code notification} row per recipient per
 * event and one {@code notification_delivery} row per allowed connection, written in the
 * caller's transaction. Reads return DTOs directly, like the connection settings of KAN-36.
 */
@Repository
public class NotificationDeliveryRepository {

	/** Outcome of one delivery attempt, persisted with the attempt timestamp. */
	public record DeliveryOutcome(String state, String failureReason, boolean accepted, boolean delivered,
			boolean readInChannel) {

		public static DeliveryOutcome from(NotificationChannelAdapter.Signal signal) {
			return switch (signal) {
				case SENT -> new DeliveryOutcome("sent", null, true, false, false);
				case DELIVERED -> new DeliveryOutcome("delivered", null, true, true, false);
				case READ_IN_CHANNEL -> new DeliveryOutcome("read_in_channel", null, true, true, true);
				case UNKNOWN -> new DeliveryOutcome("unknown", null, false, false, false);
			};
		}

		public static DeliveryOutcome failed(String reason) {
			String sanitized = reason == null ? "channel_error" : reason.trim();
			if (sanitized.length() > 200) {
				sanitized = sanitized.substring(0, 200);
			}
			return new DeliveryOutcome("failed", sanitized, false, false, false);
		}
	}

	/** One allowed connection of one recipient. */
	public record RecipientDestination(long userId, Destination destination) {
	}

	/** Locked delivery row of one recipient, with the notification text needed for a retry. */
	public record LockedDelivery(long id, long connectionId, String channel, String state, String failureReason,
			int attempts, String title, String body) {
	}

	private static final String DELIVERY_COLUMNS = """
			d.id, d.notification_id, d.connection_id, d.channel, d.state, d.failure_reason, d.attempts,
			d.sent_at, d.delivered_at, d.read_in_channel_at, d.read_in_app_at, d.last_attempt_at
			""";
	private static final String NOTIFICATION_COLUMNS = """
			id, incident_id, priority_level_id, title, body, read_in_app_at, created_at
			""";

	private final JdbcClient jdbc;

	public NotificationDeliveryRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public Optional<Long> createNotification(String eventKey, String incidentId, Integer priorityLevelId,
			long recipientUserId, String title, String body) {
		return jdbc.sql("""
				INSERT INTO notification (event_key, incident_id, priority_level_id, recipient_user_id, title, body)
				VALUES (?, ?, ?, ?, ?, ?)
				ON CONFLICT (event_key, recipient_user_id) DO NOTHING
				RETURNING id
				""").param(eventKey).param(incidentId).param(priorityLevelId).param(recipientUserId)
				.param(title).param(body).query(Long.class).optional();
	}

	public long insertDelivery(long notificationId, long connectionId, String channel, DeliveryOutcome outcome) {
		return jdbc.sql("""
				INSERT INTO notification_delivery (notification_id, connection_id, channel, state, failure_reason,
					attempts, sent_at, delivered_at, read_in_channel_at, last_attempt_at)
				VALUES (?, ?, ?, ?, ?, 1, CASE WHEN ? THEN now() END, CASE WHEN ? THEN now() END,
					CASE WHEN ? THEN now() END, now())
				RETURNING id
				""").param(notificationId).param(connectionId).param(channel).param(outcome.state())
				.param(outcome.failureReason()).param(outcome.accepted()).param(outcome.delivered())
				.param(outcome.readInChannel()).query(Long.class).single();
	}

	public void recordAttempt(long deliveryId, DeliveryOutcome outcome) {
		jdbc.sql("""
				UPDATE notification_delivery
				SET state = ?, failure_reason = ?, attempts = attempts + 1, updated_at = now(),
					sent_at = CASE WHEN ? THEN now() ELSE sent_at END,
					delivered_at = CASE WHEN ? THEN now() ELSE delivered_at END,
					read_in_channel_at = CASE WHEN ? THEN now() ELSE read_in_channel_at END,
					last_attempt_at = now()
				WHERE id = ?
				""").param(outcome.state()).param(outcome.failureReason()).param(outcome.accepted())
				.param(outcome.delivered()).param(outcome.readInChannel()).param(deliveryId).update();
	}

	public List<RecipientDestination> findPriorityDestinations(int priorityLevelId) {
		return jdbc.sql("""
				SELECT c.user_id, c.id AS connection_id, t.code AS channel,
					c.connection_metadata ->> 'address' AS address,
					c.connection_metadata ->> 'secretRef' AS secret_ref
				FROM notification_connections c
				JOIN users u ON u.id = c.user_id
				JOIN notification_types t ON t.id = c.notification_type_id
				JOIN notification_connection_priorities p ON p.notification_connection_id = c.id
				WHERE u.is_active AND c.is_active AND p.priority_level_id = ?
				ORDER BY c.user_id, c.id
				""").param(priorityLevelId).query((row, index) -> new RecipientDestination(row.getLong("user_id"),
				new Destination(row.getLong("connection_id"), row.getString("channel"),
						row.getString("address"), row.getString("secret_ref")))).list();
	}

	public boolean isActiveUser(long userId) {
		return jdbc.sql("SELECT is_active FROM users WHERE id = ?").param(userId)
				.query(Boolean.class).optional().orElse(false);
	}

	public List<DashboardNotificationResponse> findNotifications(long userId) {
		Map<Long, List<NotificationDeliveryResponse>> deliveries = new LinkedHashMap<>();
		for (var owned : findDeliveries(jdbc.sql("""
				SELECT %s FROM notification_delivery d
				JOIN notification n ON n.id = d.notification_id
				WHERE n.recipient_user_id = ? ORDER BY d.id
				""".formatted(DELIVERY_COLUMNS)).param(userId))) {
			deliveries.computeIfAbsent(owned.notificationId(), key -> new ArrayList<>()).add(owned.delivery());
		}
		return jdbc.sql("""
				SELECT %s FROM notification WHERE recipient_user_id = ? ORDER BY id DESC
				""".formatted(NOTIFICATION_COLUMNS)).param(userId)
				.query((row, index) -> notification(row, deliveries.get(row.getLong("id")))).list();
	}

	public Optional<DashboardNotificationResponse> findNotification(long userId, long notificationId) {
		Map<Long, List<NotificationDeliveryResponse>> deliveries = new LinkedHashMap<>();
		for (var owned : findDeliveries(jdbc.sql("""
				SELECT %s FROM notification_delivery d
				JOIN notification n ON n.id = d.notification_id
				WHERE n.recipient_user_id = ? AND n.id = ? ORDER BY d.id
				""".formatted(DELIVERY_COLUMNS)).param(userId).param(notificationId))) {
			deliveries.computeIfAbsent(owned.notificationId(), key -> new ArrayList<>()).add(owned.delivery());
		}
		return jdbc.sql("""
				SELECT %s FROM notification WHERE recipient_user_id = ? AND id = ?
				""".formatted(NOTIFICATION_COLUMNS)).param(userId).param(notificationId)
				.query((row, index) -> notification(row, deliveries.get(row.getLong("id")))).optional();
	}

	public boolean markReadInApp(long userId, long notificationId) {
		return jdbc.sql("""
				UPDATE notification SET read_in_app_at = COALESCE(read_in_app_at, now())
				WHERE id = ? AND recipient_user_id = ?
				""").param(notificationId).param(userId).update() == 1;
	}

	public void markDeliveriesReadInApp(long notificationId) {
		jdbc.sql("""
				UPDATE notification_delivery
				SET read_in_app_at = COALESCE(read_in_app_at, now()), updated_at = now(),
					state = CASE WHEN state IN ('unknown', 'sent', 'delivered') THEN 'read_in_app' ELSE state END
				WHERE notification_id = ?
				""").param(notificationId).update();
	}

	public Optional<LockedDelivery> findDeliveryForUpdate(long userId, long deliveryId) {
		return jdbc.sql("""
				SELECT d.id, d.connection_id, d.channel, d.state, d.failure_reason, d.attempts, n.title, n.body
				FROM notification_delivery d
				JOIN notification n ON n.id = d.notification_id
				WHERE d.id = ? AND n.recipient_user_id = ?
				FOR UPDATE OF d
				""").param(deliveryId).param(userId).query((row, index) -> new LockedDelivery(
				row.getLong("id"), row.getLong("connection_id"), row.getString("channel"),
				row.getString("state"), row.getString("failure_reason"), row.getInt("attempts"),
				row.getString("title"), row.getString("body"))).optional();
	}

	public Optional<NotificationDeliveryResponse> findDelivery(long userId, long deliveryId) {
		return jdbc.sql("""
				SELECT %s FROM notification_delivery d
				JOIN notification n ON n.id = d.notification_id
				WHERE d.id = ? AND n.recipient_user_id = ?
				""".formatted(DELIVERY_COLUMNS)).param(deliveryId).param(userId)
				.query((row, index) -> delivery(row)).optional();
	}

	public Optional<Destination> findConnectionDestination(long connectionId) {
		return jdbc.sql("""
				SELECT c.id, t.code, c.connection_metadata ->> 'address' AS address,
					c.connection_metadata ->> 'secretRef' AS secret_ref
				FROM notification_connections c
				JOIN notification_types t ON t.id = c.notification_type_id
				WHERE c.id = ?
				""").param(connectionId).query((row, index) -> new Destination(row.getLong("id"),
				row.getString("code"), row.getString("address"), row.getString("secret_ref"))).optional();
	}

	private record OwnedDelivery(long notificationId, NotificationDeliveryResponse delivery) {
	}

	private List<OwnedDelivery> findDeliveries(JdbcClient.StatementSpec query) {
		return query.query((row, index) -> new OwnedDelivery(row.getLong("notification_id"), delivery(row))).list();
	}

	private DashboardNotificationResponse notification(ResultSet row,
			List<NotificationDeliveryResponse> deliveries) throws SQLException {
		return new DashboardNotificationResponse(row.getLong("id"), row.getString("incident_id"),
				nullableInt(row, "priority_level_id"), row.getString("title"), row.getString("body"),
				instant(row, "created_at"), instant(row, "read_in_app_at"),
				deliveries == null ? List.of() : List.copyOf(deliveries));
	}

	private NotificationDeliveryResponse delivery(ResultSet row) throws SQLException {
		return new NotificationDeliveryResponse(row.getLong("id"), row.getLong("connection_id"),
				row.getString("channel"), row.getString("state"), row.getString("failure_reason"),
				row.getInt("attempts"), instant(row, "sent_at"), instant(row, "delivered_at"),
				instant(row, "read_in_channel_at"), instant(row, "read_in_app_at"),
				instant(row, "last_attempt_at"));
	}

	private static Integer nullableInt(ResultSet row, String column) throws SQLException {
		int value = row.getInt(column);
		return row.wasNull() ? null : value;
	}

	private static Instant instant(ResultSet row, String column) throws SQLException {
		OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
		return value == null ? null : value.toInstant();
	}
}
