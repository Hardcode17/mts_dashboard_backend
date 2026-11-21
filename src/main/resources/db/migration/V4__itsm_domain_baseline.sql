-- KAN-17 domain import of validated ITSM snapshots (v5 §6.1 and §8).
--
-- Catalog identities are scoped by their parent: the original snapshot repeats one
-- productId for different product names, and one external serviceId for different
-- products (see itsm_ingest_catalog_hint). A child is therefore unique inside its parent,
-- never globally, and an ambiguous external serviceId never merges catalogs.
--
-- The current baseline is replaced as one unit by the KAN-55 worker transaction: the
-- service deletes and re-inserts every domain row inside its transaction, so readers
-- never observe a partially imported snapshot. Snapshot revision keeps a repeated
-- identical snapshot from writing anything.

-- Latest fully applied snapshot. Exactly zero or one row.
CREATE TABLE itsm_snapshot (
    id BIGINT PRIMARY KEY DEFAULT 1,
    batch_id UUID NOT NULL,
    source_id VARCHAR(128) NOT NULL,
    source_version BIGINT NOT NULL,
    payload_sha256 VARCHAR(64) NOT NULL,
    incident_count INTEGER NOT NULL,
    applied_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT ck_itsm_snapshot_singleton CHECK (id = 1),
    CONSTRAINT ck_itsm_snapshot_source_version CHECK (source_version > 0),
    CONSTRAINT ck_itsm_snapshot_incident_count CHECK (incident_count >= 0)
);

-- Full-product catalog node. parent_key = productName, external_product_id = productId.
CREATE TABLE itsm_catalog_product (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    product_key VARCHAR(300) NOT NULL,
    external_product_id VARCHAR(128),
    product_master_id VARCHAR(128),
    product_url VARCHAR(1024),
    CONSTRAINT uq_itsm_catalog_product_key UNIQUE (product_key)
);

-- Service of one product. parent_key = productKey, external_service_id = serviceId.
CREATE TABLE itsm_catalog_service (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    product_id BIGINT NOT NULL,
    service_key VARCHAR(300) NOT NULL,
    external_service_id VARCHAR(128),
    CONSTRAINT fk_itsm_catalog_service_product FOREIGN KEY (product_id)
        REFERENCES itsm_catalog_product (id),
    CONSTRAINT uq_itsm_catalog_service_parent UNIQUE (product_id, service_key)
);

-- Scenario of one service. parent_key = serviceKey.
CREATE TABLE itsm_catalog_scenario (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    service_id BIGINT NOT NULL,
    scenario_key VARCHAR(300) NOT NULL,
    CONSTRAINT fk_itsm_catalog_scenario_service FOREIGN KEY (service_id)
        REFERENCES itsm_catalog_service (id),
    CONSTRAINT uq_itsm_catalog_scenario_parent UNIQUE (service_id, scenario_key)
);

-- Observed ITSM values that carry an external identity different from the catalog parent
-- key. Identities are recorded per (scope, parent) and never merged across parents.
CREATE TABLE itsm_ingest_catalog_hint (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    identity_scope VARCHAR(32) NOT NULL,
    parent_key VARCHAR(300) NOT NULL,
    external_identity VARCHAR(128) NOT NULL,
    CONSTRAINT ck_itsm_ingest_catalog_hint_scope CHECK (
        identity_scope IN ('PRODUCT_ID', 'SERVICE_ID')
    ),
    CONSTRAINT uq_itsm_ingest_catalog_hint UNIQUE (identity_scope, parent_key, external_identity)
);

-- External ITSM people. Never rows of application accounts.
CREATE TABLE itsm_external_person (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    external_login VARCHAR(128) NOT NULL,
    display_name VARCHAR(300),
    CONSTRAINT uq_itsm_external_person_login UNIQUE (external_login)
);

-- External ITSM support groups.
CREATE TABLE itsm_external_group (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    group_name VARCHAR(300) NOT NULL,
    CONSTRAINT uq_itsm_external_group_name UNIQUE (group_name)
);

