#!/usr/bin/env bash
set -euo pipefail
umask 077
: "${BACKEND_ENV_FILE:?Set BACKEND_ENV_FILE}"
: "${STAND_KEYCLOAK_URL:?Set STAND_KEYCLOAK_URL to the browser-reachable Keycloak base URL (no trailing slash)}"
: "${STAND_FRONTEND_URL:?Set STAND_FRONTEND_URL to the published frontend origin}"
case "${STAND_ENABLE_ITSM_MOCK:-false}" in
  true|false) ;;
  *) echo 'STAND_ENABLE_ITSM_MOCK must be true or false' >&2; exit 1 ;;
esac
mkdir -p "$(dirname "$BACKEND_ENV_FILE")"
touch "$BACKEND_ENV_FILE"
chmod 600 "$BACKEND_ENV_FILE"

if [[ "${STAND_ENABLE_ITSM_MOCK:-false}" == true ]]; then
  # Do not silently replace configuration for an existing external ITSM.
  mock_setting_is_compatible() {
    local key="$1" expected="$2" existing
    existing="$(grep "^${key}=" "$BACKEND_ENV_FILE" || true)"
    if [[ ( -n "$existing" && "$existing" != "$key=$expected" ) ||
          ( -v "$key" && "${!key}" != "$expected" ) ]]; then
      printf 'Refusing to enable the stand ITSM mock: %s is configured differently.\n' "$key" >&2
      exit 1
    fi
  }

  mock_setting_is_compatible BACKEND_PROFILE demo
  mock_setting_is_compatible DASHBOARD_ITSM_BASE_URL http://itsm-mock:8080
  mock_setting_is_compatible ITSM_MOCK_TARGET_URL http://backend:8080/api/integrations/itsm/batches
  mock_setting_is_compatible ITSM_MOCK_SCHEDULED true
  machine_settings=(
    "ITSM_INTEGRATION_AUTH_ISSUER=${STAND_KEYCLOAK_URL%/}/realms/dashboard"
    'ITSM_INTEGRATION_AUTH_JWK_SET_URI=http://keycloak:8080/realms/dashboard/protocol/openid-connect/certs'
    'ITSM_INTEGRATION_AUTH_AUDIENCE=itsm-ingest'
    'ITSM_INTEGRATION_AUTH_CLIENT_ID=itsm-sender'
    'ITSM_INTEGRATION_AUTH_IMPORT_ROLE=itsm:import'
    'ITSM_MOCK_TOKEN_URL=http://keycloak:8080/realms/dashboard/protocol/openid-connect/token'
    'ITSM_MOCK_CLIENT_ID=itsm-sender'
  )
  for setting in "${machine_settings[@]}"; do
    mock_setting_is_compatible "${setting%%=*}" "${setting#*=}"
  done
  for setting in "${machine_settings[@]}"; do
    if ! grep -q "^${setting%%=*}=" "$BACKEND_ENV_FILE"; then
      printf '\n%s\n' "$setting" >> "$BACKEND_ENV_FILE"
    fi
  done
  if ! grep -q '^DASHBOARD_ITSM_LINK_TEMPLATE=' "$BACKEND_ENV_FILE"; then
    stand_card_url="${STAND_FRONTEND_URL%/}/itsm_incident/{incidentId}/page"
    printf '\nDASHBOARD_ITSM_LINK_TEMPLATE=%s\n' \
      "${DASHBOARD_ITSM_LINK_TEMPLATE:-$stand_card_url}" >> "$BACKEND_ENV_FILE"
  fi
fi

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
if ! grep -q '^STAND_MCP_AGENT_SECRET=' "$BACKEND_ENV_FILE"; then
  printf 'STAND_MCP_AGENT_SECRET=%s\n' "$(openssl rand -hex 32)" >> "$BACKEND_ENV_FILE"
fi

# Read-only MCP Dashboard transport (KAN-86). Fail-closed by default in the application;
# the stand enables it for the machine agent MVP with its dedicated audience/client/role.
mcp_dashboard_settings=(
  "MCP_DASHBOARD_ENABLED=true"
  "MCP_DASHBOARD_CURSOR_SECRET=$(openssl rand -hex 32)"
  'MCP_DASHBOARD_CLIENT_AUDIENCE=mcp-dashboard'
  'MCP_DASHBOARD_CLIENT_ID=mcp-agent'
  'MCP_DASHBOARD_CLIENT_ROLE=mcp:dashboard:read'
)
for setting in "${mcp_dashboard_settings[@]}"; do
  if ! grep -q "^${setting%%=*}=" "$BACKEND_ENV_FILE"; then
    printf '%s\n' "$setting" >> "$BACKEND_ENV_FILE"
  fi
done

# Keep the host directory private while allowing the container UID to read the bind-mounted file.
realm_dir="$(dirname "$BACKEND_ENV_FILE")/keycloak-import"
install -d -m 700 "$realm_dir"
AUTH_REALM_FILE="$realm_dir/dashboard-stand-realm.json"
DASHBOARD_TEST_USERNAME=coordinator \
DASHBOARD_TEST_EMAIL=coordinator@stand.local \
DASHBOARD_TEST_PASSWORD="$(sed -n 's/^STAND_COORDINATOR_PASSWORD=//p' "$BACKEND_ENV_FILE")" \
DASHBOARD_WEB_ORIGIN="${STAND_FRONTEND_URL%/}" \
ITSM_SENDER_CLIENT_SECRET="$(sed -n 's/^STAND_ITSM_SENDER_SECRET=//p' "$BACKEND_ENV_FILE")" \
MCP_AGENT_CLIENT_SECRET="$(sed -n 's/^STAND_MCP_AGENT_SECRET=//p' "$BACKEND_ENV_FILE")" \
python3 scripts/render-realm.py src/keycloak/realm/dashboard-realm.json "$AUTH_REALM_FILE"
chmod 644 "$AUTH_REALM_FILE"

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
