package ru.hardcode.dashboard;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import ru.hardcode.dashboard.client.ItsmMachineJwtTestConfiguration;
import ru.hardcode.dashboard.repository.ItsmSnapshotPublicationRepository;
import ru.hardcode.dashboard.service.ItsmIncidentSnapshotParser;
import ru.hardcode.dashboard.service.ItsmSnapshotPublicationService;
import ru.hardcode.dashboard.service.ItsmSnapshotPublished;
import ru.hardcode.dashboard.worker.ItsmWorkerRunner;
import ru.hardcode.dashboard.worker.ItsmWorkerSqlRepository;
import ru.hardcode.dashboard.worker.ItsmWorkerTransactionService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
		"itsm.integration.auth.issuer=" + ItsmMachineJwtTestConfiguration.ISSUER,
		"itsm.integration.auth.jwk-set-uri=https://keycloak.test/realms/itsm/protocol/openid-connect/certs",
		"itsm.integration.auth.audience=" + ItsmMachineJwtTestConfiguration.AUDIENCE,
		"itsm.integration.auth.client-id=" + ItsmMachineJwtTestConfiguration.CLIENT_ID,
		"itsm.integration.auth.import-role=" + ItsmMachineJwtTestConfiguration.IMPORT_ROLE,
		"itsm.worker.workers=2",
		"itsm.worker.poll-interval=PT0.05S",
		"itsm.worker.retry-base-delay=PT2S",
		"itsm.worker.max-attempts=2",
		"itsm.intake.max-outstanding-batches=3",
		"spring.security.user.name=operator",
		"spring.security.user.password=publication-test"
})
@AutoConfigureMockMvc
@ActiveProfiles("work")
@Import({ItsmPostgresTestcontainersConfiguration.class, ItsmMachineJwtTestConfiguration.class,
		ItsmSnapshotPublicationIT.EventConfiguration.class})
class ItsmSnapshotPublicationIT extends ItsmWorkerTestSupport {

	private static final String SOURCE = "publication-source";

	@Autowired
	private ItsmWorkerRunner runner;

	@Autowired
	private PublishedEvents events;

	@Autowired
	private DataSource dataSource;

	@Autowired
	private ItsmSnapshotPublicationService publicationService;

	@MockitoSpyBean
	private ItsmIncidentSnapshotParser parser;

	@MockitoSpyBean
	private ItsmSnapshotPublicationRepository publicationRepository;

	@BeforeEach
	void reset() {
		jdbcTemplate.update("INSERT INTO users(identity_issuer,identity_subject,login,display_name) VALUES ('https://stand.test','operator','operator','Operator') ON CONFLICT DO NOTHING");
		runner.stop();
		resetDatabaseAndProcessor();
		jdbcTemplate.execute("TRUNCATE itsm_snapshot, itsm_incident_change, itsm_incident, itsm_snapshot_source CASCADE");
		events.clear();
	}

