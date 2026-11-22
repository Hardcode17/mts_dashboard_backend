package ru.hardcode.dashboard;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import ru.hardcode.dashboard.client.ItsmMachineJwtTestConfiguration;
import ru.hardcode.dashboard.client.Kan16JwtTestConfiguration;
import ru.hardcode.dashboard.client.McpDashboardMcpClient;
import ru.hardcode.dashboard.client.TestJwtSigner;
import ru.hardcode.dashboard.service.ItsmApiClient;
import ru.hardcode.dashboard.service.ItsmBatchIntakeService;
import ru.hardcode.dashboard.service.ItsmSnapshotPublicationService;
import ru.hardcode.dashboard.worker.ItsmWorkerSqlRepository;
import ru.hardcode.dashboard.worker.ItsmWorkerTransactionService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * KAN-86 acceptance: the six read-only MCP Dashboard tools behind the v1 contract, driven
 * by the real MCP Java SDK client through the real Spring Boot chain on
 * {@code POST /mcp/dashboard}, with Testcontainers PostgreSQL 17 for the published
 * domain, the KAN-28 journal and the confirmed official assignments.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
		"itsm.worker.enabled=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("work")
@Import({ItsmPostgresTestcontainersConfiguration.class, ItsmMachineJwtTestConfiguration.class})
class McpDashboardIT {

	private static final String HUMAN_ISSUER = "https://keycloak.test/realms/dashboard";
	private static final String HUMAN_AUDIENCE = "dashboard-human";
	private static final String HUMAN_SUBJECT = "kan7-dashboard-operator";
	private static final String MACHINE_CLIENT = "mcp-agent";
	private static final String MACHINE_ROLE = "mcp:dashboard:read";
	/** Incidents of a second source that specifically cover the contract edge cases. */
	private static final String CUSTOM_JSON = """
			[
			  {"id": "INC777777000001", "header": "Синтетический сбой оплаты заказов",
			   "description": "Оплата заказов не проходит через СБП при оформлении заказа",
			   "productName": "Оплата заказов", "productMasterId": "BI_pay",
			   "service": "Оплата заказов checkout-api", "serviceType": "Клиентский",
			   "scenarioName": "Оформление заказа",
			   "state": "Назначено", "stateEnum": "Assigned", "stateId": 0, "stateReason": null,
			   "submitDate": "2026-11-21T09:00:00Z",
			   "lastAssignmentToTeamDateTime": "2026-11-21T09:00:00Z",
			   "priorityId": 4, "priority": "Наивысший",
			   "assignedGroupName": null, "assignedEmployee": null, "assignedEmployeeLogin": null,
			   "initiatorLogin": "synthetic.operator", "initiatorName": "Синтетический оператор",
			   "deadline": null, "completionDate": null, "solution": null,
			   "updatedAt": "2026-11-22T08:00:00Z", "errorCode": "PAY-402"},
			  {"id": "INC777777000002", "header": "Замедлены возвраты оплат",
			   "description": "Возвраты по оплате заказов идут дольше норматива",
			   "productName": "Оплата заказов", "productMasterId": "BI_pay",
			   "service": "Оплата заказов checkout-api", "serviceType": "Клиентский",
			   "scenarioName": "Возврат заказа",
			   "state": "Закрыто", "stateEnum": "Closed", "stateId": 6, "stateReason": null,
			   "submitDate": "2026-11-20T09:00:00Z",
			   "lastAssignmentToTeamDateTime": "2026-11-20T09:00:00Z",
			   "priorityId": 3, "priority": "Высокий",
			   "assignedGroupName": "Payment Ops", "assignedEmployee": "Боб (bob)", "assignedEmployeeLogin": "bob",
			   "initiatorLogin": "synthetic.operator", "initiatorName": "Синтетический оператор",
			   "deadline": "2026-11-21T18:00:00Z", "completionDate": "2026-11-21T18:00:00Z",
			   "solution": "Восстановлен тестовый сервис оплаты",
			   "updatedAt": "2026-11-22T07:30:00Z", "errorCode": "PAY-402"},
			  {"id": "INC777777000003", "header": "Повторные списания по карте",
			   "description": "%s",
			   "productName": "Оплата заказов", "productMasterId": "BI_pay",
			   "service": "Оплата заказов checkout-api", "serviceType": "Клиентский",
			   "scenarioName": "Оформление заказа",
			   "state": "Назначено", "stateEnum": "Assigned", "stateId": 0, "stateReason": null,
			   "submitDate": "2026-11-21T10:00:00Z",
			   "lastAssignmentToTeamDateTime": "2026-11-21T10:00:00Z",
			   "priorityId": 2, "priority": "Средний",
			   "assignedGroupName": "Payment Ops", "assignedEmployee": "Алиса (alice)", "assignedEmployeeLogin": "alice",
			   "initiatorLogin": "synthetic.operator", "initiatorName": "Синтетический оператор",
			   "deadline": "2026-11-23T10:00:00Z", "completionDate": null, "solution": null,
			   "updatedAt": "2026-11-22T06:00:00Z"}
			]
			""".formatted("Д".repeat(5000));

