package ru.hardcode.dashboard;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.MvcResult;
import ru.hardcode.dashboard.client.KeycloakPkceClient;
import ru.hardcode.dashboard.client.KeycloakTestContainer;
import ru.hardcode.dashboard.controller.ItsmBatchController;
import ru.hardcode.dashboard.entity.ItsmBatchStatus;
import ru.hardcode.dashboard.worker.ItsmWorkerRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * KAN-15 acceptance of the coordinator workplace path that is implemented today: a user
 * pre-created in Keycloak signs in, the ITCS-compatible sender posts a full snapshot, the
 * worker publishes it, and the same user reads the four groups and one card from PostgreSQL.
 *
 * <p>The ITCS mock module has its own HTTP tests; here the real intake endpoint receives the
 * same 44-record fixture the sender delivers. Internal tasks, presets, MTS Link and the AI
 * investigation are separate tasks that are not part of this test.
 */
@SpringBootTest(properties = {
		"itsm.worker.workers=2",
		"itsm.worker.poll-interval=PT0.05S",
		"itsm.worker.max-attempts=2",
		"itsm.intake.max-outstanding-batches=10",
		"spring.security.user.name=operator",
		"spring.security.user.password=workplace-test"
})
@AutoConfigureMockMvc
@ActiveProfiles("work")
@Import({ItsmPostgresTestcontainersConfiguration.class, KeycloakTestContainer.class})
class CoordinatorWorkplaceIT {

	private static final String BATCH_PATH = "/api/integrations/itsm/batches";
	private static final String LIST_PATH = "/api/dashboard/incidents";
	private static final String SOURCE_ID = "itcs-mock";
	private static final String KNOWN_INC = "INC000021931151";
	private static final String LINK_PREFIX = "http://127.0.0.1:8082/itsm_incident/";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private ItsmWorkerRunner runner;

	private KeycloakPkceClient keycloak;

	@DynamicPropertySource
	static void authentication(DynamicPropertyRegistry registry) {
		registry.add("app.auth.human.issuer", KeycloakTestContainer::issuer);
		registry.add("app.auth.human.jwk-set-uri", KeycloakTestContainer::jwkSetUri);
		registry.add("app.auth.human.audience", () -> KeycloakTestContainer.HUMAN_AUDIENCE);
		registry.add("itsm.integration.auth.issuer", KeycloakTestContainer::issuer);
		registry.add("itsm.integration.auth.jwk-set-uri", KeycloakTestContainer::jwkSetUri);
		registry.add("itsm.integration.auth.audience", () -> KeycloakTestContainer.MACHINE_AUDIENCE);
		registry.add("itsm.integration.auth.client-id", () -> KeycloakTestContainer.MACHINE_CLIENT_ID);
		registry.add("itsm.integration.auth.import-role", () -> KeycloakTestContainer.IMPORT_ROLE);
	}

	@BeforeEach
	void reset() {
		runner.stop();
		jdbc.execute("""
				TRUNCATE itsm_ingest_batch, itsm_snapshot, itsm_snapshot_source, itsm_incident,
				    itsm_catalog_product, itsm_catalog_service, itsm_catalog_scenario,
				    itsm_external_group, itsm_external_person, itsm_ingest_catalog_hint CASCADE
				""");
		keycloak = new KeycloakPkceClient(KeycloakTestContainer.issuer());
		runner.start();
	}

	@AfterEach
	void stopWorker() {
		runner.stop();
	}

	@Test
	void coordinatorSignsInImportsSnapshotAndReadsGroupsAndCard() throws Exception {
		String human = humanToken();
		String machine = machineToken();
		UUID batch = UUID.randomUUID();

		// Steps 1-2: the sender delivers the full snapshot; intake answers before processing.
		mvc.perform(postSnapshot(machine, batch, 1, fixture()))
				.andExpect(status().isAccepted())
				.andExpect(header().string(HttpHeaders.LOCATION, BATCH_PATH + "/" + batch))
				.andExpect(jsonPath("$.batchId").value(batch.toString()));
		mvc.perform(get(BATCH_PATH + "/" + batch).header(HttpHeaders.AUTHORIZATION, "Bearer " + machine))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.batchId").value(batch.toString()))
				.andExpect(jsonPath("$.status").value(org.hamcrest.Matchers.oneOf(
						ItsmBatchStatus.RECEIVED.name(), ItsmBatchStatus.PROCESSING.name(),
						ItsmBatchStatus.SUCCEEDED.name())));
		await("44 unique incidents published", () -> count("itsm_incident") == 44);
		assertThat(jdbc.queryForObject("SELECT count(DISTINCT id) FROM itsm_incident", Long.class)).isEqualTo(44L);
		assertThat(count("itsm_external_person")).isPositive();
		assertThat(jdbc.queryForObject("""
				SELECT count(*) FROM itsm_incident WHERE present_in_source AND last_seen_version = 1
				""", Long.class)).isEqualTo(44L);

		// Step 3: the coordinator reads the four groups on the published version.
		MvcResult list = mvc.perform(authenticatedGet(human, LIST_PATH))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.groups[*].key", contains("critical", "coordinator", "high", "research")))
				.andExpect(jsonPath("$.groups[*].incidentCount", contains(0, 2, 1, 5)))
				.andExpect(jsonPath("$.items", hasSize(29)))
				.andReturn();
		long revision = ((Number) JsonPath.read(list.getResponse().getContentAsString(), "$.revision")).longValue();
		assertThat(revision).isPositive();

		mvc.perform(authenticatedGet(human, LIST_PATH + "/" + KNOWN_INC))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(KNOWN_INC))
				.andExpect(jsonPath("$.productName").value("Автопросматриватель TicTok"))
				.andExpect(jsonPath("$.deadline").value("2026-11-22T12:02:29Z"))
				.andExpect(jsonPath("$.itsmUrl").value(LINK_PREFIX + KNOWN_INC + "/page"))
				.andExpect(jsonPath("$.revision").value(revision));

