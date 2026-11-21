-- KAN-29 «Увидел»: the shift confirms exactly the alarm events it has shown. Acknowledgement
-- is stored on the queue row itself and is never overwritten: a repeated request (even from
-- another operator) keeps the author and time of the first acknowledgement, so a restart or
-- a replay cannot change attribution. Ack never closes an internal coordination task
-- (that is the server rule of KAN-32, not of this queue).
ALTER TABLE shift_event
    ADD COLUMN acknowledged_by BIGINT REFERENCES users(id),
    ADD COLUMN acknowledged_at TIMESTAMP(6) WITH TIME ZONE,
    ADD CONSTRAINT ck_shift_event_acknowledgement CHECK (
        (acknowledged_by IS NULL) = (acknowledged_at IS NULL)
    );

COMMENT ON COLUMN shift_event.acknowledged_by IS
    'KAN-29: local user that confirmed the shown alarm event first; never overwritten.';
COMMENT ON COLUMN shift_event.acknowledged_at IS
    'KAN-29: server time of the first «Увидел» of the event; never overwritten.';

-- The queue read returns the remaining (not yet acknowledged) events, ordered by position.
CREATE INDEX ix_shift_event_pending ON shift_event (id) WHERE acknowledged_at IS NULL;
