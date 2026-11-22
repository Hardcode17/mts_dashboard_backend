package ru.hardcode.dashboard.entity;

import java.time.Instant;
import java.util.UUID;

import ru.hardcode.dashboard.dto.McpEvidenceSource;

/**
 * One persisted AI investigation run. The run is bound to the published {@code revision}
 * it was launched against; facts and gaps of that launch are stored beside it, and the
 * model prompt keeps exactly what was asked, so a later change of the incident never
 * rewrites what an old run saw.
 */
public record AiInvestigationRun(
		UUID runId,
		String incidentId,
		long revision,
		AiInvestigationRunStatus status,
		Instant windowFrom,
		Instant windowTo,
		String modelPrompt,
		String hypothesis,
		String nextStep,
		String errorCode,
		String errorMessage,
		Instant createdAt,
		Instant finishedAt
) {
}
