package ru.hardcode.dashboard.client;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import ru.hardcode.dashboard.worker.ItsmBatchProcessor;
import ru.hardcode.dashboard.worker.ItsmBatchWork;

/**
 * Test-only KAN-55 stand-in. It publishes a normalized row inside the claim transaction, obeys
 * the same revision rule (an older revision never replaces a newer one), and can hold processing
 * on a barrier so that the test observes real concurrent workers.
 *
 * <p>This class lives in the test sources only; production has no processor.
 */
public class TestItsmBatchProcessor implements ItsmBatchProcessor {

	private static final Logger log = LoggerFactory.getLogger(TestItsmBatchProcessor.class);

	public static final String TABLE = "itsm_test_publication";

	private final JdbcTemplate jdbcTemplate;
	private final Map<UUID, AtomicInteger> calls = new ConcurrentHashMap<>();
	private final Map<UUID, CountDownLatch> gates = new ConcurrentHashMap<>();
	private final Map<UUID, CountDownLatch> performed = new ConcurrentHashMap<>();
	private final Map<UUID, AtomicInteger> committedCalls = new ConcurrentHashMap<>();
	private final Map<UUID, AtomicBoolean> fail = new ConcurrentHashMap<>();
	private final Map<UUID, AtomicBoolean> failAfterWrite = new ConcurrentHashMap<>();
	private final List<Barrier> barriers = new CopyOnWriteArrayList<>();
	private final AtomicInteger concurrent = new AtomicInteger();
	private final AtomicInteger maxConcurrent = new AtomicInteger();

