package ru.hardcode.dashboard.dto;

import java.time.Instant;

public record NotificationDeliveryResponse(
		long id,
		long connectionId,
		String channel,
		String state,
		String failureReason,
	int attempts,
	Instant sentAt,
	Instant deliveredAt,
	Instant readInChannelAt,
	Instant readInAppAt,
	Instant lastAttemptAt
) {
}
