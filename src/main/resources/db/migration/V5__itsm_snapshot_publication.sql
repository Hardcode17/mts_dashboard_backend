ALTER TABLE itsm_ingest_batch
    ADD COLUMN waiting_for_version BIGINT,
    ADD COLUMN validation_rejected BOOLEAN NOT NULL DEFAULT FALSE,
    ADD CONSTRAINT ck_itsm_ingest_batch_waiting CHECK (
        waiting_for_version IS NULL OR (status = 'RECEIVED' AND waiting_for_version < source_version)
    ),
    ADD CONSTRAINT ck_itsm_ingest_batch_rejected CHECK (
        NOT validation_rejected OR (status = 'FAILED' AND next_attempt_at IS NULL)
    );

CREATE INDEX ix_itsm_ingest_batch_waiting
    ON itsm_ingest_batch (source_id, waiting_for_version)
    WHERE waiting_for_version IS NOT NULL;

CREATE INDEX ix_itsm_ingest_batch_applied_at
    ON itsm_ingest_batch (processed_at DESC, id DESC)
    WHERE status = 'SUCCEEDED' AND processed_at IS NOT NULL;

CREATE INDEX ix_itsm_ingest_batch_applied_revision
    ON itsm_ingest_batch (processing_revision DESC)
    WHERE status = 'SUCCEEDED' AND processed_at IS NOT NULL;

CREATE SEQUENCE itsm_snapshot_change_revision;

SELECT setval('itsm_snapshot_change_revision', greatest(coalesce(max(processing_revision), 1), 1),
              coalesce(max(processing_revision), 0) > 0)
FROM itsm_ingest_batch WHERE status = 'SUCCEEDED' AND processed_at IS NOT NULL;

CREATE TABLE itsm_snapshot_source (
    source_id VARCHAR(128) PRIMARY KEY,
    observed_version BIGINT,
    change_revision BIGINT,
    CONSTRAINT ck_itsm_snapshot_source_observation CHECK (
        (observed_version IS NULL) = (change_revision IS NULL)
    )
);

INSERT INTO itsm_snapshot_source (source_id, observed_version, change_revision)
SELECT snapshot.source_id, snapshot.source_version,
       coalesce(batch.processing_revision, nextval('itsm_snapshot_change_revision'))
FROM itsm_snapshot AS snapshot
LEFT JOIN itsm_ingest_batch AS batch ON batch.id = snapshot.batch_id
    AND batch.status = 'SUCCEEDED' AND batch.processed_at IS NOT NULL;

ALTER TABLE itsm_incident
    ADD COLUMN source_id VARCHAR(128),
    ADD COLUMN source_data JSONB,
    ADD COLUMN local_data JSONB NOT NULL DEFAULT '{}',
    ADD COLUMN present_in_source BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN last_seen_version BIGINT,
    ADD COLUMN last_change_revision BIGINT;

UPDATE itsm_incident AS incident
SET source_id = source.source_id,
    last_seen_version = source.observed_version,
    last_change_revision = source.change_revision
FROM itsm_snapshot_source AS source;

CREATE INDEX ix_itsm_incident_source_presence ON itsm_incident (source_id, present_in_source);

CREATE TABLE itsm_incident_change (
    source_id VARCHAR(128) NOT NULL REFERENCES itsm_snapshot_source (source_id),
    incident_id VARCHAR(128) NOT NULL,
    source_version BIGINT NOT NULL,
    change_revision BIGINT NOT NULL,
    change_kind VARCHAR(16) NOT NULL CHECK (change_kind IN ('ADDED', 'UPDATED', 'MISSING', 'RETURNED')),
    before_data JSONB,
    after_data JSONB,
    PRIMARY KEY (source_id, incident_id, source_version)
);

CREATE INDEX ix_itsm_incident_change_revision ON itsm_incident_change (change_revision);
