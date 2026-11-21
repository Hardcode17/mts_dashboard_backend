package ru.hardcode.dashboard;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "itsm.worker.enabled=false")
@AutoConfigureMockMvc
@ActiveProfiles("work")
@Import(ItsmPostgresTestcontainersConfiguration.class)
class ConfirmedIncidentLinksIT {

	private static final String CARDS = "/api/dashboard/incidents/";
	private static final String FIRST = "INC000001";
	private static final String SECOND = "INC000002";
	private static final String MISSING = "INC000003";
	private static final String ISSUER = "https://issuer.test";
	private static final RequestPostProcessor FIRST_COORDINATOR = auth("first-coordinator");
	private static final RequestPostProcessor SECOND_COORDINATOR = auth("second-coordinator");

	@Autowired MockMvc mvc;
	@Autowired JdbcTemplate jdbc;
	@Autowired ObjectMapper json;
	@Autowired PostgreSQLContainer postgres;

	private long firstCoordinatorId;
	private long secondCoordinatorId;

	@BeforeEach
	void seed() {
		jdbc.update("DELETE FROM incident_links");
		jdbc.update("DELETE FROM itsm_incident WHERE id IN (?, ?)", FIRST, SECOND);
		jdbc.update("DELETE FROM users WHERE identity_issuer = ?", ISSUER);
		firstCoordinatorId = user("first-coordinator");
		secondCoordinatorId = user("second-coordinator");
		jdbc.update("""
				INSERT INTO itsm_snapshot_source(source_id, observed_version, change_revision)
				VALUES ('kan41', 1, 1) ON CONFLICT DO NOTHING
				""");
		jdbc.update("UPDATE dashboard_publication SET event_seq = 1, revision = 1");
		jdbc.update("INSERT INTO itsm_catalog_product(product_key) VALUES ('kan41-product') ON CONFLICT DO NOTHING");
		incident(FIRST, "Assigned");
		incident(SECOND, "WorkInProgress");
	}

