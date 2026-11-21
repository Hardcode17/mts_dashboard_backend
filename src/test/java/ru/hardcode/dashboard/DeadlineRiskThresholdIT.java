package ru.hardcode.dashboard;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(DeadlineRiskIT.FixedClock.class)
@TestPropertySource(properties = "deadline-risk.mode=threshold")
class DeadlineRiskThresholdIT extends DashboardIncidentTestSupport {

	@Test
	void storesThresholdAssessmentWithoutRegressionScoreOrHistory() throws Exception {
		var id = "INC0000100015";
		publish(receive(1, "[" + DeadlineRiskIT.active(id, "2026-11-21T13:02:29Z") + "]"));

		mvc.perform(authenticatedGet(PATH + "/{id}", id))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.deadlineRisk.risk").value("high"))
				.andExpect(jsonPath("$.deadlineRisk.reasons[0].code").value("remaining_below_threshold"))
				.andExpect(jsonPath("$.deadlineRisk.score").value(nullValue()));
	}
}
