\set ON_ERROR_STOP on
BEGIN;

CREATE TEMP TABLE handover_demo_roster ON COMMIT DROP AS
SELECT subject, login, display_name
FROM jsonb_to_recordset(:'roster'::jsonb) AS entry(subject text, login text, display_name text);

INSERT INTO coordinator_groups (name)
VALUES ('Дежурные координаторы')
ON CONFLICT (name) DO NOTHING;

INSERT INTO users (identity_issuer, identity_subject, login, display_name)
SELECT :'issuer', subject, login, display_name FROM handover_demo_roster
ON CONFLICT (identity_issuer, identity_subject) DO NOTHING;

INSERT INTO coordinator_group_members (coordinator_group_id, user_id)
SELECT g.id, u.id
FROM coordinator_groups g
JOIN handover_demo_roster roster ON true
JOIN users u ON u.identity_issuer = :'issuer' AND u.identity_subject = roster.subject
WHERE g.name = 'Дежурные координаторы'
ON CONFLICT (coordinator_group_id, user_id) DO NOTHING;

COMMIT;
