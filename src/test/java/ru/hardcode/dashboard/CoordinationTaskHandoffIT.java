package ru.hardcode.dashboard;

import java.time.Instant;
import java.util.UUID;

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
import org.springframework.transaction.support.TransactionTemplate;
import ru.hardcode.dashboard.client.ItsmMachineJwtTestConfiguration;
import ru.hardcode.dashboard.client.Kan16JwtTestConfiguration;
import ru.hardcode.dashboard.client.TestJwtSigner;
import ru.hardcode.dashboard.controller.ItsmBatchController;
import ru.hardcode.dashboard.service.ItsmSnapshotPublicationService;
import ru.hardcode.dashboard.service.CoordinationTaskService;
import ru.hardcode.dashboard.worker.ItsmWorkerSqlRepository;
import ru.hardcode.dashboard.worker.ItsmWorkerTransactionService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
		"itsm.worker.enabled=false", "coordination.external-group=Target",
		"itsm.integration.auth.issuer=" + ItsmMachineJwtTestConfiguration.ISSUER,
		"itsm.integration.auth.jwk-set-uri=https://keycloak.test/realms/itsm/protocol/openid-connect/certs",
		"itsm.integration.auth.audience=" + ItsmMachineJwtTestConfiguration.AUDIENCE,
		"itsm.integration.auth.client-id=" + ItsmMachineJwtTestConfiguration.CLIENT_ID,
		"itsm.integration.auth.import-role=" + ItsmMachineJwtTestConfiguration.IMPORT_ROLE
})
@AutoConfigureMockMvc
@ActiveProfiles("work")
@Import({ItsmPostgresTestcontainersConfiguration.class, ItsmMachineJwtTestConfiguration.class})
class CoordinationTaskHandoffIT {
	private static final String ISSUER = "https://keycloak.test/realms/dashboard";
	private static final String PATH = "/api/dashboard/coordination-tasks";
	@Autowired MockMvc mvc;
	@Autowired JdbcTemplate jdbc;
	@Autowired PlatformTransactionManager transactions;
	@Autowired ItsmSnapshotPublicationService publication;
	@Autowired CoordinationTaskService coordination;
	@Autowired JwtEncoder encoder;

	@DynamicPropertySource
	static void auth(DynamicPropertyRegistry registry) {
		registry.add("app.auth.human.issuer", () -> ISSUER);
		registry.add("app.auth.human.audience", () -> "dashboard-human");
		registry.add("app.auth.human.jwk-set-uri", Kan16JwtTestConfiguration::jwkSetUri);
	}

