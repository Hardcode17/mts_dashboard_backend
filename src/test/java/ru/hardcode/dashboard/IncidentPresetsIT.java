package ru.hardcode.dashboard;

import java.time.Instant;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import ru.hardcode.dashboard.client.Kan16JwtTestConfiguration;
import ru.hardcode.dashboard.client.TestJwtSigner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class IncidentPresetsIT extends DashboardIncidentTestSupport {
	private static final String PRESETS = "/api/dashboard/incident-presets";
	private static final String OTHER = "kan45-other-coordinator";
	private static final String PRESET = """
			{"name":"Платежи P3","queue":"all","filters":{"q":"нет совпадений","product":"Платёжный шлюз","priority":"3","deadline":"none","sort":"registered"}}
			""";

	@Test
	void persistsPerOwnerAcrossTokensAndAllowsSameNameForTwoCoordinators() throws Exception {
		String owner = humanToken();
		String other = otherCoordinator();
		long id = create(owner, PRESET);
		String refreshed = Kan16JwtTestConfiguration.signer().token(TestJwtSigner.claims()
				.issuer(HUMAN_ISSUER).audience(HUMAN_AUDIENCE).subject(HUMAN_SUBJECT)
				.claim("azp", "dashboard-web").expiresAt(Instant.now().plusSeconds(500)));
		mvc.perform(get(PRESETS).header(HttpHeaders.AUTHORIZATION, bearer(refreshed)))
				.andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value((int) id))
				.andExpect(jsonPath("$[0].filters.q").value("нет совпадений"))
				.andExpect(jsonPath("$[0].filters.priority").value("3"));

		mvc.perform(get(PRESETS).header(HttpHeaders.AUTHORIZATION, bearer(other)))
				.andExpect(status().isOk()).andExpect(jsonPath("$").isEmpty());
		assertThat(create(other, PRESET)).isNotEqualTo(id);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM coordinator_incident_presets WHERE name = 'Платежи P3'", Long.class))
				.isEqualTo(2);
	}

	@Test
	void hidesOtherOwnersAndRejectsAnOwnerInTheBody() throws Exception {
		String owner = humanToken();
		String other = otherCoordinator();
		long id = create(owner, PRESET);
		for (var request : new MockHttpServletRequestBuilder[]{
				get(PRESETS + "/" + id), delete(PRESETS + "/" + id),
				put(PRESETS + "/" + id).contentType(MediaType.APPLICATION_JSON).content(PRESET)}) {
			mvc.perform(request.header(HttpHeaders.AUTHORIZATION, bearer(other)))
					.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("INCIDENT_PRESET_NOT_FOUND"))
					.andExpect(jsonPath("$.message").value("Incident preset was not found"));
		}
		mvc.perform(post(PRESETS).header(HttpHeaders.AUTHORIZATION, bearer(other))
						.contentType(MediaType.APPLICATION_JSON)
						.content(PRESET.replace("\"queue\":", "\"ownerId\":2,\"queue\":")))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INCIDENT_PRESET"))
				.andExpect(jsonPath("$.message").value("Invalid incident preset"));
		mvc.perform(get(PRESETS + "/" + id).header(HttpHeaders.AUTHORIZATION, bearer(owner)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Платежи P3"));
	}

	@Test
	void rejectsInvalidFiltersAndSupportsOwnerRenameAndDelete() throws Exception {
		String owner = humanToken();
		long id = create(owner, PRESET);
		mvc.perform(post(PRESETS).header(HttpHeaders.AUTHORIZATION, bearer(owner))
						.contentType(MediaType.APPLICATION_JSON).content(PRESET))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INCIDENT_PRESET_NAME_EXISTS"))
				.andExpect(jsonPath("$.message").value("An incident preset with this name already exists"));
		mvc.perform(put(PRESETS + "/" + id).header(HttpHeaders.AUTHORIZATION, bearer(owner))
						.contentType(MediaType.APPLICATION_JSON).content(PRESET.replace("\"3\"", "\"7\"")))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INCIDENT_PRESET"))
				.andExpect(jsonPath("$.message").value("Invalid incident preset"));
		mvc.perform(put(PRESETS + "/" + id).header(HttpHeaders.AUTHORIZATION, bearer(owner))
						.contentType(MediaType.APPLICATION_JSON).content(PRESET.replace("Платежи P3", "Мой новый вид")))
				.andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Мой новый вид"));
		mvc.perform(delete(PRESETS + "/" + id).header(HttpHeaders.AUTHORIZATION, bearer(owner)))
				.andExpect(status().isNoContent());
		assertThat(jdbc.queryForObject("SELECT count(*) FROM coordinator_incident_presets WHERE id = ?", Long.class, id))
				.isZero();
	}

	@BeforeEach
	void clearPresets() {
		jdbc.update("DELETE FROM coordinator_incident_presets");
	}

	private String otherCoordinator() {
		jdbc.update("""
				INSERT INTO users (identity_issuer, identity_subject, login, display_name)
				VALUES (?, ?, 'presets-other', 'Другой координатор')
				ON CONFLICT (identity_issuer, identity_subject) DO NOTHING
				""", HUMAN_ISSUER, OTHER);
		return Kan16JwtTestConfiguration.signer().token(TestJwtSigner.claims()
				.issuer(HUMAN_ISSUER).audience(HUMAN_AUDIENCE).subject(OTHER)
				.claim("azp", "dashboard-web").expiresAt(Instant.now().plusSeconds(300)));
	}

	private long create(String token, String body) throws Exception {
		String content = mvc.perform(post(PRESETS).header(HttpHeaders.AUTHORIZATION, bearer(token))
						.contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isCreated()).andExpect(jsonPath("$.ownerId").doesNotExist())
				.andReturn().getResponse().getContentAsString();
		return ((Number) JsonPath.read(content, "$.id")).longValue();
	}

	private static String bearer(String token) {
		return "Bearer " + token;
	}
}
