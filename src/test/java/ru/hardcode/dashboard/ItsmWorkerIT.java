package ru.hardcode.dashboard;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import ru.hardcode.dashboard.client.ItsmMachineJwtTestConfiguration;
import ru.hardcode.dashboard.client.TestItsmBatchProcessor;
import ru.hardcode.dashboard.worker.ItsmWorkerRunner;
import ru.hardcode.dashboard.worker.TestingProcessorConfiguration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * KAN-54 worker with two workers over the real HTTP intake path (MockMvc, machine
 * authentication), the durable PostgreSQL 17 queue (Testcontainers) and real worker threads.
 * The processor is a test-only KAN-55 stand-in that publishes inside the claim transaction.
 *
 * <p>The worker is stopped before each test and started explicitly, so each test decides when
 * accepted batches become claimable.
 */
@SpringBootTest(properties = {
		"itsm.publication.enabled=false",
		"itsm.integration.auth.issuer=" + ItsmMachineJwtTestConfiguration.ISSUER,
		"itsm.integration.auth.jwk-set-uri=https://keycloak.test/realms/itsm/protocol/openid-connect/certs",
		"itsm.integration.auth.audience=" + ItsmMachineJwtTestConfiguration.AUDIENCE,
		"itsm.integration.auth.client-id=" + ItsmMachineJwtTestConfiguration.CLIENT_ID,
		"itsm.integration.auth.import-role=" + ItsmMachineJwtTestConfiguration.IMPORT_ROLE,
		"itsm.intake.max-outstanding-batches=4",
		"itsm.worker.workers=2",
		"itsm.worker.max-attempts=3",
		"itsm.worker.retry-base-delay=PT1H",
		"itsm.worker.poll-interval=PT0.1S",
		"itsm.worker.recovery-interval=PT1H",
		"spring.flyway.locations=classpath:db/migration,classpath:db/demo,classpath:db/test",
		"spring.security.user.name=operator",
		"spring.security.user.password=kan54-test-password"
})
@AutoConfigureMockMvc
@ActiveProfiles("demo")
@Import({
		ItsmPostgresTestcontainersConfiguration.class,
		ItsmMachineJwtTestConfiguration.class,
		TestingProcessorConfiguration.class
})
class ItsmWorkerIT extends ItsmWorkerTestSupport {

	@Autowired
	private ItsmWorkerRunner runner;

	@BeforeEach
	void setUp() {
		runner.stop();
		resetDatabaseAndProcessor();
	}

