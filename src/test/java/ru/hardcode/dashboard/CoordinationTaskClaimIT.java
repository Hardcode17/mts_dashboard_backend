package ru.hardcode.dashboard;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import ru.hardcode.dashboard.client.Kan16JwtTestConfiguration;
import ru.hardcode.dashboard.client.TestJwtSigner;
import ru.hardcode.dashboard.service.CoordinationClaimService;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "itsm.worker.enabled=false")
@ActiveProfiles("work")
@AutoConfigureMockMvc
@Import({ItsmPostgresTestcontainersConfiguration.class, CoordinationTaskClaimIT.FixedTime.class})
class CoordinationTaskClaimIT {

	private static final String PATH = "/api/dashboard/coordination-tasks";
	private static final String ISSUER = "https://keycloak.test/realms/dashboard";
	private static final String AUDIENCE = "dashboard-human";
	private static final String FIRST = "kan85-first";
	private static final String SECOND = "kan85-second";
	private static final Instant SHOWN = Instant.parse("2026-11-21T12:00:00Z");
	private static final Instant EXPIRES = SHOWN.plusSeconds(60);

	@Autowired MockMvc mvc;
	@Autowired JdbcTemplate jdbc;
	@Autowired HikariDataSource pool;
	@Autowired Clock clock;
	@Autowired ObjectMapper json;

	@DynamicPropertySource
	static void humanAuthentication(DynamicPropertyRegistry properties) {
		properties.add("app.auth.human.issuer", () -> ISSUER);
		properties.add("app.auth.human.audience", () -> AUDIENCE);
		properties.add("app.auth.human.jwk-set-uri", Kan16JwtTestConfiguration::jwkSetUri);
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class FixedTime {
		@Bean
		@Primary
		Clock claimClock() {
			return spy(Clock.fixed(SHOWN, ZoneOffset.UTC));
		}
	}

	@BeforeEach
	void resetQueue() {
		doReturn(SHOWN).when(clock).instant();
		jdbc.execute("TRUNCATE coordination_tasks, itsm_incident, shift_event RESTART IDENTITY CASCADE");
		jdbc.update("""
				INSERT INTO users(identity_issuer, identity_subject, login, display_name)
				VALUES (?, ?, ?, 'Coordinator'), (?, ?, ?, 'Coordinator') ON CONFLICT DO NOTHING
				""", ISSUER, FIRST, FIRST, ISSUER, SECOND, SECOND);
		jdbc.update("INSERT INTO itsm_catalog_product(product_key) VALUES ('kan85') ON CONFLICT DO NOTHING");
	}

	@Test
	void twoCoordinatorsClaimOnceAndOnlyTheWinnerSeesTheTaskInMine() throws Exception {
		long task = queued("INC000021095851", 3);
		String claim = taskPath(task) + "/claim";
		mvc.perform(post(claim)).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("HUMAN_AUTH_REQUIRED"))
				.andExpect(jsonPath("$.message").value("Human access token is required"));
		mvc.perform(as(post(claim), "unregistered")).andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("COORDINATOR_ACCESS_DENIED"))
				.andExpect(jsonPath("$.message").value("Coordinator access is not granted"));
		assertThat(owner(task)).isNull();
		assertThat(race(() -> code(claim, FIRST), () -> code(claim, SECOND)))
				.containsExactlyInAnyOrder(200, 409);

		String winner = owner(task).equals(userId(FIRST)) ? FIRST : SECOND;
		String loser = winner.equals(FIRST) ? SECOND : FIRST;
		mvc.perform(as(post(claim), winner)).andExpect(status().isOk())
				.andExpect(jsonPath("$.coordinatorId").value(userId(winner)))
				.andExpect(jsonPath("$.acceptedAt").isEmpty());
		error(mvc.perform(as(post(claim), loser)), "COORDINATION_TASK_TAKEN", "Coordination task is already taken");
		mvc.perform(as(get(PATH + "/mine"), winner)).andExpect(status().isOk())
				.andExpect(jsonPath("$[0].taskId").value(task));
		mvc.perform(as(get(PATH + "/mine"), loser)).andExpect(status().isOk())
				.andExpect(jsonPath("$").isEmpty());

