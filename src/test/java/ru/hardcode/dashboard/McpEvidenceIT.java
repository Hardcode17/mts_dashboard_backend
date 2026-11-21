package ru.hardcode.dashboard;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.web.servlet.MockMvc;
import ru.hardcode.dashboard.client.ItsmMachineJwtTestConfiguration;
import ru.hardcode.dashboard.client.McpEvidenceDemoDiagnostics;
import ru.hardcode.dashboard.client.McpEvidenceMcpClient;
import ru.hardcode.dashboard.McpEvidenceSupport.DiagnosticsListener;
import ru.hardcode.dashboard.entity.ItsmBatchStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * KAN-39 acceptance through the standard MCP Streamable HTTP transport (MCP Java SDK client,
 * {@code POST /mcp/evidence}) on the real Spring Boot chain, MockMvc as the HTTP layer and
 * Testcontainers PostgreSQL 17 for intake and the stand diagnostics table. The stand ITSM
 * and log sources are controlled local HTTP sources whose records the tests change directly.
 */
// Keep intake observable without the independent publisher changing its state during MCP reads.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "itsm.worker.enabled=false")
@AutoConfigureMockMvc
class McpEvidenceIT extends McpEvidenceSupport {

	private static final String TOOL_CONTEXT = "get_incident_context";
	private static final String TOOL_SOURCE = "get_service_evidence";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private JwtEncoder jwtEncoder;

	@Autowired
	private ObjectMapper objectMapper;

	@org.springframework.boot.test.web.server.LocalServerPort
	private int port;
	private McpEvidenceMcpClient client;
	private McpEvidenceDemoDiagnostics diagnostics;
	private String token;

	@BeforeEach
	void setUp() {
		jdbcTemplate.update("INSERT INTO users(identity_issuer,identity_subject,login,display_name) VALUES ('https://stand.test','operator','operator','Operator') ON CONFLICT DO NOTHING");
		resetStand();
		diagnostics = DiagnosticsListener.diagnostics();
		token = ItsmMachineJwtTestConfiguration.machineToken(jwtEncoder);
		// KAN-27 intake of a later test class must never observe a leftover batch.
		jdbcTemplate.update("DELETE FROM itsm_ingest_batch");
		client = McpEvidenceMcpClient.connect("http://127.0.0.1:" + port, token);
	}

	@org.junit.jupiter.api.AfterEach
	void closeClient() {
		if (client != null) {
			client.close();
		}
	}

	// ---------------------------------------------------------------- positive

	@Test
	void mcpClient_callsAssignedCommandLogMessageAndLastRecordTimeOfOneIncident() throws Exception {
		McpSchema.ListToolsResult tools = client.listTools();
		assertThat(tools.tools()).extracting(McpSchema.Tool::name)
				.containsExactlyInAnyOrder(TOOL_CONTEXT, TOOL_SOURCE);

		JsonNode body = callContext(INCIDENT, RUN, WINDOW_FROM, WINDOW_TO);
		assertThat(text(body, "scenarioId")).isEqualTo(SCENARIO);
		assertThat(text(body, "incidentId")).isEqualTo(INCIDENT);
		assertThat(text(body, "scenarioRunId")).isEqualTo(RUN);
		assertThat(text(body, "windowFrom")).isEqualTo(WINDOW_FROM);
		assertThat(text(body, "windowTo")).isEqualTo(WINDOW_TO);
		assertThat(body.get("unavailable")).isEmpty();

		JsonNode itsm = evidence(body, "ITSM");
		assertRef(itsm, "ITSM", ITSM_SERVICE, "itsm:" + INCIDENT);
		assertThat(text(itsm, "observedAt")).isEqualTo("2026-11-20T09:05:00Z");
		assertThat(text(itsm.get("observation"), "assignedCommand")).isEqualTo(COMMAND_PAYMENTS);
		assertThat(text(itsm.get("observation"), "level")).isEqualTo("HIGH");
		assertThat(text(itsm.get("observation"), "serviceState")).isEqualTo("Assigned");
		assertThat(text(itsm.get("observation"), "evidenceId")).isEqualTo("itsm:" + INCIDENT);

		JsonNode log = evidence(body, "LOG");
		assertRef(log, "LOG", LOG_SERVICE, "log:log-rec-1");
		assertThat(text(log, "observedAt")).isEqualTo("2026-11-20T09:15:00Z");
		assertThat(text(log.get("observation"), "message")).isEqualTo("payment authorization timed out");
		assertThat(log.get("observation").get("messageTruncated").isNull()).isTrue();
		assertThat(text(log.get("observation"), "evidenceId")).isEqualTo("log:log-rec-1");
		assertThat(text(log.get("observation"), "observedAt")).isEqualTo("2026-11-20T09:15:00Z");
		assertThat(text(log.get("observation"), "level")).isEqualTo("WARN");

		JsonNode database = evidence(body, "DATABASE");
		assertRef(database, "DATABASE", DB_SERVICE, "diag:diag-rec-1");
		assertThat(text(database, "observedAt")).isEqualTo("2026-11-20T09:30:00Z");
		assertThat(database.get("observation").get("recordCount").longValue()).isEqualTo(1);
		assertThat(database.get("observation").get("value").longValue()).isEqualTo(42);

		// Only the fixed reads of the mapped incident/run were sent to the stand sources.
		assertThat(STAND.logRequests().stream().map(request -> java.net.URLDecoder.decode(
				request, java.nio.charset.StandardCharsets.UTF_8)).toList()).containsExactly(
				"GET /api/logs?customerId=" + LOG_CUSTOMER + "&incidentId=" + INCIDENT
						+ "&scenarioRunId=" + RUN + "&from=" + WINDOW_FROM + "&to=" + WINDOW_TO + "&limit=1");
		assertThat(STAND.requests()).contains("GET /api/mock/incidents/" + INCIDENT);
	}

