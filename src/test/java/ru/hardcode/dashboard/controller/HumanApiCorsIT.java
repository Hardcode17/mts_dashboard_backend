package ru.hardcode.dashboard.controller;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import ru.hardcode.dashboard.ItsmPostgresTestcontainersConfiguration;
import ru.hardcode.dashboard.client.Kan16JwtTestConfiguration;
import ru.hardcode.dashboard.client.KeycloakPkceClient;
import ru.hardcode.dashboard.client.KeycloakTestContainer;
import ru.hardcode.dashboard.service.ItsmSnapshotPublicationService;
import ru.hardcode.dashboard.worker.ItsmWorkerSqlRepository;
import ru.hardcode.dashboard.worker.ItsmWorkerTransactionService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * KAN-64: CORS for the human API used by the browser SPA. Runs the real filter chains with
 * a real Keycloak: preflight without authentication, Bearer responses with CORS headers for
 * allowed origins, rejection of foreign origins, and no CORS on the machine intake chain.
 *
 * <p>The origins here are deliberately not the built-in defaults: one dev origin and one
 * stand origin prove that {@code app.cors.allowed-origins} overrides the defaults, so a
 * request from the other default dev origin must be rejected.
 */
@SpringBootTest(properties = {
		"itsm.worker.enabled=false",
		"request-logging.max-cached-body-size=1KB",
		"app.cors.allowed-origins=http://localhost:5173,https://stand.mts.example"
})
@AutoConfigureMockMvc
@ActiveProfiles("demo")
@Import({
		ItsmPostgresTestcontainersConfiguration.class,
		KeycloakTestContainer.class,
		Kan16JwtTestConfiguration.class
})
class HumanApiCorsIT {

	private static final String DASHBOARD_PATH = "/api/dashboard/incidents";
	private static final String ME_PATH = "/api/me";
	private static final String BATCH_PATH = "/api/integrations/itsm/batches";

	/** Configured by the test properties, not by the built-in defaults. */
	private static final String ALLOWED_DEV_ORIGIN = "http://localhost:5173";
	private static final String ALLOWED_STAND_ORIGIN = "https://stand.mts.example";
	/** A default dev origin that the test properties deliberately remove. */
	private static final String DROPPED_DEFAULT_ORIGIN = "http://localhost:3000";
	private static final String FOREIGN_ORIGIN = "http://attacker.example";

	private static final byte[] SNAPSHOT = "[]".getBytes(StandardCharsets.UTF_8);

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@Autowired
	private ItsmSnapshotPublicationService publication;

	private KeycloakPkceClient keycloak;

	@DynamicPropertySource
	static void authProperties(DynamicPropertyRegistry registry) {
		registry.add("app.auth.human.issuer", KeycloakTestContainer::issuer);
		registry.add("app.auth.human.jwk-set-uri", Kan16JwtTestConfiguration::combinedJwkSetUri);
		registry.add("app.auth.human.audience", () -> KeycloakTestContainer.HUMAN_AUDIENCE);
		registry.add("itsm.integration.auth.issuer", KeycloakTestContainer::issuer);
		registry.add("itsm.integration.auth.jwk-set-uri", KeycloakTestContainer::jwkSetUri);
		registry.add("itsm.integration.auth.audience", () -> KeycloakTestContainer.MACHINE_AUDIENCE);
		registry.add("itsm.integration.auth.client-id", () -> KeycloakTestContainer.MACHINE_CLIENT_ID);
		registry.add("itsm.integration.auth.import-role", () -> KeycloakTestContainer.IMPORT_ROLE);
	}

	@BeforeEach
	void resetDatabase() throws Exception {
		jdbcTemplate.execute("""
				TRUNCATE itsm_ingest_batch, itsm_snapshot, itsm_snapshot_source, itsm_incident CASCADE
				""");
		keycloak = new KeycloakPkceClient(KeycloakTestContainer.issuer());
		String subject = SignedJWT.parse(humanToken()).getJWTClaimsSet().getSubject();
		jdbcTemplate.update(
				"INSERT INTO users(identity_issuer,identity_subject,login,display_name) VALUES (?,?,?,?) ON CONFLICT DO NOTHING",
				KeycloakTestContainer.issuer(), subject, "test", "Test");
	}

