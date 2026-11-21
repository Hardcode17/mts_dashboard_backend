package ru.hardcode.dashboard.service.evidence;

import java.time.Instant;

import ru.hardcode.dashboard.dto.McpEvidenceSource;

/**
 * Read-only port to one kind of controlled stand source. An implementation performs only
 * fixed, bounded reads over explicit identifiers and never runs arbitrary queries or
 * commands. The window passed here is already validated by the evidence service.
 */
public interface McpEvidenceSourcePort {

	McpEvidenceSource source();

	/**
	 * Reads nothing but evidence of exactly the mapped incident and scenario run. Every
	 * response identifies that incident and run; the evidence service attributes a reading
	 * only when the source confirms them, so a record of another incident (or one whose
	 * owner cannot be confirmed) is never attributed to the current scenario run.
	 */
	SourceReading read(EvidenceQuery query);

	record EvidenceQuery(
			String incidentId,
			String scenarioRunId,
			String serviceKey,
			Instant from,
			Instant to
	) {
	}

	/**
	 * A normalized, bounded reading from one stand source. Only {@code evidenceId} is
	 * mandatory; a reading without a record time or observation is reported as unavailable.
	 *
	 * @param evidenceId bounded, validated source record identifier
	 * @param observedAt time of the observed record
	 * @param incidentId incident the source confirms the record belongs to
	 * @param scenarioRunId scenario run the source confirms the record belongs to
	 * @param observation safe observation; only the fields of this source are set
	 */
	record SourceReading(
			String evidenceId,
			Instant observedAt,
			String incidentId,
			String scenarioRunId,
			Observation observation
	) {
	}

	/**
	 * Safe, bounded observation values. Free text is limited to a sanitized log message and
	 * the command an incident is assigned to; identifiers are already bounded.
	 */
	record Observation(
			String message,
			Boolean messageTruncated,
			String level,
			String assignedCommand,
			Long count,
			Long value,
			String state
	) {
	}
}
