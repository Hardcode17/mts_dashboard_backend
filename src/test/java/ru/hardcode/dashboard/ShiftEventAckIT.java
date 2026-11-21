package ru.hardcode.dashboard;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import ru.hardcode.dashboard.client.ItsmMachineJwtTestConfiguration;
import ru.hardcode.dashboard.client.Kan16JwtTestConfiguration;
import ru.hardcode.dashboard.client.TestJwtSigner;
import ru.hardcode.dashboard.controller.ItsmBatchController;
import ru.hardcode.dashboard.service.ItsmSnapshotPublicationService;
import ru.hardcode.dashboard.service.ShiftEventService;
import ru.hardcode.dashboard.worker.ItsmWorkerSqlRepository;
import ru.hardcode.dashboard.worker.ItsmWorkerTransactionService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * KAN-29 acceptance of the group «Увидел» over the KAN-25 shift event queue. The real intake
 * endpoint receives REST packages under machine authentication, the publication journal is
 * applied through the production worker transaction, and the coordinator acknowledges exactly
 * the shown alarm events through the human chain (MockMvc over the real filter chain,
 * PostgreSQL 17 via Testcontainers).
 *
 * <p>Positive: ack for A,B stores the author and time, removes A,B from the queue read and
 * returns the remaining queue. Negative: an unauthorized request acknowledges nothing; an
 * unknown id and an event that requires no acknowledgement reject the whole set atomically
 * and no unrelated event is marked. Boundary: an event C arriving together with ack(A,B)
 * stays unconfirmed, a repeated request and a restart keep the author and the time of the
 * first acknowledgement, and a new critical episode of the same INC is acknowledged
 * separately. Ack never closes an internal coordination task (KAN-32 is out of scope here).
 */
@SpringBootTest(properties = {
		"itsm.worker.enabled=false",
		"itsm.integration.auth.issuer=" + ItsmMachineJwtTestConfiguration.ISSUER,
		"itsm.integration.auth.jwk-set-uri=https://keycloak.test/realms/itsm/protocol/openid-connect/certs",
		"itsm.integration.auth.audience=" + ItsmMachineJwtTestConfiguration.AUDIENCE,
		"itsm.integration.auth.client-id=" + ItsmMachineJwtTestConfiguration.CLIENT_ID,
		"itsm.integration.auth.import-role=" + ItsmMachineJwtTestConfiguration.IMPORT_ROLE
})
@AutoConfigureMockMvc
@ActiveProfiles("work")
@Import({ItsmPostgresTestcontainersConfiguration.class, ItsmMachineJwtTestConfiguration.class})
class ShiftEventAckIT {

	private static final String SOURCE = "ack-itcs";
	private static final String BATCH_PATH = "/api/integrations/itsm/batches";
	private static final String QUEUE = "/api/dashboard/shift/events";
	private static final String ACK = QUEUE + "/ack";
	private static final String COORDINATORS = "КЦ ИТ Координаторы Массовых Инцидентов";
	private static final String HUMAN_ISSUER = "https://keycloak.test/realms/dashboard";
	private static final String HUMAN_AUDIENCE = "dashboard-human";
	private static final String HUMAN_SUBJECT = "kan29-ack-operator";
	private static final Set<String> COMPLETED = Set.of("Resolved", "Closed", "Research");

	@Autowired MockMvc mockMvc;
	@Autowired JdbcTemplate jdbc;
	@Autowired DataSource dataSource;
	@Autowired PlatformTransactionManager transactions;
	@Autowired ItsmSnapshotPublicationService publication;
	@Autowired JwtEncoder jwtEncoder;

	@DynamicPropertySource
	static void humanAuthentication(DynamicPropertyRegistry registry) {
		registry.add("app.auth.human.issuer", () -> HUMAN_ISSUER);
		registry.add("app.auth.human.audience", () -> HUMAN_AUDIENCE);
		registry.add("app.auth.human.jwk-set-uri", Kan16JwtTestConfiguration::jwkSetUri);
	}

