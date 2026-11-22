package ru.hardcode.dashboard.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One AI investigation run of one incident. {@code revision} is the published revision the
 * run was launched against; {@code stale} says a newer revision has been published since,
 * so the stored answer describes an older state of the incident and must not be presented
 * as current. Facts, the hypothesis, the next step and the unavailable sources are kept
 * separate: a fact always names its {@code evidenceId}, a gap is never a fact, and a
 * {@code failed} run carries its explicit public error instead of a made-up answer.
 */
public record AiInvestigationRunResponse(
		UUID runId,
		String incidentId,
		String status,
		long revision,
		boolean stale,
		Instant windowFrom,
		Instant windowTo,
		String hypothesis,
		String nextStep,
		List<Fact> facts,
		List<UnavailableSource> unavailableSources,
		String errorCode,
		String errorMessage,
		Instant createdAt,
		Instant finishedAt
) {

	public AiInvestigationRunResponse {
		facts = facts == null ? List.of() : List.copyOf(facts);
		unavailableSources = unavailableSources == null ? List.of() : List.copyOf(unavailableSources);
	}

	/**
	 * One fact of the run with the stand source record it was taken from. The observation
	 * fields belong to the source that produced the fact; the others stay {@code null}.
	 */
	public record Fact(
			String evidenceId,
			String source,
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

	/** One stand source that could not deliver evidence at launch. */
	public record UnavailableSource(
			String source,
			String serviceKey,
			String code,
			String reason
	) {
	}
}
