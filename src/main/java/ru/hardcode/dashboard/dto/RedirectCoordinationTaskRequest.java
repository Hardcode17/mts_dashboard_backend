package ru.hardcode.dashboard.dto;

public record RedirectCoordinationTaskRequest(Long sourceGroupId, Long targetGroupId, Long expectedAssignmentId,
		String reason) {
}
