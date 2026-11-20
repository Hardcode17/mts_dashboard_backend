package ru.hardcode.dashboard.dto;

import java.time.Instant;

public record HealthResponse(
		String status,
		String service,
		Instant timestamp,
		long uptimeSeconds,
		String javaVersion
) {
}
