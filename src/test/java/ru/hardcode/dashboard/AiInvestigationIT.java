package ru.hardcode.dashboard;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.context.ActiveProfiles;
import ru.hardcode.dashboard.client.ItsmMachineJwtTestConfiguration;
import ru.hardcode.dashboard.client.TestJwtSigner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * KAN-40 acceptance of the AI investigation on the real Spring Boot chain, MockMvc as the
 * HTTP layer and Testcontainers PostgreSQL 17. The stand ITSM/log/diagnostics sources of
 * KAN-39 are controlled local HTTP sources and a stand table; the Spring AI DeepSeek client
 * calls a controlled local model stub, so every launch is reproducible and every model
 * request can be inspected. The real model is verified separately in the {@code ai} profile.
 */
@ActiveProfiles({"demo", "evidence", "ai"})
class AiInvestigationIT extends AiInvestigationSupport {

	private static final String RUN_PATH = "/api/incidents/{incidentId}/investigations/{runId}";
	private static final String CARD_PATH = "/api/dashboard/incidents/{incidentId}";

	@Autowired
	private JwtEncoder jwtEncoder;

	@BeforeEach
	void resetForKan40() {
		resetEnvironment();
	}

	// ------------------------------------------------------------------ positive

	@Test
	void launchBindsFactsImmediatelyAndCompletionAddsHypothesisAndNextStep() throws Exception {
		publish(receive(1, snapshot(
				activeIncident(INCIDENT, HEADER, GROUP_PAYMENTS),
				activeIncident(OTHER_INCIDENT, "Другой инцидент стенда", GROUP_DATA))));
		MODEL.reply(HYPOTHESIS, NEXT_STEP);

		String launched = mvc.perform(launch(INCIDENT)).andExpect(status().isAccepted())
				.andExpect(jsonPath("$.runId").isNotEmpty())
				.andExpect(jsonPath("$.incidentId").value(INCIDENT))
				.andExpect(jsonPath("$.status").value("running"))
				.andExpect(jsonPath("$.revision").value(revision()))
				.andExpect(jsonPath("$.stale").value(false))
				.andExpect(jsonPath("$.windowFrom").value(WINDOW_FROM))
				.andExpect(jsonPath("$.windowTo").value(WINDOW_TO))
				.andExpect(jsonPath("$.hypothesis").value(nullValue()))
				.andExpect(jsonPath("$.nextStep").value(nullValue()))
				.andExpect(jsonPath("$.errorCode").value(nullValue()))
				.andExpect(jsonPath("$.finishedAt").value(nullValue()))
				.andExpect(jsonPath("$.createdAt").isNotEmpty())
				.andExpect(jsonPath("$.facts.length()").value(3))
				.andExpect(jsonPath("$.facts[*].evidenceId", containsInAnyOrder(
						"itsm:" + INCIDENT, "log:log-rec-1", "diag:diag-rec-1")))
				.andExpect(jsonPath("$.facts[0].evidenceId").value("itsm:" + INCIDENT))
				.andExpect(jsonPath("$.facts[0].source").value("ITSM"))
				.andExpect(jsonPath("$.facts[0].serviceKey").value(ITSM_SERVICE))
				.andExpect(jsonPath("$.facts[0].observedAt").value("2026-11-20T09:05:00Z"))
				.andExpect(jsonPath("$.facts[0].assignedCommand").value(GROUP_PAYMENTS))
				.andExpect(jsonPath("$.facts[0].level").value("HIGH"))
				.andExpect(jsonPath("$.facts[0].serviceState").value("Assigned"))
				.andExpect(jsonPath("$.facts[1].evidenceId").value("log:log-rec-1"))
				.andExpect(jsonPath("$.facts[1].source").value("LOG"))
				.andExpect(jsonPath("$.facts[1].message").value("payment authorization timed out"))
				.andExpect(jsonPath("$.facts[1].level").value("WARN"))
				.andExpect(jsonPath("$.facts[2].evidenceId").value("diag:diag-rec-1"))
				.andExpect(jsonPath("$.facts[2].source").value("DATABASE"))
				.andExpect(jsonPath("$.facts[2].recordCount").value(1))
				.andExpect(jsonPath("$.facts[2].value").value(42))
				.andExpect(jsonPath("$.unavailableSources.length()").value(0))
				.andReturn().getResponse().getContentAsString();
		String runId = JsonPath.read(launched, "$.runId");

		awaitRun(INCIDENT, runId, "completed");
		mvc.perform(authenticatedGet(RUN_PATH, INCIDENT, runId)).andExpect(status().isOk())
				.andExpect(jsonPath("$.runId").value(runId))
				.andExpect(jsonPath("$.incidentId").value(INCIDENT))
				.andExpect(jsonPath("$.status").value("completed"))
				.andExpect(jsonPath("$.revision").value(revision()))
				.andExpect(jsonPath("$.stale").value(false))
				.andExpect(jsonPath("$.hypothesis").value(HYPOTHESIS))
				.andExpect(jsonPath("$.nextStep").value(NEXT_STEP))
				.andExpect(jsonPath("$.errorCode").value(nullValue()))
				.andExpect(jsonPath("$.finishedAt").isNotEmpty())
				.andExpect(jsonPath("$.facts.length()").value(3));

		// The model saw one request built from the published card and the gathered facts.
		assertThat(MODEL.requests()).hasSize(1);
		assertThat(MODEL.requests().get(0))
				.contains(HEADER)
				.contains(DESCRIPTION)
				.contains("itsm:" + INCIDENT)
				.contains("log:log-rec-1")
				.contains("diag:diag-rec-1")
				.contains(GROUP_PAYMENTS);
	}

