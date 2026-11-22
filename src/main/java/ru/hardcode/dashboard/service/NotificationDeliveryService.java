package ru.hardcode.dashboard.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.hardcode.dashboard.dto.DashboardNotificationResponse;
import ru.hardcode.dashboard.dto.NotificationDeliveryResponse;
import ru.hardcode.dashboard.exception.AppErrors;
import ru.hardcode.dashboard.exception.AppException;
import ru.hardcode.dashboard.repository.NotificationConnectionRepository;
import ru.hardcode.dashboard.repository.NotificationDeliveryRepository;
import ru.hardcode.dashboard.repository.NotificationDeliveryRepository.DeliveryOutcome;
import ru.hardcode.dashboard.service.NotificationChannelAdapter.Destination;
import ru.hardcode.dashboard.service.NotificationChannelAdapter.Message;

/**
 * Notification delivery of KAN-35. One {@link SignificantEvent} becomes at most one
 * notification per recipient — a replayed eventKey for the same recipient is a no-op — and
 * one delivery row per allowed connection of that recipient. Every attempt goes through the
 * {@link NotificationChannelAdapter}; a failed attempt never rolls the others back.
 *
 * <p>Delivery states, from the weakest signal to the strongest: {@code unknown} (no signal is
 * available), {@code sent} (the channel accepted the message), {@code delivered} (the channel
 * confirmed delivery), {@code read_in_app} (the recipient read the notification in the
 * dashboard app), {@code read_in_channel} (the channel reported the recipient read it there).
 * {@code failed} marks an attempt error with a sanitized reason; it is the only state a retry
 * may resend, so a successful delivery is never duplicated, and the row lock of a retry makes
 * concurrent retries of the same delivery idempotent.
 */
@Service
public class NotificationDeliveryService {

	private final NotificationDeliveryRepository repository;
	private final NotificationConnectionRepository connections;
	private final NotificationChannelAdapter adapter;

	public NotificationDeliveryService(NotificationDeliveryRepository repository,
			NotificationConnectionRepository connections, NotificationChannelAdapter adapter) {
		this.repository = repository;
		this.connections = connections;
		this.adapter = adapter;
	}

	/**
	 * Delivers one significant event. Explicit recipients (the future personal assignment of
	 * KAN-34) get a notification even when they have no allowed connection; without explicit
	 * recipients every active user with an allowed connection for the event priority gets one.
	 */
	@Transactional
	public void deliver(SignificantEvent event) {
		validate(event);
		for (var recipient : resolveRecipients(event).entrySet()) {
			long userId = recipient.getKey();
			Long notificationId = repository.createNotification(event.eventKey(), event.incidentId(),
					event.priorityLevelId(), userId, event.title(), event.body()).orElse(null);
			if (notificationId == null) {
				// A replay of the same event for this recipient must not create anything.
				continue;
			}
			Message message = new Message(event.title(), event.body());
			for (Destination destination : recipient.getValue()) {
				repository.insertDelivery(notificationId, destination.connectionId(), destination.channel(),
						attempt(destination, message));
			}
		}
	}

	@Transactional(readOnly = true)
	public List<DashboardNotificationResponse> list(long userId) {
		return repository.findNotifications(userId);
	}

	/**
	 * The recipient confirmed reading the notification in the dashboard app. This is separate
	 * from a channel read receipt: it never changes a {@code read_in_channel} delivery and a
	 * channel receipt never marks the app read.
	 */
	@Transactional
	public DashboardNotificationResponse markReadInApp(long userId, long notificationId) {
		if (!repository.markReadInApp(userId, notificationId)) {
			throw new AppException(AppErrors.NOTIFICATION_NOT_FOUND);
		}
		repository.markDeliveriesReadInApp(notificationId);
		return findNotification(userId, notificationId);
	}

	/**
	 * Retries one failed delivery. A retry of a delivery in any other state is an idempotent
	 * no-op that returns the current state and never sends again; the row lock serializes
	 * concurrent retries of the same delivery to exactly one new attempt.
	 */
	@Transactional
	public NotificationDeliveryResponse retryDelivery(long userId, long deliveryId) {
		var delivery = repository.findDeliveryForUpdate(userId, deliveryId)
				.orElseThrow(() -> new AppException(AppErrors.NOTIFICATION_DELIVERY_NOT_FOUND));
		if (!"failed".equals(delivery.state())) {
			return repository.findDelivery(userId, deliveryId)
					.orElseThrow(() -> new AppException(AppErrors.NOTIFICATION_DELIVERY_NOT_FOUND));
		}
		Destination destination = repository.findConnectionDestination(delivery.connectionId())
				.orElseThrow(() -> new AppException(AppErrors.NOTIFICATION_DELIVERY_NOT_FOUND));
		repository.recordAttempt(deliveryId, attempt(destination, new Message(delivery.title(), delivery.body())));
		return repository.findDelivery(userId, deliveryId)
				.orElseThrow(() -> new AppException(AppErrors.NOTIFICATION_DELIVERY_NOT_FOUND));
	}

	@Transactional(readOnly = true)
	public DashboardNotificationResponse findNotification(long userId, long notificationId) {
		return repository.findNotification(userId, notificationId)
				.orElseThrow(() -> new AppException(AppErrors.NOTIFICATION_NOT_FOUND));
	}

	private DeliveryOutcome attempt(Destination destination, Message message) {
		try {
			return DeliveryOutcome.from(adapter.send(destination, message));
		}
		catch (NotificationChannelFailure failure) {
			// Only the sanitized reason code is persisted; never addresses, tokens or URLs.
			return DeliveryOutcome.failed(failure.reason());
		}
	}

	private Map<Long, List<Destination>> resolveRecipients(SignificantEvent event) {
		Map<Long, List<Destination>> recipients = new LinkedHashMap<>();
		if (event.recipientUserIds().isEmpty()) {
			for (var allowed : repository.findPriorityDestinations(event.priorityLevelId())) {
				recipients.computeIfAbsent(allowed.userId(), key -> new ArrayList<>()).add(allowed.destination());
			}
			return recipients;
		}
		for (Long userId : event.recipientUserIds()) {
			if (repository.isActiveUser(userId)) {
				recipients.put(userId, connections.findDestinations(userId, event.priorityLevelId()));
			}
		}
		return recipients;
	}

	private static void validate(SignificantEvent event) {
		if (event.eventKey() == null || event.eventKey().isBlank() || event.eventKey().length() > 160
				|| event.title() == null || event.title().isBlank() || event.title().length() > 200
				|| event.body() == null || event.body().isBlank()
				|| event.priorityLevelId() == null || event.priorityLevelId() < 1 || event.priorityLevelId() > 4
				|| event.incidentId() != null && event.incidentId().length() > 128) {
			throw new IllegalArgumentException("significant event must carry a stable key, priority, title and body");
		}
	}
}
