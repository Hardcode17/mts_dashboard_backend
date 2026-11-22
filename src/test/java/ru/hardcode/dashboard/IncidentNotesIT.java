package ru.hardcode.dashboard;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import ru.hardcode.dashboard.client.Kan16JwtTestConfiguration;
import ru.hardcode.dashboard.client.TestJwtSigner;
import ru.hardcode.dashboard.dto.IncidentChangeEvent;
import ru.hardcode.dashboard.dto.IncidentChangesPage;
import ru.hardcode.dashboard.dto.IncidentNoteResponse;
import ru.hardcode.dashboard.dto.IncidentNotesPage;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * KAN-43 acceptance: two authenticated coordinators save their own notes on one INC, the next
 * shift reads both notes with authors and observation times through the real filter chain,
 * and every note event advances the shared KAN-28 {@code eventSeq} while the published
 * snapshot {@code revision} stays unchanged. Empty text, an unknown INC or a forged author
 * write nothing; parallel notes with one observation time get distinct {@code eventSeq}
 * values in one cursor read. PostgreSQL 17 runs in Testcontainers, snapshots enter through
 * the machine intake, notes travel through the human Bearer chain.
 */
class IncidentNotesIT extends DashboardIncidentTestSupport {

	private static final String NOTES_PATH = PATH + "/{incidentId}/notes";
	private static final String INC = "INC000021931151";
	private static final String UNKNOWN_INC = "INC000021999999";
	private static final String RELIEVER_SUBJECT = "kan43-shift-reliever";
	private static final String OBSERVED_AT = "2026-11-21T09:15:00Z";
	private static final String LATER_OBSERVED_AT = "2026-11-21T09:45:00Z";
	private static final String SAME_OBSERVED_AT = "2026-11-21T12:00:00Z";
	private static final String FIRST_TEXT = "Дежурная смена: связка восстановлена, деградация не подтверждена";
	private static final String SECOND_TEXT = "Принято; очередь платежей под наблюдением до 18:00";

	@Autowired
	private ObjectMapper objectMapper;

	@BeforeEach
	void seedSecondCoordinator() {
		// The base clears the published state (its CASCADE truncation also empties the notes);
		// this adds the second shift identity used by every test.
		jdbc.execute("TRUNCATE incident_note");
		jdbc.update("""
				INSERT INTO users(identity_issuer, identity_subject, login, display_name)
				VALUES (?, ?, 'reliever', 'Shift Reliever')
				ON CONFLICT (identity_issuer, identity_subject) DO NOTHING
				""", HUMAN_ISSUER, RELIEVER_SUBJECT);
	}

