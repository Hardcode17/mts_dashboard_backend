\set ON_ERROR_STOP on
BEGIN;
INSERT INTO coordinator_groups (name) VALUES ('Дежурные координаторы')
ON CONFLICT (name) DO NOTHING;

INSERT INTO coordinator_group_members (coordinator_group_id, user_id)
SELECT g.id, u.id
FROM coordinator_groups g CROSS JOIN users u
WHERE g.name = 'Дежурные координаторы' AND u.identity_issuer = :'issuer' AND u.identity_subject = :'subject'
ON CONFLICT (coordinator_group_id, user_id) DO NOTHING;
COMMIT;
