package ru.hardcode.dashboard;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import ru.hardcode.dashboard.client.Kan16JwtTestConfiguration;
import ru.hardcode.dashboard.client.TestJwtSigner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ShiftHandoverIT extends DashboardIncidentTestSupport {

	private static final String HANDOVER = "/api/dashboard/shift-handovers";
	private static final String TASKS = "/api/dashboard/coordination-tasks";
	private static final String INC = "INC000021095531";

	@Test
	void sendsManuallyAndChangesOwnerOnlyAfterRecipientClaims() throws Exception {
		long owner = jdbc.queryForObject("SELECT id FROM users WHERE identity_subject=?", Long.class, HUMAN_SUBJECT);
		long receiver = jdbc.queryForObject("""
				INSERT INTO users(identity_issuer,identity_subject,login,display_name)
				VALUES (?, 'kan87-next', 'kan87-next', 'Входящая смена')
				ON CONFLICT (identity_issuer,identity_subject) DO UPDATE SET login=excluded.login RETURNING id
				""", Long.class, HUMAN_ISSUER);
		long group = jdbc.queryForObject("INSERT INTO coordinator_groups(name) VALUES ('KAN-87') RETURNING id", Long.class);
		jdbc.update("INSERT INTO coordinator_group_members(coordinator_group_id,user_id) VALUES (?,?),(?,?)",
				group, owner, group, receiver);
		publish(receive(1, row("ITSM Support")));
		publish(receive(2, row(COORDINATORS)));
		var tasks = jdbc.queryForList("SELECT id FROM coordination_tasks ORDER BY id", Long.class);
		assertThat(tasks).hasSize(2);
		long task = tasks.getFirst();
		long other = tasks.getLast();
		for (long id : tasks) {
			mvc.perform(post(TASKS + "/{id}/claim", id).header(HttpHeaders.AUTHORIZATION, "Bearer " + humanToken()))
					.andExpect(status().isOk());
		}
		String request = """
				{"requestId":"3122d167-6ec1-402c-ad1d-e9dda0c5e026","recipientId":%d,
				 "taskIds":[%d,%d],"situationKeys":{"%d":"outage-a","%d":"outage-a"},"summary":"Проверить с командой"}
				""".formatted(receiver, task, other, task, other);
		for (int i=0; i<2; i++) {
			mvc.perform(post(HANDOVER).header(HttpHeaders.AUTHORIZATION,"Bearer " + humanToken())
							.contentType(MediaType.APPLICATION_JSON).content(request))
					.andExpect(status().isOk());
		}
		assertThat(jdbc.queryForObject("SELECT count(*) FROM shift_handover", Long.class)).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT coordinator_id FROM coordination_tasks WHERE id=?", Long.class, task))
				.isEqualTo(owner);
		String token = Kan16JwtTestConfiguration.signer().token(TestJwtSigner.claims()
				.issuer(HUMAN_ISSUER).audience(HUMAN_AUDIENCE).subject("kan87-next").claim("azp","dashboard-web")
				.expiresAt(Instant.now().plusSeconds(300)));
		mvc.perform(get(HANDOVER).header(HttpHeaders.AUTHORIZATION,"Bearer " + token))
				.andExpect(status().isOk()).andExpect(jsonPath("$.incoming[0].items[0].incidentId").value(INC));
		long id = jdbc.queryForObject("SELECT id FROM shift_handover", Long.class);
		var claim = HANDOVER + "/{id}/tasks/{task}/claim";
		mvc.perform(post(claim,id,task).header(HttpHeaders.AUTHORIZATION,"Bearer " + humanToken()))
				.andExpect(status().isForbidden());
		try (var pool = Executors.newFixedThreadPool(2)) {
			var first = pool.submit(() -> mvc.perform(post(claim,id,task).header(HttpHeaders.AUTHORIZATION,"Bearer " + token))
					.andReturn().getResponse().getStatus());
			var second = pool.submit(() -> mvc.perform(post(claim,id,other).header(HttpHeaders.AUTHORIZATION,"Bearer " + token))
					.andReturn().getResponse().getStatus());
			assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
					.containsExactly(200, 200);
		}
		assertThat(jdbc.queryForList("SELECT coordinator_id FROM coordination_tasks ORDER BY id", Long.class))
				.containsExactly(receiver, receiver);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM shift_handover_item WHERE claimed_at IS NOT NULL",
				Long.class)).isEqualTo(2);

		assertThat(jdbc.queryForObject("SELECT source_data->>'assignedGroupName' FROM itsm_incident WHERE id=?", String.class, INC))
				.isEqualTo(COORDINATORS);
	}

	private static String row(String group) {
		String data = """
				{"id":"%s","stateEnum":"Assigned","priorityId":4,"productName":"Test",
				 "submitDate":"2026-11-21T12:00:00Z","assignedGroupName":"%s","solution":null}
				""";
		return "[" + data.formatted(INC, group) + "," + data.formatted("INC000021095532", group) + "]";
	}
}
