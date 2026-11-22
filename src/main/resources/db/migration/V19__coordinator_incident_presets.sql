CREATE TABLE coordinator_incident_presets (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    owner_user_id bigint NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    name varchar(80) NOT NULL CHECK (btrim(name) <> ''),
    queue varchar(16) NOT NULL CHECK (queue IN ('all', 'critical', 'coordinator', 'high', 'research')),
    filters jsonb NOT NULL CHECK (jsonb_typeof(filters) = 'object'),
    CONSTRAINT coordinator_incident_presets_owner_name UNIQUE (owner_user_id, name)
);
