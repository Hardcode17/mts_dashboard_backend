package ru.hardcode.dashboard.dto;

/**
 * One evidence request for a single stand source of the mapped scenario run. Only these
 * explicit identifiers and the bounded window are accepted, as ISO-8601 instants; nothing
 * is free-form.
 */
public record McpEvidenceServiceSourceRequest(
		String incidentId,
		String scenarioRunId,
		String serviceKey,
		String windowFrom,
		String windowTo
) {
}
