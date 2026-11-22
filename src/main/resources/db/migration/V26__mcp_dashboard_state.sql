-- KAN-86: stable state for the /mcp/dashboard MCP adapter.
-- journal_epoch is the stable identity of the itsm_incident_change journal (KAN-28):
-- it is stored once, survives restarts, and changes only when the journal is reset or
-- replaced. MCP eventIds (inc-change:<journal_epoch>:<event_seq>) and cursor payloads
-- are bound to it, so a journal reset invalidates every outstanding cursor at once.
CREATE TABLE mcp_dashboard_state (
    singleton boolean PRIMARY KEY CHECK (singleton),
    journal_epoch uuid NOT NULL,
    initialized_at timestamptz NOT NULL DEFAULT now()
);

INSERT INTO mcp_dashboard_state(singleton, journal_epoch)
VALUES (TRUE, gen_random_uuid())
ON CONFLICT DO NOTHING;
