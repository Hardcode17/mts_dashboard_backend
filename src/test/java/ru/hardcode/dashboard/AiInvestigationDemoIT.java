package ru.hardcode.dashboard;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * KAN-40 in the demo deployment without the model profile: no chat model exists, so the
 * launch is refused with one explicit status, nothing is stored, and the incident card of
 * the same INC keeps working. The status is honest by design — it never pretends the
 * investigation ran.
 */
@ActiveProfiles({"demo", "evidence"})
class AiInvestigationDemoIT extends AiInvestigationSupport {

	private static final String CARD_PATH = "/api/dashboard/incidents/{incidentId}";

	@BeforeEach
	void resetForDemo() {
		resetEnvironment();
	}

	@Test
	void demoWithoutModelKeyRefusesTheLaunchExplicitlyAndKeepsTheCardReadable() throws Exception {
		publish(receive(1, snapshot(activeIncident(INCIDENT, HEADER, GROUP_PAYMENTS))));
		mvc.perform(authenticatedGet(CARD_PATH, INCIDENT)).andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(INCIDENT))
				.andExpect(jsonPath("$.header").value(HEADER))
				.andExpect(jsonPath("$.revision").value(revision()));

		mvc.perform(launch(INCIDENT)).andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.code").value("AI_MODEL_NOT_CONFIGURED"))
				.andExpect(jsonPath("$.message").value(
						"The AI investigation model is not configured; enable the model profile and set its API key"));

		// The refusal has no side effects and the card stays readable after it.
		assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_investigation_run", Long.class)).isZero();
		assertThat(MODEL.requests()).isEmpty();
		mvc.perform(authenticatedGet(CARD_PATH, INCIDENT)).andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(INCIDENT))
				.andExpect(jsonPath("$.header").value(HEADER));

		// Runs cannot be read because none exist; the status is the same explicit refusal.
		mvc.perform(authenticatedGet("/api/incidents/{incidentId}/investigations/{runId}",
						INCIDENT, UUID.randomUUID()))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("AI_RUN_NOT_FOUND"));
	}
}
