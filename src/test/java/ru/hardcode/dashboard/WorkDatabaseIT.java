package ru.hardcode.dashboard;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import ru.hardcode.dashboard.configuration.filter.LoggerFilter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
class WorkDatabaseIT {

	private static final String DEMO_DB = "kan19_demo";
	private static final String WORK_DB = "kan19_work";
	private static final String HISTORY = """
			SELECT version, script, checksum, installed_on
			FROM flyway_schema_history
			WHERE success
			ORDER BY installed_rank
			""";

	@Container
	private static final PostgreSQLContainer postgres =
			new ItsmPostgresTestcontainersConfiguration().itsmPostgres17Container();

	@BeforeAll
	static void createDatabases() throws SQLException {
		try (var connection = DriverManager.getConnection(
				postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()
		); var statement = connection.createStatement()) {
			statement.execute("CREATE DATABASE " + DEMO_DB);
			statement.execute("CREATE DATABASE " + WORK_DB);
		}
	}

	@Test
	void cleanWorkDatabaseStartsBesideExistingDemoAndSurvivesRestart() throws Exception {
		// Recreate a deployed demo that has only the original V1 applied.
		Flyway.configure()
				.dataSource(url(DEMO_DB), postgres.getUsername(), postgres.getPassword())
				.locations("classpath:db/demo")
				.load()
				.migrate();

		UUID batch = UUID.randomUUID();
		Map<String, Object> demoV1;
		List<Map<String, Object>> workHistory;
		try (var demo = start("demo", DEMO_DB); var work = start("work", WORK_DB)) {
			assertHealthy(demo);
			assertHealthy(work);

			JdbcTemplate demoJdbc = jdbc(demo);
			JdbcTemplate workJdbc = jdbc(work);
			demoV1 = demoJdbc.queryForMap(HISTORY.replace("WHERE success", "WHERE success AND version = '1'"));
			assertThat(demoV1.get("script")).isEqualTo("V1__deployment_sample.sql");
			assertThat(demoJdbc.queryForObject("SELECT count(*) FROM deployment_sample", Long.class)).isEqualTo(3L);
			assertThat(demoJdbc.queryForList(HISTORY)).hasSizeGreaterThanOrEqualTo(2);

			work.getBean(Flyway.class).validate();
			workHistory = workJdbc.queryForList(HISTORY);
			workJdbc.update("""
					INSERT INTO users(identity_issuer, identity_subject, login, display_name, is_active)
					VALUES ('https://idp.test/realm', 'persisted-subject', 'persisted', 'Persisted User', false)
					""");
			assertThat(workHistory).extracting(row -> row.get("script"))
					.contains("V2__itsm_ingest_batch.sql")
					.doesNotContain("V1__deployment_sample.sql");
			assertThat(workJdbc.queryForObject("SELECT to_regclass('deployment_sample')", String.class)).isNull();

			workJdbc.update("""
					INSERT INTO itsm_ingest_batch (id, source_id, source_version, payload, payload_sha256,
						payload_size, received_at, status, status_updated_at)
					VALUES (?, 'kan19', 1, ?, ?, 2, now(), 'RECEIVED', now())
					""", batch, new byte[]{'[', ']'}, "0".repeat(64));
			assertThat(demoJdbc.queryForObject("SELECT count(*) FROM itsm_ingest_batch", Long.class)).isZero();
		}

		try (var demo = start("demo", DEMO_DB); var work = start("work", WORK_DB)) {
			assertHealthy(demo);
			assertHealthy(work);
			assertThat(jdbc(demo).queryForObject("SELECT count(*) FROM deployment_sample", Long.class)).isEqualTo(3L);
			assertThat(jdbc(demo).queryForMap(HISTORY.replace("WHERE success", "WHERE success AND version = '1'")))
					.isEqualTo(demoV1);
			assertThat(jdbc(work).queryForList(HISTORY)).isEqualTo(workHistory);
			assertThat(jdbc(work).queryForObject("SELECT is_active FROM users WHERE identity_subject = 'persisted-subject'", Boolean.class))
					.isFalse();
			assertThat(jdbc(work).queryForObject("SELECT count(*) FROM itsm_ingest_batch WHERE id = ?", Long.class, batch))
					.isEqualTo(1L);
			assertThat(jdbc(work).update("UPDATE flyway_schema_history SET checksum = checksum + 1 WHERE version = '2'"))
					.isEqualTo(1);
		}

		assertThatThrownBy(() -> {
			try (var ignored = start("work", WORK_DB)) {
				throw new AssertionError("The work profile accepted a mismatched migration checksum");
			}
		}).hasStackTraceContaining("Migration checksum mismatch");

		try (var demo = start("demo", DEMO_DB)) {
			assertHealthy(demo);
			assertThat(jdbc(demo).queryForObject("SELECT count(*) FROM deployment_sample", Long.class)).isEqualTo(3L);
			assertThat(jdbc(demo).queryForMap(HISTORY.replace("WHERE success", "WHERE success AND version = '1'")))
					.isEqualTo(demoV1);
		}
	}

	private static ConfigurableApplicationContext start(String profile, String database) {
		return new SpringApplicationBuilder(DashboardApplication.class)
				.profiles(profile)
				.run(
						"--server.port=0",
						"--spring.datasource.url=" + url(database),
						"--spring.datasource.username=" + postgres.getUsername(),
						"--spring.datasource.password=" + postgres.getPassword()
				);
	}

	private static String url(String database) {
		return "jdbc:postgresql://" + postgres.getHost() + ":" + postgres.getMappedPort(5432) + "/" + database;
	}

	private static JdbcTemplate jdbc(ConfigurableApplicationContext context) {
		return context.getBean(JdbcTemplate.class);
	}

	private static void assertHealthy(ConfigurableApplicationContext context) throws Exception {
		MockMvcBuilders.webAppContextSetup((WebApplicationContext) context)
				.addFilters(context.getBean(LoggerFilter.class))
				.apply(springSecurity())
				.build()
				.perform(get("/health"))
				.andExpect(status().isOk())
				.andExpect(header().exists("X-Request-Id"))
				.andExpect(jsonPath("$.status").value("UP"))
				.andExpect(jsonPath("$.service").value("dashboard"));
	}
}
