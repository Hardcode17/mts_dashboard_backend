package ru.hardcode.dashboard.dto;

import java.time.Instant;
import java.util.List;

public record DashboardNotificationResponse(
		long id,
		String incidentId,
		Integer priorityId,
		String title,
		String body,
		Instant createdAt,
		Instant readInAppAt,
		List<NotificationDeliveryResponse> deliveries
) {
}
