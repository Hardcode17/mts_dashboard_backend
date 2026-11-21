package ru.hardcode.dashboard;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import ru.hardcode.dashboard.client.Kan16JwtTestConfiguration;
import ru.hardcode.dashboard.client.TestJwtSigner;
import ru.hardcode.dashboard.worker.ItsmWorkerSqlRepository;
import ru.hardcode.dashboard.worker.ItsmWorkerTransactionService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * KAN-66: imported one-step incident graph, its snapshot semantics and the protected read.
 */
class IncidentRelationshipIT extends DashboardIncidentTestSupport {


	private static final String PARENT = "INC000000001";
	private static final String CHILD_ONE = "INC000000002";
	private static final String CHILD_TWO = "INC000000003";
	private static final String UNRELATED = "INC000000004";
	private static final String DATE = "2026-11-21T09:00:00Z";


	@Test
	void readsParentChildAndUnrelatedGraphsFromPublishedSnapshots() throws Exception {
		// The child is listed before its parent: the final graph is resolved after all rows.
		publish(receive(1, snapshot(
				child(CHILD_ONE, PARENT, "Первый дочерний"),
				incident(PARENT, "Родительский инцидент"),
				incident(UNRELATED, null),
				child(CHILD_TWO, PARENT, "Второй дочерний"))));

		mvc.perform(relationships(PARENT)).andExpect(status().isOk())
				.andExpect(jsonPath("$.focusId").value(PARENT))
				.andExpect(jsonPath("$.nodes", hasSize(3)))
				.andExpect(jsonPath("$.nodes[*].id", containsInAnyOrder(PARENT, CHILD_ONE, CHILD_TWO)))
				.andExpect(jsonPath("$.nodes[?(@.id == '%s')].title".formatted(PARENT))
						.value("Родительский инцидент"))
				.andExpect(jsonPath("$.nodes[?(@.id == '%s')].stateEnum".formatted(PARENT))
						.value("Assigned"))
				.andExpect(jsonPath("$.nodes[?(@.id == '%s')].priorityId".formatted(PARENT)).value(4))
				.andExpect(jsonPath("$.edges", hasSize(2)))
				.andExpect(jsonPath("$.edges[*].parentId", containsInAnyOrder(PARENT, PARENT)))
				.andExpect(jsonPath("$.edges[*].childId", containsInAnyOrder(CHILD_ONE, CHILD_TWO)));

		// A child sees only its direct parent and its own children, never a sibling.
		mvc.perform(relationships(CHILD_ONE)).andExpect(status().isOk())
				.andExpect(jsonPath("$.focusId").value(CHILD_ONE))
				.andExpect(jsonPath("$.nodes[*].id", containsInAnyOrder(CHILD_ONE, PARENT)))
				.andExpect(jsonPath("$.edges", hasSize(1)))
				.andExpect(jsonPath("$.edges[0].parentId").value(PARENT))
				.andExpect(jsonPath("$.edges[0].childId").value(CHILD_ONE));

		// A known incident without links is one node with an empty edge list and a null title.
		mvc.perform(relationships(UNRELATED)).andExpect(status().isOk())
				.andExpect(jsonPath("$.focusId").value(UNRELATED))
				.andExpect(jsonPath("$.nodes", hasSize(1)))
				.andExpect(jsonPath("$.nodes[0].id").value(UNRELATED))
				.andExpect(jsonPath("$.nodes[0].title").value(nullValue()))
				.andExpect(jsonPath("$.nodes[0].stateEnum").value("Assigned"))
				.andExpect(jsonPath("$.nodes[0].priorityId").value(4))
				.andExpect(jsonPath("$.edges", empty()));

		mvc.perform(relationships("INC999999")).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("INCIDENT_NOT_FOUND"))
				.andExpect(jsonPath("$.message").value("Incident INC999999 not found"))
				.andExpect(jsonPath("$.eventSeq").value(1));
		mvc.perform(relationships("123")).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_INCIDENT_ID"));