	@BeforeEach
	void resetShiftQueue() {
		jdbc.execute("""
				TRUNCATE itsm_ingest_batch, itsm_snapshot, itsm_snapshot_source, itsm_incident,
				    itsm_catalog_product, itsm_catalog_service, itsm_catalog_scenario,
				    itsm_external_group, itsm_external_person, itsm_ingest_catalog_hint,
				    itsm_incident_change, shift_event, shift_event_source RESTART IDENTITY CASCADE
				""");
		jdbc.execute("ALTER SEQUENCE shift_event_id_seq RESTART WITH 1");
		jdbc.update("UPDATE dashboard_publication SET event_seq = 0, revision = 0 WHERE singleton");
		jdbc.update("UPDATE shift_event_progress SET last_revision = 0 WHERE singleton");
		jdbc.update("""
				INSERT INTO users(identity_issuer, identity_subject, login, display_name)
				VALUES (?, ?, 'operator', 'Ack Operator') ON CONFLICT DO NOTHING
				""", HUMAN_ISSUER, HUMAN_SUBJECT);
	}

	@Test
	void ackConfirmsShownEventsKeepsLaterArrivalsAndAcknowledgesNewEpisodesSeparately() throws Exception {
		Map<String, String> state = new LinkedHashMap<>();
		state.put("INC000021095701", incident("INC000021095701", 3, "Assigned", "Оплата"));
		state.put("INC000021095702", incident("INC000021095702", 3, "Assigned", "Оплата"));
		state.put("INC000021095703", incident("INC000021095703", 3, "Assigned", "Логистика"));
		deliver(1, state);

		// Two alarm events A and B are shown to the shift.
		state.put("INC000021095701", incident("INC000021095701", 4, "Assigned", "Оплата"));
		deliver(2, state);
		state.put("INC000021095702", incident("INC000021095702", 4, "Assigned", "Оплата"));
		deliver(3, state);
		poll(0)
				.andExpect(jsonPath("$.events[*].eventId", contains("evt-0001", "evt-0002")))
				.andExpect(jsonPath("$.events[*].ackRequired", contains(true, true)));

		// The shift confirms exactly A,B: author and time are stored, the queue read no longer
		// shows A,B, and the acknowledgement response returns the remaining queue.
		ack("[\"evt-0001\",\"evt-0002\"]")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("acknowledged"))
				.andExpect(jsonPath("$.confirmedEventIds", contains("evt-0001", "evt-0002")))
				.andExpect(jsonPath("$.events").isEmpty())
				.andExpect(jsonPath("$.nextEventSeq").value(0));
		poll(0).andExpect(jsonPath("$.events").isEmpty());
		long actorId = operatorId();
		assertThat(acknowledgedBy("evt-0001")).isEqualTo(actorId);
		assertThat(acknowledgedBy("evt-0002")).isEqualTo(actorId);
		Instant firstAcknowledgedAt = acknowledgedAt("evt-0001");
		assertThat(firstAcknowledgedAt).isNotNull();

		// C arrives together with the confirmation: it stays unconfirmed, and the repeated
		// ack of A,B never touches C because the set is explicit.
		state.put("INC000021095703", incident("INC000021095703", 4, "Assigned", "Логистика"));
		deliver(4, state);
		poll(0)
				.andExpect(jsonPath("$.events[*].eventId", contains("evt-0003")))
				.andExpect(jsonPath("$.events[*].incidentId", contains("INC000021095703")));
		ack("[\"evt-0001\",\"evt-0002\"]")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.confirmedEventIds", contains("evt-0001", "evt-0002")))
				.andExpect(jsonPath("$.events[*].eventId", contains("evt-0003")))
				.andExpect(jsonPath("$.nextEventSeq").value(3));
		assertThat(acknowledgedAt("evt-0003")).isNull();
		// The repeated request keeps the author and the time of the first acknowledgement.
		assertThat(acknowledgedBy("evt-0001")).isEqualTo(actorId);
		assertThat(acknowledgedAt("evt-0001")).isEqualTo(firstAcknowledgedAt);

		// C is confirmed on its own; the queue is empty before the next episode.
		ack("[\"evt-0003\"]")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.confirmedEventIds", contains("evt-0003")))
				.andExpect(jsonPath("$.events").isEmpty())
				.andExpect(jsonPath("$.nextEventSeq").value(0));

