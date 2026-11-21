package ru.hardcode.dashboard.client;

import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.Map;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;

/**
 * A real MCP client for KAN-39 acceptance: the standard Streamable HTTP client transport of
 * the MCP Java SDK talks to the application's Streamable HTTP endpoint ({@code POST},
 * {@code GET}, {@code DELETE} on {@code /mcp/evidence}) with a bearer token. There is no
 * hand-written JSON-RPC or REST shortcut in this client.
 */
public final class McpEvidenceMcpClient implements AutoCloseable {

	public static final String ENDPOINT = "http://localhost/mcp/evidence";

	private final McpSyncClient client;

	private McpEvidenceMcpClient(McpSyncClient client) {
		this.client = client;
	}

	/** An initialized client that sends {@code token} on every MCP request. */
	public static McpEvidenceMcpClient connect(String token) {
		return connect(ENDPOINT, token);
	}

	public static McpEvidenceMcpClient connect(String endpoint, String token) {
		if (token == null) {
			throw new IllegalArgumentException("The MCP evidence transport requires a bearer token");
		}
		// The client authenticates like any other HTTP client: every MCP request carries the
		// bearer token of the integration machine client, and nothing else.
		HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport.builder(endpoint)
				.endpoint("/mcp/evidence")
				.requestBuilder(HttpRequest.newBuilder().header("Authorization", "Bearer " + token))
				.connectTimeout(Duration.ofSeconds(10))
				.build();
		McpSyncClient client = McpClient.sync(transport)
				.clientInfo(new McpSchema.Implementation("kan39-acceptance-client", "1.0.0"))
				.requestTimeout(Duration.ofSeconds(30))
				.initializationTimeout(Duration.ofSeconds(30))
				.build();
		client.initialize();
		return new McpEvidenceMcpClient(client);
	}

	public McpSchema.ListToolsResult listTools() {
		return client.listTools();
	}

	public McpSchema.CallToolResult callTool(String name, Map<String, Object> arguments) {
		return client.callTool(new McpSchema.CallToolRequest(name, arguments));
	}

	public McpSyncClient sync() {
		return client;
	}

	@Override
	public void close() {
		client.closeGracefully();
	}
}
