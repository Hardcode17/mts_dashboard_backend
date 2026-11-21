package ru.hardcode.dashboard;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.TestExecutionListener;
import org.springframework.test.context.TestExecutionListeners;
import org.testcontainers.postgresql.PostgreSQLContainer;
import ru.hardcode.dashboard.client.ItsmMachineJwtTestConfiguration;
import ru.hardcode.dashboard.client.McpEvidenceDemoDiagnostics;
import ru.hardcode.dashboard.client.McpEvidenceStandSources;

/**
 * Shared KAN-39 acceptance environment: a dedicated PostgreSQL 17 container, the
 * evidence profile that switches on the standard Streamable HTTP MCP server, one controlled
 * stand ITSM/log source and a clock pinned inside the bounded demo window, so the window
 * rules are deterministic.
 *
 * <p>The stand diagnostics table is created once the container is running. Spring's container
 * lifecycle starts it and registers the connection properties before any test class, so the
 * table is prepared in a {@link TestExecutionListener} before the first test method.
 */
@ActiveProfiles({"demo", "evidence"})
@Import({
		McpEvidenceSupport.EvidenceDatabaseConfiguration.class,
		ItsmMachineJwtTestConfiguration.class,
		McpEvidenceSupport.FixedClockConfiguration.class
})
@TestExecutionListeners(
		listeners = McpEvidenceSupport.DiagnosticsListener.class,
		mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS
)
public abstract class McpEvidenceSupport {

	public static final String SCENARIO = "scenario-checkout-freeze";
	public static final String RUN = "run-KAN39-100-A";
	public static final String OTHER_RUN = "run-KAN39-200-B";
	public static final String INCIDENT = "INC-KAN39-100";
	public static final String OTHER_INCIDENT = "INC-KAN39-200";
	public static final String ITSM_SERVICE = "svc-itsm-checkout";
	public static final String LOG_SERVICE = "svc-log-checkout";
	public static final String DB_SERVICE = "svc-db-checkout";
	public static final String LOG_CUSTOMER = "svc-log-customer";

	public static final Instant NOW = Instant.parse("2026-11-20T10:00:00Z");
	public static final String WINDOW_FROM = "2026-11-20T09:00:00Z";
	public static final String WINDOW_TO = "2026-11-20T10:00:00Z";

	public static final String GROUP_PAYMENTS = "Дежурные платёжных сервисов";
	public static final String GROUP_DATA = "Команда хранения данных";
	public static final String COMMAND_PAYMENTS = GROUP_PAYMENTS;
	public static final String COMMAND_DATA = GROUP_DATA;

	protected static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-bookworm");
	protected static final McpEvidenceStandSources STAND = startStand();

	@DynamicPropertySource
	static void evidenceSources(DynamicPropertyRegistry registry) {
		registry.add("spring.security.user.name", () -> "operator");
		registry.add("spring.security.user.password", () -> "kan39-test-password");
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
		// A fixed stand window; the tests pin the clock inside it.
		registry.add("mcp.evidence.demo.observation-window.from", () -> "2026-11-20T08:00:00Z");
		registry.add("mcp.evidence.demo.observation-window.to", () -> "2026-11-20T12:00:00Z");
		registry.add("mcp.evidence.demo.itsm.base-url", STAND::baseUrl);
		registry.add("mcp.evidence.demo.log.base-url", STAND::baseUrl);
		registry.add("mcp.evidence.demo.log.customer-id", () -> LOG_CUSTOMER);
	}

	/** Restores the one mapped stand scenario and clears the diagnostics table. */
	protected void resetStand() {
		STAND.reset();
		DiagnosticsListener.diagnostics().reset();
		STAND.putIncident(INCIDENT, "Assigned", "Высокий", GROUP_PAYMENTS, Instant.parse("2026-11-20T09:05:00Z"));
		STAND.putIncident(OTHER_INCIDENT, "WorkInProgress", "Низкий", GROUP_DATA, Instant.parse("2026-11-20T09:10:00Z"));
		STAND.addLog("log-rec-1", Instant.parse("2026-11-20T09:15:00Z"), "payment authorization timed out",
				"WARN", INCIDENT, RUN);
		STAND.addLog("log-other-1", Instant.parse("2026-11-20T09:55:00Z"), "customer profile rejected",
				"ERROR", OTHER_INCIDENT, OTHER_RUN);
		DiagnosticsListener.diagnostics().insert("diag-rec-1", INCIDENT, DB_SERVICE,
				Instant.parse("2026-11-20T09:30:00Z"), 42);
		DiagnosticsListener.diagnostics().insert("diag-other-1", OTHER_INCIDENT, DB_SERVICE,
				Instant.parse("2026-11-20T09:58:00Z"), 99);
	}

	/**
	 * Prepares the stand diagnostics table once Spring has started and managed the
	 * PostgreSQL 17 container.
	 */
	public static class DiagnosticsListener implements TestExecutionListener {

		private static McpEvidenceDemoDiagnostics prepared;

		@Override
		public void beforeTestClass(TestContext testContext) {
			if (prepared != null) {
				return;
			}
			synchronized (DiagnosticsListener.class) {
				if (prepared != null) {
					return;
				}
				// Spring's container lifecycle is already done: the container runs and the
				// connection properties are registered. Create the table and the read-only role.
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
	static class EvidenceDatabaseConfiguration {

		@Bean
		@ServiceConnection
		PostgreSQLContainer evidencePostgres17Container() {
			return POSTGRES;
		}
	}

	/** A clock pinned to {@link #NOW}, so stand-relative windows are deterministic. */
	@TestConfiguration(proxyBeanMethods = false)
	public static class FixedClockConfiguration {

		@Bean
		@Primary
		public Clock kan39Clock() {
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