-- One current incident per original id. id is the validated ITSM number.
CREATE TABLE itsm_incident (
    id VARCHAR(128) PRIMARY KEY,
    sys_id VARCHAR(128),
    state VARCHAR(32) NOT NULL,
    priority_id INTEGER NOT NULL,
    submit_date TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    last_assignment_to_team_at TIMESTAMP(6) WITH TIME ZONE,
    deadline TIMESTAMP(6) WITH TIME ZONE,
    completion_date TIMESTAMP(6) WITH TIME ZONE,
    waiting_exit_date TIMESTAMP(6) WITH TIME ZONE,
    solution TEXT,
    product_id BIGINT NOT NULL,
    service_id BIGINT,
    scenario_id BIGINT,
    assigned_group_id BIGINT,
    assigned_person_id BIGINT,
    initiator_person_id BIGINT,
    CONSTRAINT ck_itsm_incident_state CHECK (
        state IN ('Assigned', 'WorkInProgress', 'Resolved', 'Closed', 'Research')
    ),
    CONSTRAINT ck_itsm_incident_completion CHECK (
        (state IN ('Assigned', 'WorkInProgress') AND completion_date IS NULL AND solution IS NULL)
        OR (state IN ('Resolved', 'Closed', 'Research') AND completion_date IS NOT NULL
            AND solution IS NOT NULL AND btrim(solution) <> '')
    ),
    CONSTRAINT fk_itsm_incident_product FOREIGN KEY (product_id)
        REFERENCES itsm_catalog_product (id),
    CONSTRAINT fk_itsm_incident_service FOREIGN KEY (service_id)
        REFERENCES itsm_catalog_service (id),
    CONSTRAINT fk_itsm_incident_scenario FOREIGN KEY (scenario_id)
        REFERENCES itsm_catalog_scenario (id),
    CONSTRAINT fk_itsm_incident_group FOREIGN KEY (assigned_group_id)
        REFERENCES itsm_external_group (id),
    CONSTRAINT fk_itsm_incident_assigned_person FOREIGN KEY (assigned_person_id)
        REFERENCES itsm_external_person (id),
    CONSTRAINT fk_itsm_incident_initiator_person FOREIGN KEY (initiator_person_id)
        REFERENCES itsm_external_person (id)
);

CREATE INDEX ix_itsm_incident_product ON itsm_incident (product_id);
CREATE INDEX ix_itsm_incident_service ON itsm_incident (service_id);
CREATE INDEX ix_itsm_incident_assigned_person ON itsm_incident (assigned_person_id);
CREATE INDEX ix_itsm_incident_active ON itsm_incident (state, product_id)
    WHERE state IN ('Assigned', 'WorkInProgress');

-- Original incident attributes retained for downstream consumers.
CREATE TABLE itsm_incident_attribute (
    incident_id VARCHAR(128) NOT NULL,
    attribute_name VARCHAR(128) NOT NULL,
    value_type VARCHAR(16) NOT NULL,
    string_value TEXT,
    number_value NUMERIC,
    boolean_value BOOLEAN,
    CONSTRAINT pk_itsm_incident_attribute PRIMARY KEY (incident_id, attribute_name),
    CONSTRAINT ck_itsm_incident_attribute_type CHECK (
        value_type IN ('STRING', 'NUMBER', 'BOOLEAN', 'NULL')
    ),
    CONSTRAINT ck_itsm_incident_attribute_value CHECK (
        (value_type = 'NULL' AND string_value IS NULL AND number_value IS NULL AND boolean_value IS NULL)
        OR (value_type = 'STRING' AND string_value IS NOT NULL AND number_value IS NULL AND boolean_value IS NULL)
        OR (value_type = 'NUMBER' AND string_value IS NULL AND number_value IS NOT NULL AND boolean_value IS NULL)
        OR (value_type = 'BOOLEAN' AND string_value IS NULL AND number_value IS NULL AND boolean_value IS NOT NULL)
    ),
    CONSTRAINT fk_itsm_incident_attribute_incident FOREIGN KEY (incident_id)
        REFERENCES itsm_incident (id) ON DELETE CASCADE
);

-- Current active external assignment. Locally this is the incident holder, but it is an
-- external person reference and never an application user.
CREATE TABLE itsm_external_assignment (
    incident_id VARCHAR(128) PRIMARY KEY,
    person_id BIGINT NOT NULL,
    group_id BIGINT NOT NULL,
    assigned_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_itsm_external_assignment_incident FOREIGN KEY (incident_id)
        REFERENCES itsm_incident (id) ON DELETE CASCADE,
    CONSTRAINT fk_itsm_external_assignment_person FOREIGN KEY (person_id)
        REFERENCES itsm_external_person (id),
    CONSTRAINT fk_itsm_external_assignment_group FOREIGN KEY (group_id)
        REFERENCES itsm_external_group (id)
);

CREATE INDEX ix_itsm_external_assignment_person ON itsm_external_assignment (person_id);

COMMENT ON TABLE itsm_snapshot IS
    'Latest fully applied validated ITSM snapshot. Written only by the KAN-55 import transaction.';
COMMENT ON TABLE itsm_catalog_product IS
    'Full-product catalog scoped by original productName; productId repeats across names.';
COMMENT ON TABLE itsm_catalog_service IS
    'Service catalog scoped by product; the same serviceId may belong to several products.';
COMMENT ON TABLE itsm_catalog_scenario IS
    'Scenario catalog scoped by service.';
COMMENT ON TABLE itsm_ingest_catalog_hint IS
    'Observed external productId/serviceId values so ambiguous identities are never merged.';
COMMENT ON TABLE itsm_external_person IS
    'External ITSM people; separate from application accounts.';
COMMENT ON TABLE itsm_external_assignment IS
    'Active external assignment of an incident; external person and group, not a local user.';