	/** A second delivery that only the replay test publishes, under its own source. */
	private static final String LATE_JSON = """
			[
			  {"id": "INC888888000001", "header": "Синтетический сбой оплаты заказов",
			   "description": "Оплата заказов не проходит через СБП при оформлении заказа",
			   "productName": "Оплата заказов", "productMasterId": "BI_pay",
			   "service": "Оплата заказов checkout-api", "serviceType": "Клиентский",
			   "scenarioName": "Оформление заказа",
			   "state": "Назначено", "stateEnum": "Assigned", "stateId": 0, "stateReason": null,
			   "submitDate": "2026-11-21T09:00:00Z",
			   "lastAssignmentToTeamDateTime": "2026-11-21T09:00:00Z",
			   "priorityId": 4, "priority": "Наивысший",
			   "assignedGroupName": null, "assignedEmployee": null, "assignedEmployeeLogin": null,
			   "initiatorLogin": "synthetic.operator", "initiatorName": "Синтетический оператор",
			   "deadline": null, "completionDate": null, "solution": null,
			   "updatedAt": "2026-11-22T09:00:00Z", "errorCode": "PAY-402"},
			  {"id": "INC888888000002", "header": "Замедлены возвраты оплат",
			   "description": "Возвраты по оплате заказов идут дольше норматива",
			   "productName": "Оплата заказов", "productMasterId": "BI_pay",
			   "service": "Оплата заказов checkout-api", "serviceType": "Клиентский",
			   "scenarioName": "Возврат заказа",
			   "state": "Закрыто", "stateEnum": "Closed", "stateId": 6, "stateReason": null,
			   "submitDate": "2026-11-20T09:00:00Z",
			   "lastAssignmentToTeamDateTime": "2026-11-20T09:00:00Z",
			   "priorityId": 3, "priority": "Высокий",
			   "assignedGroupName": "Payment Ops", "assignedEmployee": "Боб (bob)", "assignedEmployeeLogin": "bob",
			   "initiatorLogin": "synthetic.operator", "initiatorName": "Синтетический оператор",
			   "deadline": "2026-11-21T18:00:00Z", "completionDate": "2026-11-21T18:00:00Z",
			   "solution": "Восстановлен тестовый сервис оплаты",
			   "updatedAt": "2026-11-22T09:10:00Z", "errorCode": "PAY-402"},
			  {"id": "INC888888000003", "header": "Повторные списания по карте",
			   "description": "Повторные списания по карте при повторном оформлении заказа",
			   "productName": "Оплата заказов", "productMasterId": "BI_pay",
			   "service": "Оплата заказов checkout-api", "serviceType": "Клиентский",
			   "scenarioName": "Оформление заказа",
			   "state": "Назначено", "stateEnum": "Assigned", "stateId": 0, "stateReason": null,
			   "submitDate": "2026-11-21T10:00:00Z",
			   "lastAssignmentToTeamDateTime": "2026-11-21T10:00:00Z",
			   "priorityId": 2, "priority": "Средний",
			   "assignedGroupName": "Payment Ops", "assignedEmployee": "Алиса (alice)", "assignedEmployeeLogin": "alice",
			   "initiatorLogin": "synthetic.operator", "initiatorName": "Синтетический оператор",
			   "deadline": "2026-11-23T10:00:00Z", "completionDate": null, "solution": null,
			   "updatedAt": "2026-11-22T09:20:00Z"}
			]
			""";

