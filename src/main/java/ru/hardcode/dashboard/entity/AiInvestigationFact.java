package ru.hardcode.dashboard.entity;

import java.time.Instant;
import java.util.UUID;

import ru.hardcode.dashboard.dto.McpEvidenceSource;

/**
 * One fact of an investigation run: a bounded evidence reading gathered at launch. The
 * {@code evidenceId} always names the stand source record the fact was taken from, so no
 * fact exists without an observable source record behind it.
 */
public record AiInvestigationFact(
		UUID runId,
		int factIndex,
		String evidenceId,
		McpEvidenceSource source,
		String serviceKey,
		Instant observedAt,
		String message,
		Boolean messageTruncated,
		String level,
		String assignedCommand,
		Long recordCount,
		Long value,
		String serviceState
) {
}
