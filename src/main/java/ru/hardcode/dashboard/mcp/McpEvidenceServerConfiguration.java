package ru.hardcode.dashboard.mcp;

import ru.hardcode.dashboard.configuration.evidence.ConditionalOnEvidenceMapping;
import ru.hardcode.dashboard.configuration.evidence.McpEvidenceDemoProperties;
import java.time.Duration;
import java.util.List;

import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import tools.jackson.databind.json.JsonMapper;
import ru.hardcode.dashboard.configuration.evidence.McpEvidenceDemoProperties;
import ru.hardcode.dashboard.configuration.evidence.McpEvidenceSecurityConfiguration;

/** Dedicated, authenticated Streamable HTTP servlet for the mapped demo evidence tools. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnEvidenceMapping
@EnableConfigurationProperties(McpEvidenceDemoProperties.class)
public class McpEvidenceServerConfiguration {

	public static final String ENDPOINT = McpEvidenceSecurityConfiguration.MCP_EVIDENCE_PATH;

	@Bean
	McpJsonMapper mcpEvidenceJsonMapper(JsonMapper mapper) {
		return new JacksonMcpJsonMapper(mapper);
	}

	@Bean
	HttpServletStreamableServerTransportProvider mcpEvidenceTransport(McpJsonMapper mapper) {
		return HttpServletStreamableServerTransportProvider.builder()
				.jsonMapper(mapper)
				.mcpEndpoint(ENDPOINT)
				.keepAliveInterval(Duration.ofSeconds(30))
				.build();
	}

	@Bean
	ServletRegistrationBean<HttpServletStreamableServerTransportProvider> mcpEvidenceServlet(
			HttpServletStreamableServerTransportProvider transport
	) {
		var registration = new ServletRegistrationBean<>(transport, ENDPOINT);
		registration.setName("mcpEvidenceTransport");
		registration.setAsyncSupported(true);
		return registration;
	}

	@Bean
	McpSyncServer mcpEvidenceSyncServer(
			HttpServletStreamableServerTransportProvider mcpEvidenceTransport,
			@org.springframework.beans.factory.annotation.Qualifier("mcpEvidenceTools")
			List<McpServerFeatures.SyncToolSpecification> mcpEvidenceTools
	) {
		return McpServer.sync(mcpEvidenceTransport)
				.serverInfo(McpSchema.Implementation.builder("dashboard-evidence", "1.0.0").build())
				.capabilities(McpSchema.ServerCapabilities.builder()
						.tools(true)
						.build())
				.tools(mcpEvidenceTools)
				.build();
	}
}
