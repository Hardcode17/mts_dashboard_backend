package ru.hardcode.dashboard.mcp;

import ru.hardcode.dashboard.configuration.evidence.ConditionalOnEvidenceMapping;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.hardcode.dashboard.dto.AppErrorResponse;
import ru.hardcode.dashboard.dto.McpEvidenceIncidentContextResponse;
import ru.hardcode.dashboard.dto.McpEvidenceServiceSourceRequest;
import ru.hardcode.dashboard.dto.McpEvidenceSourceResponse;
import ru.hardcode.dashboard.exception.AppError;
import ru.hardcode.dashboard.exception.AppException;
import ru.hardcode.dashboard.service.evidence.McpEvidenceService;
import tools.jackson.databind.json.JsonMapper;

/**
 * The only MCP tools this service exposes: bounded read-only evidence for the one explicitly
 * mapped demo scenario. There are no write tools and no action tools; denying an action is a
 * configuration decision, not a tool. Argument validation and every business rule live in
 * {@link McpEvidenceService}, so the two tools share one contract. A rejected call is an MCP
 * tool error that carries only the public error code and message.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnEvidenceMapping
public class McpEvidenceToolsConfiguration {

	public static final String INCIDENT_CONTEXT_TOOL = "get_incident_context";
	public static final String SERVICE_SOURCE_TOOL = "get_service_evidence";

	private final McpEvidenceService evidenceService;
	private final JsonMapper jsonMapper;
	private final McpJsonMapper mcpJsonMapper;

	public McpEvidenceToolsConfiguration(
			McpEvidenceService evidenceService,
			JsonMapper jsonMapper,
			McpJsonMapper mcpJsonMapper
	) {
		this.evidenceService = evidenceService;
		this.jsonMapper = jsonMapper;
		this.mcpJsonMapper = mcpJsonMapper;
	}

	/**
	 * All evidence of the mapped incident from every bound stand source of the scenario run.
	 * Failing sources are reported separately and never become an empty success.
	 */
	@Bean
	McpServerFeatures.SyncToolSpecification getIncidentContextTool() {
		McpSchema.Tool tool = McpSchema.Tool.builder()
				.name(INCIDENT_CONTEXT_TOOL)
				.title("Incident context evidence")
				.description("""
						Read-only context evidence for the mapped demo incident: the assigned command, \
						the latest log record and the diagnostic metric of its bound services for one \
						bounded window. Callers are authenticated; unknown identifiers, forbidden \
						windows and unmapped services are rejected.""")
				.inputSchema(mcpJsonMapper, """
						{
						  "type": "object",
						  "additionalProperties": false,
						  "required": ["scenarioId", "incidentId", "scenarioRunId", "windowFrom", "windowTo"],
						  "properties": {
						    "scenarioId": {"type": "string", "minLength": 1, "maxLength": 128},
						    "incidentId": {"type": "string", "minLength": 1, "maxLength": 64},
						    "scenarioRunId": {"type": "string", "minLength": 1, "maxLength": 128},
						    "windowFrom": {"type": "string", "description": "ISO-8601 instant, inclusive, microsecond precision"},
						    "windowTo": {"type": "string", "description": "ISO-8601 instant, inclusive, microsecond precision"}
						  }
						}
						""")
				.annotations(readOnly("Incident context evidence"))
				.build();
		return new McpServerFeatures.SyncToolSpecification(tool, (exchange, request) -> guarded(() -> {
			Map<String, Object> arguments = arguments(request);
			McpEvidenceIncidentContextResponse response = evidenceService.incidentContext(
					stringArgument(arguments, "scenarioId"),
					stringArgument(arguments, "incidentId"),
					stringArgument(arguments, "scenarioRunId"),
					stringArgument(arguments, "windowFrom"),
					stringArgument(arguments, "windowTo")
			);
			return jsonResult(response);
		}));
	}

	/**
	 * Evidence from exactly one stand source bound to the scenario run. The service key selects
	 * the source; an unmapped request is rejected before any source is contacted.
	 */
	@Bean
	McpServerFeatures.SyncToolSpecification getServiceEvidenceTool() {
		McpSchema.Tool tool = McpSchema.Tool.builder()
				.name(SERVICE_SOURCE_TOOL)
				.title("Service source evidence")
				.description("""
						Read-only evidence from one stand source (ITSM context, logs or the diagnostics \
						database) explicitly bound to the mapped demo scenario run. An unavailable \
						source fails the call and is never an empty success.""")
				.inputSchema(mcpJsonMapper, """
						{
						  "type": "object",
						  "additionalProperties": false,
						  "required": ["incidentId", "scenarioRunId", "serviceKey", "windowFrom", "windowTo"],
						  "properties": {
						    "incidentId": {"type": "string", "minLength": 1, "maxLength": 64},
						    "scenarioRunId": {"type": "string", "minLength": 1, "maxLength": 128},
						    "serviceKey": {"type": "string", "minLength": 1, "maxLength": 128, "description": "Explicit service key of the demo mapping"},
						    "windowFrom": {"type": "string", "description": "ISO-8601 instant, inclusive, microsecond precision"},
						    "windowTo": {"type": "string", "description": "ISO-8601 instant, inclusive, microsecond precision"}
						  }
						}
						""")
				.annotations(readOnly("Service source evidence"))
				.build();
		return new McpServerFeatures.SyncToolSpecification(tool, (exchange, request) -> guarded(() -> {
			Map<String, Object> arguments = arguments(request);
			McpEvidenceSourceResponse response = evidenceService.serviceSource(new McpEvidenceServiceSourceRequest(
					stringArgument(arguments, "incidentId"),
					stringArgument(arguments, "scenarioRunId"),
					stringArgument(arguments, "serviceKey"),
					stringArgument(arguments, "windowFrom"),
					stringArgument(arguments, "windowTo")
			));
			return jsonResult(response);
		}));
	}

	@Bean
	List<McpServerFeatures.SyncToolSpecification> mcpEvidenceTools(
			McpServerFeatures.SyncToolSpecification getIncidentContextTool,
			McpServerFeatures.SyncToolSpecification getServiceEvidenceTool
	) {
		return List.of(getIncidentContextTool, getServiceEvidenceTool);
	}

	private McpSchema.CallToolResult guarded(Supplier<McpSchema.CallToolResult> call) {
		try {
			return call.get();
		} catch (AppException exception) {
			return errorResult(exception.getAppError(), exception.getMessage());
		}
	}

	private McpSchema.CallToolResult errorResult(AppError error, String message) {
		return McpSchema.CallToolResult.builder()
				.addTextContent(jsonMapper.writeValueAsString(new AppErrorResponse(error.getCode(), message)))
				.isError(true)
				.build();
	}

	private McpSchema.CallToolResult jsonResult(Object body) {
		return McpSchema.CallToolResult.builder()
				.addTextContent(jsonMapper.writeValueAsString(body))
				.isError(false)
				.build();
	}

	private static Map<String, Object> arguments(McpSchema.CallToolRequest request) {
		return request.arguments() == null ? Map.of() : request.arguments();
	}

	private static String stringArgument(Map<String, Object> arguments, String name) {
		Object value = arguments.get(name);
		return value instanceof String text ? text : null;
	}

	/**
	 * Read-only, idempotent and closed: the tool only reads the explicitly mapped stand
	 * sources of this service.
	 */
	private static McpSchema.ToolAnnotations readOnly(String title) {
		return McpSchema.ToolAnnotations.builder()
				.title(title)
				.readOnlyHint(true)
				.destructiveHint(false)
				.idempotentHint(true)
				.openWorldHint(false)
				.build();
	}
}
