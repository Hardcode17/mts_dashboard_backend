package ru.hardcode.dashboard;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import ru.hardcode.dashboard.service.PostmortemDraftService;
import ru.hardcode.dashboard.service.ItsmApiClient;
import ru.hardcode.dashboard.client.Kan16JwtTestConfiguration;
import ru.hardcode.dashboard.client.TestJwtSigner;


import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {"itsm.worker.enabled=false", "spring.application.name=postmortem-draft-it"})
class PostmortemDraftIT extends DashboardIncidentTestSupport {

	private static final String DRAFT = PATH + "/{incidentId}/postmortem";
	private static final String INC = "INC000021095531";
	private static final String MISSING = "INC000021095532";
	private static final String ACTIVE = "INC000021095533";

	@Autowired PostgreSQLContainer postgres;
	@MockitoBean ItsmApiClient itsm;
	private boolean createdNoteFixture;
	private boolean createdEvidenceFixture;
	private UUID run;
	private UUID unrelatedRun;

	@Test
	void onlyExplicitResolutionCreatesOneDraftAndPreservesAvailableSources() throws Exception {
		publish(receive(1, snapshot(row(INC, "Assigned", null), row(MISSING, "WorkInProgress", null),
				row(ACTIVE, "Assigned", null))));
		mvc.perform(get(DRAFT, INC)).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("HUMAN_AUTH_REQUIRED"));
		String unadmitted = Kan16JwtTestConfiguration.signer().token(TestJwtSigner.claims()
				.issuer(HUMAN_ISSUER).audience(HUMAN_AUDIENCE).subject("unadmitted-coordinator")
				.claim("azp", "dashboard-web").expiresAt(Instant.now().plusSeconds(60)));
		mvc.perform(get(DRAFT, INC).header(HttpHeaders.AUTHORIZATION, "Bearer " + unadmitted))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("COORDINATOR_ACCESS_DENIED"));
		mvc.perform(authenticatedGet(DRAFT, "invalid")).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_INCIDENT_ID"));
		mvc.perform(authenticatedGet(DRAFT, INC)).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("POSTMORTEM_DRAFT_NOT_FOUND"));

		publish(receive(2, snapshot(row(INC, "Resolved", "Service restored"), row(ACTIVE, "Assigned", "Workaround"))));
		long event = jdbc.queryForObject("""
				SELECT event_seq FROM itsm_incident_change
				WHERE incident_id = ? AND after_data->>'stateEnum' = 'Resolved'
				""", Long.class, INC);
		mvc.perform(authenticatedGet(DRAFT, INC)).andExpect(status().isOk())
				.andExpect(jsonPath("$.incidentId").value(INC))
				.andExpect(jsonPath("$.resolutionEventSeq").value(event))
				.andExpect(jsonPath("$.resolutionText").value("Service restored"))
				.andExpect(jsonPath("$.rootCause").value(nullValue()))
				.andExpect(jsonPath("$.timeline[-1].eventSeq").value(event))
				.andExpect(jsonPath("$.timeline[-1].href").value(containsString(INC + "/history")))
				.andExpect(jsonPath("$.gaps[0]").value("EVIDENCE_UNAVAILABLE"));
		mvc.perform(authenticatedGet(PATH + "/{incidentId}/history", INC)).andExpect(status().isOk())
				.andExpect(jsonPath("$.events[-1].eventSeq").value(event));
		for (String id : new String[]{MISSING, ACTIVE}) {
			mvc.perform(authenticatedGet(DRAFT, id)).andExpect(status().isNotFound())
					.andExpect(jsonPath("$.code").value("POSTMORTEM_DRAFT_NOT_FOUND"));
		}
		assertThat(jdbc.queryForObject("SELECT count(*) FROM incident_postmortem_draft", Long.class)).isEqualTo(1);
		publish(receive(3, snapshot(row(INC, "Resolved", "Service restored"), row(ACTIVE, "Assigned", "Workaround"))));
		assertThat(jdbc.queryForObject("SELECT count(*) FROM incident_postmortem_draft", Long.class)).isEqualTo(1);
	}



	@Test
	void confirmedItsmPatchCreatesTheDraftBeforeTheNextSnapshot() throws Exception {
		publish(receive(1, snapshot(row(INC, "WorkInProgress", null))));
		when(itsm.patch("itsm-51-" + INC, Map.of("stateEnum", "Resolved", "solution", "Service restored")))
				.thenReturn(Map.of("id", INC, "sysId", "itsm-51-" + INC, "stateEnum", "Resolved",
						"priorityId", 4, "productName", "Test", "submitDate", "2026-11-21T12:00:00Z",
						"completionDate", "2026-11-21T12:15:00Z", "solution", "Service restored"));
		mvc.perform(patch(PATH + "/{incidentId}", INC).header(HttpHeaders.AUTHORIZATION, "Bearer " + humanToken())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"stateEnum":"Resolved","solution":"Service restored"}
								"""))
				.andExpect(status().isOk()).andExpect(jsonPath("$.stateEnum").value("Resolved"));
		mvc.perform(authenticatedGet(DRAFT, INC)).andExpect(status().isOk())
				.andExpect(jsonPath("$.resolutionText").value("Service restored"));
		publish(receive(2, snapshot(row(INC, "Resolved", "Service restored"))));
		assertThat(jdbc.queryForObject("SELECT count(*) FROM incident_postmortem_draft", Long.class)).isEqualTo(1L);
	}

	@AfterEach
	void removeOptionalSourceFixtures() {
		if (createdNoteFixture) {
			jdbc.execute("DROP TABLE incident_note");
		}
		if (createdEvidenceFixture) {
			jdbc.execute("DROP TABLE ai_investigation_unavailable_source, ai_investigation_fact, ai_investigation_run");
		} else if (run != null) {
			jdbc.update("DELETE FROM ai_investigation_run WHERE run_id IN (?, ?)", run, unrelatedRun);
		}
	}

	@Test
	void draftKeepsIncidentScopedFindingsAndOneRecordAfterRestartAndReplay() throws Exception {
		publish(receive(1, snapshot(row(INC, "Assigned", null), row(ACTIVE, "Assigned", null))));
		run = UUID.randomUUID();
		unrelatedRun = UUID.randomUUID();
		prepareSources();
		addNote(INC, "note-1", "Restart confirmed");
		addNote(ACTIVE, "other-note", "Different incident");
		addEvidence(INC, run, "log-1");
		addEvidence(ACTIVE, unrelatedRun, "other-log");
		jdbc.update("""
				INSERT INTO ai_investigation_unavailable_source(run_id, source_index, source, service_key, code)
				VALUES (?, 0, 'DATABASE', 'payment', 'UNAVAILABLE')
				""", run);

		publish(receive(2, snapshot(row(INC, "Resolved", "Service restored"), row(ACTIVE, "Assigned", null))));
		long revision = jdbc.queryForObject("""
				SELECT change_revision FROM itsm_incident_change
				WHERE incident_id = ? AND after_data->>'stateEnum' = 'Resolved'
				""", Long.class, INC);
		mvc.perform(authenticatedGet(DRAFT, INC)).andExpect(status().isOk())
				.andExpect(jsonPath("$.notes.length()").value(1))
				.andExpect(jsonPath("$.notes[0].noteId").value("note-1"))
				.andExpect(jsonPath("$.notes[0].text").value("Restart confirmed"))
				.andExpect(jsonPath("$.evidence.length()").value(1))
				.andExpect(jsonPath("$.evidence[0].runId").value(run.toString()))
				.andExpect(jsonPath("$.evidence[0].evidenceId").value("log-1"))
				.andExpect(jsonPath("$.evidence[0].href").value("/api/incidents/" + INC + "/investigations/" + run))
				.andExpect(jsonPath("$.gaps[0]").value("SOURCE_UNAVAILABLE_DATABASE"));
		try (var restarted = new SpringApplicationBuilder(DashboardApplication.class).profiles("work").run(
				"--server.port=0", "--itsm.worker.enabled=false",
				"--spring.datasource.url=" + postgres.getJdbcUrl(),
				"--spring.datasource.username=" + postgres.getUsername(),
				"--spring.datasource.password=" + postgres.getPassword())) {
			var service = restarted.getBean(PostmortemDraftService.class);
			assertThat(service.latest(INC).evidence()).extracting(fact -> fact.evidenceId()).containsExactly("log-1");
			new TransactionTemplate(restarted.getBean(PlatformTransactionManager.class))
					.executeWithoutResult(tx -> service.capture(revision));
		}
		assertThat(jdbc.queryForObject("SELECT count(*) FROM incident_postmortem_draft", Long.class)).isEqualTo(1L);
	}

	private void prepareSources() {
		createdNoteFixture = !tableExists("incident_note");
		createdEvidenceFixture = !tableExists("ai_investigation_run");
		if (createdNoteFixture) {
			jdbc.execute("""
					CREATE TABLE incident_note
					    (id varchar(36), incident_id varchar(128), coordinator_id bigint,
					     event_seq bigint, observed_at timestamptz, note_text text)
					""");
		}
		if (createdEvidenceFixture) {
			jdbc.execute("""
					CREATE TABLE ai_investigation_run
					    (run_id uuid PRIMARY KEY, incident_id varchar(64), created_at timestamptz DEFAULT now(),
					     revision bigint, status text, window_from timestamptz, window_to timestamptz, model_prompt text);
					CREATE TABLE ai_investigation_fact
					    (run_id uuid, fact_index int, evidence_id text, source text, service_key text,
					     observed_at timestamptz, message text, value bigint, record_count bigint, service_state text);
					CREATE TABLE ai_investigation_unavailable_source
					    (run_id uuid, source_index int, source text, service_key text, code text)
					""");
		}
	}

	private boolean tableExists(String name) {
		return Boolean.TRUE.equals(jdbc.queryForObject("SELECT to_regclass(?) IS NOT NULL", Boolean.class, name));
	}

	private void addNote(String incident, String id, String text) {
		long sequence = jdbc.queryForObject("SELECT nextval('itsm_incident_change_event_seq')", Long.class);
		if (!createdNoteFixture) {
			jdbc.update("""
					INSERT INTO local_incident_event(event_seq, incident_id, change_revision, kind,
					    deduplication_key, observed_at, details)
					SELECT ?, id, last_change_revision, 'NOTE_ADDED', ?, now(), '{}'::jsonb
					FROM itsm_incident WHERE id = ?
					""", sequence, id, incident);
		}
		jdbc.update("""
				INSERT INTO incident_note(id, incident_id, coordinator_id, event_seq, observed_at, note_text)
				SELECT ?, ?, id, ?, now(), ? FROM users WHERE identity_issuer = ? AND identity_subject = ?
				""", id, incident, sequence, text, HUMAN_ISSUER, HUMAN_SUBJECT);
	}

	private void addEvidence(String incident, UUID run, String evidence) {
		jdbc.update("""
				INSERT INTO ai_investigation_run (run_id, incident_id, revision, status, window_from, window_to, model_prompt)
				SELECT ?, id, last_change_revision, 'running', now() - interval '10 min', now(), 'test only'
				FROM itsm_incident WHERE id = ?
				""", run, incident);
		jdbc.update("""
				INSERT INTO ai_investigation_fact(run_id, fact_index, evidence_id, source, service_key, observed_at, message)
				VALUES (?, 0, ?, 'LOG', 'payment', now(), 'Gateway recovered')
				""", run, evidence);
	}

	private static String snapshot(String... rows) {
		return "[" + String.join(",", rows) + "]";
	}

	private static String row(String id, String state, String solution) {
		return """
				{"id":"%s","sysId":"itsm-51-%s","stateEnum":"%s","priorityId":4,"productName":"Test",
				 "submitDate":"2026-11-21T12:00:00Z","completionDate":%s,"solution":%s}
				""".formatted(id, id, state, state.equals("Resolved") ? "\"2026-11-21T12:15:00Z\"" : "null",
				solution == null ? "null" : "\"" + solution + "\"");
	}
}