		jdbc.update("UPDATE coordination_tasks SET status = 'completed', finished_at = now() WHERE id = ?", task);
		mvc.perform(as(get(PATH + "/mine"), winner)).andExpect(status().isOk())
				.andExpect(jsonPath("$").isEmpty());
		mvc.perform(as(post(claim), loser)).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("COORDINATION_TASK_NOT_OPEN"))
				.andExpect(jsonPath("$.message").value("Open coordination task not found"));
		assertThat(owner(task)).isEqualTo(userId(winner));
	}

	@Test
	void fullscreenAcceptanceIsExclusiveAndAckDoesNotAccept() throws Exception {
		String incident = "INC000021095852";
		long task = queued(incident, 4);
		jdbc.update("""
				INSERT INTO shift_event(event_id, source_id, incident_id, change_revision, reasons, ack_required, payload)
				VALUES ('evt-0001', 'test', ?, 1, ARRAY['new_critical'], true, '{}')
				""", incident);
		String firstWindow = show(task, FIRST);
		String secondWindow = show(task, SECOND);
		assertThat(firstWindow).isNotEqualTo(secondWindow);
		assertThat(owner(task)).isNull();
		error(mvc.perform(as(post(acceptPath(task, firstWindow)), SECOND)),
				"COORDINATION_WINDOW_UNAVAILABLE", "Fullscreen acceptance window is not available");
		mvc.perform(as(post("/api/dashboard/shift/events/ack"), FIRST)
						.contentType(APPLICATION_JSON).content("{\"eventIds\":[\"evt-0001\"]}"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.confirmedEventIds[0]").value("evt-0001"));
		assertThat(acceptedAt(task)).isNull();
		assertThat(owner(task)).isNull();

		Instant taken = EXPIRES.minusNanos(1000);
		doReturn(taken).when(clock).instant();
		assertThat(race(() -> code(acceptPath(task, firstWindow), FIRST),
				() -> code(acceptPath(task, secondWindow), SECOND))).containsExactlyInAnyOrder(200, 409);
		assertThat(acceptedAt(task)).isEqualTo(taken);
		boolean firstWon = owner(task).equals(userId(FIRST));
		mvc.perform(as(post(acceptPath(task, firstWon ? firstWindow : secondWindow)), firstWon ? FIRST : SECOND))
				.andExpect(status().isOk()).andExpect(jsonPath("$.acceptedAt").value(taken.toString()));
		assertThat(acceptedAt(task)).isEqualTo(taken);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM coordination_tasks WHERE incident_id = ?",
				Integer.class, incident)).isOne();
	}

	@Test
	void exactDeadlineAndLateAcceptanceFailAndRestartDoesNotReopenTheWindow() throws Exception {
		long task = queued("INC000021095853", 4);
		String window = show(task, FIRST);
		doReturn(SHOWN.plusSeconds(30)).when(clock).instant();
		assertThat(show(task, FIRST)).isEqualTo(window);
		for (Instant time : List.of(EXPIRES, EXPIRES.plusSeconds(1))) {
			doReturn(time).when(clock).instant();
			error(mvc.perform(as(post(acceptPath(task, window)), FIRST)),
					"COORDINATION_WINDOW_EXPIRED", "Fullscreen acceptance window has expired");
		}

		try (var restarted = new SpringApplicationBuilder(DashboardApplication.class)
				.profiles("work").run("--server.port=0", "--itsm.worker.enabled=false",
						"--spring.datasource.url=" + pool.getJdbcUrl(),
						"--spring.datasource.username=" + pool.getUsername(),
						"--spring.datasource.password=" + pool.getPassword())) {
			var persisted = restarted.getBean(CoordinationClaimService.class).show(task, userId(FIRST));
			assertThat(persisted.windowId().toString()).isEqualTo(window);
			assertThat(persisted.expiresAt()).isEqualTo(EXPIRES);
		}
		assertThat(owner(task)).isNull();
		assertThat(acceptedAt(task)).isNull();
		mvc.perform(as(post(taskPath(task) + "/claim"), SECOND)).andExpect(status().isOk())
				.andExpect(jsonPath("$.coordinatorId").value(userId(SECOND)))
				.andExpect(jsonPath("$.acceptedAt").isEmpty());
	}

	@Test
	void p3NeverStartsAWindowAndOrdinaryP4ClaimDoesNotStartOneEither() throws Exception {
		long p3 = queued("INC000021095854", 3);
		long p4 = queued("INC000021095855", 4);
		mvc.perform(as(post(taskPath(p3) + "/fullscreen-window"), FIRST)).andExpect(status().isOk())
				.andExpect(jsonPath("$.expiresAt").isEmpty()).andExpect(jsonPath("$.windowId").isEmpty());
		mvc.perform(as(post(taskPath(p4) + "/claim"), FIRST)).andExpect(status().isOk())
				.andExpect(jsonPath("$.acceptedAt").isEmpty());
		assertThat(jdbc.queryForObject("SELECT count(*) FROM coordination_task_window", Integer.class)).isZero();
	}

	// KAN-24 owns creation of queue entries; seed its output without inventing another queue producer.
	private long queued(String incident, int priority) {
		jdbc.update("""
				INSERT INTO itsm_incident(id, priority_id, state, submit_date, product_id)
				SELECT ?, ?, 'Assigned', now(), id FROM itsm_catalog_product WHERE product_key = 'kan85'
				""", incident, priority);
		return jdbc.queryForObject("INSERT INTO coordination_tasks(incident_id) VALUES (?) RETURNING id",
				Long.class, incident);
	}

	private long userId(String subject) {
		return jdbc.queryForObject("SELECT id FROM users WHERE identity_issuer = ? AND identity_subject = ?",
				Long.class, ISSUER, subject);
	}

	private Long owner(long task) {
		return jdbc.queryForObject("SELECT coordinator_id FROM coordination_tasks WHERE id = ?", Long.class, task);
	}

	private Instant acceptedAt(long task) {
		return jdbc.queryForObject("SELECT accepted_at FROM coordination_tasks WHERE id = ?",
				(rs, i) -> rs.getTimestamp(1) == null ? null : rs.getTimestamp(1).toInstant(), task);
	}

	private String show(long task, String subject) throws Exception {
		var response = mvc.perform(as(post(taskPath(task) + "/fullscreen-window"), subject))
				.andExpect(status().isOk()).andExpect(jsonPath("$.expiresAt").value(EXPIRES.toString()))
				.andReturn().getResponse();
		return json.readTree(response.getContentAsByteArray()).get("windowId").asText();
	}

	private int code(String path, String subject) throws Exception {
		return mvc.perform(as(post(path), subject)).andReturn().getResponse().getStatus();
	}

	private static String taskPath(long task) {
		return PATH + "/" + task;
	}

	private static String acceptPath(long task, String window) {
		return taskPath(task) + "/fullscreen-window/" + window + "/accept";
	}

	private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, String subject) {
		String jwt = Kan16JwtTestConfiguration.signer().token(TestJwtSigner.claims()
				.issuer(ISSUER).audience(AUDIENCE).subject(subject).claim("azp", "dashboard-web")
				.expiresAt(Instant.now().plusSeconds(300)));
		return request.header(AUTHORIZATION, "Bearer " + jwt);
	}

	private static void error(ResultActions response, String code, String message) throws Exception {
		response.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(code))
				.andExpect(jsonPath("$.message").value(message));
	}

	private static List<Integer> race(Callable<Integer> first, Callable<Integer> second) throws Exception {
		var start = new CyclicBarrier(2);
		try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
			var a = executor.submit(() -> { start.await(10, TimeUnit.SECONDS); return first.call(); });
			var b = executor.submit(() -> { start.await(10, TimeUnit.SECONDS); return second.call(); });
			return List.of(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS));
		}
	}
}
