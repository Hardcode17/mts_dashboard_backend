package ru.hardcode.dashboard.client;

import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.Map;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;

/**
 * Real MCP client for the KAN-86 acceptance: the standard Streamable HTTP client transport
 * of the MCP Java SDK talks to {@code /mcp/dashboard} with a bearer token. There is no
 * hand-written JSON-RPC or REST shortcut in this client.
 */
public final class McpDashboardMcpClient implements AutoCloseable {

	private final McpSyncClient client;

	private McpDashboardMcpClient(McpSyncClient client) {
		this.client = client;
	}

	/** An initialized client that sends {@code token} on every MCP request. */
	public static McpDashboardMcpClient connect(String baseUrl, String token) {
		HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport.builder(baseUrl)
				.endpoint("/mcp/dashboard")
				.requestBuilder(HttpRequest.newBuilder().header("Authorization", "Bearer " + token))
				.connectTimeout(Duration.ofSeconds(10))
				.build();
		McpSyncClient client = McpClient.sync(transport)
				.clientInfo(new McpSchema.Implementation("kan86-acceptance-client", "1.0.0"))
				.requestTimeout(Duration.ofSeconds(30))
				.initializationTimeout(Duration.ofSeconds(30))
				.build();
		client.initialize();
		return new McpDashboardMcpClient(client);
	}

	public McpSchema.ListToolsResult listTools() {
		return client.listTools();
	}

	public McpSchema.CallToolResult callTool(String name, Map<String, Object> arguments) {
		return client.callTool(new McpSchema.CallToolRequest(name, arguments));
	}

	@Override
	public void close() {
		client.closeGracefully();
	}
}
