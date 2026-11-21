package ru.hardcode.dashboard;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import ru.hardcode.dashboard.client.ItsmMachineJwtTestConfiguration;
import ru.hardcode.dashboard.worker.ItsmWorkerProperties;
import ru.hardcode.dashboard.worker.ItsmWorkerRunner;
import ru.hardcode.dashboard.worker.ItsmWorkerServiceAccess;
import ru.hardcode.dashboard.worker.ItsmWorkerTransactionService;
import ru.hardcode.dashboard.worker.ItsmWorkerSqlRepository;
import ru.hardcode.dashboard.worker.TestingProcessorConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boundary checks of the KAN-54 worker on PostgreSQL 17: pool sizes 1 and 2 produce the same
 * final state, restarts never apply a committed result twice, an interrupted claim is recovered
 * for a safe retry, and an older version can never overwrite a newer published revision.
 */
@SpringBootTest(properties = {
		"itsm.integration.auth.issuer=" + ItsmMachineJwtTestConfiguration.ISSUER,
		"itsm.integration.auth.jwk-set-uri=https://keycloak.test/realms/itsm/protocol/openid-connect/certs",
		"itsm.integration.auth.audience=" + ItsmMachineJwtTestConfiguration.AUDIENCE,
		"itsm.integration.auth.client-id=" + ItsmMachineJwtTestConfiguration.CLIENT_ID,
		"itsm.integration.auth.import-role=" + ItsmMachineJwtTestConfiguration.IMPORT_ROLE,
		"itsm.intake.max-outstanding-batches=50",
		"itsm.worker.workers=2",
		"itsm.worker.max-attempts=3",
		"itsm.worker.retry-base-delay=PT1H",
		"itsm.worker.poll-interval=PT0.1S",
		"itsm.worker.recovery-interval=PT1H",
		"spring.flyway.locations=classpath:db/migration,classpath:db/demo,classpath:db/test"
})
@AutoConfigureMockMvc
@ActiveProfiles("demo")
@Import({
		ItsmPostgresTestcontainersConfiguration.class,
		ItsmMachineJwtTestConfiguration.class,
		TestingProcessorConfiguration.class
})
class ItsmWorkerBoundaryIT extends ItsmWorkerTestSupport {

	@Autowired
	private ItsmWorkerRunner runner;

	@Autowired
	private ItsmWorkerProperties properties;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@Autowired
	private ItsmWorkerSqlRepository workerSqlRepository;

	@BeforeEach
	void setUp() {
		runner.stop();
		resetDatabaseAndProcessor();
	}

	@Test
	void poolOfOneAndPoolAboveOneProduceTheSameFinalState() throws Exception {
		assertThat(properties.workers()).isEqualTo(2);
		Map<UUID, Map<String, Object>> withTwo = runWithPool(2);
		Map<UUID, Map<String, Object>> withOne = runWithPool(1);

		assertThat(withOne.keySet()).isEqualTo(withTwo.keySet());
		for (UUID batchId : withOne.keySet()) {
			assertThat(withOne.get(batchId)).isEqualTo(withTwo.get(batchId));
			assertThat(withOne.get(batchId).get("status")).isEqualTo("SUCCEEDED");
			assertThat(withOne.get(batchId).get("diagnostic")).isEqualTo("applied by test processor");
		}
	}

	@Test
	void restartDoesNotApplyCommittedResultTwice() throws Exception {
		accept(postBatch(BATCH_A, "restart-source", 3));
		runner.start();
		awaitTerminal(BATCH_A);
		assertStatus(BATCH_A, "SUCCEEDED");
		long rowVersion = ((Number) row(BATCH_A).get("row_version")).longValue();
		assertThat(processor.calls(BATCH_A)).isEqualTo(1);

		// Same database, new worker process.
		runner.stop();
		runner.start();
		Thread.sleep(1000);

		assertThat(processor.calls(BATCH_A)).isEqualTo(1);
		assertThat(processor.committedCalls(BATCH_A)).isEqualTo(1);
		assertThat(((Number) row(BATCH_A).get("row_version")).longValue()).isEqualTo(rowVersion);
		assertThat(processor.currentRevision("restart-source")).isEqualTo(3);
		// An idempotent HTTP replay is not reprocessed either.
		accept(postBatch(BATCH_A, "restart-source", 3));
		Thread.sleep(500);
		assertThat(processor.calls(BATCH_A)).isEqualTo(1);
	}