		mvc.perform(get("/api/incidents/{id}/relationships", PARENT).accept(MediaType.APPLICATION_JSON))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("AUTH_REQUIRED"))
				.andExpect(jsonPath("$.message").value("Authentication is required"));
		mvc.perform(get("/api/incidents/{id}/relationships", PARENT).accept(MediaType.APPLICATION_JSON)
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + token("itsm-import")))
				.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
	}

	@Test
	void absentKeepsTheImportedLinkNullRemovesItAndInvalidGraphsRollBack() throws Exception {
		String first = snapshot(
				incident(PARENT, "Родительский инцидент"),
				child(CHILD_ONE, PARENT, "Первый дочерний"),
				child(CHILD_TWO, PARENT, "Второй дочерний"),
				incident(UNRELATED, "Несвязанный"));
		publish(receive(1, first));
		assertThat(links()).isEqualTo(2);

		// The first child omits parentId (keep), the second sends null (remove).
		String second = snapshot(
				incident(PARENT, "Родительский инцидент"),
				incident(CHILD_ONE, "Первый дочерний"),
				child(CHILD_TWO, null, "Второй дочерний"),
				incident(UNRELATED, "Несвязанный"));
		publish(receive(2, second));
		assertThat(links()).isEqualTo(1);
		assertThat(observedVersion()).isEqualTo(2L);
		mvc.perform(relationships(CHILD_ONE)).andExpect(status().isOk())
				.andExpect(jsonPath("$.edges[0].parentId").value(PARENT));
		mvc.perform(relationships(CHILD_TWO)).andExpect(status().isOk())
				.andExpect(jsonPath("$.edges", empty()));

		// Self-reference, cycle and unknown parent are rejected without replacing version 2.
		publishRejected(receive(3, snapshot(
				incident(PARENT, "Родительский инцидент"),
				child(CHILD_ONE, CHILD_ONE, "Первый дочерний"),
				child(CHILD_TWO, null, "Второй дочерний"),
				incident(UNRELATED, "Несвязанный"))));
		publishRejected(receive(4, snapshot(
				incident(PARENT, "Родительский инцидент"),
				child(CHILD_ONE, CHILD_TWO, "Первый дочерний"),
				child(CHILD_TWO, CHILD_ONE, "Второй дочерний"),
				incident(UNRELATED, "Несвязанный"))));
		publishRejected(receive(5, snapshot(
				incident(PARENT, "Родительский инцидент"),
				child(CHILD_ONE, "INC999999", "Первый дочерний"),
				child(CHILD_TWO, null, "Второй дочерний"),
				incident(UNRELATED, "Несвязанный"))));
		assertThat(observedVersion()).isEqualTo(2L);
		assertThat(links()).isEqualTo(1);
		mvc.perform(relationships(CHILD_ONE)).andExpect(status().isOk())
				.andExpect(jsonPath("$.edges[0].parentId").value(PARENT));

		// Rejected versions are settled terminally, so the next valid version still publishes.
		publish(receive(6, second));
		assertThat(observedVersion()).isEqualTo(6L);
		assertThat(links()).isEqualTo(1);
	}

	@Test
	void repeatedDeliveryAndRepeatedFullSnapshotsKeepOneEdgePerChild() throws Exception {
		String graph = snapshot(
				incident(PARENT, "Родительский инцидент"),
				child(CHILD_ONE, PARENT, "Первый дочерний"),
				child(CHILD_TWO, PARENT, "Второй дочерний"),
				incident(UNRELATED, "Несвязанный"));
		publish(receive(1, graph));
		long revision = jdbc.queryForObject("""
				SELECT change_revision FROM itsm_snapshot_source WHERE source_id = 'dashboard-fixture'
				""", Long.class);
		publish(receive(2, graph));
		publish(receive(3, graph));
		assertThat(links()).isEqualTo(2);
		assertThat(jdbc.queryForObject("""
				SELECT change_revision FROM itsm_snapshot_source WHERE source_id = 'dashboard-fixture'
				""", Long.class)).isEqualTo(revision);
		mvc.perform(relationships(PARENT)).andExpect(status().isOk())
				.andExpect(jsonPath("$.nodes", hasSize(3)))
				.andExpect(jsonPath("$.edges", hasSize(2)));
	}

	private MockHttpServletRequestBuilder relationships(String id) {
		return authenticatedGet("/api/incidents/{id}/relationships", id);
	}


	private void publishRejected(UUID batchId) {
		var worker = new ItsmWorkerTransactionService(new ItsmWorkerSqlRepository(jdbc), transactions);
		assertThat(worker.claimAndProcess(publication, "relationship-test").batchId()).isEqualTo(batchId);
		assertThat(jdbc.queryForObject("SELECT status FROM itsm_ingest_batch WHERE id = ?", String.class, batchId))
				.isEqualTo("FAILED");
		assertThat(jdbc.queryForObject("SELECT validation_rejected FROM itsm_ingest_batch WHERE id = ?",
				Boolean.class, batchId)).isTrue();
	}

	private long observedVersion() {
		return jdbc.queryForObject("SELECT observed_version FROM itsm_snapshot_source WHERE source_id = 'dashboard-fixture'",
				Long.class);
	}

	private int links() {
		return jdbc.queryForObject("SELECT count(*) FROM incident_hierarchies", Integer.class);
	}

	private static String token(String audience) {
		return Kan16JwtTestConfiguration.signer().token(TestJwtSigner.claims().issuer(HUMAN_ISSUER).audience(audience)
				.subject(HUMAN_SUBJECT).claim("azp", "dashboard-web")
				.expiresAt(Instant.now().plusSeconds(300)));
	}

	private static String snapshot(String... incidents) {
		return "[" + String.join(",", incidents) + "]";
	}

	private static String incident(String id, String header) {
		return record(id, header, null);
	}

	private static String child(String id, String parentId, String header) {
		return record(id, header, parentId == null ? "null" : quoted(parentId));
	}

	private static String record(String id, String header, String parentIdJson) {
		String headerField = header == null ? "" : ",\"header\":\"" + header + "\"";
		String parentField = parentIdJson == null ? "" : ",\"parentId\":" + parentIdJson;
		return """
				{"id":"%s","stateEnum":"Assigned","priorityId":4,"submitDate":"%s",
				 "productName":"Graph product","solution":null%s%s}
				""".formatted(id, DATE, headerField, parentField);
	}

	private static String quoted(String value) {
		return "\"" + value + "\"";
	}
}