	@Test
	void serviceEvidenceTool_readsOneBoundSource() throws Exception {
		JsonNode source = callSource(INCIDENT, RUN, LOG_SERVICE, WINDOW_FROM, WINDOW_TO);
		assertThat(text(source, "incidentId")).isEqualTo(INCIDENT);
		assertThat(text(source, "scenarioRunId")).isEqualTo(RUN);
		assertThat(text(source, "source")).isEqualTo("LOG");
		assertThat(text(source, "serviceKey")).isEqualTo(LOG_SERVICE);
		assertRef(source.get("evidence"), "LOG", LOG_SERVICE, "log:log-rec-1");

		JsonNode itsm = callSource(INCIDENT, RUN, ITSM_SERVICE, WINDOW_FROM, WINDOW_TO);
		assertThat(text(itsm.get("evidence").get("observation"), "assignedCommand")).isEqualTo(COMMAND_PAYMENTS);

		JsonNode database = callSource(INCIDENT, RUN, DB_SERVICE, WINDOW_FROM, WINDOW_TO);
		assertRef(database.get("evidence"), "DATABASE", DB_SERVICE, "diag:diag-rec-1");
	}

	@Test
	void changedStandRecord_changesTheNextAnswer() throws Exception {
		JsonNode before = evidence(callContext(INCIDENT, RUN, WINDOW_FROM, WINDOW_TO), "LOG");
		assertRef(before, "LOG", LOG_SERVICE, "log:log-rec-1");

		// The stand gets a newer record of the mapped incident and run.
		STAND.addLog("log-rec-2", Instant.parse("2026-11-20T09:45:00Z"), "payment retried and declined",
				"ERROR", INCIDENT, RUN);
		JsonNode after = evidence(callContext(INCIDENT, RUN, WINDOW_FROM, WINDOW_TO), "LOG");
		assertRef(after, "LOG", LOG_SERVICE, "log:log-rec-2");
		assertThat(text(after, "observedAt")).isEqualTo("2026-11-20T09:45:00Z");
		assertThat(text(after.get("observation"), "level")).isEqualTo("ERROR");
		assertThat(text(after.get("observation"), "message")).isEqualTo("payment retried and declined");

		// Editing the record on the stand changes the reported message.
		STAND.updateLogMessage("log-rec-2", "payment retried and accepted");
		assertThat(text(evidence(callContext(INCIDENT, RUN, WINDOW_FROM, WINDOW_TO), "LOG")
				.get("observation"), "message")).isEqualTo("payment retried and accepted");

		// Moving the assignment on the stand changes the assigned command.
		STAND.putIncident(INCIDENT, "WorkInProgress", "Наивысший", GROUP_DATA, Instant.parse("2026-11-20T09:50:00Z"));
		JsonNode itsm = evidence(callContext(INCIDENT, RUN, WINDOW_FROM, WINDOW_TO), "ITSM");
		assertThat(text(itsm, "observedAt")).isEqualTo("2026-11-20T09:50:00Z");
		assertThat(text(itsm.get("observation"), "assignedCommand")).isEqualTo(COMMAND_DATA);
		assertThat(text(itsm.get("observation"), "level")).isEqualTo("CRITICAL");
		assertThat(text(itsm.get("observation"), "serviceState")).isEqualTo("WorkInProgress");
	}