	@Test
	void interruptedClaimIsRecoveredOnRestartAndRetriedOnce() throws Exception {
		processor.hold(BATCH_A);
		accept(postBatch(BATCH_A, "interrupted-source", 5));
		ItsmWorkerRunner interruptedRunner = new ItsmWorkerRunner(
				ItsmWorkerServiceAccess.runnerService(runner),
				new ItsmWorkerProperties(
						1,
						properties.maxAttempts(),
						properties.retryBaseDelay(),
						properties.pollInterval(),
						Duration.ZERO,
						properties.recoveryInterval(),
						properties.recoveryHeartbeatTimeout()
				)
		);
		interruptedRunner.start();
		try {
			assertThat(processor.awaitHeld(BATCH_A, AWAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
			assertStatus(BATCH_A, "PROCESSING");
		} finally {
			interruptedRunner.stop();
			processor.open(BATCH_A);
		}
		assertStatus(BATCH_A, "PROCESSING");
		assertThat(processor.committedCalls(BATCH_A)).isZero();
		assertThat(row(BATCH_A).get("batch_attempts")).isEqualTo(1);

		// Simulate the downtime passing without shortening the lease for healthy workers.
		jdbcTemplate.update(
				"UPDATE itsm_ingest_batch SET heartbeat_at = CURRENT_TIMESTAMP - INTERVAL '1 hour' WHERE id = ?",
				BATCH_A
		);
		runner.start();
		await("interrupted batch succeeds exactly once", () ->
				"SUCCEEDED".equals(row(BATCH_A).get("status")) && processor.committedCalls(BATCH_A) == 1
		);

		assertThat(processor.calls(BATCH_A)).isEqualTo(2);
		assertThat(processor.currentRevision("interrupted-source")).isEqualTo(5);
		assertThat(row(BATCH_A).get("batch_attempts")).isEqualTo(2);
	}

	@Test
	void olderVersionNeverOverwritesNewerPublishedRevision() throws Exception {
		processor.fail(BATCH_A);
		accept(postBatch(BATCH_A, "versioned-source", 7));
		runner.start();
		awaitTerminal(BATCH_A);
		assertStatus(BATCH_A, "FAILED");
		assertThat(processor.currentRevision("versioned-source")).isZero();

		// The newer snapshot becomes visible while the older batch waits for its retry.
		accept(postBatch(BATCH_B, "versioned-source", 8));
		awaitTerminal(BATCH_B);
		assertStatus(BATCH_B, "SUCCEEDED");
		assertThat(processor.currentRevision("versioned-source")).isEqualTo(8);

		// Force a real worker attempt of the older version, as after a manual requeue.
		runner.stop();
		processor.resetRecords();
		jdbcTemplate.update(
				"UPDATE itsm_ingest_batch SET status = 'RECEIVED', next_attempt_at = NULL WHERE id = ?",
				BATCH_A
		);
		ItsmWorkerTransactionService transactions = new ItsmWorkerTransactionService(
				workerSqlRepository,
				transactionManager
		);
		ItsmWorkerSqlRepository.ClaimedBatch claimed = transactions.claimAndProcess(processor, "manual-worker");

		assertThat(claimed).isNotNull();
		assertThat(claimed.batchId()).isEqualTo(BATCH_A);
		assertThat(processor.currentRevision("versioned-source")).isEqualTo(8);
		assertThat(row(BATCH_A).get("processing_revision")).isEqualTo(8L);
		assertThat(row(BATCH_B).get("processing_revision")).isEqualTo(8L);
		assertStatus(BATCH_B, "SUCCEEDED");

		// A stale write with an old row version is fenced and never resurrects the batch state.
		int rowVersion = ((Number) row(BATCH_A).get("row_version")).intValue();
		int stale = jdbcTemplate.update(
				"""
				UPDATE itsm_ingest_batch
				SET status = 'SUCCEEDED', processing_revision = 7
				WHERE id = ? AND status = 'PROCESSING' AND claimed_by = 'killed' AND row_version = 0
				""",
				BATCH_A
		);
		assertThat(stale).isZero();
		assertThat(((Number) row(BATCH_A).get("row_version")).intValue()).isEqualTo(rowVersion);
		assertThat(((Number) row(BATCH_B).get("processing_revision")).intValue()).isEqualTo(8);
	}

	private Map<UUID, Map<String, Object>> runWithPool(int workers) throws Exception {
		resetDatabaseAndProcessor();
		List<UUID> batches = List.of(BATCH_A, BATCH_B, BATCH_C);
		for (int i = 0; i < batches.size(); i++) {
			accept(postBatch(batches.get(i), "parity-" + i, i + 1));
		}
		ItsmWorkerRunner pool = new ItsmWorkerRunner(
				ItsmWorkerServiceAccess.runnerService(runner),
				withWorkers(workers)
		);
		pool.start();
		try {
			awaitTerminal(batches.toArray(UUID[]::new));
		} finally {
			pool.stop();
		}

		Map<UUID, Map<String, Object>> result = new LinkedHashMap<>();
		for (UUID batchId : batches) {
			Map<String, Object> row = row(batchId);
			result.put(batchId, Map.of(
					"status", row.get("status"),
					"diagnostic", String.valueOf(row.get("diagnostic")),
					"revision", row.get("processing_revision"),
					"claimedBy", String.valueOf(row.get("claimed_by")),
					"nextAttemptAt", String.valueOf(row.get("next_attempt_at"))
			));
		}
		return result;
	}

	private ItsmWorkerProperties withWorkers(int workers) {
		return new ItsmWorkerProperties(
				workers,
				properties.maxAttempts(),
				properties.retryBaseDelay(),
				properties.pollInterval(),
				properties.drainTimeout(),
				properties.recoveryInterval(),
				properties.recoveryHeartbeatTimeout()
		);
	}
}
