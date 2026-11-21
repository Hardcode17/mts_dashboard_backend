package ru.hardcode.dashboard.worker;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * Owns the KAN-54 threads: a dedicated {@link ExecutorService} with a configurable number of
 * workers and a bounded queue, a dispatcher that feeds it from the database, and the rolling
 * poll that picks up batches committed without a notification.
 *
 * <p>Load control: the queue capacity equals the number of workers, so at most {@code 2 *
 * workers} tasks wait or run, each holding at most one claim. A permit is taken before a task is
 * submitted and returned when it ends. When the capacity is exhausted a notification is dropped
 * on purpose: the work stays in PostgreSQL and the next poll picks it up, so no accepted batch
 * is lost and the queue never grows without bound.
 *
 * <p>Shutdown: the dispatcher stops, queued tasks still run, in-flight tasks may commit within
 * the drain timeout, then the threads are shut down. Work interrupted before commit was never
 * published and is recovered as {@code RECEIVED}. The runner can be started again afterwards
 * (with the same database) and resumes from the durable queue.
 */
public class ItsmWorkerRunner implements SmartLifecycle, ItsmWorkerLink {

	private static final Logger log = LoggerFactory.getLogger(ItsmWorkerRunner.class);

	private final ItsmWorkerService workerService;
	private final ItsmWorkerProperties properties;
	private final AtomicBoolean running = new AtomicBoolean();
	private final AtomicBoolean dispatchScheduled = new AtomicBoolean();

	private volatile Semaphore capacity;
	private volatile ThreadPoolExecutor workers;
	private volatile ScheduledExecutorService dispatcher;
	private volatile ScheduledExecutorService recovery;
	private volatile ScheduledExecutorService poller;

	public ItsmWorkerRunner(ItsmWorkerService workerService, ItsmWorkerProperties properties) {
		this.workerService = workerService;
		this.properties = properties;
	}

	@Override
	public synchronized void start() {
		if (!running.compareAndSet(false, true)) {
			return;
		}
		capacity = new Semaphore(properties.workers() * 2);
		workers = new ThreadPoolExecutor(
				properties.workers(),
				properties.workers(),
				0L,
				TimeUnit.MILLISECONDS,
				new ArrayBlockingQueue<>(properties.workers()),
				threadFactory("itsm-worker-"),
				new ThreadPoolExecutor.AbortPolicy()
		);
		dispatcher = Executors.newSingleThreadScheduledExecutor(threadFactory("itsm-worker-dispatch-"));
		recovery = Executors.newSingleThreadScheduledExecutor(threadFactory("itsm-worker-recovery-"));
		poller = Executors.newSingleThreadScheduledExecutor(threadFactory("itsm-worker-poll-"));

		int recovered = workerService.recoverAtStartup();
		long pollIntervalMillis = properties.pollInterval().toMillis();
		log.info(
				"ITSM worker started: {} workers, bounded queue {}, rolling poll {}, start-up recovery reclaimed {} batches",
				properties.workers(),
				properties.workers(),
				pollIntervalMillis + " ms",
				recovered
		);
		dispatcher.execute(this::dispatchSafely);
		poller.scheduleWithFixedDelay(this::wake, pollIntervalMillis, pollIntervalMillis, TimeUnit.MILLISECONDS);
		recovery.scheduleWithFixedDelay(
				this::recoverSafely,
				properties.recoveryInterval().toMillis(),
				properties.recoveryInterval().toMillis(),
				TimeUnit.MILLISECONDS
		);
	}

	@Override
	public synchronized void stop() {
		if (!running.compareAndSet(true, false)) {
			return;
		}
		poller.shutdownNow();
		dispatcher.shutdownNow();
		recovery.shutdownNow();
		workers.shutdown();
		try {
			if (!workers.awaitTermination(properties.drainTimeout().toMillis(), TimeUnit.MILLISECONDS)) {
				log.warn("ITSM worker drain timeout reached; unfinished claims will be recovered as RECEIVED");
				workers.shutdownNow();
				workers.awaitTermination(5, TimeUnit.SECONDS);
			}
		} catch (InterruptedException exception) {
			workers.shutdownNow();
			Thread.currentThread().interrupt();
		}
		dispatchScheduled.set(false);
		log.info("ITSM worker stopped");
	}

	@Override
	public boolean isRunning() {
		return running.get();
	}

	@Override
	public boolean isAutoStartup() {
		return true;
	}

	@Override
	public int getPhase() {
		return 0;
	}

	/**
	 * Asks the dispatcher to look for work after new load appears (intake after commit, a rolling
	 * poll, or recovery). Coalesced: at most one dispatch is queued.
	 */
	@Override
	public void wake() {
		if (!running.get() || !dispatchScheduled.compareAndSet(false, true)) {
			return;
		}
		ScheduledExecutorService current = dispatcher;
		if (current == null) {
			dispatchScheduled.set(false);
			return;
		}
		try {
			current.execute(() -> {
				dispatchScheduled.set(false);
				dispatchSafely();
			});
		} catch (RejectedExecutionException exception) {
			dispatchScheduled.set(false);
		}
	}

	void dispatchSafely() {
		ThreadPoolExecutor executor = workers;
		Semaphore permits = capacity;
		if (!running.get() || executor == null || permits == null) {
			return;
		}
		while (running.get() && permits.tryAcquire()) {
			try {
				executor.execute(() -> runTask(permits));
			} catch (RejectedExecutionException exception) {
				permits.release();
				return;
			}
		}
	}

	private void runTask(Semaphore permits) {
		try {
			workerService.executeTask();
		} catch (RuntimeException exception) {
			log.error("ITSM worker task failed: {}", exception.getClass().getSimpleName());
		} finally {
			permits.release();
		}
	}

	private void recoverSafely() {
		if (!running.get()) {
			return;
		}
		try {
			if (workerService.recoverStaleClaims() > 0) {
				wake();
			}
		} catch (RuntimeException exception) {
			log.warn("ITSM worker recovery sweep failed: {}", exception.getClass().getSimpleName());
		}
	}

	int inFlight() {
		Semaphore permits = capacity;
		return permits == null ? 0 : properties.workers() * 2 - permits.availablePermits();
	}

	ItsmWorkerService workerService() {
		return workerService;
	}

	private static ThreadFactory threadFactory(String prefix) {
		AtomicInteger counter = new AtomicInteger();
		return runnable -> {
			Thread thread = new Thread(runnable, prefix + counter.incrementAndGet());
			thread.setDaemon(true);
			return thread;
		};
	}
}
