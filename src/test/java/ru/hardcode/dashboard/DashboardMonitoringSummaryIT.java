package ru.hardcode.dashboard;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DashboardMonitoringSummaryIT extends DashboardIncidentTestSupport {

	private static final String PATH = "/api/dashboard/monitoring/summary";

	@Test
	void startsWithAnEmptyTimelineAndUsesTheDashboardAuthConvention() throws Exception {
		var pending = receive(1, baseline());
		mvc.perform(authenticatedGet(PATH)).andExpect(status().isOk())
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.updatedAt").value(nullValue()))
				.andExpect(jsonPath("$.activeCount").value(0))
				.andExpect(jsonPath("$.counts.critical").value(0))
				.andExpect(jsonPath("$.counts.high").value(0))
				.andExpect(jsonPath("$.counts.coordinators").value(0))
				.andExpect(jsonPath("$.counts.research").value(0))
				.andExpect(jsonPath("$.timeline").isEmpty())
				.andExpect(jsonPath("$.priorities").isEmpty())
				.andExpect(jsonPath("$.coordinatorLoad").isEmpty());

		mvc.perform(get(PATH).accept(MediaType.APPLICATION_JSON)).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("AUTH_REQUIRED"))
				.andExpect(jsonPath("$.message").value("Authentication is required"));

		publish(pending);
		mvc.perform(authenticatedGet(PATH)).andExpect(status().isOk())
				.andExpect(jsonPath("$.updatedAt").isString())
				.andExpect(jsonPath("$.activeCount").value(24))
				.andExpect(jsonPath("$.counts.critical").value(0))
				.andExpect(jsonPath("$.counts.high").value(1))
				.andExpect(jsonPath("$.counts.coordinators").value(2))
				.andExpect(jsonPath("$.counts.research").value(5))
				.andExpect(jsonPath("$.timeline", hasSize(1)))
				.andExpect(jsonPath("$.timeline[0].at").isString())
				.andExpect(jsonPath("$.timeline[0].critical").value(0))
				.andExpect(jsonPath("$.timeline[0].high").value(1))
				.andExpect(jsonPath("$.timeline[0].coordinators").value(2))
				.andExpect(jsonPath("$.timeline[0].research").value(5))
				.andExpect(jsonPath("$.priorities[*].priorityId", contains(1, 2, 3)))
				.andExpect(jsonPath("$.priorities[*].count", contains(14, 9, 1)))
				.andExpect(jsonPath("$.coordinatorLoad", hasSize(2)))
				.andExpect(jsonPath("$.coordinatorLoad[*].count", contains(1, 1)));
	}

	@Test
	void keepsOverlappingCategoriesAndAPersistedOrdered24HourTimeline() throws Exception {
		UUID first = receive(1, customSnapshot(true));
		publish(first);
		UUID second = receive(2, customSnapshot(false));
		publish(second);

		var result = mvc.perform(authenticatedGet(PATH)).andExpect(status().isOk())
				.andExpect(jsonPath("$.activeCount").value(2))
				.andExpect(jsonPath("$.counts.critical").value(1))
				.andExpect(jsonPath("$.counts.high").value(0))
				.andExpect(jsonPath("$.counts.coordinators").value(2))
				.andExpect(jsonPath("$.counts.research").value(1))
				.andExpect(jsonPath("$.priorities[*].priorityId", contains(2, 4)))
				.andExpect(jsonPath("$.priorities[*].count", contains(1, 1)))
				.andExpect(jsonPath("$.coordinatorLoad[0].name").value("Alice (alice)"))
				.andExpect(jsonPath("$.coordinatorLoad[0].count").value(1))
				.andExpect(jsonPath("$.timeline", hasSize(2)))
				.andExpect(jsonPath("$.timeline[0].critical").value(1))
				.andExpect(jsonPath("$.timeline[0].high").value(1))
				.andExpect(jsonPath("$.timeline[0].coordinators").value(3))
				.andExpect(jsonPath("$.timeline[0].research").value(1))
				.andExpect(jsonPath("$.timeline[1].critical").value(1))
				.andExpect(jsonPath("$.timeline[1].high").value(0))
				.andExpect(jsonPath("$.timeline[1].coordinators").value(2))
				.andExpect(jsonPath("$.timeline[1].research").value(1))
				.andReturn().getResponse().getContentAsString();
		assertThat(result).doesNotContain("\"timeline\":[]");
		assertThat(jdbc.queryForObject("SELECT count(*) FROM itsm_monitoring_observation", Long.class)).isEqualTo(2L);

		// Equal timestamps keep the publication order; the DTO must not rely on the sort of an
		// unordered in-memory structure.
		Instant sameInstant = Instant.now().minus(Duration.ofMinutes(5));
		jdbc.update("UPDATE itsm_monitoring_observation SET observed_at = ?", Timestamp.from(sameInstant));
		mvc.perform(authenticatedGet(PATH)).andExpect(status().isOk())
				.andExpect(jsonPath("$.timeline[0].high").value(1))
				.andExpect(jsonPath("$.timeline[1].high").value(0));

		// Only actual observations in the 24-hour window are returned.
		jdbc.update("UPDATE itsm_monitoring_observation SET observed_at = ? WHERE batch_id = ?",
				Timestamp.from(Instant.now().minus(Duration.ofHours(25))), first);
		mvc.perform(authenticatedGet(PATH)).andExpect(status().isOk())
				.andExpect(jsonPath("$.timeline", hasSize(1)))
				.andExpect(jsonPath("$.timeline[0].high").value(0))
				.andExpect(jsonPath("$.timeline[0].at", not(nullValue())));
	}

	private static String customSnapshot(boolean includeHigh) {
		var incidents = new java.util.ArrayList<String>();
		incidents.add(incident("INC000001", "Assigned", 4, COORDINATORS, "\"Alice (alice)\"", null));
		if (includeHigh) {
			incidents.add(incident("INC000002", "WorkInProgress", 3, COORDINATORS, "\"Alice (alice)\"", null));
		}
		incidents.add(incident("INC000003", "Research", 3, COORDINATORS, "\"Bob (bob)\"",
				"\"2026-11-20T12:00:00Z\""));
		incidents.add(incident("INC000004", "Assigned", 2, "Other", null, null));
		incidents.add(incident("INC000005", "Resolved", 4, COORDINATORS, "\"Bob (bob)\"",
				"\"2026-11-20T12:00:00Z\""));
		incidents.add(incident("INC000006", "Closed", 3, COORDINATORS, null, "\"2026-11-20T12:00:00Z\""));
		return "[" + String.join(",", incidents) + "]";
	}

	private static String incident(String id, String state, int priority, String group, String employee,
			String completionDate) {
		boolean completed = completionDate != null;
		String login = employee == null ? "null" : "\"" + employee.replaceAll("[^a-zA-Z]", "").toLowerCase() + "\"";
		return """
				{"id":"%s","stateEnum":"%s","priorityId":%d,"submitDate":"2026-11-15T09:00:00Z",
				 "productName":"Test product","assignedGroupName":"%s","assignedEmployee":%s,
				 "assignedEmployeeLogin":%s,"completionDate":%s,"solution":%s}
				""".formatted(id, state, priority, group, employee == null ? "null" : employee, login,
				completed ? completionDate : "null", completed ? "\"Fixed\"" : "null");
	}
}
