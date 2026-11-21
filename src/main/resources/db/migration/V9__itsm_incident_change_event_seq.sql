-- KAN-28: a monotonic event sequence for the INC change journal, independent of the
-- snapshot change revision (one revision can carry many events), plus the observation
-- time of the backend and the optional source-side time of each record.
CREATE SEQUENCE itsm_incident_change_event_seq;

ALTER TABLE itsm_incident_change
    ADD COLUMN event_seq BIGINT,
    ADD COLUMN observed_at TIMESTAMP(6) WITH TIME ZONE,
    ADD COLUMN source_time TIMESTAMP(6) WITH TIME ZONE;

-- Committed rows keep their publication order; ties inside one revision get a stable,
-- once-assigned sequence number and never change identity afterwards.
UPDATE itsm_incident_change
SET event_seq = nextval('itsm_incident_change_event_seq');

-- The observation time of a journal row written before this migration is the moment its
-- snapshot was applied; rows without a matching snapshot keep the migration moment.
UPDATE itsm_incident_change AS change
SET observed_at = coalesce((
        SELECT snapshot.applied_at
        FROM itsm_snapshot AS snapshot
        WHERE snapshot.source_id = change.source_id AND snapshot.source_version = change.source_version
    ), now());

ALTER TABLE itsm_incident_change
    ALTER COLUMN event_seq SET NOT NULL,
    ALTER COLUMN observed_at SET NOT NULL;

CREATE UNIQUE INDEX ux_itsm_incident_change_event_seq ON itsm_incident_change (event_seq);
CREATE INDEX ix_itsm_incident_change_incident ON itsm_incident_change (incident_id, event_seq);
