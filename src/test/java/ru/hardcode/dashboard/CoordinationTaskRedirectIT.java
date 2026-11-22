package ru.hardcode.dashboard;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import ru.hardcode.dashboard.client.Kan16JwtTestConfiguration;
import ru.hardcode.dashboard.client.TestJwtSigner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CoordinationTaskRedirectIT extends DashboardIncidentTestSupport {

	private static final String TASKS = "/api/dashboard/coordination-tasks";
	private static final String INC = "INC000021095531";

	@Test
	void redirectsClaimedTaskIntoDestinationPoolAndKeepsAuditAndOfficialGroup() throws Exception {
		var run = scenario();
		mvc.perform(redirect(run, run.destination(), "No access", token("receiver")))
				.andExpect(status().isForbidden());
		mvc.perform(redirect(run, run.source(), "Same group", humanToken())).andExpect(status().isBadRequest());
		mvc.perform(redirect(run, run.inactive(), "Inactive group", humanToken())).andExpect(status().isBadRequest());
		assertThat(assignmentCount(run.task())).isZero();

		mvc.perform(redirect(run, run.destination(), "Следующая смена", humanToken()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.groupId").value(run.destination()))
				.andExpect(jsonPath("$.status").value("assigned"));
		mvc.perform(authenticatedGet(TASKS + "/{task}/assignments", run.task()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2))
				.andExpect(jsonPath("$[0].status").value("redirected"))
				.andExpect(jsonPath("$[0].redirectReason").value("Следующая смена"))
				.andExpect(jsonPath("$[0].redirectedByUserId").value(run.owner()))
				.andExpect(jsonPath("$[1].status").value("assigned"));
		mvc.perform(authenticatedGet(TASKS)).andExpect(status().isOk()).andExpect(jsonPath("$").isEmpty());
		mvc.perform(get(TASKS).header(HttpHeaders.AUTHORIZATION, "Bearer " + token("receiver")))
				.andExpect(status().isOk()).andExpect(jsonPath("$[0].taskId").value(run.task()));
		mvc.perform(post(TASKS + "/{task}/claim", run.task()).header(HttpHeaders.AUTHORIZATION, "Bearer " + humanToken()))
				.andExpect(status().isForbidden());
		mvc.perform(post(TASKS + "/{task}/claim", run.task()).header(HttpHeaders.AUTHORIZATION, "Bearer " + token("receiver")))
				.andExpect(status().isOk());
		assertThat(jdbc.queryForObject("SELECT source_data->>'assignedGroupName' FROM itsm_incident WHERE id=?", String.class, INC))
				.isEqualTo(COORDINATORS);
		assertThat(jdbc.queryForObject("SELECT status FROM coordination_tasks WHERE id=?", String.class, run.task()))
				.isEqualTo("open");
		publish(receive(3, snapshot("ITSM Support")));
		mvc.perform(authenticatedGet(TASKS + "/{task}/assignments", run.task()))
				.andExpect(status().isOk()).andExpect(jsonPath("$[1].status").value("completed"))
				.andExpect(jsonPath("$[1].endedAt").isNotEmpty());
	}

	@Test
	void sameVersionHasOneWinnerAndFailedInsertRollsBack() throws Exception {
		var run = scenario();
		jdbc.execute("ALTER TABLE coordination_task_assignments ADD CONSTRAINT kan44_test_fail CHECK (coordinator_group_id <> " + run.destination() + ")");
		try {
			assertThatThrownBy(() -> mvc.perform(redirect(run, run.destination(), "Ошибка БД", humanToken())))
					.hasCauseInstanceOf(DataIntegrityViolationException.class);
			assertThat(assignmentCount(run.task())).isZero();
			assertThat(jdbc.queryForObject("SELECT coordinator_id FROM coordination_tasks WHERE id=?", Long.class, run.task()))
					.isEqualTo(run.owner());
		} finally {
			jdbc.execute("ALTER TABLE coordination_task_assignments DROP CONSTRAINT kan44_test_fail");
		}
		var ready = new CountDownLatch(2);
		try (var pool = Executors.newFixedThreadPool(2)) {
			var first = pool.submit(() -> race(run, ready));
			var second = pool.submit(() -> race(run, ready));
			assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
					.containsExactlyInAnyOrder(200, 409);
		}
		assertThat(assignmentCount(run.task())).isEqualTo(2);
		assertThat(jdbc.queryForObject("""
				SELECT count(*) FROM coordination_task_assignments WHERE coordination_task_id=? AND ended_at IS NULL
				""", Long.class, run.task())).isEqualTo(1);
	}

	private int race(Scenario run, CountDownLatch ready) throws Exception {
		ready.countDown();
		assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
		return mvc.perform(redirect(run, run.destination(), "Конкурентный запрос", humanToken()))
				.andReturn().getResponse().getStatus();
	}

	private Scenario scenario() throws Exception {
		publish(receive(1, snapshot("ITSM Support")));
		publish(receive(2, snapshot(COORDINATORS)));
		long task = jdbc.queryForObject("SELECT id FROM coordination_tasks WHERE incident_id=?", Long.class, INC);
		long owner = jdbc.queryForObject("SELECT id FROM users WHERE identity_subject=?", Long.class, HUMAN_SUBJECT);
		long receiver = jdbc.queryForObject("""
				INSERT INTO users(identity_issuer, identity_subject, login, display_name)
				VALUES (?, 'receiver', 'receiver', 'Receiver')
				ON CONFLICT (identity_issuer, identity_subject) DO UPDATE SET login=excluded.login RETURNING id
				""", Long.class, HUMAN_ISSUER);
		long source = group("Source", true);
		long destination = group("Destination", true);
		long inactive = group("Inactive", false);
		jdbc.update("""
				INSERT INTO coordinator_group_members(coordinator_group_id, user_id)
				VALUES (?, ?), (?, ?) ON CONFLICT DO NOTHING
				""", source, owner, destination, receiver);
		mvc.perform(post(TASKS + "/{task}/claim", task).header(HttpHeaders.AUTHORIZATION, "Bearer " + humanToken()))
				.andExpect(status().isOk());
		return new Scenario(task, owner, source, destination, inactive);
	}

	private long group(String name, boolean active) {
		return jdbc.queryForObject("""
				INSERT INTO coordinator_groups(name,is_active) VALUES (?, ?)
				ON CONFLICT (name) DO UPDATE SET is_active=excluded.is_active RETURNING id
				""", Long.class, name, active);
	}

	private long assignmentCount(long task) {
		return jdbc.queryForObject("SELECT count(*) FROM coordination_task_assignments WHERE coordination_task_id=?",
				Long.class, task);
	}

	private static String snapshot(String group) {
		return """
				[{"id":"%s","stateEnum":"Assigned","priorityId":4,"productName":"Test",
				  "submitDate":"2026-11-21T12:00:00Z","assignedGroupName":"%s","solution":null}]
				""".formatted(INC, group);
	}

	private static MockHttpServletRequestBuilder redirect(Scenario run, long destination, String reason, String token) {
		return post(TASKS + "/{task}/redirect", run.task())
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"sourceGroupId":%d,"targetGroupId":%d,"expectedAssignmentId":0,"reason":"%s"}
						""".formatted(run.source(), destination, reason));
	}

	private static String token(String user) {
		return Kan16JwtTestConfiguration.signer().token(TestJwtSigner.claims()
				.issuer(HUMAN_ISSUER).audience(HUMAN_AUDIENCE).subject(user).claim("azp", "dashboard-web")
				.expiresAt(Instant.now().plusSeconds(300)));
	}

	private record Scenario(long task, long owner, long source, long destination, long inactive) {
	}
}