	@DynamicPropertySource
	static void authenticationAndMcp(DynamicPropertyRegistry registry) {
		// Human chain (same as the dashboard reads) plus the dedicated machine agent MVP.
		registry.add("app.auth.human.issuer", () -> HUMAN_ISSUER);
		registry.add("app.auth.human.audience", () -> HUMAN_AUDIENCE);
		registry.add("app.auth.human.jwk-set-uri", Kan16JwtTestConfiguration::jwkSetUri);
		registry.add("mcp.dashboard.enabled", () -> "true");
		registry.add("mcp.dashboard.cursor-secret", () -> "it-test-secret");
		registry.add("mcp.dashboard.snapshot-fresh", () -> "PT60S");
		registry.add("mcp.dashboard.client.audience", () -> ItsmMachineJwtTestConfiguration.AUDIENCE);
		registry.add("mcp.dashboard.client.client-id", () -> MACHINE_CLIENT);
		registry.add("mcp.dashboard.client.role", () -> MACHINE_ROLE);
	}

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private ItsmBatchIntakeService intake;

	@Autowired
	private ItsmSnapshotPublicationService publication;

	@Autowired
	private PlatformTransactionManager transactions;

	@MockitoBean
	private ItsmApiClient itsm;

	@Autowired
	private JwtEncoder jwtEncoder;

	@Autowired
	private ObjectMapper objectMapper;

	@org.springframework.boot.test.web.server.LocalServerPort
	private int port;

	private McpDashboardMcpClient client;

	@BeforeEach
	void seed() {
		jdbc.execute("""
				TRUNCATE itsm_ingest_batch, itsm_snapshot, itsm_snapshot_source, itsm_incident,
				    itsm_catalog_product, itsm_catalog_service, itsm_catalog_scenario,
				    itsm_external_group, itsm_external_person, itsm_ingest_catalog_hint,
				    deadline_risk, coordination_official_assignment CASCADE
				""");
		jdbc.update("UPDATE dashboard_publication SET event_seq = 0, revision = 0 WHERE singleton");
		jdbc.update("""
				INSERT INTO users(identity_issuer, identity_subject, login, display_name)
				VALUES (?, ?, 'operator', 'Dashboard Operator')
				ON CONFLICT (identity_issuer, identity_subject) DO NOTHING
				""", HUMAN_ISSUER, HUMAN_SUBJECT);
		publish("dashboard-fixture", 1, baseline());
		publish("mcp-fixture", 1, CUSTOM_JSON);
		client = McpDashboardMcpClient.connect("http://127.0.0.1:" + port, machineToken());
	}

	@AfterEach
	void closeClient() {
		if (client != null) {
			client.close();
			client = null;
		}
	}

	// ---------------------------------------------------------------- tools/list

	@Test
	void toolsListDeclaresExactlySixReadOnlyContractTools() {
		var tools = client.listTools().tools();
		assertThat(tools).extracting(tool -> tool.name()).containsExactlyInAnyOrder(
				"list_incidents", "get_incident", "list_incident_changes",
				"find_similar_incidents", "find_resolver_groups", "find_assignees");
		var getIncident = tools.stream().filter(tool -> tool.name().equals("get_incident")).findFirst().orElseThrow();
		assertThat(getIncident.annotations().readOnlyHint()).isTrue();
		assertThat(getIncident.inputSchema()).containsKey("required");
		assertThat(String.valueOf(getIncident.inputSchema().get("required"))).contains("incidentId");
		Object defs = getIncident.outputSchema().get("$defs");
		assertThat(defs).isNotNull();
		assertThat(((java.util.Map<?, ?>) defs).containsKey("card")).isTrue();
	}

