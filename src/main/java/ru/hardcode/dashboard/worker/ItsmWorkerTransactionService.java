package ru.hardcode.dashboard.worker;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import ru.hardcode.dashboard.worker.ItsmWorkerSqlRepository.ClaimedBatch;

/**
 * Transaction boundaries of the worker. Each method runs in a new transaction of its own; the
 * HTTP request transaction is never propagated. Created by {@link ItsmWorkerConfiguration} only,
 * never component-scanned.
 */
public class ItsmWorkerTransactionService {

	private static final Logger log = LoggerFactory.getLogger(ItsmWorkerTransactionService.class);

	private final ItsmWorkerSqlRepository sqlRepository;
	private final TransactionTemplate transactionTemplate;

	public ItsmWorkerTransactionService(
			ItsmWorkerSqlRepository sqlRepository,
			PlatformTransactionManager transactionManager
	) {
		this.sqlRepository = sqlRepository;
		this.transactionTemplate = new TransactionTemplate(transactionManager);
		this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
	}

	/**
	 * Commits the claim before calling the processor. Publication takes its own transaction
	 * and locks the current claim, which prevents a recovery sweep from reassigning it.
	 */
	public ClaimedBatch claimAndProcess(ItsmBatchProcessor processor, String owner) {
		ClaimedBatch batch = transactionTemplate.execute(status ->
				sqlRepository.claimNext(owner, Instant.now()).orElse(null)
		);
		if (batch == null) {
			return null;
		}
		try {
			return transactionTemplate.execute(status -> {
				if (!sqlRepository.lockClaim(batch, owner)) {
					// Recovery already released the claim before processing acquired its lock.
					return null;
				}
				return processClaimedBatch(processor, owner, batch);
			});
		} catch (RuntimeException exception) {
			throw new ItsmBatchProcessingException(batch, exception);
		}
	}

	private ClaimedBatch processClaimedBatch(ItsmBatchProcessor processor, String owner, ClaimedBatch batch) {
		log.debug("ITSM batch {} claimed on attempt {} by {}", batch.batchId(), batch.attempt(), owner);

		PreparedPublication publication = new PreparedPublication();
		processor.process(
				new ItsmBatchWork(
						batch.batchId(),
						batch.sourceId(),
						batch.sourceVersion(),
						batch.payload(),
						batch.attempt()
				),
				publication
		);
		if (!publication.prepared) {
			throw new IllegalStateException("ITSM processor finished without markSucceeded");
		}

		Instant now = Instant.now();
		boolean applied = sqlRepository.markSucceeded(
				batch.batchId(),
				owner,
				batch.attempt(),
				batch.rowVersion(),
				publication.revision,
				ItsmWorkerService.diagnostic(publication.diagnostic),
				now
		);
		if (!applied) {
			// The transaction holds the current claim lock, so this is an invariant violation.
			throw new IllegalStateException("ITSM batch claim is no longer current inside its transaction");
		}
		log.info("ITSM batch {} SUCCEEDED with revision {}", batch.batchId(), publication.revision);
		return batch;
	}

	/**
	 * Records a normal processing failure in a separate guarded transaction. The update is fenced
	 * by claim owner, attempt and row version, so it can never overwrite a
	 * committed success or the state of another claim, and it never resurrects a recovered claim.
	 * Must be called only after the claim transaction has ended.
	 */
	public boolean recordFailureGuarded(
			ClaimedBatch batch,
			String owner,
			String diagnostic,
			Instant nextAttemptAt
	) {
		try {
			Boolean recorded = transactionTemplate.execute(status -> {
				Instant now = Instant.now();
				return sqlRepository.markFailed(
						batch.batchId(),
						owner,
						batch.attempt(),
						batch.rowVersion(),
						ItsmWorkerService.diagnostic(diagnostic),
						nextAttemptAt,
						now
				);
			});
			if (!Boolean.TRUE.equals(recorded)) {
				log.warn(
						"ITSM batch {} failure not recorded: claim {} attempt {} is no longer current",
						batch.batchId(),
						owner,
						batch.attempt()
				);
				return false;
			}
			return true;
		} catch (RuntimeException exception) {
			log.error("ITSM batch {} failure could not be recorded: {}", batch.batchId(), exception.getClass().getSimpleName());
			return false;
		}
	}

	/**
	 * Reclaims {@code PROCESSING} rows without a fresh heartbeat, in its own transaction.
	 *
	 * @param staleHeartbeatBefore heartbeat cutoff; claims newer than it are left alone
	 */
	public int recoverAbandoned(Instant staleHeartbeatBefore) {
		Integer recovered = transactionTemplate.execute(status ->
				sqlRepository.recoverAbandoned(Instant.now(), staleHeartbeatBefore)
		);
		int count = recovered == null ? 0 : recovered;
		if (count > 0) {
			log.warn("Recovered {} abandoned PROCESSING ITSM batches to RECEIVED", count);
		}
		return count;
	}

	/**
	 * Per-call publication prepared by the processor inside the publication transaction.
	 */
	private static final class PreparedPublication implements ItsmBatchProcessor.PublicationContext {

		private boolean prepared;
		private long revision;
		private String diagnostic;

		@Override
		public void markSucceeded(long revision, String diagnostic) {
			if (prepared) {
				throw new IllegalStateException("ITSM publication already prepared for this batch");
			}
			if (revision <= 0) {
				throw new IllegalArgumentException("ITSM applied revision must be positive");
			}
			prepared = true;
			this.revision = revision;
			this.diagnostic = diagnostic;
		}
	}

	/**
	 * A claimed batch whose publication failed. The publication transaction has rolled back when this
	 * exception leaves {@link #claimAndProcess}.
	 */
	public static final class ItsmBatchProcessingException extends RuntimeException {

		private final ClaimedBatch batch;

		ItsmBatchProcessingException(ClaimedBatch batch, RuntimeException cause) {
			super("ITSM batch " + batch.batchId() + " processing failed on attempt " + batch.attempt(), cause);
			this.batch = batch;
		}

		public ClaimedBatch batch() {
			return batch;
		}
	}
}