	@Test
	void changedContainerDatabaseRecord_changesTimeIdAndValue() throws Exception {
		diagnostics.insert("diag-rec-2", INCIDENT, DB_SERVICE, Instant.parse("2026-11-20T09:50:00Z"), 7);

		JsonNode after = evidence(callContext(INCIDENT, RUN, WINDOW_FROM, WINDOW_TO), "DATABASE");
		assertRef(after, "DATABASE", DB_SERVICE, "diag:diag-rec-2");
		assertThat(text(after, "observedAt")).isEqualTo("2026-11-20T09:50:00Z");
		assertThat(after.get("observation").get("value").longValue()).isEqualTo(7);
		assertThat(after.get("observation").get("recordCount").longValue()).isEqualTo(2);
		assertThat(text(after.get("observation"), "evidenceId")).isEqualTo("diag:diag-rec-2");

		// Editing the latest row in the container changes the reported value in place.
		diagnostics.updateValue("diag-rec-2", 8);
		JsonNode edited = evidence(callContext(INCIDENT, RUN, WINDOW_FROM, WINDOW_TO), "DATABASE");
		assertThat(text(edited, "evidenceId")).isEqualTo("diag:diag-rec-2");
		assertThat(edited.get("observation").get("value").longValue()).isEqualTo(8);

		// Moving the latest row earlier hands the "latest" position to the other record.
		diagnostics.updateRecordedAt("diag-rec-2", Instant.parse("2026-11-20T09:10:00Z"));
		JsonNode moved = evidence(callContext(INCIDENT, RUN, WINDOW_FROM, WINDOW_TO), "DATABASE");
		assertThat(text(moved, "evidenceId")).isEqualTo("diag:diag-rec-1");
		assertThat(Instant.parse(text(moved, "observedAt"))).isEqualTo(Instant.parse("2026-11-20T09:30:00Z"));
		assertThat(diagnostics.count()).isEqualTo(3);
	}

	@Test
	void evidenceIsReadOnly_andLeavesNoTrace() throws Exception {
		UUID batchKey = UUID.randomUUID();
		mockMvc.perform(post("/api/integrations/itsm/batches")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
						.header("Idempotency-Key", batchKey.toString())
						.header("X-Source-Id", "itsm-source")
						.header("X-Source-Version", "1")
						.contentType(MediaType.APPLICATION_JSON)
						.content("[{\"id\":\"" + INCIDENT + "\"}]"))
				.andExpect(status().isAccepted());

		callContext(INCIDENT, RUN, WINDOW_FROM, WINDOW_TO);
		callSource(INCIDENT, RUN, DB_SERVICE, WINDOW_FROM, WINDOW_TO);
		callSource(INCIDENT, RUN, LOG_SERVICE, WINDOW_FROM, WINDOW_TO);

		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM itsm_ingest_batch", Long.class)).isEqualTo(1L);
		assertThat(jdbcTemplate.queryForObject("SELECT status FROM itsm_ingest_batch WHERE id = ?",
				String.class, batchKey)).isEqualTo(ItsmBatchStatus.RECEIVED.name());
		assertThat(diagnostics.count()).isEqualTo(2);
		assertThat(STAND.requests()).allMatch(request -> request.startsWith("GET /api/mock/incidents/" + INCIDENT)
				|| request.startsWith("GET /api/logs?customerId=" + LOG_CUSTOMER + "&incidentId=" + INCIDENT + "&"));
	}

	// ---------------------------------------------------------------- negative

	@Test
	void unknownServiceKey_isRejectedWithoutContactingAnySource() {
		for (String unknown : new String[] {"svc-unknown", ITSM_SERVICE + "-2", ITSM_SERVICE.toUpperCase()}) {
			McpSchema.CallToolResult result = call(TOOL_SOURCE, sourceArguments(INCIDENT, RUN, unknown,
					WINDOW_FROM, WINDOW_TO));
			assertToolError(result, "MCP_EVIDENCE_SERVICE_UNKNOWN");
		}
		assertThat(STAND.requests()).isEmpty();
		assertThat(diagnostics.count()).isEqualTo(2);
	}

