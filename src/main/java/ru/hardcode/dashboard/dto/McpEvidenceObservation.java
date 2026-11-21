package ru.hardcode.dashboard.dto;

/**
 * Bounded, safe description of what was observed in a stand source. Sources fill only the
 * fields that belong to them; the other fields stay {@code null}. No raw payloads, secrets
 * or SQL are ever placed here. {@code evidenceId} and {@code observedAt} are the last record
 * of the evidence, so a client can read the latest record time straight from the
 * observation.
 *
 * @param message log message, sanitized and truncated to the safe length
 * @param messageTruncated true when the log message was shortened or redacted
 * @param level log level or normalized priority of the incident
 * @param assignedCommand command the incident is assigned to (ITSM only)
 * @param evidenceId identifier of the last log or diagnostic record
 * @param observedAt time of the last log or diagnostic record, or of the ITSM context read
 * @param recordCount number of records inside the evidence window
 * @param value diagnostic metric value
 * @param serviceState safe ITSM incident or service state
 */
public record McpEvidenceObservation(
		String message,
		Boolean messageTruncated,
		String level,
		String assignedCommand,
		String evidenceId,
		String observedAt,
		Long recordCount,
		Long value,
		String serviceState
) {
}
