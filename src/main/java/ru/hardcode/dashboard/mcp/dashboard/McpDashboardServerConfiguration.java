package ru.hardcode.dashboard.mcp.dashboard;

import java.time.Duration;
import java.util.List;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.hardcode.dashboard.configuration.mcpdashboard.ConditionalOnDashboardMcp;
import ru.hardcode.dashboard.configuration.mcpdashboard.McpDashboardSecurityConfiguration;
import tools.jackson.databind.json.JsonMapper;

/**
 * Dedicated, authenticated Streamable HTTP servlet of the read-only MCP Dashboard
 * (KAN-86) at {@code /mcp/dashboard}. It is independent of the demo evidence transport,
 * which is not extended into a general dashboard MCP.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnDashboardMcp
public class McpDashboardServerConfiguration {

	public static final String ENDPOINT = McpDashboardSecurityConfiguration.MCP_DASHBOARD_PATH;

	@Bean
	McpJsonMapper mcpDashboardJsonMapper(JsonMapper mapper) {
		return new JacksonMcpJsonMapper(mapper);
	}

	@Bean
	HttpServletStreamableServerTransportProvider mcpDashboardTransport(McpJsonMapper mapper) {
		return HttpServletStreamableServerTransportProvider.builder()
				.jsonMapper(mapper)
				.mcpEndpoint(ENDPOINT)
				.keepAliveInterval(Duration.ofSeconds(30))
				.build();
	}

	@Bean
	ServletRegistrationBean<HttpServletStreamableServerTransportProvider> mcpDashboardServlet(
			HttpServletStreamableServerTransportProvider transport
	) {
		var registration = new ServletRegistrationBean<>(transport, ENDPOINT);
		registration.setName("mcpDashboardTransport");
		registration.setAsyncSupported(true);
		return registration;
	}

	@Bean
	McpSyncServer mcpDashboardSyncServer(
			HttpServletStreamableServerTransportProvider mcpDashboardTransport,
			@org.springframework.beans.factory.annotation.Qualifier("mcpDashboardTools")
			List<McpServerFeatures.SyncToolSpecification> mcpDashboardTools
	) {
		return McpServer.sync(mcpDashboardTransport)
				.serverInfo(McpSchema.Implementation.builder("dashboard", "1.0.0").build())
				.capabilities(McpSchema.ServerCapabilities.builder()
						.tools(true)
						.build())
				.tools(mcpDashboardTools)
				.build();
	}
}
