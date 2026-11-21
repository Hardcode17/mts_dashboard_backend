package ru.hardcode.dashboard;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import ru.hardcode.dashboard.service.DeadlineWarningService;
import ru.hardcode.dashboard.service.LocalIncidentEventWriter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@TestPropertySource(properties = "dashboard.deadline-warning.enabled=false")
class DeadlineWarningIT extends DashboardIncidentTestSupport {

	private static final String RULE = "/api/dashboard/deadline-warning-rule";
	private static final String INC = "INC000021095531";
	private static final Instant DEADLINE = Instant.parse("2026-11-21T14:00:00Z");

	@Autowired private DeadlineWarningService warnings;
	@Autowired private LocalIncidentEventWriter events;
	@Autowired private PostgreSQLContainer postgres;
	@Autowired private ObjectMapper json;

	@BeforeEach
	void resetRule() {
		jdbc.update("UPDATE deadline_warning_rule SET threshold_seconds = 900, version = 1");
	}

	@Test
	void versionChangesOnlyWhenAValidThresholdChanges() throws Exception {
		mvc.perform(authenticatedGet(RULE)).andExpect(status().isOk())
				.andExpect(jsonPath("$.thresholdSeconds").value(900)).andExpect(jsonPath("$.version").value(1));
		mvc.perform(get(RULE)).andExpect(status().isUnauthorized());
		setThreshold("{\"thresholdSeconds\":600}").andExpect(status().isOk())
				.andExpect(jsonPath("$.thresholdSeconds").value(600)).andExpect(jsonPath("$.version").value(2));
		setThreshold("{\"thresholdSeconds\":600}").andExpect(status().isOk())
				.andExpect(jsonPath("$.version").value(2));
		for (String invalid : new String[]{"{}", "{\"thresholdSeconds\":0}", "{\"thresholdSeconds\":1.5}"}) {
			setThreshold(invalid).andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.code").value("INVALID_DEADLINE_WARNING_RULE"))
					.andExpect(jsonPath("$.message").isNotEmpty());
		}
		mvc.perform(authenticatedGet(RULE)).andExpect(status().isOk())
				.andExpect(jsonPath("$.thresholdSeconds").value(600)).andExpect(jsonPath("$.version").value(2));
	}

	@Test
	void warningUsesSameCursorAndSurvivesTicksRuleChangesAndRestart() throws Exception {
		seedPublishedIncidents();
		long revision = dashboardValue("revision");
		long publicationSeq = dashboardValue("event_seq");
		long cursor = snapshotCursor();

		assertThat(warnings.checkDue(DEADLINE.minusSeconds(901))).isZero();
		assertThat(warnings.checkDue(DEADLINE.minusSeconds(900))).isEqualTo(1);
		assertThat(warnings.checkDue(DEADLINE.minusSeconds(899))).isZero();
		assertThat(warnings.checkDue(DEADLINE)).isZero();

		JsonNode event = json.readTree(mvc.perform(feed(cursor)).andExpect(status().isOk())
				.andExpect(jsonPath("$.events.length()").value(1))
				.andExpect(jsonPath("$.events[0].incidentId").value(INC))
				.andExpect(jsonPath("$.events[0].changeKind").value("DEADLINE_APPROACHING"))
				.andExpect(jsonPath("$.events[0].sourceVersion").value(nullValue()))
				.andExpect(jsonPath("$.events[0].details.deadline").value(DEADLINE.toString()))
				.andExpect(jsonPath("$.events[0].details.ruleVersion").value(1))
				.andExpect(jsonPath("$.events[0].changeRevision").value(revision))
				.andReturn().getResponse().getContentAsString()).get("events").get(0);
		long warningCursor = event.get("eventSeq").longValue();
		assertThat(warningCursor).isGreaterThan(cursor);
		assertThat(event.get("eventId").stringValue()).isEqualTo("local-" + warningCursor);
		mvc.perform(feed(warningCursor)).andExpect(status().isOk()).andExpect(jsonPath("$.events").isEmpty());
		mvc.perform(authenticatedGet(PATH + "/{id}/history", INC)).andExpect(status().isOk())
				.andExpect(jsonPath("$.events[-1].eventId").value("local-" + warningCursor));

		try (var app = new SpringApplicationBuilder(DashboardApplication.class).profiles("work").run(
				"--server.port=0", "--itsm.worker.enabled=false", "--dashboard.deadline-warning.enabled=false",
				"--spring.datasource.url=" + postgres.getJdbcUrl(),
				"--spring.datasource.username=" + postgres.getUsername(),
				"--spring.datasource.password=" + postgres.getPassword())) {
			assertThat(app.getBean(DeadlineWarningService.class).checkDue(DEADLINE.minusSeconds(898))).isZero();
		}
		assertThat(dashboardValue("event_seq")).isEqualTo(publicationSeq + 1);
		assertThat(dashboardValue("revision")).isEqualTo(revision);
		setThreshold("{\"thresholdSeconds\":600}").andExpect(status().isOk());
		assertThat(warnings.checkDue(DEADLINE.minusSeconds(600))).isEqualTo(1);
		mvc.perform(feed(warningCursor)).andExpect(status().isOk())
				.andExpect(jsonPath("$.events.length()").value(1))
				.andExpect(jsonPath("$.events[0].details.ruleVersion").value(2));
		assertThat(jdbc.queryForObject("SELECT count(*) FROM local_incident_event", Long.class)).isEqualTo(2);
	}

