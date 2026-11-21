package ru.hardcode.dashboard;

import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import ru.hardcode.dashboard.client.Kan16JwtTestConfiguration;
import ru.hardcode.dashboard.client.TestJwtSigner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * KAN-42: the similar-incident suggestions are a deterministic read over the published
 * snapshot. A candidate shares the catalog service and the 72-hour registration window;
 * the response explains every match and never creates a relation.
 */
class DashboardSimilarIncidentsIT extends DashboardIncidentTestSupport {

	private static final String SIMILAR = PATH + "/{id}/similar";
	private static final String PRODUCT = "Розничная сеть";
	private static final String WIFI = "Wi-Fi авторизация";
	private static final String MOBILE = "Мобильный интернет";
	private static final String SCENARIO = "Вход";
	private static final String ERROR_CODE = "ERR-17";

	@Test
	void suggestsSimilarIncidentsWithExplainedMatchesAndCreatesNothing() throws Exception {
		var batch = receive(1, snapshot(
				record("INC000001", "Assigned", "2026-11-21T12:00:00Z", WIFI, SCENARIO, ERROR_CODE, false,
						"Отказ Wi-Fi в Ростове"),
				record("INC000002", "Resolved", "2026-11-21T10:00:00Z", WIFI, SCENARIO, ERROR_CODE, false,
						"Отказ Wi-Fi в Москве"),
				record("INC000003", "Assigned", "2026-11-21T13:30:00Z", WIFI, null, null, false,
						"Единичные сбои Wi-Fi"),
				// A different service and an isDouble flag alone never create a suggestion.
				record("INC000004", "Assigned", "2026-11-21T12:30:00Z", MOBILE, SCENARIO, ERROR_CODE, false,
						"Отказ мобильного интернета"),
				record("INC000005", "Assigned", "2026-11-25T12:00:00Z", WIFI, SCENARIO, ERROR_CODE, false,
						"Повторный отказ Wi-Fi"),
				record("INC000006", "Assigned", "2026-11-21T12:10:00Z", MOBILE, null, null, true,
						"Дубль отказа мобильного интернета")));
		publish(batch);
		long revision = jdbc.queryForObject(
				"SELECT processing_revision FROM itsm_ingest_batch WHERE id = ?", Long.class, batch);
		long journalBefore = journalSize();
		var countersBefore = publicationCounters();

		var first = mvc.perform(authenticatedGet(SIMILAR, "INC000001")).andExpect(status().isOk())
				.andExpect(jsonPath("$.incidentId").value("INC000001"))
				.andExpect(jsonPath("$.eventSeq").value(1))
				.andExpect(jsonPath("$.revision").value(revision))
				.andExpect(jsonPath("$.candidates[*].incidentId", contains("INC000002", "INC000003")))
				.andExpect(jsonPath("$.candidates[0].score").value(110))
				.andExpect(jsonPath("$.candidates[0].reasons", contains(
						"Совпадает сервис: «" + WIFI + "»",
						"Регистрации отличаются на 2 ч — в пределах окна 72 ч",
						"Совпадает сценарий: «" + SCENARIO + "»",
						"Совпадает код ошибки: «" + ERROR_CODE + "»")))
				.andExpect(jsonPath("$.candidates[0].sharedAttributes.productName").value(PRODUCT))
				.andExpect(jsonPath("$.candidates[0].sharedAttributes.service").value(WIFI))
				.andExpect(jsonPath("$.candidates[0].sharedAttributes.scenarioName").value(SCENARIO))
				.andExpect(jsonPath("$.candidates[0].sharedAttributes.errorCode").value(ERROR_CODE))
				.andExpect(jsonPath("$.candidates[0].header").value("Отказ Wi-Fi в Москве"))
				.andExpect(jsonPath("$.candidates[0].stateEnum").value("Resolved"))
				.andExpect(jsonPath("$.candidates[0].priorityId").value(4))
				.andExpect(jsonPath("$.candidates[0].submitDate").value("2026-11-21T10:00:00Z"))
				.andExpect(jsonPath("$.candidates[0].submitDifferenceSeconds").value(7200))
				.andExpect(jsonPath("$.candidates[0].isDouble").value(false))
				.andExpect(jsonPath("$.candidates[0].isMaster").value(false))
				.andExpect(jsonPath("$.candidates[1].score").value(60))
				.andExpect(jsonPath("$.candidates[1].reasons", contains(
						"Совпадает сервис: «" + WIFI + "»",
						"Регистрации отличаются на 1 ч 30 мин — в пределах окна 72 ч")))
				.andExpect(jsonPath("$.candidates[1].sharedAttributes.scenarioName").value(nullValue()))
				.andExpect(jsonPath("$.candidates[1].sharedAttributes.errorCode").value(nullValue()))
				.andExpect(jsonPath("$.candidates[1].stateEnum").value("Assigned"))
				.andExpect(jsonPath("$.candidates[1].submitDifferenceSeconds").value(5400))
				.andReturn().getResponse().getContentAsString();

		var second = mvc.perform(authenticatedGet(SIMILAR, "INC000001")).andExpect(status().isOk()).andReturn()
				.getResponse().getContentAsString();
		assertThat(second).isEqualTo(first);
		// Reading suggestions writes nothing: no journal rows, no publication counters.
		assertThat(journalSize()).isEqualTo(journalBefore);
		assertThat(publicationCounters()).isEqualTo(countersBefore);
	}

