package ru.hardcode.dashboard;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * KAN-82 end-to-end: a snapshot publication triggers one scoring pass, and the card exposes the
 * stored result. The clock is fixed, so the shipped threshold rule gives deterministic categories.
 */
@Import(DeadlineRiskIT.FixedClock.class)
class DeadlineRiskIT extends DashboardIncidentTestSupport {

	private static final Instant NOW = Instant.parse("2026-11-21T12:02:29Z");

	@Test
	void scoresActiveIncidentsWithDeadlineOnEverySnapshotPublication() throws Exception {
		publish(receive(1, "[" + String.join(",",
				completed("INC0000100001", "2026-11-20T08:00:00Z", "2026-11-20T10:00:00Z"),
				active("INC0000100002", "2026-11-21T13:02:29Z")) + "]"));

		// One hour is left, the UCI-calibrated high threshold is 32.92 h, and the only completed
		// Support×P4 incident took 2 h: high with both grounds.
		mvc.perform(authenticatedGet(PATH + "/{id}", "INC0000100002"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.deadlineRisk.risk").value("high"))
				.andExpect(jsonPath("$.deadlineRisk.evaluatedAt").value(NOW.toString()))
				.andExpect(jsonPath("$.deadlineRisk.reasons[0].code").value("remaining_below_threshold"))
				.andExpect(jsonPath("$.deadlineRisk.reasons[0].text").value("До дедлайна 1.0 ч, порог 32.9 ч"))
				.andExpect(jsonPath("$.deadlineRisk.reasons[1].code").value("group_median_exceeds"))
				.andExpect(jsonPath("$.deadlineRisk.reasons[1].text")
						.value("Медиана группы решения «Support»: 2.0 ч, остаток 1.0 ч"))
				.andExpect(jsonPath("$.deadlineRisk.score").value(nullValue()));
	}

	@Test
	void aLaterPublicationRefreshesTheStoredScoring() throws Exception {
		publish(receive(1, "[" + active("INC0000100003", "2026-11-23T13:02:29Z") + "]"));

		// 49 h left: inside the medium buffer zone (32.92, 65.84), without any group history.
		mvc.perform(authenticatedGet(PATH + "/{id}", "INC0000100003"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.deadlineRisk.risk").value("medium"))
				.andExpect(jsonPath("$.deadlineRisk.reasons[0].code").value("remaining_in_buffer"))
				.andExpect(jsonPath("$.deadlineRisk.reasons[0].text").value("До дедлайна 49.0 ч, буфер 65.8 ч"));

		// A later snapshot moves the deadline closer and adds group history: the pass refreshes.
		publish(receive(2, "[" + String.join(",",
				completed("INC0000100004", "2026-11-20T08:00:00Z", "2026-11-20T10:00:00Z"),
				active("INC0000100003", "2026-11-21T13:02:29Z")) + "]"));

		mvc.perform(authenticatedGet(PATH + "/{id}", "INC0000100003"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.deadlineRisk.risk").value("high"))
				.andExpect(jsonPath("$.deadlineRisk.reasons[0].code").value("remaining_below_threshold"))
				.andExpect(jsonPath("$.deadlineRisk.reasons[1].code").value("group_median_exceeds"))
				.andExpect(jsonPath("$.deadlineRisk.reasons[1].text")
						.value("Медиана группы решения «Support»: 2.0 ч, остаток 1.0 ч"));
	}

	@Test
	void cardsWithoutADeadlineOrCompletedStateReportUnknownWithoutBlockingTheCard() throws Exception {
		publish(receive(1, "[" + String.join(",",
				active("INC0000100005", null),
				completed("INC0000100006", "2026-11-20T08:00:00Z", "2026-11-20T10:00:00Z")) + "]"));

		mvc.perform(authenticatedGet(PATH + "/{id}", "INC0000100005"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.deadlineRisk.risk").value("unknown"))
				.andExpect(jsonPath("$.deadlineRisk.reasons[0].code").value("no_deadline"))
				.andExpect(jsonPath("$.deadlineRisk.reasons[0].text")
						.value("У инцидента нет дедлайна — риск не оценивается"))
				.andExpect(jsonPath("$.deadlineRisk.score").value(nullValue()));
		mvc.perform(authenticatedGet(PATH + "/{id}", "INC0000100006"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.deadlineRisk.risk").value("unknown"))
				.andExpect(jsonPath("$.deadlineRisk.reasons[0].code").value("not_active"))
				.andExpect(jsonPath("$.deadlineRisk.score").value(nullValue()));
	}

	private static String active(String id, String deadline) {
		// Registered exactly at the fixed test clock; solution and completionDate are explicit
		// nulls, as the snapshot parser requires the fields to be present.
		return """
				{"id":"%s","stateEnum":"Assigned","priorityId":4,"submitDate":"2026-11-21T12:02:29Z",
				 "productName":"Test product","assignedGroupName":"Support",
				 "completionDate":null,"solution":null%s}
				""".formatted(id, deadline == null ? "" : ",\"deadline\":\"" + deadline + "\"");
	}

	private static String completed(String id, String submit, String completion) {
		return """
				{"id":"%s","stateEnum":"Resolved","priorityId":4,"submitDate":"%s",
				 "productName":"Test product","assignedGroupName":"Support",
				 "completionDate":"%s","solution":"Fixed"}
				""".formatted(id, submit, completion);
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
