CREATE TABLE local_incident_event (
    event_seq bigint PRIMARY KEY,
    incident_id varchar(128) NOT NULL REFERENCES itsm_incident(id),
    change_revision bigint NOT NULL,
    kind varchar(64) NOT NULL CHECK (kind ~ '^[A-Z][A-Z0-9_]*$'),
    deduplication_key text NOT NULL,
    observed_at timestamptz NOT NULL,
    details jsonb NOT NULL CHECK (jsonb_typeof(details) = 'object'),
    UNIQUE (incident_id, kind, deduplication_key)
);

CREATE INDEX ix_local_incident_event_history ON local_incident_event (incident_id, event_seq);

CREATE TABLE deadline_warning_rule (
    singleton boolean PRIMARY KEY CHECK (singleton),
    threshold_seconds int NOT NULL CHECK (threshold_seconds > 0),
    version bigint NOT NULL CHECK (version > 0)
);

INSERT INTO deadline_warning_rule (singleton, threshold_seconds, version) VALUES (true, 900, 1);

CREATE INDEX ix_itsm_incident_active_deadline ON itsm_incident (deadline)
    WHERE present_in_source AND state IN ('Assigned', 'WorkInProgress') AND deadline IS NOT NULL;
