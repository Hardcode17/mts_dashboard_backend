package ru.hardcode.dashboard;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Focused PostgreSQL 17 dependency for the ITSM intake integration tests. AI/vector
 * containers from the shared configuration are deliberately not started here.
 */
@TestConfiguration(proxyBeanMethods = false)
public class ItsmPostgresTestcontainersConfiguration {

	@Bean
	@ServiceConnection
	PostgreSQLContainer itsmPostgres17Container() {
		return new PostgreSQLContainer(DockerImageName.parse("postgres:17-bookworm"));
	}
}
