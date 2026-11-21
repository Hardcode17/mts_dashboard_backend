ALTER TABLE coordination_tasks
    ADD COLUMN coordinator_id bigint REFERENCES users(id),
    ADD COLUMN accepted_at timestamptz,
    ADD CONSTRAINT coordination_task_acceptance_has_owner
        CHECK (accepted_at IS NULL OR coordinator_id IS NOT NULL);

CREATE INDEX coordination_tasks_open_coordinator
    ON coordination_tasks(coordinator_id, id) WHERE status = 'open';

-- A viewer's window is not an assignment. Keep it even after expiry so a reload
-- cannot reopen it; a new queue episode uses a new task ID.
CREATE TABLE coordination_task_window (
    task_id bigint NOT NULL REFERENCES coordination_tasks(id) ON DELETE CASCADE,
    viewer_id bigint NOT NULL REFERENCES users(id),
    window_id uuid NOT NULL UNIQUE,
    expires_at timestamptz NOT NULL,
    PRIMARY KEY (task_id, viewer_id)
);
