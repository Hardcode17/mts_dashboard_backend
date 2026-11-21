package ru.hardcode.dashboard;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import ru.hardcode.dashboard.client.Kan16JwtTestConfiguration;
import ru.hardcode.dashboard.client.TestJwtSigner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(DashboardIncidentCardIT.FixedClock.class)
class DashboardIncidentCardIT extends DashboardIncidentTestSupport {

	private static final Instant NOW = Instant.parse("2026-11-21T12:02:29Z");
	private static final String ISSUER = "https://keycloak.test/realms/dashboard";
	private static final String AUDIENCE = "dashboard-human";
	private static final String KNOWN_INC = "INC000021931151";
	private static final String LINK_PREFIX = "https://itsm.example.org/demo/card?number=";

	@DynamicPropertySource
	static void configuration(DynamicPropertyRegistry registry) {
		registry.add("app.auth.human.issuer", () -> ISSUER);
		registry.add("app.auth.human.audience", () -> AUDIENCE);
		registry.add("app.auth.human.jwk-set-uri", Kan16JwtTestConfiguration::jwkSetUri);
		registry.add("dashboard.itsm.link-template", () -> LINK_PREFIX + "{incidentId}");
		registry.add("dashboard.itsm.link-demo", () -> true);
	}

	@Test
	void opensTheSelectedPublishedIncAndItsExactLinkWithoutChangingAssignmentOrHistory() throws Exception {
		publish(receive(42, baseline()));
		jdbc.update("UPDATE itsm_incident SET local_data = '{\"task\":\"assigned\",\"ack\":false}' WHERE id = ?", KNOWN_INC);
		var before = persistedState(KNOWN_INC);

		var list = mvc.perform(authenticatedGet(PATH).param("group", "coordinator")).andExpect(status().isOk())
				.andExpect(jsonPath("$.items[*].id", contains("INC000021938960", KNOWN_INC)))
				.andReturn().getResponse().getContentAsString();
		String id = JsonPath.read(list, "$.items[1].id");
		int revision = JsonPath.read(list, "$.revision");

		var card = mvc.perform(card(id)).andExpect(status().isOk())
				.andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
				.andExpect(jsonPath("$.id").value(id))
				.andExpect(jsonPath("$.header").value("Отказ Объектив (Камера-сканер)"))
				.andExpect(jsonPath("$.description").isNotEmpty())
				.andExpect(jsonPath("$.productName").value("Автопросматриватель TicTok"))
				.andExpect(jsonPath("$.productMasterId").value("BI_3244"))
				.andExpect(jsonPath("$.service").value("[Автопросматриватель TicTok] Камера-сканер"))
				.andExpect(jsonPath("$.serviceType").value("Технологический"))
				.andExpect(jsonPath("$.assignedGroupName").value(COORDINATORS))
				.andExpect(jsonPath("$.assignedEmployee").value("Дядя Фёдор (unclefedor)"))
				.andExpect(jsonPath("$.priorityId").value(1))
				.andExpect(jsonPath("$.stateEnum").value("Assigned"))
				.andExpect(jsonPath("$.stateReason").value("Нет"))
				.andExpect(jsonPath("$.submitDate").value("2026-11-08T12:02:29Z"))
				.andExpect(jsonPath("$.lastAssignmentToTeamDateTime").value("2026-11-08T12:02:29Z"))
				.andExpect(jsonPath("$.deadline").value("2026-11-22T12:02:29Z"))
				.andExpect(jsonPath("$.completionDate").value(nullValue()))
				.andExpect(jsonPath("$.solution").value(nullValue()))
				.andExpect(jsonPath("$.remainingSeconds").value(86_400))
				.andExpect(jsonPath("$.overdue").value(false))
				.andExpect(jsonPath("$.itsmUrl").value(LINK_PREFIX + id))
				.andExpect(jsonPath("$.itsmDemo").value(true))
				.andExpect(jsonPath("$.groupMemberships[*].group", contains("coordinator")))
				.andExpect(jsonPath("$.eventSeq").value(1))
				.andExpect(jsonPath("$.revision").value(revision)).andReturn().getResponse().getContentAsString();
		mvc.perform(card(id)).andExpect(status().isOk())
				.andExpect(result -> assertThat(result.getResponse().getContentAsString()).isEqualTo(card));
		assertThat(persistedState(id)).isEqualTo(before);
	}

