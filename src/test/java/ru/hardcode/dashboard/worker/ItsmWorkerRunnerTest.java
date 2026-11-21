package ru.hardcode.dashboard.worker;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import ru.hardcode.dashboard.worker.ItsmWorkerTransactionService.ItsmBatchProcessingException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

class ItsmWorkerRunnerTest {

	private ItsmWorkerRunner runner;

	@AfterEach
	void tearDown() {
		if (runner != null) {
			runner.stop();
		}
	}

	@Test
	void dispatch_neverExceedsTwiceTheWorkersEvenWhenWokenRepeatedly() throws Exception {
		CountDownLatch release = new CountDownLatch(1);
		AtomicInteger claims = new AtomicInteger();
		ItsmWorkerTransactionService transactionService = mock(ItsmWorkerTransactionService.class);
		given(transactionService.claimAndProcess(any(), any())).willAnswer(invocation -> {
			claims.incrementAndGet();
			release.await(10, TimeUnit.SECONDS);
			return idle();
		});
		runner = new ItsmWorkerRunner(workerService(transactionService, 2), properties(2));
		runner.start();

		for (int i = 0; i < 50; i++) {
			runner.dispatchSafely();
			runner.wake();
		}

		waitUntil(() -> runner.inFlight() == 4);
		Thread.sleep(200);
		assertThat(runner.inFlight()).isEqualTo(4);
		assertThat(claims.get()).isEqualTo(2);

		release.countDown();
		// Scheduled polls can start another bounded round after the blocked work drains.
		waitUntil(() -> claims.get() >= 4);
	}

	@Test
	void taskFailure_releasesCapacityAndAnotherTaskCanStart() throws Exception {
		AtomicInteger claims = new AtomicInteger();
		ItsmWorkerTransactionService transactionService = mock(ItsmWorkerTransactionService.class);
		given(transactionService.claimAndProcess(any(), any())).willAnswer(invocation -> {
			if (claims.incrementAndGet() == 1) {
				// Claim transaction failed outside the processor: it rolled back.
				throw new IllegalStateException("unexpected connection loss");
			}
			return idle();
		});
		runner = new ItsmWorkerRunner(workerService(transactionService, 1), properties(1));
		runner.start();

		runner.dispatchSafely();
		// A subsequent dispatch can use the released slot.
		waitUntil(() -> claims.get() == 2);
		assertThat(claims.get()).isGreaterThanOrEqualTo(2);
	}

	@Test
	void idleTask_releasesSlot() {
		AtomicInteger claims = new AtomicInteger();
		List<String> threads = new CopyOnWriteArrayList<>();
		ItsmWorkerTransactionService transactionService = mock(ItsmWorkerTransactionService.class);
		given(transactionService.claimAndProcess(any(), any())).willAnswer(invocation -> {
			claims.incrementAndGet();
			threads.add(Thread.currentThread().getName());
			return idle();
		});
		runner = new ItsmWorkerRunner(workerService(transactionService, 3), properties(3));
		runner.start();

		runner.dispatchSafely();

		waitUntil(() -> claims.get() >= 3);
		assertThat(threads).allMatch(name -> name.startsWith("itsm-worker-"));
		waitUntil(() -> runner.inFlight() == 0);
	}

	@Test
	void processingFailure_isHandledByWorkerServiceNotRunner() throws Exception {
		ItsmWorkerTransactionService transactionService = mock(ItsmWorkerTransactionService.class);
		ItsmWorkerSqlRepository sqlRepository = mock(ItsmWorkerSqlRepository.class);
		given(transactionService.claimAndProcess(any(), any()))
				.willThrow(new ItsmBatchProcessingException(
						new ItsmWorkerSqlRepository.ClaimedBatch(java.util.UUID.randomUUID(), "s", 1, new byte[0], 1, 0),
						new IllegalStateException()
				));
		ItsmWorkerService service = new ItsmWorkerService(
				transactionService,
				sqlRepository,
				mock(ItsmBatchProcessor.class),
				"owner",
				properties(1),
				() -> java.time.Instant.now()
		);
		runner = new ItsmWorkerRunner(service, properties(1));
		runner.start();

		runner.dispatchSafely();

		// The worker handles the claim failure and returns the slot to the dispatcher.
		org.mockito.Mockito.verify(transactionService, org.mockito.Mockito.timeout(5000).atLeastOnce())
				.recordFailureGuarded(any(), any(), any(), any());
		waitUntil(() -> runner.inFlight() == 0);
	}

	@Test
	void stop_beforeStart_isIdempotent() {
		runner = new ItsmWorkerRunner(workerService(mock(ItsmWorkerTransactionService.class), 1), properties(1));

		runner.stop();
		runner.stop();

		assertThat(runner.isRunning()).isFalse();
	}

	@Test
	void capacity_isBoundedByTwiceTheWorkers() throws Exception {
		CountDownLatch release = new CountDownLatch(1);
		AtomicInteger claims = new AtomicInteger();
		ItsmWorkerTransactionService transactionService = mock(ItsmWorkerTransactionService.class);
		given(transactionService.claimAndProcess(any(), any())).willAnswer(invocation -> {
			claims.incrementAndGet();
			release.await(10, TimeUnit.SECONDS);
			return null;
		});
		runner = new ItsmWorkerRunner(workerService(transactionService, 3), properties(3));
		runner.start();

		runner.dispatchSafely();
		runner.dispatchSafely();
		runner.dispatchSafely();

		waitUntil(() -> runner.inFlight() == 6);
		release.countDown();
		waitUntil(() -> runner.inFlight() == 0);
		assertThat(claims.get()).isEqualTo(6);
	}

	private static ItsmWorkerService workerService(
			ItsmWorkerTransactionService transactionService,
			int workers
	) {
		return new ItsmWorkerService(
				transactionService,
				mock(ItsmWorkerSqlRepository.class),
				mock(ItsmBatchProcessor.class),
				"owner",
				properties(workers),
				java.time.Instant::now
		);
	}

	private static ItsmWorkerSqlRepository.ClaimedBatch idle() {
		return new ItsmWorkerSqlRepository.ClaimedBatch(java.util.UUID.randomUUID(), "s", 1, new byte[0], 1, 0);
	}

	private static ItsmWorkerProperties properties(int workers) {
		return new ItsmWorkerProperties(
				workers, 1, Duration.ZERO, Duration.ofMillis(50),
				Duration.ofSeconds(1), Duration.ofMinutes(1), Duration.ofMinutes(1)
		);
	}

	private static void waitUntil(BooleanSupplier condition) {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
			try {
				Thread.sleep(10);
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				return;
			}
		}
		assertThat(condition.getAsBoolean()).isTrue();
	}
}
