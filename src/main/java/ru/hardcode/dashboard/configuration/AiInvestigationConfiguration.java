package ru.hardcode.dashboard.configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.hardcode.dashboard.service.investigation.AiInvestigationProperties;

/**
 * Executors of the AI investigation feature (KAN-40). Model-answer workers and model calls
 * run on separate pools: a worker waiting for a slow model never occupies the only thread a
 * new model call needs. Both pools are bounded; an overflow fails its run explicitly
 * instead of blocking a request. The real model client is enabled by the {@code ai} Spring
 * profile, never here.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AiInvestigationProperties.class)
public class AiInvestigationConfiguration {

	@Bean(name = "aiInvestigationExecutor")
	ExecutorService aiInvestigationExecutor(AiInvestigationProperties properties) {
		return fixedPool("ai-investigation-", properties.workers(), properties.queueCapacity());
	}

	@Bean(name = "aiModelCallExecutor")
	ExecutorService aiModelCallExecutor(AiInvestigationProperties properties) {
		return fixedPool("ai-model-call-", Math.max(4, properties.workers() * 2), properties.queueCapacity());
	}

	private static ExecutorService fixedPool(String prefix, int workers, int queueCapacity) {
		AtomicInteger index = new AtomicInteger();
		return new ThreadPoolExecutor(workers, workers, 0L, TimeUnit.MILLISECONDS,
				new ArrayBlockingQueue<>(queueCapacity), runnable -> {
					Thread thread = new Thread(runnable, prefix + index.incrementAndGet());
					thread.setDaemon(true);
					return thread;
				});
	}
}
