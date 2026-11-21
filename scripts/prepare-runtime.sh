#!/usr/bin/env bash
set -euo pipefail
umask 077
: "${BACKEND_ENV_FILE:?Set BACKEND_ENV_FILE}"
: "${STAND_KEYCLOAK_URL:?Set STAND_KEYCLOAK_URL to the browser-reachable Keycloak origin, e.g. http://192.168.0.10:8084 (no trailing slash)}"
: "${STAND_FRONTEND_URL:?Set STAND_FRONTEND_URL to the published frontend origin, e.g. http://192.168.0.10:8080}"
mkdir -p "$(dirname "$BACKEND_ENV_FILE")"
touch "$BACKEND_ENV_FILE"
chmod 600 "$BACKEND_ENV_FILE"

# Generated once and preserved on subsequent deployments: the volumes keep the database
# and the imported realm.
if ! grep -q '^POSTGRES_PASSWORD=' "$BACKEND_ENV_FILE"; then
  printf '\nPOSTGRES_PASSWORD=%s\n' "$(openssl rand -hex 32)" >> "$BACKEND_ENV_FILE"
fi
if ! grep -q '^KEYCLOAK_ADMIN_USERNAME=' "$BACKEND_ENV_FILE"; then
  printf 'KEYCLOAK_ADMIN_USERNAME=admin\n' >> "$BACKEND_ENV_FILE"
fi
if ! grep -q '^KEYCLOAK_ADMIN_PASSWORD=' "$BACKEND_ENV_FILE"; then
  printf 'KEYCLOAK_ADMIN_PASSWORD=%s\n' "$(openssl rand -hex 32)" >> "$BACKEND_ENV_FILE"
fi
if ! grep -q '^STAND_COORDINATOR_PASSWORD=' "$BACKEND_ENV_FILE"; then
  printf 'STAND_COORDINATOR_PASSWORD=%s\n' "$(openssl rand -hex 32)" >> "$BACKEND_ENV_FILE"
fi
if ! grep -q '^STAND_ITSM_SENDER_SECRET=' "$BACKEND_ENV_FILE"; then
  printf 'STAND_ITSM_SENDER_SECRET=%s\n' "$(openssl rand -hex 32)" >> "$BACKEND_ENV_FILE"
fi

# Keycloak imports the realm once per h2 volume; keep the rendered file next to the env file.
AUTH_REALM_FILE="$(dirname "$BACKEND_ENV_FILE")/dashboard-stand-realm.json"
DASHBOARD_TEST_USERNAME=coordinator \
DASHBOARD_TEST_EMAIL=coordinator@stand.local \
DASHBOARD_TEST_PASSWORD="$(sed -n 's/^STAND_COORDINATOR_PASSWORD=//p' "$BACKEND_ENV_FILE")" \
DASHBOARD_WEB_ORIGIN="${STAND_FRONTEND_URL%/}" \
ITSM_SENDER_CLIENT_SECRET="$(sed -n 's/^STAND_ITSM_SENDER_SECRET=//p' "$BACKEND_ENV_FILE")" \
python3 scripts/render-realm.py src/keycloak/realm/dashboard-realm.json "$AUTH_REALM_FILE"

# The auth settings follow STAND_KEYCLOAK_URL on every deploy: the repository variable is
# the single source of truth for the issuer the browser and the backend must share.
# CORS (KAN-64) follows STAND_FRONTEND_URL the same way, with the local dev origins kept
# for direct browser access to the stand.
sed -i '/^AUTH_REALM_FILE=/d;/^APP_AUTH_HUMAN_/d;/^APP_CORS_ALLOWED_ORIGINS=/d' "$BACKEND_ENV_FILE"
printf 'AUTH_REALM_FILE=%s\n' "$AUTH_REALM_FILE" >> "$BACKEND_ENV_FILE"
printf 'APP_AUTH_HUMAN_ISSUER=%s\n' "${STAND_KEYCLOAK_URL%/}/realms/dashboard" >> "$BACKEND_ENV_FILE"
printf 'APP_AUTH_HUMAN_JWK_SET_URI=http://keycloak:8080/realms/dashboard/protocol/openid-connect/certs\n' >> "$BACKEND_ENV_FILE"
printf 'APP_AUTH_HUMAN_AUDIENCE=dashboard-api\n' >> "$BACKEND_ENV_FILE"
printf 'APP_CORS_ALLOWED_ORIGINS=%s,http://localhost:3000,http://localhost:5173\n' "${STAND_FRONTEND_URL%/}" >> "$BACKEND_ENV_FILE"