	// ---------------------------------------------------------------- list_incidents

	@Test
	void listIncidentsFirstPageCarriesSnapshotCheckpointAndContractOrder() {
		JsonNode envelope = call("list_incidents", Map.of("limit", 3));
		assertThat(path(envelope, "ok").asBoolean()).isTrue();
		assertThat(path(envelope, "data", "items").size()).isEqualTo(3);
		JsonNode first = path(envelope, "data", "items", 0);
		// Contract order: priorityId DESC, incidentId ASC — the custom P4 incident leads.
		assertThat(first.get("incidentId").stringValue()).isEqualTo("INC777777000001");
		for (String field : List.of("incidentId", "title", "state", "priorityId", "serviceId", "siteId",
				"assignedGroupId", "assignedPersonId", "deadline", "updatedAt")) {
			assertThat(first.has(field)).as("list row must carry " + field).isTrue();
		}
		assertThat(first.get("siteId").isNull()).isTrue();
		assertThat(path(envelope, "data", "snapshotId").stringValue()).startsWith("snap-");
		assertThat(path(envelope, "data", "checkpoint").stringValue()).isNotBlank();
		assertThat(path(envelope, "meta", "sources", 0, "status").stringValue()).isEqualTo("fresh");
		assertThat(path(envelope, "meta", "generatedAt").stringValue()).endsWith("Z");
	}

	@Test
	void listIncidentsPagesContinueTheSameSnapshotThroughTheSignedCursor() {
		JsonNode first = call("list_incidents", Map.of("limit", 2));
		String nextCursor = path(first, "data", "nextCursor").stringValue();
		assertThat(nextCursor).isNotBlank();
		JsonNode second = call("list_incidents", Map.of("limit", 3, "cursor", nextCursor));
		assertThat(path(second, "data", "items").size()).isEqualTo(3);
		assertThat(path(second, "data", "snapshotId").stringValue())
				.isEqualTo(path(first, "data", "snapshotId").stringValue());
		// No overlap between pages of one walk.
		assertThat(path(second, "data", "items", 0, "incidentId").stringValue())
				.isNotEqualTo(path(first, "data", "items", 0, "incidentId").stringValue());
		// The checkpoint stays fixed for every page of the walk.
		assertThat(path(second, "data", "checkpoint").stringValue())
				.isEqualTo(path(first, "data", "checkpoint").stringValue());
	}

	@Test
	void listIncidentsChangedFiltersInvalidateTheCursor() {
		JsonNode page = call("list_incidents", Map.of("limit", 2, "filters", Map.of("states", List.of("Assigned"))));
		String cursor = path(page, "data", "nextCursor").stringValue();
		assertThat(cursor).isNotBlank();
		JsonNode rejected = call("list_incidents", Map.of("limit", 2, "cursor", cursor,
				"filters", Map.of("states", List.of("Closed"))));
		assertThat(path(rejected, "ok").asBoolean()).isFalse();
		assertThat(path(rejected, "error", "code").stringValue()).isEqualTo("INVALID_CURSOR");
	}

	@Test
	void listIncidentsUnassignedFilterFindsOnlyTheCustomIncident() {
		JsonNode page = call("list_incidents", Map.of("filters", Map.of("unassigned", true), "limit", 50));
		var ids = path(page, "data", "items").valueStream()
				.map(node -> node.get("incidentId").stringValue()).toList();
		assertThat(ids).containsExactly("INC777777000001");
	}

	// ---------------------------------------------------------------- get_incident

	@Test
	void getIncidentReturnsTheFullCardOfAPublishedIncident() {
		JsonNode envelope = call("get_incident", Map.of("incidentId", "INC777777000002"));
		assertThat(path(envelope, "ok").asBoolean()).isTrue();
		assertThat(path(envelope, "data", "solution").stringValue())
				.isEqualTo("Восстановлен тестовый сервис оплаты");
		assertThat(path(envelope, "data", "assignedGroupId").stringValue()).isEqualTo("Payment Ops");
		assertThat(path(envelope, "data", "assignedPersonId").stringValue()).isEqualTo("bob");
		assertThat(path(envelope, "data", "deadline").stringValue()).isEqualTo("2026-11-21T18:00:00Z");
		assertThat(path(envelope, "meta", "completeness").stringValue()).isEqualTo("complete");
	}

