package ru.hardcode.dashboard;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import ru.hardcode.dashboard.repository.DeadlineRiskRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * KAN-82 end-to-end: a snapshot publication triggers one scoring pass, and the card exposes the
 * stored result. The clock is fixed, so the scoring is deterministic.
 */
@Import(DeadlineRiskIT.FixedClock.class)
class DeadlineRiskIT extends DashboardIncidentTestSupport {

	private static final Instant NOW = Instant.parse("2026-11-21T12:02:29Z");

	@Autowired DeadlineRiskRepository riskRepository;

	@Test
	void usesServicePriorityAndGroupMedianBeforeBroaderHistory() throws Exception {
		publish(receive(1, "[" + String.join(",",
				withService(completed("INC0000100011", "2026-11-20T08:00:00Z", "2026-11-20T10:00:00Z"), "A"),
				withService(completed("INC0000100012", "2026-11-20T08:00:00Z", "2026-11-20T18:00:00Z"), "B"),
				withService(active("INC0000100013", "2026-11-21T13:02:29Z"), "A"),
				withService(active("INC0000100014", "2026-11-21T13:02:29Z"), "B")) + "]"));

		mvc.perform(authenticatedGet(PATH + "/{id}", "INC0000100013"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.deadlineRisk.score").value(2.0));
		mvc.perform(authenticatedGet(PATH + "/{id}", "INC0000100014"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.deadlineRisk.score").value(10.0));
	}

	private static String withService(String incident, String service) {
		return incident.replace("\"productName\":\"Test product\"",
				"\"productName\":\"Test product\",\"service\":\"" + service + "\"");
	}

	@Test
	void countsOnlyExtensionsAndDetectsAssigneeNameChanges() {
		publish(receive(1, "[" + activeWithAssignee("2026-11-21T15:02:29Z", "Alice") + "]"));
		publish(receive(2, "[" + activeWithAssignee("2026-11-21T14:02:29Z", "Bob") + "]"));
		publish(receive(3, "[" + activeWithAssignee("2026-11-21T16:02:29Z", "Bob") + "]"));

		var incident = riskRepository.findActiveWithDeadline().getFirst();
		assertThat(incident.deadlineExtensions()).isEqualTo(1);
		assertThat(incident.reassignments()).isEqualTo(1);
	}

	private static String activeWithAssignee(String deadline, String assignee) {
		return """
				{"id":"INC0000100007","stateEnum":"Assigned","priorityId":4,
				 "submitDate":"2026-11-21T12:02:29Z","productName":"Test product",
				 "assignedGroupName":"Support","assignedEmployee":"%s","deadline":"%s","solution":null}
				""".formatted(assignee, deadline);
	}

	@Test
	void scoresActiveIncidentsWithDeadlineOnEverySnapshotPublication() throws Exception {
		publish(receive(1, "[" + String.join(",",
				completed("INC0000100001", "2026-11-20T08:00:00Z", "2026-11-20T10:00:00Z"),
				active("INC0000100002", "2026-11-21T13:02:29Z")) + "]"));

		// The only completed Support×P4 incident took 2 h; one hour is left, so
		// x1 = 2 and score = -1 + 2 + 4*0.25 = 2.0 -> high with the placeholder weights.
		mvc.perform(authenticatedGet(PATH + "/{id}", "INC0000100002"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.deadlineRisk.risk").value("high"))
				.andExpect(jsonPath("$.deadlineRisk.evaluatedAt").value(NOW.toString()))
				.andExpect(jsonPath("$.deadlineRisk.reasons[0].code").value("group_median_exceeds"))
				.andExpect(jsonPath("$.deadlineRisk.reasons[0].text")
						.value("Медиана группы решения «Support»: 2.0 ч, до дедлайна 1.0 ч"))
				.andExpect(jsonPath("$.deadlineRisk.score").value(2.0));
	}

	@Test
	void aLaterPublicationRefreshesTheStoredScoring() throws Exception {
		publish(receive(1, "[" + active("INC0000100003", "2026-11-22T13:02:29Z") + "]"));

		// No completed history for Support yet: the pass stores unknown with the reason.
		mvc.perform(authenticatedGet(PATH + "/{id}", "INC0000100003"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.deadlineRisk.risk").value("unknown"))
				.andExpect(jsonPath("$.deadlineRisk.reasons[0].code").value("no_history"))
				.andExpect(jsonPath("$.deadlineRisk.reasons[0].text", containsString("Support")))
				.andExpect(jsonPath("$.deadlineRisk.score").value(null));

		publish(receive(2, "[" + String.join(",",
				completed("INC0000100004", "2026-11-20T08:00:00Z", "2026-11-20T10:00:00Z"),
				active("INC0000100003", "2026-11-22T13:02:29Z")) + "]"));

		// With the 2 h median and 25 h left: x1 = 0.08, score = 0.08 -> medium.
		mvc.perform(authenticatedGet(PATH + "/{id}", "INC0000100003"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.deadlineRisk.risk").value("medium"))
				.andExpect(jsonPath("$.deadlineRisk.evaluatedAt").value(NOW.toString()))
				.andExpect(jsonPath("$.deadlineRisk.reasons[0].code").value("group_median"));
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
				.andExpect(jsonPath("$.deadlineRisk.score").value(null));
		mvc.perform(authenticatedGet(PATH + "/{id}", "INC0000100006"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.deadlineRisk.risk").value("unknown"))
				.andExpect(jsonPath("$.deadlineRisk.reasons[0].code").value("not_active"))
				.andExpect(jsonPath("$.deadlineRisk.score").value(null));
	}

	private static String active(String id, String deadline) {
		// Registered exactly at the fixed test clock: x5 (hours without a status change) is 0.
		return """
				{"id":"%s","stateEnum":"Assigned","priorityId":4,"submitDate":"2026-11-21T12:02:29Z",
				 "productName":"Test product","assignedGroupName":"Support","solution":null%s}
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