	@Test
	void keepsTheWindowBoundaryInclusiveAndEqualScoresDeterministic() throws Exception {
		publish(receive(1, snapshot(
				record("INC000010", "Assigned", "2026-11-21T12:00:00Z", WIFI, null, null, false, "Отказ Wi-Fi"),
				// Both sides of the threshold, exactly at the inclusive edge.
				record("INC000011", "Assigned", "2026-11-18T12:00:00Z", WIFI, null, null, false, "Отказ Wi-Fi"),
				record("INC000012", "Assigned", "2026-11-24T12:00:00Z", WIFI, null, null, false, "Отказ Wi-Fi"),
				// One second beyond the window on each side.
				record("INC000013", "Assigned", "2026-11-18T11:59:59Z", WIFI, null, null, false, "Отказ Wi-Fi"),
				record("INC000014", "Assigned", "2026-11-24T12:00:01Z", WIFI, null, null, false, "Отказ Wi-Fi"))));

		var first = mvc.perform(authenticatedGet(SIMILAR, "INC000010")).andExpect(status().isOk())
				.andExpect(jsonPath("$.incidentId").value("INC000010"))
				// Equal scores fall back to the incident id, each number exactly once.
				.andExpect(jsonPath("$.candidates[*].incidentId", contains("INC000011", "INC000012")))
				.andExpect(jsonPath("$.candidates[0].score").value(50))
				.andExpect(jsonPath("$.candidates[0].reasons", contains(
						"Совпадает сервис: «" + WIFI + "»",
						"Регистрации отличаются на 72 ч — в пределах окна 72 ч")))
				.andExpect(jsonPath("$.candidates[1].score").value(50))
				.andExpect(jsonPath("$.candidates[1].submitDifferenceSeconds").value(259_200))
				.andReturn().getResponse().getContentAsString();
		var second = mvc.perform(authenticatedGet(SIMILAR, "INC000010")).andExpect(status().isOk()).andReturn()
				.getResponse().getContentAsString();
		assertThat(second).isEqualTo(first);
		mvc.perform(authenticatedGet(SIMILAR, "INC000010").param("limit", "1")).andExpect(status().isOk())
				.andExpect(jsonPath("$.candidates[*].incidentId", contains("INC000011")));
	}

	@Test
	void returnsAnEmptyListWhenTheServiceIsUnknownOrNothingMatches() throws Exception {
		publish(receive(1, snapshot(
				record("INC000020", "Assigned", "2026-11-21T12:00:00Z", null, null, null, false, "Сбой без сервиса"),
				record("INC000021", "Assigned", "2026-11-21T12:00:00Z", WIFI, null, null, false, "Отказ Wi-Fi"),
				// Same service, but registered far outside the window.
				record("INC000022", "Assigned", "2026-12-01T00:00:00Z", WIFI, null, null, false, "Отказ Wi-Fi"))));
		mvc.perform(authenticatedGet(SIMILAR, "INC000020")).andExpect(status().isOk())
				.andExpect(jsonPath("$.incidentId").value("INC000020"))
				.andExpect(jsonPath("$.eventSeq").value(1))
				.andExpect(jsonPath("$.candidates").isEmpty());
		mvc.perform(authenticatedGet(SIMILAR, "INC000021")).andExpect(status().isOk())
				.andExpect(jsonPath("$.incidentId").value("INC000021"))
				.andExpect(jsonPath("$.candidates").isEmpty());
	}

