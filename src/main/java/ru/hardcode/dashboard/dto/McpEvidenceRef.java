package ru.hardcode.dashboard.dto;

import java.time.Instant;

/**
 * One piece of readonly evidence. It never exists without the incident it was requested for,
 * the explicitly linked scenario run, the observed service, the source, the observation time,
 * the bounded evidence window and the observation itself.
 */
public record McpEvidenceRef(
		String evidenceId,
		McpEvidenceSource source,
		String incidentId,
		String scenarioRunId,
		String serviceKey,
		Instant observedAt,
		Instant windowFrom,
		Instant windowTo,
		McpEvidenceObservation observation
) {
}
