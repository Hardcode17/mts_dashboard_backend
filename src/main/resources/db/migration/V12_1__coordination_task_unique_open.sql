-- A repeat or concurrent delivery cannot create two open tasks for one INC.
CREATE UNIQUE INDEX coordination_tasks_one_open_per_incident
    ON coordination_tasks (incident_id) WHERE status = 'open';
