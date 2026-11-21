#!/usr/bin/env bash
# Obtains a human access token for KAN-16 from the local Keycloak with Authorization Code + PKCE.
# It never reads a client secret: dashboard-web is a public client. The pre-created test user and
# password must already exist in the realm (compose.auth.yaml imports them from the fixture).
#
# Usage (from the auth runtime file: KEYCLOAK_PUBLIC_URL=http://keycloak.localhost:8084,
# DASHBOARD_TEST_USERNAME, DASHBOARD_TEST_PASSWORD):
#   token=$(sh scripts/auth-stand.sh token)
#   curl -sS -H "Authorization: Bearer $token" http://localhost:8091/api/me
set -euo pipefail

KEYCLOAK_PUBLIC_URL="${KEYCLOAK_PUBLIC_URL:-http://keycloak.localhost:8084}"
REALM="${KEYCLOAK_REALM:-dashboard}"
CLIENT_ID="${DASHBOARD_PUBLIC_CLIENT_ID:-dashboard-web}"
REDIRECT_URI="${DASHBOARD_REDIRECT_URI:-http://keycloak.localhost:3000/callback}"
: "${DASHBOARD_TEST_USERNAME:?Set DASHBOARD_TEST_USERNAME (pre-created in the realm)}"
: "${DASHBOARD_TEST_PASSWORD:?Set DASHBOARD_TEST_PASSWORD}"

for tool in curl openssl python3; do
  command -v "$tool" > /dev/null || { echo "Missing $tool" >&2; exit 1; }
done

BASE="$KEYCLOAK_PUBLIC_URL/realms/$REALM/protocol/openid-connect"
# Keycloak ties the login page to its session cookie (AUTH_SESSION_ID), so the cookie written by
# the GET below must be posted back with the form.
PUBLIC_HOST=${KEYCLOAK_PUBLIC_URL#*://}
PUBLIC_HOST=${PUBLIC_HOST%%[:/]*}
test -n "$PUBLIC_HOST" || { echo "KEYCLOAK_PUBLIC_URL must be an absolute URL" >&2; exit 1; }
COOKIES="$(mktemp)"
trap 'rm -f "$COOKIES"' EXIT

VERIFIER=$(openssl rand -base64 48 | tr '+/' '-_' | tr -d '=')
CHALLENGE=$(printf '%s' "$VERIFIER" | openssl dgst -binary -sha256 | openssl base64 | tr '+/' '-_' | tr -d '=')
STATE=$(openssl rand -hex 16)

AUTH_URL="$BASE/auth?response_type=code&client_id=$CLIENT_ID&redirect_uri=$REDIRECT_URI"\
"&scope=openid%20profile%20email&state=$STATE&code_challenge=$CHALLENGE&code_challenge_method=S256"

LOGIN_URL=$(curl -sS -c "$COOKIES" "$AUTH_URL" \
  | python3 -c 'import html, re, sys; m = re.search(r"action=\"([^\"]+)\"", sys.stdin.read()); print(html.unescape(m.group(1)))')
test -n "$LOGIN_URL" || { echo "Keycloak did not return a login form" >&2; exit 1; }

LOCATION=$(curl -sS -b "$COOKIES" -c "$COOKIES" -o /dev/null -D - -X POST "$LOGIN_URL" \
  --data-urlencode "username=$DASHBOARD_TEST_USERNAME" \
  --data-urlencode "password=$DASHBOARD_TEST_PASSWORD" \
  --data-urlencode "credentialId=" \
  | tr -d '\r' | sed -n 's/^[Ll]ocation: //p')
case "$LOCATION" in
  "$REDIRECT_URI"*) ;;
  *) echo "Login failed (unknown user, wrong password or self-registration disabled): $LOCATION" >&2; exit 1 ;;
esac

CODE=$(python3 -c 'import sys, urllib.parse as u; print(u.parse_qs(u.urlparse(sys.argv[1]).query).get("code", [""])[0])' "$LOCATION")
test -n "$CODE" || { echo "Authorization response has no code" >&2; exit 1; }

curl -sS -X POST "$BASE/token" \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  --data-urlencode "grant_type=authorization_code" \
  --data-urlencode "client_id=$CLIENT_ID" \
  --data-urlencode "code=$CODE" \
  --data-urlencode "redirect_uri=$REDIRECT_URI" \
  --data-urlencode "code_verifier=$VERIFIER" \
  | python3 -c 'import json, sys; body = json.load(sys.stdin); body.get("access_token") or sys.exit("Token error: " + body.get("error", "unknown")); print(body["access_token"])'