	// --- positive -------------------------------------------------------------------------

	@Test
	void preflight_isAnsweredWithoutAuthentication_andAllowsConfiguredOrigin() throws Exception {
		mockMvc.perform(options(DASHBOARD_PATH)
						.header(HttpHeaders.ORIGIN, ALLOWED_DEV_ORIGIN)
						.header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
				.andExpect(status().isOk())
				.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ALLOWED_DEV_ORIGIN))
				.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, containsString("GET")))
				.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_MAX_AGE, "3600"))
				.andExpect(header().string(HttpHeaders.VARY, containsString(HttpHeaders.ORIGIN)))
				.andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS));
	}

	@Test
	void preflightForPatch_listsDeclaredRequestHeaders() throws Exception {
		// Browsers send the requested headers lowercased; the filter verifies each against
		// the configured allow-list and echoes them back.
		mockMvc.perform(options(DASHBOARD_PATH + "/INC000016")
						.header(HttpHeaders.ORIGIN, ALLOWED_DEV_ORIGIN)
						.header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "PATCH")
						.header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS,
								"authorization, content-type, if-none-match, x-request-id"))
				.andExpect(status().isOk())
				.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ALLOWED_DEV_ORIGIN))
				.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, containsString("PATCH")))
				.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS, containsString("authorization")))
				.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS, containsString("content-type")))
				.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS, containsString("if-none-match")))
				.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS, containsString("x-request-id")));
	}

	@Test
	void getIncidents_withOriginAndBearer_returnsCorsHeadersAndData() throws Exception {
		String incidentId = "INC000016";
		publishIncident(incidentId);

		mockMvc.perform(get(DASHBOARD_PATH)
						.header(HttpHeaders.ORIGIN, ALLOWED_DEV_ORIGIN)
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + humanToken()))
				.andExpect(status().isOk())
				.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ALLOWED_DEV_ORIGIN))
				.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, containsString("ETag")))
				.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, containsString("Location")))
				.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, containsString("X-Request-Id")))
				.andExpect(jsonPath("$.items[0].id").value(incidentId));
	}

	@Test
	void configuredNonDevOrigin_isAllowedOnMe() throws Exception {
		mockMvc.perform(get(ME_PATH)
						.header(HttpHeaders.ORIGIN, ALLOWED_STAND_ORIGIN)
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + humanToken()))
				.andExpect(status().isOk())
				.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ALLOWED_STAND_ORIGIN))
				.andExpect(jsonPath("$.subject").isNotEmpty());
	}

	// --- negative -------------------------------------------------------------------------

	@Test
	void withoutToken_withAllowedOrigin_returns401() throws Exception {
		mockMvc.perform(get(DASHBOARD_PATH).header(HttpHeaders.ORIGIN, ALLOWED_DEV_ORIGIN))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("AUTH_REQUIRED"))
				.andExpect(jsonPath("$.message").value("Authentication is required"));
	}

	@Test
	void foreignOrigin_isRejectedWithoutCorsHeaders() throws Exception {
		// The CORS filter runs before authentication, so even a valid Bearer token cannot
		// make an unknown origin pass: the browser sees 403 and no Access-Control-Allow-Origin.
		mockMvc.perform(get(DASHBOARD_PATH)
						.header(HttpHeaders.ORIGIN, FOREIGN_ORIGIN)
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + humanToken()))
				.andExpect(status().isForbidden())
				.andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));

		mockMvc.perform(options(DASHBOARD_PATH)
						.header(HttpHeaders.ORIGIN, FOREIGN_ORIGIN)
						.header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
				.andExpect(status().isForbidden())
				.andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
	}

	@Test
	void originRemovedByConfiguration_isRejected() throws Exception {
		// localhost:3000 is a built-in default, but the test configuration replaces the
		// defaults, so the SPA served from there must not receive CORS headers.
		mockMvc.perform(get(ME_PATH)
						.header(HttpHeaders.ORIGIN, DROPPED_DEFAULT_ORIGIN)
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + humanToken()))
				.andExpect(status().isForbidden())
				.andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
	}

	// --- boundary: the machine chain is untouched -------------------------------------------

	@Test
	void machineRoute_getsNoCorsHeaders_evenForAllowedOrigin() throws Exception {
		mockMvc.perform(options(BATCH_PATH)
						.header(HttpHeaders.ORIGIN, ALLOWED_DEV_ORIGIN)
						.header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
				.andExpect(status().isUnauthorized())
				.andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN))
				.andExpect(jsonPath("$.code").value("ITSM_MACHINE_AUTH_REQUIRED"));

		mockMvc.perform(get(BATCH_PATH + "/" + UUID.randomUUID())
						.header(HttpHeaders.ORIGIN, ALLOWED_DEV_ORIGIN)
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + humanToken()))
				.andExpect(status().isUnauthorized())
				.andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN))
				.andExpect(jsonPath("$.code").value("ITSM_MACHINE_AUTH_REQUIRED"));
	}

	@Test
	void machineIntake_stillWorksWithOriginHeader_andAddsNoCorsHeaders() throws Exception {
		UUID batchId = UUID.randomUUID();

		// A machine client never sends a browser Origin, but if it does the intake must be
		// unaffected: no CORS filter in that chain, so no CORS decision is made at all.
		mockMvc.perform(post(BATCH_PATH)
						.header(HttpHeaders.ORIGIN, ALLOWED_DEV_ORIGIN)
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + machineToken())
						.contentType(MediaType.APPLICATION_JSON)
						.header(ItsmBatchController.IDEMPOTENCY_KEY_HEADER, batchId)
						.header(ItsmBatchController.SOURCE_ID_HEADER, "kan64-cors-source")
						.header(ItsmBatchController.SOURCE_VERSION_HEADER, 1)
						.content(SNAPSHOT))
				.andExpect(status().isAccepted())
				.andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN))
				.andExpect(jsonPath("$.batchId").value(batchId.toString()));

		assertThatBatchCountIs(1);
	}

	// --- helpers ---------------------------------------------------------------------------

	private String humanToken() throws Exception {
		return keycloak.login(KeycloakTestContainer.TEST_USERNAME, KeycloakTestContainer.TEST_PASSWORD);
	}

	private String machineToken() {
		return KeycloakPkceClient.accessToken(keycloak.clientCredentials(
				KeycloakTestContainer.MACHINE_CLIENT_ID, KeycloakTestContainer.ITSM_SENDER_SECRET));
	}

	/** Ingests and publishes one incident through the real machine chain and worker. */
	private void publishIncident(String incidentId) throws Exception {
		UUID batchId = UUID.randomUUID();
		mockMvc.perform(post(BATCH_PATH)
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + machineToken())
						.contentType(MediaType.APPLICATION_JSON)
						.header(ItsmBatchController.IDEMPOTENCY_KEY_HEADER, batchId)
						.header(ItsmBatchController.SOURCE_ID_HEADER, "kan64-cors-source")
						.header(ItsmBatchController.SOURCE_VERSION_HEADER, 1)
						.content("""
								[{"id":"%s","stateEnum":"Assigned","priorityId":4,"submitDate":"2026-11-21T09:00:00Z",
								  "productName":"CORS IT", "solution":null}]
								""".formatted(incidentId)))
				.andExpect(status().isAccepted());

		var worker = new ItsmWorkerTransactionService(
				new ItsmWorkerSqlRepository(jdbcTemplate), transactionManager);
		assertThat(worker.claimAndProcess(publication, "kan64-cors-test").batchId())
				.isEqualTo(batchId);
	}

	private void assertThatBatchCountIs(int expected) {
		Integer count = jdbcTemplate.queryForObject("SELECT count(*) FROM itsm_ingest_batch", Integer.class);
		assertThat(count).isEqualTo(expected);
	}
}
