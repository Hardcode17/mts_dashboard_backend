#!/usr/bin/env bash
set -euo pipefail
umask 077
: "${BACKEND_ENV_FILE:?Set BACKEND_ENV_FILE}"
mkdir -p "$(dirname "$BACKEND_ENV_FILE")"
touch "$BACKEND_ENV_FILE"
chmod 600 "$BACKEND_ENV_FILE"
if ! grep -q '^POSTGRES_PASSWORD=' "$BACKEND_ENV_FILE"; then
  printf '\nPOSTGRES_PASSWORD=%s\n' "$(openssl rand -hex 32)" >> "$BACKEND_ENV_FILE"
fi
# Preserve the password on subsequent deployments: the volume keeps the database.
