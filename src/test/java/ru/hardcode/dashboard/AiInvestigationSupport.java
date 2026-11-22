package ru.hardcode.dashboard;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.TestExecutionListener;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.postgresql.PostgreSQLContainer;
import ru.hardcode.dashboard.client.AiModelStub;
import ru.hardcode.dashboard.client.ItsmMachineJwtTestConfiguration;
import ru.hardcode.dashboard.client.Kan16JwtTestConfiguration;
import ru.hardcode.dashboard.client.McpEvidenceDemoDiagnostics;
import ru.hardcode.dashboard.client.McpEvidenceStandSources;
import ru.hardcode.dashboard.client.TestJwtSigner;
import ru.hardcode.dashboard.service.ItsmBatchIntakeService;
import ru.hardcode.dashboard.service.ItsmSnapshotPublicationService;
import ru.hardcode.dashboard.worker.ItsmWorkerSqlRepository;
import ru.hardcode.dashboard.worker.ItsmWorkerTransactionService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Shared KAN-40 acceptance environment: a dedicated PostgreSQL 17 container, the evidence
 * profile with the controlled stand ITSM/log/diagnostics sources of KAN-39, the {@code ai}
 * profile pointing Spring AI at a controlled local model stub, and a clock pinned inside the
 * bounded demo window, so the launch window is deterministic. The stand evidence sources and
 * the model are scripted by each test directly; the ITSM snapshot is published through the
 * real intake and publication services.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = "itsm.worker.enabled=false")
@AutoConfigureMockMvc
@Import({
		AiInvestigationSupport.InvestigationDatabaseConfiguration.class,
		AiInvestigationSupport.FixedClockConfiguration.class,
		ItsmMachineJwtTestConfiguration.class,
		Kan16JwtTestConfiguration.class
})
@TestExecutionListeners(
		listeners = AiInvestigationSupport.DiagnosticsListener.class,
		mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS
)
abstract class AiInvestigationSupport {

	public static final String SCENARIO = "scenario-checkout-freeze";
	public static final String RUN = "run-KAN40-100-A";
	public static final String INCIDENT = "INC0000420001";
	public static final String OTHER_INCIDENT = "INC0000420002";
	public static final String ITSM_SERVICE = "svc-itsm-checkout";
	public static final String LOG_SERVICE = "svc-log-checkout";
	public static final String DB_SERVICE = "svc-db-checkout";
	public static final String LOG_CUSTOMER = "svc-log-customer";

	public static final Instant NOW = Instant.parse("2026-11-20T10:00:00Z");
	public static final String WINDOW_FROM = "2026-11-20T08:00:00Z";
	public static final String WINDOW_TO = "2026-11-20T10:00:00Z";

	public static final String GROUP_PAYMENTS = "Дежурные платёжных сервисов";
	public static final String GROUP_DATA = "Команда хранения данных";
	public static final String HEADER = "Отказ авторизации платежей на кассах";
	public static final String DESCRIPTION = "Кассы торговой сети не проходят авторизацию платежей.";
	public static final String HYPOTHESIS = "Вероятная причина — тайм-аут авторизации платежей из-за недоступности внутреннего сервиса платежного шлюза.";
	public static final String NEXT_STEP = "Проверить состояние платежного шлюза и очереди авторизаций у команды платёжных сервисов.";

	static final String HUMAN_ISSUER = "https://keycloak.test/realms/dashboard";
	static final String HUMAN_AUDIENCE = "dashboard-human";
	static final String HUMAN_SUBJECT = "kan40-dashboard-operator";

	protected static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-bookworm");
	protected static final McpEvidenceStandSources STAND = startStand();
	protected static final AiModelStub MODEL = AiModelStub.start();

	@Autowired MockMvc mvc;
	@Autowired JdbcTemplate jdbc;
	@Autowired PlatformTransactionManager transactions;
	@Autowired ItsmBatchIntakeService intake;
	@Autowired ItsmSnapshotPublicationService publication;

