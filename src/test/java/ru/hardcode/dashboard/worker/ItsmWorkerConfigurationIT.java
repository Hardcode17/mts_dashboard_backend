package ru.hardcode.dashboard.worker;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import ru.hardcode.dashboard.ItsmPostgresTestcontainersConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Conditional worker start-up on a real PostgreSQL 17 container. With a processor the worker is
 * wired and started; without one no worker bean exists and intake still stores batches. The
 * processor is a test-only stand-in for KAN-55 and is registered by the test context only.
 */
class ItsmWorkerConfigurationIT {

	@SpringBootTest(properties = {
			"itsm.publication.enabled=false",
			"itsm.worker.poll-interval=PT1H",
			"spring.flyway.locations=classpath:db/migration,classpath:db/demo,classpath:db/test"
	})
	@AutoConfigureMockMvc
	@ActiveProfiles("demo")
	@Import({ItsmPostgresTestcontainersConfiguration.class, TestingProcessorConfiguration.class})
	@Nested
	class WithProcessor {

		@Autowired
		private ConfigurableApplicationContext context;

		@Autowired
		private JdbcTemplate jdbcTemplate;

		@Test
		void workerBeansArePresentAndRunningWithTheProcessor() {
			assertThat(beanNames(ItsmBatchProcessor.class)).containsExactly("testItsmBatchProcessor");
			assertThat(beanNames(ItsmWorkerRunner.class)).containsExactly("itsmWorkerRunner");
			assertThat(beanNames(ItsmWorkerLink.class)).containsExactly("itsmWorkerRunner");
			assertThat(context.getBean(ItsmWorkerConfiguration.ItsmWorkerProcessorMarker.class).beanName())
					.isEqualTo("testItsmBatchProcessor");
			assertThat(context.getBean(ItsmWorkerRunner.class).isRunning()).isTrue();
			assertThat(context.getBean(ItsmWorkerProperties.class).workers()).isEqualTo(2);
		}

		@Test
		void migrationAddsClaimColumnsWithSafeDefaults() {
			jdbcTemplate.update("TRUNCATE TABLE itsm_ingest_batch");
			UUID batchId = UUID.randomUUID();
			Instant now = Instant.now();
			jdbcTemplate.update(
					"""
					INSERT INTO itsm_ingest_batch
					    (id, source_id, source_version, payload, payload_sha256, payload_size,
					     received_at, status, status_updated_at)
					VALUES (?, 'configured-source', 1, ?, ?, 2, ?, 'RECEIVED', ?)
					""",
					batchId,
					"[]".getBytes(StandardCharsets.UTF_8),
					"0".repeat(64),
					Timestamp.from(now),
					Timestamp.from(now)
			);

			Map<String, Object> row = jdbcTemplate.queryForMap(
					"SELECT batch_attempts, claimed_by, heartbeat_at, row_version, next_attempt_at "
							+ "FROM itsm_ingest_batch WHERE id = ?",
					batchId
			);
			assertThat(((Number) row.get("batch_attempts")).intValue()).isZero();
			assertThat(((Number) row.get("row_version")).longValue()).isZero();
			assertThat(row.get("claimed_by")).isNull();
			assertThat(row.get("heartbeat_at")).isNull();
			assertThat(row.get("next_attempt_at")).isNull();
		}

		private List<String> beanNames(Class<?> type) {
			return List.of(context.getBeanFactory().getBeanNamesForType(type));
		}
	}

	@SpringBootTest(properties = {
			"itsm.publication.enabled=false",
			"itsm.worker.poll-interval=PT1H",
			"spring.flyway.locations=classpath:db/migration,classpath:db/demo,classpath:db/test"
	})
	@AutoConfigureMockMvc
	@ActiveProfiles("demo")
	@Import(ItsmPostgresTestcontainersConfiguration.class)
	@Nested
	class WithoutProcessor {

		@Autowired
		private ConfigurableApplicationContext context;

		@Autowired
		private JdbcTemplate jdbcTemplate;

		@Test
		void noProcessor_noWorkerBeansAndAcceptedBatchesStayReceived() throws Exception {
			assertThat(context.getBeanNamesForType(ItsmBatchProcessor.class))
					.as(Arrays.toString(context.getBeanDefinitionNames()))
					.isEmpty();
			assertThat(context.getBeanNamesForType(ItsmWorkerRunner.class)).isEmpty();
			assertThat(context.getBeanNamesForType(ItsmWorkerService.class)).isEmpty();
			assertThat(context.getBeanNamesForType(ItsmWorkerTransactionService.class)).isEmpty();
			assertThat(context.getBeanNamesForType(ItsmWorkerSqlRepository.class)).isEmpty();
			assertThat(context.getBeanNamesForType(ItsmWorkerLink.class)).isEmpty();

			jdbcTemplate.update("TRUNCATE TABLE itsm_ingest_batch");
			UUID batchId = UUID.randomUUID();
			Instant now = Instant.now();
			jdbcTemplate.update(
					"""
					INSERT INTO itsm_ingest_batch
					    (id, source_id, source_version, payload, payload_sha256, payload_size,
					     received_at, status, status_updated_at)
					VALUES (?, 'no-worker-source', 1, ?, ?, 2, ?, 'RECEIVED', ?)
					""",
					batchId,
					"[]".getBytes(StandardCharsets.UTF_8),
					"0".repeat(64),
					Timestamp.from(now),
					Timestamp.from(now)
			);
			Thread.sleep(500);
			assertThat(jdbcTemplate.queryForObject(
					"SELECT status FROM itsm_ingest_batch WHERE id = ?",
					String.class,
					batchId
			)).isEqualTo("RECEIVED");
		}
	}
}