	@Test
	void appliesFullObservationAfter202AndRestartWithoutOverwritingLocalFields() throws Exception {
		String first = snapshot(inc("A", "\"Alex\""), inc("B", "\"B\""), inc("C", "\"C\""));
		acceptSnapshot(BATCH_A, 1, first);
		mockMvc.perform(getBatch(BATCH_A)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("RECEIVED"));
		mockMvc.perform(get("/api/snapshot/status").with(jwt().jwt(token -> token.issuer("https://stand.test").subject("operator").claim("azp","dashboard-web"))))
				.andExpect(jsonPath("$.lastSuccessAt").value(nullValue()));
		runner.start();
		awaitSuccess(BATCH_A);
		long initialRevision = revision();
		jdbcTemplate.update("""
				UPDATE itsm_incident
				SET local_data = '{"ack":true,"note":"local","link":"keep","assignment":"team"}'
				WHERE source_id = ? AND id = 'A'
				""", SOURCE);
		mockMvc.perform(get("/api/itsm/incidents").with(jwt().jwt(token -> token.issuer("https://stand.test").subject("operator").claim("azp","dashboard-web"))))
				.andExpect(status().isOk()).andExpect(jsonPath("$.count").value(3));

		String second = snapshot(inc("A", "null"), inc("B", "\"B\""), inc("D", "\"D\""));
		acceptSnapshot(BATCH_B, 2, second);
		awaitSuccess(BATCH_B);
		long changedRevision = revision();
		assertThat(changedRevision).isGreaterThan(initialRevision);
		assertThat(changes(2)).containsExactly("A:UPDATED", "C:MISSING", "D:ADDED");
		assertThat(jdbcTemplate.queryForObject("""
				SELECT source_data->'assignedEmployee' = 'null'::jsonb
				   AND local_data = '{"ack":true,"note":"local","link":"keep","assignment":"team"}'::jsonb
				FROM itsm_incident WHERE source_id = ? AND id = 'A'
				""", Boolean.class, SOURCE)).isTrue();
		assertThat(jdbcTemplate.queryForMap("""
				SELECT present_in_source, last_seen_version, source_data->>'stateEnum' AS state
				FROM itsm_incident WHERE source_id = ? AND id = 'C'
				""", SOURCE)).containsEntry("present_in_source", false).containsEntry("state", "Assigned")
				.containsEntry("last_seen_version", 1L);
		assertThat(jdbcTemplate.queryForMap("""
				SELECT last_seen_version, last_change_revision FROM itsm_incident
				WHERE source_id = ? AND id = 'B'
				""", SOURCE)).containsEntry("last_seen_version", 2L).containsEntry("last_change_revision", initialRevision);
		await("post-commit change event", () -> events.stream().anyMatch(e -> e.sourceVersion() == 2L));
		assertThat(events.stream().filter(e -> e.sourceVersion() == 2L).findFirst().orElseThrow().affectedIncidentIds())
				.containsExactlyInAnyOrder("A", "C", "D");
		mockMvc.perform(get("/api/itsm/incidents").with(jwt().jwt(token -> token.issuer("https://stand.test").subject("operator").claim("azp","dashboard-web"))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.snapshot.sourceVersion").value(2))
				.andExpect(jsonPath("$.count").value(3))
				.andExpect(jsonPath("$.incidents[*].id", org.hamcrest.Matchers.containsInAnyOrder("A", "B", "D")))
				.andExpect(jsonPath("$.incidents[?(@.id == 'A')].assignedPersonId").value((Object) null));

		runner.stop();
		runner.start();
		acceptSnapshot(BATCH_B, 2, second);
		acceptSnapshot(BATCH_C, 3, second);
		awaitSuccess(BATCH_C);
		assertThat(changes(2)).containsExactly("A:UPDATED", "C:MISSING", "D:ADDED");
		assertThat(changes(3)).isEmpty();
		assertThat(revision()).isEqualTo(changedRevision);
		assertThat(events).hasSize(2);
		// Replay is reliable even if the process didn't receive any wake-up events.
		events.clear();
		assertThat(publicationService.changesAfter(initialRevision, 1))
				.containsExactly(new ItsmSnapshotPublished(SOURCE, 2, changedRevision, java.util.Set.of("A", "C", "D")));
		sourceStatus().andExpect(jsonPath("$.observedVersion").value(3));
	}

	@Test
	void semanticRejectionIsTerminalAndLeavesLastSuccessAndIncidentsUntouched() throws Exception {
		runner.start();
		acceptSnapshot(BATCH_A, 1, snapshot(inc("A", "\"original\"")));
		awaitSuccess(BATCH_A);
		long initialRevision = revision();
		Object initialSuccess = row(BATCH_A).get("processed_at");

		acceptSnapshot(BATCH_B, 2, snapshot(inc("A", "\"changed\""), "{\"id\":\"never expose this ID\"}"));
		awaitTerminal(BATCH_B);
		mockMvc.perform(getBatch(BATCH_B)).andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("FAILED"))
				.andExpect(jsonPath("$.nextAttemptAt").value(nullValue()))
				.andExpect(jsonPath("$.diagnostic").value(
						"ITSM_INVALID_RECORD: ITSM record in snapshot at index 1 has invalid field stateEnum: is missing"));
		assertThat(row(BATCH_B)).containsEntry("batch_attempts", 1).containsEntry("processed_at", null);
		assertThat(row(BATCH_A)).containsEntry("processed_at", initialSuccess);
		assertThat(revision()).isEqualTo(initialRevision);
		assertThat(changes(2)).isEmpty();
		assertThat(employee()).isEqualTo("original");
		mockMvc.perform(get("/api/snapshot/status").with(jwt().jwt(token -> token.issuer("https://stand.test").subject("operator").claim("azp","dashboard-web"))))
				.andExpect(jsonPath("$.lastSuccessAt").isNotEmpty())
				.andExpect(jsonPath("$.revision").value((Object) initialRevision))
				.andExpect(jsonPath("$.stale").value(true));

		acceptSnapshot(BATCH_C, 3, snapshot(inc("A", "\"valid after rejection\"")));
		awaitSuccess(BATCH_C);
		assertThat(employee()).isEqualTo("valid after rejection");
	}

	@Test
	void outOfRangeDateFailsWithoutBlockingTheNextVersion() throws Exception {
		runner.start();
		acceptSnapshot(BATCH_A, 1, snapshot(inc("A", "\"original\"")));
		awaitSuccess(BATCH_A);
		acceptSnapshot(BATCH_B, 2, snapshot(inc("A", "\"invalid date\"")
				.replace("2026-11-21T02:00:00Z", "+300000-01-01T00:00:00Z")));
		awaitTerminal(BATCH_B);
		mockMvc.perform(getBatch(BATCH_B)).andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("FAILED"))
				.andExpect(jsonPath("$.nextAttemptAt").value(nullValue()))
				.andExpect(jsonPath("$.diagnostic").value(
						"ITSM_INVALID_RECORD: ITSM record in snapshot at index 0 has invalid field submitDate: must have a year between 0001 and 9999"));
		mockMvc.perform(retry(BATCH_B)).andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("ITSM_BATCH_NOT_RETRYABLE"));
		acceptSnapshot(BATCH_C, 3, snapshot(inc("A", "\"valid date\"")));
		awaitSuccess(BATCH_C);
		assertThat(employee()).isEqualTo("valid date");
	}

	@Test
	void exactDecimalChangeIsJournaledButEquivalentRepresentationIsNot() throws Exception {
		runner.start();
		acceptSnapshot(BATCH_A, 1, snapshot(withDecimal("0.123456789012345678")));
		awaitSuccess(BATCH_A);
		acceptSnapshot(BATCH_B, 2, snapshot(withDecimal("0.123456789012345679")));
		awaitSuccess(BATCH_B);
		long changeRevision = revision();
		assertThat(jdbcTemplate.queryForMap("""
				SELECT before_data->>'metric' AS before, after_data->>'metric' AS after
				FROM itsm_incident_change WHERE source_id = ? AND source_version = 2
				""", SOURCE)).containsEntry("before", "0.123456789012345678")
				.containsEntry("after", "0.123456789012345679");
		acceptSnapshot(BATCH_C, 3, snapshot(withDecimal("0.1234567890123456790")));
		awaitSuccess(BATCH_C);
		assertThat(revision()).isEqualTo(changeRevision);
		assertThat(changes(3)).isEmpty();
	}

	@Test
	void snapshotReorderingDoesNotRewriteSharedIdentityForUnchangedIncidents() throws Exception {
		runner.start();
		String a = inc("A", "\"display A\"").replace("\"assignedEmployeeLogin\":\"display A\"", "\"assignedEmployeeLogin\":\"shared\"");
		String b = inc("B", "\"display B\"").replace("\"assignedEmployeeLogin\":\"display B\"", "\"assignedEmployeeLogin\":\"shared\"");
		acceptSnapshot(BATCH_A, 1, snapshot(a, b, inc("C", "\"before\"")));
		awaitSuccess(BATCH_A);
		acceptSnapshot(BATCH_B, 2, snapshot(b, a, inc("C", "\"after\"")));
		awaitSuccess(BATCH_B);
		assertThat(changes(2)).containsExactly("C:UPDATED");
		assertThat(jdbcTemplate.queryForObject(
				"SELECT display_name FROM itsm_external_person WHERE external_login = 'shared'", String.class))
				.isEqualTo("display A");
	}

	@Test
	void intakeDoesNotAcceptAnEarlierVersionHalfwayThroughInitialPublication() throws Exception {
		CountDownLatch checked = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		doAnswer(invocation -> {
			Object result = invocation.callRealMethod();
			checked.countDown();
			if (!release.await(AWAIT_SECONDS, TimeUnit.SECONDS)) {
				throw new IllegalStateException("test gate timed out");
			}
			return result;
		}).when(publicationRepository).firstUnsettledVersion(eq(SOURCE), isNull(), eq(3L));

		runner.start();
		try {
			acceptSnapshot(BATCH_C, 3, "[]");
			assertThat(checked.await(AWAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
			mockMvc.perform(postBatch(BATCH_B, SOURCE, 2).content("[]"))
					.andExpect(status().isTooManyRequests())
					.andExpect(jsonPath("$.code").value("ITSM_SOURCE_INITIALIZING"));
			assertThat(count()).isEqualTo(1L);
			acceptSnapshot(BATCH_C, 3, "[]"); // a replay remains available even during publication
		} finally {
			release.countDown();
		}
		awaitSuccess(BATCH_C);
	}

	@Test
	void appliesAllSourceFixtureRecordsAndRejectsUnstorableInputWithoutRetry() throws Exception {
		String original;
		try (var fixture = getClass().getResourceAsStream("/itsm/character_problems.json")) {
			assertThat(fixture).isNotNull();
			original = new String(fixture.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
		}
		runner.start();
		acceptSnapshot(BATCH_A, 1, original);
		awaitSuccess(BATCH_A);
		assertThat(jdbcTemplate.queryForObject("""
				SELECT count(*) FROM itsm_incident WHERE source_id = ? AND present_in_source
				""", Long.class, SOURCE)).isEqualTo(44L);

		acceptSnapshot(BATCH_B, 2, snapshot(inc("A", "\"invalid\\u0000value\"")));
		awaitTerminal(BATCH_B);
		mockMvc.perform(getBatch(BATCH_B)).andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("FAILED"))
				.andExpect(jsonPath("$.nextAttemptAt").value(nullValue()))
				.andExpect(jsonPath("$.diagnostic").value(
						"ITSM_UNSUPPORTED_SNAPSHOT_VALUE: ITSM snapshot contains a string or number that cannot be stored"));
		assertThat(observedVersion()).isEqualTo(1L);
		assertThat(changes(2)).isEmpty();
	}

	@Test
	void twoWorkersPrepareOutOfOrderButCommitBothAssignmentChangesInOrder() throws Exception {
		CountDownLatch enteredV2 = new CountDownLatch(1);
		CountDownLatch releaseV2 = new CountDownLatch(1);
		AtomicReference<Thread> threadV2 = new AtomicReference<>();
		AtomicReference<Thread> threadV3 = new AtomicReference<>();
		doAnswer(invocation -> {
			byte[] body = invocation.getArgument(0);
			String json = new String(body, java.nio.charset.StandardCharsets.UTF_8);
			if (json.contains("\"V2-employee\"")) {
				threadV2.set(Thread.currentThread());
				enteredV2.countDown();
				if (!releaseV2.await(AWAIT_SECONDS, TimeUnit.SECONDS)) {
					throw new IllegalStateException("test gate timed out");
				}
			}
			if (json.contains("\"V3-employee\"")) {
				threadV3.set(Thread.currentThread());
			}
			return invocation.callRealMethod();
		}).when(parser).parse(any(byte[].class));
		runner.start();
		acceptSnapshot(BATCH_A, 1, snapshot(inc("A", "\"initial\"")));
		awaitSuccess(BATCH_A);
		try {
			acceptSnapshot(BATCH_B, 2, snapshot(inc("A", "\"V2-employee\"")));
			assertThat(enteredV2.await(AWAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
			acceptSnapshot(BATCH_C, 3, snapshot(inc("A", "\"V3-employee\"")));
			await("v3 waiting for active v2", () -> Long.valueOf(2).equals(waitingFor(BATCH_C)));
			assertThat(threadV3.get()).isNotNull().isNotSameAs(threadV2.get());
			assertThat(changes(3)).isEmpty();
			assertThat(row(BATCH_C).get("batch_attempts")).isEqualTo(0);
			sourceStatus().andExpect(jsonPath("$.waitingForVersion").value(2));
		} finally {
			releaseV2.countDown();
		}
		awaitSuccess(BATCH_B);
		awaitSuccess(BATCH_C);
		assertThat(jdbcTemplate.queryForList("""
				SELECT after_data->>'assignedEmployee' FROM itsm_incident_change
				WHERE source_id = ? AND incident_id = 'A' ORDER BY change_revision
				""", String.class, SOURCE)).containsExactly("initial", "V2-employee", "V3-employee");
		sourceStatus().andExpect(jsonPath("$.observedVersion").value(3))
				.andExpect(jsonPath("$.waitingForVersion").value(nullValue()));
	}

	@Test
	void reportsActualGapAndCanAcceptPredecessorEvenWhenOrdinaryQueueIsFull() throws Exception {
		runner.start();
		acceptSnapshot(BATCH_A, 1, "[]");
		awaitSuccess(BATCH_A);
		for (long version = 3; version <= 5; version++) {
			UUID id = UUID.randomUUID();
			acceptSnapshot(id, version, "[]");
			await("missing predecessor for version " + version, () -> Long.valueOf(2).equals(waitingFor(id)));
		}
		mockMvc.perform(postBatch(UUID.randomUUID(), SOURCE, 6).content("[]"))
				.andExpect(status().isTooManyRequests());
		sourceStatus().andExpect(jsonPath("$.waitingForVersion").value(2));
		acceptSnapshot(BATCH_B, 2, "[]");
		awaitSuccess(BATCH_B);
		await("all versions applied in order", () -> Long.valueOf(5).equals(observedVersion()));
		sourceStatus().andExpect(jsonPath("$.waitingForVersion").value(nullValue()));
	}

	@Test
	void lateOlderVersionDoesNotReplaceDataOrRefreshLastSuccess() throws Exception {
		runner.start();
		acceptSnapshot(BATCH_A, 7, snapshot(inc("A", "\"newer\"")));
		awaitSuccess(BATCH_A);
		long currentRevision = revision();
		acceptSnapshot(BATCH_B, 5, snapshot(inc("A", "\"older\"")));
		awaitSuccess(BATCH_B);
		assertThat(row(BATCH_B)).containsEntry("processed_at", null).containsEntry("processing_revision", currentRevision);
		assertThat(employee()).isEqualTo("newer");
		assertThat(changes(5)).isEmpty();
		sourceStatus().andExpect(jsonPath("$.observedVersion").value(7));
	}

	@Test
	void writeFailureRollsBackJournalAndRetriesEarlierVersionEvenWithNewerQueued() throws Exception {
		runner.start();
		acceptSnapshot(BATCH_A, 1, snapshot(inc("A", "\"initial\"")));
		awaitSuccess(BATCH_A);
		runner.stop();
		jdbcTemplate.execute("""
				ALTER TABLE itsm_incident ADD CONSTRAINT test_reject_employee
				    CHECK (source_data->>'assignedEmployee' IS DISTINCT FROM 'reject write')
				""");
		try {
			acceptSnapshot(BATCH_B, 2, snapshot(inc("A", "\"reject write\"")));
			acceptSnapshot(BATCH_C, 3, snapshot(inc("A", "\"third\"")));
			runner.start();
			awaitTerminal(BATCH_B);
			assertStatus(BATCH_B, "FAILED");
			assertThat(row(BATCH_B).get("next_attempt_at")).isNotNull();
			assertThat(changes(2)).isEmpty();
			assertThat(employee()).isEqualTo("initial");
			assertThat(observedVersion()).isEqualTo(1L);
		} finally {
			jdbcTemplate.execute("ALTER TABLE itsm_incident DROP CONSTRAINT test_reject_employee");
		}
		awaitSuccess(BATCH_B);
		awaitSuccess(BATCH_C);
		assertThat(changes(2)).containsExactly("A:UPDATED");
		assertThat(changes(3)).containsExactly("A:UPDATED");
		assertThat(employee()).isEqualTo("third");
	}

	@Test
	void manuallyRequeuesExhaustedTechnicalFailureSoSuccessorsCanProceed() throws Exception {
		runner.start();
		acceptSnapshot(BATCH_A, 1, snapshot(inc("A", "\"initial\"")));
		awaitSuccess(BATCH_A);
		jdbcTemplate.execute("""
				ALTER TABLE itsm_incident ADD CONSTRAINT test_exhaust_attempts
				    CHECK (source_data->>'assignedEmployee' IS DISTINCT FROM 'cannot write')
				""");
		try {
			acceptSnapshot(BATCH_B, 2, snapshot(inc("A", "\"cannot write\"")));
			acceptSnapshot(BATCH_C, 3, snapshot(inc("A", "\"third\"")));
			await("technical retries exhausted", () -> {
				var failed = row(BATCH_B);
				return "FAILED".equals(failed.get("status")) && failed.get("next_attempt_at") == null;
			});
			await("successor waits for the failed version", () -> Long.valueOf(2).equals(waitingFor(BATCH_C)));
		} finally {
			jdbcTemplate.execute("ALTER TABLE itsm_incident DROP CONSTRAINT test_exhaust_attempts");
		}
		mockMvc.perform(retry(BATCH_B)).andExpect(status().isAccepted())
				.andExpect(header().string(HttpHeaders.LOCATION, BATCH_PATH + "/" + BATCH_B));
		awaitSuccess(BATCH_C);
		assertThat(changes(2)).containsExactly("A:UPDATED");
		assertThat(changes(3)).containsExactly("A:UPDATED");
	}

	@Test
	void canonicalOpenApiExamplesAreAppliedByTheProductionWorker() throws Exception {
		runner.start();
		acceptSnapshot(BATCH_A, 42, fixture("/openapi/synthetic/snapshot-minimal.json"));
		awaitSuccess(BATCH_A);
		acceptSnapshot(BATCH_B, 43, fixture("/openapi/synthetic/snapshot-next-tick.json"));
		awaitSuccess(BATCH_B);
		assertThat(changes(43)).containsExactly("INC000021095533:UPDATED");
	}

	@Test
	void upgradesAnInstalledV4BaselineWithoutFalseChangesOrRevisionRegression() throws Exception {
		var pool = (com.zaxxer.hikari.HikariDataSource) dataSource;
		String schema = "kan55_upgrade";
		String url = pool.getJdbcUrl();
		Flyway.configure().dataSource(url, pool.getUsername(), pool.getPassword()).schemas(schema)
				.locations("classpath:db/migration").target(MigrationVersion.fromVersion("4")).load().migrate();
		String schemaUrl = url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema;
		var legacyJdbc = new JdbcTemplate(new DriverManagerDataSource(schemaUrl, pool.getUsername(), pool.getPassword()));
		String original = snapshot(inc("A", "\"Alex\""), inc("C", "\"C\""));
		insertBatch(legacyJdbc, BATCH_A, 42, original);
		legacyJdbc.update("""
				UPDATE itsm_ingest_batch SET status = 'SUCCEEDED', processing_revision = 42,
				    processed_at = now() WHERE id = ?
				""", BATCH_A);
		legacyJdbc.update("INSERT INTO itsm_catalog_product (product_key) VALUES ('Test product')");
		legacyJdbc.update("""
				INSERT INTO itsm_incident (id, state, priority_id, submit_date, product_id)
				SELECT ids.id, 'Assigned', 4, timestamptz '2026-11-21 02:00:00+00', product.id
				FROM (VALUES ('A'), ('C')) AS ids(id) CROSS JOIN itsm_catalog_product AS product
				""");
		legacyJdbc.update("""
				INSERT INTO itsm_snapshot (batch_id, source_id, source_version, payload_sha256, incident_count, applied_at)
				VALUES (?, ?, 42, ?, 2, now())
				""", BATCH_A, SOURCE, "0".repeat(64));
		try (var context = new SpringApplicationBuilder(DashboardApplication.class).profiles("work").run(
				"--server.port=0", "--itsm.worker.enabled=false",
				"--spring.datasource.url=" + schemaUrl, "--spring.datasource.username=" + pool.getUsername(),
				"--spring.datasource.password=" + pool.getPassword())) {
			var jdbc = context.getBean(JdbcTemplate.class);
			var worker = new ItsmWorkerTransactionService(new ItsmWorkerSqlRepository(jdbc),
					context.getBean(PlatformTransactionManager.class));
			var processor = context.getBean(ItsmSnapshotPublicationService.class);

			insertBatch(jdbc, BATCH_B, 43, original);
			assertThat(worker.claimAndProcess(processor, "upgrade-test").batchId()).isEqualTo(BATCH_B);
			assertThat(jdbc.queryForObject("SELECT count(*) FROM itsm_incident_change", Long.class)).isZero();
			assertThat(jdbc.queryForObject("SELECT processing_revision FROM itsm_ingest_batch WHERE id = ?",
					Long.class, BATCH_B)).isEqualTo(42L);

			insertBatch(jdbc, BATCH_C, 44, snapshot(inc("A", "\"Alex\"")));
			assertThat(worker.claimAndProcess(processor, "upgrade-test").batchId()).isEqualTo(BATCH_C);
			assertThat(jdbc.queryForMap("""
					SELECT change_kind, change_revision, before_data->>'assignedEmployee' AS before
					FROM itsm_incident_change
					""")).containsEntry("change_kind", "MISSING")
					.containsEntry("change_revision", 43L).containsEntry("before", "C");
		}
	}

	private static void insertBatch(JdbcTemplate jdbc, UUID id, long version, String snapshot) {
		byte[] body = snapshot.getBytes(java.nio.charset.StandardCharsets.UTF_8);
		jdbc.update("""
				INSERT INTO itsm_ingest_batch (id, source_id, source_version, payload, payload_sha256,
				                              payload_size, received_at, status, status_updated_at)
				VALUES (?, ?, ?, ?, ?, ?, now(), 'RECEIVED', now())
				""", id, SOURCE, version, body, "0".repeat(64), body.length);
	}

	private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder retry(UUID batchId) {
		return post(BATCH_PATH + "/" + batchId + "/retry")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + ItsmMachineJwtTestConfiguration.machineToken(jwtEncoder));
	}

	private String fixture(String path) throws java.io.IOException {
		try (var input = getClass().getResourceAsStream(path)) {
			assertThat(input).isNotNull();
			return new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
		}
	}

	private void acceptSnapshot(UUID id, long version, String body) throws Exception {
		mockMvc.perform(postBatch(id, SOURCE, version).content(body)).andExpect(status().isAccepted())
				.andExpect(header().string(HttpHeaders.LOCATION, BATCH_PATH + "/" + id))
				.andExpect(jsonPath("$.batchId").value(id.toString()));
	}

	private void awaitSuccess(UUID id) throws InterruptedException {
		await("batch " + id + " to succeed", () -> "SUCCEEDED".equals(row(id).get("status")));
	}

	private Long waitingFor(UUID id) {
		return jdbcTemplate.queryForObject("SELECT waiting_for_version FROM itsm_ingest_batch WHERE id = ?", Long.class, id);
	}

	private Long observedVersion() {
		return jdbcTemplate.queryForObject("SELECT observed_version FROM itsm_snapshot_source WHERE source_id = ?",
				Long.class, SOURCE);
	}

	private long revision() {
		return jdbcTemplate.queryForObject("SELECT change_revision FROM itsm_snapshot_source WHERE source_id = ?",
				Long.class, SOURCE);
	}

	private List<String> changes(long version) {
		return jdbcTemplate.queryForList("""
				SELECT incident_id || ':' || change_kind FROM itsm_incident_change
				WHERE source_id = ? AND source_version = ? ORDER BY incident_id
				""", String.class, SOURCE, version);
	}

	private String employee() {
		return jdbcTemplate.queryForObject("""
				SELECT source_data->>'assignedEmployee' FROM itsm_incident
				WHERE source_id = ? AND id = 'A'
				""", String.class, SOURCE);
	}

	private org.springframework.test.web.servlet.ResultActions sourceStatus() throws Exception {
		return mockMvc.perform(get("/api/snapshot/source-status").param("sourceId", SOURCE)
				.with(jwt().jwt(token -> token.issuer("https://stand.test").subject("operator").claim("azp","dashboard-web")))).andExpect(status().isOk());
	}

	private static String snapshot(String... incidents) {
		return "[" + String.join(",", incidents) + "]";
	}

	private static String inc(String suffix, String employee) {
		return """
				{"id":"%s","stateEnum":"Assigned","priorityId":4,"submitDate":"2026-11-21T02:00:00Z",
				 "productName":"Test product","assignedGroupName":"Test team","assignedEmployee":%s,
				 "assignedEmployeeLogin":%s,"solution":null}
				""".formatted(suffix, employee, employee);
	}

	private static String withDecimal(String decimal) {
		String incident = inc("A", "\"initial\"").strip();
		return incident.substring(0, incident.length() - 1) + ",\"metric\":" + decimal + "}";
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class EventConfiguration {
		@Bean
		PublishedEvents publishedEvents() {
			return new PublishedEvents();
		}
	}

	static class PublishedEvents extends ConcurrentLinkedQueue<ItsmSnapshotPublished> {
		@EventListener
		void record(ItsmSnapshotPublished event) {
			add(event);
		}
	}
}