	@DynamicPropertySource
	static void investigationSources(DynamicPropertyRegistry registry) {
		registry.add("spring.security.user.name", () -> "operator");
		registry.add("spring.security.user.password", () -> "kan40-test-password");
		registry.add("app.auth.human.issuer", () -> HUMAN_ISSUER);
		registry.add("app.auth.human.audience", () -> HUMAN_AUDIENCE);
		registry.add("app.auth.human.jwk-set-uri", Kan16JwtTestConfiguration::jwkSetUri);
		registry.add("itsm.integration.auth.issuer", () -> ItsmMachineJwtTestConfiguration.ISSUER);
		registry.add("itsm.integration.auth.jwk-set-uri",
				() -> "https://keycloak.test/realms/itsm/protocol/openid-connect/certs");
		registry.add("itsm.integration.auth.audience", () -> ItsmMachineJwtTestConfiguration.AUDIENCE);
		registry.add("itsm.integration.auth.client-id", () -> ItsmMachineJwtTestConfiguration.CLIENT_ID);
		registry.add("itsm.integration.auth.import-role", () -> ItsmMachineJwtTestConfiguration.IMPORT_ROLE);
		registry.add("mcp.evidence.client.audience", () -> ItsmMachineJwtTestConfiguration.AUDIENCE);
		registry.add("mcp.evidence.client.client-id", () -> ItsmMachineJwtTestConfiguration.CLIENT_ID);
		registry.add("mcp.evidence.client.role", () -> ItsmMachineJwtTestConfiguration.IMPORT_ROLE);
		registry.add("mcp.evidence.demo.database.url", POSTGRES::getJdbcUrl);
		registry.add("mcp.evidence.demo.database.username", () -> McpEvidenceDemoDiagnostics.READ_ONLY_USER);
		registry.add("mcp.evidence.demo.database.password", () -> "kan39-reader");
		registry.add("mcp.evidence.demo.database.view", () -> McpEvidenceDemoDiagnostics.TABLE);
		registry.add("mcp.evidence.demo.scenario-id", () -> SCENARIO);
		registry.add("mcp.evidence.demo.incident-id", () -> INCIDENT);
		registry.add("mcp.evidence.demo.scenario-run-id", () -> RUN);
		registry.add("mcp.evidence.demo.itsm-service-key", () -> ITSM_SERVICE);
		registry.add("mcp.evidence.demo.log-service-key", () -> LOG_SERVICE);
		registry.add("mcp.evidence.demo.database-service-key", () -> DB_SERVICE);
		registry.add("mcp.evidence.demo.max-window", () -> "PT2H");
		registry.add("mcp.evidence.demo.observation-window.from", () -> "2026-11-20T08:00:00Z");
		registry.add("mcp.evidence.demo.observation-window.to", () -> "2026-11-20T12:00:00Z");
		registry.add("mcp.evidence.demo.itsm.base-url", STAND::baseUrl);
		registry.add("mcp.evidence.demo.log.base-url", STAND::baseUrl);
		registry.add("mcp.evidence.demo.log.customer-id", () -> LOG_CUSTOMER);
		// The real Spring AI DeepSeek client calls the controlled local stub; the profile and
		// the key are the same gates the production deployment uses.
		registry.add("spring.ai.deepseek.base-url", MODEL::baseUrl);
		registry.add("spring.ai.deepseek.api-key", () -> "kan40-test-key");
		registry.add("ai.investigation.model-api-key", () -> "kan40-test-key");
		registry.add("ai.investigation.model-timeout", () -> "PT5S");
	}

	/** Clears published data, old runs and the model answers of a previous test. */
	void resetEnvironment() {
		jdbc.execute("""
				TRUNCATE ai_investigation_unavailable_source, ai_investigation_fact, ai_investigation_run
				""");
		jdbc.execute("""
				TRUNCATE itsm_ingest_batch, itsm_snapshot, itsm_snapshot_source, itsm_incident,
				    itsm_catalog_product, itsm_catalog_service, itsm_catalog_scenario,
				    itsm_external_group, itsm_external_person, itsm_ingest_catalog_hint CASCADE
				""");
		jdbc.update("UPDATE dashboard_publication SET event_seq = 0, revision = 0 WHERE singleton");
		jdbc.update("""
				INSERT INTO users(identity_issuer, identity_subject, login, display_name)
				VALUES (?, ?, 'operator', 'Dashboard Operator')
				ON CONFLICT (identity_issuer, identity_subject) DO NOTHING
				""", HUMAN_ISSUER, HUMAN_SUBJECT);
		STAND.reset();
		MODEL.reset();
		DiagnosticsListener.diagnostics().reset();
		STAND.putIncident(INCIDENT, "Assigned", "Высокий", GROUP_PAYMENTS, Instant.parse("2026-11-20T09:05:00Z"));
		STAND.addLog("log-rec-1", Instant.parse("2026-11-20T09:15:00Z"), "payment authorization timed out",
				"WARN", INCIDENT, RUN);
		DiagnosticsListener.diagnostics().insert("diag-rec-1", INCIDENT, RUN, DB_SERVICE,
				Instant.parse("2026-11-20T09:30:00Z"), 42);
	}

