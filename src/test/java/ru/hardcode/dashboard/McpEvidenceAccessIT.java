package ru.hardcode.dashboard;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import ru.hardcode.dashboard.client.ItsmMachineJwtTestConfiguration;
import ru.hardcode.dashboard.service.evidence.McpEvidenceService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * KAN-39 fail-closed configuration: evidence tooling is switched on (the evidence profile
 * selects the Streamable HTTP MCP server) but no complete demo mapping exists. Then there is
 * no evidence tool, no evidence service and no evidence endpoint, while the transport refuses
 * every call and the existing application and intake contract is unchanged.
 */
@SpringBootTest(properties = {
		"itsm.integration.auth.issuer=" + ItsmMachineJwtTestConfiguration.ISSUER,
		"itsm.integration.auth.jwk-set-uri=https://keycloak.test/realms/itsm/protocol/openid-connect/certs",
		"itsm.integration.auth.audience=" + ItsmMachineJwtTestConfiguration.AUDIENCE,
		"itsm.integration.auth.client-id=" + ItsmMachineJwtTestConfiguration.CLIENT_ID,
		"itsm.integration.auth.import-role=" + ItsmMachineJwtTestConfiguration.IMPORT_ROLE,
		// An incomplete mapping: the incident and the log service key are missing.
		"mcp.evidence.demo.scenario-id=scenario-checkout-freeze",
		"mcp.evidence.demo.scenario-run-id=run-KAN39-100-A",
		"mcp.evidence.demo.itsm-service-key=svc-itsm-checkout"
})
@AutoConfigureMockMvc
@ActiveProfiles({"demo", "evidence"})
@Import({ItsmPostgresTestcontainersConfiguration.class, ItsmMachineJwtTestConfiguration.class})
class McpEvidenceAccessIT {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private JwtEncoder jwtEncoder;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private ApplicationContext context;

	@Test
	void withoutACompleteMapping_noEvidenceToolServiceOrRouteExists() throws Exception {
		assertThat(context.getBeanNamesForType(McpEvidenceService.class)).isEmpty();
		assertThat(context.getBeanNamesForType(McpServerFeatures.SyncToolSpecification.class)).isEmpty();
		assertThat(context.getBeanNamesForType(McpSyncServer.class)).isEmpty();
		// The evidence servlet/transport is not registered either.
		assertThat(context.getBeanNamesForType(
				io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider.class)).isEmpty();
		assertThat(context.getBeansOfType(org.springframework.boot.web.servlet.ServletRegistrationBean.class)
				.values()).extracting(org.springframework.boot.web.servlet.ServletRegistrationBean::getServletName)
				.doesNotContain("mcpEvidenceTransport");

		// The evidence chain only exists for a mapped scenario; the application chain still does.
		assertThat(context.getBeansOfType(SecurityFilterChain.class))
				.isNotEmpty()
				.doesNotContainKey("mcpEvidenceSecurityFilterChain");

		MvcResult refused = mockMvc.perform(initializeRequest()).andReturn();
		assertThat(refused.getResponse().getStatus()).isIn(401, 403, 404);

		String bearer = ItsmMachineJwtTestConfiguration.machineToken(jwtEncoder);
		jdbcTemplate.update("DELETE FROM itsm_ingest_batch");
		mockMvc.perform(post("/api/integrations/itsm/batches")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer)
						.header("Idempotency-Key", UUID.randomUUID().toString())
						.header("X-Source-Id", "itsm-source")
						.header("X-Source-Version", "1")
						.contentType(MediaType.APPLICATION_JSON)
						.content("[]"))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.batchId").exists());
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM itsm_ingest_batch", Long.class)).isEqualTo(1L);

		mockMvc.perform(get("/health")).andExpect(status().isOk());
	}

	private static MockHttpServletRequestBuilder initializeRequest() {
		Map<String, Object> params = Map.of(
				"protocolVersion", "2025-03-26",
				"capabilities", Map.of(),
				"clientInfo", Map.of("name", "kan39-probe", "version", "1.0.0")
		);
		Map<String, Object> rpc = new LinkedHashMap<>();
		rpc.put("jsonrpc", "2.0");
		rpc.put("id", 1);
		rpc.put("method", "initialize");
		rpc.put("params", params);
		return post("/mcp/evidence")
				.contentType(MediaType.APPLICATION_JSON)
				.accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
				.content(new ObjectMapper().writeValueAsString(rpc));
	}
}
