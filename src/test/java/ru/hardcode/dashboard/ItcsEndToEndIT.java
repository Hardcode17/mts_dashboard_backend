package ru.hardcode.dashboard;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.function.Consumer;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import ru.hardcode.dashboard.client.KeycloakPkceClient;
import ru.hardcode.dashboard.client.KeycloakTestContainer;
import ru.hardcode.dashboard.controller.ItsmBatchController;
import ru.hardcode.dashboard.worker.ItsmWorkerRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.oneOf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * KAN-52 end-to-end acceptance: one command, one IT. PostgreSQL 17 and a real Keycloak are
 * started as isolated dependencies; the human token comes from a browser PKCE login and the
 * machine token from the sender client credentials, both issued by Keycloak. The fixed JSON
 * fixture travels through the same HTTP contract as the KAN-53 sender (Bearer machine JWT,
 * {@code Idempotency-Key}, {@code X-Source-Id}, {@code X-Source-Version}, 202 + Location),
 * and every step waits for a concrete batchId and its status — never a fixed sleep.
 *
 * <p>Positive: the 44-record fixture publishes four groups with the approved counters,
 * the card links into ITSM, an employee change appears only after its REST package, and the
 * escalation + coordinator assignment, «Увидел», Resolved and Research of one INC are read
 * back through the DTOs, the shift queue and the durable journal. Negative: broken tokens
 * and broken or invalid packages never replace the last successful snapshot; corrected data
 * applies afterwards. Boundary: ack(A,B) leaves a simultaneously arrived C, two workers
 * apply successive versions in order, and a restart followed by a replay neither rolls the
 * data back nor duplicates events. A separate scenario replays the changeable series of the
 * KAN-53 mock with its reproducible seed.
 */
@SpringBootTest(properties = {
		"itsm.worker.workers=2",
		"itsm.worker.poll-interval=PT0.05S",
		"itsm.worker.retry-base-delay=PT2S",
		"itsm.worker.max-attempts=2",
		"itsm.intake.max-outstanding-batches=10"
})
@AutoConfigureMockMvc
@ActiveProfiles("work")
@Import({ItsmPostgresTestcontainersConfiguration.class, KeycloakTestContainer.class})
class ItcsEndToEndIT {

	private static final String BATCH_PATH = "/api/integrations/itsm/batches";
	private static final String LIST_PATH = "/api/dashboard/incidents";
	private static final String CHANGES_PATH = "/api/dashboard/incidents/changes";
	private static final String QUEUE_PATH = "/api/dashboard/shift/events";
	private static final String ACK_PATH = QUEUE_PATH + "/ack";
	private static final String SOURCE_ID = "e2e-itcs";
	private static final String COORDINATORS = "КЦ ИТ Координаторы Массовых Инцидентов";
	private static final String LINK_PREFIX = "http://127.0.0.1:8082/itsm_incident/";
	private static final String KNOWN_INC = "INC000021931151";
	/** The only active P3 of the fixture: the escalation, Resolved and Research protagonist. */
	private static final String CRITICAL_TARGET = "INC000021938167";
	private static final String EMPLOYEE_TARGET = "INC000021933097";
	private static final String ACK_A = "INC000021930935";
	private static final String ACK_B = "INC000021931189";
	private static final String ACK_C = "INC000021931338";
	private static final String RESEARCH_SOLUTION = "Восстановление подтверждено на стенде.";
	private static final Set<String> FINISHED_STATES = Set.of("Resolved", "Closed", "Research");
	private static final List<String> SERIES_STATES = List.of(
			"Assigned", "WorkInProgress", "Resolved", "Closed", "Research");

	@Autowired private MockMvc mvc;
	@Autowired private JdbcTemplate jdbc;
	@Autowired private ObjectMapper json;
	@Autowired private ItsmWorkerRunner runner;
	@Autowired private DataSource dataSource;

	private KeycloakPkceClient keycloak;