	@Test
	void unknownIncidentScenarioOrRun_isRejected() {
		assertToolError(call(TOOL_CONTEXT, contextArguments(SCENARIO, OTHER_INCIDENT, RUN, WINDOW_FROM, WINDOW_TO)),
				"MCP_EVIDENCE_INCIDENT_NOT_MAPPED");
		assertToolError(call(TOOL_CONTEXT, contextArguments(SCENARIO, INCIDENT, "run-other", WINDOW_FROM, WINDOW_TO)),
				"MCP_EVIDENCE_SCENARIO_RUN_UNKNOWN");
		assertToolError(call(TOOL_CONTEXT, contextArguments("scenario-other", INCIDENT, RUN, WINDOW_FROM, WINDOW_TO)),
				"MCP_EVIDENCE_DEMO_SCENARIO_UNKNOWN");
		assertToolError(call(TOOL_SOURCE, sourceArguments(OTHER_INCIDENT, OTHER_RUN, DB_SERVICE,
				WINDOW_FROM, WINDOW_TO)), "MCP_EVIDENCE_INCIDENT_NOT_MAPPED");
		assertThat(STAND.requests()).isEmpty();
	}

	@Test
	void forbiddenWindows_areRejected() {
		Object[][] invalid = {
				{"2026-11-20 09:00", WINDOW_TO},
				{WINDOW_FROM, "tomorrow"},
				{WINDOW_FROM, WINDOW_FROM},
				{WINDOW_TO, WINDOW_FROM},
				// longer than the configured two-hour maximum
				{"2026-11-20T08:00:00Z", "2026-11-20T10:00:00.000001Z"},
		};
		for (Object[] window : invalid) {
			assertToolError(call(TOOL_SOURCE, sourceArguments(INCIDENT, RUN, DB_SERVICE,
					(String) window[0], (String) window[1])), "MCP_EVIDENCE_WINDOW_INVALID");
		}
		// sub-microsecond bounds are refused instead of being rounded into the window
		assertToolError(call(TOOL_CONTEXT, contextArguments(SCENARIO, INCIDENT, RUN,
				"2026-11-20T09:00:00.0000001Z", WINDOW_TO)), "MCP_EVIDENCE_WINDOW_INVALID");

		Object[][] outside = {
				{"2026-11-20T07:59:59.999999Z", "2026-11-20T09:00:00Z"},
				{"2026-11-20T11:00:00Z", "2026-11-20T12:00:00.000001Z"},
				{"2026-11-20T11:00:00Z", "2026-11-20T12:30:00Z"},
		};
		for (Object[] window : outside) {
			assertToolError(call(TOOL_CONTEXT, contextArguments(SCENARIO, INCIDENT, RUN,
					(String) window[0], (String) window[1])), "MCP_EVIDENCE_WINDOW_OUTSIDE_DEMO");
		}
		assertThat(STAND.requests()).isEmpty();
	}

	@Test
	void unavailableLogSource_isAnErrorAndNeverAnEmptySuccess() {
		STAND.startRefusal();

		McpSchema.CallToolResult source = call(TOOL_SOURCE, sourceArguments(INCIDENT, RUN, LOG_SERVICE,
				WINDOW_FROM, WINDOW_TO));
		assertToolError(source, "MCP_EVIDENCE_SOURCE_REJECTED");
		assertThat(toolText(source)).doesNotContain("\"evidenceId\"");

		JsonNode body = callContext(INCIDENT, RUN, WINDOW_FROM, WINDOW_TO);
		assertThat(body.get("evidence")).hasSize(2);
		assertThat(body.get("evidence").findValuesAsString("source")).containsExactlyInAnyOrder("ITSM", "DATABASE");
		assertThat(body.get("unavailable")).hasSize(1);
		JsonNode unavailable = body.get("unavailable").get(0);
		assertThat(text(unavailable, "source")).isEqualTo("LOG");
		assertThat(text(unavailable, "serviceKey")).isEqualTo(LOG_SERVICE);
		assertThat(text(unavailable, "code")).isEqualTo("MCP_EVIDENCE_SOURCE_REJECTED");
	}

