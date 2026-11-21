package ru.hardcode.dashboard;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;

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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import ru.hardcode.dashboard.client.ItsmMachineJwtTestConfiguration;
import ru.hardcode.dashboard.client.Kan16JwtTestConfiguration;
import ru.hardcode.dashboard.client.TestJwtSigner;
import ru.hardcode.dashboard.controller.ItsmBatchController;
import ru.hardcode.dashboard.repository.CoordinationTaskRepository;
import ru.hardcode.dashboard.service.ItsmSnapshotPublicationService;
import ru.hardcode.dashboard.worker.ItsmWorkerSqlRepository;
import ru.hardcode.dashboard.worker.ItsmWorkerTransactionService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
		"itsm.worker.enabled=false",
		"coordination.external-group=Target",
		"itsm.integration.auth.issuer=" + ItsmMachineJwtTestConfiguration.ISSUER,
		"itsm.integration.auth.jwk-set-uri=https://keycloak.test/realms/itsm/protocol/openid-connect/certs",
		"itsm.integration.auth.audience=" + ItsmMachineJwtTestConfiguration.AUDIENCE,
		"itsm.integration.auth.client-id=" + ItsmMachineJwtTestConfiguration.CLIENT_ID,
		"itsm.integration.auth.import-role=" + ItsmMachineJwtTestConfiguration.IMPORT_ROLE
})
@AutoConfigureMockMvc
@ActiveProfiles("work")
@Import({ItsmPostgresTestcontainersConfiguration.class, ItsmMachineJwtTestConfiguration.class})
class CoordinationTaskQueueIT {

	private static final String ISSUER = "https://keycloak.test/realms/dashboard";
	private static final String AUDIENCE = "dashboard-human";
	private static final String PATH = "/api/dashboard/coordination-tasks";
	@Autowired MockMvc mvc;
	@Autowired JdbcTemplate jdbc;
	@Autowired PlatformTransactionManager transactions;
	@Autowired ItsmSnapshotPublicationService publication;
	@Autowired CoordinationTaskRepository tasks;
	@Autowired JwtEncoder encoder;

	@DynamicPropertySource
	static void auth(DynamicPropertyRegistry registry) {
		registry.add("app.auth.human.issuer", () -> ISSUER);
		registry.add("app.auth.human.audience", () -> AUDIENCE);
		registry.add("app.auth.human.jwk-set-uri", Kan16JwtTestConfiguration::jwkSetUri);
	}

	@BeforeEach
	void reset() {
		jdbc.execute("""
				TRUNCATE itsm_ingest_batch, itsm_snapshot, itsm_snapshot_source, itsm_incident,
				    itsm_catalog_product, itsm_catalog_service, itsm_catalog_scenario,
				    itsm_external_group, itsm_external_person, itsm_ingest_catalog_hint,
				    itsm_incident_change, shift_event, shift_event_source RESTART IDENTITY CASCADE
				""");
		jdbc.update("UPDATE dashboard_publication SET event_seq = 0, revision = 0 WHERE singleton");
		jdbc.update("UPDATE shift_event_progress SET last_revision = 0 WHERE singleton");
		jdbc.update("""
				INSERT INTO users(identity_issuer, identity_subject, login, display_name)
				VALUES (?, 'kan24', 'kan24', 'Coordinator') ON CONFLICT DO NOTHING
				""", ISSUER);
	}

