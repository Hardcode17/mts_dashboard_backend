package ru.hardcode.dashboard.worker;

import java.util.UUID;

/**
 * One claimed batch handed to {@link ItsmBatchProcessor}. The payload is a defensive copy of the
 * raw request bytes (KAN-21 validation belongs to the processor).
 */
public record ItsmBatchWork(
		UUID batchId,
		String sourceId,
		long sourceVersion,
		byte[] payload,
		int attempt
) {
	public ItsmBatchWork {
		payload = payload.clone();
	}

	@Override
	public byte[] payload() {
		return payload.clone();
	}
}