	@Test
	void onlyOrderedOfficialTransferClosesClaimAndNextTaskRemainsManual() throws Exception {
		jdbc.execute("""
				TRUNCATE itsm_ingest_batch, itsm_snapshot, itsm_snapshot_source, itsm_incident,
				    itsm_catalog_product, itsm_catalog_service, itsm_catalog_scenario,
				    itsm_external_group, itsm_external_person, itsm_ingest_catalog_hint,
				    itsm_incident_change, shift_event, shift_event_source RESTART IDENTITY CASCADE
				""");
		jdbc.update("UPDATE dashboard_publication SET event_seq=0, revision=0 WHERE singleton");
		jdbc.update("UPDATE shift_event_progress SET last_revision=0 WHERE singleton");
		jdbc.update("""
				INSERT INTO users(identity_issuer,identity_subject,login,display_name)
				VALUES (?, 'kan32', 'kan32', 'Coordinator') ON CONFLICT DO NOTHING
				""", ISSUER);
		String bearer = "Bearer " + Kan16JwtTestConfiguration.signer().token(TestJwtSigner.claims()
				.issuer(ISSUER).audience("dashboard-human").subject("kan32").claim("azp", "dashboard-web")
				.expiresAt(Instant.now().plusSeconds(300)));
		deliver(1, row("INC1", "Other", "a"));
		deliver(2, row("INC1", "Target", "a"), row("INC2", "Target", "a"));
		long first = task("INC1");
		long next = task("INC2");
		mvc.perform(post(PATH + "/{id}/claim", first).header(HttpHeaders.AUTHORIZATION, bearer))
				.andExpect(status().isOk());
		new TransactionTemplate(transactions).executeWithoutResult(tx -> {
			coordination.observeHandoffs("kan32", 3, "[" + row("INC1", "Support", "a") + "]", Instant.now());
			tx.setRollbackOnly();
		});
		assertThat(state(first)).isEqualTo("open");
		assertThat(jdbc.queryForObject("SELECT team FROM coordination_official_assignment WHERE source_id='kan32' AND incident_id='INC1'", String.class))
				.isEqualTo("Target");
		deliver(3, row("INC1", "Target", "text"), row("INC2", "Target", "a"));
		deliver(4, row("INC2", "Target", "a"));
		assertThat(state(first)).isEqualTo("open");
		// Simulate the already-published PATCH projection: REST diff may now be empty.
		jdbc.update("UPDATE itsm_incident SET source_data=jsonb_set(source_data,'{assignedGroupName}','\"Support\"') WHERE id='INC1'");
		deliver(5, row("INC1", "Support", "text"), row("INC2", "Target", "a"));
		assertThat(state(first)).isEqualTo("completed");
		assertThat(jdbc.queryForMap("SELECT handoff_source_id,handoff_source_version,handoff_type,coordinator_id,finished_at,handoff_observed_at FROM coordination_tasks WHERE id=?", first))
				.containsEntry("handoff_source_id", "kan32").containsEntry("handoff_source_version", 5L)
				.containsEntry("handoff_type", "TEAM").doesNotContainValue(null);
		mvc.perform(get(PATH + "/mine").header(HttpHeaders.AUTHORIZATION, bearer))
				.andExpect(status().isOk()).andExpect(jsonPath("$").isEmpty());
		mvc.perform(get(PATH).header(HttpHeaders.AUTHORIZATION, bearer))
				.andExpect(status().isOk()).andExpect(jsonPath("$[0].taskId").value(next));
		assertThat(jdbc.queryForObject("SELECT coordinator_id FROM coordination_tasks WHERE id=?", Long.class, next)).isNull();
		deliver(6, row("INC1", "Target", "back"), row("INC2", "Target", "a"));
		long returned = jdbc.queryForObject("SELECT id FROM coordination_tasks WHERE incident_id='INC1' AND status='open'", Long.class);
		deliver(7, row("INC1", "Target", "back"), row("INC2", "Target", "a"));
		assertThat(state(returned)).isEqualTo("open");
	}

	private String state(long task) {
		return jdbc.queryForObject("SELECT status FROM coordination_tasks WHERE id=?", String.class, task);
	}

	private long task(String incident) {
		return jdbc.queryForObject("SELECT id FROM coordination_tasks WHERE incident_id=?", Long.class, incident);
	}

	private static String row(String id, String group, String header) {
		return """
				{"id":"%s","productName":"Product","header":"%s","stateEnum":"Assigned",
				 "priorityId":3,"submitDate":"2026-11-21T09:00:00Z","solution":null,"assignedGroupName":"%s"}
				""".formatted(id, header, group);
	}

	private void deliver(long version, String... rows) throws Exception {
		UUID batch = UUID.randomUUID();
		mvc.perform(post("/api/integrations/itsm/batches")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + ItsmMachineJwtTestConfiguration.machineToken(encoder))
				.header(ItsmBatchController.IDEMPOTENCY_KEY_HEADER, batch.toString())
				.header(ItsmBatchController.SOURCE_ID_HEADER, "kan32")
				.header(ItsmBatchController.SOURCE_VERSION_HEADER, Long.toString(version))
				.contentType(MediaType.APPLICATION_JSON).content("[" + String.join(",", rows) + "]"))
				.andExpect(status().isAccepted());
		var worker = new ItsmWorkerTransactionService(new ItsmWorkerSqlRepository(jdbc), transactions);
		assertThat(worker.claimAndProcess(publication, "kan32").batchId()).isEqualTo(batch);
		assertThat(jdbc.queryForMap("SELECT status, diagnostic FROM itsm_ingest_batch WHERE id=?", batch))
				.containsEntry("status", "SUCCEEDED");
	}
}