	@Test
	void concurrentNotesShareTheCursorAndRollbackDoesNotPublishAnEvent() throws Exception {
		seedPublishedIncidents();
		long cursor = snapshotCursor();
		long revision = dashboardValue("revision");
		var observedAt = DEADLINE.minusSeconds(1000);
		var transaction = new TransactionTemplate(transactions);
		transaction.executeWithoutResult(status -> {
			assertThat(events.append(INC, "NOTE_ADDED", "rolled-back", observedAt, Map.of("text", "Discarded")))
					.isPresent();
			status.setRollbackOnly();
		});
		try (var executor = Executors.newFixedThreadPool(2)) {
			var ready = new CountDownLatch(1);
			var first = executor.submit(() -> {
				ready.await();
				return transaction.execute(status -> events.append(INC, "NOTE_ADDED", "first", observedAt,
						Map.of("noteId", "first", "authorId", 1, "text", "First")));
			});
			var second = executor.submit(() -> {
				ready.await();
				return transaction.execute(status -> events.append(INC, "NOTE_ADDED", "second", observedAt,
						Map.of("noteId", "second", "authorId", 2, "text", "Second")));
			});
			ready.countDown();
			assertThat(first.get(10, TimeUnit.SECONDS).getAsLong())
					.isNotEqualTo(second.get(10, TimeUnit.SECONDS).getAsLong());
		}
		var duplicate = transaction.execute(status -> events.append(INC, "NOTE_ADDED", "first", observedAt,
				Map.of("text", "Duplicate")));
		assertThat(duplicate).isEmpty();
		mvc.perform(feed(cursor)).andExpect(status().isOk())
				.andExpect(jsonPath("$.events.length()").value(2))
				.andExpect(jsonPath("$.events[0].changeKind").value("NOTE_ADDED"))
				.andExpect(jsonPath("$.events[1].changeKind").value("NOTE_ADDED"));
		assertThat(dashboardValue("revision")).isEqualTo(revision);
	}

	private MockHttpServletRequestBuilder feed(long after) {
		return authenticatedGet(PATH + "/changes").param("afterEventSeq", Long.toString(after));
	}

	private long snapshotCursor() {
		return jdbc.queryForObject("SELECT max(event_seq) FROM itsm_incident_change", Long.class);
	}

	private long dashboardValue(String column) {
		return jdbc.queryForObject("SELECT " + column + " FROM dashboard_publication", Long.class);
	}

	private void seedPublishedIncidents() {
		publish(receive(1, """
				[
				  {"id":"INC000021095531","stateEnum":"Assigned","priorityId":4,"productName":"Test",
				   "submitDate":"2026-11-21T12:00:00Z","deadline":"2026-11-21T14:00:00Z","solution":null},
				  {"id":"INC000021095532","stateEnum":"WorkInProgress","priorityId":4,"productName":"Test",
				   "submitDate":"2026-11-21T12:00:00Z","solution":null},
				  {"id":"INC000021095533","stateEnum":"Research","priorityId":4,"productName":"Test",
				   "submitDate":"2026-11-21T12:00:00Z","deadline":"2026-11-21T14:00:00Z",
				   "completionDate":"2026-11-21T12:30:00Z","solution":"Checked"},
				  {"id":"INC000021095534","stateEnum":"Resolved","priorityId":4,"productName":"Test",
				   "submitDate":"2026-11-21T12:00:00Z","deadline":"2026-11-21T14:00:00Z",
				   "completionDate":"2026-11-21T12:30:00Z","solution":"Fixed"}
				]
				"""));
	}

	private ResultActions setThreshold(String body) throws Exception {
		return mvc.perform(put(RULE).contentType(MediaType.APPLICATION_JSON)
				.header("Authorization", "Bearer " + humanToken()).content(body));
	}
}
