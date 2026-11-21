package ru.hardcode.dashboard.dto;

import java.time.Instant;
import java.util.UUID;

import ru.hardcode.dashboard.entity.ItsmBatchStatus;

/**
 * Operational metadata for one accepted snapshot. The raw payload is intentionally absent.
 * {@code nextAttemptAt} is set only when the KAN-54 worker scheduled an automatic retry of a
 * failed batch.
 */
public record ItsmBatchMetadataResponse(
		UUID batchId,
		String sourceId,
		long sourceVersion,
		Instant receivedAt,
		ItsmBatchStatus status,
		String diagnostic,
		Instant nextAttemptAt
) {
}