	@Test
	void getIncidentTruncatesLongFieldsWithWarningsAndPartialCompleteness() {
		JsonNode envelope = call("get_incident", Map.of("incidentId", "INC777777000003"));
		assertThat(path(envelope, "data", "description").stringValue().length()).isEqualTo(4000);
		assertThat(path(envelope, "meta", "completeness").stringValue()).isEqualTo("partial");
		assertThat(path(envelope, "meta", "warnings").toString()).contains("Описание обрезано");
	}

	@Test
	void getIncidentOfUnknownIdIsNotFoundNotAnEmptySuccess() {
		JsonNode envelope = call("get_incident", Map.of("incidentId", "INC777777999999"));
		assertThat(path(envelope, "ok").asBoolean()).isFalse();
		assertThat(path(envelope, "error", "code").stringValue()).isEqualTo("NOT_FOUND");
		assertThat(path(envelope, "error", "retryable").asBoolean()).isFalse();
		assertThat(path(envelope, "error", "recovery").stringValue()).isEqualTo("none");
	}

	// ---------------------------------------------------------------- list_incident_changes

	@Test
	void listIncidentChangesReplaysOnlyEventsAfterTheCheckpointWithStableIds() {
		JsonNode first = call("list_incidents", Map.of("limit", 1));
		String checkpoint = path(first, "data", "checkpoint").stringValue();
		// Everything delivered after the checkpoint is replayed, including a whole new source.
		publish("mcp-late", 1, LATE_JSON);

		JsonNode page = call("list_incident_changes", Map.of("cursor", checkpoint, "limit", 100));
		assertThat(path(page, "ok").asBoolean()).isTrue();
		var events = path(page, "data", "events");
		assertThat(events.size()).isEqualTo(3);
		for (JsonNode event : events.valueStream().toList()) {
			assertThat(event.get("eventId").stringValue()).startsWith("inc-change:");
			assertThat(event.get("eventId").stringValue().matches(
					"inc-change:[0-9a-f-]{36}:[1-9][0-9]{0,18}")).isTrue();
			assertThat(event.get("kind").stringValue()).isEqualTo("ADDED");
			assertThat(event.get("changedFields").toString()).contains("stateEnum");
			assertThat(event.get("eventSeq").isString()).isTrue();
		}
		// Same cursor returns the same facts: a repeat is safe for deduplication.
		JsonNode repeat = call("list_incident_changes", Map.of("cursor", checkpoint, "limit", 100));
		assertThat(path(repeat, "data", "events", 0, "eventId").stringValue())
				.isEqualTo(events.get(0).get("eventId").stringValue());
		// The returned cursor is never empty, even for an empty page.
		JsonNode emptyPage = call("list_incident_changes",
				Map.of("cursor", path(page, "data", "nextCursor").stringValue(), "limit", 100));
		assertThat(path(emptyPage, "data", "events").size()).isZero();
		assertThat(path(emptyPage, "data", "nextCursor").stringValue()).isNotBlank();
		assertThat(path(emptyPage, "data", "hasMore").asBoolean()).isFalse();
	}

