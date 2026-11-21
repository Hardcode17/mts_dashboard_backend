package ru.hardcode.dashboard;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DashboardIncidentListIT extends DashboardIncidentTestSupport {

	@Test
	void returnsBaselineGroupCountsAndOrderAfterPublicationOnly() throws Exception {
		UUID pending = receive(42, baseline());
		mvc.perform(listRequest()).andExpect(status().isOk())
				.andExpect(jsonPath("$.beforeFirstSuccess").value(true))
				.andExpect(jsonPath("$.eventSeq").value(0)).andExpect(jsonPath("$.revision").value(0))
				.andExpect(jsonPath("$.items").isEmpty()).andExpect(jsonPath("$.groups").isEmpty());
		publish(pending);
		assertThat(jdbc.queryForObject("SELECT incident_count FROM itsm_snapshot", Integer.class)).isEqualTo(44);

		var result = mvc.perform(listRequest()).andExpect(status().isOk())
				.andExpect(jsonPath("$.beforeFirstSuccess").value(false))
				.andExpect(jsonPath("$.eventSeq").value(1))
				.andExpect(jsonPath("$.groups[*].key", contains("critical", "coordinator", "high", "research")))
				.andExpect(jsonPath("$.groups[*].incidentCount", contains(0, 2, 1, 5)))
				.andExpect(jsonPath("$.groups[0].incidents").isEmpty())
				.andExpect(jsonPath("$.groups[1].incidents[*].id", contains("INC000021938960", "INC000021931151")))
				.andExpect(jsonPath("$.groups[2].incidents[*].id", contains("INC000021938167")))
				.andExpect(jsonPath("$.groups[3].incidents[*].id", contains("INC000021938764", "INC000021939297",
						"INC000021930418", "INC000021932639", "INC000021932868")))
				.andExpect(jsonPath("$.items[*].id", contains(
						"INC000021938960", "INC000021940779", "INC000021940033", "INC000021938764",
						"INC000021938167", "INC000021941816", "INC000021941658", "INC000021941647",
						"INC000021939297", "INC000021940003", "INC000021938435", "INC000021938150",
						"INC000021938770", "INC000021936852", "INC000021936825", "INC000021930418",
						"INC000021935528", "INC000021932639", "INC000021932868", "INC000021933291",
						"INC000021933290", "INC000021933233", "INC000021933097", "INC000021931583",
						"INC000021931582", "INC000021931338", "INC000021931189", "INC000021931151", "INC000021930935")))
				.andExpect(jsonPath("$.nextCursor").value(nullValue())).andReturn();
		String revision = mvc.perform(authenticatedGet("/api/snapshot/status")).andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		assertThat(revision).contains("\"revision\":" + jdbc.queryForObject(
				"SELECT processing_revision FROM itsm_ingest_batch WHERE id = ?", Long.class, pending));
		assertThat(result.getResponse().getContentAsString()).contains("\"revision\":" + jdbc.queryForObject(
				"SELECT processing_revision FROM itsm_ingest_batch WHERE id = ?", Long.class, pending));
	}

	@Test
	void computesOverlappingMembershipIndependentlyOfAssigneeAndExcludesOtherStatuses() throws Exception {
		for (int version = 1; version <= 3; version++) {
			String assignee = version == 2 ? "\"Test assignee\"" : "null";
			publish(receive(version, "[" + String.join(",",
					incident("INC000002", "Assigned", 4, COORDINATORS, assignee),
					incident("INC000001", "Research", 4, COORDINATORS, "null"),
					incident("INC000003", "WorkInProgress", 3, "Other", "null"),
					incident("INC000004", "Assigned", 2, "Other", "null"),
					incident("INC000005", "Resolved", 4, COORDINATORS, "null"),
					incident("INC000006", "Closed", 3, COORDINATORS, "null")) + "]"));
			mvc.perform(listRequest()).andExpect(status().isOk())
					.andExpect(jsonPath("$.items[*].id", contains("INC000001", "INC000002", "INC000003", "INC000004")))
					.andExpect(jsonPath("$.groups[*].incidentCount", contains(1, 2, 1, 1)))
					.andExpect(jsonPath("$.groups[0].incidents[*].id", contains("INC000002")))
					.andExpect(jsonPath("$.groups[1].incidents[*].id", contains("INC000001", "INC000002")))
					.andExpect(jsonPath("$.groups[2].incidents[*].id", contains("INC000003")))
					.andExpect(jsonPath("$.groups[3].incidents[*].id", contains("INC000001")))
					.andExpect(jsonPath("$.items[0].groupMemberships[*].group", contains("coordinator", "research")))
					.andExpect(jsonPath("$.items[1].groupMemberships[*].group", contains("coordinator", "critical")))
					.andExpect(jsonPath("$.items[3].groupMemberships").isEmpty());
			assertThat(jdbc.queryForObject("SELECT source_data->>'assignedEmployee' FROM itsm_incident WHERE id = 'INC000002'",
					String.class)).isEqualTo(version == 2 ? "Test assignee" : null);
		}
		mvc.perform(listRequest().param("group", "critical", "research").param("includeCompleted", "true"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.items[*].id", contains("INC000001", "INC000002")))
				.andExpect(jsonPath("$.groups[*].incidentCount", contains(1, 0, 0, 1)));
		mvc.perform(listRequest().param("includeCompleted", "true")).andExpect(status().isOk())
				.andExpect(jsonPath("$.items", hasSize(6)))
				.andExpect(jsonPath("$.items[4].groupMemberships").isEmpty())
				.andExpect(jsonPath("$.items[5].groupMemberships").isEmpty());
		mvc.perform(authenticatedGet(PATH).accept(MediaType.valueOf("text/csv"))
						.param("includeCompleted", "true"))
				.andExpect(status().isOk())
				.andExpect(content().string(org.hamcrest.Matchers.containsString(
						"INC000002,4,Assigned,,coordinator;critical\r\n")));
	}

	@Test
	void keepsPublishedIncidentsWithAnUnrecognizedPriorityInTheirNonPriorityGroups() throws Exception {
		publish(receive(1, "[" + incident("INC000001", "Assigned", 0, COORDINATORS, "null") + "]"));
		mvc.perform(listRequest()).andExpect(status().isOk())
				.andExpect(jsonPath("$.groups[*].incidentCount", contains(0, 1, 0, 0)))
				.andExpect(jsonPath("$.items[0].priorityId").value(0))
				.andExpect(jsonPath("$.items[0].groupMemberships[*].group", contains("coordinator")));
	}

	@Test
	void anOlderSkippedDeliveryDoesNotAdvanceThePublicationCounter() throws Exception {
		publish(receive(2, baseline()));
		mvc.perform(listRequest()).andExpect(status().isOk())
				.andExpect(jsonPath("$.eventSeq").value(1))
				.andExpect(jsonPath("$.groups[*].incidentCount", contains(0, 2, 1, 5)));

		publish(receive(1, baseline())); // older than the observed version: settled without publication
		mvc.perform(listRequest()).andExpect(status().isOk())
				.andExpect(jsonPath("$.eventSeq").value(1))
				.andExpect(jsonPath("$.groups[*].incidentCount", contains(0, 2, 1, 5)));
	}

	@Test
	void validatesFiltersPaginatesAndExportsWithoutThePageSlice() throws Exception {
		publish(receive(1, baseline()));
		mvc.perform(listRequest().param("group", "critical", "current")).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_GROUP"))
				.andExpect(jsonPath("$.message").value("Unknown dashboard group current"));
		mvc.perform(listRequest().param("group", "current").accept(MediaType.valueOf("text/csv")))
				.andExpect(status().isBadRequest()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
				.andExpect(jsonPath("$.code").value("INVALID_GROUP"))
				.andExpect(jsonPath("$.message").value("Unknown dashboard group current"));
		mvc.perform(listRequest().param("limit", "101")).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_LIMIT"));
		mvc.perform(get(PATH).accept(MediaType.APPLICATION_JSON)).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("AUTH_REQUIRED"))
				.andExpect(jsonPath("$.message").value("Authentication is required"));
		// `*/*` and a missing Accept header mean the documented application/json default,
		// not the browser form redirect.
		for (var request : List.of(get(PATH), get(PATH).accept(MediaType.ALL))) {
			mvc.perform(request).andExpect(status().isUnauthorized())
					.andExpect(jsonPath("$.code").value("AUTH_REQUIRED"))
					.andExpect(jsonPath("$.message").value("Authentication is required"));
		}
		// The dashboard reads belong to the stateless human Bearer chain (KAN-22): an
		// unauthenticated browser also receives the documented 401 JSON, not a form redirect.
		mvc.perform(get(PATH).accept("text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("AUTH_REQUIRED"))
				.andExpect(jsonPath("$.message").value("Authentication is required"));

		var filtered = listRequest().param("group", "coordinator").param("limit", "1");
		var first = mvc.perform(filtered).andExpect(status().isOk()).andExpect(header().exists("ETag"))
				.andExpect(jsonPath("$.items[*].id", contains("INC000021938960")))
				.andExpect(jsonPath("$.groups[1].incidentCount").value(2))
				.andExpect(jsonPath("$.groups[1].incidents", hasSize(1)))
				.andExpect(jsonPath("$.nextCursor").isString()).andReturn().getResponse();
		String cursor = com.jayway.jsonpath.JsonPath.read(first.getContentAsString(), "$.nextCursor");
		mvc.perform(filtered.header("If-None-Match", first.getHeader("ETag")))
				.andExpect(status().isNotModified()).andExpect(content().string(""));
		mvc.perform(listRequest().param("group", "coordinator").param("limit", "1").param("cursor", cursor))
				.andExpect(status().isOk()).andExpect(jsonPath("$.groups").isEmpty())
				.andExpect(jsonPath("$.items[*].id", contains("INC000021931151")))
				.andExpect(jsonPath("$.nextCursor").value(nullValue()));
		mvc.perform(listRequest().param("group", "coordinator").param("limit", "1")
						.param("cursor", cursor).accept(MediaType.valueOf("text/csv")))
				.andExpect(status().isOk()).andExpect(content().string("""
						incidentId,priorityId,stateEnum,deadline,groups\r
						INC000021938960,2,Assigned,2026-11-10T23:45:40Z,coordinator\r
						INC000021931151,1,Assigned,2026-11-22T12:02:29Z,coordinator\r
						"""));

		publish(receive(2, baseline())); // same revision, but a new published observation
		mvc.perform(listRequest()).andExpect(status().isOk()).andExpect(jsonPath("$.eventSeq").value(2));
		mvc.perform(listRequest().param("group", "coordinator").param("limit", "1").param("cursor", cursor))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_CURSOR"))
				.andExpect(jsonPath("$.message").value("cursor is not a valid list cursor"));
	}

	private static String incident(String id, String state, int priority, String team, String assignee) {
		boolean completed = state.equals("Resolved") || state.equals("Closed") || state.equals("Research");
		return """
				{"id":"%s","stateEnum":"%s","priorityId":%d,"submitDate":"2026-11-15T09:00:00Z",
				 "productName":"Test product","assignedGroupName":"%s","assignedEmployee":%s,
				 "assignedEmployeeLogin":%s,"updatedAt":"2026-11-21T10:00:00Z","completionDate":%s,"solution":%s}
				""".formatted(id, state, priority, team, assignee, assignee,
				completed ? "\"2026-11-20T12:00:00Z\"" : "null", completed ? "\"Fixed\"" : "null");
	}
}
