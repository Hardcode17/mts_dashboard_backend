package ru.hardcode.dashboard;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MvcResult;
import ru.hardcode.dashboard.client.ItsmMachineJwtTestConfiguration;
import ru.hardcode.dashboard.dto.IncidentChangeEvent;
import ru.hardcode.dashboard.dto.IncidentChangeField;
import ru.hardcode.dashboard.dto.IncidentChangesPage;
import ru.hardcode.dashboard.dto.IncidentHistoryResponse;
import ru.hardcode.dashboard.entity.ItsmIncidentState;
import ru.hardcode.dashboard.worker.ItsmWorkerRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * KAN-28 acceptance: the INC change journal commits old and new values of every tracked field
 * with the observation time and the optional source time, and the journal is readable by a
 * monotonic {@code eventSeq} cursor that is independent of the change revision. The chain is
 * the real filter chain: snapshots enter through the machine-authenticated intake endpoint,
 * the production worker publishes them, and the journal is read through the human chain.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = {
		"itsm.integration.auth.issuer=" + ItsmMachineJwtTestConfiguration.ISSUER,
		"itsm.integration.auth.jwk-set-uri=https://keycloak.test/realms/itsm/protocol/openid-connect/certs",
		"itsm.integration.auth.audience=" + ItsmMachineJwtTestConfiguration.AUDIENCE,
		"itsm.integration.auth.client-id=" + ItsmMachineJwtTestConfiguration.CLIENT_ID,
		"itsm.integration.auth.import-role=" + ItsmMachineJwtTestConfiguration.IMPORT_ROLE,
		"itsm.worker.workers=1",
		"itsm.worker.poll-interval=PT0.05S",
		"itsm.worker.retry-base-delay=PT2S",
		"itsm.worker.max-attempts=2",
		"itsm.intake.max-outstanding-batches=3",
		"spring.security.user.name=operator",
		"spring.security.user.password=history-test"
})
@AutoConfigureMockMvc
@ActiveProfiles("work")
@Import({ItsmPostgresTestcontainersConfiguration.class, ItsmMachineJwtTestConfiguration.class})
class IncidentChangeHistoryIT extends ItsmWorkerTestSupport {

	private static final String SOURCE = "history-source";
	private static final String A = "INC000000000001";
	private static final String B = "INC000000000002";
	private static final String C = "INC000000000003";
	private static final String OBSERVED_AT_V1 = "2026-11-21T05:00:00Z";
	private static final String OBSERVED_AT_V2 = "2026-11-21T06:30:00Z";

	@Autowired
	private ItsmWorkerRunner runner;

	@BeforeEach
	void reset() {
		jdbcTemplate.update("""
				INSERT INTO users(identity_issuer,identity_subject,login,display_name)
				VALUES ('https://stand.test','operator','operator','Operator') ON CONFLICT DO NOTHING
				""");
		runner.stop();
		resetDatabaseAndProcessor();
		jdbcTemplate.execute("TRUNCATE itsm_snapshot, itsm_incident_change, itsm_incident, itsm_snapshot_source CASCADE");
	}

