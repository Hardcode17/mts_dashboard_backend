package ru.hardcode.dashboard;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import ru.hardcode.dashboard.service.ItsmBatchIntakeService;
import ru.hardcode.dashboard.service.ItsmSnapshotPublicationService;
import ru.hardcode.dashboard.worker.ItsmWorkerSqlRepository;
import ru.hardcode.dashboard.worker.ItsmWorkerTransactionService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

@SpringBootTest(properties = {
		"itsm.worker.enabled=false",
		"spring.security.user.name=operator",
		"spring.security.user.password=dashboard-test"
})
@AutoConfigureMockMvc
@ActiveProfiles("work")
@Import(ItsmPostgresTestcontainersConfiguration.class)
abstract class DashboardIncidentTestSupport {

	static final String PATH = "/api/dashboard/incidents";
	static final String COORDINATORS = "КЦ ИТ Координаторы Массовых Инцидентов";

	@Autowired MockMvc mvc;
	@Autowired JdbcTemplate jdbc;
	@Autowired PlatformTransactionManager transactions;
	@Autowired ItsmBatchIntakeService intake;
	@Autowired ItsmSnapshotPublicationService publication;

	@BeforeEach
	void clearPublishedData() {
		jdbc.execute("""
				TRUNCATE itsm_ingest_batch, itsm_snapshot, itsm_snapshot_source, itsm_incident,
				    itsm_catalog_product, itsm_catalog_service, itsm_catalog_scenario,
				    itsm_external_group, itsm_external_person, itsm_ingest_catalog_hint CASCADE
				""");
	}

	MockHttpServletRequestBuilder listRequest() {
		return authenticatedGet(PATH);
	}

	MockHttpServletRequestBuilder authenticatedGet(String path, Object... variables) {
		return get(path, variables).accept(MediaType.APPLICATION_JSON).with(httpBasic("operator", "dashboard-test"));
	}

	UUID receive(long sourceVersion, String json) {
		return intake.receive(UUID.randomUUID().toString(), "dashboard-fixture", Long.toString(sourceVersion),
				json.getBytes(StandardCharsets.UTF_8));
	}

	void publish(UUID batchId) {
		var worker = new ItsmWorkerTransactionService(new ItsmWorkerSqlRepository(jdbc), transactions);
		assertThat(worker.claimAndProcess(publication, "dashboard-test").batchId()).isEqualTo(batchId);
		assertThat(jdbc.queryForObject("SELECT status FROM itsm_ingest_batch WHERE id = ?", String.class, batchId))
				.isEqualTo("SUCCEEDED");
	}

	String baseline() throws IOException {
		try (var fixture = getClass().getResourceAsStream("/itsm/character_problems.json")) {
			assertThat(fixture).isNotNull();
			return new String(fixture.readAllBytes(), StandardCharsets.UTF_8);
		}
	}
}
