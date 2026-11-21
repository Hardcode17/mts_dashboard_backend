-- KAN-25 durable queue of significant shift events. One journaled change of one INC
-- (see itsm_incident_change, written by the KAN-55/KAN-22 publication paths) becomes at
-- most one queue event: every reason matched by that single update is merged into one row,
-- and (source_id, incident_id, change_revision) keeps a replay of the same revision from
-- adding anything.
--
-- eventId is assigned once from shift_event_id_seq as 'evt-' with at least four digits; the
-- sequence can leave gaps, because a replayed revision consumes a number without inserting
-- a row. Uniqueness and stability are guaranteed by the constraints, not by the gapless run.

-- Per-source baseline: the revision of the first journaled change of a source. Detection
-- skips rows at or below it, so the first full snapshot import of a source (and its already
-- active P4 rows) never floods the shift queue with signals.
CREATE TABLE shift_event_source (
    source_id VARCHAR(128) PRIMARY KEY,
    baseline_revision BIGINT NOT NULL
);

COMMENT ON TABLE shift_event_source IS
    'KAN-25: revision of the first journaled change per source; the first full snapshot import is the baseline and raises no shift signals.';

CREATE SEQUENCE shift_event_id_seq;

-- One significant event of the shift for one incident change. reasons holds the merged
-- codes of the single update; ack_required marks the alarm signals that KAN-29 will let the
-- shift confirm with «Увидел». payload is the notification data of the INC after the change.
CREATE TABLE shift_event (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_id VARCHAR(24) NOT NULL,
    source_id VARCHAR(128) NOT NULL,
    incident_id VARCHAR(128) NOT NULL,
    change_revision BIGINT NOT NULL,
    source_version BIGINT,
    reasons TEXT[] NOT NULL,
    ack_required BOOLEAN NOT NULL,
    payload JSONB NOT NULL,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT uq_shift_event_event_id UNIQUE (event_id),
    CONSTRAINT uq_shift_event_change UNIQUE (source_id, incident_id, change_revision),
    CONSTRAINT ck_shift_event_event_id CHECK (event_id ~ '^evt-[0-9]{4,}$'),
    CONSTRAINT ck_shift_event_reasons CHECK (
        reasons <@ ARRAY['new_critical', 'became_critical', 'critical_reopened',
                         'assigned_to_coordinators', 'critical_resolved']
    )
);

COMMENT ON TABLE shift_event IS
    'KAN-25: durable queue of significant shift events, deduplicated per (source, incident, change revision).';
COMMENT ON COLUMN shift_event.reasons IS
    'Merged reason codes of one INC update: new_critical, became_critical, critical_reopened, assigned_to_coordinators, critical_resolved.';
COMMENT ON COLUMN shift_event.ack_required IS
    'True only for the alarm reasons (new_critical, became_critical, critical_reopened); KAN-29 confirms exactly these shown events.';

-- Singleton detection cursor: every processed journal row is at or below last_revision.
-- The cursor is durable, so detection is idempotent after a restart or a replayed package.
CREATE TABLE shift_event_progress (
    singleton BOOLEAN PRIMARY KEY CHECK (singleton),
    last_revision BIGINT NOT NULL
);

INSERT INTO shift_event_progress (singleton, last_revision) VALUES (true, 0);
