package ru.hardcode.dashboard.dto;

import java.time.OffsetDateTime;

public record CoordinationTaskAssignmentResponse(long assignmentId, long groupId, String groupName, String status,
		long assignedByUserId, OffsetDateTime assignedAt, OffsetDateTime endedAt, Long redirectedByUserId,
		String redirectReason) {
}