	@Test
	void publishedEntriesCreateOneOpenTaskAndQueueRequiresCoordinator() throws Exception {
		deliver(1, incident("INC1", "Target", "baseline"), incident("INC2", "Other", "baseline"));
		assertThat(count()).isZero();
		deliver(2, incident("INC1", "Target", "changed"), incident("INC2", "Target", "baseline"),
				incident("INC3", "Target", "new"), incident("INC4", "Other", "new"));
		assertThat(count()).isEqualTo(2);
		mvc.perform(get(PATH)).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").isNotEmpty()).andExpect(jsonPath("$.message").isNotEmpty());
		mvc.perform(get(PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + token("unknown")))
				.andExpect(status().isForbidden());
		mvc.perform(get(PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + token("kan24")))
				.andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2))
				.andExpect(jsonPath("$[0].taskId").isNumber())
				.andExpect(jsonPath("$[0].incidentId").value("INC2"))
				.andExpect(jsonPath("$[1].incidentId").value("INC3"));
		deliver(3, incident("INC1", "Target", "changed"), incident("INC2", "Other", "baseline"),
				incident("INC3", "Target", "changed"), incident("INC4", "Other", "new"));
		deliver(4, incident("INC1", "Target", "changed"), incident("INC2", "Target", "baseline"),
				incident("INC3", "Target", "changed"), incident("INC4", "Other", "new"));
		assertThat(count()).isEqualTo(2);
		assertThat(jdbc.queryForObject("SELECT source_data->>'assignedGroupName' FROM itsm_incident WHERE id='INC2'",
				String.class)).isEqualTo("Target");
		assertThat(jdbc.queryForObject("SELECT count(*) FROM coordination_tasks WHERE status='open' AND finished_at IS NULL",
				Long.class)).isEqualTo(2);
		// Disappearance does not close a task; returning on the target group does not duplicate it.
		deliver(5, incident("INC1", "Target", "changed"), incident("INC4", "Other", "new"));
		assertThat(count()).isEqualTo(2);
		deliver(6, incident("INC1", "Target", "changed"), incident("INC2", "Target", "returned"),
				incident("INC3", "Target", "returned"), incident("INC4", "Other", "new"));
		assertThat(count()).isEqualTo(2);
	}

	@Test
	void concurrentInsertionsHaveExactlyOneWinner() throws Exception {
		deliver(1, incident("INC1", "Other", "baseline"));
		try (var executor = Executors.newFixedThreadPool(12)) {
			var futures = executor.invokeAll(java.util.stream.IntStream.range(0, 24)
					.<java.util.concurrent.Callable<Boolean>>mapToObj(i -> () -> tasks.insertOpen("INC1")).toList());
			int winners = 0;
			for (var result : futures) {
				if (result.get()) winners++;
			}
			assertThat(winners).isEqualTo(1);
			assertThat(count()).isEqualTo(1);
		}
	}

	private long count() {
		return jdbc.queryForObject("SELECT count(*) FROM coordination_tasks", Long.class);
	}

	private static String incident(String id, String group, String header) {
		return """
				{"id":"%s","productName":"Product","header":"%s","stateEnum":"Assigned",
				 "priorityId":3,"submitDate":"2026-11-21T09:00:00Z","solution":null,"assignedGroupName":"%s"}
				""".formatted(id, header, group);
	}

	private void deliver(long version, String... incidents) throws Exception {
		UUID batch = UUID.randomUUID();
		mvc.perform(post("/api/integrations/itsm/batches")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + ItsmMachineJwtTestConfiguration.machineToken(encoder))
				.header(ItsmBatchController.IDEMPOTENCY_KEY_HEADER, batch.toString())
				.header(ItsmBatchController.SOURCE_ID_HEADER, "kan24")
				.header(ItsmBatchController.SOURCE_VERSION_HEADER, Long.toString(version))
				.contentType(MediaType.APPLICATION_JSON).content("[" + String.join(",", List.of(incidents)) + "]"))
				.andExpect(status().isAccepted());
		var worker = new ItsmWorkerTransactionService(new ItsmWorkerSqlRepository(jdbc), transactions);
		assertThat(worker.claimAndProcess(publication, "kan24").batchId()).isEqualTo(batch);
		assertThat(jdbc.queryForMap("SELECT status, diagnostic FROM itsm_ingest_batch WHERE id = ?", batch))
				.containsEntry("status", "SUCCEEDED");
	}

	private static String token(String subject) {
		return Kan16JwtTestConfiguration.signer().token(TestJwtSigner.claims()
				.issuer(ISSUER).audience(AUDIENCE).subject(subject)
				.claim("azp", "dashboard-web").expiresAt(Instant.now().plusSeconds(300)));
	}
}
