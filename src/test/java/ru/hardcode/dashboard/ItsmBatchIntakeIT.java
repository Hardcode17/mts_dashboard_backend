package ru.hardcode.dashboard;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import ru.hardcode.dashboard.client.ItsmMachineJwtTestConfiguration;
import ru.hardcode.dashboard.controller.ItsmBatchController;
import ru.hardcode.dashboard.dto.ItsmBatchAcceptedResponse;
import ru.hardcode.dashboard.entity.ItsmBatchStatus;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
		"itsm.integration.auth.issuer=" + ItsmMachineJwtTestConfiguration.ISSUER,
		"itsm.integration.auth.jwk-set-uri=https://keycloak.test/realms/itsm/protocol/openid-connect/certs",
		"itsm.integration.auth.audience=" + ItsmMachineJwtTestConfiguration.AUDIENCE,
		"itsm.integration.auth.client-id=" + ItsmMachineJwtTestConfiguration.CLIENT_ID,
		"itsm.integration.auth.import-role=" + ItsmMachineJwtTestConfiguration.IMPORT_ROLE,
		"itsm.intake.max-outstanding-batches=3",
		"spring.security.user.name=operator",
		"spring.security.user.password=kan27-test-password",
		"request-logging.max-cached-body-size=1KB"
})
@AutoConfigureMockMvc
@ActiveProfiles("demo")
@Import({ItsmPostgresTestcontainersConfiguration.class, ItsmMachineJwtTestConfiguration.class})
class ItsmBatchIntakeIT {

	private static final String BATCH_PATH = "/api/integrations/itsm/batches";
	private static final String SOURCE_ID = "itsm-source";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private JwtEncoder jwtEncoder;

	@Autowired
	private ObjectMapper objectMapper;

	private String bearerToken;

	@BeforeEach
	void setUp() {
		jdbcTemplate.update("TRUNCATE TABLE itsm_ingest_batch");
		bearerToken = ItsmMachineJwtTestConfiguration.machineToken(jwtEncoder);
	}

	@Test
	void machineChainPreservesHealthAndFormLogin() throws Exception {
		mockMvc.perform(get("/health")).andExpect(status().isOk());
		mockMvc.perform(formLogin().user("operator").password("kan27-test-password"))
				.andExpect(authenticated().withUsername("operator"));
	}

	@Test
	void emptyArray_isAcceptedStoredVerbatimAndExposedWithoutPayload() throws Exception {
		UUID key = UUID.randomUUID();

		MvcResult result = mockMvc.perform(postBatch(key.toString(), SOURCE_ID, 1, "[]"))
				.andExpect(status().isAccepted())
				.andExpect(header().string(HttpHeaders.LOCATION, BATCH_PATH + "/" + key))
				.andExpect(jsonPath("$.batchId").value(key.toString()))
				.andReturn();

		assertThat(storedPayload(key)).isEqualTo("[]".getBytes(StandardCharsets.UTF_8));
		assertThat(batchCount()).isEqualTo(1);
		assertThat(acceptedBatchId(result)).isEqualTo(key);

		mockMvc.perform(get(BATCH_PATH + "/" + key).header(HttpHeaders.AUTHORIZATION, bearer()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.batchId").value(key.toString()))
				.andExpect(jsonPath("$.sourceId").value(SOURCE_ID))
				.andExpect(jsonPath("$.sourceVersion").value(1))
				.andExpect(jsonPath("$.status").value("RECEIVED"))
				.andExpect(jsonPath("$.receivedAt").isNotEmpty())
				.andExpect(jsonPath("$.diagnostic").value(nullValue()))
				.andExpect(jsonPath("$.payload").doesNotExist())
				.andExpect(jsonPath("$.payloadSha256").doesNotExist());
	}

	@Test
	void fullArray_preservesEveryOriginalByteAndField() throws Exception {
		UUID key = UUID.randomUUID();
		String body = """
				[
				  {
				    "id": "INC-100",
				    "number": "INC-100",
				    "customField": {"nested": [1, 2.50, null, true]},
				    "unicode": "снимок"
				  },
				  {"id": "INC-101", "priority": "high"}
				]
				""";

		mockMvc.perform(postBatch(key.toString(), SOURCE_ID, 2, body))
				.andExpect(status().isAccepted());

		assertThat(storedPayload(key)).isEqualTo(body.getBytes(StandardCharsets.UTF_8));
	}

	@ParameterizedTest
	@MethodSource("invalidPayloads")
	void malformedOrNonArrayPayload_isRejectedWithoutStorage(String body) throws Exception {
		mockMvc.perform(postBatch(UUID.randomUUID().toString(), SOURCE_ID, 3, body))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("ITSM_INVALID_PAYLOAD"))
				.andExpect(jsonPath("$.message").value("Request body must be a single JSON array"));

		assertThat(batchCount()).isZero();
	}

	@ParameterizedTest
	@MethodSource("invalidHeaders")
	void invalidHeaders_areRejectedWithoutStorage(
			String idempotencyKey,
			String sourceId,
			String sourceVersion,
			String expectedCode
	) throws Exception {
		MockHttpServletRequestBuilder request = post(BATCH_PATH)
				.header(HttpHeaders.AUTHORIZATION, bearer())
				.contentType(MediaType.APPLICATION_JSON)
				.content("[]");
		if (idempotencyKey != null) {
			request.header(ItsmBatchController.IDEMPOTENCY_KEY_HEADER, idempotencyKey);
		}
		if (sourceId != null) {
			request.header(ItsmBatchController.SOURCE_ID_HEADER, sourceId);
		}
		if (sourceVersion != null) {
			request.header(ItsmBatchController.SOURCE_VERSION_HEADER, sourceVersion);
		}

		mockMvc.perform(request)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value(expectedCode));

		assertThat(batchCount()).isZero();
	}

