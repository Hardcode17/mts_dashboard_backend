package ru.hardcode.dashboard;

import java.time.Clock;
import java.time.Instant;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.Mockito.doReturn;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DashboardIncidentFiltersIT extends DashboardIncidentTestSupport {

	private static final String PRODUCT_A = "Продукт A";
	private static final String PRODUCT_B = "Продукт B";
	private static final String TEAM_A = "Команда A";
	private static final String TEAM_B = "Команда B";
	private static final String SERVICE_CUSTOMER = "Клиентский";
	private static final String SERVICE_TECH = "Технологический";

	@MockitoSpyBean Clock clock;

	@Test
	void filtersAndSearchCombineWithoutDuplicatingMultiGroupIncidents() throws Exception {
		publish(receive(1, snapshot(
				incident("INC000001", "Assigned", 4, PRODUCT_A, COORDINATORS, SERVICE_CUSTOMER,
						"Сбой оплаты на кассе", deadline(1)),
				incident("INC000002", "WorkInProgress", 3, PRODUCT_B, TEAM_B, SERVICE_TECH,
						"Задержка каталога после INC000001", deadline(2)),
				incident("INC000003", "Assigned", 2, PRODUCT_A, TEAM_B, SERVICE_TECH,
						"Ошибка авторизации", null))));

		mvc.perform(listRequest()
						.param("product", PRODUCT_A)
						.param("priority", "4"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items[*].id", contains("INC000001")))
				.andExpect(jsonPath("$.items", hasSize(1)));

		mvc.perform(listRequest()
						.param("product", PRODUCT_B)
						.param("priority", "3")
						.param("team", TEAM_B)
						.param("state", "WorkInProgress")
						.param("serviceType", SERVICE_TECH))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items[*].id", contains("INC000002")))
				.andExpect(jsonPath("$.items", hasSize(1)));

		mvc.perform(listRequest()
						.param("q", "сбой оплаты"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items[*].id", contains("INC000001")))
				.andExpect(jsonPath("$.items", hasSize(1)));

		mvc.perform(listRequest()
						.param("q", "несуществующий текст"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items").isEmpty());

		mvc.perform(listRequest().param("q", "inc000001"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items[*].id", contains("INC000001")));

		mvc.perform(listRequest()
						.param("group", "critical", "coordinator")
						.param("q", "INC000001"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items[*].id", contains("INC000001")))
				.andExpect(jsonPath("$.items", hasSize(1)));
	}

	@Test
	void unicodeSearchKeepsItsFilterAcrossPages() throws Exception {
		publish(receive(1, snapshot(
				incident("INC000001", "Assigned", 3, PRODUCT_A, TEAM_A, SERVICE_CUSTOMER,
						"Сбой авторизации платёжного шлюза", null),
				incident("INC000002", "Assigned", 3, PRODUCT_A, TEAM_A, SERVICE_CUSTOMER,
						"Повторный сбой авторизации платёжного шлюза", null))));

		String query = "сбой авторизации платёжного шлюза";
		var first = mvc.perform(listRequest().param("q", query).param("product", PRODUCT_A)
						.param("sort", "registered").param("limit", "1"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items[*].id", contains("INC000002")))
				.andReturn().getResponse();
		String cursor = JsonPath.read(first.getContentAsString(), "$.nextCursor");
		mvc.perform(listRequest().param("q", query).param("product", PRODUCT_A)
						.param("sort", "registered").param("limit", "1").param("cursor", cursor))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items[*].id", contains("INC000001")));
		mvc.perform(listRequest().param("q", "сбой").param("product", PRODUCT_A)
						.param("sort", "registered").param("limit", "1").param("cursor", cursor))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_CURSOR"))
				.andExpect(jsonPath("$.message").value("cursor is not a valid list cursor"));
	}

	@Test
	void deadlineFilterSeparatesLateNearAndNone() throws Exception {
		Instant now = Instant.parse("2026-11-21T11:00:00Z");
		doReturn(now).when(clock).instant();
		publish(receive(1, snapshot(
				incident("INC000001", "Assigned", 4, PRODUCT_A, TEAM_A, SERVICE_CUSTOMER,
						"Просроченный сбой", now.minusSeconds(1).toString()),
				incident("INC000002", "Assigned", 3, PRODUCT_A, TEAM_A, SERVICE_CUSTOMER,
						"Срок прямо сейчас", now.toString()),
				incident("INC000003", "Assigned", 2, PRODUCT_B, TEAM_B, SERVICE_TECH,
						"Сбой без срока", null),
				incident("INC000004", "Assigned", 2, PRODUCT_A, TEAM_A, SERVICE_TECH,
						"Близкий сбой", now.plusSeconds(1200).toString()),
				incident("INC000005", "Assigned", 2, PRODUCT_A, TEAM_A, SERVICE_TECH,
						"Сбой вне окна", now.plusSeconds(1202).toString()))));

		mvc.perform(listRequest().param("deadline", "late"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items[*].id", contains("INC000001")))
				.andExpect(jsonPath("$.items", hasSize(1)));

		var near = mvc.perform(listRequest().param("deadline", "near").param("sort", "deadline"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items[*].id", contains("INC000002", "INC000004")))
				.andReturn().getResponse();

		doReturn(now.plusSeconds(1)).when(clock).instant();
		mvc.perform(listRequest().param("deadline", "near").param("sort", "deadline")
						.header("If-None-Match", near.getHeader("ETag")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items[*].id", contains("INC000004")));

		mvc.perform(listRequest().param("deadline", "none"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items[*].id", contains("INC000003")))
				.andExpect(jsonPath("$.items", hasSize(1)));
	}

	@Test
	void invalidFiltersReturnContractErrors() throws Exception {
		publish(receive(1, snapshot(
				incident("INC000001", "Assigned", 4, PRODUCT_A, TEAM_A, SERVICE_CUSTOMER,
						"Сбой оплаты", deadline(1)))));

		mvc.perform(listRequest().param("state", "Unknown"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_INCIDENT_FILTER"))
				.andExpect(jsonPath("$.message").value("Invalid incident filter state: Unknown"));

		mvc.perform(listRequest().param("priority", "5"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_INCIDENT_FILTER"))
				.andExpect(jsonPath("$.message").value("Invalid incident filter priority: 5"));

		mvc.perform(listRequest().param("deadline", "soon"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_INCIDENT_FILTER"))
				.andExpect(jsonPath("$.message").value("Invalid incident filter deadline: soon"));

		mvc.perform(listRequest().param("sort", "priority"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_INCIDENT_FILTER"))
				.andExpect(jsonPath("$.message").value("Invalid incident filter sort: priority"));
	}

	@Test
	void sortOrdersByDeadlineAndRegistration() throws Exception {
		publish(receive(1, snapshot(
				incident("INC000003", "Assigned", 3, PRODUCT_B, TEAM_B, SERVICE_TECH,
						"Третий сбой", deadline(3)),
				incident("INC000001", "Assigned", 4, PRODUCT_A, TEAM_A, SERVICE_CUSTOMER,
						"Первый сбой", deadline(1)),
				incident("INC000002", "Assigned", 2, PRODUCT_B, TEAM_B, SERVICE_TECH,
						"Второй сбой", deadline(2)))));

		mvc.perform(listRequest().param("sort", "deadline"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items[*].id", contains("INC000001", "INC000003", "INC000002")));

		mvc.perform(listRequest().param("sort", "registered"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items[*].id", contains("INC000003", "INC000002", "INC000001")));
	}

	private static String deadline(int minutesFromNow) {
		return minutesFromNow == 0 ? null : Instant.now().plusSeconds(minutesFromNow * 60L).toString();
	}

	private static String snapshot(String... incidents) {
		return "[" + String.join(",", incidents) + "]";
	}

	private static String incident(String id, String state, int priority, String product, String team,
			String serviceType, String header, String deadline) {
		String submitDate = switch (id) {
			case "INC000001" -> "2026-11-21T08:00:00Z";
			case "INC000002" -> "2026-11-21T09:00:00Z";
			default -> "2026-11-21T10:00:00Z";
		};
		return """
				{"id":"%s","stateEnum":"%s","priorityId":%d,"submitDate":"%s",
				 "productName":"%s","assignedGroupName":"%s","serviceType":"%s","header":"%s",
				 "deadline":%s,"completionDate":null,"solution":null}
				""".formatted(id, state, priority, submitDate, product, team, serviceType, header,
				deadline == null ? "null" : '"' + deadline + '"');
	}
}