	@Test
	void rejectsUnknownIncidentsAndInvalidRequestsLikeTheCardRead() throws Exception {
		var pending = receive(1, snapshot(
				record("INC000001", "Assigned", "2026-11-21T12:00:00Z", WIFI, SCENARIO, ERROR_CODE, false,
						"Отказ Wi-Fi")));
		mvc.perform(authenticatedGet(SIMILAR, "INC000001")).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("INCIDENT_NOT_FOUND"))
				.andExpect(jsonPath("$.message").value("Incident not found"))
				.andExpect(jsonPath("$.eventSeq").value(0)).andExpect(jsonPath("$.revision").value(0));
		publish(pending);
		long revision = jdbc.queryForObject(
				"SELECT processing_revision FROM itsm_ingest_batch WHERE id = ?", Long.class, pending);
		mvc.perform(authenticatedGet(SIMILAR, "INC000009")).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("INCIDENT_NOT_FOUND"))
				.andExpect(jsonPath("$.message").value("Incident INC000009 not found"))
				.andExpect(jsonPath("$.eventSeq").value(1)).andExpect(jsonPath("$.revision").value(revision));
		mvc.perform(authenticatedGet(SIMILAR, "21931151")).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_INCIDENT_ID"))
				.andExpect(jsonPath("$.message").value(
						"incidentId must be an INC identifier such as INC000021095531"));
		mvc.perform(authenticatedGet(SIMILAR, "INC000001").param("limit", "0")).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_LIMIT"))
				.andExpect(jsonPath("$.message").value("limit must be between 1 and 100"));
		mvc.perform(authenticatedGet(SIMILAR, "INC000001").param("limit", "101")).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_LIMIT"));
		for (String malformed : new String[]{"abc", "", "2147483648"}) {
			mvc.perform(authenticatedGet(SIMILAR, "INC000001").param("limit", malformed))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.code").value("INVALID_LIMIT"))
					.andExpect(jsonPath("$.message").value("limit must be between 1 and 100"));
		}
	}

	@Test
	void requiresTheCoordinatorChainAndDocumentsBothAuthenticationErrors() throws Exception {
		publish(receive(1, snapshot(
				record("INC000001", "Assigned", "2026-11-21T12:00:00Z", WIFI, SCENARIO, ERROR_CODE, false,
						"Отказ Wi-Fi"))));
		mvc.perform(get(SIMILAR, "INC000001").accept(MediaType.APPLICATION_JSON))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("AUTH_REQUIRED"))
				.andExpect(jsonPath("$.message").value("Authentication is required"));
		mvc.perform(get(SIMILAR, "INC000001").accept(MediaType.APPLICATION_JSON)
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + token("itsm-import")))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
		mvc.perform(get(SIMILAR, "INC000001").accept(MediaType.APPLICATION_JSON).header(HttpHeaders.AUTHORIZATION,
						"Bearer " + token(HUMAN_AUDIENCE, "other-web")))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("COORDINATOR_ACCESS_DENIED"))
				.andExpect(jsonPath("$.message").value("Coordinator access is not granted"));
	}

	private long journalSize() {
		Long size = jdbc.queryForObject("SELECT count(*) FROM itsm_incident_change", Long.class);
		return size == null ? 0 : size;
	}

	private Map<String, Object> publicationCounters() {
		return jdbc.queryForMap("SELECT event_seq, revision FROM dashboard_publication WHERE singleton");
	}

	private static String snapshot(String... records) {
		return "[" + String.join(",", records) + "]";
	}

	private static String record(String id, String stateEnum, String submit, String service, String scenario,
			String errorCode, boolean isDouble, String header) {
		boolean completed = "Resolved".equals(stateEnum) || "Closed".equals(stateEnum) || "Research".equals(stateEnum);
		return """
				{"id":"%s","stateEnum":"%s","priorityId":4,"submitDate":"%s","productName":"%s",
				 "service":%s,"scenarioName":%s,"errorCode":%s,"header":"%s",
				 "completionDate":%s,"solution":%s,"isDouble":%s,"isMaster":false}
				""".formatted(id, stateEnum, submit, PRODUCT, text(service), text(scenario), text(errorCode), header,
				completed ? "\"2026-11-20T18:00:00Z\"" : "null",
				completed ? "\"Перезапущены контроллеры доступа\"" : "null", isDouble);
	}

	private static String text(String value) {
		return value == null ? "null" : "\"" + value + "\"";
	}

	private static String token(String audience) {
		return token(audience, "dashboard-web");
	}

	private static String token(String audience, String clientId) {
		return Kan16JwtTestConfiguration.signer().token(TestJwtSigner.claims()
				.issuer(HUMAN_ISSUER).audience(audience).subject(HUMAN_SUBJECT)
				.claim("azp", clientId)
				.expiresAt(Instant.now().plusSeconds(300)));
	}
}
