# ITSM snapshot intake (KAN-27)

This service accepts raw full ITSM snapshots over HTTP, stores them durably in
PostgreSQL, and exposes operational state. It does **not** validate individual
incident records (KAN-21) or process snapshots (KAN-54/55). No worker runs in this
service.

## HTTP contract

### `POST /api/integrations/itsm/batches`

Consumes `application/json`. The request body is a bare JSON array, including `[]`.
The complete request body is stored byte-for-byte; record fields are not rewritten,
normalized, or validated here.

Required headers (KAN-53):

| Header | Rule |
| --- | --- |
| `Idempotency-Key` | Canonical UUID. Used as the public `batchId`. |
| `X-Source-Id` | Stable, non-empty ID of 1-128 visible ASCII characters. |
| `X-Source-Version` | Positive signed 64-bit decimal integer (`int64 > 0`, no sign or leading zero). |

The JSON parser is strict: malformed JSON, a non-array top level, and extra trailing
JSON values are rejected with `400` and never stored. An empty array is valid. Per-record
semantics stay with KAN-21.

Successful response:

```http
HTTP/1.1 202 Accepted
Location: /api/integrations/itsm/batches/<batchId>
Content-Type: application/json

{"batchId":"<batchId>"}
```

`Location` is relative. The row is committed before the `202` is returned.

### Idempotency and conflicts

| Situation | Result |
| --- | --- |
| Same key, same source, same version, same bytes | `202` with the original `batchId`; no second row |
| Same key, but different body or source/version | `409 ITSM_IDEMPOTENCY_CONFLICT` |
| Same source and version, but a different key | `409 ITSM_SOURCE_VERSION_CONFLICT` (even if the body is identical) |
| Different version, same body | New distinct observation, `202` |
| Concurrent identical requests | PostgreSQL uniqueness plus `ON CONFLICT DO NOTHING`/read-after-insert returns one row and the original ID |

`Idempotency-Key` is the primary key, and `(source_id, source_version)` is unique. The
intake transaction uses a PostgreSQL advisory transaction lock so the outstanding-queue
check and insert are serialized for the single machine-intake stream.

### `GET /api/integrations/itsm/batches/{batchId}`

Machine-authenticated metadata endpoint. Returns `200` with `batchId`, `sourceId`,
`sourceVersion`, `receivedAt`, `status`, and `diagnostic`. The raw payload is never
exposed. Unknown IDs return `404 ITSM_BATCH_NOT_FOUND`.

### `GET /api/snapshot/status`

Uses the existing authenticated application chain (not the machine chain). Returns:

```json
{
  "lastReceivedAt": "2026-11-21T10:00:00Z",
  "lastSuccessAt": null,
  "revision": null,
  "stale": true
}
```

- `lastReceivedAt` is when intake last committed a batch.
- `lastSuccessAt` is the latest successful worker `processed_at`; it is `null` until
  the worker succeeds.
- `revision` is the worker revision from that same successful row; it is `null` until
  the worker succeeds.
- `stale` is true until the latest accepted batch succeeds, or when no new
  snapshot has arrived within `ITSM_INTAKE_STALE_AFTER` (90 seconds by default).
  HTTP `202` records receipt and leaves application to the worker.

## Machine authentication

The integration routes have a dedicated stateless bearer resource-server chain.
Everything else keeps the existing health/default login chain and its CSRF rules.
CSRF is relaxed only for `/api/integrations/itsm/**`.

The service validates the JWT issuer, JWKS signature, expiry, and configured audience.
It then grants import permission only when all of these are true:

1. `azp` exactly equals the configured machine client ID.
2. The configured import role is present in `realm_access.roles`,
   `resource_access.<client-id>.roles`, or top-level `roles`.

Missing/invalid/expired/wrong-issuer/wrong-audience tokens return `401
ITSM_MACHINE_AUTH_REQUIRED`. A valid human token without the expected machine `azp`
and import permission returns `403 ITSM_MACHINE_ACCESS_DENIED`. There is no fallback
token. If the auth settings are blank, the application still boots and all integration
bearer tokens are rejected (fail closed).

