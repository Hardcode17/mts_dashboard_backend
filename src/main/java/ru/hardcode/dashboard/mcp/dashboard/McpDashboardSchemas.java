package ru.hardcode.dashboard.mcp.dashboard;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Tool schemas of the MCP Dashboard adapter, generated from the frozen contract artifact
 * {@code Hardcode17/mts_dashboard_data/docs/mcp/v1/contract.schema.json}, which is copied
 * verbatim into {@code mcp/dashboard/contract.schema.json}. For a tool {@code X} the
 * adapter places {@code $defs.XInput} ({@code XOutput}) with the shared {@code $defs}
 * into a self-contained {@code inputSchema} ({@code outputSchema}); internal
 * {@code #/$defs/...} references stay valid because the defs live at the document root.
 */
public final class McpDashboardSchemas {

	private static final String TOOL_DEFS_PREFIX = "";

	private McpDashboardSchemas() {
	}

	/** Self-contained input schema of one tool from the contract defs. */
	public static String inputSchema(ObjectMapper json, String tool) {
		return schema(json, tool + "Input");
	}

	/** Self-contained output schema of one tool from the contract defs. */
	public static String outputSchema(ObjectMapper json, String tool) {
		return schema(json, tool + "Output");
	}

	private static String schema(ObjectMapper json, String defName) {
		JsonNode contract = readContract(json);
		JsonNode defs = contract.get("$defs");
		JsonNode def = defs.get(defName);
		if (def == null) {
			throw new IllegalStateException("Contract schema is missing $defs." + defName);
		}
		ObjectNode root = (ObjectNode) def.deepCopy();
		// The defs keep the contract's internal #/$defs/... references resolvable.
		root.set("$defs", defs.deepCopy());
		return json.writeValueAsString(root);
	}

	private static JsonNode readContract(ObjectMapper json) {
		try (var stream = McpDashboardSchemas.class.getResourceAsStream("/mcp/dashboard/contract.schema.json")) {
			if (stream == null) {
				throw new IllegalStateException("MCP Dashboard contract schema resource is missing");
			}
			return json.readTree(stream);
		} catch (java.io.IOException unreadable) {
			throw new IllegalStateException("MCP Dashboard contract schema resource is unreadable", unreadable);
		}
	}
}
