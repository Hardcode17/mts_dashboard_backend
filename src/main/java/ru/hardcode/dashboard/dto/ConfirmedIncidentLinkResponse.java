package ru.hardcode.dashboard.dto;

import java.time.Instant;

public record ConfirmedIncidentLinkResponse(long id, String sourceIncidentId, String targetIncidentId, String kind,
		long confirmedByUserId, Instant createdAt) {
}