Keycloak is required for a real machine flow. Create a confidential machine client,
set its audience mapper to the configured audience, assign the import role as a realm
role or a client role, and provide a signed JWT. Docker is only required for the
`*IT` Testcontainers checks (PostgreSQL 17). The application itself does not need
Docker.

### Exact configuration

Environment variables (all blank by default, which keeps intake fail-closed):

| Variable | Meaning | Default |
| --- | --- | --- |
| `ITSM_INTEGRATION_AUTH_ISSUER` | Exact JWT `iss` value | blank |
| `ITSM_INTEGRATION_AUTH_JWK_SET_URI` | Keycloak JWKS endpoint | blank |
| `ITSM_INTEGRATION_AUTH_AUDIENCE` | Required JWT audience | blank |
| `ITSM_INTEGRATION_AUTH_CLIENT_ID` | Expected `azp` machine client ID | blank |
| `ITSM_INTEGRATION_AUTH_IMPORT_ROLE` | Role required for import | `itsm:import` |
| `ITSM_INTAKE_MAX_OUTSTANDING_BATCHES` | Maximum rows in `RECEIVED` or `PROCESSING` | `1000` |
| `ITSM_INTAKE_STALE_AFTER` | Time without a new snapshot before reporting stale | `PT90S` |
| `REQUEST_LOGGING_MAX_CACHED_BODY_SIZE` | Existing JSON body limit; larger bodies get `413` | `1MB` |

The body limit is the existing `LoggerFilter` limit, so it also applies before the
controller parses the array. Keep `request-logging.log-request-body` disabled outside
local tests: it can log the first 4 KB of our raw payload.

### Backpressure

The outstanding queue is bounded. A new distinct snapshot is rejected with `429
ITSM_INTAKE_QUEUE_FULL` when the configured number of not-yet-successful rows is
reached. The capacity check runs after duplicate/conflict resolution, so identical
idempotent replays still receive `202` while the queue is full; a same-source/version
replay with a different key still receives `409`.

`RECEIVED` and `PROCESSING` occupy queue capacity. A terminal `FAILED` batch
keeps its diagnostic and releases its slot.

## Worker update contract

KAN-54 writes only these processing fields on the existing row:

| Column | Meaning |
| --- | --- |
| `status` | `RECEIVED`, `PROCESSING`, `SUCCEEDED`, or `FAILED` |
| `status_updated_at` | Timestamp of the last status change |
| `processed_at` | Success timestamp (also `lastSuccessAt`) |
| `processing_revision` | Applied revision (also snapshot `revision`) |
| `diagnostic` | Safe diagnostic summary; never payload bytes or secrets |

The raw bytes remain in `payload` (with `payload_sha256` and `payload_size`). They are
for the worker and are never returned by the HTTP API.

## Database

Flyway migration `V2__itsm_ingest_batch.sql` creates `itsm_ingest_batch`. `V2` is used
because the demo profile already owns `V1`. The table has the key/version uniqueness
constraints, status/received indexes, and processing fields above.

## Verification

Docker-free focused checks (Java 21):

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@21 sh ./gradlew test \
  --tests '*ItsmIntegrationAuthenticationTest' \
  --tests '*ItsmBatchIntakeServiceTest' \
  --tests '*LoggerFilterTest' \
  --tests '*GlobalExceptionHandlerTest'
```

`ItsmBatchIntakeIT` uses Spring Boot MockMvc and a PostgreSQL 17 Testcontainers
(`postgres:17-bookworm`). It covers raw-byte preservation, strict JSON, body limit,
idempotent replay, concurrent replay, header conflicts, bounded queue, metadata, and
snapshot-status transitions. It requires a working Docker daemon:

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@21 sh ./gradlew test --tests '*ItsmBatchIntakeIT'
```
