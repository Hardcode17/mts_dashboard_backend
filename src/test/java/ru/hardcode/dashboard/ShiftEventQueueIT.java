package ru.hardcode.dashboard;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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
 * KAN-25 acceptance of the significant shift event queue. The real intake endpoint receives a
 * REST package under machine authentication, the publication journal is applied through the
 * production worker transaction, and the shift reads the queue through the human coordinator
 * chain (MockMvc over the real filter chain, PostgreSQL 17 via Testcontainers).
 *
 * <p>Positive: a new active P4, an escalation to P4, an entry on the coordinators, an explicit
 * Resolved of a critical and its reopening arrive through polling with their INC numbers,
 * reasons and eventIds. Negative: the first baseline snapshot, a vanished row and a
 * description-only change never raise a signal. Boundary: escalation and coordinator
 * assignment inside one revision merge into one eventId with both reasons, a repeated
 * package and a replayed cursor add nothing, and a later opening of the same INC gets a new
 * eventId. Acknowledgement itself belongs to KAN-29 and is not part of this test.
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
class ShiftEventQueueIT {

	private static final String SOURCE = "shift-itcs";
	private static final String BATCH_PATH = "/api/integrations/itsm/batches";
	private static final String QUEUE = "/api/dashboard/shift/events";
	private static final String COORDINATORS = "КЦ ИТ Координаторы Массовых Инцидентов";
	private static final String HUMAN_ISSUER = "https://keycloak.test/realms/dashboard";
	private static final String HUMAN_AUDIENCE = "dashboard-human";
	private static final String HUMAN_SUBJECT = "kan25-shift-operator";
	private static final Set<String> COMPLETED = Set.of("Resolved", "Closed", "Research");

