package ru.hardcode.dashboard;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.transaction.PlatformTransactionManager;
import ru.hardcode.dashboard.client.Kan16JwtTestConfiguration;
import ru.hardcode.dashboard.client.TestJwtSigner;
import ru.hardcode.dashboard.service.ItsmBatchIntakeService;
import ru.hardcode.dashboard.service.ItsmSnapshotPublicationService;
import ru.hardcode.dashboard.worker.ItsmWorkerSqlRepository;
import ru.hardcode.dashboard.worker.ItsmWorkerTransactionService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

@SpringBootTest(properties = {
		"itsm.worker.enabled=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("work")
@Import(ItsmPostgresTestcontainersConfiguration.class)
abstract class DashboardIncidentTestSupport {

	static final String PATH = "/api/dashboard/incidents";
	static final String COORDINATORS = "КЦ ИТ Координаторы Массовых Инцидентов";
	static final String HUMAN_ISSUER = "https://keycloak.test/realms/dashboard";
	static final String HUMAN_AUDIENCE = "dashboard-human";
	static final String HUMAN_SUBJECT = "kan7-dashboard-operator";

	@DynamicPropertySource
	static void humanAuthentication(DynamicPropertyRegistry registry) {
		// The dashboard reads are owned by the human Bearer JWT chain with local coordinator
		// admission (KAN-22): a signed test token with azp=dashboard-web and a seeded users row.
		registry.add("app.auth.human.issuer", () -> HUMAN_ISSUER);
		registry.add("app.auth.human.audience", () -> HUMAN_AUDIENCE);
		registry.add("app.auth.human.jwk-set-uri", Kan16JwtTestConfiguration::jwkSetUri);
	}

	@Autowired MockMvc mvc;
	@Autowired JdbcTemplate jdbc;
	@Autowired PlatformTransactionManager transactions;
	@Autowired ItsmBatchIntakeService intake;
	@Autowired ItsmSnapshotPublicationService publication;

	@BeforeEach
	void clearPublishedData() {
		jdbc.execute("""
				TRUNCATE itsm_ingest_batch, itsm_snapshot, itsm_snapshot_source, itsm_incident,
				    itsm_catalog_product, itsm_catalog_service, itsm_catalog_scenario,
				    itsm_external_group, itsm_external_person, itsm_ingest_catalog_hint,
				    deadline_risk CASCADE
				""");
		// The publication counters live in their own singleton row: they must not leak
		// between tests when the batch and journal tables are truncated.
		jdbc.update("UPDATE dashboard_publication SET event_seq = 0, revision = 0 WHERE singleton");
		jdbc.update("""
				INSERT INTO users(identity_issuer, identity_subject, login, display_name)
				VALUES (?, ?, 'operator', 'Dashboard Operator')
				ON CONFLICT (identity_issuer, identity_subject) DO NOTHING
				""", HUMAN_ISSUER, HUMAN_SUBJECT);
	}

	MockHttpServletRequestBuilder listRequest() {
		return authenticatedGet(PATH);
	}

	MockHttpServletRequestBuilder authenticatedGet(String path, Object... variables) {
		return get(path, variables).accept(MediaType.APPLICATION_JSON)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + humanToken());
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
				.isEqualTo("SUCCEEDED");
	}

	String baseline() throws IOException {
		try (var fixture = getClass().getResourceAsStream("/itsm/character_problems.json")) {
			assertThat(fixture).isNotNull();
			return new String(fixture.readAllBytes(), StandardCharsets.UTF_8);
		}
	}
}