	@Test
	void logSourceWithoutRecord_isAnErrorAndNeverAnEmptySuccess() {
		STAND.removeLog("log-rec-1");

		McpSchema.CallToolResult source = call(TOOL_SOURCE, sourceArguments(INCIDENT, RUN, LOG_SERVICE,
				WINDOW_FROM, WINDOW_TO));
		assertToolError(source, "MCP_EVIDENCE_SOURCE_UNAVAILABLE");

		JsonNode body = callContext(INCIDENT, RUN, WINDOW_FROM, WINDOW_TO);
		assertThat(body.get("evidence").findValuesAsString("source")).doesNotContain("LOG");
		assertThat(text(body.get("unavailable").get(0), "source")).isEqualTo("LOG");
		assertThat(text(body.get("unavailable").get(0), "code")).isEqualTo("MCP_EVIDENCE_SOURCE_UNAVAILABLE");
	}

	@Test
	void logRecordOfAnotherIncident_isNeverAttributed() {
		// The other incident shares the log customer and its newer record sits in the window.
		STAND.addLog("log-other-2", Instant.parse("2026-11-20T09:59:00Z"), "other incident detail",
				"ERROR", OTHER_INCIDENT, OTHER_RUN);
		JsonNode log = evidence(callContext(INCIDENT, RUN, WINDOW_FROM, WINDOW_TO), "LOG");
		assertThat(text(log, "evidenceId")).isEqualTo("log:log-rec-1");
		assertThat(text(log.get("observation"), "message")).isEqualTo("payment authorization timed out");

		// A record of the mapped incident under another run is not this run's evidence either.
		STAND.addLog("log-rec-run", Instant.parse("2026-11-20T09:58:00Z"), "replayed for another run",
				"INFO", INCIDENT, OTHER_RUN);
		assertThat(text(evidence(callContext(INCIDENT, RUN, WINDOW_FROM, WINDOW_TO), "LOG"), "evidenceId"))
				.isEqualTo("log:log-rec-1");

		// A record that names no owner is unusable even if it is the only one.
		STAND.removeLog("log-rec-1");
		STAND.addLog("log-unowned", Instant.parse("2026-11-20T09:40:00Z"), "unowned record", "INFO", null, null);
		McpSchema.CallToolResult result = call(TOOL_SOURCE, sourceArguments(INCIDENT, RUN, LOG_SERVICE,
				WINDOW_FROM, WINDOW_TO));
		assertToolError(result, "MCP_EVIDENCE_SOURCE_UNAVAILABLE");
	}

	@Test
	void untrustedLogFields_areBoundedAndSanitized() {
		String secretMessage = "auth failed password=Sup3rSecret token=abc123 card 4111 1111 1111 1111\nnext line "
				+ "x".repeat(400);
		STAND.removeLog("log-rec-1");
		STAND.addLog("log-secret", Instant.parse("2026-11-20T09:30:00Z"), secretMessage, "CRITICAL LEVEL",
				INCIDENT, RUN);

		JsonNode log = evidence(callContext(INCIDENT, RUN, WINDOW_FROM, WINDOW_TO), "LOG");
		String message = text(log.get("observation"), "message");
		assertThat(message.length()).isLessThanOrEqualTo(200);
		assertThat(message).doesNotContain("Sup3rSecret", "abc123", "4111 1111", "\n");
		assertThat(log.get("observation").get("messageTruncated").booleanValue()).isTrue();
		assertThat(log.get("observation").get("level").isNull()).isTrue();
	}

	// ---------------------------------------------------------------- boundary