	@Test
	void acceptedBatchesAreProcessedConcurrentlyAfterHttpAlreadyReturned202() throws Exception {
		TestItsmBatchProcessor.Barrier barrier = processor.barrier(2);
		processor.hold(BATCH_A);
		processor.hold(BATCH_B);

		// Both requests return 202 before the worker starts: the database is the durable queue.
		assertThat(accept(postBatch(BATCH_A, "itsm-source-a", 1))).isEqualTo(BATCH_A);
		assertThat(accept(postBatch(BATCH_B, "itsm-source-b", 1))).isEqualTo(BATCH_B);
		assertThat(rows(BATCH_A, BATCH_B)).hasSize(2);

		runner.start();

		try {
			assertThat(barrier.awaitAll(AWAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
			assertThat(barrier.distinctThreads()).isEqualTo(2);
			barrier.release();
			assertThat(processor.awaitHeld(BATCH_A, AWAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
			assertThat(processor.awaitHeld(BATCH_B, AWAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
			assertThat(processor.maxConcurrent()).isGreaterThanOrEqualTo(2);
			assertStatus(BATCH_A, "PROCESSING");
			assertStatus(BATCH_B, "PROCESSING");
		} finally {
			barrier.release();
			processor.open(BATCH_A);
			processor.open(BATCH_B);
		}

		awaitTerminal(BATCH_A, BATCH_B);
		for (UUID batchId : List.of(BATCH_A, BATCH_B)) {
			mockMvc.perform(getBatch(batchId))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.batchId").value(batchId.toString()))
					.andExpect(jsonPath("$.status").value("SUCCEEDED"))
					.andExpect(jsonPath("$.diagnostic").value("applied by test processor"))
					.andExpect(jsonPath("$.nextAttemptAt").value(nullValue()))
					.andExpect(jsonPath("$.payload").doesNotExist());
		}
		mockMvc.perform(get("/api/snapshot/status").with(httpBasic("operator", "kan54-test-password")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.lastSuccessAt").isNotEmpty())
				.andExpect(jsonPath("$.revision").value(greaterThanOrEqualTo(1)))
				.andExpect(jsonPath("$.stale").value(false));
		assertThat(processor.currentRevision("itsm-source-a")).isEqualTo(1);
		assertThat(processor.currentRevision("itsm-source-b")).isEqualTo(1);
		assertThat(processor.calls(BATCH_A)).isEqualTo(1);
		assertThat(processor.calls(BATCH_B)).isEqualTo(1);
	}

	@Test
	void processingErrorHasClearStatusAndDoesNotStopOtherBatches() throws Exception {
		processor.failAfterWrite(BATCH_A);
		accept(postBatch(BATCH_A, "failing-source", 4));
		accept(postBatch(BATCH_B, "healthy-source", 2));

		runner.start();
		awaitTerminal(BATCH_A, BATCH_B);

		mockMvc.perform(getBatch(BATCH_A))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("FAILED"))
				.andExpect(jsonPath("$.diagnostic").value(WORKER_FAILED.formatted(4, 1)))
				.andExpect(jsonPath("$.nextAttemptAt").isNotEmpty());
		mockMvc.perform(getBatch(BATCH_B))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("SUCCEEDED"));
		assertThat(row(BATCH_A).get("processing_revision")).isNull();
		assertThat(row(BATCH_A).get("batch_attempts")).isEqualTo(1);
		assertThat(processor.currentRevision("failing-source")).isZero();

		// The worker survived the error and keeps processing new batches.
		assertThat(runner.isRunning()).isTrue();
		accept(postBatch(BATCH_C, "healthy-source-2", 1));
		awaitTerminal(BATCH_C);
		assertStatus(BATCH_C, "SUCCEEDED");
	}
	@Test
	void fullQueueRejectsOnlyNewWorkAndDrainsAllAcceptedBatches() throws Exception {
		UUID fourth = UUID.randomUUID();
		UUID rejected = UUID.randomUUID();
		List<UUID> accepted = List.of(BATCH_A, BATCH_B, BATCH_C, fourth);
		for (UUID id : accepted) {
			accept(postBatch(id, id.toString(), 1));
		}
		mockMvc.perform(postBatch(rejected, "rejected-source", 1))
				.andExpect(status().isTooManyRequests())
				.andExpect(jsonPath("$.code").value("ITSM_INTAKE_QUEUE_FULL"));
		assertThat(count()).isEqualTo(4);
		assertThat(rows(rejected)).isEmpty();

		runner.start();
		awaitTerminal(accepted.toArray(UUID[]::new));
		for (UUID id : accepted) {
			assertStatus(id, "SUCCEEDED");
			assertThat(processor.calls(id)).isEqualTo(1);
		}
	}

	@Test
	void retryWaitsForItsScheduledTimeAndUsesTheNextAttemptNumber() throws Exception {
		processor.failAfterWrite(BATCH_A);
		accept(postBatch(BATCH_A, "retry-source", 1));
		runner.start();
		awaitTerminal(BATCH_A);
		assertStatus(BATCH_A, "FAILED");
		assertThat(processor.calls(BATCH_A)).isEqualTo(1);

		runner.stop();
		processor.resetRecords();
		runner.start();
		assertStatus(BATCH_A, "FAILED");
		assertThat(processor.calls(BATCH_A)).isZero();

		jdbcTemplate.update("UPDATE itsm_ingest_batch SET next_attempt_at = CURRENT_TIMESTAMP WHERE id = ?", BATCH_A);
		await("scheduled retry succeeds", () ->
				"SUCCEEDED".equals(row(BATCH_A).get("status")) && processor.committedCalls(BATCH_A) == 1
		);
		assertThat(row(BATCH_A).get("batch_attempts")).isEqualTo(2);
		assertThat(processor.committedCalls(BATCH_A)).isEqualTo(1);
	}

}
