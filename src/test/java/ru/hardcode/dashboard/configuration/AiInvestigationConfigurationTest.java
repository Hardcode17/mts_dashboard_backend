package ru.hardcode.dashboard.configuration;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import ru.hardcode.dashboard.service.investigation.AiInvestigationProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiInvestigationConfigurationTest {

	@Test
	void refusesLaunchWhenWorkerAndQueueAreFull() throws Exception {
		var properties = new AiInvestigationProperties("", Duration.ofSeconds(5), 1, 1);
		var working = new CountDownLatch(1);
		var release = new CountDownLatch(1);

		try (var executor = new AiInvestigationConfiguration().aiInvestigationExecutor(properties)) {
			var first = executor.submit(() -> {
				working.countDown();
				release.await();
				return true;
			});
			try {
				assertThat(working.await(5, TimeUnit.SECONDS)).isTrue();
				executor.submit(() -> {});
				assertThatThrownBy(() -> executor.submit(() -> {})).isInstanceOf(RejectedExecutionException.class);
			} finally {
				release.countDown();
			}
			assertThat(first.get(5, TimeUnit.SECONDS)).isTrue();
		}
	}
}