	@Autowired MockMvc mockMvc;
	@Autowired JdbcTemplate jdbc;
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
				VALUES (?, ?, 'operator', 'Shift Operator') ON CONFLICT DO NOTHING
				""", HUMAN_ISSUER, HUMAN_SUBJECT);
	}

	@Test
	void pollingReturnsQueueEventsForEverySignificantTransition() throws Exception {
		Map<String, String> state = new LinkedHashMap<>();
		state.put("INC000021095601", incident("INC000021095601", 3, "Assigned", "Оплата"));
		state.put("INC000021095602", incident("INC000021095602", 4, "Assigned", "Оплата"));
		state.put("INC000021095603", incident("INC000021095603", 3, "Assigned", "Поддержка"));
		state.put("INC000021095604", incident("INC000021095604", 4, "Assigned", "Оплата"));
		// The first full snapshot is the baseline of the source: even its active P4 raises no signal.
		deliver(1, state);
		poll(0).andExpect(jsonPath("$.events").isEmpty()).andExpect(jsonPath("$.nextEventSeq").value(0));
		assertThat(shiftEventCount()).isZero();

		// A new active P4 observed after the baseline is the first alarm event.
		state.put("INC000021095607", incident("INC000021095607", 4, "WorkInProgress", "Оплата"));
		deliver(2, state);
		long revision = journalRevision("INC000021095607");
		poll(0)
				.andExpect(jsonPath("$.events", hasSize(1)))
				.andExpect(jsonPath("$.events[0].eventId").value("evt-0001"))
				.andExpect(jsonPath("$.events[0].eventSeq").value(1))
				.andExpect(jsonPath("$.events[0].incidentId").value("INC000021095607"))
				.andExpect(jsonPath("$.events[0].reasons", contains("new_critical")))
				.andExpect(jsonPath("$.events[0].ackRequired").value(true))
				.andExpect(jsonPath("$.events[0].changeRevision").value((int) revision))
				.andExpect(jsonPath("$.events[0].sourceId").value(SOURCE))
				.andExpect(jsonPath("$.events[0].occurredAt").isNotEmpty())
				.andExpect(jsonPath("$.events[0].incident.id").value("INC000021095607"))
				.andExpect(jsonPath("$.events[0].incident.priorityId").value(4))
				.andExpect(jsonPath("$.events[0].incident.stateEnum").value("WorkInProgress"))
				.andExpect(jsonPath("$.events[0].incident.assignedGroupName").value("Оплата"))
				.andExpect(jsonPath("$.events[0].incident.deadline").value("2026-11-21T12:00:00Z"))
				.andExpect(jsonPath("$.nextEventSeq").value(1));
		assertThat(shiftEventCount()).isEqualTo(1);
		assertThat(reasonsOf("INC000021095607")).containsExactly("new_critical");
		assertThat(ackRequiredOf("INC000021095607")).isTrue();

		// Raising an active incident to P4 is the second alarm event.
		state.put("INC000021095601", incident("INC000021095601", 4, "Assigned", "Оплата"));
		deliver(3, state);
		poll(1)
				.andExpect(jsonPath("$.events", hasSize(1)))
				.andExpect(jsonPath("$.events[0].eventId").value("evt-0002"))
				.andExpect(jsonPath("$.events[0].incidentId").value("INC000021095601"))
				.andExpect(jsonPath("$.events[0].reasons", contains("became_critical")))
				.andExpect(jsonPath("$.events[0].ackRequired").value(true))
				.andExpect(jsonPath("$.nextEventSeq").value(2));

		// Entering the ITSM coordinator group is a queue signal that requires no «Увидел».
		state.put("INC000021095603", incident("INC000021095603", 3, "Assigned", COORDINATORS));
		deliver(4, state);
		poll(2)
				.andExpect(jsonPath("$.events", hasSize(1)))
				.andExpect(jsonPath("$.events[0].eventId").value("evt-0003"))
				.andExpect(jsonPath("$.events[0].incidentId").value("INC000021095603"))
				.andExpect(jsonPath("$.events[0].reasons", contains("assigned_to_coordinators")))
				.andExpect(jsonPath("$.events[0].ackRequired").value(false))
				.andExpect(jsonPath("$.nextEventSeq").value(3));

		// An explicit Resolved of a critical incident carries the solution in the notification data.
		state.put("INC000021095604", incident("INC000021095604", 4, "Resolved", "Оплата"));
		deliver(5, state);
		poll(3)
				.andExpect(jsonPath("$.events", hasSize(1)))
				.andExpect(jsonPath("$.events[0].eventId").value("evt-0004"))
				.andExpect(jsonPath("$.events[0].incidentId").value("INC000021095604"))
				.andExpect(jsonPath("$.events[0].reasons", contains("critical_resolved")))
				.andExpect(jsonPath("$.events[0].ackRequired").value(false))
				.andExpect(jsonPath("$.events[0].incident.solution").value("Канал восстановлен"))
				.andExpect(jsonPath("$.events[0].incident.completionDate").value("2026-11-21T10:30:00Z"))
				.andExpect(jsonPath("$.nextEventSeq").value(4));

		// Returning the critical incident to work is a new alarm event with a new eventId.
		state.put("INC000021095604", incident("INC000021095604", 4, "WorkInProgress", "Оплата"));
		deliver(6, state);
		poll(4)
				.andExpect(jsonPath("$.events", hasSize(1)))
				.andExpect(jsonPath("$.events[0].eventId").value("evt-0005"))
				.andExpect(jsonPath("$.events[0].incidentId").value("INC000021095604"))
				.andExpect(jsonPath("$.events[0].reasons", contains("critical_reopened")))
				.andExpect(jsonPath("$.events[0].ackRequired").value(true))
				.andExpect(jsonPath("$.events[0].incident.stateEnum").value("WorkInProgress"))
				.andExpect(jsonPath("$.nextEventSeq").value(5));

		// The whole shift reads back in order and a caught-up poll returns nothing new.
		poll(0)
				.andExpect(jsonPath("$.events", hasSize(5)))
				.andExpect(jsonPath("$.events[*].eventId", contains(
						"evt-0001", "evt-0002", "evt-0003", "evt-0004", "evt-0005")))
				.andExpect(jsonPath("$.nextEventSeq").value(5));
		poll(5)
				.andExpect(jsonPath("$.events").isEmpty())
				.andExpect(jsonPath("$.nextEventSeq").value(5));
		assertThat(shiftEventCount()).isEqualTo(5);
	}

	@Test
	void baselineVanishedRowAndDescriptionOnlyChangeRaiseNoShiftSignal() throws Exception {
		Map<String, String> state = new LinkedHashMap<>();
		state.put("INC000021095601", incident("INC000021095601", 3, "Assigned", "Оплата"));
		state.put("INC000021095602", incident("INC000021095602", 4, "Assigned", "Оплата"));
		state.put("INC000021095605", incident("INC000021095605", 3, "Assigned", "Логистика"));
		deliver(1, state);
		poll(0).andExpect(jsonPath("$.events").isEmpty());
		assertThat(shiftEventCount()).isZero();

		// Only the header changed: the journal records the update, the queue stays silent.
		state.put("INC000021095602", incident("INC000021095602", 4, "Assigned", "Оплата", "Обновлённый заголовок"));
		deliver(2, state);
		assertThat(jdbc.queryForObject("""
				SELECT count(*) FROM itsm_incident_change
				WHERE incident_id = 'INC000021095602' AND change_kind = 'UPDATED'
				""", Long.class)).isEqualTo(1);
		poll(0).andExpect(jsonPath("$.events").isEmpty());
		assertThat(shiftEventCount()).isZero();

		// A row vanishing from the full snapshot never raises a critical signal.
		state.remove("INC000021095605");
		deliver(3, state);
		assertThat(jdbc.queryForObject("""
				SELECT count(*) FROM itsm_incident_change
				WHERE incident_id = 'INC000021095605' AND change_kind = 'MISSING'
				""", Long.class)).isEqualTo(1);
		poll(0).andExpect(jsonPath("$.events").isEmpty());
		assertThat(shiftEventCount()).isZero();
	}

	@Test
	void escalationAndCoordinatorAssignmentInOneRevisionMergeIntoOneEventWithBothReasons() throws Exception {
		Map<String, String> state = new LinkedHashMap<>();
		state.put("INC000021095606", incident("INC000021095606", 3, "Assigned", "Логистика"));
		deliver(1, state);
		poll(0).andExpect(jsonPath("$.events").isEmpty());

		// One update of one INC: raised to P4 and assigned to the coordinators at the same time.
		state.put("INC000021095606", incident("INC000021095606", 4, "Assigned", COORDINATORS));
		deliver(2, state);
		long revision = journalRevision("INC000021095606");
		poll(0)
				.andExpect(jsonPath("$.events", hasSize(1)))
				.andExpect(jsonPath("$.events[0].eventId").value("evt-0001"))
				.andExpect(jsonPath("$.events[0].eventSeq").value(1))
				.andExpect(jsonPath("$.events[0].incidentId").value("INC000021095606"))
				.andExpect(jsonPath("$.events[0].reasons", contains("became_critical", "assigned_to_coordinators")))
				.andExpect(jsonPath("$.events[0].ackRequired").value(true))
				.andExpect(jsonPath("$.events[0].changeRevision").value((int) revision))
				.andExpect(jsonPath("$.nextEventSeq").value(1));
		assertThat(shiftEventCount()).isEqualTo(1);
		assertThat(reasonsOf("INC000021095606")).containsExactly("became_critical", "assigned_to_coordinators");

		// Repeating the same package changes nothing: one eventId with both reasons survives.
		deliver(3, state);
		poll(1).andExpect(jsonPath("$.events").isEmpty());
		assertThat(shiftEventCount()).isEqualTo(1);
		assertThat(eventIdOf("INC000021095606")).isEqualTo("evt-0001");

		// A resolution and a later reopening of the same INC are new revisions with new eventIds.
		state.put("INC000021095606", incident("INC000021095606", 4, "Resolved", COORDINATORS));
		deliver(4, state);
		state.put("INC000021095606", incident("INC000021095606", 4, "WorkInProgress", COORDINATORS));
		deliver(5, state);
		poll(1)
				.andExpect(jsonPath("$.events", hasSize(2)))
				.andExpect(jsonPath("$.events[0].eventId").value("evt-0002"))
				.andExpect(jsonPath("$.events[0].reasons", contains("critical_resolved")))
				.andExpect(jsonPath("$.events[1].eventId").value("evt-0003"))
				.andExpect(jsonPath("$.events[1].reasons", contains("critical_reopened")))
				.andExpect(jsonPath("$.nextEventSeq").value(3));
		assertThat(shiftEventCount()).isEqualTo(3);

		// A lost cursor replays the journal: every existing revision keeps its event and
		// eventId (a conflicting replay only consumes an unused sequence number), and no
		// queue row is added or changed.
		jdbc.update("UPDATE shift_event_progress SET last_revision = 0 WHERE singleton");
		poll(0)
				.andExpect(jsonPath("$.events", hasSize(3)))
				.andExpect(jsonPath("$.events[*].eventId", contains("evt-0001", "evt-0002", "evt-0003")))
				.andExpect(jsonPath("$.events[0].reasons", contains("became_critical", "assigned_to_coordinators")))
				.andExpect(jsonPath("$.nextEventSeq").value(3));
		assertThat(shiftEventCount()).isEqualTo(3);
		assertThat(jdbc.queryForObject("""
				SELECT event_id FROM shift_event
				WHERE incident_id = ? AND reasons @> ARRAY['became_critical', 'assigned_to_coordinators']
				""", String.class, "INC000021095606")).isEqualTo("evt-0001");
	}

	@Test
	void queuePollValidatesParametersAndRequiresHumanToken() throws Exception {
		mockMvc.perform(get(QUEUE).accept(MediaType.APPLICATION_JSON))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("HUMAN_AUTH_REQUIRED"))
				.andExpect(jsonPath("$.message").isNotEmpty());

		// Parameter validation happens behind authentication: these requests carry a valid
		// human token, while the request without any token stays rejected by the chain itself.
		pollRaw(null)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_EVENT_SEQ"));
		pollRaw("abc")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_EVENT_SEQ"));
		pollRaw("-1")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_EVENT_SEQ"));
		pollRaw("99999999999999999999")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_EVENT_SEQ"));
		pollWithLimit("0")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_EVENT_LIMIT"));
		pollWithLimit("501")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_EVENT_LIMIT"));

		// A valid poll of the empty queue is a clean page.
		poll(0)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.events").isEmpty())
				.andExpect(jsonPath("$.nextEventSeq").value(0));
	}

	private static String incident(String id, int priority, String state, String group) {
		return incident(id, priority, state, group, "Инцидент " + id);
	}

	private static String incident(String id, int priority, String state, String group, String header) {
		boolean completed = COMPLETED.contains(state);
		return """
				{"id":"%s","sysId":"sys-%s","header":"%s","description":"Сценарий KAN-25",
				 "productName":"Оплата заказов","priorityId":%d,"stateEnum":"%s","state":"%s",
				 "submitDate":"2026-11-21T09:00:00Z","lastAssignmentToTeamDateTime":"2026-11-21T09:00:00Z",
				 "deadline":"2026-11-21T12:00:00Z","assignedGroupName":"%s","assignedEmployee":"Дежурный",
				 "assignedEmployeeLogin":"duty","solution":%s,"completionDate":%s}
				""".formatted(id, id, header, priority, state, state, group,
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
		var claimed = worker.claimAndProcess(publication, "shift-it");
		assertThat(claimed).isNotNull();
		assertThat(claimed.batchId()).isEqualTo(batchId);
	}

	private ResultActions poll(long sinceEventSeq) throws Exception {
		return mockMvc.perform(get(QUEUE).param("sinceEventSeq", Long.toString(sinceEventSeq))
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + humanToken())
						.accept(MediaType.APPLICATION_JSON))
				.andExpect(status().isOk());
	}

	private ResultActions pollRaw(String sinceEventSeq) throws Exception {
		var request = get(QUEUE).accept(MediaType.APPLICATION_JSON)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + humanToken());
		if (sinceEventSeq != null) {
			request.param("sinceEventSeq", sinceEventSeq);
		}
		return mockMvc.perform(request);
	}

	private ResultActions pollWithLimit(String limit) throws Exception {
		return mockMvc.perform(get(QUEUE).param("sinceEventSeq", "0").param("limit", limit)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + humanToken())
				.accept(MediaType.APPLICATION_JSON));
	}

	private static String humanToken() {
		return Kan16JwtTestConfiguration.signer().token(TestJwtSigner.claims()
				.issuer(HUMAN_ISSUER).audience(HUMAN_AUDIENCE).subject(HUMAN_SUBJECT)
				.claim("azp", "dashboard-web")
				.expiresAt(Instant.now().plusSeconds(300)));
	}

	private long shiftEventCount() {
		return jdbc.queryForObject("SELECT count(*) FROM shift_event", Long.class);
	}

	private List<String> reasonsOf(String incidentId) {
		return jdbc.queryForObject("SELECT reasons FROM shift_event WHERE incident_id = ?",
				(rs, n) -> List.of((String[]) rs.getArray(1).getArray()), incidentId);
	}

	private boolean ackRequiredOf(String incidentId) {
		return jdbc.queryForObject("SELECT ack_required FROM shift_event WHERE incident_id = ?",
				Boolean.class, incidentId);
	}

	private String eventIdOf(String incidentId) {
		return jdbc.queryForObject("SELECT event_id FROM shift_event WHERE incident_id = ?",
				String.class, incidentId);
	}

	private long journalRevision(String incidentId) {
		return jdbc.queryForObject("SELECT max(change_revision) FROM itsm_incident_change WHERE incident_id = ?",
				Long.class, incidentId);
	}
}
