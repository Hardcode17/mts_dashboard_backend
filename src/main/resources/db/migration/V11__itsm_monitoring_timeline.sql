-- KAN-62 durable monitoring history. One row is one published ITSM snapshot observation.
--
-- The row is written in the same transaction as the published snapshot, so a restart can never
-- lose an observation or expose one for a publication that rolled back. Category counts overlap
-- by design (for example critical and coordinator), exactly as in docs/incident-classification.md.
CREATE TABLE itsm_monitoring_observation (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    batch_id UUID NOT NULL,
    observed_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    critical_count INTEGER NOT NULL,
    high_count INTEGER NOT NULL,
    coordinator_count INTEGER NOT NULL,
    research_count INTEGER NOT NULL,
    CONSTRAINT uq_itsm_monitoring_observation_batch UNIQUE (batch_id),
    CONSTRAINT fk_itsm_monitoring_observation_batch FOREIGN KEY (batch_id)
        REFERENCES itsm_ingest_batch (id) ON DELETE CASCADE,
    CONSTRAINT ck_itsm_monitoring_observation_critical CHECK (critical_count >= 0),
    CONSTRAINT ck_itsm_monitoring_observation_high CHECK (high_count >= 0),
    CONSTRAINT ck_itsm_monitoring_observation_coordinator CHECK (coordinator_count >= 0),
    CONSTRAINT ck_itsm_monitoring_observation_research CHECK (research_count >= 0)
);

-- The monitoring API reads only the bounded recent window in ascending order.
CREATE INDEX ix_itsm_monitoring_observation_window
    ON itsm_monitoring_observation (observed_at, id);

COMMENT ON TABLE itsm_monitoring_observation IS
    'KAN-62 durable per-publication category counts for the 24-hour monitoring timeline.';
COMMENT ON COLUMN itsm_monitoring_observation.batch_id IS
    'Published batch that produced this observation; a replay cannot create a second point.';
COMMENT ON COLUMN itsm_monitoring_observation.observed_at IS
    'Publication time; ordering uses this timestamp and the identity column.';
COMMENT ON COLUMN itsm_monitoring_observation.critical_count IS
    'Active incidents with priorityId = 4.';
COMMENT ON COLUMN itsm_monitoring_observation.high_count IS
    'Active incidents with priorityId = 3.';
COMMENT ON COLUMN itsm_monitoring_observation.coordinator_count IS
    'Active or Research incidents assigned to the coordinator group.';
COMMENT ON COLUMN itsm_monitoring_observation.research_count IS
    'Incidents with stateEnum = Research.';
