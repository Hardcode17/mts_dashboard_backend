ALTER TABLE coordination_tasks
    ADD COLUMN handoff_source_id varchar(128),
    ADD COLUMN handoff_source_version bigint,
    ADD COLUMN handoff_observed_at timestamptz,
    ADD COLUMN handoff_type varchar(16) CHECK (handoff_type IN ('TEAM', 'EXECUTOR')),
    ADD CONSTRAINT coordination_handoff_metadata CHECK (
        (handoff_source_id IS NULL AND handoff_source_version IS NULL AND handoff_observed_at IS NULL AND handoff_type IS NULL)
        OR (handoff_source_id IS NOT NULL AND handoff_source_version IS NOT NULL AND handoff_observed_at IS NOT NULL
            AND handoff_type IS NOT NULL AND status = 'completed' AND finished_at IS NOT NULL)
    );

CREATE TABLE coordination_official_assignment (
    source_id varchar(128) NOT NULL REFERENCES itsm_snapshot_source(source_id) ON DELETE CASCADE,
    incident_id varchar(128) NOT NULL,
    team varchar(300),
    login varchar(128),
    employee varchar(300),
    PRIMARY KEY (source_id, incident_id)
);

-- Recover the last REST observation rather than a later locally published PATCH.
INSERT INTO coordination_official_assignment(source_id, incident_id, team, login, employee)
SELECT i.source_id, i.id, nullif(btrim(b.data->>'assignedGroupName'), ''),
       nullif(btrim(b.data->>'assignedEmployeeLogin'), ''), nullif(btrim(b.data->>'assignedEmployee'), '')
FROM itsm_incident i
CROSS JOIN LATERAL (
    SELECT coalesce(
        (SELECT c.after_data FROM itsm_incident_change c
         WHERE c.source_id=i.source_id AND c.incident_id=i.id AND c.coordinator_id IS NULL
           AND c.after_data IS NOT NULL ORDER BY c.event_seq DESC LIMIT 1),
        CASE WHEN NOT EXISTS (SELECT 1 FROM itsm_incident_change c WHERE c.source_id=i.source_id
             AND c.incident_id=i.id AND c.coordinator_id IS NOT NULL) THEN i.source_data END
    ) AS data
) b
WHERE i.source_id IS NOT NULL AND b.data IS NOT NULL;
