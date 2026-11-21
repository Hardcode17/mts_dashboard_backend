package ru.hardcode.dashboard.service;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.hardcode.dashboard.entity.ItsmIngestBatch;
import ru.hardcode.dashboard.exception.AppErrors;
import ru.hardcode.dashboard.exception.AppException;
import ru.hardcode.dashboard.repository.ItsmBatchSqlRepository;
import ru.hardcode.dashboard.repository.ItsmIngestBatchRepository;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class ItsmBatchIntakeServiceTest {

	private static final String SOURCE_ID = "itsm-source";
	private static final String KEY = "8f7f5f3e-4a9e-4c6e-9f50-3f6e2e6f3a11";

	@Mock
	private ItsmIngestBatchRepository batchRepository;

	@Mock
	private ItsmBatchSqlRepository sqlRepository;

	private ItsmBatchIntakeService service;

	@BeforeEach
	void setUp() {
		service = new ItsmBatchIntakeService(
				batchRepository, sqlRepository, new ObjectMapper(),
				org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class),
				10
		);
	}

	@Test
	void emptyArray_isAccepted() {
		given(batchRepository.findById(UUID.fromString(KEY))).willReturn(Optional.empty());
		given(batchRepository.findBySourceIdAndSourceVersion(SOURCE_ID, 7)).willReturn(Optional.empty());
		given(sqlRepository.countOutstanding()).willReturn(0L);
		given(sqlRepository.insert(
				eq(UUID.fromString(KEY)),
				eq(SOURCE_ID),
				eq(7L),
				any(byte[].class),
				anyString(),
				anyInt(),
				any()
		)).willReturn(true);

		UUID result = service.receive(KEY, SOURCE_ID, "7", "[]".getBytes(StandardCharsets.UTF_8));

		assertThat(result).isEqualTo(UUID.fromString(KEY));
	}

	@Test
	void malformedJson_isRejected() {
		assertInvalidPayload("[{\"id\":1}");
	}

	@Test
	void topLevelObject_isRejected() {
		assertInvalidPayload("{\"id\":1}");
	}

	@Test
	void trailingJsonValue_isRejected() {
		assertInvalidPayload("[] []");
	}

	@Test
	void emptyBody_isRejected() {
		assertInvalidPayload("");
	}

	@Test
	void invalidIdempotencyKey_isRejected() {
		assertAppError(
				() -> service.receive("not-a-uuid", SOURCE_ID, "1", "[]".getBytes(StandardCharsets.UTF_8)),
				AppErrors.ITSM_INVALID_IDEMPOTENCY_KEY
		);
	}

	@Test
	void nonAsciiSourceId_isRejected() {
		assertAppError(
				() -> service.receive(KEY, "источник", "1", "[]".getBytes(StandardCharsets.UTF_8)),
				AppErrors.ITSM_INVALID_SOURCE_ID
		);
	}

	@Test
	void nonPositiveSourceVersion_isRejected() {
		assertAppError(
				() -> service.receive(KEY, SOURCE_ID, "0", "[]".getBytes(StandardCharsets.UTF_8)),
				AppErrors.ITSM_INVALID_SOURCE_VERSION
		);
	}

	@Test
	void sameKeyWithChangedBody_isConflict() {
		ItsmIngestBatch existing = existingBatch("[{\"id\":\"1\"}]");
		given(batchRepository.findById(UUID.fromString(KEY))).willReturn(Optional.of(existing));

		assertAppError(
				() -> service.receive(KEY, SOURCE_ID, "7", "[{\"id\":\"2\"}]".getBytes(StandardCharsets.UTF_8)),
				AppErrors.ITSM_IDEMPOTENCY_CONFLICT
		);
	}

	@Test
	void sameSourceVersionWithDifferentKey_isConflict() {
		ItsmIngestBatch existing = existingBatch("[]");
		given(batchRepository.findById(UUID.fromString(KEY))).willReturn(Optional.empty());
		given(batchRepository.findBySourceIdAndSourceVersion(SOURCE_ID, 7))
				.willReturn(Optional.of(existing));

		assertAppError(
				() -> service.receive(KEY, SOURCE_ID, "7", "[]".getBytes(StandardCharsets.UTF_8)),
				AppErrors.ITSM_SOURCE_VERSION_CONFLICT
		);
	}

	@Test
	void queueLimit_isEnforcedAfterDuplicateLookups() {
		given(batchRepository.findById(UUID.fromString(KEY))).willReturn(Optional.empty());
		given(batchRepository.findBySourceIdAndSourceVersion(SOURCE_ID, 7)).willReturn(Optional.empty());
		given(sqlRepository.countOutstanding()).willReturn(10L);

		assertAppError(
				() -> service.receive(KEY, SOURCE_ID, "7", "[]".getBytes(StandardCharsets.UTF_8)),
				AppErrors.ITSM_INTAKE_QUEUE_FULL
		);
	}

	private void assertInvalidPayload(String body) {
		assertAppError(
				() -> service.receive(KEY, SOURCE_ID, "7", body.getBytes(StandardCharsets.UTF_8)),
				AppErrors.ITSM_INVALID_PAYLOAD
		);
	}

	private static void assertAppError(Runnable action, AppErrors expected) {
		assertThatThrownBy(action::run)
				.isInstanceOfSatisfying(AppException.class, exception ->
						assertThat(exception.getAppError()).isEqualTo(expected));
	}

	private static ItsmIngestBatch existingBatch(String payload) {
		byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
		ItsmIngestBatch batch = org.mockito.Mockito.mock(ItsmIngestBatch.class);
		org.mockito.Mockito.lenient().when(batch.getSourceId()).thenReturn(SOURCE_ID);
		org.mockito.Mockito.lenient().when(batch.getSourceVersion()).thenReturn(7L);
		org.mockito.Mockito.lenient().when(batch.getPayload()).thenReturn(bytes);
		org.mockito.Mockito.lenient().when(batch.getPayloadSha256()).thenReturn(sha256(bytes));
		return batch;
	}

	private static String sha256(byte[] bytes) {
		try {
			return java.util.HexFormat.of().formatHex(
					java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
			);
		} catch (java.security.NoSuchAlgorithmException exception) {
			throw new IllegalStateException(exception);
		}
	}
}
