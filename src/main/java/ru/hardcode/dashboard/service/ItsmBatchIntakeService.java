package ru.hardcode.dashboard.service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import ru.hardcode.dashboard.entity.ItsmIngestBatch;
import ru.hardcode.dashboard.exception.AppErrors;
import ru.hardcode.dashboard.exception.AppException;
import ru.hardcode.dashboard.repository.ItsmBatchSqlRepository;
import ru.hardcode.dashboard.repository.ItsmIngestBatchRepository;
import ru.hardcode.dashboard.worker.ItsmWorkerLink;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Validates and durably accepts one full ITSM snapshot. It deliberately does not apply
 * KAN-21 record validation or process the snapshot. The KAN-54 worker loads committed batches
 * from the database; intake only notifies it after commit and never hands over its transaction.
 */
@Service
public class ItsmBatchIntakeService {

	private static final Pattern UUID_PATTERN = Pattern.compile(
			"[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
	);
	private static final Pattern SOURCE_ID_PATTERN = Pattern.compile("[\\x21-\\x7E]{1,128}");
	private static final Pattern POSITIVE_LONG_PATTERN = Pattern.compile("[1-9][0-9]*");

	private final ItsmIngestBatchRepository batchRepository;
	private final ItsmBatchSqlRepository sqlRepository;
	private final ObjectMapper objectMapper;
	private final ObjectProvider<ItsmWorkerLink> workerLinkProvider;
	private final int maxOutstandingBatches;

	public ItsmBatchIntakeService(
			ItsmIngestBatchRepository batchRepository,
			ItsmBatchSqlRepository sqlRepository,
			ObjectMapper objectMapper,
			ObjectProvider<ItsmWorkerLink> workerLinkProvider,
			@Value("${itsm.intake.max-outstanding-batches:1000}") int maxOutstandingBatches
	) {
		if (maxOutstandingBatches < 0) {
			throw new IllegalArgumentException("itsm.intake.max-outstanding-batches must be non-negative");
		}
		this.batchRepository = batchRepository;
		this.sqlRepository = sqlRepository;
		this.objectMapper = objectMapper;
		this.workerLinkProvider = workerLinkProvider;
		this.maxOutstandingBatches = maxOutstandingBatches;
	}

	@Transactional
	public UUID receive(String idempotencyKey, String sourceId, String sourceVersion, byte[] requestBody) {
		UUID batchId = parseBatchId(idempotencyKey);
		String validatedSourceId = validateSourceId(sourceId);
		long validatedSourceVersion = parseSourceVersion(sourceVersion);
		byte[] payload = requestBody == null ? null : requestBody.clone();
		validateJsonArray(payload);
		String payloadSha256 = sha256(payload);

		sqlRepository.lockIntake();
		Optional<ItsmIngestBatch> existingById = batchRepository.findById(batchId);
		if (existingById.isPresent()) {
			return replayOrConflict(existingById.get(), validatedSourceId, validatedSourceVersion, payloadSha256, payload);
		}

		Optional<ItsmIngestBatch> existingBySourceVersion =
				batchRepository.findBySourceIdAndSourceVersion(validatedSourceId, validatedSourceVersion);
		if (existingBySourceVersion.isPresent()) {
			throw new AppException(AppErrors.ITSM_SOURCE_VERSION_CONFLICT);
		}

		if (sqlRepository.countOutstanding() >= maxOutstandingBatches) {
			throw new AppException(AppErrors.ITSM_INTAKE_QUEUE_FULL, Map.of("max", maxOutstandingBatches));
		}

		Instant receivedAt = Instant.now();
		boolean inserted = sqlRepository.insert(
				batchId,
				validatedSourceId,
				validatedSourceVersion,
				payload,
				payloadSha256,
				payload.length,
				receivedAt
		);
		if (inserted) {
			notifyWorkerAfterCommit();
			return batchId;
		}

		// The advisory lock normally prevents this branch. The database constraints remain the
		// final authority, so re-read and resolve a row inserted by another process.
		Optional<ItsmIngestBatch> concurrentById = batchRepository.findById(batchId);
		if (concurrentById.isPresent()) {
			return replayOrConflict(concurrentById.get(), validatedSourceId, validatedSourceVersion, payloadSha256, payload);
		}
		if (batchRepository.findBySourceIdAndSourceVersion(validatedSourceId, validatedSourceVersion).isPresent()) {
			throw new AppException(AppErrors.ITSM_SOURCE_VERSION_CONFLICT);
		}
		throw new AppException(AppErrors.ITSM_IDEMPOTENCY_CONFLICT);
	}