	@DynamicPropertySource
	static void authentication(DynamicPropertyRegistry registry) {
		registry.add("app.auth.human.issuer", KeycloakTestContainer::issuer);
		registry.add("app.auth.human.jwk-set-uri", KeycloakTestContainer::jwkSetUri);
		registry.add("app.auth.human.audience", () -> KeycloakTestContainer.HUMAN_AUDIENCE);
		registry.add("itsm.integration.auth.issuer", KeycloakTestContainer::issuer);
		registry.add("itsm.integration.auth.jwk-set-uri", KeycloakTestContainer::jwkSetUri);
		registry.add("itsm.integration.auth.audience", () -> KeycloakTestContainer.MACHINE_AUDIENCE);
		registry.add("itsm.integration.auth.client-id", () -> KeycloakTestContainer.MACHINE_CLIENT_ID);
		registry.add("itsm.integration.auth.import-role", () -> KeycloakTestContainer.IMPORT_ROLE);
	}

	@BeforeEach
	void resetWorld() {
		runner.stop();
		jdbc.execute("""
				TRUNCATE itsm_ingest_batch, itsm_snapshot, itsm_snapshot_source, itsm_incident,
				    itsm_catalog_product, itsm_catalog_service, itsm_catalog_scenario,
				    itsm_external_group, itsm_external_person, itsm_ingest_catalog_hint,
				    itsm_incident_change, shift_event, shift_event_source RESTART IDENTITY CASCADE
				""");
		jdbc.execute("ALTER SEQUENCE shift_event_id_seq RESTART WITH 1");
		jdbc.execute("ALTER SEQUENCE itsm_incident_change_event_seq RESTART WITH 1");
		jdbc.update("UPDATE dashboard_publication SET event_seq = 0, revision = 0 WHERE singleton");
		jdbc.update("UPDATE shift_event_progress SET last_revision = 0 WHERE singleton");
		keycloak = new KeycloakPkceClient(KeycloakTestContainer.issuer());
		runner.start();
	}

	@AfterEach
	void stopWorker() {
		runner.stop();
	}

