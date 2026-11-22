package ru.hardcode.dashboard.service;

import java.util.Collection;
import java.util.List;

/**
 * One significant event that must reach coordinators through their connected channels
 * (KAN-35). This is the integration point for the future callers:
 *
 * <ul>
 * <li>KAN-34 personal P4 assignment will call
 * {@link NotificationDeliveryService#deliver(SignificantEvent)} with the assignee in
 * {@code recipientUserIds} and its own stable {@code eventKey};
 * <li>the KAN-25 shift event queue will fan an alarm event out to every coordinator whose
 * connections subscribe to the event priority, passing no {@code recipientUserIds}.
 * </ul>
 *
 * <p>{@code eventKey} must be stable for one event (for example the queue {@code eventId});
 * a replay of the same key for the same recipient never creates a second notification.
 * {@code recipientUserIds} selects explicit recipients; when it is empty, every active user
 * with an allowed connection for {@code priorityLevelId} becomes a recipient.
 */
public record SignificantEvent(
		String eventKey,
		String incidentId,
		Integer priorityLevelId,
		String title,
		String body,
		Collection<Long> recipientUserIds
) {

	public SignificantEvent {
		recipientUserIds = recipientUserIds == null ? List.of() : List.copyOf(recipientUserIds);
	}
}
