package ru.hardcode.dashboard;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import ru.hardcode.dashboard.client.ItsmMachineJwtTestConfiguration;
import ru.hardcode.dashboard.client.TestItsmBatchProcessor;
import ru.hardcode.dashboard.controller.ItsmBatchController;
import ru.hardcode.dashboard.dto.ItsmBatchAcceptedResponse;
import ru.hardcode.dashboard.entity.ItsmBatchStatus;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP, database and waiting helpers for the KAN-54 worker integration tests. They use the real
 * intake endpoint with machine authentication and inspect PostgreSQL directly only for
 * worker-internal fields that the public API does not expose.
 */
abstract class ItsmWorkerTestSupport {

	static final String BATCH_PATH = "/api/integrations/itsm/batches";
	static final String WORKER_FAILED = "ITSM snapshot version %d could not be processed on attempt %d";
	static final long AWAIT_SECONDS = 30;

	static final UUID BATCH_A = UUID.fromString("0b8f1a8e-3c1f-4a7d-9e8b-2b7f6a5c4d10");
	static final UUID BATCH_B = UUID.fromString("1c9f2b9f-4d2a-4b8e-8f9c-3c8a7b6d5e21");
	static final UUID BATCH_C = UUID.fromString("2d0a3c0a-5e3b-4c9f-9a0d-4d9b8c7e6f32");

	@Autowired
	protected MockMvc mockMvc;

	@Autowired
	protected JdbcTemplate jdbcTemplate;

	@Autowired
	protected JwtEncoder jwtEncoder;

	@Autowired
	protected ObjectMapper objectMapper;

	/**
	 * Test-only processor. Present in every worker IT; resolved optionally so the same base also
	 * serves the "no processor" context check.
	 */
	@Autowired
	protected ObjectProvider<TestItsmBatchProcessor> processorProvider;

	protected TestItsmBatchProcessor processor;

	private String bearerToken;

	/**
	 * Initializes machine authentication, truncates the durable queue and the test publication
	 * table and clears the test processor state (when a processor is present).
	 */
	protected void resetDatabaseAndProcessor() {
		bearerToken = ItsmMachineJwtTestConfiguration.machineToken(jwtEncoder);
		processor = processorProvider.getIfAvailable();
		if (processor == null) {
			jdbcTemplate.update("TRUNCATE TABLE itsm_ingest_batch CASCADE");
			return;
		}
		jdbcTemplate.update("TRUNCATE TABLE itsm_ingest_batch, " + TestItsmBatchProcessor.TABLE + " CASCADE");
		processor.clear();
	}

	protected UUID accept(MockHttpServletRequestBuilder request) throws Exception {
		MvcResult result = mockMvc.perform(request).andExpect(status().isAccepted()).andReturn();
		return objectMapper.readValue(
				result.getResponse().getContentAsString(),
				ItsmBatchAcceptedResponse.class
		).batchId();
	}

	protected MockHttpServletRequestBuilder postBatch(UUID key, String sourceId, long version) {
		return post(BATCH_PATH)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken)
				.header(ItsmBatchController.IDEMPOTENCY_KEY_HEADER, key.toString())
				.header(ItsmBatchController.SOURCE_ID_HEADER, sourceId)
				.header(ItsmBatchController.SOURCE_VERSION_HEADER, version)
				.contentType(MediaType.APPLICATION_JSON)
				.content("[{\"id\":\"INC-" + version + "\"}]");
	}

	protected MockHttpServletRequestBuilder getBatch(UUID batchId) {
		return org.springframework.test.web.servlet.request.MockMvcRequestBuilders
				.get(BATCH_PATH + "/" + batchId)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken);
	}

	protected void awaitTerminal(UUID... batchIds) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(AWAIT_SECONDS);
		while (System.nanoTime() < deadline) {
			List<Map<String, Object>> rows = rows(batchIds);
			boolean allTerminal = rows.size() == batchIds.length && rows.stream().allMatch(row ->
					ItsmBatchStatus.SUCCEEDED.name().equals(row.get("status"))
							|| ItsmBatchStatus.FAILED.name().equals(row.get("status"))
			);
			if (allTerminal) {
				return;
			}
			Thread.sleep(25);
		}
		throw new AssertionError("batches did not reach a terminal state: " + rows(batchIds));
	}

	protected void assertStatus(UUID batchId, String expected) {
		assertThat(row(batchId).get("status")).isEqualTo(expected);
	}

	protected Map<String, Object> row(UUID batchId) {
		return jdbcTemplate.queryForMap(
				"SELECT status, diagnostic, processing_revision, processed_at, claimed_by, "
						+ "next_attempt_at, batch_attempts, row_version FROM itsm_ingest_batch WHERE id = ?",
				batchId
		);
	}

	protected List<Map<String, Object>> rows(UUID... batchIds) {
		List<Map<String, Object>> rows = new ArrayList<>();
		for (UUID batchId : batchIds) {
			rows.addAll(jdbcTemplate.queryForList("SELECT status FROM itsm_ingest_batch WHERE id = ?", batchId));
		}
		return rows;
	}

	protected long count() {
		Long count = jdbcTemplate.queryForObject("SELECT count(*) FROM itsm_ingest_batch", Long.class);
		return count == null ? 0 : count;
	}

	/** Waits until a condition holds; fails with a stable message otherwise. */
	protected static void await(String what, java.util.function.BooleanSupplier condition)
			throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(AWAIT_SECONDS);
		while (System.nanoTime() < deadline) {
			if (condition.getAsBoolean()) {
				return;
			}
			Thread.sleep(25);
		}
		throw new AssertionError("condition not reached: " + what);
	}
}
