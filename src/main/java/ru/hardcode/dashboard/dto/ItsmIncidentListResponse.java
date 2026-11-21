package ru.hardcode.dashboard.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Current ITSM incidents read from the installed baseline. {@code snapshot} is {@code null}
 * until the first snapshot was applied.
 */
public record ItsmIncidentListResponse(
		Snapshot snapshot,
		int count,
		List<ItsmIncidentResponse> incidents
) {

	public record Snapshot(
			UUID batchId,
			String sourceId,
			long sourceVersion,
			Instant appliedAt
	) {
	}
}
