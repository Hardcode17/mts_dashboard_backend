#!/usr/bin/env bash
set -euo pipefail

[[ "${STAND_ENABLE_ITSM_MOCK:-false}" == true ]] || {
  echo 'The stand ITSM mock requires explicit opt-in.' >&2
  exit 1
}
: "${BACKEND_ENV_FILE:?Set BACKEND_ENV_FILE}"
compose=(docker compose --env-file "$BACKEND_ENV_FILE" --profile itsm-mock)
db=("${compose[@]}" exec -T postgres psql -X -qAt -v ON_ERROR_STOP=1 -U dashboard -d dashboard)

# Check the actual, running backend as well as the prepared environment.
[[ "$("${compose[@]}" exec -T backend printenv SPRING_PROFILES_ACTIVE)" == demo ]] || {
  echo 'The stand ITSM mock may only start against the demo backend.' >&2
  exit 1
}

"${compose[@]}" build --pull itsm-mock
# Use the DB server's clock and exclude previously accepted or applied batches.
started_at="$("${db[@]}" -c 'SELECT clock_timestamp()')"
"${compose[@]}" up -d --no-build --no-deps --wait --wait-timeout 120 itsm-mock
binding="$("${compose[@]}" port itsm-mock 8080)"
[[ "$binding" =~ ^127\.0\.0\.1:[0-9]+$ ]] || {
  echo 'Expected a loopback-only published port for the ITSM mock.' >&2
  exit 1
}

for ((attempt = 0; attempt < 36; attempt++)); do
  if source="$(
    curl --noproxy '*' --fail --silent --max-time 5 "http://$binding/api/mock/status" |
      python3 -c '
import json, sys
status = json.load(sys.stdin)
assert status["paused"] is False and status["deliveredVersion"] > 0
source = status["sourceId"]
assert isinstance(source, str) and 0 < len(source) <= 128 and "\n" not in source
print(source)
' 2>/dev/null
  )"; then
    fresh_count="$("${db[@]}" -v source="$source" -v since="$started_at" <<'SQL'
SELECT count(DISTINCT source_version)
FROM itsm_ingest_batch
WHERE source_id = :'source'
  AND received_at >= :'since'::timestamptz
  AND status = 'SUCCEEDED'
  AND processed_at IS NOT NULL;
SQL
    )"
    if [[ "$fresh_count" =~ ^[0-9]+$ ]] && ((fresh_count >= 2)); then
      printf 'ITSM mock is running; backend applied %s fresh snapshots.\n' "$fresh_count"
      scored="$("${db[@]}" -v source="$source" -v since="$started_at" <<'SQL'
SELECT count(*)
FROM deadline_risk AS risk
JOIN itsm_incident AS incident ON incident.id = risk.incident_id
WHERE incident.source_id = :'source'
  AND risk.evaluated_at >= :'since'::timestamptz
  AND risk.score IS NOT NULL;
SQL
      )"
      printf 'Backend calculated numeric regression scores for %s stand incidents.\n' "$scored"
      exit 0
    fi
  fi
  sleep 5
done

echo 'ITSM mock is not ready: expected two fresh SUCCEEDED snapshots after startup.' >&2
exit 1