		// One canonical read model: the journal, card and list share the published revision.
		assertThat(count("itsm_incident_change")).isPositive();
	}

	@Test
	void unauthorizedSenderIsRejectedAndChangesNothing() throws Exception {
		Long before = jdbc.queryForObject("SELECT count(*) FROM itsm_ingest_batch", Long.class);
		mvc.perform(post(BATCH_PATH)
						.header(ItsmBatchController.IDEMPOTENCY_KEY_HEADER, UUID.randomUUID())
						.header(ItsmBatchController.SOURCE_ID_HEADER, SOURCE_ID)
						.header(ItsmBatchController.SOURCE_VERSION_HEADER, 1)
						.contentType(MediaType.APPLICATION_JSON)
						.content(fixture()))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("ITSM_MACHINE_AUTH_REQUIRED"));
		assertThat(jdbc.queryForObject("SELECT count(*) FROM itsm_ingest_batch", Long.class)).isEqualTo(before);
		assertThat(count("itsm_incident")).isZero();
	}

	@Test
	void failedProcessingKeepsThePreviousValidVersion() throws Exception {
		String human = humanToken();
		String machine = machineToken();
		mvc.perform(postSnapshot(machine, UUID.randomUUID(), 1, fixture())).andExpect(status().isAccepted());
		await("first snapshot published", () -> count("itsm_incident") == 44);
		long revision = jdbc.queryForObject("SELECT max(processing_revision) FROM itsm_ingest_batch", Long.class);

		UUID broken = UUID.randomUUID();
		mvc.perform(postSnapshot(machine, broken, 2, """
				[{"id":"INC000021931151","priorityId":4,"submitDate":"2026-11-21T09:00:00Z"}]
				""")).andExpect(status().isAccepted());
		await("broken snapshot rejected", () -> ItsmBatchStatus.FAILED.name()
				.equals(jdbc.queryForObject("SELECT status FROM itsm_ingest_batch WHERE id = ?", String.class, broken)));

		assertThat(count("itsm_incident")).isEqualTo(44);
		assertThat(jdbc.queryForObject("SELECT max(processing_revision) FROM itsm_ingest_batch", Long.class))
				.isEqualTo(revision);
		mvc.perform(authenticatedGet(human, LIST_PATH))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.groups[*].incidentCount", contains(0, 2, 1, 5)))
				.andExpect(jsonPath("$.revision").value(revision));
	}

	private String humanToken() {
		String token = keycloak.login(KeycloakTestContainer.TEST_USERNAME, KeycloakTestContainer.TEST_PASSWORD);
		assertThat(token).isNotBlank();
		try {
			// Local admission (KAN-22): the workplace test signs in through the real dashboard-web
			// client, so azp is already correct; only the local users row must exist.
			String subject = com.nimbusds.jwt.SignedJWT.parse(token).getJWTClaimsSet().getSubject();
			jdbc.update("""
					INSERT INTO users(identity_issuer, identity_subject, login, display_name)
					VALUES (?, ?, ?, 'Coordinator IT')
					ON CONFLICT (identity_issuer, identity_subject) DO NOTHING
					""", KeycloakTestContainer.issuer(), subject, KeycloakTestContainer.TEST_USERNAME);
		} catch (java.text.ParseException unreadable) {
			throw new IllegalStateException("Unreadable Keycloak token", unreadable);
		}
		return token;
	}

	private String machineToken() {
		return KeycloakPkceClient.accessToken(keycloak.clientCredentials(
				KeycloakTestContainer.MACHINE_CLIENT_ID, KeycloakTestContainer.ITSM_SENDER_SECRET));
	}

	private MockHttpServletRequestBuilder postSnapshot(String token, UUID batch, long version, String body) {
		return post(BATCH_PATH)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.header(ItsmBatchController.IDEMPOTENCY_KEY_HEADER, batch)
				.header(ItsmBatchController.SOURCE_ID_HEADER, SOURCE_ID)
				.header(ItsmBatchController.SOURCE_VERSION_HEADER, version)
				.contentType(MediaType.APPLICATION_JSON)
				.content(body);
	}

	private MockHttpServletRequestBuilder authenticatedGet(String token, String path, Object... variables) {
		return get(path, variables).accept(MediaType.APPLICATION_JSON)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
	}

	private long count(String table) {
		Long count = jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
		return count == null ? 0 : count;
	}

	private String fixture() throws IOException {
		try (var input = getClass().getResourceAsStream("/itsm/character_problems.json")) {
			assertThat(input).isNotNull();
			return new String(input.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	private static void await(String what, java.util.function.BooleanSupplier condition) throws InterruptedException {
		long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
		while (System.nanoTime() < deadline) {
			if (condition.getAsBoolean()) {
				return;
			}
			Thread.sleep(25);
		}
		throw new AssertionError("condition not reached: " + what);
	}
}
