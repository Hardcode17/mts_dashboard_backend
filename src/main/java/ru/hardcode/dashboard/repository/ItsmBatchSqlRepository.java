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

	public long countOutstanding() {
		Long count = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM itsm_ingest_batch WHERE status IN (?, ?)",
				Long.class,
				ItsmBatchStatus.RECEIVED.name(), ItsmBatchStatus.PROCESSING.name()
		);
		return count == null ? 0 : count;
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
				SELECT id, source_id, source_version, received_at, status, diagnostic
				FROM itsm_ingest_batch
				WHERE id = ?
				""",
				(rs, rowNum) -> new StoredMetadata(
						rs.getObject("id", UUID.class),
						rs.getString("source_id"),
						rs.getLong("source_version"),
						instant(rs, "received_at"),
						ItsmBatchStatus.valueOf(rs.getString("status")),
						rs.getString("diagnostic")
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
				SELECT processed_at, processing_revision
				FROM itsm_ingest_batch
				WHERE status = ? AND processed_at IS NOT NULL
				ORDER BY processed_at DESC, id DESC
				LIMIT 1
				""",
				(rs, rowNum) -> {
					Long revision = rs.getObject("processing_revision", Long.class);
					return new SuccessfulBatch(
							instant(rs, "processed_at"),
							revision
					);
				},
				ItsmBatchStatus.SUCCEEDED.name()
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
			String diagnostic
	) {
	}

	public record LatestBatch(Instant receivedAt, ItsmBatchStatus status) {
	}

	public record SuccessfulBatch(Instant processedAt, Long revision) {
	}
}
