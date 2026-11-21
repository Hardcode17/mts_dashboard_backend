-- KAN-54 worker state on the KAN-27 durable queue. The row itself is the work item.
ALTER TABLE itsm_ingest_batch
    ADD COLUMN batch_attempts INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN claimed_by VARCHAR(255),
    ADD COLUMN heartbeat_at TIMESTAMP(6) WITH TIME ZONE,
    ADD COLUMN next_attempt_at TIMESTAMP(6) WITH TIME ZONE,
    ADD COLUMN row_version BIGINT NOT NULL DEFAULT 0;

ALTER TABLE itsm_ingest_batch
    ADD CONSTRAINT ck_itsm_ingest_batch_attempts CHECK (batch_attempts >= 0),
    ADD CONSTRAINT ck_itsm_ingest_batch_row_version CHECK (row_version >= 0),
    ADD CONSTRAINT ck_itsm_ingest_batch_claim CHECK (
        (claimed_by IS NULL AND heartbeat_at IS NULL)
        OR (claimed_by IS NOT NULL AND heartbeat_at IS NOT NULL)
    );

-- Every write by the worker advances row_version. A claim that is lost (crash, database
-- restart) is fenced by the old row_version, so it can never overwrite newer state.
CREATE FUNCTION itsm_ingest_batch_bump_row_version() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    NEW.row_version := OLD.row_version + 1;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_itsm_ingest_batch_row_version
    BEFORE UPDATE ON itsm_ingest_batch
    FOR EACH ROW
    EXECUTE FUNCTION itsm_ingest_batch_bump_row_version();

-- Claim order: oldest available RECEIVED row. Retries carry next_attempt_at.
CREATE INDEX ix_itsm_ingest_batch_claim
    ON itsm_ingest_batch (status, next_attempt_at, received_at, id);

-- Recovery and liveness lookups of unfinished claims.
CREATE INDEX ix_itsm_ingest_batch_claimed_by
    ON itsm_ingest_batch (claimed_by, heartbeat_at)
    WHERE claimed_by IS NOT NULL;

COMMENT ON COLUMN itsm_ingest_batch.batch_attempts IS
    'KAN-54 processing attempts started for this batch.';
COMMENT ON COLUMN itsm_ingest_batch.claimed_by IS
    'KAN-54 claim owner (instance:process:uuid); NULL when no worker holds the batch.';
COMMENT ON COLUMN itsm_ingest_batch.heartbeat_at IS
    'KAN-54 claim start/liveness timestamp used for restart recovery fencing.';
COMMENT ON COLUMN itsm_ingest_batch.next_attempt_at IS
    'KAN-54 automatic retry time after a normal failure; NULL when no retry is scheduled.';
COMMENT ON COLUMN itsm_ingest_batch.row_version IS
    'Optimistic fence advanced on every update; workers write only with the version they claimed.';
