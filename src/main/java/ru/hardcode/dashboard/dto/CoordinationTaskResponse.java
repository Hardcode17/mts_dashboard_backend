package ru.hardcode.dashboard.dto;

import java.time.Instant;

public record CoordinationTaskResponse(long taskId, String incidentId, Instant createdAt) {
}