	@Test
	void confirmsEachKindAndKeepsAuthorDirectionAndTimeOnRetry() throws Exception {
		var link = confirm(mvc, SECOND, FIRST, "related_to", FIRST_COORDINATOR);
		assertThat(link.get("sourceIncidentId").asText()).isEqualTo(FIRST);
		assertThat(link.get("targetIncidentId").asText()).isEqualTo(SECOND);
		assertThat(link.get("kind").asText()).isEqualTo("related_to");
		assertThat(link.get("confirmedByUserId").asLong()).isEqualTo(firstCoordinatorId);
		assertThat(Instant.parse(link.get("createdAt").asText())).isBeforeOrEqualTo(Instant.now());
		assertThat(confirm(mvc, FIRST, SECOND, "related_to", SECOND_COORDINATOR)).isEqualTo(link);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM incident_links", Long.class)).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT confirmed_by_user_id FROM incident_links", Long.class))
				.isEqualTo(firstCoordinatorId);
		assertThat(jdbc.queryForObject("SELECT created_at FROM incident_links", Timestamp.class).toInstant())
				.isEqualTo(Instant.parse(link.get("createdAt").asText()));

		var causes = confirm(mvc, SECOND, FIRST, "causes", FIRST_COORDINATOR);
		assertThat(causes.get("sourceIncidentId").asText()).isEqualTo(SECOND);
		assertThat(causes.get("targetIncidentId").asText()).isEqualTo(FIRST);
		var reverseCauses = confirm(mvc, FIRST, SECOND, "causes", SECOND_COORDINATOR);
		assertThat(reverseCauses.get("id").asLong()).isNotEqualTo(causes.get("id").asLong());
		var duplicate = confirm(mvc, SECOND, FIRST, "duplicate_of", FIRST_COORDINATOR);
		for (String incidentId : new String[]{FIRST, SECOND}) {
			var links = card(mvc, incidentId).get("confirmedLinks");
			assertThat(links).containsExactly(link, causes, reverseCauses, duplicate);
		}
		assertThat(jdbc.queryForObject("SELECT count(*) FROM incident_links", Long.class)).isEqualTo(4);
		assertIncidentsUnchanged();
	}

	@Test
	void rejectsSelfUnknownInvalidAndUnauthorizedWithoutWriting() throws Exception {
		var first = card(mvc, FIRST);
		var second = card(mvc, SECOND);
		mvc.perform(command(FIRST, SECOND, "related_to"))
				.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("HUMAN_AUTH_REQUIRED"))
				.andExpect(jsonPath("$.message").isString());
		mvc.perform(command(FIRST, SECOND, "related_to").with(auth("unregistered")))
				.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("COORDINATOR_ACCESS_DENIED"))
				.andExpect(jsonPath("$.message").isString());
		mvc.perform(command(FIRST, FIRST, "related_to").with(FIRST_COORDINATOR))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INCIDENT_LINK"))
				.andExpect(jsonPath("$.message").isString());
		mvc.perform(command(FIRST, SECOND, "parent_of").with(FIRST_COORDINATOR))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INCIDENT_LINK"))
				.andExpect(jsonPath("$.message").isString());
		mvc.perform(command(FIRST, "invalid", "related_to").with(FIRST_COORDINATOR))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INCIDENT_ID"))
				.andExpect(jsonPath("$.message").isString());
		for (var request : List.of(command(FIRST, MISSING, "related_to"),
				command(MISSING, SECOND, "related_to"))) {
			mvc.perform(request.with(FIRST_COORDINATOR))
					.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("INCIDENT_NOT_FOUND"))
					.andExpect(jsonPath("$.message").value("Incident " + MISSING + " not found"));
		}
		assertThat(jdbc.queryForObject("SELECT count(*) FROM incident_links", Long.class)).isZero();
		assertThat(card(mvc, FIRST)).isEqualTo(first);
		assertThat(card(mvc, SECOND)).isEqualTo(second);
		assertIncidentsUnchanged();
	}

	@Test
	void concurrentReverseConfirmationsReturnOneLinkAndSurviveBackendRestart() throws Exception {
		JsonNode link;
		try (var app = start(); var threads = Executors.newFixedThreadPool(2)) {
			var requests = mvc(app);
			var startTogether = new CountDownLatch(1);
			var forward = threads.submit(() -> {
				startTogether.await();
				return confirm(requests, FIRST, SECOND, "related_to", FIRST_COORDINATOR);
			});
			var reverse = threads.submit(() -> {
				startTogether.await();
				return confirm(requests, SECOND, FIRST, "related_to", SECOND_COORDINATOR);
			});
			startTogether.countDown();
			link = forward.get(10, TimeUnit.SECONDS);
			assertThat(reverse.get(10, TimeUnit.SECONDS)).isEqualTo(link);
		}
		assertThat(link.get("confirmedByUserId").asLong()).isIn(firstCoordinatorId, secondCoordinatorId);
		try (var restarted = start()) {
			var requests = mvc(restarted);
			assertThat(card(requests, FIRST).get("confirmedLinks")).containsExactly(link);
			assertThat(card(requests, SECOND).get("confirmedLinks")).containsExactly(link);
			assertThat(confirm(requests, SECOND, FIRST, "related_to", SECOND_COORDINATOR)).isEqualTo(link);
		}
		assertThat(jdbc.queryForObject("SELECT count(*) FROM incident_links", Long.class)).isEqualTo(1);
		assertIncidentsUnchanged();
	}

	private ConfigurableApplicationContext start() {
		return new SpringApplicationBuilder(DashboardApplication.class).profiles("work").run(
				"--server.port=0", "--itsm.worker.enabled=false",
				"--spring.datasource.url=" + postgres.getJdbcUrl(),
				"--spring.datasource.username=" + postgres.getUsername(),
				"--spring.datasource.password=" + postgres.getPassword()
		);
	}

	private static MockMvc mvc(ConfigurableApplicationContext app) {
		return MockMvcBuilders.webAppContextSetup((WebApplicationContext) app).apply(springSecurity()).build();
	}

	private JsonNode confirm(MockMvc requests, String source, String target, String kind, RequestPostProcessor actor)
			throws Exception {
		var response = requests.perform(command(source, target, kind).with(actor)).andExpect(status().isOk())
				.andReturn().getResponse();
		return json.readTree(response.getContentAsString());
	}

	private JsonNode card(MockMvc requests, String incidentId) throws Exception {
		var response = requests.perform(get(CARDS + incidentId).with(FIRST_COORDINATOR)).andExpect(status().isOk())
				.andReturn().getResponse();
		return json.readTree(response.getContentAsString());
	}

	private static MockHttpServletRequestBuilder command(
			String source, String target, String kind) {
		return post(CARDS + source + "/confirmed-links").contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"targetIncidentId":"%s","kind":"%s"}
						""".formatted(target, kind));
	}

	private long user(String subject) {
		return jdbc.queryForObject("""
				INSERT INTO users(identity_issuer, identity_subject, login, display_name)
				VALUES (?, ?, ?, ?) RETURNING id
				""", Long.class, ISSUER, subject, subject, subject);
	}

	private void incident(String id, String state) {
		jdbc.update("""
				INSERT INTO itsm_incident(id, state, priority_id, submit_date, product_id,
				    source_id, source_data, last_seen_version, last_change_revision)
				SELECT ?, ?, 4, '2026-11-21T12:00:00Z', id, 'kan41', '{}', 1, 1
				FROM itsm_catalog_product WHERE product_key = 'kan41-product'
				""", id, state);
	}

	private void assertIncidentsUnchanged() {
		assertThat(jdbc.queryForList("SELECT state FROM itsm_incident ORDER BY id", String.class))
				.containsExactly("Assigned", "WorkInProgress");
		assertThat(jdbc.queryForObject("SELECT count(*) FROM itsm_incident_change", Long.class)).isZero();
		assertThat(jdbc.queryForObject("SELECT event_seq FROM dashboard_publication", Long.class)).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT revision FROM dashboard_publication", Long.class)).isEqualTo(1);
	}

	private static RequestPostProcessor auth(String subject) {
		return jwt().jwt(token -> token.issuer(ISSUER).subject(subject).claim("azp", "dashboard-web"));
	}
}
