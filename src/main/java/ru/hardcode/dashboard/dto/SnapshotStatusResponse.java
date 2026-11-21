package ru.hardcode.dashboard.dto;

import java.time.Instant;

/**
 * Downstream snapshot state. {@code stale} is true until the most recently accepted
 * snapshot is successfully processed; it is never derived from an HTTP 202 alone.
 */
public record SnapshotStatusResponse(
		Instant lastReceivedAt,
		Instant lastSuccessAt,
		Long revision,
		boolean stale
) {
}
