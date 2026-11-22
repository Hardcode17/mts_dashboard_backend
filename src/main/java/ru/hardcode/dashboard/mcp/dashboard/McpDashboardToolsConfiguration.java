package ru.hardcode.dashboard.mcp.dashboard;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.hardcode.dashboard.configuration.mcpdashboard.ConditionalOnDashboardMcp;
import ru.hardcode.dashboard.service.mcpdashboard.McpDashboardException;
import ru.hardcode.dashboard.service.mcpdashboard.McpDashboardService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * The six read-only MCP Dashboard tools (KAN-86). Argument validation and business rules
 * live in {@link McpDashboardService}; the result is the contract envelope in
 * {@code structuredContent} with a JSON copy in the text content. {@code ok=false} comes
 * with {@code isError=true}; {@code assign_incident} is deliberately not declared.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnDashboardMcp
public class McpDashboardToolsConfiguration {

	public static final String LIST_INCIDENTS = "list_incidents";
	public static final String GET_INCIDENT = "get_incident";
	public static final String LIST_INCIDENT_CHANGES = "list_incident_changes";
	public static final String FIND_SIMILAR_INCIDENTS = "find_similar_incidents";
	public static final String FIND_RESOLVER_GROUPS = "find_resolver_groups";
	public static final String FIND_ASSIGNEES = "find_assignees";

	private final McpDashboardService service;
	private final JsonMapper jsonMapper;
	private final McpJsonMapper mcpJsonMapper;
	private final ObjectMapper objectMapper;

	public McpDashboardToolsConfiguration(McpDashboardService service, JsonMapper jsonMapper,
			McpJsonMapper mcpJsonMapper, ObjectMapper objectMapper) {
		this.service = service;
		this.jsonMapper = jsonMapper;
		this.mcpJsonMapper = mcpJsonMapper;
		this.objectMapper = objectMapper;
	}

	@Bean
	McpServerFeatures.SyncToolSpecification listIncidentsTool() {
		return tool(LIST_INCIDENTS, """
				Read-only page of the published incident snapshot: contract incident rows with \
				filters, a signed cursor and the journal checkpoint. Order is priorityId DESC, \
				incidentId ASC; an empty result is a valid success.""",
				service::listIncidents);
	}

	@Bean
	McpServerFeatures.SyncToolSpecification getIncidentTool() {
		return tool(GET_INCIDENT, """
				Read-only card of one incident by its exact INC id: description and solution with \
				truncation warnings, deadline and assignment. An incident missing from the \
				published snapshot is NOT_FOUND, never an empty success.""",
				service::getIncident);
	}

	@Bean
	McpServerFeatures.SyncToolSpecification listIncidentChangesTool() {
		return tool(LIST_INCIDENT_CHANGES, """
				Read-only replay of the shared incident change journal (KAN-28) behind a signed \
				checkpoint: stable eventIds, deduplication-friendly ordering, no critical-only \
				filter. An expired or stale cursor is CURSOR_EXPIRED with resync_snapshot.""",
				service::listIncidentChanges);
	}

	@Bean
	McpServerFeatures.SyncToolSpecification findSimilarIncidentsTool() {
		return tool(FIND_SIMILAR_INCIDENTS, """
				Read-only completed analogues of one incident: exact error code first, then FTS \
				rank over header, description and solution; matched signals, differences and the \
				historical resolver group with their evidence.""",
				service::findSimilarIncidents);
	}

	@Bean
	McpServerFeatures.SyncToolSpecification findResolverGroupsTool() {
		return tool(FIND_RESOLVER_GROUPS, """
				Read-only resolver group candidates for one incident: confirmed owner from the \
				official assignment first, then historical resolvers; history never claims a \
				current owner, and competence claims stay out of the result.""",
				service::findResolverGroups);
	}

	@Bean
	McpServerFeatures.SyncToolSpecification findAssigneesTool() {
		return tool(FIND_ASSIGNEES, """
				Read-only external assignees of one ITSM group: personId and displayName from the \
				ITSM directory, optional case-insensitive name substring. Without a dedicated \
				source competence and duty stay unknown.""",
				service::findAssignees);
	}

	@Bean
	List<McpServerFeatures.SyncToolSpecification> mcpDashboardTools(
			McpServerFeatures.SyncToolSpecification listIncidentsTool,
			McpServerFeatures.SyncToolSpecification getIncidentTool,
			McpServerFeatures.SyncToolSpecification listIncidentChangesTool,
			McpServerFeatures.SyncToolSpecification findSimilarIncidentsTool,
			McpServerFeatures.SyncToolSpecification findResolverGroupsTool,
			McpServerFeatures.SyncToolSpecification findAssigneesTool
	) {
		return List.of(listIncidentsTool, getIncidentTool, listIncidentChangesTool,
				findSimilarIncidentsTool, findResolverGroupsTool, findAssigneesTool);
	}

	private McpServerFeatures.SyncToolSpecification tool(String name, String description,
			Function<Map<String, Object>, JsonNode> call) {
		// The non-deprecated SDK entry point: the three-argument builder factory takes the tool
		// name, the shared MCP JSON mapper and the input schema JSON directly.
		McpSchema.Tool tool = McpSchema.Tool
				.builder(name, mcpJsonMapper, McpDashboardSchemas.inputSchema(objectMapper, name))
				.title(name)
				.description(description)
				.outputSchema(mcpJsonMapper, McpDashboardSchemas.outputSchema(objectMapper, name))
				.annotations(readOnly(name))
				.build();
		return McpServerFeatures.SyncToolSpecification.builder()
				.tool(tool)
				.callHandler((exchange, request) -> execute(() -> call.apply(arguments(request))))
				.build();
	}

	private McpSchema.CallToolResult execute(Supplier<JsonNode> call) {
		JsonNode envelope;
		try {
			envelope = call.get();
		} catch (McpDashboardException contractError) {
			envelope = service.errorEnvelope(contractError);
		} catch (RuntimeException unexpected) {
			envelope = service.errorEnvelope(McpDashboardException.sourceUnavailable(
					"The dashboard is temporarily unable to serve this call"));
		}
		String body = jsonMapper.writeValueAsString(envelope);
		return new McpSchema.CallToolResult(
				List.of(McpSchema.TextContent.builder(body).build()),
				!envelope.path("ok").asBoolean(true),
				objectMapper.readValue(body, Object.class),
				null);
	}

	private static Map<String, Object> arguments(McpSchema.CallToolRequest request) {
		return request.arguments() == null ? Map.of() : request.arguments();
	}

	/**
	 * Read-only, idempotent and closed: the tools read only the published dashboard domain
	 * and the ITSM directory; nothing writes.
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