	@Test
	void listIncidentChangesDetectsTheRealChangedFieldsOfAnUpdate() {
		JsonNode first = call("list_incidents", Map.of("limit", 1));
		String checkpoint = path(first, "data", "checkpoint").stringValue();
		// A second delivery of the same source with a new solution produces one UPDATED fact.
		String updated = """
				[
				  {"id": "INC777777000001", "header": "Синтетический сбой оплаты заказов",
				   "description": "Оплата заказов не проходит через СБП при оформлении заказа",
				   "productName": "Оплата заказов", "productMasterId": "BI_pay",
				   "service": "Оплата заказов checkout-api", "serviceType": "Клиентский",
				   "scenarioName": "Оформление заказа",
				   "state": "Назначено", "stateEnum": "Assigned", "stateId": 0, "stateReason": null,
				   "submitDate": "2026-11-21T09:00:00Z",
				   "lastAssignmentToTeamDateTime": "2026-11-21T09:00:00Z",
				   "priorityId": 3, "priority": "Высокий",
				   "assignedGroupName": "Payment Ops", "assignedEmployee": "Боб (bob)", "assignedEmployeeLogin": "bob",
				   "initiatorLogin": "synthetic.operator", "initiatorName": "Синтетический оператор",
				   "deadline": null, "completionDate": null, "solution": null,
				   "updatedAt": "2026-11-22T08:30:00Z", "errorCode": "PAY-402"}
				]
				""";
		publish("mcp-fixture", 2, updated);
		JsonNode page = call("list_incident_changes", Map.of("cursor", checkpoint, "limit", 100));
		var kinds = path(page, "data", "events").valueStream()
				.map(node -> node.get("kind").stringValue()).toList();
		assertThat(kinds).contains("UPDATED");
		var updatedEvent = path(page, "data", "events").valueStream()
				.filter(node -> node.get("kind").stringValue().equals("UPDATED"))
				.findFirst().orElseThrow();
		assertThat(updatedEvent.get("changedFields").toString())
				.contains("assignedGroupName").contains("priorityId").doesNotContain("stateEnum");
	}

	@Test
	void listIncidentChangesExpiresAfterAJournalEpochChange() {
		JsonNode first = call("list_incidents", Map.of("limit", 1));
		String checkpoint = path(first, "data", "checkpoint").stringValue();
		jdbc.update("UPDATE mcp_dashboard_state SET journal_epoch = gen_random_uuid() WHERE singleton");
		JsonNode page = call("list_incident_changes", Map.of("cursor", checkpoint));
		assertThat(path(page, "ok").asBoolean()).isFalse();
		assertThat(path(page, "error", "code").stringValue()).isEqualTo("CURSOR_EXPIRED");
		assertThat(path(page, "error", "recovery").stringValue()).isEqualTo("resync_snapshot");
	}

	// ---------------------------------------------------------------- find_similar_incidents

	@Test
	void findSimilarIncidentsPrefersTheExactErrorCodeAndCarriesEvidence() {
		JsonNode envelope = call("find_similar_incidents", Map.of("incidentId", "INC777777000001"));
		assertThat(path(envelope, "ok").asBoolean()).as("envelope: %s", envelope).isTrue();
		var items = path(envelope, "data", "items");
		assertThat(items.size()).isGreaterThanOrEqualTo(1);
		JsonNode analogue = items.get(0);
		// The exact error-code match of the same service ranks first.
		assertThat(analogue.get("incident").get("incidentId").stringValue()).isEqualTo("INC777777000002");
		assertThat(analogue.get("matchedSignals").toString()).contains("Совпадает код ошибки")
				.contains("Совпадает сервис");
		assertThat(analogue.get("resolutionSummary").stringValue()).contains("тестовый сервис оплаты");
		assertThat(analogue.get("historicalResolverGroupId").stringValue()).isEqualTo("Payment Ops");
		assertThat(path(analogue, "evidence", "kind").stringValue()).isEqualTo("history");
		assertThat(path(analogue, "evidence", "sourceRef").stringValue()).isEqualTo("itsm:INC777777000002");
		assertThat(path(envelope, "data", "nextCursor").isNull());
	}

	@Test
	void findSimilarIncidentsRejectsUnresolvedStatesAndUnknownTarget() {
		JsonNode invalidState = call("find_similar_incidents", Map.of("incidentId", "INC777777000001",
				"filters", Map.of("states", List.of("Assigned"))));
		assertThat(path(invalidState, "error", "code").stringValue()).isEqualTo("INVALID_ARGUMENT");

		JsonNode unknown = call("find_similar_incidents", Map.of("incidentId", "INC777777999999"));
		assertThat(path(unknown, "error", "code").stringValue()).isEqualTo("NOT_FOUND");
	}

