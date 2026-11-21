package ru.hardcode.dashboard.worker;

import java.time.Instant;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.hardcode.dashboard.worker.ItsmWorkerSqlRepository.ClaimedBatch;
import ru.hardcode.dashboard.worker.ItsmWorkerTransactionService.ItsmBatchProcessingException;

/**
 * Lifecycle of one worker invocation and the database load source of KAN-54.
 *
 * <p>The database is the durable queue. A worker thread only learns that a batch may exist; the
 * claim runs in a fresh transaction, so a batch committed by the HTTP thread after its {@code 202}
 * is picked up on the next poll. The HTTP transaction is never handed to the worker.
 *
 * <p>Capacity is bounded by the number of worker threads: at most that many batches hold a claim
 * at once, and the number of outstanding rows is bounded by
 * {@code itsm.intake.max-outstanding-batches} (KAN-27). No accepted batch is dropped: a claimed
 * batch either commits its result or is recorded as {@code FAILED}; an interrupted claim becomes {@code RECEIVED} after recovery and is available for a safe retry.
 */
public class ItsmWorkerService {

	static final String ABANDONED_DIAGNOSTIC =
			"Processing was interrupted before it completed; batch is available for retry";

	static final int DIAGNOSTIC_LIMIT = 2000;

	private static final Logger log = LoggerFactory.getLogger(ItsmWorkerService.class);

	private final ItsmWorkerTransactionService transactionService;
	private final ItsmWorkerSqlRepository sqlRepository;
	private final ItsmBatchProcessor processor;
	private final String owner;
	private final ItsmWorkerProperties properties;
	private final Supplier<Instant> clock;

	ItsmWorkerService(
			ItsmWorkerTransactionService transactionService,
			ItsmWorkerSqlRepository sqlRepository,
			ItsmBatchProcessor processor,
			String owner,
			ItsmWorkerProperties properties,
			Supplier<Instant> clock
	) {
		this.transactionService = transactionService;
		this.sqlRepository = sqlRepository;
		this.processor = processor;
		this.owner = owner;
		this.properties = properties;
		this.clock = clock;
	}

	/**
	 * Runs on a worker thread. Exceptions never escape: one failed batch must not stop other
	 * workers or the dispatcher.
	 */
	void executeTask() {
		try {
			transactionService.claimAndProcess(processor, owner);
		} catch (ItsmBatchProcessingException exception) {
			if (!Thread.currentThread().isInterrupted()) {
				recordFailure(exception.batch(), exception.getCause());
			}
		} catch (RuntimeException exception) {
			// The claim transaction failed before publication began.
			log.error("ITSM worker claim transaction failed: {}", exception.getClass().getSimpleName());
		}
	}

	private void recordFailure(ClaimedBatch batch, Throwable cause) {
		try {
			boolean newerPending = sqlRepository.hasPendingNewerVersion(
					batch.sourceId(), batch.sourceVersion(), batch.batchId()
			);
			Instant nextAttemptAt = nextAttemptAt(batch.attempt(), newerPending);
			String diagnostic = failureDiagnostic(batch.sourceVersion(), batch.attempt());
			boolean recorded = transactionService.recordFailureGuarded(
					batch,
					owner,
					diagnostic,
					nextAttemptAt
			);
			if (recorded) {
				log.info(
						"ITSM batch {} FAILED on attempt {} ({}); automatic retry {}",
						batch.batchId(),
						batch.attempt(),
						cause == null ? "processing error" : cause.getClass().getSimpleName(),
						nextAttemptAt == null ? "not scheduled" : "at " + nextAttemptAt
				);
			}
		} catch (RuntimeException exception) {
			log.error("ITSM batch {} failure handling failed: {}", batch.batchId(), exception.getClass().getSimpleName());
		}
	}

	/**
	 * Visible diagnostic of a normal processing failure. Deliberately generic: processor exception
	 * messages can contain payload fragments, SQL, or secrets and are never exposed.
	 */
	static String diagnostic(String value) {
		if (value == null || value.length() <= DIAGNOSTIC_LIMIT) {
			return value;
		}
		return value.substring(0, DIAGNOSTIC_LIMIT);
	}

	static String failureDiagnostic(long sourceVersion, int attempt) {
		return diagnostic("ITSM snapshot version " + sourceVersion + " could not be processed on attempt " + attempt);
	}

	/**
	 * Avoid scheduling a retry when a newer batch is already queued. A newer batch can still
	 * arrive later, so the processor must enforce version ordering at publication.
	 */
	private Instant nextAttemptAt(int attempt, boolean newerPending) {
		if (attempt >= properties.maxAttempts() || newerPending) {
			return null;
		}
		long factor = 1L << Math.min(attempt - 1, 20);
		long backoffMillis = properties.retryBaseDelay().toMillis() * factor;
		return clock.get().plusMillis(backoffMillis);
	}

	/** Checks for stale claims when the worker starts. */
	public int recoverAtStartup() {
		return recoverStaleClaims();
	}

	/** Sweeps stale claims even if other workers are active. */
	public int recoverStaleClaims() {
		return transactionService.recoverAbandoned(clock.get().minus(properties.recoveryHeartbeatTimeout()));
	}
}