	@Test
	void windowBoundaries_areInclusiveAndTheMaximumWindowIsAccepted() {
		STAND.removeLog("log-rec-1");
		STAND.addLog("log-boundary", Instant.parse(WINDOW_TO), "upper boundary record", "INFO", INCIDENT, RUN);
		assertThat(text(evidence(callContext(INCIDENT, RUN, WINDOW_FROM, WINDOW_TO), "LOG"), "evidenceId"))
				.isEqualTo("log:log-boundary");

		// Exactly one microsecond outside the window is not evidence.
		assertToolError(call(TOOL_SOURCE, sourceArguments(INCIDENT, RUN, LOG_SERVICE,
				WINDOW_FROM, "2026-11-20T09:59:59.999999Z")), "MCP_EVIDENCE_SOURCE_UNAVAILABLE");

		STAND.removeLog("log-boundary");
		STAND.addLog("log-lower", Instant.parse(WINDOW_FROM), "lower boundary record", "INFO", INCIDENT, RUN);
		assertThat(text(evidence(callContext(INCIDENT, RUN, WINDOW_FROM, WINDOW_TO), "LOG"), "evidenceId"))
				.isEqualTo("log:log-lower");

		// The exact two-hour maximum and the exact demo window edges are accepted.
		JsonNode exactMaximum = callContext(INCIDENT, RUN, "2026-11-20T08:00:00Z", "2026-11-20T10:00:00Z");
		assertThat(text(exactMaximum, "windowFrom")).isEqualTo("2026-11-20T08:00:00Z");
		JsonNode lastDemoHour = callContext(INCIDENT, RUN, "2026-11-20T10:00:00Z", "2026-11-20T12:00:00Z");
		assertThat(text(lastDemoHour, "windowTo")).isEqualTo("2026-11-20T12:00:00Z");
	}

	@Test
	void diagnosticDatabaseBoundary_isInclusiveAndRecordsOutsideAreExcluded() {
		diagnostics.insert("diag-left", INCIDENT, DB_SERVICE, Instant.parse("2026-11-20T09:00:00Z"), 1);
		diagnostics.insert("diag-right", INCIDENT, DB_SERVICE, Instant.parse("2026-11-20T10:00:00Z"), 2);

		JsonNode database = evidence(callContext(INCIDENT, RUN, WINDOW_FROM, WINDOW_TO), "DATABASE");
		assertThat(text(database, "evidenceId")).isEqualTo("diag:diag-right");
		assertThat(database.get("observation").get("recordCount").longValue()).isEqualTo(3);
		assertThat(database.get("observation").get("value").longValue()).isEqualTo(2);

		JsonNode leftOnly = evidence(callContext(INCIDENT, RUN, WINDOW_FROM, "2026-11-20T09:00:00.000001Z"),
				"DATABASE");
		assertThat(text(leftOnly, "evidenceId")).isEqualTo("diag:diag-left");
		assertThat(text(leftOnly, "observedAt")).isEqualTo("2026-11-20T09:00:00Z");
		assertThat(leftOnly.get("observation").get("recordCount").longValue()).isEqualTo(1);
	}

	@Test
	void twoIncidentsWithTheSameExternalServiceId_evidenceBelongsToTheExplicitScenarioRunOnly() {
		diagnostics.insert("diag-other-run", INCIDENT, OTHER_RUN, DB_SERVICE,
				Instant.parse("2026-11-20T09:59:00Z"), 88);
		JsonNode body = callContext(INCIDENT, RUN, WINDOW_FROM, WINDOW_TO);
		for (JsonNode ref : body.get("evidence")) {
			assertThat(ref).isNotNull();
			assertThat(text(ref, "incidentId")).isEqualTo(INCIDENT);
			assertThat(text(ref, "scenarioRunId")).isEqualTo(RUN);
		}
		JsonNode database = evidence(body, "DATABASE");
		assertThat(text(database, "evidenceId")).isEqualTo("diag:diag-rec-1");
		assertThat(database.get("observation").get("recordCount").longValue()).isEqualTo(1);
		assertThat(database.get("observation").get("value").longValue()).isEqualTo(42);
		assertThat(text(evidence(body, "LOG"), "evidenceId")).isEqualTo("log:log-rec-1");

		// The other incident shares the external service identity but has no explicit mapping.
		assertToolError(call(TOOL_SOURCE, sourceArguments(OTHER_INCIDENT, OTHER_RUN, DB_SERVICE,
				WINDOW_FROM, WINDOW_TO)), "MCP_EVIDENCE_INCIDENT_NOT_MAPPED");
		assertToolError(call(TOOL_SOURCE, sourceArguments(OTHER_INCIDENT, RUN, LOG_SERVICE,
				WINDOW_FROM, WINDOW_TO)), "MCP_EVIDENCE_INCIDENT_NOT_MAPPED");
	}

	// ---------------------------------------------------------------- access

