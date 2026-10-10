# Person 3 · API, data, streaming, intelligence, SDK and MCP

Paste this file into your coding agent as the kickoff prompt.

## Mission
Be the spine: one canonical match record that the app, SDK, embed and MCP all read; uploads and
analysis that feel live; the intelligence that turns shot records into honest comparisons backed
by **real clips**; history that works across many sessions.

## Read first
`docs/plan/contracts.md` (all), `docs/plan/03-architecture-and-streaming.md`, `docs/plan/08-developer-platform.md`,
`docs/plan/06-coaching-intelligence.md`, `docs/plan/12-real-clips.md` §4 and §7, `docs/plan/11-debates.md` (D2, D4).

## You own
`supabase/` (Edge Function `api`, migrations, RLS, SQL functions), `services/insights/`
(Python on Modal CPU), `sdk/` (generated), `mcp/`, the `seed_demo_match` command, the only
writer of `analysis_events` and Broadcasts.

## Stack (pin exactly)
Hono **4.13.13** + `@hono/zod-openapi` **1.6.3** on Supabase Edge Functions (Deno) · Supabase
Postgres `ap-south-1`, Auth (anonymous first, identity linking), Realtime Broadcast with private
channels · Cloudflare R2 (private) signed with `aws4fetch` **1.0.20** · OpenAPI **3.0.4** (chosen
for generator compatibility) → OpenAPI Generator **7.26.0** `typescript-fetch` ·
`@modelcontextprotocol/server` **2.3.1** (local stdio first) · optional Workers AI
`@cf/openai/gpt-oss-20b` as a caption service only. If the R2 signing probe fails, solve it on the
Edge Function; there is no second API host.

## How you ship
In slices, straight to `main`, through the pipeline in `docs/plan/10-build-plan.md` §6: small slice → your lane's checks → agent code review → PR → squash-merge on green CI → deployed. Unfinished work hides behind a flag. If you break `main`, fixing it comes first.

## Phase 1 · Foundations
- Deploy Edge Function `api`: `/v1/health`, `/v1/openapi.json`, base
  `{SUPABASE_URL}/functions/v1/api`.
- **R2 multipart probe** with curl and a real 60 MB clip: create → signed `UploadPart` PUTs →
  complete → HEAD. This is the riskiest unknown; prove it first.
- Minimal migrations with RLS (`owner_id = auth.uid()`): `matches, sessions, uploads,
  analysis_runs, analysis_events, artifacts`; SQL function `append_event(analysis_id, payload)`
  assigning `seq` in one transaction; RLS on `realtime.messages` for `analysis:{id}`.
- `StubAnalysis`: replays the hand-labelled **real** fixture's window results and events so Person 2
  integrates before the GPU path exists (never invented values).

## Phase 2 · First real result
Sessions, multipart parts/complete/abort, idempotent `POST /v1/analyses` (unique key + stored
Modal call ID) → Person 1's `submit`; `/internal/analyses/{id}/events|artifacts|finalize` with
`X-Internal-Token`; Broadcast `analysis_seq` after each append; cursor endpoint (`after_seq`
exclusive); `GET /v1/matches/{id}`; `GET /v1/artifacts/{id}` signed URLs; OpenAPI regenerated on
every change.

## Phase 3 · Full loop
- Remaining tables: `rallies, shots, events, reviews, confirmed_points, metrics, insights,
  moments, clips, corrections, share_grants, consents, reference_examples, claims, developer_keys`.
- Persist the vision result from `finalize`; insights job: shot records with canonical orientation
  and roles; hero insight with its gate; similar-moment search exactly as `06` §3.1; role labels
  per `06` §3.2; real clip choice per `12` §4 (rights + consent); then append the single
  `analysis.completed`.
- `POST …/reviews/{rvid}/confirm` (idempotent; actor from the JWT) → `confirmed_points`, score via
  `@elevate/rules`, `point.confirmed` event.
- Comparison endpoint, history endpoints with comparability tiers, moments search with typed filters.
- TS SDK; local MCP (`get_match_summary`, `find_moments`, `get_rally`, `get_capabilities`), links
  `elevate://m/{match}/r/{rally}?t_ms=` plus an https embed fallback; test against the actual
  Claude host, else demo through the MCP inspector.
- `seed_demo_match` from Person 4's seed JSON and media manifest.

## Phase 4 · Depth and extras
Developer keys, embed tokens, share grants, match delete cascade; simulation-runs and
opponent-scout endpoints; optional one-line captions with deterministic fallback; quota headers,
429s and a GPU spend guard; Python SDK if everything above is done.

## Phase 5 · Demo-ready
Budget and concurrency limits on; demo records seeded; API, SDK and MCP read verified.

## Non-negotiables
No video through the API. Only you assign `seq` and send Broadcasts. Immutable analysis runs;
append-only corrections; score only from confirmed points. RLS on every table; consent checked
before any clip crosses accounts. The LLM never computes numbers, never sees video, faces, names
or emails, and the app works without it.
