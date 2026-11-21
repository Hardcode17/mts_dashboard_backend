package ru.hardcode.dashboard.worker;

/**
 * Application-side processor invoked by the worker for one claimed batch.
 */
@FunctionalInterface
public interface ItsmBatchProcessor {

	/**
	 * Publishes the snapshot in the caller's transaction and returns the applied revision.
	 *
	 * <p>Contract:
	 * <ul>
	 *     <li>{@link #process} runs on a worker thread inside a fresh transaction that already
	 *     holds {@code SELECT ... FOR UPDATE} on the batch row. The HTTP request transaction is
	 *     never propagated here.</li>
	 *     <li>Any visible normalized state must be written through the {@link PublicationContext}
	 *     of the current transaction, and {@code markSucceeded} must be called only after it is
	 *     written. The worker then commits the publication and the batch status together, or rolls
	 *     both back.</li>
	 *     <li>Version ordering, conflicts and atomic publication are KAN-55 rules; this framework
	 *     only guarantees that the publication and final state commit as one unit and that a
	 *     stale claim can never write afterwards.</li>
	 *     <li>Return normally after selecting exactly one outcome. Throwing means it was not published:
	 *     the publication transaction rolls back, the committed claim remains {@code PROCESSING}, and the worker
	 *     records a {@code FAILED} diagnostic in a separate guarded transaction. Exceptions must
	 *     never carry secrets; their messages are not exposed.</li>
	 * </ul>
	 *
	 * @param work               immutable payload and metadata of the claimed batch
	 * @param publicationContext writes of the current publication transaction
	 */
	void process(ItsmBatchWork work, PublicationContext publicationContext);

	default boolean requiresSequentialVersions() {
		return false;
	}

	/**
	 * Writes inside the publication transaction.
	 */
	interface PublicationContext {

		/**
		 * Prepares the result returned for this batch. It does not publish anything by itself;
		 * the worker commits it together with the batch status at the end of the publication
		 * transaction, or discards it on rollback.
		 *
		 * @param revision   visible revision after publication (positive)
		 * @param diagnostic short public-safe summary stored in {@code diagnostic}
		 * @throws IllegalStateException if called more than once
		 */
		void markSucceeded(long revision, String diagnostic);

		/** Completes an older no-op without advancing the time of the last applied observation. */
		void markSkipped(long revision, String diagnostic);

		/** Records an immutable validation rejection; parser diagnostics must be public-safe. */
		void rejectInvalid(String diagnostic);

		/** Releases the claim without spending a retry attempt, until a predecessor settles. */
		void deferUntil(long sourceVersion);
	}
}
