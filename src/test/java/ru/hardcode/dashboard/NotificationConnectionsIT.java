package ru.hardcode.dashboard;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.postgresql.PostgreSQLContainer;
import ru.hardcode.dashboard.service.NotificationChannelAdapter.Destination;
import ru.hardcode.dashboard.service.NotificationConnectionService;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "itsm.worker.enabled=false")
@AutoConfigureMockMvc
@ActiveProfiles("work")
@Import(ItsmPostgresTestcontainersConfiguration.class)
class NotificationConnectionsIT {

	private static final String PATH = "/api/dashboard/notification-connections";
	private static final String ISSUER = "https://issuer.test";
	private static final RequestPostProcessor OWNER = coordinator("kan36-owner");
	private static final RequestPostProcessor OTHER = coordinator("kan36-other");

	@Autowired MockMvc mvc;
	@Autowired JdbcTemplate jdbc;
	@Autowired ObjectMapper json;
	@Autowired NotificationConnectionService service;
	@Autowired PostgreSQLContainer postgres;
	private long ownerId;
	private long otherId;

	@BeforeEach
	void seedUsers() {
		jdbc.update("DELETE FROM users WHERE identity_issuer = ?", ISSUER);
		ownerId = user("kan36-owner");
		otherId = user("kan36-other");
	}

