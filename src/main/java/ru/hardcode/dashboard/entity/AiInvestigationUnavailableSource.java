package ru.hardcode.dashboard.entity;

import java.util.UUID;

import ru.hardcode.dashboard.dto.McpEvidenceSource;

/**
 * One stand source that could not deliver evidence at launch. The gap is persisted with
 * its public error code and is reported separately from the facts; an unavailable source
 * never becomes an invented fact.
 */
public record AiInvestigationUnavailableSource(
		UUID runId,
		int sourceIndex,
		McpEvidenceSource source,
		String serviceKey,
		String code,
		String reason
) {
}
