package ru.hardcode.dashboard;

import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import ru.hardcode.dashboard.client.ItsmMachineJwtTestConfiguration;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
		"itsm.integration.auth.issuer=" + ItsmMachineJwtTestConfiguration.ISSUER,
		"itsm.integration.auth.jwk-set-uri=https://keycloak.test/realms/itsm/protocol/openid-connect/certs",
		"itsm.integration.auth.audience=" + ItsmMachineJwtTestConfiguration.AUDIENCE,
		"itsm.integration.auth.client-id=" + ItsmMachineJwtTestConfiguration.CLIENT_ID,
		"itsm.integration.auth.import-role=" + ItsmMachineJwtTestConfiguration.IMPORT_ROLE,
		"itsm.worker.poll-interval=PT0.05S"
})
@AutoConfigureMockMvc
@ActiveProfiles("work")
@Import({ItsmPostgresTestcontainersConfiguration.class, ItsmMachineJwtTestConfiguration.class})
class MeetingLinkIT extends ItsmWorkerTestSupport {

	private static final String INCIDENT = "INC000021095531";
	private static final String LINK_PATH = "/api/incidents/{id}/meeting-link";
	private static final String CARD_PATH = "/api/dashboard/incidents/{id}";
	private static final String ISSUER = "https://coordinator.test";
	private static final String FIRST = "https://link.example.test/meeting/first?access=1#join";
	private static final String SECOND = "http://link.example.test/meeting/next";
	private static final RequestPostProcessor COORDINATOR = coordinator("operator");

	@Autowired private DataSource dataSource;

	@BeforeEach
	void seed() throws Exception {
		resetDatabaseAndProcessor();
		jdbcTemplate.execute("TRUNCATE itsm_snapshot, itsm_incident_change, itsm_incident, itsm_snapshot_source CASCADE");
		jdbcTemplate.update("""
				INSERT INTO users(identity_issuer, identity_subject, login, display_name)
				VALUES (?, 'operator', 'operator', 'Coordinator') ON CONFLICT DO NOTHING
				""", ISSUER);
		publish(1, "Assigned");
	}

	@Test
	void savesReplacesAndReadsLinkWithoutChangingOtherLinksOrPublication() throws Exception {
		save(INCIDENT, FIRST).andExpect(status().isNoContent());
		assertMeetingUrl(FIRST);
		assertThat(links().size()).isEqualTo(1);
		var publication = jdbcTemplate.queryForMap("SELECT revision, event_seq FROM dashboard_publication");
		addGrafanaLink();

		save(INCIDENT, SECOND).andExpect(status().isNoContent());
		save(INCIDENT, SECOND).andExpect(status().isNoContent());
		assertMeetingUrl(SECOND);
		assertThat(links().size()).isEqualTo(2);
		assertThat(links().get("Grafana").asText()).isEqualTo("https://grafana.example.test");
		assertThat(jdbcTemplate.queryForMap("SELECT revision, event_seq FROM dashboard_publication"))
				.isEqualTo(publication);
	}