	@Test
	void anonymousAndUnauthorizedCallers_areRefusedByTheMcpTransport() throws Exception {
		Map<String, Object> arguments = contextArguments(SCENARIO, INCIDENT, RUN, WINDOW_FROM, WINDOW_TO);
		String rpc = jsonRpc(arguments);

		var anonymous = mockMvc.perform(post("/mcp/evidence")
						.contentType(MediaType.APPLICATION_JSON)
						.accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
						.content(rpc))
				.andReturn();
		assertThat(anonymous.getResponse().getStatus()).isEqualTo(401);
		assertError(anonymous.getResponse().getContentAsString(), "ITSM_MACHINE_AUTH_REQUIRED");

		var wrongAudience = mockMvc.perform(post("/mcp/evidence")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + ItsmMachineJwtTestConfiguration.token(
								jwtEncoder, ItsmMachineJwtTestConfiguration.ISSUER, "other-audience",
								ItsmMachineJwtTestConfiguration.CLIENT_ID,
								List.of(ItsmMachineJwtTestConfiguration.IMPORT_ROLE), null))
						.contentType(MediaType.APPLICATION_JSON)
						.accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
						.content(rpc))
				.andReturn();
		assertThat(wrongAudience.getResponse().getStatus()).isEqualTo(401);