	@Test
	void restPackagePublishesGroupsCardJournalAndTheAcknowledgedShift() throws Exception {
		String human = humanToken();
		String machine = machineToken();
		List<Map<String, Object>> rows = fixtureRows();

		// The sender's REST package is accepted before the worker finishes: 202 + Location.
		UUID first = deliver(machine, UUID.randomUUID(), 1, snapshotJson(rows));
		mvc.perform(get(BATCH_PATH + "/" + first).header(HttpHeaders.AUTHORIZATION, "Bearer " + machine))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.batchId").value(first.toString()))
				.andExpect(jsonPath("$.status").value(oneOf("RECEIVED", "PROCESSING", "SUCCEEDED")));
		awaitBatch(machine, first, "SUCCEEDED");
		assertThat(count("itsm_incident")).isEqualTo(44);

		// Four groups with the approved counters on the published version.
		MvcResult list = mvc.perform(authenticatedGet(human, LIST_PATH))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.groups[*].key",
						contains("critical", "coordinator", "high", "research")))
				.andExpect(jsonPath("$.groups[*].incidentCount", contains(0, 2, 1, 5)))
				.andExpect(jsonPath("$.items", hasSize(29)))
				.andReturn();
		long revision = ((Number) com.jayway.jsonpath.JsonPath
				.read(content(list), "$.revision")).longValue();
		assertThat(revision).isPositive();

		// «Детальнее» leads into ITSM: the card carries the canonical external link.
		mvc.perform(authenticatedGet(human, LIST_PATH + "/" + KNOWN_INC))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(KNOWN_INC))
				.andExpect(jsonPath("$.itsmUrl").value(LINK_PREFIX + KNOWN_INC + "/page"))
				.andExpect(jsonPath("$.revision").value(revision));

		// The journal of the first publication is readable by cursor: every INC arrived once.
		mvc.perform(authenticatedGet(human, CHANGES_PATH).param("afterEventSeq", "0").param("limit", "100"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.events", hasSize(44)))
				.andExpect(jsonPath("$.events[*].changeKind",
						org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is("ADDED"))))
				.andExpect(jsonPath("$.hasMore").value(false))
				.andExpect(jsonPath("$.lastEventSeq").value(44));

		// An employee change reaches the card only through its REST package.
		Object employeeBefore = rowOf(rows, EMPLOYEE_TARGET).get("assignedEmployee");
		mvc.perform(authenticatedGet(human, LIST_PATH + "/" + EMPLOYEE_TARGET))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.assignedEmployee").value(employeeBefore == null ? null : employeeBefore.toString()));
		rows = withIncident(rows, EMPLOYEE_TARGET, incident -> {
			incident.put("assignedEmployee", "Сменный дежурный (demo2)");
			incident.put("assignedEmployeeLogin", "demo2");
		});
		deliverAndAwait(machine, 2, snapshotJson(rows));
		mvc.perform(authenticatedGet(human, LIST_PATH + "/" + EMPLOYEE_TARGET))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.assignedEmployee").value("Сменный дежурный (demo2)"));

		// Escalation to P4 and assignment to the coordinators in one update of one INC.
		rows = withIncident(rows, CRITICAL_TARGET, incident -> {
			incident.put("priorityId", 4);
			incident.put("priority", "Наивысший");
			incident.put("assignedGroupName", COORDINATORS);
		});
		deliverAndAwait(machine, 3, snapshotJson(rows));
		mvc.perform(authenticatedGet(human, LIST_PATH))
				.andExpect(jsonPath("$.groups[*].incidentCount", contains(1, 3, 0, 5)));
		MvcResult queue = mvc.perform(authenticatedGet(human, QUEUE_PATH + "?sinceEventSeq=0"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.events", hasSize(1)))
				.andExpect(jsonPath("$.events[0].eventId").value("evt-0001"))
				.andExpect(jsonPath("$.events[0].incidentId").value(CRITICAL_TARGET))
				.andExpect(jsonPath("$.events[0].reasons",
						contains("became_critical", "assigned_to_coordinators")))
				.andExpect(jsonPath("$.events[0].ackRequired").value(true))
				.andReturn();
		String alarmEventId = com.jayway.jsonpath.JsonPath.read(content(queue), "$.events[0].eventId");

		// «Увидел»: the shift confirms exactly the shown alarm event.
		acknowledge(human, "[\"" + alarmEventId + "\"]")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("acknowledged"))
				.andExpect(jsonPath("$.confirmedEventIds", contains(alarmEventId)))
				.andExpect(jsonPath("$.events").isEmpty());
		assertThat(jdbc.queryForObject(
				"SELECT acknowledged_by FROM shift_event WHERE event_id = ?", Long.class, alarmEventId))
				.isEqualTo(operatorId());
		assertThat(jdbc.queryForObject(
				"SELECT acknowledged_at FROM shift_event WHERE event_id = ?",
				java.sql.Timestamp.class, alarmEventId)).isNotNull();

		// An explicit Resolved of the critical incident, then Research.
		rows = withIncident(rows, CRITICAL_TARGET, incident -> {
			incident.put("stateEnum", "Resolved");
			incident.put("state", "Завершен");
			incident.put("completionDate", "2026-11-21T10:30:00Z");
			incident.put("solution", RESEARCH_SOLUTION);
		});
		deliverAndAwait(machine, 4, snapshotJson(rows));
		mvc.perform(authenticatedGet(human, LIST_PATH + "/" + CRITICAL_TARGET))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.stateEnum").value("Resolved"))
				.andExpect(jsonPath("$.solution").value(RESEARCH_SOLUTION))
				.andExpect(jsonPath("$.completionDate").value("2026-11-21T10:30:00Z"));
		rows = withIncident(rows, CRITICAL_TARGET, incident -> {
			incident.put("stateEnum", "Research");
			incident.put("state", "Исследование ситуации");
		});
		deliverAndAwait(machine, 5, snapshotJson(rows));
		mvc.perform(authenticatedGet(human, LIST_PATH + "/" + CRITICAL_TARGET))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.stateEnum").value("Research"));

		// The queue keeps only the resolution signal; the Research transition adds nothing.
		mvc.perform(authenticatedGet(human, QUEUE_PATH + "?sinceEventSeq=0"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.events[*].reasons", contains(contains("critical_resolved"))))
				.andExpect(jsonPath("$.events[*].ackRequired", contains(false)));
		assertThat(count("shift_event")).isEqualTo(2);

		// The journal of the protagonist: added once, then one event per REST update.
		MvcResult history = mvc.perform(authenticatedGet(human,
						LIST_PATH + "/" + CRITICAL_TARGET + "/history"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.incidentId").value(CRITICAL_TARGET))
				.andExpect(jsonPath("$.events", hasSize(4)))
				.andReturn();
		JsonNode events = json.readTree(content(history)).get("events");
		assertThat(events.get(0).get("changeKind").asString()).isEqualTo("ADDED");
		assertThat(events.get(1).get("changeKind").asString()).isEqualTo("UPDATED");
		assertThat(events.get(2).get("changeKind").asString()).isEqualTo("UPDATED");
		assertThat(events.get(3).get("changeKind").asString()).isEqualTo("UPDATED");
		assertThat(events.get(1).get("sourceVersion").asLong()).isEqualTo(3);
		assertThat(trackedValue(events.get(1), "priorityId", "current")).isEqualTo("4");
		assertThat(trackedValue(events.get(1), "assignedGroupName", "current")).isEqualTo(COORDINATORS);
		assertThat(trackedValue(events.get(3), "stateEnum", "current")).isEqualTo("Research");
		for (int index = 1; index < events.size(); index++) {
			assertThat(events.get(index).get("eventSeq").asLong())
					.isGreaterThan(events.get(index - 1).get("eventSeq").asLong());
		}
	}

	@Test
	void invalidTokensAndBrokenPackagesNeverReplaceTheLastSuccessfulSnapshot() throws Exception {
		String human = humanToken();
		String machine = machineToken();

		// A broken machine token is rejected before anything is accepted.
		mvc.perform(postSnapshot("not-a-jwt", UUID.randomUUID(), 1, fixtureJson()))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("ITSM_MACHINE_AUTH_REQUIRED"));
		assertThat(count("itsm_ingest_batch")).isZero();
		// A broken human token is rejected by every dashboard read.
		mvc.perform(get(LIST_PATH).header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt")
						.accept(MediaType.APPLICATION_JSON))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
		mvc.perform(get(QUEUE_PATH).param("sinceEventSeq", "0")
						.header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("HUMAN_AUTH_REQUIRED"));

		// The last successful snapshot stays the published truth after broken deliveries.
		deliverAndAwait(machine, 1, fixtureJson());
		long revision = jdbc.queryForObject("SELECT revision FROM dashboard_publication WHERE singleton",
				Long.class);
		// A damaged request is rejected at intake: 400 and not even a batch row.
		long batches = count("itsm_ingest_batch");
		mvc.perform(postSnapshot(machine, UUID.randomUUID(), 2, "]это не JSON"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("ITSM_INVALID_PAYLOAD"));
		assertThat(count("itsm_ingest_batch")).isEqualTo(batches);
		// A well-formed package with an invalid record is accepted and fails in the worker.
		UUID invalid = deliver(machine, UUID.randomUUID(), 2, "[{\"id\":\"INC000021931151\"}]");
		awaitBatch(machine, invalid, "FAILED");
		mvc.perform(authenticatedGet(human, LIST_PATH))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.groups[*].incidentCount", contains(0, 2, 1, 5)))
				.andExpect(jsonPath("$.items", hasSize(29)));
		assertThat(count("itsm_incident")).isEqualTo(44);
		assertThat(jdbc.queryForObject("SELECT revision FROM dashboard_publication WHERE singleton",
				Long.class)).isEqualTo(revision);

		// A corrected package applies new data after the failures.
		List<Map<String, Object>> rows = fixtureRows();
		rows = withIncident(rows, EMPLOYEE_TARGET, incident -> {
			incident.put("assignedEmployee", "Ночная смена (demo3)");
			incident.put("assignedEmployeeLogin", "demo3");
		});
		deliverAndAwait(machine, 3, snapshotJson(rows));
		mvc.perform(authenticatedGet(human, LIST_PATH + "/" + EMPLOYEE_TARGET))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.assignedEmployee").value("Ночная смена (demo3)"));
		assertThat(count("itsm_incident")).isEqualTo(44);
	}

	@Test
	void ackOfTwoAlarmEventsKeepsASimultaneouslyArrivedThird() throws Exception {
		String human = humanToken();
		String machine = machineToken();
		List<Map<String, Object>> rows = fixtureRows();
		deliverAndAwait(machine, 1, snapshotJson(rows));

		// A,B are raised to P4 in one package; the queue shows both alarm events.
		rows = raiseToCritical(rows, ACK_A);
		rows = raiseToCritical(rows, ACK_B);
		deliverAndAwait(machine, 2, snapshotJson(rows));
		mvc.perform(authenticatedGet(human, QUEUE_PATH + "?sinceEventSeq=0"))
				.andExpect(jsonPath("$.events[*].incidentId", contains(ACK_A, ACK_B)))
				.andExpect(jsonPath("$.events[*].reasons",
						contains(contains("became_critical"), contains("became_critical"))));

		// C arrives together with the acknowledgement of A,B: the exact set leaves C untouched.
		rows = raiseToCritical(rows, ACK_C);
		deliverAndAwait(machine, 3, snapshotJson(rows));
		String alarmA = eventIdOf(human, 1);
		String alarmB = eventIdOf(human, 2);
		String alarmC = eventIdOf(human, 3);
		acknowledge(human, "[\"" + alarmA + "\",\"" + alarmB + "\"]")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.confirmedEventIds", contains(alarmA, alarmB)))
				.andExpect(jsonPath("$.events[*].incidentId", contains(ACK_C)));
		// The repeated acknowledgement still never touches C.
		acknowledge(human, "[\"" + alarmA + "\",\"" + alarmB + "\"]")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.events[*].incidentId", contains(ACK_C)));
		assertThat(jdbc.queryForObject("""
				SELECT acknowledged_at FROM shift_event WHERE incident_id = ?
				""", java.sql.Timestamp.class, ACK_C)).isNull();
		// A fresh queue read no longer shows A,B.
		mvc.perform(authenticatedGet(human, QUEUE_PATH + "?sinceEventSeq=0"))
				.andExpect(jsonPath("$.events[*].incidentId", contains(ACK_C)));

		// C is acknowledged on its own; the queue is empty.
		acknowledge(human, "[\"" + alarmC + "\"]")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.events").isEmpty())
				.andExpect(jsonPath("$.nextEventSeq").value(0));
		assertThat(count("shift_event")).isEqualTo(3);
	}

	@Test
	void twoWorkersApplyVersionsInOrderAndRestartsDoNotRollBackOrDuplicate() throws Exception {
		String human = humanToken();
		String machine = machineToken();
		List<Map<String, Object>> rows = fixtureRows();
		deliverAndAwait(machine, 1, snapshotJson(rows));

		// Two versions are delivered back to back; two workers may finish them in any order,
		// but the publication applies them strictly by source version.
		rows = withIncident(rows, "INC000021935528", incident -> {
			incident.put("assignedEmployee", "Дневная смена (demo1)");
			incident.put("assignedEmployeeLogin", "demo1");
		});
		UUID second = deliver(machine, UUID.randomUUID(), 2, snapshotJson(rows));
		rows = withIncident(rows, "INC000021930935", incident -> {
			incident.put("priorityId", 4);
			incident.put("priority", "Наивысший");
		});
		UUID third = deliver(machine, UUID.randomUUID(), 3, snapshotJson(rows));
		awaitBatch(machine, second, "SUCCEEDED");
		awaitBatch(machine, third, "SUCCEEDED");
		assertThat(jdbc.queryForObject(
				"SELECT observed_version FROM itsm_snapshot_source WHERE source_id = ?",
				Long.class, SOURCE_ID)).isEqualTo(3);
		long seqOfSecond = jdbc.queryForObject(
				"SELECT event_seq FROM itsm_incident_change WHERE source_version = 2", Long.class);
		long seqOfThird = jdbc.queryForObject(
				"SELECT event_seq FROM itsm_incident_change WHERE source_version = 3", Long.class);
		assertThat(seqOfSecond).isLessThan(seqOfThird);
		assertThat(jdbc.queryForObject("""
				SELECT count(*) FROM itsm_incident_change WHERE source_version = 2
				""", Long.class)).isEqualTo(1);
		assertThat(jdbc.queryForObject("""
				SELECT count(*) FROM itsm_incident_change WHERE source_version = 3
				""", Long.class)).isEqualTo(1);
		mvc.perform(authenticatedGet(human, LIST_PATH + "/" + "INC000021930935"))
				.andExpect(jsonPath("$.priorityId").value(4));
		mvc.perform(authenticatedGet(human, QUEUE_PATH + "?sinceEventSeq=0"))
				.andExpect(jsonPath("$.events[*].incidentId", contains("INC000021930935")))
				.andExpect(jsonPath("$.events[*].reasons", contains(contains("became_critical"))));
		long journalRows = count("itsm_incident_change");
		long shiftEvents = count("shift_event");
		long revision = jdbc.queryForObject("SELECT revision FROM dashboard_publication WHERE singleton",
				Long.class);

		// Restart: a fresh application context over the same database keeps everything.
		var pool = (com.zaxxer.hikari.HikariDataSource) dataSource;
		try (var restarted = new SpringApplicationBuilder(DashboardApplication.class)
				.profiles("work").run("--server.port=0", "--itsm.worker.enabled=false",
						"--spring.datasource.url=" + pool.getJdbcUrl(),
						"--spring.datasource.username=" + pool.getUsername(),
						"--spring.datasource.password=" + pool.getPassword())) {
			var groups = restarted.getBean(ru.hardcode.dashboard.service.DashboardIncidentService.class)
					.list(List.of(), false, null, 50).body().groups();
			assertThat(groups).hasSize(4);
			assertThat(groups.stream().map(ru.hardcode.dashboard.dto.DashboardIncidentListResponse.Group::incidentCount)
					.toList()).containsExactly(1, 2, 1, 5);
			assertThat(restarted.getBean(ru.hardcode.dashboard.service.ShiftEventService.class)
					.events(0, 100).body().events()).hasSize(1);
		}

		// A replay of the same content after the restart changes nothing: no rollback, no
		// duplicated journal rows or shift events.
		deliverAndAwait(machine, 4, snapshotJson(rows));
		assertThat(count("itsm_incident_change")).isEqualTo(journalRows);
		assertThat(count("shift_event")).isEqualTo(shiftEvents);
		assertThat(jdbc.queryForObject("SELECT revision FROM dashboard_publication WHERE singleton",
				Long.class)).isEqualTo(revision);
		mvc.perform(authenticatedGet(human, LIST_PATH + "/" + "INC000021930935"))
				.andExpect(jsonPath("$.priorityId").value(4));

		// The same delivery replayed with its idempotency key returns the same batch.
		mvc.perform(postSnapshot(machine, third, 3, snapshotJson(rows)))
				.andExpect(status().isAccepted())
				.andExpect(header().string(HttpHeaders.LOCATION, BATCH_PATH + "/" + third))
				.andExpect(jsonPath("$.batchId").value(third.toString()));
	}

	@Test
	void reproducibleMockSeedSeriesAppliesExactlyTheJournaledChanges() throws Exception {
		String human = humanToken();
		String machine = machineToken();
		// The KAN-53 mock series algorithm (itsm-mock IncidentGenerator): every third delivery
		// repeats the full snapshot unchanged, otherwise one incident chosen by
		// SplittableRandom(seed ^ version) moves to the next state in the fixed cycle.
		long seed = 20261121L;
		Instant now = Instant.parse("2026-11-21T10:00:00Z");
		List<String> series = new ArrayList<>();
		List<String> changedIncidents = new ArrayList<>();
		List<Map<String, Object>> current = fixtureRows();
		series.add(snapshotJson(current));
		changedIncidents.add(null);
		for (long version = 2; version <= 7; version++) {
			var next = mockSeries(current, seed, version, now);
			series.add(snapshotJson(next));
			changedIncidents.add(changedBetween(current, next));
			current = next;
		}
		// The seed is reproducible: a second run of the same series is identical.
		List<String> replay = new ArrayList<>();
		List<Map<String, Object>> replayRows = fixtureRows();
		replay.add(snapshotJson(replayRows));
		for (long version = 2; version <= 7; version++) {
			replayRows = mockSeries(replayRows, seed, version, now);
			replay.add(snapshotJson(replayRows));
		}
		assertThat(replay).containsExactlyElementsOf(series);

		deliverAndAwait(machine, 1, series.getFirst());
		for (int version = 2; version <= 7; version++) {
			deliverAndAwait(machine, version, series.get(version - 1));
		}

		// Every third version is an unchanged full observation; the others move exactly one INC.
		for (int version = 2; version <= 7; version++) {
			long journaled = jdbc.queryForObject("""
					SELECT count(*) FROM itsm_incident_change WHERE source_version = ?
					""", Long.class, (long) version);
			if (version % 3 == 0) {
				assertThat(journaled).as("version %s repeats the snapshot unchanged", version).isZero();
			} else {
				assertThat(journaled).as("version %s changes exactly one INC", version).isEqualTo(1);
				String changed = jdbc.queryForObject("""
						SELECT incident_id FROM itsm_incident_change WHERE source_version = ?
						""", String.class, (long) version);
				assertThat(changed).isEqualTo(changedIncidents.get(version - 1));
			}
		}
		// The dashboard reflects the last state of every incident the series touched.
		for (int version = 2; version <= 7; version++) {
			String changed = changedIncidents.get(version - 1);
			if (changed == null) {
				continue;
			}
			String expected = trackedStateOf(current, changed);
			mvc.perform(authenticatedGet(human, LIST_PATH + "/" + changed))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.stateEnum").value(expected));
		}
		assertThat(count("shift_event")).isZero();
	}

	// --- KAN-53 mock series, mirrored from itsm-mock IncidentGenerator ---------------------

	/** The mock's changeable series: one incident per version, every third delivery unchanged. */
	private static List<Map<String, Object>> mockSeries(List<Map<String, Object>> previous,
			long seed, long version, Instant now) {
		if (version % 3 == 0) {
			return previous;
		}
		var rows = new ArrayList<>(previous);
		var random = new SplittableRandom(seed ^ version);
		int index = random.nextInt(rows.size());
		var incident = new LinkedHashMap<>(rows.get(index));
		String current = String.valueOf(incident.get("stateEnum"));
		String next = SERIES_STATES.get((SERIES_STATES.indexOf(current) + 1) % SERIES_STATES.size());
		applySeriesState(incident, next, now);
		rows.set(index, incident);
		return rows;
	}

	private static void applySeriesState(Map<String, Object> incident, String state, Instant now) {
		incident.put("stateEnum", state);
		boolean finished = FINISHED_STATES.contains(state);
		if (finished && incident.get("completionDate") == null) {
			incident.put("completionDate", now.toString());
		} else if (!finished) {
			incident.put("completionDate", null);
		}
		incident.put("solution", finished ? RESEARCH_SOLUTION : "");
	}

	// --- helpers ---------------------------------------------------------------------------

	private static String changedBetween(List<Map<String, Object>> before, List<Map<String, Object>> after) {
		for (int index = 0; index < before.size(); index++) {
			if (!before.get(index).equals(after.get(index))) {
				return String.valueOf(after.get(index).get("id"));
			}
		}
		return null;
	}

	private static String trackedStateOf(List<Map<String, Object>> rows, String incidentId) {
		return String.valueOf(rowOf(rows, incidentId).get("stateEnum"));
	}

	private static List<Map<String, Object>> raiseToCritical(List<Map<String, Object>> rows, String incidentId) {
		return withIncident(rows, incidentId, incident -> {
			incident.put("priorityId", 4);
			incident.put("priority", "Наивысший");
		});
	}

	private static List<Map<String, Object>> withIncident(List<Map<String, Object>> rows, String incidentId,
			Consumer<Map<String, Object>> change) {
		var copy = new ArrayList<Map<String, Object>>(rows);
		for (int index = 0; index < copy.size(); index++) {
			if (incidentId.equals(copy.get(index).get("id"))) {
				var incident = new LinkedHashMap<>(copy.get(index));
				change.accept(incident);
				copy.set(index, incident);
				return copy;
			}
		}
		throw new IllegalArgumentException("Unknown incident " + incidentId);
	}

	private static Map<String, Object> rowOf(List<Map<String, Object>> rows, String incidentId) {
		return rows.stream().filter(row -> incidentId.equals(row.get("id"))).findFirst().orElseThrow();
	}

	private String fixtureJson() throws IOException {
		try (var input = getClass().getResourceAsStream("/itsm/character_problems.json")) {
			assertThat(input).isNotNull();
			return new String(input.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	private List<Map<String, Object>> fixtureRows() throws IOException {
		return json.readValue(fixtureJson(), new TypeReference<List<Map<String, Object>>>() {});
	}

	private String snapshotJson(List<Map<String, Object>> rows) {
		return json.writeValueAsString(rows);
	}

	private String humanToken() throws Exception {
		String token = keycloak.login(KeycloakTestContainer.TEST_USERNAME, KeycloakTestContainer.TEST_PASSWORD);
		assertThat(token).isNotBlank();
		try {
			String subject = com.nimbusds.jwt.SignedJWT.parse(token).getJWTClaimsSet().getSubject();
			jdbc.update("""
					INSERT INTO users(identity_issuer, identity_subject, login, display_name)
					VALUES (?, ?, ?, 'Coordinator IT')
					ON CONFLICT (identity_issuer, identity_subject) DO NOTHING
					""", KeycloakTestContainer.issuer(), subject, KeycloakTestContainer.TEST_USERNAME);
		} catch (java.text.ParseException unreadable) {
			throw new IllegalStateException("Unreadable Keycloak token", unreadable);
		}
		return token;
	}

	private String machineToken() {
		return KeycloakPkceClient.accessToken(keycloak.clientCredentials(
				KeycloakTestContainer.MACHINE_CLIENT_ID, KeycloakTestContainer.ITSM_SENDER_SECRET));
	}

	private long operatorId() {
		return jdbc.queryForObject("SELECT id FROM users WHERE login = ?",
				Long.class, KeycloakTestContainer.TEST_USERNAME);
	}

	private UUID deliver(String machine, UUID batch, long version, String body) throws Exception {
		mvc.perform(postSnapshot(machine, batch, version, body))
				.andExpect(status().isAccepted())
				.andExpect(header().string(HttpHeaders.LOCATION, BATCH_PATH + "/" + batch))
				.andExpect(jsonPath("$.batchId").value(batch.toString()));
		return batch;
	}

	private void deliverAndAwait(String machine, long version, String body) throws Exception {
		awaitBatch(machine, deliver(machine, UUID.randomUUID(), version, body), "SUCCEEDED");
	}

	/** Waits for the concrete batchId to reach the expected status through the REST status. */
	private void awaitBatch(String machine, UUID batchId, String expected) throws Exception {
		long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
		String status = null;
		while (System.nanoTime() < deadline) {
			MvcResult result = mvc.perform(get(BATCH_PATH + "/" + batchId)
							.header(HttpHeaders.AUTHORIZATION, "Bearer " + machine))
					.andExpect(status().isOk()).andReturn();
			status = com.jayway.jsonpath.JsonPath.read(content(result), "$.status");
			if (expected.equals(status)) {
				return;
			}
			if ("FAILED".equals(status)) {
				break;
			}
			Thread.sleep(25);
		}
		throw new AssertionError("batch " + batchId + " did not reach " + expected + ", last status " + status);
	}

	private String eventIdOf(String human, int position) throws Exception {
		MvcResult queue = mvc.perform(authenticatedGet(human, QUEUE_PATH + "?sinceEventSeq=0")).andReturn();
		return com.jayway.jsonpath.JsonPath.read(content(queue), "$.events[" + (position - 1) + "].eventId");
	}

	private org.springframework.test.web.servlet.ResultActions acknowledge(String human, String eventIdsJson)
			throws Exception {
		return mvc.perform(post(ACK_PATH)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + human)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"eventIds\":" + eventIdsJson + "}"));
	}

	private MockHttpServletRequestBuilder postSnapshot(String token, UUID batch, long version, String body) {
		return post(BATCH_PATH)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.header(ItsmBatchController.IDEMPOTENCY_KEY_HEADER, batch.toString())
				.header(ItsmBatchController.SOURCE_ID_HEADER, SOURCE_ID)
				.header(ItsmBatchController.SOURCE_VERSION_HEADER, Long.toString(version))
				.contentType(MediaType.APPLICATION_JSON)
				.content(body);
	}

	private MockHttpServletRequestBuilder authenticatedGet(String token, String path, Object... variables) {
		return get(path, variables).accept(MediaType.APPLICATION_JSON)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
	}

	private static String content(MvcResult result) throws java.io.UnsupportedEncodingException {
		return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
	}

	private long count(String table) {
		return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
	}

	private Object trackedValue(JsonNode event, String field, String side) {
		for (JsonNode tracked : event.get("fields")) {
			if (field.equals(tracked.get("field").asString())) {
				JsonNode value = tracked.get(side);
				return value == null || value.isNull() ? null : value.asText();
			}
		}
		throw new AssertionError("field " + field + " is not tracked by event");
	}
}