	/**
	 * The worker is notified only once the intake transaction commits, so it can never observe an
	 * uncommitted batch or join the HTTP transaction. If the transaction rolls back, no
	 * notification is sent and nothing is claimed.
	 */
	private void notifyWorkerAfterCommit() {
		if (!TransactionSynchronizationManager.isSynchronizationActive()) {
			wakeWorker();
			return;
		}
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCommit() {
				wakeWorker();
			}
		});
	}

	/**
	 * Resolves the link lazily: the worker may be created after intake during start-up, and not
	 * at all when no processor exists. A notification failure never affects intake.
	 */
	private void wakeWorker() {
		try {
			ItsmWorkerLink link = workerLinkProvider.getIfAvailable();
			if (link != null) {
				link.wake();
			}
		} catch (RuntimeException ignored) {
			// The database remains the queue: the worker poll picks the batch up anyway.
		}
	}

	private UUID replayOrConflict(
			ItsmIngestBatch existing,
			String sourceId,
			long sourceVersion,
			String payloadSha256,
			byte[] payload
	) {
		boolean sameRequest = existing.getSourceId().equals(sourceId)
				&& existing.getSourceVersion() == sourceVersion
				&& existing.getPayloadSha256().equals(payloadSha256)
				&& MessageDigest.isEqual(existing.getPayload(), payload);
		if (!sameRequest) {
			throw new AppException(AppErrors.ITSM_IDEMPOTENCY_CONFLICT);
		}
		return existing.getId();
	}

	private static UUID parseBatchId(String value) {
		if (value == null || !UUID_PATTERN.matcher(value).matches()) {
			throw new AppException(AppErrors.ITSM_INVALID_IDEMPOTENCY_KEY);
		}
		try {
			return UUID.fromString(value);
		} catch (IllegalArgumentException exception) {
			throw new AppException(AppErrors.ITSM_INVALID_IDEMPOTENCY_KEY, exception);
		}
	}

	private static String validateSourceId(String value) {
		if (value == null || !SOURCE_ID_PATTERN.matcher(value).matches()) {
			throw new AppException(AppErrors.ITSM_INVALID_SOURCE_ID);
		}
		return value;
	}

	private static long parseSourceVersion(String value) {
		if (value == null || !POSITIVE_LONG_PATTERN.matcher(value).matches()) {
			throw new AppException(AppErrors.ITSM_INVALID_SOURCE_VERSION);
		}
		try {
			return Long.parseLong(value);
		} catch (NumberFormatException exception) {
			throw new AppException(AppErrors.ITSM_INVALID_SOURCE_VERSION, exception);
		}
	}

	private void validateJsonArray(byte[] payload) {
		if (payload == null || payload.length == 0) {
			throw new AppException(AppErrors.ITSM_INVALID_PAYLOAD);
		}
		try (JsonParser parser = objectMapper.createParser(payload)) {
			JsonNode root = objectMapper.readTree(parser);
			if (root == null || !root.isArray()) {
				throw new AppException(AppErrors.ITSM_INVALID_PAYLOAD);
			}
			if (parser.nextToken() != null) {
				throw new AppException(AppErrors.ITSM_INVALID_PAYLOAD);
			}
		} catch (JacksonException exception) {
			throw new AppException(AppErrors.ITSM_INVALID_PAYLOAD, exception);
		}
	}

	private static String sha256(byte[] payload) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(digest.digest(payload));
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is not available", exception);
		}
	}
}
