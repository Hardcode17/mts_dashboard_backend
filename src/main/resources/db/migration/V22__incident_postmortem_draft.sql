CREATE TABLE incident_postmortem_draft (
    resolution_event_seq bigint PRIMARY KEY REFERENCES itsm_incident_change (event_seq) ON DELETE CASCADE,
    incident_id varchar(128) NOT NULL REFERENCES itsm_incident (id),
    draft jsonb NOT NULL CHECK (jsonb_typeof(draft) = 'object')
);

CREATE INDEX ix_postmortem_draft_incident
    ON incident_postmortem_draft (incident_id, resolution_event_seq DESC);
