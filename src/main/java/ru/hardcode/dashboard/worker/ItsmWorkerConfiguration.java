package ru.hardcode.dashboard.worker;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Application-side KAN-54 worker factories.
 *
 * <p>This configuration is imported by {@link ItsmWorkerAutoConfiguration}, which only runs when
 * {@code itsm.worker.enabled} is not {@code false} and a real {@link ItsmBatchProcessor} bean
 * exists (delivered by KAN-55). There is no fallback processor: without one nothing here is
 * created, and accepted batches stay {@code RECEIVED} while HTTP intake keeps working.
 *
 * <p>A worker is an executable infrastructure process: run it where the processor and its data
 * access are available, not in every web replica.
 */
public class ItsmWorkerConfiguration {

	private static final Logger log = LoggerFactory.getLogger(ItsmWorkerConfiguration.class);

	/**
	 * Records which processor drives the worker. Runs only when the auto-configuration matched a
	 * processor; the type itself is resolved with parameter matching so every registration style
	 * (including {@code @Bean} factory methods) is seen.
	 */
	@Bean
	public ItsmWorkerProcessorMarker itsmWorkerProcessorMarker(ConfigurableListableBeanFactory beanFactory) {
		String[] names = beanFactory.getBeanNamesForType(
				org.springframework.core.ResolvableType.forClass(ItsmBatchProcessor.class),
				true,
				false
		);
		if (names.length != 1) {
			throw new IllegalStateException("Exactly one ItsmBatchProcessor bean is required, found " + List.of(names));
		}
		log.info("ITSM worker enabled for processor {}", names[0]);
		return new ItsmWorkerProcessorMarker(names[0]);
	}

	@Bean
	public ItsmWorkerProperties itsmWorkerProperties(Environment environment) {
		return ItsmWorkerProperties.fromConfiguration(
				environment.getProperty("itsm.worker.workers", Integer.class, 2),
				environment.getProperty("itsm.worker.max-attempts", Integer.class, 5),
				environment.getProperty("itsm.worker.retry-base-delay", Duration.class, Duration.ofSeconds(30)),
				environment.getProperty("itsm.worker.poll-interval", Duration.class, Duration.ofSeconds(1)),
				environment.getProperty("itsm.worker.drain-timeout", Duration.class, Duration.ofSeconds(30)),
				environment.getProperty("itsm.worker.recovery-interval", Duration.class, Duration.ofSeconds(30)),
				environment.getProperty("itsm.worker.recovery-heartbeat-timeout", Duration.class, Duration.ofMinutes(10))
		);
	}

	@Bean
	public ItsmWorkerSqlRepository itsmWorkerSqlRepository(org.springframework.jdbc.core.JdbcTemplate jdbcTemplate) {
		return new ItsmWorkerSqlRepository(jdbcTemplate);
	}

	@Bean
	public ItsmWorkerTransactionService itsmWorkerTransactionService(
			ItsmWorkerSqlRepository sqlRepository,
			PlatformTransactionManager transactionManager
	) {
		return new ItsmWorkerTransactionService(sqlRepository, transactionManager);
	}

	/**
	 * The runner is the {@link ItsmWorkerLink}: intake uses it to notify the dispatcher after a
	 * new batch is committed. The link is optional for intake, which works without the worker.
	 */
	@Bean
	public ItsmWorkerRunner itsmWorkerRunner(ItsmWorkerService workerService, ItsmWorkerProperties properties) {
		return new ItsmWorkerRunner(workerService, properties);
	}

	@Bean
	public ItsmWorkerService itsmWorkerService(
			ItsmWorkerTransactionService transactionService,
			ItsmWorkerSqlRepository workerSqlRepository,
			ItsmBatchProcessor processor,
			ItsmWorkerProperties properties
	) {
		return new ItsmWorkerService(
				transactionService,
				workerSqlRepository,
				processor,
				ItsmWorkerId.create().value(),
				properties,
				Instant::now
		);
	}

	/** Name of the processor bean the worker runs. */
	public record ItsmWorkerProcessorMarker(String beanName) {
	}
}
