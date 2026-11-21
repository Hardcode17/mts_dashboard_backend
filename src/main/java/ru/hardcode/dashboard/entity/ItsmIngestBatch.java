package ru.hardcode.dashboard.entity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Raw ITSM snapshot accepted by KAN-27. The processing fields are updated by the
 * separate KAN-54 worker; this service only stores them and exposes the snapshot state.
 */
@Entity
@Table(name = "itsm_ingest_batch")
public class ItsmIngestBatch {

	@Id
	@Column(name = "id", nullable = false, updatable = false)
	private UUID id;

	@Column(name = "source_id", nullable = false, length = 128, updatable = false)
	private String sourceId;

	@Column(name = "source_version", nullable = false, updatable = false)
	private long sourceVersion;

	@Column(name = "payload", nullable = false, updatable = false)
	private byte[] payload;

	@Column(name = "payload_sha256", nullable = false, length = 64, updatable = false)
	private String payloadSha256;

	@Column(name = "payload_size", nullable = false, updatable = false)
	private int payloadSize;

	@JdbcTypeCode(SqlTypes.TIMESTAMP_WITH_TIMEZONE)
	@Column(name = "received_at", nullable = false, updatable = false)
	private Instant receivedAt;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 32)
	private ItsmBatchStatus status;

	@JdbcTypeCode(SqlTypes.TIMESTAMP_WITH_TIMEZONE)
	@Column(name = "status_updated_at", nullable = false)
	private Instant statusUpdatedAt;

	@JdbcTypeCode(SqlTypes.TIMESTAMP_WITH_TIMEZONE)
	@Column(name = "processed_at")
	private Instant processedAt;

	@Column(name = "processing_revision")
	private Long processingRevision;

	@Column(name = "diagnostic", length = 2000)
	private String diagnostic;

	@Column(name = "row_version", nullable = false)
	private long rowVersion;

	@JdbcTypeCode(SqlTypes.TIMESTAMP_WITH_TIMEZONE)
	@Column(name = "next_attempt_at")
	private Instant nextAttemptAt;

	protected ItsmIngestBatch() {
	}

	public UUID getId() {
		return id;
	}

	public String getSourceId() {
		return sourceId;
	}

	public long getSourceVersion() {
		return sourceVersion;
	}

	public byte[] getPayload() {
		return payload;
	}

	public String getPayloadSha256() {
		return payloadSha256;
	}

	public int getPayloadSize() {
		return payloadSize;
	}

	public Instant getReceivedAt() {
		return receivedAt;
	}

	public ItsmBatchStatus getStatus() {
		return status;
	}

	public Instant getStatusUpdatedAt() {
		return statusUpdatedAt;
	}

	public Instant getProcessedAt() {
		return processedAt;
	}

	public Long getProcessingRevision() {
		return processingRevision;
	}

	public String getDiagnostic() {
		return diagnostic;
	}

	public long getRowVersion() {
		return rowVersion;
	}

	public Instant getNextAttemptAt() {
		return nextAttemptAt;
	}
}
