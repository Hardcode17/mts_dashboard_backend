package ru.hardcode.dashboard.dto;

import java.time.Instant;

public record CoordinationClaimResponse(long taskId, String incidentId, Long coordinatorId, Instant acceptedAt) {
}
