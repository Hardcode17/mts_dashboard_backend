ALTER TABLE itsm_incident
    ADD COLUMN links_data jsonb NOT NULL DEFAULT '{}',
    ADD CONSTRAINT chk_links_data_object CHECK (jsonb_typeof(links_data) = 'object');