	public TestItsmBatchProcessor(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	@Override
	public void process(ItsmBatchWork work, PublicationContext publicationContext) {
		UUID batchId = work.batchId();
		calls.computeIfAbsent(batchId, key -> new AtomicInteger()).incrementAndGet();
		int now = concurrent.incrementAndGet();
		maxConcurrent.accumulateAndGet(now, Math::max);
		try {
			// A parity barrier makes the test observe that distinct worker threads execute
			// concurrently; transient waits count as work in progress.
			for (Barrier barrier : barriers) {
				barrier.arrive();
			}
			for (Barrier barrier : barriers) {
				barrier.awaitWorkDone(30, TimeUnit.SECONDS);
			}

			Optional<CountDownLatch> performedLatch = Optional.ofNullable(performed.get(batchId));
			performedLatch.ifPresent(CountDownLatch::countDown);
			Optional<CountDownLatch> gate = Optional.ofNullable(gates.get(batchId));
			gate.ifPresent(latch -> await(latch, "processor gate"));

			if (isTrue(fail.get(batchId))) {
				throw new IllegalStateException("simulated processing failure for " + batchId);
			}

			int visibleRevision = publish(work);
			if (isTrue(failAfterWrite.get(batchId))) {
				// Rolls the publication back together with the claim.
				throw new IllegalStateException("simulated failure after writing the publication");
			}
			registerCommitted(batchId);
			publicationContext.markSucceeded(visibleRevision, "applied by test processor");
			log.info("test processor {} visible revision={}", batchId, visibleRevision);
		} finally {
			concurrent.decrementAndGet();
		}
	}

	private int publish(ItsmBatchWork work) {
		jdbcTemplate.update(
				"""
				INSERT INTO %s (source_id, revision) VALUES (?, ?)
				ON CONFLICT (source_id) DO UPDATE SET revision = EXCLUDED.revision
				WHERE %s.revision < EXCLUDED.revision
				""".formatted(TABLE, TABLE),
				work.sourceId(),
				work.sourceVersion()
		);
		return currentRevision(work.sourceId());
	}

	private void registerCommitted(UUID batchId) {
		AtomicInteger committed = committedCalls.computeIfAbsent(batchId, key -> new AtomicInteger());
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override
				public void afterCommit() {
					committed.incrementAndGet();
				}
			});
		} else {
			committed.incrementAndGet();
		}
	}

	/** Holds processing of the batch until {@link #open(UUID)}. */
	public void hold(UUID batchId) {
		gates.put(batchId, new CountDownLatch(1));
		performed.put(batchId, new CountDownLatch(1));
	}

	public void open(UUID batchId) {
		CountDownLatch gate = gates.get(batchId);
		if (gate != null) {
			gate.countDown();
		}
	}

	public void fail(UUID batchId) {
		fail.put(batchId, new AtomicBoolean(true));
	}

	public void failAfterWrite(UUID batchId) {
		failAfterWrite.put(batchId, new AtomicBoolean(true));
	}

	/**
	 * Waits until this batch performed its work (barriers released) and is now held on its gate.
	 */
	public boolean awaitHeld(UUID batchId, long timeout, TimeUnit unit) {
		CountDownLatch latch = performed.get(batchId);
		if (latch == null) {
			return false;
		}
		try {
			return latch.await(timeout, unit);
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			return false;
		}
	}

	public int calls(UUID batchId) {
		AtomicInteger count = calls.get(batchId);
		return count == null ? 0 : count.get();
	}

	public int committedCalls(UUID batchId) {
		AtomicInteger count = committedCalls.get(batchId);
		return count == null ? 0 : count.get();
	}

	public int currentRevision(String sourceId) {
		List<Integer> revisions = jdbcTemplate.query(
				"SELECT revision FROM " + TABLE + " WHERE source_id = ?",
				(resultSet, rowNum) -> resultSet.getInt("revision"),
				sourceId
		);
		return revisions.isEmpty() ? 0 : revisions.get(0);
	}

	public int maxConcurrent() {
		return maxConcurrent.get();
	}

	/** Forgets flags and records but keeps the publication table of the current database. */
	public void resetRecords() {
		calls.clear();
		gates.clear();
		performed.clear();
		committedCalls.clear();
		fail.clear();
		failAfterWrite.clear();
		barriers.clear();
		maxConcurrent.set(0);
	}

	public Barrier barrier(int parties) {
		Barrier barrier = new Barrier(parties);
		barriers.add(barrier);
		return barrier;
	}

	public void clear() {
		calls.clear();
		gates.clear();
		performed.clear();
		committedCalls.clear();
		fail.clear();
		failAfterWrite.clear();
		barriers.clear();
		maxConcurrent.set(0);
		jdbcTemplate.update("TRUNCATE TABLE " + TABLE);
	}

	private static boolean isTrue(AtomicBoolean flag) {
		return flag != null && flag.get();
	}

	private static void await(CountDownLatch latch, String name) {
		try {
			if (!latch.await(30, TimeUnit.SECONDS)) {
				throw new IllegalStateException(name + " was not released");
			}
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(exception);
		}
	}

	/**
	 * Two-phase barrier: every participant must reach it, then all participants wait for the
	 * test's release, so the test can assert they were inside the processor concurrently.
	 */
	public static final class Barrier {

		private final CountDownLatch arrived;
		private final CountDownLatch release = new CountDownLatch(1);
		private final List<Long> threads = new CopyOnWriteArrayList<>();

		Barrier(int parties) {
			this.arrived = new CountDownLatch(parties);
		}

		void arrive() {
			long threadId = Thread.currentThread().threadId();
			synchronized (threads) {
				if (!threads.contains(threadId)) {
					threads.add(threadId);
				}
			}
			arrived.countDown();
		}

		void awaitWorkDone(long timeout, TimeUnit unit) {
			try {
				if (!arrived.await(timeout, unit)) {
					return;
				}
				release.await(timeout, unit);
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException(exception);
			}
		}

		public boolean awaitAll(long timeout, TimeUnit unit) {
			try {
				return arrived.await(timeout, unit);
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				return false;
			}
		}

		public void release() {
			release.countDown();
		}

		public int distinctThreads() {
			synchronized (threads) {
				return threads.size();
			}
		}
	}
}