	// ---------------------------------------------------------------- find_resolver_groups

	@Test
	void findResolverGroupsConfirmsTheSingleOfficialOwnerAndRecommendsIt() {
		// The publication already journaled the official assignment of the incident with a
		// null team; an official confirmation fills the same row in place.
		jdbc.update("""
				UPDATE coordination_official_assignment SET team = 'Payment Ops'
				WHERE source_id = 'mcp-fixture' AND incident_id = 'INC777777000001'
				""");
		JsonNode envelope = call("find_resolver_groups", Map.of("incidentId", "INC777777000001"));
		assertThat(path(envelope, "ok").asBoolean()).isTrue();
		JsonNode first = path(envelope, "data", "items", 0);
		assertThat(first.get("groupId").stringValue()).isEqualTo("Payment Ops");
		assertThat(first.get("responsibility").stringValue()).isEqualTo("confirmed");
		assertThat(first.get("evidence").get(0).get("kind").stringValue()).isEqualTo("directory");
		assertThat(path(envelope, "data", "recommendedGroupId").stringValue()).isEqualTo("Payment Ops");
		assertThat(path(envelope, "data", "reason").stringValue()).isEqualTo("confirmed_owner");
	}

	@Test
	void findResolverGroupsSuppressesTheRecommendationWhenAlreadyAssigned() {
		// The handoff observation of the publication carries the official team and executor.
		JsonNode envelope = call("find_resolver_groups", Map.of("incidentId", "INC777777000003"));
		assertThat(path(envelope, "data", "recommendedGroupId").isNull());
		assertThat(path(envelope, "data", "reason").stringValue()).isEqualTo("already_assigned");
	}

	@Test
	void findResolverGroupsReportsNoOwnerWithoutAssignment() {
		JsonNode envelope = call("find_resolver_groups", Map.of("incidentId", "INC777777000001"));
		assertThat(path(envelope, "data", "recommendedGroupId").isNull());
		assertThat(path(envelope, "data", "reason").stringValue()).isEqualTo("no_owner");
	}

	// ---------------------------------------------------------------- find_assignees

	@Test
	void findAssigneesReadsTheExternalDirectoryAndKeepsUnknownVerdicts() {
		when(itsm.users()).thenReturn(List.of(
				Map.of("sysId", "p2", "login", "bob", "displayName", "Боб", "groupName", "Payment Ops"),
				Map.of("sysId", "p1", "login", "alice", "displayName", "Алиса", "groupName", "Payment Ops"),
				Map.of("sysId", "p3", "login", "carol", "displayName", "Кэрол", "groupName", "Other")));
		JsonNode envelope = call("find_assignees", Map.of("groupId", "Payment Ops"));
		assertThat(path(envelope, "ok").asBoolean()).isTrue();
		var ids = path(envelope, "data", "items").valueStream()
				.map(node -> node.get("personId").stringValue()).toList();
		// Contract order: personId ASC.
		assertThat(ids).containsExactly("p1", "p2");
		assertThat(path(envelope, "data", "items", 0, "competence").stringValue()).isEqualTo("unknown");
		assertThat(path(envelope, "data", "items", 0, "onDuty").stringValue()).isEqualTo("unknown");
		assertThat(path(envelope, "data", "nextCursor").isNull());
	}

	@Test
	void findAssigneesFiltersByQuerySubstringAndReportsEmptySearchAsSuccess() {
		when(itsm.users()).thenReturn(List.of(
				Map.of("sysId", "p1", "login", "alice", "displayName", "Алиса", "groupName", "Payment Ops"),
				Map.of("sysId", "p2", "login", "bob", "displayName", "Боб", "groupName", "Payment Ops")));
		JsonNode queried = call("find_assignees", Map.of("groupId", "Payment Ops", "query", "бо"));
		assertThat(path(queried, "data", "items").size()).isEqualTo(1);
		assertThat(path(queried, "data", "items", 0, "personId").stringValue()).isEqualTo("p2");

		JsonNode empty = call("find_assignees", Map.of("groupId", "Nobody", "limit", 5));
		assertThat(path(empty, "ok").asBoolean()).isTrue();
		assertThat(path(empty, "data", "items").size()).isZero();
	}