	@Test
	void persistsPersonalSettingsAndDisablesOnlyTheSelectedConnection() throws Exception {
		long first = create(OWNER, body("email", "first@example.test", true, "[4]"));
		long second = create(OWNER, body("email", "second@example.test", true, "[4]"));
		long third = create(OWNER, body("sms", "+70000000003", true, "[3]"));
		long other = create(OTHER, body("email", "other@example.test", true, "[4]"));

		assertDestinations(ownerId, 4, first, second);
		assertDestinations(ownerId, 3, third);
		assertDestinations(otherId, 4, other);
		assertDestinations(ownerId, 2);

		assertThat(first).isNotEqualTo(second);
		assertThat(jdbc.queryForList("""
				SELECT p.priority_level_id FROM notification_connection_priorities p
				JOIN notification_connections c ON c.id = p.notification_connection_id
				WHERE c.user_id = ? ORDER BY c.id
				""", Integer.class, ownerId)).containsExactly(4, 4, 3);
		mvc.perform(get(PATH).with(OWNER))
				.andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.length()").value(3))
				.andExpect(jsonPath("$[0].id").value(first)).andExpect(jsonPath("$[0].priorityIds[0]").value(4))
				.andExpect(jsonPath("$[1].id").value(second))
				.andExpect(jsonPath("$[2].id").value(third)).andExpect(jsonPath("$[2].priorityIds[0]").value(3));

		mvc.perform(put(PATH + "/{id}", first).with(OWNER).contentType(MediaType.APPLICATION_JSON)
						.content(body("email", "first@example.test", false, "[4]")))
				.andExpect(status().isOk()).andExpect(jsonPath("$.active").value(false));
		assertThat(jdbc.queryForList("""
				SELECT is_active FROM notification_connections WHERE user_id = ? ORDER BY id
				""", Boolean.class, ownerId)).containsExactly(false, true, true);
		assertDestinations(ownerId, 4, second);
		assertDestinations(ownerId, 3, third);

		mvc.perform(put(PATH + "/{id}", second).with(OWNER).contentType(MediaType.APPLICATION_JSON)
						.content(body("email", "second@example.test", true, "[]")))
				.andExpect(status().isOk()).andExpect(jsonPath("$.priorityIds.length()").value(0));
		assertDestinations(ownerId, 4);
		assertDestinations(ownerId, 3, third);
		jdbc.update("UPDATE users SET is_active = false WHERE id = ?", ownerId);
		assertDestinations(ownerId, 3);
		mvc.perform(get(PATH).with(OWNER)).andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("COORDINATOR_ACCESS_DENIED"))
				.andExpect(jsonPath("$.message").isString());
	}

	@ParameterizedTest
	@ValueSource(strings = {"email", "sms", "push", "telegram", "mattermost", "max", "mts_link", "mobile_hub", "mts_notifica"})
	void supportsAllDemoChannels(String channel) throws Exception {
		long id = create(OWNER, body(channel, "demo-target", true, "[3,4]"));
		mvc.perform(get(PATH).with(OWNER))
				.andExpect(status().isOk()).andExpect(jsonPath("$[0].channel").value(channel));
		assertThat(jdbc.queryForObject("""
				SELECT t.code FROM notification_types t
				JOIN notification_connections c ON c.notification_type_id = t.id WHERE c.id = ?
				""", String.class, id)).isEqualTo(channel);
	}

	@Test
	void protectsOwnershipAndRejectsInvalidInputsWithoutWriting() throws Exception {
		long id = create(OTHER, body("email", "private@example.test", true, "[4]"));
		mvc.perform(get(PATH)).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("HUMAN_AUTH_REQUIRED"))
				.andExpect(jsonPath("$.message").isString());
		mvc.perform(get(PATH).with(OWNER)).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
		mvc.perform(put(PATH + "/{id}", id).with(OWNER).contentType(MediaType.APPLICATION_JSON)
						.content(body("sms", "changed", false, "[3]")))
				.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOTIFICATION_CONNECTION_NOT_FOUND"))
				.andExpect(jsonPath("$.message").isString());
		for (String input : new String[] {
				body("fax", "demo-target", true, "[4]"),
				body("email", "demo-target", true, "[5]"),
				body("email", "demo-target", true, "[4.5]"),
				body("email", "demo-target", true, "[4,4]"),
				body("email", " ", true, "[4]")
		}) {
			mvc.perform(post(PATH).with(OWNER).contentType(MediaType.APPLICATION_JSON).content(input))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.code").value("INVALID_NOTIFICATION_CONNECTION"))
					.andExpect(jsonPath("$.message").isString());
		}
		assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_connections", Long.class)).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT user_id FROM notification_connections WHERE id = ?", Long.class, id))
				.isEqualTo(otherId);
		mvc.perform(get(PATH).with(OTHER)).andExpect(status().isOk())
				.andExpect(jsonPath("$[0].channel").value("email")).andExpect(jsonPath("$[0].active").value(true))
				.andExpect(jsonPath("$[0].priorityIds[0]").value(4));
	}

	@Test
	void keepsSecretReferencesOutOfResponsesAndNeverStoresRawCredentials() throws Exception {
		mvc.perform(post(PATH).with(OWNER).contentType(MediaType.APPLICATION_JSON).content("""
				{"channel":"telegram","address":"demo-chat","active":true,"priorityIds":[4],"token":"raw-test-token"}
				""")).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_NOTIFICATION_CONNECTION"))
				.andExpect(jsonPath("$.message").isString());
		assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_connections", Long.class)).isZero();
		String input = """
				{"channel":"telegram","address":"demo-chat","active":true,"priorityIds":[4],
				 "secretRef":"TELEGRAM_DEMO_TOKEN"}
				""";
		var response = mvc.perform(post(PATH).with(OWNER).contentType(MediaType.APPLICATION_JSON).content(input))
				.andExpect(status().isCreated()).andExpect(jsonPath("$.secretRef").doesNotExist())
				.andExpect(jsonPath("$.token").doesNotExist()).andReturn().getResponse();
		assertThat(response.getContentAsString()).doesNotContain("TELEGRAM_DEMO_TOKEN", "raw-test-token");
		long id = json.readTree(response.getContentAsString()).get("id").asLong();
		assertThat(jdbc.queryForObject("SELECT connection_metadata::text FROM notification_connections WHERE id = ?",
				String.class, id)).contains("TELEGRAM_DEMO_TOKEN").doesNotContain("raw-test-token");
		var read = mvc.perform(get(PATH).with(OWNER)).andExpect(status().isOk())
				.andExpect(jsonPath("$[0].secretRef").doesNotExist()).andReturn().getResponse();
		assertThat(read.getContentAsString()).doesNotContain("TELEGRAM_DEMO_TOKEN", "raw-test-token");
		assertThat(service.findDestinations(ownerId, 4)).extracting(Destination::secretRef)
				.containsExactly("TELEGRAM_DEMO_TOKEN");

		mvc.perform(put(PATH + "/{id}", id).with(OWNER).contentType(MediaType.APPLICATION_JSON)
						.content(body("telegram", "demo-chat", true, "[4]")))
				.andExpect(status().isOk()).andExpect(jsonPath("$.secretRef").doesNotExist());
		assertThat(service.findDestinations(ownerId, 4).getFirst().secretRef()).isEqualTo("TELEGRAM_DEMO_TOKEN");

		mvc.perform(put(PATH + "/{id}", id).with(OWNER).contentType(MediaType.APPLICATION_JSON).content("""
				{"channel":"telegram","address":"demo-chat","active":true,"priorityIds":[4],"secretRef":null}
				""")).andExpect(status().isOk()).andExpect(jsonPath("$.secretRef").doesNotExist());
		assertThat(service.findDestinations(ownerId, 4).getFirst().secretRef()).isNull();
	}

	@Test
	void restoresConnectionsAfterBackendRestart() throws Exception {
		long id;
		try (var app = start()) {
			id = create(mvc(app), OWNER, body("max", "restart-target", true, "[4,3]"));
		}
		try (var app = start()) {
			mvc(app).perform(get(PATH + "/{id}", id).with(OWNER))
					.andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id))
					.andExpect(jsonPath("$.channel").value("max"))
					.andExpect(jsonPath("$.address").value("restart-target"))
					.andExpect(jsonPath("$.active").value(true))
					.andExpect(jsonPath("$.priorityIds[0]").value(3))
					.andExpect(jsonPath("$.priorityIds[1]").value(4));
			mvc(app).perform(get(PATH + "/{id}", id).with(OTHER))
					.andExpect(status().isNotFound())
					.andExpect(jsonPath("$.code").value("NOTIFICATION_CONNECTION_NOT_FOUND"));
			assertThat(app.getBean(NotificationConnectionService.class).findDestinations(ownerId, 4))
					.extracting(Destination::connectionId).containsExactly(id);
		}
	}

	private ConfigurableApplicationContext start() {
		return new SpringApplicationBuilder(DashboardApplication.class).profiles("work").run(
				"--server.port=0",
				"--itsm.worker.enabled=false",
				"--spring.datasource.url=" + postgres.getJdbcUrl(),
				"--spring.datasource.username=" + postgres.getUsername(),
				"--spring.datasource.password=" + postgres.getPassword()
		);
	}

	private static MockMvc mvc(ConfigurableApplicationContext app) {
		return MockMvcBuilders.webAppContextSetup((WebApplicationContext) app).apply(springSecurity()).build();
	}

	private void assertDestinations(long userId, int priorityId, Long... ids) {
		assertThat(service.findDestinations(userId, priorityId))
				.extracting(Destination::connectionId).containsExactly(ids);
	}

	private long create(RequestPostProcessor owner, String input) throws Exception {
		return create(mvc, owner, input);
	}

	private long create(MockMvc requests, RequestPostProcessor owner, String input) throws Exception {
		var response = requests.perform(post(PATH).with(owner).contentType(MediaType.APPLICATION_JSON).content(input))
				.andExpect(status().isCreated()).andExpect(jsonPath("$.id").isNumber()).andReturn().getResponse();
		long id = json.readTree(response.getContentAsString()).get("id").asLong();
		assertThat(response.getHeader("Location")).isEqualTo(PATH + "/" + id);
		return id;
	}

	private long user(String subject) {
		return jdbc.queryForObject("""
				INSERT INTO users(identity_issuer, identity_subject, login, display_name)
				VALUES (?, ?, ?, ?) RETURNING id
				""", Long.class, ISSUER, subject, subject, subject);
	}

	private static RequestPostProcessor coordinator(String subject) {
		return jwt().jwt(token -> token.issuer(ISSUER).subject(subject).claim("azp", "dashboard-web"));
	}

	private static String body(String channel, String address, boolean active, String priorities) {
		return """
				{"channel":"%s","address":"%s","active":%b,"priorityIds":%s}
				""".formatted(channel, address, active, priorities);
	}
}