		// A new critical episode of the same INC is a separate eventId and is acknowledged
		// separately; the resolution episode in between requires no «Увидел» and stays queued.
		state.put("INC000021095701", incident("INC000021095701", 4, "Resolved", "Оплата"));
		deliver(5, state);
		state.put("INC000021095701", incident("INC000021095701", 4, "WorkInProgress", "Оплата"));
		deliver(6, state);
		poll(0)
				.andExpect(jsonPath("$.events[*].eventId", contains("evt-0004", "evt-0005")))
				.andExpect(jsonPath("$.events[*].incidentId", contains("INC000021095701", "INC000021095701")))
				.andExpect(jsonPath("$.events[0].ackRequired").value(false))
				.andExpect(jsonPath("$.events[1].ackRequired").value(true));
		ack("[\"evt-0005\"]")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.confirmedEventIds", contains("evt-0005")))
				.andExpect(jsonPath("$.events[*].eventId", contains("evt-0004")))
				.andExpect(jsonPath("$.nextEventSeq").value(4));
		assertThat(acknowledgedAt("evt-0001")).isEqualTo(firstAcknowledgedAt);
		assertThat(acknowledgedAt("evt-0004")).isNull();
		assertThat(acknowledgedAt("evt-0005")).isNotNull();
	}

	@Test
	void unauthorizedOrInvalidAcknowledgementSetsAreRejectedAtomically() throws Exception {
		Map<String, String> state = new LinkedHashMap<>();
		state.put("INC000021095704", incident("INC000021095704", 3, "Assigned", "Оплата"));
		state.put("INC000021095705", incident("INC000021095705", 3, "Assigned", "Поддержка"));
		deliver(1, state);
		state.put("INC000021095704", incident("INC000021095704", 4, "Assigned", "Оплата"));
		deliver(2, state);
		state.put("INC000021095705", incident("INC000021095705", 3, "Assigned", COORDINATORS));
		deliver(3, state);
		// evt-0001 is the alarm, evt-0002 is a queue signal without «Увидел».
		poll(0).andExpect(jsonPath("$.events[*].eventId", contains("evt-0001", "evt-0002")));

		// An unauthorized request acknowledges nothing.
		mockMvc.perform(post(ACK).contentType(MediaType.APPLICATION_JSON)
						.content("{\"eventIds\":[\"evt-0001\"]}"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("HUMAN_AUTH_REQUIRED"));
		assertThat(acknowledgedAt("evt-0001")).isNull();

		// A non-alarm event is never acknowledged.
		ack("[\"evt-0002\"]")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("ACK_EVENT_NOT_ALARM"));
		assertThat(acknowledgedAt("evt-0002")).isNull();

		// An unknown id rejects the whole set atomically: the known alarm stays unconfirmed.
		ack("[\"evt-0001\",\"evt-9999\"]")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("ACK_EVENT_UNKNOWN"));
		assertThat(acknowledgedAt("evt-0001")).isNull();

		// Malformed sets are rejected without any side effect.
		ack("[]")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("ACK_EVENTS_INVALID"));
		ack("[\"evt-0001\",\"evt-0001\"]")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("ACK_EVENTS_INVALID"));
		ack("[\"1\"]")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("ACK_EVENTS_INVALID"));

		// No unrelated event was marked and the queue is untouched.
		poll(0).andExpect(jsonPath("$.events[*].eventId", contains("evt-0001", "evt-0002")));
		assertThat(shiftEventCount()).isEqualTo(2);
		assertThat(jdbc.queryForObject(
				"SELECT count(*) FROM shift_event WHERE acknowledged_at IS NOT NULL", Long.class)).isZero();
	}

	@Test
	void acknowledgementSurvivesRestartAndKeepsFirstAttribution() throws Exception {
		Map<String, String> state = new LinkedHashMap<>();
		state.put("INC000021095706", incident("INC000021095706", 3, "Assigned", "Оплата"));
		state.put("INC000021095707", incident("INC000021095707", 3, "Assigned", "Оплата"));
		deliver(1, state);
		state.put("INC000021095706", incident("INC000021095706", 4, "Assigned", "Оплата"));
		deliver(2, state);
		state.put("INC000021095707", incident("INC000021095707", 4, "Assigned", "Оплата"));
		deliver(3, state);

		// First acknowledgement of A: the attribution and time are recorded once.
		ack("[\"evt-0001\"]")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.confirmedEventIds", contains("evt-0001")))
				.andExpect(jsonPath("$.events[*].eventId", contains("evt-0002")));
		long actorId = operatorId();
		Instant firstAcknowledgedAt = acknowledgedAt("evt-0001");
		assertThat(acknowledgedBy("evt-0001")).isEqualTo(actorId);

		// Restart: a fresh application context over the same database still sees the queue
		// without A, and the acknowledgement has survived with its author and time.
		var pool = (com.zaxxer.hikari.HikariDataSource) dataSource;
		try (var restarted = new SpringApplicationBuilder(DashboardApplication.class)
				.profiles("work").run("--server.port=0", "--itsm.worker.enabled=false",
						"--spring.datasource.url=" + pool.getJdbcUrl(),
						"--spring.datasource.username=" + pool.getUsername(),
						"--spring.datasource.password=" + pool.getPassword())) {
			var restartedQueue = restarted.getBean(ShiftEventService.class).events(0, 100).body();
			assertThat(restartedQueue.events()).hasSize(1);
			assertThat(restartedQueue.events().getFirst().eventId()).isEqualTo("evt-0002");
		}

		// A repeated request after the restart keeps the first author and time untouched.
		ack("[\"evt-0001\"]")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.confirmedEventIds", contains("evt-0001")))
				.andExpect(jsonPath("$.events[*].eventId", contains("evt-0002")));
		assertThat(acknowledgedBy("evt-0001")).isEqualTo(actorId);
		assertThat(acknowledgedAt("evt-0001")).isEqualTo(firstAcknowledgedAt);
	}

	private static String incident(String id, int priority, String state, String group) {
		boolean completed = COMPLETED.contains(state);
		return """
				{"id":"%s","sysId":"sys-%s","header":"Инцидент %s","description":"Сценарий KAN-29",
				 "productName":"Оплата заказов","priorityId":%d,"stateEnum":"%s","state":"%s",
				 "submitDate":"2026-11-21T09:00:00Z","lastAssignmentToTeamDateTime":"2026-11-21T09:00:00Z",
				 "deadline":"2026-11-21T12:00:00Z","assignedGroupName":"%s","assignedEmployee":"Дежурный",
				 "assignedEmployeeLogin":"duty","solution":%s,"completionDate":%s}
				""".formatted(id, id, id, priority, state, state, group,
				completed ? "\"Канал восстановлен\"" : "null",
				completed ? "\"2026-11-21T10:30:00Z\"" : "null");
	}

	/** Posts one full snapshot as a REST package and applies it through the worker transaction. */
	private void deliver(long version, Map<String, String> state) throws Exception {
		UUID batchId = UUID.randomUUID();
		mockMvc.perform(post(BATCH_PATH)
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + ItsmMachineJwtTestConfiguration.machineToken(jwtEncoder))
						.header(ItsmBatchController.IDEMPOTENCY_KEY_HEADER, batchId.toString())
						.header(ItsmBatchController.SOURCE_ID_HEADER, SOURCE)
						.header(ItsmBatchController.SOURCE_VERSION_HEADER, Long.toString(version))
						.contentType(MediaType.APPLICATION_JSON)
						.content("[" + String.join(",", state.values()) + "]"))
				.andExpect(status().isAccepted());
		var worker = new ItsmWorkerTransactionService(new ItsmWorkerSqlRepository(jdbc), transactions);
		var claimed = worker.claimAndProcess(publication, "ack-it");
		assertThat(claimed).isNotNull();
		assertThat(claimed.batchId()).isEqualTo(batchId);
	}

	private ResultActions poll(long sinceEventSeq) throws Exception {
		return mockMvc.perform(get(QUEUE).param("sinceEventSeq", Long.toString(sinceEventSeq))
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + humanToken())
						.accept(MediaType.APPLICATION_JSON))
				.andExpect(status().isOk());
	}

	private ResultActions ack(String eventIdsJson) throws Exception {
		return mockMvc.perform(post(ACK)
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + humanToken())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"eventIds\":" + eventIdsJson + "}"));
	}

	private static String humanToken() {
		return Kan16JwtTestConfiguration.signer().token(TestJwtSigner.claims()
				.issuer(HUMAN_ISSUER).audience(HUMAN_AUDIENCE).subject(HUMAN_SUBJECT)
				.claim("azp", "dashboard-web")
				.expiresAt(Instant.now().plusSeconds(300)));
	}

	private long operatorId() {
		return jdbc.queryForObject("SELECT id FROM users WHERE login = 'operator'", Long.class);
	}

	private Long acknowledgedBy(String eventId) {
		return jdbc.queryForObject("SELECT acknowledged_by FROM shift_event WHERE event_id = ?",
				Long.class, eventId);
	}

	private Instant acknowledgedAt(String eventId) {
		java.sql.Timestamp at = jdbc.queryForObject(
				"SELECT acknowledged_at FROM shift_event WHERE event_id = ?", java.sql.Timestamp.class, eventId);
		return at == null ? null : at.toInstant();
	}

	private long shiftEventCount() {
		return jdbc.queryForObject("SELECT count(*) FROM shift_event", Long.class);
	}
}