	@Test
	void historyCarriesEveryTrackedFieldWithSourceObservationAndEventOrder() throws Exception {
		runner.start();
		acceptSnapshot(BATCH_A, 1, snapshot(
				inc(A, "Assigned", 4, "2026-11-25T02:00:00Z", "Team A", "Alex", null, OBSERVED_AT_V1),
				inc(B, "Assigned", 5, null, "Team B", "Bob", null, OBSERVED_AT_V2)));
		awaitSuccess(BATCH_A);
		acceptSnapshot(BATCH_B, 2, snapshot(
				inc(A, "Resolved", 3, "2026-11-26T03:00:00Z", "Team B", "Boris", "Fixed the incident",
						OBSERVED_AT_V2),
				inc(B, "Assigned", 5, null, "Team B", "Bob", null, OBSERVED_AT_V2)));
		awaitSuccess(BATCH_B);

		mockMvc.perform(get("/api/dashboard/incidents/{id}/history", A).with(human()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.incidentId").value(A))
				.andExpect(jsonPath("$.lastObservedAt").isNotEmpty())
				.andExpect(jsonPath("$.events.length()").value(2))
				.andExpect(jsonPath("$.events[0].changeKind").value("ADDED"))
				.andExpect(jsonPath("$.events[0].sourceId").value(SOURCE))
				.andExpect(jsonPath("$.events[0].sourceVersion").value(1))
				.andExpect(jsonPath("$.events[0].sourceTime").value(OBSERVED_AT_V1))
				.andExpect(jsonPath("$.events[0].fields.length()").value(6))
				.andExpect(jsonPath("$.events[0].fields[?(@.field == 'stateEnum')].previous").value(hasItem(nullValue())))
				.andExpect(jsonPath("$.events[0].fields[?(@.field == 'stateEnum')].current").value("Assigned"))
				.andExpect(jsonPath("$.events[1].changeKind").value("UPDATED"))
				.andExpect(jsonPath("$.events[1].sourceVersion").value(2))
				.andExpect(jsonPath("$.events[1].sourceTime").value(OBSERVED_AT_V2))
				.andExpect(jsonPath("$.events[1].fields[?(@.field == 'stateEnum')].previous").value("Assigned"))
				.andExpect(jsonPath("$.events[1].fields[?(@.field == 'stateEnum')].current").value("Resolved"))
				.andExpect(jsonPath("$.events[1].fields[?(@.field == 'assignedGroupName')].previous").value("Team A"))
				.andExpect(jsonPath("$.events[1].fields[?(@.field == 'assignedGroupName')].current").value("Team B"))
				.andExpect(jsonPath("$.events[1].fields[?(@.field == 'assignedEmployee')].previous").value("Alex"))
				.andExpect(jsonPath("$.events[1].fields[?(@.field == 'assignedEmployee')].current").value("Boris"))
				.andExpect(jsonPath("$.events[1].fields[?(@.field == 'priorityId')].previous").value(4))
				.andExpect(jsonPath("$.events[1].fields[?(@.field == 'priorityId')].current").value(3))
				.andExpect(jsonPath("$.events[1].fields[?(@.field == 'deadline')].previous").value("2026-11-25T02:00:00Z"))
				.andExpect(jsonPath("$.events[1].fields[?(@.field == 'deadline')].current").value("2026-11-26T03:00:00Z"))
				.andExpect(jsonPath("$.events[1].fields[?(@.field == 'solution')].previous").value(hasItem(nullValue())))
				.andExpect(jsonPath("$.events[1].fields[?(@.field == 'solution')].current").value("Fixed the incident"));

		var feed = readFeed(0, 100);
		assertThat(feed.events()).hasSize(3);
		assertThat(feed.events()).extracting(IncidentChangeEvent::incidentId)
				.containsExactly(A, B, A);
		assertThat(feed.events()).extracting(IncidentChangeEvent::changeKind)
				.containsExactly("ADDED", "ADDED", "UPDATED");
		// The event sequence is monotonic and independent of the revision: one revision
		// carries both events of the first observation, and every event has its own seq.
		assertThat(feed.events()).extracting(IncidentChangeEvent::eventSeq)
				.isSorted().doesNotHaveDuplicates();
		assertThat(feed.events().get(0).changeRevision()).isEqualTo(feed.events().get(1).changeRevision());
		assertThat(feed.events().get(2).changeRevision())
				.isGreaterThan(feed.events().get(1).changeRevision());
		assertThat(feed.events().get(2).sourceVersion()).isEqualTo(2);
		// Several events of one observation share the observation time; the sequence orders them.
		assertThat(feed.events().get(0).observedAt()).isEqualTo(feed.events().get(1).observedAt());
		assertThat(feed.events().get(0).sourceTime().toString()).isEqualTo(OBSERVED_AT_V1);
		assertThat(feed.events().get(1).sourceTime().toString()).isEqualTo(OBSERVED_AT_V2);
		assertThat(feed.events().get(2).sourceTime().toString()).isEqualTo(OBSERVED_AT_V2);
		assertThat(feed.hasMore()).isFalse();
	}

	@Test
	void cursorPagesTheFeedAndStopsAfterTheLastEvent() throws Exception {
		runner.start();
		acceptSnapshot(BATCH_A, 1, snapshot(
				inc(A, "Assigned", 4, null, "Team A", "Alex", null, OBSERVED_AT_V1),
				inc(B, "Assigned", 4, null, "Team A", "Bob", null, OBSERVED_AT_V1),
				inc(C, "Assigned", 4, null, "Team A", "Carl", null, OBSERVED_AT_V1)));
		awaitSuccess(BATCH_A);
		acceptSnapshot(BATCH_B, 2, snapshot(
				inc(A, "WorkInProgress", 4, null, "Team A", "Alex Prime", null, OBSERVED_AT_V2),
				inc(B, "Assigned", 4, null, "Team A", "Bob", null, OBSERVED_AT_V1),
				inc(C, "WorkInProgress", 4, null, "Team A", "Carl Prime", null, OBSERVED_AT_V2)));
		awaitSuccess(BATCH_B);
		assertThat(journalCount()).isEqualTo(5L);

		var first = readFeed(0, 2);
		assertThat(first.events()).hasSize(2);
		assertThat(first.hasMore()).isTrue();
		var second = readFeed(first.lastEventSeq(), 2);
		assertThat(second.events()).hasSize(2);
		assertThat(second.hasMore()).isTrue();
		var third = readFeed(second.lastEventSeq(), 2);
		assertThat(third.events()).hasSize(1);
		assertThat(third.hasMore()).isFalse();
		assertThat(third.lastEventSeq()).isEqualTo(feedTip());

		// A page boundary can split events with one observation time; the order is kept.
		assertThat(first.events().get(0).observedAt()).isEqualTo(first.events().get(1).observedAt());
		List<Long> sequence = new java.util.ArrayList<>(first.events().stream()
				.map(IncidentChangeEvent::eventSeq).toList());
		sequence.addAll(second.events().stream().map(IncidentChangeEvent::eventSeq).toList());
		sequence.addAll(third.events().stream().map(IncidentChangeEvent::eventSeq).toList());
		assertThat(sequence).isSorted().doesNotHaveDuplicates();

		// After the last event the feed is empty and the cursor stays at the tip.
		var tail = readFeed(third.lastEventSeq(), 2);
		assertThat(tail.events()).isEmpty();
		assertThat(tail.hasMore()).isFalse();
		assertThat(tail.lastEventSeq()).isEqualTo(third.lastEventSeq());
		assertThat(readFeed(tail.lastEventSeq(), 100).events()).isEmpty();

		mockMvc.perform(get("/api/dashboard/incidents/changes").with(human()).param("afterEventSeq", "-1"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_EVENT_SEQ"));
		mockMvc.perform(get("/api/dashboard/incidents/changes").with(human()).param("limit", "0"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_LIMIT"));
	}

	@Test
	void invalidSnapshotDoesNotExtendTheJournal() throws Exception {
		runner.start();
		acceptSnapshot(BATCH_A, 1, snapshot(inc(A, "Assigned", 4, null, "Team A", "Alex", null, OBSERVED_AT_V1)));
		awaitSuccess(BATCH_A);
		assertThat(journalCount()).isEqualTo(1L);

		String invalid = snapshot(inc(A, "Assigned", 4, null, "Team A", "Alex", null, OBSERVED_AT_V1),
				"{\"id\":\"INC000000000099\",\"stateEnum\":\"unknown\"}");
		acceptSnapshot(BATCH_B, 2, invalid);
		awaitTerminal(BATCH_B);
		assertThat(row(BATCH_B)).containsEntry("status", "FAILED");
		assertThat(journalCount()).isEqualTo(1L);
		var feed = readFeed(0, 100);
		assertThat(feed.events()).hasSize(1);
		assertThat(feed.events().getFirst().incidentId()).isEqualTo(A);

		acceptSnapshot(BATCH_C, 3, snapshot(
				inc(A, "WorkInProgress", 4, null, "Team A", "Alex", null, OBSERVED_AT_V2)));
		awaitSuccess(BATCH_C);
		assertThat(journalCount()).isEqualTo(2L);
		assertThat(readFeed(0, 100).events().getLast().sourceVersion()).isEqualTo(3);
	}

	@Test
	void disappearanceIsAMissingFactNotAResolutionAndKeepsHistoryReadable() throws Exception {
		runner.start();
		acceptSnapshot(BATCH_A, 1, snapshot(
				inc(A, "Assigned", 4, null, "Team A", "Alex", null, OBSERVED_AT_V1),
				inc(B, "WorkInProgress", 3, null, "Team B", "Bob", null, OBSERVED_AT_V1)));
		awaitSuccess(BATCH_A);
		acceptSnapshot(BATCH_B, 2, snapshot(
				inc(A, "Assigned", 4, null, "Team A", "Alex Prime", null, OBSERVED_AT_V2)));
		awaitSuccess(BATCH_B);

		// The disappeared incident is not resolved by its absence.
		assertThat(jdbcTemplate.queryForMap("SELECT state, present_in_source FROM itsm_incident WHERE id = ?", B))
				.containsEntry("state", "WorkInProgress")
				.containsEntry("present_in_source", false);

		var history = readHistory(B);
		assertThat(history.events()).hasSize(2);
		var missing = history.events().getLast();
		assertThat(missing.changeKind()).isEqualTo("MISSING");
		assertThat(missing.sourceVersion()).isEqualTo(2);
		assertThat(missing.observedAt()).isNotNull();
		assertThat(missing.sourceTime()).isNull();
		assertThat(missing.fields()).hasSize(6);
		assertThat(missing.fields()).allSatisfy(field -> assertThat(field.current()).isNull());
		assertThat(field(missing, "stateEnum").previous()).isEqualTo("WorkInProgress");
		assertThat(field(missing, "assignedGroupName").previous()).isEqualTo("Team B");
		assertThat(field(missing, "assignedEmployee").previous()).isEqualTo("Bob");
		assertThat(field(missing, "priorityId").previous()).isEqualTo(3);
		assertThat(field(missing, "solution").previous()).isNull();
		// The incident is still findable with the time of its last observation.
		assertThat(history.lastObservedAt()).isEqualTo(missing.observedAt());
		assertThat(readFeed(0, 100).events()).extracting(IncidentChangeEvent::incidentId)
				.containsExactly(A, B, A, B);

		mockMvc.perform(get("/api/dashboard/incidents/{id}/history", "INC000000000099").with(human()))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("INCIDENT_NOT_FOUND"));
		mockMvc.perform(get("/api/dashboard/incidents/{id}/history", "not-an-incident").with(human()))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_INCIDENT_ID"));
	}

	@Test
	void replayAndRestartKeepEventIdentityAndOrder() throws Exception {
		runner.start();
		String first = snapshot(
				inc(A, "Assigned", 4, null, "Team A", "Alex", null, OBSERVED_AT_V1),
				inc(B, "Assigned", 4, null, "Team A", "Bob", null, OBSERVED_AT_V1),
				inc(C, "Assigned", 4, null, "Team A", "Carl", null, OBSERVED_AT_V1));
		acceptSnapshot(BATCH_A, 1, first);
		awaitSuccess(BATCH_A);
		String second = snapshot(
				inc(A, "WorkInProgress", 4, null, "Team A", "Alex Prime", null, OBSERVED_AT_V2),
				inc(B, "Assigned", 4, null, "Team A", "Bob Prime", null, OBSERVED_AT_V2),
				inc(C, "Assigned", 4, null, "Team A", "Carl", null, OBSERVED_AT_V1));
		acceptSnapshot(BATCH_B, 2, second);
		awaitSuccess(BATCH_B);

		var before = readFeed(0, 100);
		assertThat(before.events()).hasSize(5);
		List<Long> sequence = before.events().stream().map(IncidentChangeEvent::eventSeq).toList();

		// A repeated delivery of the applied version and a no-change successor add no rows.
		runner.stop();
		runner.start();
		acceptSnapshot(BATCH_B, 2, second);
		acceptSnapshot(BATCH_C, 3, second);
		awaitSuccess(BATCH_C);
		assertThat(journalCount()).isEqualTo(5L);

		var after = readFeed(0, 100);
		assertThat(after.events()).usingRecursiveComparison().isEqualTo(before.events());
		assertThat(after.events()).extracting(IncidentChangeEvent::eventSeq)
				.containsExactlyElementsOf(sequence);
		assertThat(readFeed(sequence.getLast(), 100).events()).isEmpty();
	}

	private static org.springframework.test.web.servlet.request.RequestPostProcessor human() {
		return jwt().jwt(token -> token.issuer("https://stand.test").subject("operator").claim("azp", "dashboard-web"));
	}

	private void acceptSnapshot(UUID id, long version, String body) throws Exception {
		mockMvc.perform(postBatch(id, SOURCE, version).content(body))
				.andExpect(status().isAccepted())
				.andExpect(header().string(HttpHeaders.LOCATION, BATCH_PATH + "/" + id))
				.andExpect(jsonPath("$.batchId").value(id.toString()));
	}

	private void awaitSuccess(UUID id) throws InterruptedException {
		await("batch " + id + " to succeed", () -> "SUCCEEDED".equals(row(id).get("status")));
	}

	private IncidentChangesPage readFeed(long afterEventSeq, int limit) throws Exception {
		MvcResult result = mockMvc.perform(get("/api/dashboard/incidents/changes").with(human())
				.param("afterEventSeq", String.valueOf(afterEventSeq)).param("limit", String.valueOf(limit)))
				.andExpect(status().isOk()).andReturn();
		return objectMapper.readValue(result.getResponse().getContentAsString(), IncidentChangesPage.class);
	}

	private IncidentHistoryResponse readHistory(String incidentId) throws Exception {
		MvcResult result = mockMvc.perform(get("/api/dashboard/incidents/{id}/history", incidentId).with(human()))
				.andExpect(status().isOk()).andReturn();
		return objectMapper.readValue(result.getResponse().getContentAsString(), IncidentHistoryResponse.class);
	}

	private static IncidentChangeField field(IncidentChangeEvent event, String name) {
		return event.fields().stream().filter(item -> item.field().equals(name)).findFirst().orElseThrow();
	}

	private long feedTip() {
		Long tip = jdbcTemplate.queryForObject("SELECT max(event_seq) FROM itsm_incident_change", Long.class);
		return tip == null ? 0 : tip;
	}

	private long journalCount() {
		Long count = jdbcTemplate.queryForObject("SELECT count(*) FROM itsm_incident_change", Long.class);
		return count == null ? 0 : count;
	}

	private static String snapshot(String... incidents) {
		return "[" + String.join(",", incidents) + "]";
	}

	private static String inc(String id, String state, int priority, String deadline, String group,
			String employee, String solution, String updatedAt) {
		String completion = ItsmIncidentState.valueOf(state).isCompleted() ? "\"2026-11-21T09:30:00Z\"" : "null";
		return """
				{"id":"%s","stateEnum":"%s","priorityId":%d,"submitDate":"2026-11-21T02:00:00Z","deadline":%s,
				 "productName":"Test product","assignedGroupName":"%s","assignedEmployee":"%s",
				 "assignedEmployeeLogin":"%s","completionDate":%s,"solution":%s,"updatedAt":"%s"}
				""".formatted(id, state, priority, quote(deadline), group, employee,
				employee.toLowerCase() + ".login", completion, quote(solution), updatedAt);
	}

	private static String quote(String value) {
		return value == null ? "null" : "\"" + value + "\"";
	}
}