	// ------------------------------------------------------------------ negative: model failures

	@Test
	void modelTimeoutFailsTheRunExplicitlyAndKeepsTheCardReadable() throws Exception {
		publish(receive(1, snapshot(activeIncident(INCIDENT, HEADER, GROUP_PAYMENTS))));
		// Slower than the configured 5-second deadline of the model call.
		MODEL.delay(Duration.ofSeconds(8));
		String launched = mvc.perform(launch(INCIDENT)).andExpect(status().isAccepted())
				.andExpect(jsonPath("$.status").value("running"))
				.andReturn().getResponse().getContentAsString();
		String runId = JsonPath.read(launched, "$.runId");

		// The card stays readable while the model is thinking and after the failure.
		mvc.perform(authenticatedGet(CARD_PATH, INCIDENT)).andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(INCIDENT));
		awaitRun(INCIDENT, runId, "failed");
		mvc.perform(authenticatedGet(CARD_PATH, INCIDENT)).andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(INCIDENT));

		mvc.perform(authenticatedGet(RUN_PATH, INCIDENT, runId)).andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("failed"))
				.andExpect(jsonPath("$.errorCode").value("AI_MODEL_TIMEOUT"))
				.andExpect(jsonPath("$.errorMessage").value("The AI model did not answer within 5 seconds"))
				.andExpect(jsonPath("$.hypothesis").value(nullValue()))
				.andExpect(jsonPath("$.nextStep").value(nullValue()))
				.andExpect(jsonPath("$.facts.length()").value(3));

		// The abandoned late answer completes, but cannot resurrect the failed run.
		MODEL.awaitAnswers(1, Duration.ofSeconds(15));
		mvc.perform(authenticatedGet(RUN_PATH, INCIDENT, runId)).andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("failed"))
				.andExpect(jsonPath("$.errorCode").value("AI_MODEL_TIMEOUT"))
				.andExpect(jsonPath("$.finishedAt").isNotEmpty());
		assertThat(jdbc.queryForObject(
				"SELECT count(*) FROM ai_investigation_run WHERE status = 'completed'", Long.class)).isZero();
	}

	@Test
	void invalidModelReplyAndModelRefusalFailTheirRunsExplicitly() throws Exception {
		publish(receive(1, snapshot(activeIncident(INCIDENT, HEADER, GROUP_PAYMENTS))));

		MODEL.rawReply("простой текст без JSON, который модель не должна присылать");
		String invalid = launchAndAwait(INCIDENT, "failed");
		mvc.perform(authenticatedGet(RUN_PATH, INCIDENT, invalid)).andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("failed"))
				.andExpect(jsonPath("$.errorCode").value("AI_MODEL_INVALID_REPLY"))
				.andExpect(jsonPath("$.hypothesis").value(nullValue()));

		MODEL.refuse();
		String refused = launchAndAwait(INCIDENT, "failed");
		mvc.perform(authenticatedGet(RUN_PATH, INCIDENT, refused)).andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("failed"))
				.andExpect(jsonPath("$.errorCode").value("AI_MODEL_UNAVAILABLE"))
				.andExpect(jsonPath("$.hypothesis").value(nullValue()));

		// The incident card keeps working through both model failures.
		mvc.perform(authenticatedGet(CARD_PATH, INCIDENT)).andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(INCIDENT))
				.andExpect(jsonPath("$.stateEnum").value("Assigned"));
	}

	// ------------------------------------------------------------------ negative: source gap

	@Test
	void unavailableSourceIsAnExplicitGapAndNeverAnInventedFact() throws Exception {
		publish(receive(1, snapshot(activeIncident(INCIDENT, HEADER, GROUP_PAYMENTS))));
		// The log source refuses: a gap, never an empty success.
		STAND.startRefusal();
		MODEL.reply(HYPOTHESIS, NEXT_STEP);

		String launched = mvc.perform(launch(INCIDENT)).andExpect(status().isAccepted())
				.andExpect(jsonPath("$.facts.length()").value(2))
				.andExpect(jsonPath("$.facts[*].evidenceId", containsInAnyOrder(
						"itsm:" + INCIDENT, "diag:diag-rec-1")))
				.andExpect(jsonPath("$.unavailableSources.length()").value(1))
				.andExpect(jsonPath("$.unavailableSources[0].source").value("LOG"))
				.andExpect(jsonPath("$.unavailableSources[0].serviceKey").value(LOG_SERVICE))
				.andExpect(jsonPath("$.unavailableSources[0].code").value("MCP_EVIDENCE_SOURCE_REJECTED"))
				.andExpect(jsonPath("$.unavailableSources[0].reason").isNotEmpty())
				.andReturn().getResponse().getContentAsString();
		String runId = JsonPath.read(launched, "$.runId");

		awaitRun(INCIDENT, runId, "completed");
		mvc.perform(authenticatedGet(RUN_PATH, INCIDENT, runId)).andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("completed"))
				.andExpect(jsonPath("$.hypothesis").value(HYPOTHESIS));

		// No log fact exists anywhere in the run, and the gap itself reached the model.
		String storedFacts = jdbc.queryForObject("""
				SELECT string_agg(evidence_id, ',' ORDER BY fact_index) FROM ai_investigation_fact
				WHERE run_id = ?
				""", String.class, UUID.fromString(runId));
		assertThat(storedFacts).isEqualTo("itsm:" + INCIDENT + ",diag:diag-rec-1");
		assertThat(MODEL.requests()).hasSize(1);
		assertThat(MODEL.requests().get(0)).contains("MCP_EVIDENCE_SOURCE_REJECTED");
	}

	// ------------------------------------------------------------------ source change between runs

	@Test
	void changedSourceAppearsOnlyInNewRunsAndTheOldRunKeepsItsFacts() throws Exception {
		publish(receive(1, snapshot(activeIncident(INCIDENT, HEADER, GROUP_PAYMENTS))));
		String first = launchAndComplete(INCIDENT);
		assertThat(factMessage(first, "log:log-rec-1")).isEqualTo("payment authorization timed out");

		STAND.updateLogMessage("log-rec-1", "payment gateway restarted after upstream timeout");
		String second = launchAndComplete(INCIDENT);
		assertThat(factMessage(second, "log:log-rec-1"))
				.isEqualTo("payment gateway restarted after upstream timeout");
		// The stored answer of the first run is untouched and both runs stay fresh.
		assertThat(factMessage(first, "log:log-rec-1")).isEqualTo("payment authorization timed out");
		mvc.perform(authenticatedGet(RUN_PATH, INCIDENT, first)).andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("completed"))
				.andExpect(jsonPath("$.stale").value(false));
		mvc.perform(authenticatedGet(RUN_PATH, INCIDENT, second)).andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("completed"))
				.andExpect(jsonPath("$.stale").value(false));
	}

	// ------------------------------------------------------------------ revision race

	@Test
	void revisionAdvanceWhileTheModelAnswersMarksTheOldRunStaleAndKeepsItReadable() throws Exception {
		publish(receive(1, snapshot(activeIncident(INCIDENT, HEADER, GROUP_PAYMENTS))));
		long firstRevision = revision();
		MODEL.reply(HYPOTHESIS, NEXT_STEP);
		// Busy while the publication advances, but within the 5-second model deadline.
		MODEL.delay(Duration.ofSeconds(3));
		String runA = JsonPath.read(mvc.perform(launch(INCIDENT)).andExpect(status().isAccepted())
				.andExpect(jsonPath("$.status").value("running"))
				.andExpect(jsonPath("$.revision").value(firstRevision))
				.andReturn().getResponse().getContentAsString(), "$.runId");

		// The model is answering now. While it does, the publication moves to a new
		// revision of the same incident, and the stand ITSM source changes its assignment.
		MODEL.awaitRequests(1, Duration.ofSeconds(10));
		publish(receive(2, snapshot(
				activeIncident(INCIDENT, HEADER + " (эскалация)", GROUP_DATA))));
		long secondRevision = revision();
		assertThat(secondRevision).isGreaterThan(firstRevision);
		STAND.putIncident(INCIDENT, "Assigned", "Высокий", GROUP_DATA, NOW);

		// The old run completes with its own old facts and is marked stale by the revision.
		awaitRun(INCIDENT, runA, "completed");
		mvc.perform(authenticatedGet(RUN_PATH, INCIDENT, runA)).andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("completed"))
				.andExpect(jsonPath("$.revision").value(firstRevision))
				.andExpect(jsonPath("$.stale").value(true))
				.andExpect(jsonPath("$.hypothesis").value(HYPOTHESIS))
				.andExpect(jsonPath("$.facts.length()").value(3));
		assertThat(factCommand(runA, "itsm:" + INCIDENT)).isEqualTo(GROUP_PAYMENTS);

		// A new launch binds the new revision and sees the changed card and source data.
		MODEL.delay(Duration.ZERO);
		String runB = launchAndComplete(INCIDENT);
		mvc.perform(authenticatedGet(RUN_PATH, INCIDENT, runB)).andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("completed"))
				.andExpect(jsonPath("$.revision").value(secondRevision))
				.andExpect(jsonPath("$.stale").value(false));
		assertThat(factCommand(runB, "itsm:" + INCIDENT)).isEqualTo(GROUP_DATA);
		assertThat(MODEL.requests().get(1)).contains(HEADER + " (эскалация)").contains(GROUP_DATA);

		// The old run stays accessible with its stored answer.
		mvc.perform(authenticatedGet(RUN_PATH, INCIDENT, runA)).andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("completed"))
				.andExpect(jsonPath("$.stale").value(true))
				.andExpect(jsonPath("$.revision").value(firstRevision));
		assertThat(factCommand(runA, "itsm:" + INCIDENT)).isEqualTo(GROUP_PAYMENTS);
	}

	// ------------------------------------------------------------------ solution policy

	@Test
	void activeIncidentSolutionNeverReachesTheModelButACompletedOneDoes() throws Exception {
		publish(receive(1, snapshot(activeIncident(INCIDENT, HEADER, GROUP_PAYMENTS))));
		// The storage invariant keeps an active incident solution-free (ck_itsm_incident_completion),
		// and the card confirms it; the model input must not carry any solution section either.
		mvc.perform(authenticatedGet(CARD_PATH, INCIDENT)).andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(INCIDENT))
				.andExpect(jsonPath("$.stateEnum").value("Assigned"))
				.andExpect(jsonPath("$.solution").value(nullValue()));

		String active = launchAndComplete(INCIDENT);
		assertThat(MODEL.requests()).hasSize(1);
		assertThat(MODEL.requests().get(0))
				.contains(HEADER)
				.contains(DESCRIPTION)
				.doesNotContain("решение (инцидент завершён)");
		assertThat(promptOf(active))
				.contains(HEADER)
				.doesNotContain("решение (инцидент завершён)");

		// The same card resolved: a completed incident may reveal its solution to the model.
		String publishedSolution = "Восстановлен основной канал авторизации платежей";
		publish(receive(2, snapshot(resolvedIncident(INCIDENT, HEADER, publishedSolution))));
		String resolved = launchAndComplete(INCIDENT);
		assertThat(MODEL.requests()).hasSize(2);
		assertThat(MODEL.requests().get(1)).contains(publishedSolution);
		assertThat(promptOf(resolved)).contains(publishedSolution);
	}

	// ------------------------------------------------------------------ documented errors

	@Test
	void refusesUnknownIncidentsUnmappedRunsAndCallersWithoutAccess() throws Exception {
		publish(receive(1, snapshot(
				activeIncident(INCIDENT, HEADER, GROUP_PAYMENTS),
				activeIncident(OTHER_INCIDENT, "Другой инцидент стенда", GROUP_DATA))));
		String existing = JsonPath.read(mvc.perform(launch(INCIDENT))
				.andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString(), "$.runId");

		// Unknown INC in the publication carries the publication counters.
		mvc.perform(launch("INC999999")).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("INCIDENT_NOT_FOUND"))
				.andExpect(jsonPath("$.message").value("Incident INC999999 not found"))
				.andExpect(jsonPath("$.eventSeq").value(1))
				.andExpect(jsonPath("$.revision").value(revision()));
		// Published, but not the incident the evidence mapping names.
		mvc.perform(launch(OTHER_INCIDENT)).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("MCP_EVIDENCE_INCIDENT_NOT_MAPPED"))
				.andExpect(jsonPath("$.message").value(
						"Incident " + OTHER_INCIDENT + " has no explicit mapping to a scenario run"));
		mvc.perform(launch("21931151")).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_INCIDENT_ID"));

		// Runs of other incidents and unknown runIds address nothing.
		mvc.perform(authenticatedGet(RUN_PATH, INCIDENT, UUID.randomUUID())).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("AI_RUN_NOT_FOUND"));
		mvc.perform(authenticatedGet(RUN_PATH, OTHER_INCIDENT, existing)).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("AI_RUN_NOT_FOUND"));
		mvc.perform(authenticatedGet(RUN_PATH, INCIDENT, "not-a-uuid")).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("AI_RUN_NOT_FOUND"));

		// Without a token, and with a machine or unknown human token, nothing launches.
		mvc.perform(post("/api/incidents/{id}/investigations", INCIDENT)
						.accept(MediaType.APPLICATION_JSON))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("HUMAN_AUTH_REQUIRED"));
		mvc.perform(get(RUN_PATH, INCIDENT, existing).accept(MediaType.APPLICATION_JSON))
				.andExpect(status().isUnauthorized());
		mvc.perform(post("/api/incidents/{id}/investigations", INCIDENT)
						.accept(MediaType.APPLICATION_JSON)
						.header("Authorization", "Bearer " + machineToken()))
				.andExpect(status().isUnauthorized());
		mvc.perform(post("/api/incidents/{id}/investigations", INCIDENT)
						.accept(MediaType.APPLICATION_JSON)
						.header("Authorization", "Bearer " + unknownUserToken()))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("COORDINATOR_ACCESS_DENIED"));

		// Nothing above stored a run beyond the one existing launch.
		assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_investigation_run", Long.class)).isEqualTo(1);
	}

	// ------------------------------------------------------------------ helpers

	private String promptOf(String runId) {
		return jdbc.queryForObject("SELECT model_prompt FROM ai_investigation_run WHERE run_id = ?",
				String.class, UUID.fromString(runId));
	}

	private String factMessage(String runId, String evidenceId) {
		return jdbc.queryForObject("""
				SELECT message FROM ai_investigation_fact WHERE run_id = ? AND evidence_id = ?
				""", String.class, UUID.fromString(runId), evidenceId);
	}

	private String factCommand(String runId, String evidenceId) {
		return jdbc.queryForObject("""
				SELECT assigned_command FROM ai_investigation_fact WHERE run_id = ? AND evidence_id = ?
				""", String.class, UUID.fromString(runId), evidenceId);
	}

	private String launchAndComplete(String incidentId) throws Exception {
		return launchAndAwait(incidentId, "completed");
	}

	private String launchAndAwait(String incidentId, String expectedStatus) throws Exception {
		String launched = mvc.perform(launch(incidentId)).andExpect(status().isAccepted())
				.andReturn().getResponse().getContentAsString();
		String runId = JsonPath.read(launched, "$.runId");
		awaitRun(incidentId, runId, expectedStatus);
		return runId;
	}

	/** Polls the run endpoint until the run reaches the expected status. */
	private String awaitRun(String incidentId, String runId, String expectedStatus) throws Exception {
		long deadline = System.currentTimeMillis() + 20_000;
		String body = "";
		while (System.currentTimeMillis() < deadline) {
			body = mvc.perform(authenticatedGet(RUN_PATH, incidentId, runId))
					.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
			if (expectedStatus.equals(JsonPath.read(body, "$.status"))) {
				return body;
			}
			Thread.sleep(100);
		}
		throw new AssertionError("Run " + runId + " did not reach status " + expectedStatus + ": " + body);
	}

	private String machineToken() {
		return ItsmMachineJwtTestConfiguration.machineToken(jwtEncoder);
	}

	private String unknownUserToken() {
		return ru.hardcode.dashboard.client.Kan16JwtTestConfiguration.signer().token(TestJwtSigner.claims()
				.issuer(HUMAN_ISSUER).audience(HUMAN_AUDIENCE).subject("kan40-unknown-operator")
				.claim("azp", "dashboard-web")
				.expiresAt(Instant.now().plusSeconds(300)));
	}
}