		var withoutRole = mockMvc.perform(post("/mcp/evidence")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + ItsmMachineJwtTestConfiguration.token(
								jwtEncoder, ItsmMachineJwtTestConfiguration.ISSUER,
								ItsmMachineJwtTestConfiguration.AUDIENCE,
								ItsmMachineJwtTestConfiguration.CLIENT_ID, null, null))
						.contentType(MediaType.APPLICATION_JSON)
						.accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
						.content(rpc))
				.andReturn();
		assertThat(withoutRole.getResponse().getStatus()).isEqualTo(403);
		assertError(withoutRole.getResponse().getContentAsString(), "MCP_EVIDENCE_ACCESS_DENIED");

		var otherClient = mockMvc.perform(post("/mcp/evidence")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + ItsmMachineJwtTestConfiguration.token(
								jwtEncoder, ItsmMachineJwtTestConfiguration.ISSUER,
								ItsmMachineJwtTestConfiguration.AUDIENCE, "another-client",
								List.of(ItsmMachineJwtTestConfiguration.IMPORT_ROLE), null))
						.contentType(MediaType.APPLICATION_JSON)
						.accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
						.content(rpc))
				.andReturn();
		assertThat(otherClient.getResponse().getStatus()).isEqualTo(403);
		assertThat(otherClient.getResponse().getContentAsString()).doesNotContain("assignedCommand");

		// The application login session is not an MCP identity either.
		var humanSession = mockMvc.perform(post("/mcp/evidence")
						.with(org.springframework.security.test.web.servlet.request
								.SecurityMockMvcRequestPostProcessors.user("operator").roles("USER"))
						.contentType(MediaType.APPLICATION_JSON)
						.accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
						.content(rpc))
				.andReturn();
		assertThat(humanSession.getResponse().getStatus()).isIn(401, 403);
		assertThat(humanSession.getResponse().getContentAsString()).doesNotContain("assignedCommand");

		assertThat(STAND.requests()).isEmpty();
	}

	@Test
	void evidenceRoute_doesNotOpenTheApplicationChain() throws Exception {
		// The rest of the application keeps the unchanged login contract.
		mockMvc.perform(get("/health")).andExpect(status().isOk());
		mockMvc.perform(get("/api/snapshot/status")).andExpect(status().isUnauthorized());
		mockMvc.perform(get("/api/snapshot/status").with(jwt().jwt(token -> token.issuer("https://stand.test").subject("operator").claim("azp","dashboard-web"))))
				.andExpect(status().isOk());
		mockMvc.perform(formLogin().user("operator").password("kan39-test-password"))
				.andExpect(authenticated().withUsername("operator"));

		// An authenticated caller may still send a body to another MVC endpoint.
		mockMvc.perform(post("/mcp/other")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"probe\":true}"))
				.andExpect(status().is4xxClientError());
		mockMvc.perform(post("/api/integrations/itsm/batches")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
						.header("Idempotency-Key", UUID.randomUUID().toString())
						.header("X-Source-Id", "itsm-source")
						.header("X-Source-Version", "7")
						.contentType(MediaType.APPLICATION_JSON)
						.content("[{\"id\":\"" + INCIDENT + "\"}]"))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.batchId").exists());
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM itsm_ingest_batch", Long.class)).isEqualTo(1L);
	}

	@Test
	void otherHttpMethodsOnTheEvidenceRoute_areRefused() throws Exception {
		mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
						"/mcp/evidence")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{}"))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("MCP_EVIDENCE_ACCESS_DENIED"));
		assertThat(STAND.requests()).isEmpty();
	}

	// ---------------------------------------------------------------- helpers

	private JsonNode callContext(String incident, String run, String from, String to) {
		return decode(call(TOOL_CONTEXT, contextArguments(SCENARIO, incident, run, from, to)));
	}

	private JsonNode callSource(String incident, String run, String serviceKey, String from, String to) {
		return decode(call(TOOL_SOURCE, sourceArguments(incident, run, serviceKey, from, to)));
	}

	private McpSchema.CallToolResult call(String tool, Map<String, Object> arguments) {
		return client.callTool(tool, arguments);
	}

	private static Map<String, Object> contextArguments(
			String scenarioId, String incidentId, String run, String from, String to
	) {
		Map<String, Object> arguments = new LinkedHashMap<>();
		arguments.put("scenarioId", scenarioId);
		arguments.put("incidentId", incidentId);
		arguments.put("scenarioRunId", run);
		arguments.put("windowFrom", from);
		arguments.put("windowTo", to);
		return arguments;
	}

	private static Map<String, Object> sourceArguments(
			String incidentId, String run, String serviceKey, String from, String to
	) {
		Map<String, Object> arguments = new LinkedHashMap<>();
		arguments.put("incidentId", incidentId);
		arguments.put("scenarioRunId", run);
		arguments.put("serviceKey", serviceKey);
		arguments.put("windowFrom", from);
		arguments.put("windowTo", to);
		return arguments;
	}

	private static String jsonRpc(Map<String, Object> arguments) {
		Map<String, Object> params = new HashMap<>();
		params.put("name", TOOL_CONTEXT);
		params.put("arguments", arguments);
		Map<String, Object> rpc = new LinkedHashMap<>();
		rpc.put("jsonrpc", "2.0");
		rpc.put("id", 1);
		rpc.put("method", "tools/call");
		rpc.put("params", params);
		return new ObjectMapper().writeValueAsString(rpc);
	}

	private JsonNode decode(McpSchema.CallToolResult result) {
		assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
		return objectMapper.readTree(toolText(result));
	}

	private static String toolText(McpSchema.CallToolResult result) {
		assertThat(result.content()).hasSize(1);
		assertThat(result.content().get(0)).isInstanceOf(McpSchema.TextContent.class);
		return ((McpSchema.TextContent) result.content().get(0)).text();
	}

	private void assertToolError(McpSchema.CallToolResult result, String code) {
		assertThat(result.isError()).as("tool error %s", code).isEqualTo(Boolean.TRUE);
		JsonNode error = objectMapper.readTree(toolText(result));
		assertThat(text(error, "code")).isEqualTo(code);
		assertThat(text(error, "message")).isNotBlank();
		assertThat(toolText(result)).doesNotContain("SELECT", "Exception", "itsm_ingest_batch");
	}

	private void assertError(String body, String code) {
		assertThat(body).contains("\"code\":\"" + code + "\"");
	}

	private static String text(JsonNode node, String field) {
		JsonNode value = node == null ? null : node.get(field);
		return value == null || value.isNull() ? null : value.stringValue();
	}

	private static JsonNode evidence(JsonNode body, String source) {
		for (JsonNode ref : body.get("evidence")) {
			if (source.equals(text(ref, "source"))) {
				return ref;
			}
		}
		assertThat(false).as("evidence %s must be present", source).isTrue();
		return null;
	}

	private static void assertRef(JsonNode ref, String source, String serviceKey, String evidenceId) {
		assertThat(ref).as("evidence %s must be present", source).isNotNull();
		assertThat(text(ref, "source")).isEqualTo(source);
		assertThat(text(ref, "incidentId")).isEqualTo(INCIDENT);
		assertThat(text(ref, "scenarioRunId")).isEqualTo(RUN);
		assertThat(text(ref, "serviceKey")).isEqualTo(serviceKey);
		assertThat(text(ref, "evidenceId")).isEqualTo(evidenceId);
		assertThat(text(ref.get("observation"), "evidenceId")).isEqualTo(evidenceId);
		assertThat(text(ref, "windowFrom")).isEqualTo(WINDOW_FROM);
		assertThat(text(ref, "windowTo")).isEqualTo(WINDOW_TO);
	}
}