	@Test
	void rejectsInvalidRequestsAndMissingAuthorizationWithoutChangingStoredLinks() throws Exception {
		save(INCIDENT, FIRST).andExpect(status().isNoContent());
		JsonNode before = links();
		for (String url : new String[]{"javascript:alert(1)", "file:///tmp/meeting", "https:///missing-host",
				"https://user:password@link.example.test/meeting", "https://link.example.test:70000/meeting"}) {
			save(INCIDENT, url).andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.code").value("INVALID_MEETING_LINK")).andExpect(jsonPath("$.message").isString());
		}
		mockMvc.perform(put(LINK_PATH, INCIDENT).with(COORDINATOR).contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"meetingUrl":"https://link.example.test","Grafana":"https://other.example.test"}
								"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_MEETING_LINK")).andExpect(jsonPath("$.message").isString());
		save("INC000021095532", SECOND).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("INCIDENT_NOT_FOUND")).andExpect(jsonPath("$.message").isString());
		mockMvc.perform(request(INCIDENT, SECOND)).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("HUMAN_AUTH_REQUIRED")).andExpect(jsonPath("$.message").isString());
		mockMvc.perform(request(INCIDENT, SECOND).with(coordinator("unregistered"))).andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("COORDINATOR_ACCESS_DENIED")).andExpect(jsonPath("$.message").isString());
		assertThat(links()).isEqualTo(before);
		assertMeetingUrl(FIRST);
	}

	@Test
	void concurrentChangeToAnotherKeyAndLaterRestImportPreserveBothLinks() throws Exception {
		mockMvc.perform(get(CARD_PATH, INCIDENT).with(COORDINATOR)).andExpect(status().isOk())
				.andExpect(jsonPath("$.meetingUrl").value(nullValue()));
		assertThat(links().isEmpty()).isTrue();
		try (var threads = Executors.newSingleThreadExecutor(); var connection = dataSource.getConnection()) {
			connection.setAutoCommit(false);
			try (var statement = connection.prepareStatement("""
					UPDATE itsm_incident SET links_data = jsonb_set(links_data, '{Grafana}',
						to_jsonb(CAST(? AS text))) WHERE id = ?
					""")) {
				statement.setString(1, "https://grafana.example.test");
				statement.setString(2, INCIDENT);
				assertThat(statement.executeUpdate()).isEqualTo(1);
			}
			var pending = threads.submit(() -> save(INCIDENT, FIRST).andExpect(status().isNoContent()));
			try {
				await("meeting link request to wait for the concurrent update", () -> Boolean.TRUE.equals(
						jdbcTemplate.queryForObject("""
								SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE datname = current_database()
								    AND cardinality(pg_blocking_pids(pid)) > 0)
								""", Boolean.class)));
			} finally {
				connection.commit();
			}
			pending.get(10, TimeUnit.SECONDS);
		}
		JsonNode beforeImport = links();
		assertThat(beforeImport.get("Grafana").asText()).isEqualTo("https://grafana.example.test");
		assertThat(beforeImport.get("MTS Link").asText()).isEqualTo(FIRST);

		publish(2, "WorkInProgress");
		mockMvc.perform(get(CARD_PATH, INCIDENT).with(COORDINATOR)).andExpect(status().isOk())
				.andExpect(jsonPath("$.stateEnum").value("WorkInProgress"))
				.andExpect(jsonPath("$.meetingUrl").value(FIRST));
		assertThat(links()).isEqualTo(beforeImport);
	}

	private ResultActions save(String id, String url) throws Exception {
		return mockMvc.perform(request(id, url).with(COORDINATOR));
	}

	private MockHttpServletRequestBuilder request(String id, String url) {
		return put(LINK_PATH, id).contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.createObjectNode().put("meetingUrl", url).toString());
	}

	private void assertMeetingUrl(String url) throws Exception {
		mockMvc.perform(get(CARD_PATH, INCIDENT).with(COORDINATOR)).andExpect(status().isOk())
				.andExpect(jsonPath("$.meetingUrl").value(url));
	}

	private JsonNode links() {
		return objectMapper.readTree(jdbcTemplate.queryForObject(
				"SELECT links_data::text FROM itsm_incident WHERE id = ?", String.class, INCIDENT));
	}

	private void addGrafanaLink() {
		jdbcTemplate.update("""
				UPDATE itsm_incident SET links_data = jsonb_set(links_data, '{Grafana}',
					to_jsonb(CAST(? AS text))) WHERE id = ?
				""", "https://grafana.example.test", INCIDENT);
	}

	private void publish(long version, String state) throws Exception {
		UUID key = UUID.randomUUID();
		mockMvc.perform(postBatch(key, "kan37", version).content("""
				[{"id":"%s","stateEnum":"%s","priorityId":4,"submitDate":"2026-11-21T02:00:00Z",
				  "productName":"Test product","assignedGroupName":"Test team","solution":null,
				  "meetingUrl":"https://imported.example.test/meeting",
				  "links_data":{"MTS Link":"https://imported.example.test/meeting"}}]
				""".formatted(INCIDENT, state))).andExpect(status().isAccepted());
		awaitTerminal(key);
		assertStatus(key, "SUCCEEDED");
	}

	private static RequestPostProcessor coordinator(String subject) {
		return jwt().jwt(token -> token.issuer(ISSUER).subject(subject).claim("azp", "dashboard-web"));
	}
}
