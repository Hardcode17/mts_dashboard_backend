package ru.hardcode.dashboard.worker;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import ru.hardcode.dashboard.entity.ItsmBatchStatus;

/**
 * PostgreSQL operations for the durable queue. Claims commit before processing starts and
 * carry a row version. Processing holds a row lock, so recovery skips active work.
 */
public class ItsmWorkerSqlRepository {

	private final JdbcTemplate jdbcTemplate;

	public ItsmWorkerSqlRepository(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	public Optional<ClaimedBatch> claimNext(String owner, Instant now) {
		Timestamp timestamp = Timestamp.from(now);
		List<ClaimedBatch> rows = jdbcTemplate.query(
				"""
				WITH candidate AS (
				    SELECT id
				    FROM itsm_ingest_batch
				    WHERE status = ? OR (status = ? AND next_attempt_at <= ?)
				    ORDER BY COALESCE(next_attempt_at, received_at), id
				    LIMIT 1
				    FOR UPDATE SKIP LOCKED
				)
				UPDATE itsm_ingest_batch AS batch
				SET status = ?,
				    status_updated_at = ?,
				    batch_attempts = batch.batch_attempts + 1,
				    claimed_by = ?,
				    heartbeat_at = ?,
				    next_attempt_at = NULL,
				    diagnostic = NULL
				FROM candidate
				WHERE batch.id = candidate.id
				RETURNING batch.id, batch.source_id, batch.source_version, batch.payload,
				          batch.batch_attempts, batch.row_version
				""",
				(rs, rowNum) -> new ClaimedBatch(
						rs.getObject("id", UUID.class),
						rs.getString("source_id"),
						rs.getLong("source_version"),
						rs.getBytes("payload"),
						rs.getInt("batch_attempts"),
						rs.getLong("row_version")
				),
				ItsmBatchStatus.RECEIVED.name(),
				ItsmBatchStatus.FAILED.name(),
				timestamp,
				ItsmBatchStatus.PROCESSING.name(),
				timestamp,
				owner,
				timestamp
		);
		return rows.stream().findFirst();
	}

	public boolean lockClaim(ClaimedBatch batch, String owner) {
		return !jdbcTemplate.query(
				"""
				SELECT id FROM itsm_ingest_batch
				WHERE id = ? AND status = ? AND claimed_by = ? AND row_version = ?
				FOR UPDATE
				""",
				(rs, rowNum) -> rs.getObject("id", UUID.class),
				batch.batchId(),
				ItsmBatchStatus.PROCESSING.name(),
				owner,
				batch.rowVersion()
		).isEmpty();
	}

	/**
	 * Records a successful attempt. This runs in the same transaction as the processor, so the
	 * status and the published state commit or roll back together.
	 */
	public boolean markSucceeded(
			UUID batchId,
			String owner,
			int attempt,
			long rowVersion,
			long processingRevision,
			String diagnostic,
			Instant processedAt
	) {
		int updated = jdbcTemplate.update(
				"""
				UPDATE itsm_ingest_batch
				SET status = ?,
				    status_updated_at = ?,
				    processed_at = ?,
				    processing_revision = ?,
				    diagnostic = ?,
				    claimed_by = NULL,
				    heartbeat_at = NULL,
				    next_attempt_at = NULL
				WHERE id = ? AND status = ? AND claimed_by = ? AND batch_attempts = ?
				  AND row_version = ?
				""",
				ItsmBatchStatus.SUCCEEDED.name(),
				Timestamp.from(processedAt),
				Timestamp.from(processedAt),
				processingRevision,
				diagnostic,
				batchId,
				ItsmBatchStatus.PROCESSING.name(),
				owner,
				attempt,
				rowVersion
		);
		return updated == 1;
	}

	/**
	 * Records a failed attempt and releases the claim. The caller owns a separate short
	 * transaction for this write.
	 */
	public boolean markFailed(
			UUID batchId,
			String owner,
			int attempt,
			long rowVersion,
			String diagnostic,
			Instant nextAttemptAt,
			Instant now
	) {
		Object nextAttempt = nextAttemptAt == null ? null : Timestamp.from(nextAttemptAt);
		int updated = jdbcTemplate.update(
				"""
				UPDATE itsm_ingest_batch
				SET status = ?,
				    status_updated_at = ?,
				    diagnostic = ?,
				    claimed_by = NULL,
				    heartbeat_at = NULL,
				    next_attempt_at = ?
				WHERE id = ? AND status = ? AND claimed_by = ? AND batch_attempts = ? AND row_version = ?
				""",
				ItsmBatchStatus.FAILED.name(),
				Timestamp.from(now),
				diagnostic,
				nextAttempt,
				batchId,
				ItsmBatchStatus.PROCESSING.name(),
				owner,
				attempt,
				rowVersion
		);
		return updated == 1;
	}

	/** Requeues stale claims without waiting for active processing transactions. */
	public int recoverAbandoned(Instant now, Instant heartbeatBefore) {
		return jdbcTemplate.update(
				"""
				WITH abandoned AS (
				    SELECT id FROM itsm_ingest_batch
				    WHERE status = ? AND (heartbeat_at IS NULL OR heartbeat_at < ?)
				    FOR UPDATE SKIP LOCKED
				)
				UPDATE itsm_ingest_batch AS batch
				SET status = ?,
				    status_updated_at = ?,
				    diagnostic = ?,
				    claimed_by = NULL,
				    heartbeat_at = NULL
				FROM abandoned
				WHERE batch.id = abandoned.id
				""",
				ItsmBatchStatus.PROCESSING.name(),
				Timestamp.from(heartbeatBefore),
				ItsmBatchStatus.RECEIVED.name(),
				Timestamp.from(now),
				ItsmWorkerService.ABANDONED_DIAGNOSTIC
		);
	}

	/**
	 * True when the same source already has a newer batch awaiting processing.
	 */
	public boolean hasPendingNewerVersion(String sourceId, long sourceVersion, UUID batchId) {
		Boolean pending = jdbcTemplate.queryForObject(
				"""
				SELECT EXISTS (
				    SELECT 1
				    FROM itsm_ingest_batch
				    WHERE source_id = ?
				      AND source_version > ?
				      AND id <> ?
				      AND status IN (?, ?)
				)
				""",
				Boolean.class,
				sourceId,
				sourceVersion,
				batchId,
				ItsmBatchStatus.RECEIVED.name(),
				ItsmBatchStatus.PROCESSING.name()
		);
		return Boolean.TRUE.equals(pending);
	}

	public record ClaimedBatch(
			UUID batchId,
			String sourceId,
			long sourceVersion,
			byte[] payload,
			int attempt,
			long rowVersion
	) {
	}

}