	MockHttpServletRequestBuilder authenticatedGet(String path, Object... variables) {
		return get(path, variables).accept(MediaType.APPLICATION_JSON)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + humanToken());
	}

	MockHttpServletRequestBuilder launch(String incidentId) {
		return post("/api/incidents/{incidentId}/investigations", incidentId)
				.accept(MediaType.APPLICATION_JSON)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + humanToken());
	}

	/** The published revision of the current snapshot, as the card and the runs bind it. */
	long revision() {
		return jdbc.queryForObject("SELECT revision FROM dashboard_publication WHERE singleton", Long.class);
	}

	static String humanToken() {
		return Kan16JwtTestConfiguration.signer().token(TestJwtSigner.claims()
				.issuer(HUMAN_ISSUER).audience(HUMAN_AUDIENCE).subject(HUMAN_SUBJECT)
				.claim("azp", "dashboard-web")
				.expiresAt(Instant.now().plusSeconds(300)));
	}

	UUID receive(long sourceVersion, String json) {
		return intake.receive(UUID.randomUUID().toString(), "dashboard-fixture", Long.toString(sourceVersion),
				json.getBytes(StandardCharsets.UTF_8));
	}

	void publish(UUID batchId) {
		var worker = new ItsmWorkerTransactionService(new ItsmWorkerSqlRepository(jdbc), transactions);
		assertThat(worker.claimAndProcess(publication, "dashboard-test").batchId()).isEqualTo(batchId);
		assertThat(jdbc.queryForObject("SELECT status FROM itsm_ingest_batch WHERE id = ?", String.class, batchId))
				.as("batch diagnostic: %s", jdbc.queryForObject(
						"SELECT diagnostic FROM itsm_ingest_batch WHERE id = ?", String.class, batchId))
				.isEqualTo("SUCCEEDED");
	}

	/** One active incident of the published snapshot; header and group are the levers tests change. */
	static String activeIncident(String id, String header, String group) {
		return """
				{"id":"%s","stateEnum":"Assigned","priorityId":3,"submitDate":"2026-11-20T08:30:00Z",
				 "deadline":"2026-11-21T08:30:00Z","productName":"Платёжный шлюз","header":"%s",
				 "description":"%s","assignedGroupName":"%s",
				 "lastAssignmentToTeamDateTime":"2026-11-20T08:40:00Z","solution":null}
				""".formatted(id, header, DESCRIPTION, group);
	}

	/** One completed incident: a completed state must carry a completion date and a solution. */
	static String resolvedIncident(String id, String header, String solution) {
		return """
				{"id":"%s","stateEnum":"Resolved","priorityId":3,"submitDate":"2026-11-20T08:30:00Z",
				 "deadline":"2026-11-21T08:30:00Z","productName":"Платёжный шлюз","header":"%s",
				 "description":"%s","assignedGroupName":"%s","completionDate":"2026-11-20T09:45:00Z",
				 "solution":"%s"}
				""".formatted(id, header, DESCRIPTION, GROUP_PAYMENTS, solution);
	}

	static String snapshot(String... records) {
		return "[" + String.join(",", records) + "]";
	}

	/**
	 * Prepares the stand diagnostics table once Spring has started and manages the
	 * PostgreSQL 17 container, exactly as the KAN-39 environment does.
	 */
	public static class DiagnosticsListener implements TestExecutionListener {

		private static McpEvidenceDemoDiagnostics prepared;

		@Override
		public void prepareTestInstance(TestContext testContext) {
			testContext.getApplicationContext();
			if (prepared != null) {
				return;
			}
			synchronized (DiagnosticsListener.class) {
				if (prepared != null) {
					return;
				}
				McpEvidenceDemoDiagnostics.createStandSchema(POSTGRES);
				prepared = new McpEvidenceDemoDiagnostics(POSTGRES);
				if (prepared.readOnlyConnectionCannotWrite()) {
					return;
				}
				prepared = null;
				throw new IllegalStateException("The diagnostics evidence connection must be read-only");
			}
		}

		public static McpEvidenceDemoDiagnostics diagnostics() {
			if (prepared == null) {
				throw new IllegalStateException("The stand diagnostics table is not prepared");
			}
			return prepared;
		}
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class InvestigationDatabaseConfiguration {

		@Bean
		@ServiceConnection
		PostgreSQLContainer investigationPostgres17Container() {
			return POSTGRES;
		}
	}

	/** A clock pinned to {@link #NOW}, so the launch window and timestamps are deterministic. */
	@TestConfiguration(proxyBeanMethods = false)
	public static class FixedClockConfiguration {

		@Bean
		@Primary
		public Clock kan40Clock() {
			return Clock.fixed(NOW, ZoneOffset.UTC);
		}
	}

	private static McpEvidenceStandSources startStand() {
		try {
			return McpEvidenceStandSources.start();
		} catch (IOException exception) {
			throw new IllegalStateException("Cannot start the stand ITSM/log sources", exception);
		}
	}
}
