package ru.hardcode.dashboard.dto;

/**
 * A stand source that could not deliver evidence. It is reported separately from the
 * available evidence and is never turned into a successful empty result.
 */
public record McpEvidenceUnavailableSource(
		McpEvidenceSource source,
		String serviceKey,
		String code,
		String reason
) {
}
