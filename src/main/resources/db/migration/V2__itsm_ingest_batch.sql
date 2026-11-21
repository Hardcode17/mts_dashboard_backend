-- Raw ITSM snapshots received over HTTP. The row id is the KAN-53 Idempotency-Key.
CREATE TABLE itsm_ingest_batch (
    id UUID PRIMARY KEY,
    source_id VARCHAR(128) NOT NULL,
    source_version BIGINT NOT NULL,
    payload BYTEA NOT NULL,
    payload_sha256 VARCHAR(64) NOT NULL,
    payload_size INTEGER NOT NULL,
    received_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    status VARCHAR(32) NOT NULL,
    status_updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    processed_at TIMESTAMP(6) WITH TIME ZONE,
    processing_revision BIGINT,
    diagnostic VARCHAR(2000),
    CONSTRAINT ck_itsm_ingest_batch_source_version CHECK (source_version > 0),
    CONSTRAINT ck_itsm_ingest_batch_payload_size CHECK (payload_size >= 0),
    CONSTRAINT ck_itsm_ingest_batch_status CHECK (
        status IN ('RECEIVED', 'PROCESSING', 'SUCCEEDED', 'FAILED')
    ),
    CONSTRAINT uq_itsm_ingest_batch_source_version UNIQUE (source_id, source_version)
);

CREATE INDEX ix_itsm_ingest_batch_received_at
    ON itsm_ingest_batch (received_at DESC, id DESC);

CREATE INDEX ix_itsm_ingest_batch_status
    ON itsm_ingest_batch (status);

COMMENT ON TABLE itsm_ingest_batch IS
    'Raw full ITSM snapshots accepted by KAN-27; processing is owned by KAN-54.';
COMMENT ON COLUMN itsm_ingest_batch.id IS
    'Idempotency-Key from the sender and the public batchId.';
COMMENT ON COLUMN itsm_ingest_batch.payload IS
    'Exact request bytes. Never returned by the HTTP API.';
COMMENT ON COLUMN itsm_ingest_batch.processed_at IS
    'Worker success timestamp used as snapshot lastSuccessAt.';
COMMENT ON COLUMN itsm_ingest_batch.processing_revision IS
    'Worker-provided applied revision; NULL until the first success.';
COMMENT ON COLUMN itsm_ingest_batch.diagnostic IS
    'Safe worker or receive diagnostic; NO payload or secret values.';
