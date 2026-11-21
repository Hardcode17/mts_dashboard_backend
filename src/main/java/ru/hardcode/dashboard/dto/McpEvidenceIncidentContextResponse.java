package ru.hardcode.dashboard.dto;

import java.time.Instant;
import java.util.List;

/**
 * Bounded incident context for one explicitly mapped demo scenario run. A source that could
 * not deliver evidence is listed in {@code unavailable} with its own error code and is never
 * mixed with, or silently dropped from, the evidence list.
 */
public record McpEvidenceIncidentContextResponse(
		String scenarioId,
		String incidentId,
		String scenarioRunId,
		Instant windowFrom,
		Instant windowTo,
		List<McpEvidenceRef> evidence,
		List<McpEvidenceUnavailableSource> unavailable
) {

	public McpEvidenceIncidentContextResponse {
		evidence = List.copyOf(evidence);
		unavailable = List.copyOf(unavailable);
	}
}
