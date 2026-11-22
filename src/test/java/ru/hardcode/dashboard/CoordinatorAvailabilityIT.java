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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CoordinatorAvailabilityIT extends DashboardIncidentTestSupport {
	private static final String WRITE = "/api/me/availability";

	@Test
	void keepsMembershipAndAvailabilityAcrossTokensAndCountsOnlyNewActivations() throws Exception {
		long user = userId();
		long group = enroll(user);

		mvc.perform(authenticatedGet("/api/me"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.availability.groups[0].id").value(group))
				.andExpect(jsonPath("$.availability.available").value(false));

		String firstSince = enable(1);
		assertThat(enable(1)).isEqualTo(firstSince);
		String refreshed = Kan16JwtTestConfiguration.signer().token(TestJwtSigner.claims()
				.issuer(HUMAN_ISSUER).audience(HUMAN_AUDIENCE).subject(HUMAN_SUBJECT)
				.claim("azp", "dashboard-web").expiresAt(Instant.now().plusSeconds(500)));
		mvc.perform(get("/api/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + refreshed))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.availability.available").value(true))
				.andExpect(jsonPath("$.availability.availableSince").value(firstSince));

		mvc.perform(write("{\"available\":false}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.available").value(false))
				.andExpect(jsonPath("$.generation").value(1));
		assertThat(Instant.parse(enable(2))).isAfter(Instant.parse(firstSince));
		assertThat(jdbc.queryForObject("SELECT is_active FROM users WHERE id = ?", Boolean.class, user)).isTrue();
		assertThat(jdbc.queryForObject("SELECT count(*) FROM coordinator_group_members WHERE user_id = ?", Long.class, user))
				.isEqualTo(1);
	}

	@Test
	void refusesInactiveAccountsNonMembersAndAttemptsToTargetAnotherUser() throws Exception {
		long user = userId();
		mvc.perform(write("{\"available\":true}"))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("COORDINATOR_GROUP_REQUIRED"))
				.andExpect(jsonPath("$.message").value("Active coordinator group membership is required"));

		long group = enroll(user);
		long other = jdbc.queryForObject("""
				INSERT INTO users (identity_issuer, identity_subject, login, display_name)
				VALUES (?, 'availability-other', 'other', 'Другой координатор') RETURNING id
				""", Long.class, HUMAN_ISSUER);
		jdbc.update("INSERT INTO coordinator_group_members (coordinator_group_id, user_id) VALUES (?, ?)", group, other);

		mvc.perform(write("{\"available\":true,\"userId\":" + other + "}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").isNotEmpty())
				.andExpect(jsonPath("$.message").isNotEmpty());
		mvc.perform(write("{\"available\":\"true\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_REQUEST_BODY"))
				.andExpect(jsonPath("$.message").value("Request body is malformed or missing"));
		mvc.perform(write("{}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_REQUEST_BODY"))
				.andExpect(jsonPath("$.message").value("Request body is malformed or missing"));
		enable(1);
		jdbc.update("UPDATE users SET is_active = false WHERE id = ?", user);
		try {
			mvc.perform(write("{\"available\":false}"))
					.andExpect(status().isForbidden())
					.andExpect(jsonPath("$.code").value("COORDINATOR_ACCESS_DENIED"))
					.andExpect(jsonPath("$.message").isNotEmpty());
			assertThat(jdbc.queryForObject("SELECT available FROM coordinator_availability WHERE user_id = ?", Boolean.class, user)).isTrue();
			assertThat(jdbc.queryForObject("SELECT count(*) FROM coordinator_availability WHERE user_id = ?", Long.class, other))
					.isZero();
		} finally {
			jdbc.update("UPDATE users SET is_active = true WHERE id = ?", user);
		}
	}

	@BeforeEach
	void clearAvailability() {
		jdbc.update("DELETE FROM coordinator_availability");
		jdbc.update("DELETE FROM coordinator_groups");
	}

	private long userId() {
		return jdbc.queryForObject("SELECT id FROM users WHERE identity_subject = ?", Long.class, HUMAN_SUBJECT);
	}

	private long enroll(long user) {
		long group = jdbc.queryForObject("INSERT INTO coordinator_groups (name) VALUES ('Рабочая группа') RETURNING id", Long.class);
		jdbc.update("INSERT INTO coordinator_group_members (coordinator_group_id, user_id) VALUES (?, ?)", group, user);
		return group;
	}

	private String enable(int generation) throws Exception {
		String result = mvc.perform(write("{\"available\":true}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.available").value(true))
				.andExpect(jsonPath("$.generation").value(generation))
				.andReturn().getResponse().getContentAsString();
		return JsonPath.read(result, "$.availableSince");
	}

	private static MockHttpServletRequestBuilder write(String body) {
		return put(WRITE).accept(MediaType.APPLICATION_JSON).contentType(MediaType.APPLICATION_JSON)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + humanToken()).content(body);
	}
}
