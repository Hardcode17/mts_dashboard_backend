package ru.hardcode.dashboard.worker;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.hardcode.dashboard.worker.ItsmWorkerSqlRepository.ClaimedBatch;
import ru.hardcode.dashboard.worker.ItsmWorkerTransactionService.ItsmBatchProcessingException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ItsmWorkerServiceTest {

	private static final String OWNER = "host:1:uuid";
	private static final Instant NOW = Instant.parse("2026-11-21T10:00:00Z");

	@Mock
	private ItsmWorkerTransactionService transactionService;

	@Mock
	private ItsmWorkerSqlRepository sqlRepository;

	@Mock
	private ItsmBatchProcessor processor;

	private ItsmWorkerService service;

	@BeforeEach
	void setUp() {
		service = new ItsmWorkerService(
				transactionService,
				sqlRepository,
				processor,
				OWNER,
				new ItsmWorkerProperties(2, 3, Duration.ofSeconds(10), Duration.ofSeconds(1), Duration.ofSeconds(5), Duration.ofMinutes(1), Duration.ofMinutes(10)),
				() -> NOW
		);
	}

	@Test
	void successfulBatch_doesNotRecordFailure() {
		ClaimedBatch batch = batch(2);
		given(transactionService.claimAndProcess(processor, OWNER)).willReturn(batch);

		service.executeTask();

		verify(transactionService, never()).recordFailureGuarded(any(), any(), any(), any());
	}

	@Test
	void processingFailure_isRecordedInGuardedTransactionWithBackoff() {
		ClaimedBatch batch = batch(1);
		given(transactionService.claimAndProcess(processor, OWNER))
				.willThrow(new ItsmBatchProcessingException(batch, new IllegalStateException("boom")));
		given(sqlRepository.hasPendingNewerVersion("source", 7, batch.batchId())).willReturn(false);
		given(transactionService.recordFailureGuarded(eq(batch), eq(OWNER), any(), any())).willReturn(true);

		service.executeTask();

		InOrder order = inOrder(transactionService, sqlRepository);
		order.verify(transactionService).claimAndProcess(processor, OWNER);
		order.verify(sqlRepository).hasPendingNewerVersion("source", 7, batch.batchId());
		order.verify(transactionService).recordFailureGuarded(
				eq(batch),
				eq(OWNER),
				eq("ITSM snapshot version 7 could not be processed on attempt 1"),
				eq(NOW.plusSeconds(10))
		);
	}

	@Test
	void processingFailure_neverExposesProcessorMessage() {
		ClaimedBatch batch = batch(1);
		given(transactionService.claimAndProcess(processor, OWNER))
				.willThrow(new ItsmBatchProcessingException(batch, new IllegalStateException("secret-token=abc payload=[]")));
		given(sqlRepository.hasPendingNewerVersion(any(), anyLong(), any())).willReturn(false);

		service.executeTask();

		verify(transactionService).recordFailureGuarded(
				eq(batch),
				eq(OWNER),
				eq("ITSM snapshot version 7 could not be processed on attempt 1"),
				any()
		);
	}

	@Test
	void newestAttempt_isNotRetriedAutomatically() {
		ClaimedBatch batch = batch(3);
		given(transactionService.claimAndProcess(processor, OWNER))
				.willThrow(new ItsmBatchProcessingException(batch, new IllegalStateException()));
		given(sqlRepository.hasPendingNewerVersion(any(), anyLong(), any())).willReturn(false);

		service.executeTask();

		verify(transactionService).recordFailureGuarded(eq(batch), eq(OWNER), any(), isNull());
	}

	@Test
	void failedOlderVersion_isNotRetriedWhileNewerVersionWaits() {
		ClaimedBatch batch = batch(1);
		given(transactionService.claimAndProcess(processor, OWNER))
				.willThrow(new ItsmBatchProcessingException(batch, new IllegalStateException()));
		given(sqlRepository.hasPendingNewerVersion("source", 7, batch.batchId())).willReturn(true);

		service.executeTask();

		verify(transactionService).recordFailureGuarded(eq(batch), eq(OWNER), any(), isNull());
	}

	@Test
	void failureOutsideProcessor_doesNotPublishAnything() {
		given(transactionService.claimAndProcess(processor, OWNER))
				.willThrow(new IllegalStateException("connection reset"));

		service.executeTask();

		verify(transactionService, never()).recordFailureGuarded(any(), any(), any(), any());
	}

	@Test
	void failureHandlingError_isSwallowed() {
		ClaimedBatch batch = batch(1);
		given(transactionService.claimAndProcess(processor, OWNER))
				.willThrow(new ItsmBatchProcessingException(batch, new IllegalStateException()));
		given(sqlRepository.hasPendingNewerVersion(any(), anyLong(), any())).willThrow(new IllegalStateException("db down"));

		service.executeTask();

		verify(transactionService, never()).recordFailureGuarded(any(), any(), any(), any());
	}

	@Test
	void periodicRecovery_releasesStaleClaims() {
		given(transactionService.recoverAbandoned(any())).willReturn(2);

		assertThat(service.recoverStaleClaims()).isEqualTo(2);

		verify(transactionService).recoverAbandoned(NOW.minus(Duration.ofMinutes(10)));
	}

	@Test
	void startupRecovery_onlyReclaimsStaleClaims() {
		given(transactionService.recoverAbandoned(NOW.minus(Duration.ofMinutes(10)))).willReturn(1);

		assertThat(service.recoverAtStartup()).isEqualTo(1);
	}

	@Test
	void diagnostic_isTruncatedToColumnLength() {
		String truncated = ItsmWorkerService.diagnostic("x".repeat(2500));

		assertThat(truncated).hasSize(ItsmWorkerService.DIAGNOSTIC_LIMIT);
		assertThat(ItsmWorkerService.diagnostic(null)).isNull();
	}

	private static ClaimedBatch batch(int attempt) {
		return new ClaimedBatch(UUID.randomUUID(), "source", 7, new byte[] {'[', ']'}, attempt, 0);
	}
}
