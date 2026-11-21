package ru.hardcode.dashboard;

import java.io.IOException;
import javax.sql.DataSource;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import ru.hardcode.dashboard.client.ItsmMachineJwtTestConfiguration;
import ru.hardcode.dashboard.service.ItsmBaselineService;
import ru.hardcode.dashboard.worker.ItsmWorkerSqlRepository;
import ru.hardcode.dashboard.worker.ItsmWorkerTransactionService;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
		"itsm.integration.auth.issuer=" + ItsmMachineJwtTestConfiguration.ISSUER,
		"itsm.integration.auth.jwk-set-uri=https://keycloak.test/realms/itsm/protocol/openid-connect/certs",
		"itsm.integration.auth.audience=" + ItsmMachineJwtTestConfiguration.AUDIENCE,
		"itsm.integration.auth.client-id=" + ItsmMachineJwtTestConfiguration.CLIENT_ID,
		"itsm.integration.auth.import-role=" + ItsmMachineJwtTestConfiguration.IMPORT_ROLE,
		"spring.security.user.name=operator",
		"spring.security.user.password=kan17-test-password"
})
@AutoConfigureMockMvc
@ActiveProfiles("work")
@Import({ItsmPostgresTestcontainersConfiguration.class, ItsmMachineJwtTestConfiguration.class})
class ItsmBaselineImportIT {

	@Autowired MockMvc mvc;
	@Autowired JdbcTemplate jdbc;
	@Autowired JwtEncoder encoder;
	@Autowired PlatformTransactionManager transactions;
	@Autowired ItsmBaselineService baseline;
	@Autowired DataSource dataSource;
	@Autowired ObjectMapper mapper;

	@BeforeEach
	void reset() {
		jdbc.execute("TRUNCATE itsm_snapshot, itsm_ingest_catalog_hint, itsm_ingest_batch, itsm_catalog_product, itsm_external_person, itsm_external_group CASCADE");
	}

	@Test
	void installsRealFixtureThroughInboundBatchAndRetainsBaselineOnReplay() throws Exception {
		byte[] body = original();
		UUID first = accept(1, body);
		assertThat(publish().batchId()).isEqualTo(first);
		var data = baseline.readBaseline();
		assertThat(data.snapshot().batchId()).isEqualTo(first);
		assertThat(data.incidents()).hasSize(44);
		assertThat(data.assignments()).hasSize(24);
		assertThat(data.scenarios()).allSatisfy(scenario ->
				assertThat(data.services()).anyMatch(service -> service.id() == scenario.serviceId()));
		assertThat(jdbc.queryForObject("SELECT count(DISTINCT product_id) FROM itsm_catalog_service WHERE external_service_id IS NOT NULL", Long.class))
				.isGreaterThan(1L);

		UUID replay = accept(2, body);
		assertThat(publish().batchId()).isEqualTo(replay);
		assertThat(baseline.readBaseline()).isEqualTo(data);
		try (var connection = dataSource.getConnection();
			 var reader = new SpringApplicationBuilder(DashboardApplication.class)
					 .profiles("work").run("--server.port=0",
							 "--spring.datasource.url=" + connection.getMetaData().getURL(),
							 "--spring.datasource.username=" + connection.getMetaData().getUserName(),
							 "--spring.datasource.password=" + databasePassword())) {
			assertThat(reader.getBean(ItsmBaselineService.class).readBaseline()).isEqualTo(data);
		}
		mvc.perform(get("/api/itsm/incidents").with(httpBasic("operator", "kan17-test-password")))
				.andExpect(status().isOk()).andExpect(jsonPath("$.count").value(44));
	}

	@Test
	void rollsBackAllDomainRowsWhenLastIncidentCannotBeWritten() throws Exception {
		byte[] body = original();
		var tree = mapper.readTree(body);
		String lastId = tree.get(43).get("id").asString();
		jdbc.execute("ALTER TABLE itsm_incident ADD CONSTRAINT kan17_test_fail CHECK (id <> '" + lastId + "')");
		try {
			accept(1, body);
			assertThatThrownBy(this::publish).hasRootCauseInstanceOf(org.postgresql.util.PSQLException.class);
			assertThat(baseline.readBaseline().incidents()).isEmpty();
			assertThat(baseline.findSnapshot()).isEmpty();
			assertThat(jdbc.queryForObject("SELECT count(*) FROM itsm_catalog_product", Long.class)).isZero();
		} finally {
			jdbc.execute("ALTER TABLE itsm_incident DROP CONSTRAINT kan17_test_fail");
		}
	}

	@Test
	void identicalExternalServiceIdentifiersRemainSeparateUnderTwoProducts() throws Exception {
		ObjectNode alpha = (ObjectNode) mapper.readTree(original()).get(0).deepCopy();
		alpha.put("id", "INC100000000001");
		alpha.put("productName", "Product Alpha");
		alpha.put("service", "[Product Alpha] Shared service");
		alpha.put("serviceId", "shared-id");
		ObjectNode beta = alpha.deepCopy();
		beta.put("id", "INC100000000002");
		beta.put("productName", "Product Beta");
		beta.put("service", "[Product Beta] Shared service");

		accept(1, mapper.writeValueAsBytes(mapper.createArrayNode().add(alpha).add(beta)));
		publish();

		assertThat(baseline.readBaseline().services()).hasSize(2);
		assertThat(jdbc.queryForObject("""
				SELECT count(DISTINCT product_id) FROM itsm_catalog_service
				WHERE external_service_id = 'shared-id'
				""", Long.class)).isEqualTo(2L);
	}

	private UUID accept(long version, byte[] body) throws Exception {
		UUID id = UUID.randomUUID();
		mvc.perform(post("/api/integrations/itsm/batches")
				.header("Authorization", "Bearer " + ItsmMachineJwtTestConfiguration.machineToken(encoder))
				.header("Idempotency-Key", id.toString())
				.header("X-Source-Id", "kan17-fixture")
				.header("X-Source-Version", Long.toString(version))
				.contentType(MediaType.APPLICATION_JSON)
				.content(body)).andExpect(status().isAccepted());
		return id;
	}

	private ItsmWorkerSqlRepository.ClaimedBatch publish() {
		var worker = new ItsmWorkerTransactionService(new ItsmWorkerSqlRepository(jdbc), transactions);
		return worker.claimAndProcess((batch, context) -> {
			var result = baseline.publish(batch.batchId(), batch.payload(), batch.sourceId(), batch.sourceVersion());
			context.markSucceeded(result.revision(), null);
		}, "kan17-test");
	}

	private String databasePassword() {
		return ((com.zaxxer.hikari.HikariDataSource) dataSource).getPassword();
	}

	private byte[] original() throws IOException {
		try (var input = getClass().getResourceAsStream("/itsm/character_problems.json")) {
			return input.readAllBytes();
		}
	}
}
