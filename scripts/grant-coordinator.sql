\set ON_ERROR_STOP on
-- Explicit administrative grant after creating the human in Keycloak. Never match by login.
INSERT INTO users (identity_issuer, identity_subject, login, display_name, is_active)
VALUES (:'issuer', :'subject', :'login', :'display_name', true)
ON CONFLICT (identity_issuer, identity_subject)
DO UPDATE SET login = EXCLUDED.login, display_name = EXCLUDED.display_name,
              is_active = true, updated_at = now()
RETURNING id, login, display_name, is_active;
