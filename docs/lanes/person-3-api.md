# Person 3 API reference and runbook

Snapshot: `origin/main` at `0e328d3` (API code commit `2865c19`) and the
latest deployed OpenAPI fetched 2026-10-11. The main package and resynced vendor are
`@elevate/contracts` `1.10.0`. Contracts remain authoritative:
[contracts.md](../plan/contracts.md). Public route status below is checked against
main and the live document; internal routes are checked against main and omitted
from public OpenAPI by design.

## Base URL, authentication, idempotency, errors

The public base is `{SUPABASE_URL}/functions/v1/api`. The deployed OpenAPI
advertises `https://zpwxjjsugujmihhkmfjx.supabase.co/functions/v1/api`. Append
`/v1/...` to that base. The API is mounted under `/api` in Hono; do not add a
second `/api` segment.

- App requests use `Authorization: Bearer <anonymous-or-user-access-JWT>` and
  `apikey: <Supabase-publishable-key>`. Anonymous JWTs are allowed. Data access
  uses the caller-scoped client and RLS. Never send the Supabase secret key to a
  client.
- `/v1/health` and `/v1/openapi.json` are public at API middleware. Supabase
  gateway requests still use the project's `apikey` header.
- Modal-only `/internal/*` requests use `X-Internal-Token`; handlers use the
  admin client after token verification.
