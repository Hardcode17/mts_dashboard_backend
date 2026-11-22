-- KAN-43: event_seq points to the NOTE_ADDED event written in the same transaction.
CREATE TABLE incident_note (
    id varchar(36) PRIMARY KEY,
    incident_id varchar(128) NOT NULL REFERENCES itsm_incident (id),
    coordinator_id bigint NOT NULL REFERENCES users (id),
    observed_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    note_text varchar(4000) NOT NULL,
    event_seq bigint NOT NULL REFERENCES local_incident_event (event_seq),
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT ck_incident_note_text CHECK (btrim(note_text) <> ''),
    CONSTRAINT ux_incident_note_event_seq UNIQUE (event_seq)
);

-- The history of one INC pages by the shared event sequence, so parallel notes with one
-- observation time still get distinct positions and one cursor read returns both.
CREATE INDEX ix_incident_note_incident ON incident_note (incident_id, event_seq);
