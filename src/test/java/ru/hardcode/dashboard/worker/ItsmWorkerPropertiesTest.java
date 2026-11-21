package ru.hardcode.dashboard.worker;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class ItsmWorkerPropertiesTest {

	@Test
	void validConfiguration_isAccepted() {
		ItsmWorkerProperties properties = ItsmWorkerProperties.fromConfiguration(2, 5, Duration.ZERO, Duration.ofSeconds(1), Duration.ofSeconds(30), Duration.ofMinutes(1), Duration.ofMinutes(10));

		assertThat(properties.workers()).isEqualTo(2);
		assertThat(properties.retryBaseDelay()).isZero();
	}

	@ParameterizedTest
	@ValueSource(ints = {0, -1, 257})
	void workerCountOutsideBounds_isRejected(int workers) {
		assertThatIllegalArgumentException().isThrownBy(() -> ItsmWorkerProperties.fromConfiguration(workers, 5, Duration.ZERO, Duration.ofSeconds(1), Duration.ofSeconds(30), Duration.ofMinutes(1), Duration.ofMinutes(10)))
				.withMessageContaining("itsm.worker.workers");
	}

	@Test
	void negativeRetryDelay_isRejected() {
		assertThatIllegalArgumentException().isThrownBy(() -> ItsmWorkerProperties.fromConfiguration(1, 1, Duration.ofSeconds(-1), Duration.ofSeconds(1), Duration.ZERO, Duration.ofMinutes(1), Duration.ofMinutes(1)));
	}

	@Test
	void zeroPollInterval_isRejected() {
		assertThatIllegalArgumentException()
				.isThrownBy(() -> ItsmWorkerProperties.fromConfiguration(1, 1, Duration.ZERO, Duration.ZERO, Duration.ZERO, Duration.ofMinutes(1), Duration.ofMinutes(1)))
				.withMessageContaining("poll-interval");
	}

	@Test
	void negativePollInterval_isRejected() {
		assertThatIllegalArgumentException()
				.isThrownBy(() -> ItsmWorkerProperties.fromConfiguration(1, 1, Duration.ZERO, Duration.ofSeconds(-1), Duration.ZERO, Duration.ofMinutes(1), Duration.ofMinutes(1)))
				.withMessageContaining("poll-interval");
	}

	@Test
	void zeroRecoveryHeartbeatTimeout_isRejected() {
		assertThatIllegalArgumentException().isThrownBy(() -> ItsmWorkerProperties.fromConfiguration(1, 1, Duration.ZERO, Duration.ofSeconds(1), Duration.ZERO, Duration.ofMinutes(1), Duration.ZERO));
	}
}
