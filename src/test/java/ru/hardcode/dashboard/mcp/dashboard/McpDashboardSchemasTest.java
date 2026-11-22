package ru.hardcode.dashboard.mcp.dashboard;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The tool schemas are generated from the frozen contract artifact: every self-contained
 * schema keeps the contract's own defs and required fields, so a tool cannot drift from
 * {@code Hardcode17/mts_dashboard_data/docs/mcp/v1}.
 */
class McpDashboardSchemasTest {

	private final ObjectMapper json = new ObjectMapper();

	@Test
	void inputSchemaIsSelfContainedWithContractDefs() {
		String schema = McpDashboardSchemas.inputSchema(json, "get_incident");
		var root = json.readTree(schema);
		assertThat(root.get("type").stringValue()).isEqualTo("object");
		assertThat(root.get("required").toString()).contains("incidentId");
		assertThat(root.get("additionalProperties").asBoolean()).isFalse();
		// Shared defs are embedded so #/$defs/... references resolve without HTTP.
		assertThat(root.get("$defs").get("incident").get("required").toString())
				.contains("assignedGroupId").contains("updatedAt");
		assertThat(root.get("properties").get("incidentId").get("pattern").stringValue())
				.isEqualTo("^INC[0-9]{6,18}$");
	}

	@Test
	void outputSchemaDescribesTheContractEnvelope() {
		String schema = McpDashboardSchemas.outputSchema(json, "find_resolver_groups");
		var root = json.readTree(schema);
		assertThat(root.get("oneOf")).isNotNull();
		assertThat(root.get("$defs").get("group").get("properties").get("responsibility").toString())
				.contains("confirmed").contains("historical").contains("unknown");
	}

	@Test
	void everyToolHasBothSchemas() {
		for (String tool : new String[] {"list_incidents", "get_incident", "list_incident_changes",
				"find_similar_incidents", "find_resolver_groups", "find_assignees"}) {
			assertThat(McpDashboardSchemas.inputSchema(json, tool)).startsWith("{");
			assertThat(McpDashboardSchemas.outputSchema(json, tool)).startsWith("{");
		}
	}
}