	@Test
	void twoCoordinatorsSaveNotesAndTheNextShiftReadsBothWithAuthorsAndTimes() throws Exception {
		publish(receive(1, baseline()));
		long operatorId = localUserId(HUMAN_SUBJECT);
		long relieverId = localUserId(RELIEVER_SUBJECT);
		Map<String, Object> publicationBefore = publication();
		long journalTipBefore = journalTip();
		long journalCountBefore = journalCount();

		var first = saveNote(humanToken(), INC, FIRST_TEXT, OBSERVED_AT, operatorId);
		assertThat(first.eventSeq()).isGreaterThan(journalTipBefore);
		assertThat(first.noteId()).isNotBlank();
		assertThat(first.incidentId()).isEqualTo(INC);
		assertThat(first.authorId()).isEqualTo(operatorId);
		assertThat(first.authorLogin()).isEqualTo("operator");
		assertThat(first.authorDisplayName()).isEqualTo("Dashboard Operator");
		assertThat(first.observedAt().toString()).isEqualTo(OBSERVED_AT);
		assertThat(first.text()).isEqualTo(FIRST_TEXT);

		var second = saveNote(relieverToken(), INC, SECOND_TEXT, LATER_OBSERVED_AT, null);
		assertThat(second.eventSeq()).isGreaterThan(first.eventSeq());
		assertThat(second.authorId()).isEqualTo(relieverId);
		assertThat(second.authorLogin()).isEqualTo("reliever");
		assertThat(second.authorDisplayName()).isEqualTo("Shift Reliever");
		assertThat(second.observedAt().toString()).isEqualTo(LATER_OBSERVED_AT);

		// The next shift reads the whole note history of the INC, with authors and times.
		for (String token : List.of(humanToken(), relieverToken())) {
			var page = readNotes(token, INC, journalTipBefore, 100);
			assertThat(page.notes()).hasSize(2);
			assertThat(page.notes()).extracting(IncidentNoteResponse::eventSeq)
					.isSorted().doesNotHaveDuplicates();
			assertThat(page.notes()).extracting(IncidentNoteResponse::noteId)
					.containsExactly(first.noteId(), second.noteId());
			assertThat(page.notes()).extracting(IncidentNoteResponse::authorLogin)
					.containsExactly("operator", "reliever");
			assertThat(page.notes()).extracting(IncidentNoteResponse::authorId)
					.containsExactly(operatorId, relieverId);
			assertThat(page.notes()).extracting(IncidentNoteResponse::text)
					.containsExactly(FIRST_TEXT, SECOND_TEXT);
			assertThat(page.notes()).extracting(IncidentNoteResponse::observedAt)
					.containsExactly(Instant.parse(OBSERVED_AT), Instant.parse(LATER_OBSERVED_AT));
			assertThat(page.hasMore()).isFalse();
			assertThat(page.lastEventSeq()).isEqualTo(second.eventSeq());
		}

		// The note events advance the shared chronology but not the ITSM snapshot publication.
		assertThat(noteCount()).isEqualTo(2);
		assertThat(journalCount()).isEqualTo(journalCountBefore);
		assertThat(journalTip()).isEqualTo(journalTipBefore);
		assertPublicationAfterNotes(publicationBefore, 2);
		assertSharedCursorMatchesNotes(readNotes(humanToken(), INC, journalTipBefore, 100), journalTipBefore);
		List<Long> noteSequences = jdbc.queryForList(
				"SELECT event_seq FROM incident_note ORDER BY event_seq", Long.class);
		assertThat(noteSequences).containsExactly(first.eventSeq(), second.eventSeq());
		assertThat(noteSequences.getFirst()).isGreaterThan(journalTipBefore);
	}

