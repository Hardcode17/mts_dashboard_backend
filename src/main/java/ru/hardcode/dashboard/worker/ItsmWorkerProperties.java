package ru.hardcode.dashboard.worker;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;

/**
 * KAN-54 worker tuning ({@code itsm.worker.*}). Constructor validation fails fast on invalid
 * values instead of silently starting an unbounded pool.
 *
 * @param workers             worker threads; at most this many batches are claimed concurrently
 * @param maxAttempts         total attempts per batch before it stays {@code FAILED}
 * @param retryBaseDelay      base of the exponential automatic retry delay
 * @param pollInterval        rolling poll interval for batches without a commit notification
 * @param drainTimeout        time shutdown waits for in-flight batches to commit
 * @param recoveryInterval    period of the stale-claim recovery sweep
 * @param recoveryHeartbeatTimeout age after which an unfinished claim is considered abandoned
 */
public record ItsmWorkerProperties(
		int workers,
		int maxAttempts,
		Duration retryBaseDelay,
		Duration pollInterval,
		Duration drainTimeout,
		Duration recoveryInterval,
		Duration recoveryHeartbeatTimeout
) {

	public ItsmWorkerProperties {
		if (workers < 1 || workers > 256) {
			throw new IllegalArgumentException("itsm.worker.workers must be between 1 and 256");
		}
		if (maxAttempts < 1 || maxAttempts > 100) {
			throw new IllegalArgumentException("itsm.worker.max-attempts must be between 1 and 100");
		}
		if (retryBaseDelay.isNegative()) {
			throw new IllegalArgumentException("itsm.worker.retry-base-delay must not be negative");
		}
		if (pollInterval.compareTo(Duration.ofMillis(1)) < 0) {
			throw new IllegalArgumentException("itsm.worker.poll-interval must be at least 1 ms");
		}
		if (drainTimeout.isNegative()) {
			throw new IllegalArgumentException("itsm.worker.drain-timeout must not be negative");
		}
		if (recoveryInterval.compareTo(Duration.ofMillis(1)) < 0) {
			throw new IllegalArgumentException("itsm.worker.recovery-interval must be at least 1 ms");
		}
		if (recoveryHeartbeatTimeout.isNegative() || recoveryHeartbeatTimeout.isZero()) {
			throw new IllegalArgumentException("itsm.worker.recovery-heartbeat-timeout must be positive");
		}
	}

	public static ItsmWorkerProperties fromConfiguration(
			@Value("${itsm.worker.workers:2}") int workers,
			@Value("${itsm.worker.max-attempts:5}") int maxAttempts,
			@Value("${itsm.worker.retry-base-delay:PT30S}") Duration retryBaseDelay,
			@Value("${itsm.worker.poll-interval:PT1S}") Duration pollInterval,
			@Value("${itsm.worker.drain-timeout:PT30S}") Duration drainTimeout,
			@Value("${itsm.worker.recovery-interval:PT1M}") Duration recoveryInterval,
			@Value("${itsm.worker.recovery-heartbeat-timeout:PT10M}") Duration recoveryHeartbeatTimeout
	) {
		return new ItsmWorkerProperties(
				workers,
				maxAttempts,
				retryBaseDelay,
				pollInterval,
				drainTimeout,
				recoveryInterval,
				recoveryHeartbeatTimeout
		);
	}
}
