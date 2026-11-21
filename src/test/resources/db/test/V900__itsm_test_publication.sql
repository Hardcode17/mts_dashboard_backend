-- Test-only KAN-55 stand-in: the visible normalized state published by TestItsmBatchProcessor
-- inside the worker's claim transaction. Never part of the application schema.
CREATE TABLE itsm_test_publication (
    source_id VARCHAR(128) PRIMARY KEY,
    revision BIGINT NOT NULL
);