	@Test
	void emptyTextUnknownIncidentOrForgedAuthorWriteNothing() throws Exception {
		publish(receive(1, baseline()));
		long operatorId = localUserId(HUMAN_SUBJECT);
		long relieverId = localUserId(RELIEVER_SUBJECT);
		Map<String, Object> publicationBefore = publication();
		long journalTipBefore = journalTip();
		long journalCountBefore = journalCount();
		long sequenceBefore = sequenceValue();

		// The human Bearer chain guards the note routes themselves.
		mvc.perform(postNotes(UNKNOWN_INC, noteBody(FIRST_TEXT, null, null)))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("HUMAN_AUTH_REQUIRED"));
		String stranger = Kan16JwtTestConfiguration.signer().token(TestJwtSigner.claims()
				.issuer(HUMAN_ISSUER).audience(HUMAN_AUDIENCE).subject("kan43-stranger")
				.claim("azp", "dashboard-web").expiresAt(Instant.now().plusSeconds(300)));
		mvc.perform(postNotes(INC, noteBody(FIRST_TEXT, null, null))
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + stranger))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("COORDINATOR_ACCESS_DENIED"));

		// Empty or whitespace-only text is rejected before the writer is called.
		postExpectingError(humanToken(), INC, noteBody("", OBSERVED_AT, null), 400, "INCIDENT_NOTE_TEXT_INVALID");
		postExpectingError(humanToken(), INC, noteBody("   ", OBSERVED_AT, null), 400, "INCIDENT_NOTE_TEXT_INVALID");
		postExpectingError(humanToken(), INC, noteBody("x".repeat(4001), OBSERVED_AT, null), 400,
				"INCIDENT_NOTE_TEXT_INVALID");
		// A claimed foreign author — another known coordinator or an unknown id — writes nothing.
		postExpectingError(humanToken(), INC, noteBody(FIRST_TEXT, OBSERVED_AT, relieverId), 403,
				"INCIDENT_NOTE_AUTHOR_MISMATCH");
		postExpectingError(humanToken(), INC, noteBody(FIRST_TEXT, OBSERVED_AT, 999_999L), 403,
				"INCIDENT_NOTE_AUTHOR_MISMATCH");
		// An INC unknown to the dashboard is a 404 carrying the unchanged publication counters.
		mvc.perform(postNotes(UNKNOWN_INC, noteBody(FIRST_TEXT, OBSERVED_AT, null))
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + humanToken()))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("INCIDENT_NOT_FOUND"))
				.andExpect(jsonPath("$.message").value("Incident " + UNKNOWN_INC + " not found"))
				.andExpect(jsonPath("$.eventSeq").value(number(publicationBefore, "event_seq")))
				.andExpect(jsonPath("$.revision").value(number(publicationBefore, "revision")));
		mvc.perform(postNotes("INC123", noteBody(FIRST_TEXT, OBSERVED_AT, null))
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + humanToken()))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_INCIDENT_ID"));

		// The notes history rejects the same broken inputs.
		mvc.perform(getNotes(UNKNOWN_INC).header(HttpHeaders.AUTHORIZATION, "Bearer " + humanToken()))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("INCIDENT_NOT_FOUND"));
		mvc.perform(getNotes("INC123").header(HttpHeaders.AUTHORIZATION, "Bearer " + humanToken()))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_INCIDENT_ID"));
		mvc.perform(getNotes(INC).header(HttpHeaders.AUTHORIZATION, "Bearer " + humanToken())
						.param("afterEventSeq", "-1"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_EVENT_SEQ"));
		mvc.perform(getNotes(INC).header(HttpHeaders.AUTHORIZATION, "Bearer " + humanToken()).param("limit", "0"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_LIMIT"));
		mvc.perform(getNotes(INC).header(HttpHeaders.AUTHORIZATION, "Bearer " + humanToken())
						.param("limit", "101"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_LIMIT"));

		// Nothing was written: no note, no journal row, no burned sequence value.
		assertThat(noteCount()).isZero();
		assertThat(journalCount()).isEqualTo(journalCountBefore);
		assertThat(journalTip()).isEqualTo(journalTipBefore);
		assertThat(sequenceValue()).isEqualTo(sequenceBefore);
		assertThat(publication()).isEqualTo(publicationBefore);

		// A correct request still succeeds after the rejections — with the author echo.
		var saved = saveNote(humanToken(), INC, FIRST_TEXT, OBSERVED_AT, operatorId);
		assertThat(saved.eventSeq()).isGreaterThan(journalTipBefore);
		assertThat(noteCount()).isEqualTo(1);
		assertThat(sequenceValue()).isEqualTo(sequenceBefore + 1);
		assertPublicationAfterNotes(publicationBefore, 1);
	}

	@Test
	void parallelNotesWithOneObservationTimeGetDistinctEventSeqInOneCursorRead() throws Exception {
		publish(receive(1, baseline()));
		long journalTipBefore = journalTip();
		long journalCountBefore = journalCount();
		Map<String, Object> publicationBefore = publication();

		// Two coordinators save notes in parallel with one observation time.
		CyclicBarrier start = new CyclicBarrier(2);
		List<IncidentNoteResponse> saved = new ArrayList<>();
		try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
			Callable<IncidentNoteResponse> first = () -> {
				start.await(30, TimeUnit.SECONDS);
				return saveNote(humanToken(), INC, FIRST_TEXT, SAME_OBSERVED_AT, null);
			};
			Callable<IncidentNoteResponse> second = () -> {
				start.await(30, TimeUnit.SECONDS);
				return saveNote(relieverToken(), INC, SECOND_TEXT, SAME_OBSERVED_AT, null);
			};
			var firstFuture = pool.submit(first);
			var secondFuture = pool.submit(second);
			saved.add(firstFuture.get(60, TimeUnit.SECONDS));
			saved.add(secondFuture.get(60, TimeUnit.SECONDS));
		}
		assertThat(saved).extracting(IncidentNoteResponse::eventSeq)
				.doesNotHaveDuplicates()
				.allSatisfy(eventSeq -> assertThat(eventSeq).isGreaterThan(journalTipBefore));
		assertThat(saved).extracting(IncidentNoteResponse::observedAt)
				.containsOnly(Instant.parse(SAME_OBSERVED_AT));

		// One cursor read returns both parallel notes together.
		var page = readNotes(humanToken(), INC, journalTipBefore, 100);
		assertThat(page.notes()).hasSize(2);
		assertThat(page.notes()).extracting(IncidentNoteResponse::eventSeq)
				.isSorted().doesNotHaveDuplicates();
		assertThat(page.notes()).extracting(IncidentNoteResponse::observedAt)
				.containsOnly(Instant.parse(SAME_OBSERVED_AT));
		assertThat(page.notes()).extracting(IncidentNoteResponse::authorLogin)
				.containsExactlyInAnyOrder("operator", "reliever");
		assertThat(page.notes()).extracting(IncidentNoteResponse::text)
				.containsExactlyInAnyOrder(FIRST_TEXT, SECOND_TEXT);
		assertThat(page.hasMore()).isFalse();
		assertThat(page.lastEventSeq()).isEqualTo(saved.stream()
				.map(IncidentNoteResponse::eventSeq).max(Long::compareTo).orElseThrow());

		// A page boundary can split the parallel notes; the cursor keeps the order.
		var head = readNotes(humanToken(), INC, 0, 1);
		assertThat(head.notes()).hasSize(1);
		assertThat(head.hasMore()).isTrue();
		var tail = readNotes(humanToken(), INC, head.lastEventSeq(), 1);
		assertThat(tail.notes()).hasSize(1);
		assertThat(tail.hasMore()).isFalse();
		assertThat(List.of(head.notes().getFirst().eventSeq(), tail.notes().getFirst().eventSeq()))
				.isSorted()
				.containsExactlyInAnyOrderElementsOf(saved.stream()
						.map(IncidentNoteResponse::eventSeq).toList());

		// Parallel notes advanced the chronology but not the published snapshot.
		assertThat(noteCount()).isEqualTo(2);
		assertThat(journalCount()).isEqualTo(journalCountBefore);
		assertPublicationAfterNotes(publicationBefore, 2);
		assertSharedCursorMatchesNotes(page, journalTipBefore);
	}

	private void assertPublicationAfterNotes(Map<String, Object> before, int notes) {
		var after = publication();
		assertThat(number(after, "revision")).isEqualTo(number(before, "revision"));
		assertThat(number(after, "event_seq")).isEqualTo(number(before, "event_seq") + notes);
	}

	private void assertSharedCursorMatchesNotes(IncidentNotesPage notes, long afterEventSeq) throws Exception {
		var response = mvc.perform(authenticatedGet(PATH + "/changes")
						.param("afterEventSeq", Long.toString(afterEventSeq)))
				.andExpect(status().isOk()).andReturn().getResponse();
		var changes = objectMapper.readValue(response.getContentAsByteArray(), IncidentChangesPage.class);
		assertThat(changes.hasMore()).isFalse();
		assertThat(changes.lastEventSeq()).isEqualTo(notes.lastEventSeq());
		assertThat(changes.events()).extracting(IncidentChangeEvent::eventSeq)
				.containsExactlyElementsOf(notes.notes().stream().map(IncidentNoteResponse::eventSeq).toList());
		for (int i = 0; i < notes.notes().size(); i++) {
			var note = notes.notes().get(i);
			var event = changes.events().get(i);
			assertThat(event.changeKind()).isEqualTo("NOTE_ADDED");
			assertThat(event.incidentId()).isEqualTo(note.incidentId());
			assertThat(event.observedAt()).isEqualTo(note.observedAt());
			assertThat(event.details()).containsEntry("noteId", note.noteId()).containsEntry("text", note.text());
			assertThat(((Number) event.details().get("authorId")).longValue()).isEqualTo(note.authorId());
		}
		mvc.perform(authenticatedGet(PATH + "/{incidentId}/history", INC))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.events[-1].eventSeq").value(notes.lastEventSeq()));
	}

	private long localUserId(String subject) {
		return jdbc.queryForObject("SELECT id FROM users WHERE identity_subject = ?", Long.class, subject);
	}

	private Map<String, Object> publication() {
		return jdbc.queryForMap("SELECT revision, event_seq FROM dashboard_publication WHERE singleton");
	}

	private static long number(Map<String, Object> row, String column) {
		return ((Number) row.get(column)).longValue();
	}

	private long journalTip() {
		Long tip = jdbc.queryForObject("SELECT coalesce(max(event_seq), 0) FROM itsm_incident_change", Long.class);
		return tip == null ? 0 : tip;
	}

	private long journalCount() {
		Long count = jdbc.queryForObject("SELECT count(*) FROM itsm_incident_change", Long.class);
		return count == null ? 0 : count;
	}

	private long noteCount() {
		Long count = jdbc.queryForObject("SELECT count(*) FROM incident_note", Long.class);
		return count == null ? 0 : count;
	}

	/** The shared chronology sequence itself: a rejected request burns no value. */
	private long sequenceValue() {
		return jdbc.queryForObject("SELECT last_value FROM itsm_incident_change_event_seq", Long.class);
	}

	private IncidentNoteResponse saveNote(String token, String incidentId, String text, String observedAt,
			Long authorId) throws Exception {
		MvcResult result = mvc.perform(postNotes(incidentId, noteBody(text, observedAt, authorId))
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.incidentId").value(incidentId))
				.andExpect(jsonPath("$.text").value(text))
				.andReturn();
		return objectMapper.readValue(result.getResponse().getContentAsString(), IncidentNoteResponse.class);
	}

	private void postExpectingError(String token, String incidentId, String body, int httpStatus, String code)
			throws Exception {
		mvc.perform(postNotes(incidentId, body).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().is(httpStatus))
				.andExpect(jsonPath("$.code").value(code))
				.andExpect(jsonPath("$.message").isNotEmpty());
	}

	private IncidentNotesPage readNotes(String token, String incidentId, long afterEventSeq, int limit)
			throws Exception {
		MvcResult result = mvc.perform(getNotes(incidentId)
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
						.param("afterEventSeq", String.valueOf(afterEventSeq))
						.param("limit", String.valueOf(limit)))
				.andExpect(status().isOk()).andReturn();
		return objectMapper.readValue(result.getResponse().getContentAsString(), IncidentNotesPage.class);
	}

	private String noteBody(String text, String observedAt, Long authorId) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("text", text);
		if (observedAt != null) {
			body.put("observedAt", observedAt);
		}
		if (authorId != null) {
			body.put("authorId", authorId);
		}
		return objectMapper.writeValueAsString(body);
	}

	static String relieverToken() {
		return Kan16JwtTestConfiguration.signer().token(TestJwtSigner.claims()
				.issuer(HUMAN_ISSUER).audience(HUMAN_AUDIENCE).subject(RELIEVER_SUBJECT)
				.claim("azp", "dashboard-web").expiresAt(Instant.now().plusSeconds(300)));
	}

	private static MockHttpServletRequestBuilder postNotes(String incidentId, String body) {
		return post(NOTES_PATH, incidentId).contentType(MediaType.APPLICATION_JSON).content(body);
	}

	private static MockHttpServletRequestBuilder getNotes(String incidentId) {
		return get(NOTES_PATH, incidentId).accept(MediaType.APPLICATION_JSON);
	}
}
