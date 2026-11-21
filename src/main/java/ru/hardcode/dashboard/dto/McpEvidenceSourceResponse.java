package ru.hardcode.dashboard.dto;

import java.time.Instant;

/**
 * Evidence from one stand source of the mapped scenario run.
 */
public record McpEvidenceSourceResponse(
		String scenarioId,
		String incidentId,
		String scenarioRunId,
		McpEvidenceSource source,
		String serviceKey,
		Instant windowFrom,
		Instant windowTo,
		McpEvidenceRef evidence
) {
}