	@Test
	void wrongContentType_returns415() throws Exception {
		mockMvc.perform(post(BATCH_PATH)
						.header(HttpHeaders.AUTHORIZATION, bearer())
						.header(ItsmBatchController.IDEMPOTENCY_KEY_HEADER, UUID.randomUUID())
						.header(ItsmBatchController.SOURCE_ID_HEADER, SOURCE_ID)
						.header(ItsmBatchController.SOURCE_VERSION_HEADER, "1")
						.contentType(MediaType.TEXT_PLAIN)
						.content("[]"))
				.andExpect(status().isUnsupportedMediaType())
				.andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));

		assertThat(batchCount()).isZero();
	}

	@Test
	void oversizedSnapshot_isRejectedByLoggerFilterBeforeStorage() throws Exception {
		String body = "[{\"id\":\"" + "a".repeat(1200) + "\"}]";

		mockMvc.perform(postBatch(UUID.randomUUID().toString(), SOURCE_ID, 4, body))
				.andExpect(status().isContentTooLarge())
				.andExpect(jsonPath("$.code").value("REQUEST_BODY_TOO_LARGE"))
				.andExpect(jsonPath("$.message").value("Request body exceeds 1024 bytes"));

		assertThat(batchCount()).isZero();
	}

	@Test
	void replayOfIdenticalRequest_returnsOriginal202AndSingleRow() throws Exception {
		UUID key = UUID.randomUUID();
		String body = "[{\"id\":\"INC-1\"}]";

		UUID first = acceptedBatchId(mockMvc.perform(postBatch(key.toString(), SOURCE_ID, 5, body))
				.andExpect(status().isAccepted())
				.andReturn());
		UUID replay = acceptedBatchId(mockMvc.perform(postBatch(key.toString(), SOURCE_ID, 5, body))
				.andExpect(status().isAccepted())
				.andReturn());

		assertThat(first).isEqualTo(key);
		assertThat(replay).isEqualTo(key);
		assertThat(batchCount()).isEqualTo(1);
	}

	@Test
	void changedBodyForSameKey_returns409() throws Exception {
		UUID key = UUID.randomUUID();
		mockMvc.perform(postBatch(key.toString(), SOURCE_ID, 6, "[{\"id\":\"INC-1\"}]"))
				.andExpect(status().isAccepted());

		mockMvc.perform(postBatch(key.toString(), SOURCE_ID, 6, "[{\"id\":\"INC-2\"}]"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("ITSM_IDEMPOTENCY_CONFLICT"));

		assertThat(batchCount()).isEqualTo(1);
	}

	@Test
	void inconsistentHeadersForSameKey_returns409() throws Exception {
		UUID key = UUID.randomUUID();
		mockMvc.perform(postBatch(key.toString(), SOURCE_ID, 7, "[]"))
				.andExpect(status().isAccepted());

		mockMvc.perform(postBatch(key.toString(), SOURCE_ID, 8, "[]"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("ITSM_IDEMPOTENCY_CONFLICT"));

		assertThat(batchCount()).isEqualTo(1);
	}

	@Test
	void sameSourceVersionWithDifferentKey_returns409() throws Exception {
		String body = "[{\"id\":\"INC-1\"}]";
		mockMvc.perform(postBatch(UUID.randomUUID().toString(), SOURCE_ID, 9, body))
				.andExpect(status().isAccepted());

		mockMvc.perform(postBatch(UUID.randomUUID().toString(), SOURCE_ID, 9, body))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("ITSM_SOURCE_VERSION_CONFLICT"));

		assertThat(batchCount()).isEqualTo(1);
	}

	@Test
	void differentVersionWithIdenticalBody_isDistinctObservation() throws Exception {
		String body = "[{\"id\":\"INC-1\"}]";
		UUID first = UUID.randomUUID();
		UUID second = UUID.randomUUID();

		mockMvc.perform(postBatch(first.toString(), SOURCE_ID, 10, body))
				.andExpect(status().isAccepted());
		mockMvc.perform(postBatch(second.toString(), SOURCE_ID, 11, body))
				.andExpect(status().isAccepted());

		assertThat(batchCount()).isEqualTo(2);
		assertThat(storedPayload(first)).isEqualTo(storedPayload(second));
	}

	@Test
	void concurrentIdenticalPosts_returnOneIdAndOneRow() throws Exception {
		UUID key = UUID.randomUUID();
		String body = "[{\"id\":\"INC-CONCURRENT\"}]";
		int attempts = 8;
		ExecutorService executor = Executors.newFixedThreadPool(attempts);
		CountDownLatch start = new CountDownLatch(1);
		List<Future<MvcResult>> futures = new ArrayList<>();
		try {
			for (int i = 0; i < attempts; i++) {
				futures.add(executor.submit(() -> {
					start.await();
					return mockMvc.perform(postBatch(key.toString(), SOURCE_ID, 12, body)).andReturn();
				}));
			}
			start.countDown();

			Set<UUID> ids = new HashSet<>();
			for (Future<MvcResult> future : futures) {
				MvcResult result = future.get(30, TimeUnit.SECONDS);
				assertThat(result.getResponse().getStatus()).isEqualTo(202);
				ids.add(acceptedBatchId(result));
			}
			assertThat(ids).containsExactly(key);
			assertThat(batchCount()).isEqualTo(1);
		} finally {
			executor.shutdownNow();
		}
	}

	@Test
	void queueIsBoundedButIdenticalReplaysRemainAvailable() throws Exception {
		UUID first = UUID.randomUUID();
		mockMvc.perform(postBatch(first.toString(), SOURCE_ID, 13, "[]"))
				.andExpect(status().isAccepted());
		mockMvc.perform(postBatch(UUID.randomUUID().toString(), SOURCE_ID, 14, "[]"))
				.andExpect(status().isAccepted());
		mockMvc.perform(postBatch(UUID.randomUUID().toString(), SOURCE_ID, 15, "[]"))
				.andExpect(status().isAccepted());

		mockMvc.perform(postBatch(UUID.randomUUID().toString(), SOURCE_ID, 16, "[]"))
				.andExpect(status().isTooManyRequests())
				.andExpect(jsonPath("$.code").value("ITSM_INTAKE_QUEUE_FULL"))
				.andExpect(jsonPath("$.message").value("ITSM intake queue is full (3 outstanding batches)"));

		mockMvc.perform(postBatch(first.toString(), SOURCE_ID, 13, "[]"))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.batchId").value(first.toString()));

		assertThat(batchCount()).isEqualTo(3);

		jdbcTemplate.update("UPDATE itsm_ingest_batch SET status = 'FAILED' WHERE id = ?", first);
		mockMvc.perform(postBatch(UUID.randomUUID().toString(), SOURCE_ID, 16, "[]"))
				.andExpect(status().isAccepted());
		assertThat(batchCount()).isEqualTo(4);
	}

	@Test
	void unknownBatch_returns404() throws Exception {
		UUID unknown = UUID.randomUUID();

		mockMvc.perform(get(BATCH_PATH + "/" + unknown).header(HttpHeaders.AUTHORIZATION, bearer()))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("ITSM_BATCH_NOT_FOUND"))
				.andExpect(jsonPath("$.message").value("ITSM batch " + unknown + " not found"));
	}

	@Test
	void snapshotStatus_staysStaleUntilLatestAcceptedSnapshotSucceeds() throws Exception {
		UUID key = UUID.randomUUID();
		mockMvc.perform(postBatch(key.toString(), SOURCE_ID, 17, "[{\"id\":\"INC-1\"}]"))
				.andExpect(status().isAccepted());

		mockMvc.perform(get("/api/snapshot/status").with(httpBasic("operator", "kan27-test-password")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.lastReceivedAt").isNotEmpty())
				.andExpect(jsonPath("$.lastSuccessAt").value(nullValue()))
				.andExpect(jsonPath("$.revision").value(nullValue()))
				.andExpect(jsonPath("$.stale").value(true));

		Instant successAt = Instant.now();
		jdbcTemplate.update(
				"""
				UPDATE itsm_ingest_batch
				SET status = ?, status_updated_at = ?, processed_at = ?,
				    processing_revision = ?, diagnostic = ?
				WHERE id = ?
				""",
				ItsmBatchStatus.SUCCEEDED.name(),
				Timestamp.from(successAt),
				Timestamp.from(successAt),
				41L,
				"applied to normalized model",
				key
		);

		mockMvc.perform(get("/api/snapshot/status").with(httpBasic("operator", "kan27-test-password")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.lastReceivedAt").isNotEmpty())
				.andExpect(jsonPath("$.lastSuccessAt").isNotEmpty())
				.andExpect(jsonPath("$.revision").value(41))
				.andExpect(jsonPath("$.stale").value(false));

		mockMvc.perform(get(BATCH_PATH + "/" + key).header(HttpHeaders.AUTHORIZATION, bearer()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("SUCCEEDED"))
				.andExpect(jsonPath("$.diagnostic").value("applied to normalized model"))
				.andExpect(jsonPath("$.payload").doesNotExist());

		mockMvc.perform(postBatch(UUID.randomUUID().toString(), SOURCE_ID, 18, "[{\"id\":\"INC-2\"}]"))
				.andExpect(status().isAccepted());

		mockMvc.perform(get("/api/snapshot/status").with(httpBasic("operator", "kan27-test-password")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.lastSuccessAt").isNotEmpty())
				.andExpect(jsonPath("$.revision").value(41))
				.andExpect(jsonPath("$.stale").value(true));
	}

	private MockHttpServletRequestBuilder postBatch(
			String idempotencyKey,
			String sourceId,
			long sourceVersion,
			String body
	) {
		return post(BATCH_PATH)
				.header(HttpHeaders.AUTHORIZATION, bearer())
				.header(ItsmBatchController.IDEMPOTENCY_KEY_HEADER, idempotencyKey)
				.header(ItsmBatchController.SOURCE_ID_HEADER, sourceId)
				.header(ItsmBatchController.SOURCE_VERSION_HEADER, sourceVersion)
				.contentType(MediaType.APPLICATION_JSON)
				.content(body);
	}

	private static Stream<Arguments> invalidPayloads() {
		return Stream.of(
				Arguments.of("[{\"id\":1}"),
				Arguments.of("{\"id\":1}"),
				Arguments.of("[] []"),
				Arguments.of("null"),
				Arguments.of("")
		);
	}

	private static Stream<Arguments> invalidHeaders() {
		return Stream.of(
				Arguments.of("not-a-uuid", SOURCE_ID, "1", "ITSM_INVALID_IDEMPOTENCY_KEY"),
				Arguments.of(null, SOURCE_ID, "1", "ITSM_INVALID_IDEMPOTENCY_KEY"),
				Arguments.of(KEY, "", "1", "ITSM_INVALID_SOURCE_ID"),
				Arguments.of(KEY, "источник", "1", "ITSM_INVALID_SOURCE_ID"),
				Arguments.of(KEY, SOURCE_ID, "0", "ITSM_INVALID_SOURCE_VERSION"),
				Arguments.of(KEY, SOURCE_ID, "-1", "ITSM_INVALID_SOURCE_VERSION"),
				Arguments.of(KEY, SOURCE_ID, "01", "ITSM_INVALID_SOURCE_VERSION"),
				Arguments.of(KEY, SOURCE_ID, "9223372036854775808", "ITSM_INVALID_SOURCE_VERSION")
		);
	}

	private static final String KEY = "8f7f5f3e-4a9e-4c6e-9f50-3f6e2e6f3a11";

	private String bearer() {
		return "Bearer " + bearerToken;
	}

	private UUID acceptedBatchId(MvcResult result) throws Exception {
		return objectMapper.readValue(
				result.getResponse().getContentAsString(),
				ItsmBatchAcceptedResponse.class
		).batchId();
	}

	private byte[] storedPayload(UUID batchId) {
		return jdbcTemplate.query(
				"SELECT payload FROM itsm_ingest_batch WHERE id = ?",
				resultSet -> resultSet.next() ? resultSet.getBytes("payload") : null,
				batchId
		);
	}

	private long batchCount() {
		Long count = jdbcTemplate.queryForObject("SELECT count(*) FROM itsm_ingest_batch", Long.class);
		return count == null ? 0 : count;
	}
}