	// ---------------------------------------------------------------- access over HTTP

	@Test
	void unauthenticatedCallIsRejectedWith401() throws Exception {
		mockMvc.perform(rpc(null)).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
	}

	@Test
	void machineClientWithoutTheDashboardRoleIsRejectedWith403() throws Exception {
		String token = ItsmMachineJwtTestConfiguration.token(jwtEncoder,
				ItsmMachineJwtTestConfiguration.ISSUER, ItsmMachineJwtTestConfiguration.AUDIENCE,
				MACHINE_CLIENT, List.of("itsm:import"), null);
		mockMvc.perform(rpc(token)).andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("MCP_DASHBOARD_ACCESS_DENIED"));
	}

	@Test
	void coordinatorTokenReachesTheMcpTransport() {
		// The human coordinator chain is the second accepted grant of the same endpoint.
		try (var coordinator = McpDashboardMcpClient.connect("http://127.0.0.1:" + port, humanToken())) {
			assertThat(coordinator.listTools().tools())
					.extracting(tool -> tool.name())
					.contains("list_incidents", "find_resolver_groups");
		}
	}

	// ---------------------------------------------------------------- fixture helpers

	private static MockHttpServletRequestBuilder rpc(String token) {
		var request = post("/mcp/dashboard").contentType(MediaType.APPLICATION_JSON).content("""
				{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18",
				"capabilities":{},"clientInfo":{"name":"access-probe","version":"1.0.0"}}}
				""");
		return token == null ? request : request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
	}

	/** Every tool call goes through the real MCP client and its envelope. */
	private JsonNode call(String tool, Map<String, Object> arguments) {
		var result = client.callTool(tool, arguments);
		String body = objectMapper.writeValueAsString(result.structuredContent());
		return objectMapper.readTree(body);
	}

	private static JsonNode path(JsonNode root, Object... fieldPath) {
		JsonNode node = root;
		for (Object field : fieldPath) {
			node = field instanceof Integer index ? node.get(index) : node.get((String) field);
		}
		return node;
	}

	private String machineToken() {
		return ItsmMachineJwtTestConfiguration.token(jwtEncoder,
				ItsmMachineJwtTestConfiguration.ISSUER, ItsmMachineJwtTestConfiguration.AUDIENCE,
				MACHINE_CLIENT, List.of(MACHINE_ROLE), null);
	}

	private static String humanToken() {
		return Kan16JwtTestConfiguration.signer().token(TestJwtSigner.claims()
				.issuer(HUMAN_ISSUER).audience(HUMAN_AUDIENCE).subject(HUMAN_SUBJECT)
				.claim("azp", "dashboard-web")
				.expiresAt(Instant.now().plusSeconds(300)));
	}

	private String baseline() {
		try (var fixture = getClass().getResourceAsStream("/itsm/character_problems.json")) {
			assertThat(fixture).isNotNull();
			return new String(fixture.readAllBytes(), StandardCharsets.UTF_8);
		} catch (java.io.IOException unreadable) {
			throw new IllegalStateException(unreadable);
		}
	}

	private void publish(String sourceId, long version, String json) {
		var batchId = intake.receive(UUID.randomUUID().toString(), sourceId, Long.toString(version),
				json.getBytes(StandardCharsets.UTF_8));
		var worker = new ItsmWorkerTransactionService(new ItsmWorkerSqlRepository(jdbc), transactions);
		assertThat(worker.claimAndProcess(publication, "mcp-dashboard-test").batchId()).isEqualTo(batchId);
		assertThat(jdbc.queryForObject("SELECT status FROM itsm_ingest_batch WHERE id = ?", String.class, batchId))
				.as("batch diagnostic: %s",
						jdbc.queryForObject("SELECT diagnostic FROM itsm_ingest_batch WHERE id = ?", String.class, batchId))
				.isEqualTo("SUCCEEDED");
	}
}