	@Test
	void returnsDocumentedErrorsAndDoesNotReadUnpublishedOrRemovedIncidents() throws Exception {
		var pending = receive(1, baseline());
		mvc.perform(card(KNOWN_INC)).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("INCIDENT_NOT_FOUND"))
				.andExpect(jsonPath("$.message").value("Incident not found"))
				.andExpect(jsonPath("$.eventSeq").value(0)).andExpect(jsonPath("$.revision").value(0));
		publish(pending);
		long revision = jdbc.queryForObject("SELECT processing_revision FROM itsm_ingest_batch WHERE id = ?", Long.class, pending);
		mvc.perform(card("INC999999")).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("INCIDENT_NOT_FOUND"))
				.andExpect(jsonPath("$.message").value("Incident INC999999 not found"))
				.andExpect(jsonPath("$.eventSeq").value(1)).andExpect(jsonPath("$.revision").value(revision));
		mvc.perform(card("21931151")).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_INCIDENT_ID"))
				.andExpect(jsonPath("$.message").value("incidentId must be an INC identifier such as INC000021095531"));
		mvc.perform(get(PATH + "/{id}", KNOWN_INC).accept(MediaType.APPLICATION_JSON))
				.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTH_REQUIRED"))
				.andExpect(jsonPath("$.message").value("Authentication is required"));
		mvc.perform(get(PATH + "/{id}", KNOWN_INC).accept(MediaType.APPLICATION_JSON)
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + token("itsm-import")))
				.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
		mvc.perform(authenticatedGet("/api/incidents/{id}", KNOWN_INC)).andExpect(status().isNotFound());

		var emptyBatch = receive(2, "[]");
		mvc.perform(card(KNOWN_INC)).andExpect(status().isOk()).andExpect(jsonPath("$.eventSeq").value(1));
		publish(emptyBatch);
		mvc.perform(card(KNOWN_INC)).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("INCIDENT_NOT_FOUND"))
				.andExpect(jsonPath("$.eventSeq").value(2));
	}

	@Test
	void keepsAllThreeOriginalMissingDeadlinesNull() throws Exception {
		publish(receive(1, baseline()));
		for (String id : List.of("INC000021932868", "INC000021938764", "INC000021939297")) {
			mvc.perform(card(id)).andExpect(status().isOk())
					.andExpect(jsonPath("$.stateEnum").value("Research"))
					.andExpect(jsonPath("$.deadline").value(nullValue()))
					.andExpect(jsonPath("$.remainingSeconds").value(nullValue()))
					.andExpect(jsonPath("$.overdue").value(false))
					.andExpect(jsonPath("$.itsmUrl").value(LINK_PREFIX + id));
		}
	}

	@Test
	void usesServerClockAtMicrosecondBoundariesAndDoesNotComputeAnSlaForResearch() throws Exception {
		publish(receive(1, "[" + String.join(",",
				incident("INC000001", "Assigned", "2026-11-21T12:02:29.000001Z"),
				incident("INC000002", "WorkInProgress", NOW.toString()),
				incident("INC000003", "Assigned", "2026-11-21T12:02:28.999999Z"),
				incident("INC000004", "Research", "2026-11-21T12:02:28.999999Z"),
				incident("INC000005", "Assigned", "0001-01-01T00:00:00Z"),
				incident("INC000006", "Assigned", null)) + "]"));
		mvc.perform(card("INC000001")).andExpect(status().isOk())
				.andExpect(jsonPath("$.deadline").value("2026-11-21T12:02:29.000001Z"))
				.andExpect(jsonPath("$.remainingSeconds").value(0)).andExpect(jsonPath("$.overdue").value(false))
				.andExpect(jsonPath("$.itsmUrl").value(LINK_PREFIX + "INC000001"))
				.andExpect(jsonPath("$.assignedEmployee").value(nullValue()));
		mvc.perform(card("INC000002")).andExpect(status().isOk())
				.andExpect(jsonPath("$.deadline").value(NOW.toString()))
				.andExpect(jsonPath("$.remainingSeconds").value(0)).andExpect(jsonPath("$.overdue").value(false));
		mvc.perform(card("INC000003")).andExpect(status().isOk())
				.andExpect(jsonPath("$.remainingSeconds").value(-1)).andExpect(jsonPath("$.overdue").value(true));
		mvc.perform(card("INC000004")).andExpect(status().isOk())
				.andExpect(jsonPath("$.deadline").value("2026-11-21T12:02:28.999999Z"))
				.andExpect(jsonPath("$.completionDate").value("2026-11-20T12:00:00Z"))
				.andExpect(jsonPath("$.solution").value("Fixed"))
				.andExpect(jsonPath("$.remainingSeconds").value(nullValue()))
				.andExpect(jsonPath("$.overdue").value(false));
		for (String id : List.of("INC000005", "INC000006")) {
			mvc.perform(card(id)).andExpect(status().isOk())
					.andExpect(jsonPath("$.deadline").value(nullValue()))
					.andExpect(jsonPath("$.remainingSeconds").value(nullValue()))
					.andExpect(jsonPath("$.overdue").value(false));
		}
	}

	@Override
	MockHttpServletRequestBuilder authenticatedGet(String path, Object... variables) {
		return get(path, variables).accept(MediaType.APPLICATION_JSON)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token(AUDIENCE));
	}

	private MockHttpServletRequestBuilder card(String id) {
		return authenticatedGet(PATH + "/{id}", id);
	}

	private static String token(String audience) {
		return Kan16JwtTestConfiguration.signer().token(TestJwtSigner.claims().issuer(ISSUER).audience(audience)
				.subject(HUMAN_SUBJECT).claim("azp", "dashboard-web")
				.expiresAt(Instant.now().plusSeconds(300)));
	}

	private String persistedState(String id) {
		return jdbc.queryForObject("""
				SELECT jsonb_build_object(
				    'incident', to_jsonb(incident),
				    'assignment', to_jsonb(assignment),
				    'snapshot', (SELECT to_jsonb(snapshot) FROM itsm_snapshot AS snapshot),
				    'head', (SELECT to_jsonb(source) FROM itsm_snapshot_source AS source
				             WHERE source.source_id = incident.source_id),
				    'journal', (SELECT jsonb_agg(change ORDER BY change.change_revision) FROM itsm_incident_change AS change
				                WHERE change.incident_id = incident.id),
				    'journalSize', (SELECT count(*) FROM itsm_incident_change)
				)::text
				FROM itsm_incident AS incident
				JOIN itsm_external_assignment AS assignment ON assignment.incident_id = incident.id
				WHERE incident.id = ?
				""", String.class, id);
	}

	private static String incident(String id, String state, String deadline) {
		boolean research = state.equals("Research");
		return """
				{"id":"%s","stateEnum":"%s","priorityId":4,"submitDate":"2026-11-15T09:00:00Z",
				 "productName":"Test product","productUrl":"https://product.example.org/",
				 "completionDate":%s,"solution":%s%s}
				""".formatted(id, state, research ? "\"2026-11-20T12:00:00Z\"" : "null", research ? "\"Fixed\"" : "null",
				deadline == null ? "" : ",\"deadline\":\"" + deadline + "\"");
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class FixedClock {
		@Bean
		@Primary
		Clock dashboardTestClock() {
			return Clock.fixed(NOW, ZoneOffset.UTC);
		}
	}
}