- `X-API-Key: k_…` is live (PR #110). Create and revoke keys with an owner JWT;
  only the SHA-256 hash is stored, and the plaintext is returned only on the
  first create response. A valid key is read-only and authorizes only
  `GET /v1/analyses/{id}`. Other paths or methods return 403 `read_only_key`.
  Keep the gateway `apikey` header for Supabase Function requests.
- Embed-token authentication and its API route are not on main; see blocked
  PR #114 below.

`Idempotency-Key` is required by `POST /v1/sessions`, `POST /v1/analyses`,
`POST /v1/matches/{id}/reviews/{rvid}/confirm`,
`POST /v1/matches/{id}/corrections`, and `POST /v1/developer-keys`. It is 1-255
characters. The record is scoped by key, owner, and endpoint; the canonical
request body is SHA-256 hashed. A same-key/same-body retry replays the saved
response for 24 hours. Reusing a key with a different body returns 409
`idempotency_conflict`. Multipart completion/abort and segment completion are
idempotent without this header. Segment registration reuses an identical
uncompleted manifest, while each issued PUT URL is still subject to the URL
budget. A developer-key replay omits the plaintext key.

Errors use vendored `error.envelope.v1`:
`{ error: { code: string, message: string, details?: unknown }, retry_after_s?: number }`.
When present, `retry_after_s` is also sent as the `Retry-After` header. All
handled failures and uncaught errors use this JSON envelope; uncaught errors
return HTTP 500 with code `internal` (PR #116). Protected routes
can return 401 `unauthorized`; request validation can return 422
`validation_failed`, and malformed JSON can return 422 `invalid_body`. The 429
`analysis_limit_reached` path currently omits `retry_after_s`; budget quota 429s
include it.

## Route catalog

The schema names below are the registry names backed by
`supabase/functions/api/lib/contracts_vendor`. `none` means no body. Where no
named vendored schema exists, that is stated rather than assigning a new
contract name. Statuses include handler behavior; OpenAPI can omit middleware
and runtime error statuses, described in the audit below. A B10 `X-API-Key`
request to anything except `GET /v1/analyses/{id}` also gets 403
`read_only_key`.

### Public routes on main and in the deployed OpenAPI

`LIVE` marks a merged, deployed operation. The document has 18 distinct public
path entries and 19 methods because `GET` and `DELETE /v1/matches/{id}` share
one path. Every public row below except `GET /v1/openapi.json` is listed in the
fetched document; the OpenAPI route is omitted from its own `paths` object.

| Method and path | Request -> response schema | Runtime statuses and access | Status |
|---|---|---|---|
| `GET /v1/health` | none -> `health.response.v1` | 200; public at API middleware | LIVE (PR #11) |
| `GET /v1/openapi.json` | none -> OpenAPI 3.0.4 document; no registry schema | 200; public at API middleware | LIVE (PR #11; omitted from its own paths) |
| `POST /v1/sessions` | `session.create.request.v1` + `Idempotency-Key` -> `session.create.response.v1` | 201; runtime 409, 413, 422, 429, 503. JWT owner; creates multipart or segments-mode session. | LIVE (PR #29; segments mode PR #99) |
| `GET /v1/sessions/{id}` | path `{id}` -> `session.get.response.v1` | 200; runtime 404. Owner JWT via RLS; `upload` is null for segments mode. | LIVE (PR #29; segments mode PR #99) |
| `POST /v1/sessions/{id}/upload/parts` | `upload.parts.request.v1` -> `upload.parts.response.v1` | 200; runtime 404, 409, 422, 429, 503. Owner JWT; multipart PUT URLs expire in 600 seconds. | LIVE (PR #29) |
| `POST /v1/sessions/{id}/upload/complete` | `upload.complete.request.v1` -> `upload.complete.response.v1` | 200; runtime 404, 409, 422, 503. Owner JWT; server completes multipart and checks stored size. | LIVE (PR #29) |
| `POST /v1/sessions/{id}/upload/abort` | none -> `upload.abort.response.v1` | 200; runtime 404, 409, 503. Owner JWT; server aborts multipart. | LIVE (PR #29) |
| `POST /v1/sessions/{id}/segments` | `segment.register.request.v1` -> `segment.register.response.v1` | 200; runtime 404, 409, 413, 422, 429, 503. Owner JWT; contiguous registration, direct R2 PUT URL, budget enforced. | LIVE (PR #99) |
| `POST /v1/sessions/{id}/segments/{seq}/complete` | path `{id, seq}` -> `segment.complete.response.v1` | 200; runtime 404, 409, 503. Owner JWT; server HEAD-checks the segment and returns its manifest. | LIVE (PR #99) |
| `POST /v1/analyses` | `analysis.create.request.v1` + `Idempotency-Key` -> `analysis.create.response.v1` | 200, 202, 403, 429, 503 in OpenAPI; runtime also 404, 409, 422, 500. Owner JWT; segments dispatch after segment 0 completes. | LIVE (PR #28; segments dispatch PR #99) |
| `GET /v1/analyses/{id}` | path `{id}` -> `analysis.get.response.v1` | 200; runtime 404. Owner JWT or owner-scoped read-only `X-API-Key`. | LIVE (PR #28; API keys PR #110) |
| `GET /v1/analyses/{id}/events` | `after_seq` exclusive (default 0), `limit` 1-500 (default 100) -> `analysis.events.page.v1` | 200; runtime 404, 422. Owner JWT via RLS on run and events. | LIVE (PR #28) |
| `GET /v1/artifacts/{id}` | path `{id}` -> no named response schema; implementation returns `{url, expires_at}` | 200, 404, 429, 503. Owner or active match share grant via artifact RLS; download quota applies before signing. | LIVE (PR #29; share RLS test PR #115) |
| `GET /v1/matches/{id}` | path `{id}` -> `match.v1` | 200, 404, 409, 500. User client calls security-invoker `get_match_v1`; owner or active share grant via RLS. | LIVE (PR #103) |
| `DELETE /v1/matches/{id}` | path `{id}` -> none, 202 | 202, 404, 409, 500. Owner JWT; 409 while an analysis is active; rows delete before asynchronous R2 cleanup. | LIVE (PR #112) |
| `POST /v1/matches/{id}/reviews/{rvid}/confirm` | `review.confirm.request.v1` + `Idempotency-Key` -> `confirmed.point.v1` | 200, 409, 422; runtime also 404. Owner JWT; current handler supports singles only; only confirmation changes score. | LIVE (PR #104) |
| `POST /v1/matches/{id}/corrections` | match correction item without server-owned `correction_id`, `actor`, `at` -> correction item from `match.v1.corrections`; no standalone registry name | 201, 404, 409, 422. Owner JWT; append-only, actor resolved from the unique `user_confirmed` player. | LIVE (PR #111) |
| `GET /v1/matches/{mid}/shots/{shot_id}/comparisons` | query `scope=own/shared/reference`, `limit` default 3, capped at 5 -> `comparisons.response.v1` | 200, 404. Match and query shot must be caller-owned; SQL ranks only viewable candidates. | LIVE (PR #109) |
| `POST /v1/developer-keys` | none + `Idempotency-Key` -> no named response schema; key metadata and plaintext on first response only | 201, 401, 403, 409, 422. Owner JWT; admin stores the hash only. | LIVE (PR #110) |
| `DELETE /v1/developer-keys/{id}` | path `{id}` -> none, 204 | 204, 401, 403, 404. Owner JWT; RLS checks ownership before admin revocation. | LIVE (PR #110) |

### Internal routes on main

These use `X-Internal-Token` and the admin client. They are deployed but
intentionally absent from the public OpenAPI document.

| Method and path | Request -> response schema | Runtime statuses | Status |
|---|---|---|---|
| `POST /internal/analyses/{id}/events` | `internal.event.request.v1` -> `analysis.event.v1` | 200; runtime 401, 404, 409, 422 | LIVE (PR #28) |
| `POST /internal/analyses/{id}/artifacts` | `internal.artifact.request.v1` -> `internal.artifact.response.v1` | 200; runtime 401, 404, 422, 503 | LIVE (PR #28) |
| `GET /internal/analyses/{id}/segments` | query `after_seq` default 0 -> `internal.segments.page.v1` | 200; runtime 401, 404, 422; returns only the contiguous completed prefix | LIVE (PR #99) |
| `POST /internal/analyses/{id}/finalize` | `analysis.finalize.request.v1` -> no named response schema; implementation returns `{analysis_id, state, last_seq}` | 200; runtime 401, 404, 409, 422, 500 | LIVE (PR #60) |

### Open routes in PR #132 (B6)

The vendored 1.10.0 contract registry defines the response schemas, but these
routes are not registered in main and do not appear in the live OpenAPI. Their
query behavior follows D-261011-HW16; there is no separate request-body schema.

| Method and path | Query -> response schema | Status |
|---|---|---|
| `GET /v1/capabilities` | none -> `capabilities.v1` | OPEN (PR #132) |
| `GET /v1/matches` | cursor and limit -> `matches.page.v1` | OPEN (PR #132) |
| `GET /v1/matches/{id}/rallies` | cursor and limit -> `rallies.page.v1` | OPEN (PR #132) |
| `GET /v1/matches/{id}/rallies/{rid}` | path `{id, rid}` -> `rally.detail.v1` | OPEN (PR #132) |
| `GET /v1/matches/{id}/moments` | `tag`, cursor and limit -> `moments.page.v1` | OPEN (PR #132) |
| `GET /v1/moments` | `tag`, `match_id`, cursor and limit -> `moments.page.v1` | OPEN (PR #132) |

## Deployed OpenAPI audit

The deployed document is
[the live OpenAPI JSON](https://zpwxjjsugujmihhkmfjx.supabase.co/functions/v1/api/v1/openapi.json).
The fetch returned OpenAPI 3.0.4 with 18 distinct public `paths` entries (19
HTTP methods because `GET` and `DELETE /v1/matches/{id}` share one path). It
includes the live segment, comparison, developer-key, correction, and delete
routes. `servers.url` matches the base above. The document has no component
schemas or security schemes; request and response schemas are inline.
`GET /v1/openapi.json` is not listed in its own paths, and `/internal/*` routes
are omitted by design.

Observed implementation and spec mismatches:

- The current `sessions.ts` handler and deployed document support
  `upload_mode: "segments"` (PR #99). `person-3-spec.md` §6.2 still describes
  segments as unavailable; this reference follows main and the live document.
- `person-3-spec.md` §8 says `GET /v1/matches/{id}` returns the available record
  while analysis is incomplete. Main and the deployed OpenAPI instead return 409
  `conflict` until analysis is completed. This reference records the implemented
  behavior without changing the contract or spec.
- The OpenAPI omits runtime errors on several live routes. For example, session
  create advertises 201/422 but also returns 409/413/429/503; session, analysis,
  and event reads can return 404; upload and confirm routes have additional
  runtime failures. Clients should handle the route errors in the catalog, not
  only the published response list.
- Multipart completion maps invalid ETag/part errors to 409 `conflict`; a
  declared-size mismatch returns 409 `upload_parts_invalid`. Incomplete analysis
  creation returns 409 `upload_not_complete` for both upload modes.
- `analysis_limit_reached` is emitted as 429 without `retry_after_s`, unlike
  budget quota 429s. `invalid_input` and `read_only_key` are local runtime error
  codes in `lib/errors.ts`, not entries in the vendored `knownErrorCodes` map.
- Artifact URL, finalize, and developer-key creation responses have no named
  vendored response schema. The correction item is derived from
  `match.v1.corrections`, with no standalone correction request/response entry.
- PR #116 makes unhandled errors return HTTP 500 with `error.envelope.v1`,
  consistent with the other API errors.

## Analysis lifecycle and dispatch

States in `analysisStateSchema`: `awaiting_upload`, `uploaded`, `queued`,
`processing`, `partial`, `finalizing`, `completed`, `failed`. The `append_event`
transition table is:

```text
uploaded   -> queued | failed
queued     -> processing | partial | failed
processing -> partial | finalizing | failed
partial    -> partial | finalizing | failed
finalizing -> completed | failed
completed, failed -> terminal
```

`analysis.queued` sets `queued`; `analysis.started` sets `processing`; the first
`window.completed` sets `partial` and later windows keep it `partial`;
`analysis.finalizing` sets `finalizing`; `analysis.completed` sets `completed`;
`analysis.failed` sets `failed`. `window.failed` and `point.confirmed` do not
change state. `analysis.partial` is in the enum but is not emitted in V1.
`point.confirmed` may append after terminal completion without changing the
analysis state.

`append_event` locks the run, assigns `seq`, applies the transition and writes
the event in one transaction. Non-confirmation retries deduplicate by
`(type, window_seq)` under that lock. It sends a private Broadcast on
`analysis:{analysis_id}` with event name `analysis_seq` and payload
`{schema_version: 1, analysis_id, latest_seq}`. The Broadcast is only a hint.
`GET /v1/analyses/{id}/events?after_seq=N&limit=100` is authoritative:
`after_seq` is exclusive, the first cursor is 0, events are ordered by `seq`,
and clients resume from the last contiguous sequence and refetch on gaps.
Polling is the fallback if a hint is missed.

There is at most one non-terminal run per session (partial unique index).
`POST /v1/analyses` with a new key while a run is active returns that live run
rather than creating a second. The API caps a session at three analysis runs
total; a further run returns 429 `analysis_limit_reached` once no run is active.

For multipart, dispatch follows completed upload. In segments mode it follows
completion of segment 0. The current `modal.submit.request.v1` body is
`{analysis_id, match_id, session_id, r2_key, session_spec}`; `r2_key` is
`orig/{session_id}/source.mp4` for multipart or `seg/{session_id}/0.mp4` for
segments. The contract keeps `match_id` and `session_id` optional for deploy
ordering, but main dispatch sends both when present. Person 3 calls
`MODAL_SUBMIT_URL` with `Authorization: Bearer <Modal-submit-token>`.

Finalization is `POST /internal/analyses/{id}/finalize` with
`analysis.finalize.request.v1` (`vision_result` without insights plus
`clip_artifact_ids`). It validates the vision result and registered clip/poster
references, then `ingest_vision_result` persists it transactionally, sets
`finalized_at`, appends `analysis.finalizing`, runs deterministic SQL insights,
and appends exactly one `analysis.completed`. Invalid vision output is recorded
as `analysis.failed` with `reason: invalid_vision_result`; insights failure is
recorded as `analysis.failed` with its failure reason.

## Upload and Cloudflare budget

### Multipart on main

`POST /v1/sessions` creates the match/session and a server-side R2 multipart
upload. `part_size` is 8 MiB and `max_concurrency` is 2.
`POST /v1/sessions/{id}/upload/parts` returns short-lived presigned PUT URLs;
the API caps each request at 50 part numbers and validates the range. The app
uploads bytes directly to R2. `POST .../upload/complete` sends part numbers and
ETags to the API; the API completes the multipart upload server-side and
HEAD-checks the stored byte count against `source.bytes`. Repeating completion
after success returns the completed response. Abort is server-side. Video bytes
do not pass through the API.

### Segments live on main (PR #99)

`POST /v1/sessions` accepts `upload_mode: "segments"` and returns
`segments.max_segment_bytes` (300 MiB), `max_concurrency` (2), and `url_ttl_s`
(600). `GET /v1/sessions/{id}` returns `upload: null` because segments are stored
separately. The app registers contiguous sequence numbers, gets a presigned PUT
URL, uploads bytes directly to R2, then calls the completion route so the server
can HEAD-check the segment size. Registration rejects sequence gaps, matching
retries reuse an uncompleted manifest, and `final: true` marks the last segment.
The engine polls the internal cursor for only the contiguous completed prefix;
it never lists R2. Segment 0 completion is the analysis gate;
`POST /v1/analyses` returns 409 `upload_not_complete` until it completes, then
dispatches with segment 0's R2 key. Multipart remains supported.

### G1-G10

See the [Cloudflare budget runbook](../ops/cloudflare-budget.md) and
D-261011-HW2 for limits and operational response.

| Guard | Enforced limit / behavior                                                                                                                                                                     |
| ----- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| G1    | One video including segments is at most 300 MiB; 413 `upload_too_large`                                                                                                                       |
| G2    | Stored originals plus results/clips stay at or below 2 GiB; 429 `storage_budget_reached`                                                                                                      |
| G3    | At most 5 sessions per user per day and 30 uploads per day; 429 `daily_upload_limit`                                                                                                          |
| G4    | Part or segment URL issuance is at most 3 times that upload's part/segment count                                                                                                              |
| G5    | Class A budget is 200,000 operations per month; refuse new uploads at the limit                                                                                                               |
| G6    | Upload URLs expire within 10 minutes; download URLs within 15 minutes; at most 200 download URLs per user per day                                                                             |
| G7    | No `ListObjects` or `ListParts` in request paths or loops                                                                                                                                     |
| G8    | Engine cap per analysis: one window result per window, 2 track files, 1 match vision file, and at most 40 clips (one MP4 up to 3 seconds and one JPG poster per clip); read the original once |
| G9    | `R2_WRITES_ENABLED=false` pauses presign/write paths with 503 `uploads_paused`                                                                                                                |
| G10   | Automatic pause at 1 GiB stored or 100,000 monthly Class A operations; uploads fail closed with 503 `uploads_paused`                                                                          |

## Confirmation, corrections, comparisons, developer keys

- **B4b confirmation (LIVE, PR #104):** only a player confirmation changes
  score. The handler currently supports singles only. It checks match ownership,
  requires one resolved `user_confirmed` player, validates `winner` is a match
  player, and uses the vendored rules package to recompute the contiguous prefix
  of rallies with user-confirmed winners. The JSON `confirmed_by`/`by` field is
  the player ID mapped from the signed-in account;
  `confirmed_points.confirmed_by` stores the auth user UUID for audit. The
  transaction writes confirmed points, review confirmation fields, and match
  score; a new confirmation appends `point.confirmed`. Model estimates never
  change score. Same-key retries replay; conflicting reuse is 409.
- **Corrections (LIVE, PR #111):** append-only. The route requires the match
  owner, an idempotency key, and a uniquely resolved `user_confirmed` player.
  It stores the auth user as audit actor and returns the player ID as the
  correction actor. Engine-produced rows are not overwritten.
- **Comparisons (LIVE, PR #109):** filters exact sport, ruleset, format, phase,
  and camera-quality bucket; excludes the query rally and, for own-history
  evidence, the query match. Distance uses observed features only, with missing
  weights renormalized: focal position 0.35, opponent geometry 0.30, incoming
  source/landing zones 0.20, confirmed shot family 0.10, pressure bucket 0.05. A
  candidate needs at least 70% of total feature weight observed and distance at
  most 0.30. Return at most the top 5, default 3, ordered by distance. Clip
  provenance preference is own, shared, team reference, then public CC.
  `worked_for_you` additionally requires at least 20 comparable decisions, at
  least 5 per action, at least 3 matches, and no more than one decision per
  rally. Otherwise label `similar_moment`. No candidate means an empty list;
  clips must be real and viewable.
- **Developer keys (LIVE, PR #110):** create/revoke require an owner JWT. The
  presented key is stored only as SHA-256; plaintext is returned once.
  `X-API-Key` authorizes only `GET /v1/analyses/{id}` and is read-only; other
  routes return 403 `read_only_key`. The response has no named vendored schema.

## SDK and MCP

The TypeScript SDK and read-only MCP server are in main (PR #113). PR #126 adds
titles and read-only annotations to its first four tools; PR #135 adds four
coach/progress tools, for eight total: `get_match_summary`, `find_moments`,
`get_rally`, `get_capabilities`, `rewatch_picks`, `get_landing_zones`,
`compare_moment`, and `get_analysis_progress`.

`get_match_summary`, `get_rally`, match-scoped `find_moments`, `rewatch_picks`,
and `get_landing_zones` read deployed `match.v1`; `compare_moment` and
`get_analysis_progress` use live route handlers. Cross-match `find_moments` and
build-wide `get_capabilities` still depend on the B6 routes OPEN in PR #132.
This refresh did not run an SDK or MCP request.

## Runbook

### Deploy the API from a clean export

From a clean temporary export of merged main, deploy only after the normal
merge/release approval. Do not deploy from a dirty feature worktree.

```sh
EXPORT_DIR="$(mktemp -d)"
git archive --format=tar origin/main supabase | tar -x -C "$EXPORT_DIR"
supabase functions deploy api --use-api --workdir "$EXPORT_DIR"
```

Use the approved project context and secrets from the operator environment. Do
not put tokens, project secrets, or local user paths in this document or in
proof output.

### Apply remote migrations

After merge, apply pending migration SQL through the approved Supabase SQL
workflow in timestamp order. Confirm each before applying the next. Do not use
`supabase db push` for the remote project. The segments migration is
`20261011152200_segments.sql` (renumbered by PR #131); delete-match safety is
`20261011170000_delete_match_safety.sql`. The repository does not record which
remote migrations have been applied; see #130 for the pending list.

### Mark an eligible stuck run failed

Use the internal event route, not a direct table update. Only do this when the
current state permits the transition; `append_event` rejects terminal or invalid
transitions.

```http
POST {BASE}/internal/analyses/{analysis_id}/events
X-Internal-Token: <INTERNAL_API_TOKEN>
Content-Type: application/json

{"type":"analysis.failed","reason":"<brief factual failure reason>"}
```

The API appends the durable event, records the failure reason and broadcasts the
sequence hint. Never fabricate a reason or edit the run state directly.

### Live proof locations

- `supabase/scripts/r2_probe.ts` is the checked-in real R2 multipart probe; it
  is not an API behavior test.
- `docs/submission/CHECKLIST.md` contains the generic live `/v1/health` curl
  check. `LEDGER.md` records prior Person 3 live proof for merged slices.
- No reusable deployed-API proof runner was found in `supabase/scripts/`.
  Capture the exact curl, SDK, or MCP request/response and produced rows/events
  in the slice's `## Proof`; redact credentials and use real IDs. The API schema
  fetch in this documentation task is not proof of route behavior.

## Known gaps and items to verify

- **B6 read routes: OPEN (PR #132).** The six paths in the table above have
  response schemas in the vendored 1.10.0 contracts and D-261011-HW16, but are
  not registered in main or present in the deployed OpenAPI.
- **B7 player history: BLOCKED (#91).** `GET /v1/players/{id}/history` is absent
  from main and the deployed OpenAPI; see
  [issue #91](https://github.com/darved2305/ggw_elevate/issues/91).
- **Share grants and embed tokens: BLOCKED (PR #114).** The routes
  `POST /v1/matches/{id}/share-grants`,
  `DELETE /v1/matches/{id}/share-grants/{gid}`, and
  `POST /v1/matches/{id}/embed-tokens` are not registered. RLS can honor an
  existing active grant for match and artifact reads, but there is no public
  grant-management or embed-token API on main.
- **Candidate rallies: BLOCKED (PR #117).** This work is not implemented on
  main; no route path is asserted here.
- **Other contract-only Phase 4 routes:** RallyPack export, simulation runs,
  and opponent scout are not registered on main. No PR status is asserted here;
  see [developer-platform plan](../plan/08-developer-platform.md) and
  [contracts section 8](../plan/contracts.md#8-other-endpoints-full-list-in-08-developer-platformmd).
- **Real behavior proof:** this refresh fetched the deployed OpenAPI and
  confirmed its path count only. It did not call protected routes or claim
  end-to-end SDK or MCP behavior.
