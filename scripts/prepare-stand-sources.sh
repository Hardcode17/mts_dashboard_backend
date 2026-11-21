#!/usr/bin/env bash
set -euo pipefail

: "${GITHUB_ENV:?Set GITHUB_ENV}"
: "${STAND_FRONTEND_URL:?Set STAND_FRONTEND_URL}"
: "${STAND_KEYCLOAK_URL:?Set STAND_KEYCLOAK_URL}"

python3 - <<'PY'
import os
from urllib.parse import urlsplit

frontend = urlsplit(os.environ["STAND_FRONTEND_URL"].rstrip("/"))
keycloak = urlsplit(os.environ["STAND_KEYCLOAK_URL"].rstrip("/"))
if (frontend.scheme != "https" or not frontend.hostname or frontend.username is not None
        or frontend.path or frontend.query or frontend.fragment):
    raise SystemExit("STAND_FRONTEND_URL must be the public HTTPS origin")
if (keycloak.scheme != frontend.scheme or keycloak.netloc != frontend.netloc
        or keycloak.path not in ("/auth", "/keycloak") or keycloak.query or keycloak.fragment):
    raise SystemExit("STAND_KEYCLOAK_URL must be the same HTTPS origin with /auth or /keycloak")
PY

frontend_dir="$PWD/.stand-sources/frontend"
test -f "$frontend_dir/Dockerfile"
printf 'STAND_FRONTEND_CONTEXT=%s\nSTAND_FRONTEND_REVISION=%s\nSTAND_OIDC_AUTHORITY=%s/realms/dashboard\n' \
  "$frontend_dir" "$(git -C "$frontend_dir" rev-parse HEAD)" "${STAND_KEYCLOAK_URL%/}" >> "$GITHUB_ENV"

if [[ "${STAND_ENABLE_ITSM_MOCK:-false}" == true ]]; then
  fixture=".stand-sources/data/Предоставленные материалы/character_problems.json"
  fixture_dir="$PWD/.stand-sources/fixtures"
  mkdir -p "$fixture_dir"
  install -m 644 "$fixture" "$fixture_dir/character_problems.json"
  printf 'STAND_FIXTURE_DIR=%s\nITSM_MOCK_FIXTURE_FILE=/fixtures/character_problems.json\nSTAND_ITSM_PAGE_URL=http://itsm-mock:8080\n' \
    "$fixture_dir" >> "$GITHUB_ENV"
fi
