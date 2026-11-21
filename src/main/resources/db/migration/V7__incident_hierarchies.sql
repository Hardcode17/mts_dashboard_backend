-- KAN-66: one imported direct parent per incident and several children per parent.
--
-- parent_id and child_id are the external ITSM incident numbers from the same source
-- snapshot, never sysId and never a local database key. The relationship is optional:
-- an incident without a row here has no imported parent. child_id is the primary key,
-- so a retried delivery can never create a second parent for the same incident.
CREATE TABLE incident_hierarchies (
    child_id VARCHAR(128) PRIMARY KEY,
    parent_id VARCHAR(128) NOT NULL,
    CONSTRAINT ck_incident_hierarchies_not_self CHECK (parent_id <> child_id),
    CONSTRAINT fk_incident_hierarchies_child FOREIGN KEY (child_id)
        REFERENCES itsm_incident (id) ON DELETE CASCADE,
    CONSTRAINT fk_incident_hierarchies_parent FOREIGN KEY (parent_id)
        REFERENCES itsm_incident (id) ON DELETE CASCADE
);

CREATE INDEX ix_incident_hierarchies_parent
    ON incident_hierarchies (parent_id);

COMMENT ON TABLE incident_hierarchies IS
    'Imported one-step incident hierarchy: one direct parent and any number of children.';
COMMENT ON COLUMN incident_hierarchies.child_id IS
    'External ITSM id of the child incident; unique, so it has at most one direct parent.';
COMMENT ON COLUMN incident_hierarchies.parent_id IS
    'External ITSM id of the direct parent in the same source; not sysId and not a database id.';
