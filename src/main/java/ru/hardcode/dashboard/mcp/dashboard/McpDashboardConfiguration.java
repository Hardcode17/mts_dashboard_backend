package ru.hardcode.dashboard.mcp.dashboard;

import io.modelcontextprotocol.json.McpJsonMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.hardcode.dashboard.configuration.mcpdashboard.ConditionalOnDashboardMcp;
import ru.hardcode.dashboard.configuration.mcpdashboard.McpDashboardProperties;
import ru.hardcode.dashboard.exception.AppException;
import ru.hardcode.dashboard.service.ItsmApiClient;
import ru.hardcode.dashboard.service.mcpdashboard.McpDashboardCursors;
import ru.hardcode.dashboard.service.mcpdashboard.McpDashboardException;
import ru.hardcode.dashboard.service.mcpdashboard.McpDashboardService;
import tools.jackson.databind.ObjectMapper;

/**
 * Shared beans of the MCP Dashboard adapter: the signed cursor codec and the ITSM
 * directory port that turns client failures into a contract source error. They live apart
 * from the tool registration so the service and the tools never depend on each other.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnDashboardMcp
public class McpDashboardConfiguration {

	@Bean
	McpDashboardCursors mcpDashboardCursors(ObjectMapper objectMapper, McpDashboardProperties properties) {
		return new McpDashboardCursors(objectMapper, properties.cursorSecret());
	}

	@Bean
	McpDashboardService.ItsmDirectory mcpDashboardItsmDirectory(ItsmApiClient itsm) {
		return () -> {
			try {
				return itsm.users();
			} catch (AppException rejected) {
				throw McpDashboardException.sourceUnavailable(
						"ITSM directory is unavailable: " + rejected.getAppError().getCode());
			}
		};
	}
}
