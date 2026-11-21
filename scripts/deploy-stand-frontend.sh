#!/usr/bin/env bash
set -euo pipefail

: "${BACKEND_ENV_FILE:?Set BACKEND_ENV_FILE}"
: "${STAND_FRONTEND_REVISION:?Run prepare-stand-sources.sh first}"
: "${STAND_OIDC_AUTHORITY:?Set STAND_OIDC_AUTHORITY}"

compose=(docker compose --env-file "$BACKEND_ENV_FILE" --profile frontend)
"${compose[@]}" build --pull frontend

legacy_ids=$(docker ps -q --filter label=com.docker.compose.project=mts-dashboard-front \
  --filter label=com.docker.compose.service=frontend)
legacy=()
if [[ -n "$legacy_ids" ]]; then mapfile -t legacy <<< "$legacy_ids"; fi

restore_legacy() {
  local result=$?
  if ((result != 0 && ${#legacy[@]} > 0)); then
    "${compose[@]}" stop frontend || true
    docker start "${legacy[@]}" || true
  fi
}
trap restore_legacy EXIT
if ((${#legacy[@]} > 0)); then docker stop "${legacy[@]}"; fi
"${compose[@]}" up -d --no-deps --no-build --wait --wait-timeout 120 frontend

container_id=$("${compose[@]}" ps -q frontend)
image_id=$(docker inspect --format '{{.Image}}' "$container_id")
revision=$(docker image inspect --format '{{index .Config.Labels "org.opencontainers.image.revision"}}' "$image_id")
test "$revision" = "$STAND_FRONTEND_REVISION"

python3 - <<'PY'
import json
import os
from urllib.error import HTTPError
from urllib.parse import urlsplit
from urllib.request import ProxyHandler, build_opener

origin = "http://127.0.0.1:" + os.environ.get("FRONTEND_PORT", "8080")
opener = build_opener(ProxyHandler({}))

def fetch(path):
    with opener.open(origin + path, timeout=10) as response:
        return response.read()

def expect_status(path, expected):
    try:
        fetch(path)
    except HTTPError as error:
        if error.code == expected:
            return
        raise
    raise SystemExit(f"{path} did not return HTTP {expected}")

authority = os.environ["STAND_OIDC_AUTHORITY"]
discovery = json.loads(fetch(urlsplit(authority).path + "/.well-known/openid-configuration"))
if discovery["issuer"] != authority:
    raise SystemExit("Keycloak issuer does not match STAND_OIDC_AUTHORITY")
if json.loads(fetch("/health"))["status"] != "UP":
    raise SystemExit("Backend healthcheck failed through frontend")
if fetch("/auth/callback") != fetch("/"):
    raise SystemExit("OIDC callback does not serve the frontend")
expect_status("/api/dashboard/incidents", 401)
expect_status("/api/stand/snapshot", 404)
print("Unified frontend: backend, OIDC, callback and API protection verified.")
PY
