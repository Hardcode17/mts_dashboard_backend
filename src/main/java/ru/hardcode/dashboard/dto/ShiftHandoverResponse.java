package ru.hardcode.dashboard.dto;

import java.time.OffsetDateTime;
import java.util.List;

public record ShiftHandoverResponse(List<Recipient> recipients, List<Item> owned,
		List<Batch> incoming, List<Batch> outgoing) {

	public record Recipient(long id, String name) {
	}

	public record Item(long taskId, String incidentId, int priority, OffsetDateTime deadline, String title,
			String team, String situationKey, OffsetDateTime claimedAt, boolean available) {
	}

	public record Batch(long id, long senderId, String senderName, long recipientId, String recipientName,
			OffsetDateTime createdAt, String summary, List<Item> items) {
	}

	public record Receipt(long id) {
	}
}
