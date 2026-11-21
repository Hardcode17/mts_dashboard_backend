-- KAN-82 deadline risk: the last scoring pass of active incidents with a deadline.
--
-- The rows are a full refresh written by the snapshot wake-up after every committed ITSM
-- publication; readers never see a partial state. A missing row means the pass has not run
-- (or the incident is completed / without a deadline) and the card reports risk = unknown.
-- The scoring only reads ITSM data: it never changes the deadline, priority or state.

CREATE TABLE deadline_risk (
    incident_id VARCHAR(128) PRIMARY KEY REFERENCES itsm_incident (id) ON DELETE CASCADE,
    risk VARCHAR(8) NOT NULL CHECK (risk IN ('low', 'medium', 'high', 'unknown')),
    score DOUBLE PRECISION,
    evaluated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    reasons JSONB NOT NULL DEFAULT '[]'::jsonb,
    CONSTRAINT ck_deadline_risk_reasons CHECK (jsonb_typeof(reasons) = 'array')
);

COMMENT ON TABLE deadline_risk IS
    'Last deadline risk scoring (KAN-82): one row per active incident with a deadline, fully replaced on every snapshot publication.';
