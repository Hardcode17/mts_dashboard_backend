-- V15 trimmed the group; restore the exact REST value where the historical data is available.
UPDATE coordination_official_assignment a
SET team = CASE WHEN btrim(b.data->>'assignedGroupName') <> ''
                THEN b.data->>'assignedGroupName' END
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
WHERE a.source_id=i.source_id AND a.incident_id=i.id AND b.data IS NOT NULL
  AND a.team IS NOT DISTINCT FROM nullif(btrim(b.data->>'assignedGroupName'), '');
