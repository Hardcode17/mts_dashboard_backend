package ru.hardcode.dashboard.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import ru.hardcode.dashboard.entity.ItsmBatchStatus;

/**
 * PostgreSQL-specific intake operations. The advisory transaction lock serializes the
 * bounded backlog check and the insert; duplicate/idempotent requests still have to use
 * the database uniqueness constraints as the final authority.
 */
@Repository
public class ItsmBatchSqlRepository {

	/**
	 * Stable application-wide advisory lock key for the single ITSM intake stream.
	 */
	private static final long INTAKE_LOCK_KEY = 4_723_199_901_277L;
	private static final String BASELINE_LOCK_KEY = "hashtextextended(?, 65031)";

	private final JdbcTemplate jdbcTemplate;

	public ItsmBatchSqlRepository(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	public void lockIntake() {
		jdbcTemplate.queryForObject(
				"SELECT pg_advisory_xact_lock(?)",
				(rs, rowNum) -> Boolean.TRUE,
				INTAKE_LOCK_KEY
		);
	}

	/** Intake never waits behind a cold source's first publication. */
	public boolean isSourcePublishingBaseline(String sourceId) {
		return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
				"SELECT NOT pg_try_advisory_xact_lock(" + BASELINE_LOCK_KEY + ")", Boolean.class, sourceId));
	}

	public void lockSourceForBaseline(String sourceId) {
		jdbcTemplate.queryForObject("SELECT pg_advisory_xact_lock(" + BASELINE_LOCK_KEY + ")",
				(rs, rowNum) -> Boolean.TRUE, sourceId);
	}

	public boolean requeueTechnicalFailure(UUID batchId) {
		return jdbcTemplate.update("""
				UPDATE itsm_ingest_batch SET status = 'RECEIVED', status_updated_at = now(),
				    batch_attempts = 0, diagnostic = NULL
				WHERE id = ? AND status = 'FAILED' AND next_attempt_at IS NULL AND NOT validation_rejected
				""", batchId) == 1;
	}

	public long countOutstanding() {
		Long count = jdbcTemplate.queryForObject(
				"""
				SELECT count(*) FROM itsm_ingest_batch
				WHERE status IN (?, ?) OR (status = ? AND next_attempt_at IS NOT NULL)
				""",
				Long.class,
				ItsmBatchStatus.RECEIVED.name(), ItsmBatchStatus.PROCESSING.name(), ItsmBatchStatus.FAILED.name()
		);
		return count == null ? 0 : count;
	}

	public boolean isAwaitedVersion(String sourceId, long version) {
		return Boolean.TRUE.equals(jdbcTemplate.queryForObject("""
				SELECT EXISTS (SELECT 1 FROM itsm_ingest_batch WHERE source_id = ? AND waiting_for_version = ?)
				""", Boolean.class, sourceId, version));
	}

	public boolean insert(
			UUID id,
			String sourceId,
			long sourceVersion,
			byte[] payload,
			String payloadSha256,
			int payloadSize,
			Instant receivedAt
	) {
		List<UUID> inserted = jdbcTemplate.query(
				"""
				INSERT INTO itsm_ingest_batch (
				    id,
				    source_id,
				    source_version,
				    payload,
				    payload_sha256,
				    payload_size,
				    received_at,
				    status,
				    status_updated_at
				) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
				ON CONFLICT DO NOTHING
				RETURNING id
				""",
				(rs, rowNum) -> rs.getObject("id", UUID.class),
				id,
				sourceId,
				sourceVersion,
				payload,
				payloadSha256,
				payloadSize,
				Timestamp.from(receivedAt),
				ItsmBatchStatus.RECEIVED.name(),
				Timestamp.from(receivedAt)
		);
		return !inserted.isEmpty();
	}

	public Optional<StoredMetadata> findMetadata(UUID id) {
		List<StoredMetadata> rows = jdbcTemplate.query(
				"""
				SELECT id, source_id, source_version, received_at, status, diagnostic, next_attempt_at, waiting_for_version
				FROM itsm_ingest_batch
				WHERE id = ?
				""",
				(rs, rowNum) -> new StoredMetadata(
						rs.getObject("id", UUID.class),
						rs.getString("source_id"),
						rs.getLong("source_version"),
						instant(rs, "received_at"),
						ItsmBatchStatus.valueOf(rs.getString("status")),
						rs.getString("diagnostic"),
						instant(rs, "next_attempt_at"),
						rs.getObject("waiting_for_version", Long.class)
				),
				id
		);
		return rows.stream().findFirst();
	}

	public Optional<LatestBatch> findLatestBatch() {
		List<LatestBatch> rows = jdbcTemplate.query(
				"""
				SELECT received_at, status
				FROM itsm_ingest_batch
				ORDER BY received_at DESC, id DESC
				LIMIT 1
				""",
				(rs, rowNum) -> new LatestBatch(
						instant(rs, "received_at"),
						ItsmBatchStatus.valueOf(rs.getString("status"))
				)
		);
		return rows.stream().findFirst();
	}

	public Optional<SuccessfulBatch> findLatestSuccessfulBatch() {
		List<SuccessfulBatch> rows = jdbcTemplate.query(
				"""
				SELECT latest.processed_at, latest.received_at, (
				    SELECT max(processing_revision) FROM itsm_ingest_batch
				    WHERE status = 'SUCCEEDED' AND processed_at IS NOT NULL
				) AS processing_revision
				FROM itsm_ingest_batch AS latest
				WHERE latest.status = 'SUCCEEDED' AND latest.processed_at IS NOT NULL
				ORDER BY latest.processed_at DESC, latest.id DESC
				LIMIT 1
				""",
				(rs, rowNum) -> {
					Long revision = rs.getObject("processing_revision", Long.class);
					return new SuccessfulBatch(
							instant(rs, "processed_at"),
							revision,
							instant(rs, "received_at")
					);
				}
		);
		return rows.stream().findFirst();
	}

	private static Instant instant(ResultSet resultSet, String column) throws SQLException {
		Timestamp timestamp = resultSet.getTimestamp(column);
		return timestamp == null ? null : timestamp.toInstant();
	}

	public record StoredMetadata(
			UUID batchId,
			String sourceId,
			long sourceVersion,
			Instant receivedAt,
			ItsmBatchStatus status,
			String diagnostic,
			Instant nextAttemptAt,
			Long waitingForVersion
	) {
	}

	public record LatestBatch(Instant receivedAt, ItsmBatchStatus status) {
	}

	public record SuccessfulBatch(Instant processedAt, Long revision, Instant receivedAt) {
	}
}
