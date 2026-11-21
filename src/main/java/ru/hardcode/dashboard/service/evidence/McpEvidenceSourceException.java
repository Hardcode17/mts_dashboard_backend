package ru.hardcode.dashboard.service.evidence;

import java.util.Map;

import ru.hardcode.dashboard.dto.McpEvidenceSource;
import ru.hardcode.dashboard.exception.AppError;
import ru.hardcode.dashboard.exception.AppException;
import ru.hardcode.dashboard.exception.AppErrors;

/**
 * A stand source could not deliver evidence. The exception already carries the public
 * application error, so callers can either propagate it (single-source tool) or report the
 * source as unavailable (aggregated context) without remapping it. Only public, fixed
 * reasons are ever used; the cause stays internal.
 */
public class McpEvidenceSourceException extends AppException {

	private final McpEvidenceSource source;
	private final String serviceKey;

	public McpEvidenceSourceException(
			AppError appError,
			McpEvidenceSource source,
			String serviceKey,
			Map<String, ?> parameters,
			Throwable cause
	) {
		super(appError, parameters, cause);
		this.source = source;
		this.serviceKey = serviceKey;
	}

	public static McpEvidenceSourceException unavailable(
			McpEvidenceSource source,
			String serviceKey,
			Throwable cause
	) {
		return new McpEvidenceSourceException(
				AppErrors.MCP_EVIDENCE_SOURCE_UNAVAILABLE,
				source,
				serviceKey,
				Map.of("source", source.name(), "reason", "connection failed or no record in the evidence window"),
				cause
		);
	}

	public static McpEvidenceSourceException rejected(McpEvidenceSource source, String serviceKey) {
		return new McpEvidenceSourceException(
				AppErrors.MCP_EVIDENCE_SOURCE_REJECTED,
				source,
				serviceKey,
				Map.of("source", source.name()),
				null
		);
	}

	/**
	 * The source answered, but with a record that does not belong to the mapped incident and
	 * scenario run (or does not name them at all).
	 */
	public static McpEvidenceSourceException notAttributed(
			McpEvidenceSource source,
			String serviceKey,
			String incidentId,
			String scenarioRunId
	) {
		return new McpEvidenceSourceException(
				AppErrors.MCP_EVIDENCE_NOT_ATTRIBUTED,
				source,
				serviceKey,
				Map.of("source", source.name(), "incidentId", incidentId, "scenarioRunId", scenarioRunId),
				null
		);
	}

	public static McpEvidenceSourceException outOfWindow(McpEvidenceSource source, String serviceKey) {
		return new McpEvidenceSourceException(
				AppErrors.MCP_EVIDENCE_OUT_OF_WINDOW,
				source,
				serviceKey,
				Map.of("source", source.name()),
				null
		);
	}

	public McpEvidenceSource getSource() {
		return source;
	}

	public String getServiceKey() {
		return serviceKey;
	}
}
