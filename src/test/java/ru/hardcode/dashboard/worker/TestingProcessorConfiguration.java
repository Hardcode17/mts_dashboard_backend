package ru.hardcode.dashboard.worker;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import ru.hardcode.dashboard.client.TestItsmBatchProcessor;

/**
 * Provides the test-only KAN-55 stand-in as the single processor bean. The worker itself is
 * started by the application's auto-configuration because this processor bean exists.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestingProcessorConfiguration {

	@Bean
	public TestItsmBatchProcessor testItsmBatchProcessor(JdbcTemplate jdbcTemplate) {
		return new TestItsmBatchProcessor(jdbcTemplate);
	}
}
