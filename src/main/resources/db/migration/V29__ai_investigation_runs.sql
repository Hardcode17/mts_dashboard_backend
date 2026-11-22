-- KAN-40: persisted AI investigation runs launched from one incident card.
-- Every run is bound to the published revision it was launched against, so a newer
-- revision can mark an older run as stale without destroying its stored result.
CREATE TABLE ai_investigation_run (
    run_id uuid PRIMARY KEY,
    incident_id varchar(64) NOT NULL,
    revision bigint NOT NULL,
    status text NOT NULL CHECK (status IN ('running', 'completed', 'failed')),
    window_from timestamptz NOT NULL,
    window_to timestamptz NOT NULL,
    model_prompt text NOT NULL,
    hypothesis text,
    next_step text,
    error_code text,
    error_message text,
    created_at timestamptz NOT NULL DEFAULT now(),
    finished_at timestamptz,
    CHECK (window_from < window_to),
    CHECK ((status = 'running') = (finished_at IS NULL)),
    CHECK ((status = 'failed') = (error_code IS NOT NULL)),
    CHECK ((status = 'completed') = (hypothesis IS NOT NULL AND next_step IS NOT NULL
                                     AND error_code IS NULL))
);
CREATE INDEX ai_investigation_run_incident
    ON ai_investigation_run (incident_id, created_at DESC);

-- Facts are the bounded evidence readings gathered at launch; every fact carries the
-- evidenceId of the stand source record it was taken from. A fact never exists without
-- its run, and a run never mixes facts of two incidents or two evidence windows.
CREATE TABLE ai_investigation_fact (
    run_id uuid NOT NULL REFERENCES ai_investigation_run(run_id) ON DELETE CASCADE,
    fact_index int NOT NULL,
    evidence_id varchar(128) NOT NULL,
    source text NOT NULL CHECK (source IN ('ITSM', 'LOG', 'DATABASE')),
    service_key varchar(128) NOT NULL,
    observed_at timestamptz NOT NULL,
    message text,
    message_truncated boolean,
    level varchar(16),
    assigned_command varchar(128),
    record_count bigint,
    value bigint,
    service_state varchar(64),
    PRIMARY KEY (run_id, fact_index)
);

-- A stand source that could not deliver evidence at launch. The gap is stored with the
-- run and is reported separately from the facts; it never becomes an invented fact.
CREATE TABLE ai_investigation_unavailable_source (
    run_id uuid NOT NULL REFERENCES ai_investigation_run(run_id) ON DELETE CASCADE,
    source_index int NOT NULL,
    source text NOT NULL CHECK (source IN ('ITSM', 'LOG', 'DATABASE')),
    service_key varchar(128) NOT NULL,
    code text NOT NULL,
    reason text,
    PRIMARY KEY (run_id, source_index)
);
