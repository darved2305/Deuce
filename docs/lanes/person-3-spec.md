# Person 3 spec · API, database, events, insights, SDK, MCP (@nikhilpravinpise)

**For your coding agent: implement this exactly.** Every design decision for this lane is made
here. If something you need is missing or contradicts `docs/plan/contracts.md`, STOP and ask
Person 4 (or write a `DECISIONS.md` entry). Do not invent tables, columns, routes, states, error
codes or behaviour. Do not "improve" the design while implementing it.

Verified against current docs on 2026-10-10 (Supabase, Hono, Cloudflare R2, aws4fetch, MCP SDK v2).
Items marked **VERIFY FIRST** must be smoke-tested before you build on them.

---

## 0. Ground rules for this lane
1. **Nothing synthetic.** No seed rows with invented values; demo data comes only from Person 4's
   real hand-labelled clip or real engine output (`seed_demo_match`, §12).
2. **Proof = real calls against the deployed API** (curl or SDK with real IDs, plus the rows/events
   produced). Tests are regression only.
3. **Single writer:** only this lane writes Postgres, assigns event `seq` and sends Broadcasts.
   Person 1's Modal job talks to you only through `/internal/...`.
4. **Video never passes through the API** except in the R2 fallback in §6.4.
5. **Edge Function limits:** 2 s CPU per request (async I/O excluded), 150 s wall clock, 256 MB. No
   heavy compute in the API; insights run on Modal CPU (§10).
6. Before using any API you have not used yet in this repo: Context7 (`/supabase/supabase`,
   `/honojs/middleware`, `/mhart/aws4fetch`, `/websites/ts_sdk_modelcontextprotocol_io_v2`) or the
   llms.txt files in `supabase/AGENTS.md`.

## 1. What you own
```
supabase/
  config.toml                         # [functions.api] verify_jwt = false
  migrations/<ts>_<name>.sql          # Supabase CLI timestamps; never edit a merged one
  functions/
    .env                              # local only (gitignored)
    api/
      deno.json                       # pinned npm:/jsr: imports
      index.ts                        # Hono app, basePath('/api'), Deno.serve(app.fetch)
      routes/{health,sessions,analyses,matches,reviews,artifacts,internal,developer}.ts
      lib/{auth,db,r2,modal,events,errors,ids,state,rules}.ts
      _test/*_test.ts
    scripts/sync-rules.ts             # copies packages/rules/src -> functions/api/lib/rules_vendor (generated, DO NOT EDIT)
services/insights/                    # Python job on Modal CPU (§10)
sdk/                                  # generated TS client (never hand-edit)
mcp/                                  # local stdio MCP server (§11)
```

## 2. Stack and pins
Hono **4.13.13**, `@hono/zod-openapi` **1.6.3** (needs zod ^4 and hono ≥4.10), zod 4, Deno 2.9.x,
`@supabase/supabase-js` 2.x (pin the exact version you install), `aws4fetch` **1.0.20**,
OpenAPI Generator **7.26.0**, `@modelcontextprotocol/server` **2.3.1**.
All imports in `supabase/functions/api/deno.json` with exact versions (`npm:hono@4.13.13`, …).

## 3. Keys, secrets, environments
- Supabase project in **Mumbai `ap-south-1`**. One dev project (used by `.mcp.json`, read-only for agents) is fine for the hackathon; never point agent tools at anything else.
- Built into Edge Functions: `SUPABASE_URL`, `SUPABASE_PUBLISHABLE_KEYS`, `SUPABASE_SECRET_KEYS`, `SUPABASE_JWKS` (**VERIFY FIRST** their exact format with Context7; legacy `SUPABASE_ANON_KEY` / `SUPABASE_SERVICE_ROLE_KEY` are being retired by end of 2026: do not build on them).
- Custom secrets (`supabase secrets set NAME=value`; names must NOT start with `SUPABASE_`):
  `R2_ACCOUNT_ID`, `R2_ACCESS_KEY_ID`, `R2_SECRET_ACCESS_KEY`, `R2_BUCKET`, `MODAL_SUBMIT_URL`,
  `MODAL_SUBMIT_TOKEN`, `INTERNAL_API_TOKEN`, `EMBED_SIGNING_KEY` (32 random bytes, base64).
- Two DB clients per request:
  - **user client** (publishable key + the caller's JWT in `Authorization`): all app reads and the app's own inserts. RLS applies.
  - **admin client** (secret key): only `/internal/*`, event appends, persistence of engine results, developer-key lookups, embed-token reads, seeding. Never reachable from app routes without an explicit ownership check.

## 4. Auth middleware (lib/auth.ts)
`config.toml`: `[functions.api] verify_jwt = false` because we accept four credential types and
authorise in code. Middleware order:

| Route prefix | Credential | Check | On failure |
|---|---|---|---|
| `/v1/health`, `/v1/openapi.json` | none | — | — |
| `/internal/*` | `X-Internal-Token` | constant-time compare with `INTERNAL_API_TOKEN` | 401 `unauthorized` |
| `/v1/*` with `Authorization: Bearer` | Supabase access JWT | build the user client and call `auth.getUser()` (or `auth.getClaims()` against `SUPABASE_JWKS`; **VERIFY FIRST** which your supabase-js version supports); set `ctx.userId`, `ctx.isAnonymous = jwt.is_anonymous` | 401 `unauthorized` |
| `/v1/*` with `X-API-Key: k_…` (Phase 4) | developer key | sha256(key) lookup in `developer_keys` (admin client), not revoked; read-only routes only | 401 / 403 `read_only_key` |
| `/v1/matches/{id}` with `?embed_token=` (Phase 4) | embed token | HMAC-SHA256 with `EMBED_SIGNING_KEY` over `{match_id, exp}`; exp in the future; match id equal | 401 |

Anonymous users are `authenticated` with `is_anonymous = true`: allowed everywhere in the app;
history survives reinstall only after they link an identity (the app shows this).

## 5. Database (migrations; RLS on every table in the same migration)

### 5.1 IDs
Text primary keys with prefixes from the contracts, generated in SQL:
`'m_' || replace(gen_random_uuid()::text, '-', '')` (same for `s_`, `a_`, `e_`, `art_`, `r_`, `sh_`, `rv_`, `cp_`, `mo_`, `c_`, `cr_`, `k_`).

### 5.2 Phase 1 tables (exact)
```sql
create table public.matches (
  id text primary key default 'm_' || replace(gen_random_uuid()::text,'-',''),
  owner_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
  sport text not null check (sport in ('badminton','table_tennis','tennis','free_play')),
  format text not null check (format in ('singles','doubles','none')),
  ruleset_id text not null, ruleset_version text not null,
  played_at timestamptz, created_at timestamptz not null default now()
);
create table public.sessions (
  id text primary key default 's_' || replace(gen_random_uuid()::text,'-',''),
  match_id text not null references public.matches(id) on delete cascade,
  owner_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
  mode text not null check (mode in ('match','practice','free_play')),
  source jsonb not null, calibration_input jsonb, who_is_me jsonb, free_play_seed jsonb,
  rally_candidates jsonb not null default '[]',
  upload_mode text not null default 'multipart' check (upload_mode in ('multipart','segments')),
  created_at timestamptz not null default now()
);
create table public.uploads (
  session_id text primary key references public.sessions(id) on delete cascade,
  owner_id uuid not null references auth.users(id) on delete cascade,
  r2_key text not null unique, r2_upload_id text not null,
  part_size integer not null default 8388608, bytes bigint not null, sha256 text not null,
  status text not null check (status in ('initiated','completed','aborted')),
  parts jsonb, completed_at timestamptz, created_at timestamptz not null default now()
);
create table public.analysis_runs (
  id text primary key default 'a_' || replace(gen_random_uuid()::text,'-',''),
  session_id text not null references public.sessions(id) on delete cascade,
  match_id text not null references public.matches(id) on delete cascade,
  owner_id uuid not null references auth.users(id) on delete cascade,
  idempotency_key text not null,
  state text not null default 'uploaded' check (state in
    ('awaiting_upload','uploaded','queued','processing','partial','finalizing','completed','failed')),
  engine_version text, modal_call_id text, dispatching boolean not null default false,
  dispatch_attempts integer not null default 0, last_seq integer not null default 0,
  failure_reason text, created_at timestamptz not null default now(), updated_at timestamptz not null default now(),
  unique (session_id, idempotency_key)
);
create table public.analysis_events (
  event_id text primary key default 'e_' || replace(gen_random_uuid()::text,'-',''),
  analysis_id text not null references public.analysis_runs(id) on delete cascade,
  match_id text not null, owner_id uuid not null,
  seq integer not null, schema_version integer not null default 1,
  type text not null check (type in ('analysis.queued','analysis.started','window.completed','window.failed',
    'analysis.partial','analysis.finalizing','analysis.completed','analysis.failed','point.confirmed')),
  window_seq integer, decode_start_us bigint, decode_end_us bigint, emit_start_us bigint, emit_end_us bigint,
  artifact_id text, reason text, engine_version text, created_at timestamptz not null default now(),
  unique (analysis_id, seq)
);
create table public.artifacts (
  id text primary key default 'art_' || replace(gen_random_uuid()::text,'-',''),
  analysis_id text references public.analysis_runs(id) on delete cascade,
  match_id text not null references public.matches(id) on delete cascade,
  owner_id uuid not null references auth.users(id) on delete cascade,
  kind text not null check (kind in ('window_result','ball_track','player_tracks','match_vision','clip','poster')),
  r2_key text not null unique, bytes bigint, sha256 text, created_at timestamptz not null default now()
);
create index on public.sessions(owner_id); create index on public.analysis_runs(owner_id);
create index on public.analysis_events(analysis_id, seq); create index on public.artifacts(match_id);
```
RLS (same migration), pattern for every table:
```sql
alter table public.matches enable row level security;
create policy matches_select on public.matches for select to authenticated using ((select auth.uid()) = owner_id);
create policy matches_insert on public.matches for insert to authenticated with check ((select auth.uid()) = owner_id);
-- sessions: select + insert like matches. uploads, analysis_runs, analysis_events, artifacts:
-- SELECT policy only for the owner; NO insert/update/delete policies (writes go through the admin client).
```
### 5.3 Event append (the only way to write events)
```sql
create or replace function public.append_event(p_analysis_id text, p_type text, p_fields jsonb)
returns public.analysis_events language plpgsql set search_path = '' as $$
declare v_run public.analysis_runs; v_row public.analysis_events;
begin
  update public.analysis_runs set last_seq = last_seq + 1, updated_at = now()
    where id = p_analysis_id returning * into v_run;            -- row lock serialises seq
  if not found then raise exception 'analysis_not_found' using errcode = 'P0002'; end if;
  insert into public.analysis_events (analysis_id, match_id, owner_id, seq, type, window_seq,
    decode_start_us, decode_end_us, emit_start_us, emit_end_us, artifact_id, reason, engine_version)
  values (p_analysis_id, v_run.match_id, v_run.owner_id, v_run.last_seq, p_type,
    (p_fields->>'window_seq')::int, (p_fields->>'decode_start_us')::bigint, (p_fields->>'decode_end_us')::bigint,
    (p_fields->>'emit_start_us')::bigint, (p_fields->>'emit_end_us')::bigint,
    p_fields->>'artifact_id', p_fields->>'reason', p_fields->>'engine_version')
  returning * into v_row;
  begin  -- Broadcast is a hint only; never fail the append because of it
    perform realtime.send(jsonb_build_object('schema_version',1,'analysis_id',p_analysis_id,'latest_seq',v_row.seq),
                          'analysis_seq', 'analysis:' || p_analysis_id, true);
  exception when others then null;
  end;
  return v_row;
end $$;
revoke all on function public.append_event(text,text,jsonb) from public, anon, authenticated;
grant execute on function public.append_event(text,text,jsonb) to service_role;
```
**VERIFY FIRST:** `realtime.send(payload, event, topic, private)` signature with Context7. Supabase
notes the daily partition must exist (a client connected that day); the exception handler keeps
appends safe, and clients always fall back to the cursor.

Realtime authorisation (do NOT `alter table realtime.messages`; only add policies; turn off "Allow
public access" in Realtime settings):
```sql
create policy analysis_hint_receive on realtime.messages for select to authenticated
using (
  realtime.messages.extension in ('broadcast')
  and realtime.topic() like 'analysis:%'
  and exists (select 1 from public.analysis_runs r
              where r.id = substring(realtime.topic() from 10) and r.owner_id = (select auth.uid()))
);
```
### 5.4 Phase 3 tables (columns exactly as in `contracts.md` §7)
`rallies`, `shots` (store `roles`, `incoming`, `chosen`, `context`, `outcome`, `quality` as jsonb
columns, plus flat `player_id`, `contact_ms`, `phase`), `events_vision` (the `match.v1` `events`),
`reviews`, `confirmed_points`, `metrics`, `insights`, `moments`, `clips`, `corrections`,
`share_grants`, `consents`, `reference_examples`, `claims`, `developer_keys`
(`id k_`, `owner_id`, `key_hash`, `scope`, `revoked_at`). Every table: `owner_id`, RLS select for
owner (plus share-grant select for `clips` and `moments`), writes only via admin client except
`corrections` and `confirmed_points` (owner insert with check).
`get_match_v1(p_match_id text) returns jsonb` (`security invoker`, so RLS applies) assembles the
exact `match.v1` shape; the API returns it unchanged.

## 6. Upload (R2 multipart)

### 6.1 Signing (lib/r2.ts)
```ts
import { AwsClient } from "npm:aws4fetch@1.0.20";
const r2 = new AwsClient({ service: "s3", region: "auto",
  accessKeyId: Deno.env.get("R2_ACCESS_KEY_ID")!, secretAccessKey: Deno.env.get("R2_SECRET_ACCESS_KEY")! });
const base = `https://${Deno.env.get("R2_ACCOUNT_ID")}.r2.cloudflarestorage.com/${Deno.env.get("R2_BUCKET")}`;
```
- **Create** (server-side; presigned POST is not supported by R2): `r2.fetch(\`${base}/${key}?uploads\`, { method: "POST" })` → parse `UploadId` from XML.
- **Part URL** (presigned PUT): build `\`${base}/${key}?partNumber=${n}&uploadId=${encodeURIComponent(id)}&X-Amz-Expires=900\``, then `(await r2.sign(new Request(url, { method: "PUT" }), { aws: { signQuery: true } })).url`. Put `partNumber` and `uploadId` in the URL **before** signing. Do not sign `Content-Type`.
- **Complete** (server-side): `POST ${base}/${key}?uploadId=…` with `<CompleteMultipartUpload><Part><PartNumber>n</PartNumber><ETag>"…"</ETag></Part>…</CompleteMultipartUpload>`.
- **Abort**: `DELETE ${base}/${key}?uploadId=…`.
- **VERIFY FIRST (Phase 1 probe):** create → presigned part PUTs with curl (2 parts of 8 MiB) → complete → `HEAD` object size equals the file. Presigned UploadPart on R2 is not in Cloudflare's examples.

### 6.2 Endpoints
| Route | Request | Response | Behaviour |
|---|---|---|---|
| `POST /v1/sessions` | body = contracts §2 session JSON (validated by zod) | 201 `{session_id, match_id, upload:{upload_id, r2_key, part_size, max_concurrency:2}}` | Inserts match + session (user client), creates the R2 multipart upload, inserts `uploads(status='initiated')` (admin). `r2_key = orig/{session_id}/source.mp4`. |
| `POST /v1/sessions/{id}/upload/parts` | `{part_numbers:[1..]}` (≤ 50 per call) | 200 `{parts:[{n, url, expires_at}]}` | Session must belong to caller and upload `initiated`. URLs expire in 900 s; re-requesting the same part is allowed. |
| `POST /v1/sessions/{id}/upload/complete` | `{parts:[{n, etag}]}` | 200 `{status:'completed', bytes}` | Idempotent: if already `completed`, return the same response. Calls R2 Complete; on success `uploads.status='completed'`, `parts` stored. |
| `POST /v1/sessions/{id}/upload/abort` | — | 200 `{status:'aborted'}` | Calls R2 Abort; status `aborted`. |

### 6.3 Validation and edge cases (expected behaviour)
- `source.bytes` > 2 GB (2147483648) → 413 `upload_too_large`. `part_size` fixed at 8 MiB; part count = ceil(bytes / 8 MiB); part numbers outside 1..count → 400 `invalid_part_number`.
- Template must match format (`badminton_singles` with `singles`) → else 422 `calibration_template_mismatch`.
- Duplicate `POST /v1/sessions` with the same `Idempotency-Key` header → return the first response (store key + response hash in a small `idempotency` table for 24 h).
- Complete with a missing or wrong ETag → R2 returns `InvalidPart`/`InvalidPartOrder` → 409 `upload_parts_invalid` with the R2 message; status stays `initiated` (the app re-uploads those parts).
- Complete called twice concurrently → the second sees `completed` (use `update … where status='initiated' returning`) and returns the same body.
- Expired part URL → the app re-requests that part number; never extend expiry beyond 900 s.
- Session not owned by caller → 404 `not_found` (never 403, to avoid leaking existence).
- R2 rate limit 429 (writes to the same key at most 1 per second for Create/Complete) → retry with backoff (250 ms, 500 ms, 1 s), then 503 `storage_busy`.
- Incomplete uploads auto-abort in R2 after 7 days; a scheduled cleanup marks such rows `aborted`.

### 6.4 Fallback if presigned UploadPart fails the probe
Write a decision, then add `PUT /v1/sessions/{id}/upload/parts/{n}` that streams the request body to
R2 with `r2.fetch(partUrl, { method: "PUT", body: c.req.raw.body })` and returns the ETag. 8 MiB
bodies stay within Edge limits (I/O does not count against CPU).

## 7. Analyses (dispatch, state, events)

### 7.1 `POST /v1/analyses` `{session_id}` + required `Idempotency-Key`
1. Session owned by caller; upload `completed` → else 409 `upload_not_complete`.
2. `insert … on conflict (session_id, idempotency_key) do nothing returning *`; if nothing returned, load the existing row and return it (200) without dispatching again.
3. New row: `state='uploaded'`, then claim dispatch atomically:
   `update analysis_runs set dispatching=true, dispatch_attempts=dispatch_attempts+1 where id=$1 and modal_call_id is null and dispatching=false returning *`. If no row returned, another request is dispatching: return current state.
4. Call `POST MODAL_SUBMIT_URL` with `Authorization: Bearer MODAL_SUBMIT_TOKEN`, body
   `{analysis_id, r2_key, session_spec}`, timeout 10 s. Retry on network error/5xx: 0.5 s, 1 s, 2 s.
5. Success → store `modal_call_id`, `dispatching=false`, append `analysis.queued` (state `queued`). Return 202 `{analysis_id, state:'queued'}`.
6. All retries fail → `state='failed'`, `failure_reason='dispatch_failed'`, append `analysis.failed`, return 502 `engine_unavailable`. A new `Idempotency-Key` creates a fresh run.

### 7.2 Allowed state transitions (enforce in lib/state.ts; reject others with 409 `invalid_transition`)
```
uploaded   -> queued | failed
queued     -> processing | failed
processing -> partial | finalizing | failed
partial    -> partial | finalizing | failed
finalizing -> completed | failed
completed, failed -> (terminal)
```
Event type → state: `analysis.started`→processing; first `window.completed`→partial;
`analysis.finalizing`→finalizing; `analysis.completed`→completed; `analysis.failed`→failed.
`window.failed` does not change state.

### 7.3 Internal routes (Modal only, `X-Internal-Token`)
| Route | Body | Behaviour |
|---|---|---|
| `POST /internal/analyses/{id}/events` | `{type, window_seq?, decode_start_us?, decode_end_us?, emit_start_us?, emit_end_us?, artifact_id?, reason?, engine_version}` | Validate type ∈ allowed-from-Modal (`analysis.started`, `window.completed`, `window.failed`, `analysis.finalizing`, `analysis.failed`; never `analysis.completed` or `point.confirmed`); check transition; `rpc('append_event', …)`; update state. Idempotency: if an event with the same `(type, window_seq)` already exists for this analysis, return it instead of appending (Modal retries must not duplicate). |
| `POST /internal/analyses/{id}/artifacts` | `{kind, r2_key, bytes, sha256}` | `r2_key` must start with `analysis/{id}/` or `clips/{match_id}/` → else 422. Insert; return `{artifact_id}`. Same key twice → return existing. |
| `POST /internal/analyses/{id}/finalize` | `{vision_result, clip_artifact_ids}` | zod-validate `vision_result` against `match.v1` minus insights; in ONE transaction insert rallies/shots/events_vision/reviews/metrics/moments/clips; state `finalizing`; spawn the insights job (§10). Second call for the same analysis → 409 `already_finalized`. |

### 7.4 Events cursor `GET /v1/analyses/{id}/events?after_seq=N&limit=100`
`after_seq` exclusive (default 0), `limit` 1..500 (default 100). Returns
`{events:[…ordered by seq], next_after_seq: <max seq returned or N>, has_more, state}`. Owner only.

## 8. Matches, reviews, comparisons

| Route | Behaviour |
|---|---|
| `GET /v1/matches/{id}` | `get_match_v1(id)` via user client (RLS). 404 if not visible. While analysis not `completed`, return what exists with `analysis.state`. |
| `GET /v1/matches/{id}/rallies/{rid}` | Rally with its shots, vision events, reviews, clip artifact ids. |
| `GET /v1/artifacts/{id}` | Ownership (or share grant) check → presigned GET (expires 300 s) → `{url, expires_at}`. |
| `POST /v1/matches/{id}/reviews/{rvid}/confirm` `{winner}` + `Idempotency-Key` | Winner must be a player of the match → else 422. Insert `confirmed_points` (actor = JWT user), recompute the score timeline with the vendored `@elevate/rules` from all confirmed points in rally order, append `point.confirmed` to the match's latest analysis. Same key twice → same response. Never change the score from a model estimate. |
| `POST /v1/matches/{id}/corrections` | Append-only correction rows; never update engine rows. |
| `GET /v1/matches/{mid}/shots/{shot_id}/comparisons?scope=own|shared|reference&limit=3` | §10.3 output, real clips only. |
| `DELETE /v1/matches/{id}` (Phase 4) | Owner only; delete R2 objects under `orig/`, `analysis/`, `clips/` for the match, then the row (cascades). Return 202 and finish asynchronously if slow. |

`@elevate/rules` in Deno: `supabase/functions/scripts/sync-rules.ts` copies `packages/rules/src` into
`api/lib/rules_vendor/` with a `// GENERATED from packages/rules — do not edit` header; CI checks it
is up to date. Your tests run Person 4's golden vectors against the vendored copy.

## 9. Error format and codes
`{ "error": { "code": "<snake_case>", "message": "<human>", "details": {…} } }`. Codes used:
`unauthorized`(401), `not_found`(404), `read_only_key`(403), `validation_failed`(422, zod details),
`calibration_template_mismatch`(422), `invalid_part_number`(400), `upload_too_large`(413),
`upload_not_complete`(409), `upload_parts_invalid`(409), `invalid_transition`(409),
`already_finalized`(409), `idempotency_conflict`(409, same key different body), `storage_busy`(503),
`engine_unavailable`(502), `quota_exhausted`(429 with `retry_after_s`). Use one Hono `defaultHook`
for zod errors; every handler passes an explicit status code (`c.json(body, 200)`).

## 10. Insights job (`services/insights/`, Modal CPU)
Triggered by `finalize` with `{analysis_id}`. Deterministic, no LLM. Steps:
1. Load shots, rallies, events, clips for the match (admin client).
2. **Shot records:** rotate coordinates so the focal player is in the bottom half (`x → W − x`, `y → L − y` when the focal player started far); keep handedness; zones per contracts §9.
3. **Hero insight:** among metrics with tier ≠ unsupported and denominator ≥ 5, pick the one with the largest deviation from 50% or the clearest pattern; caption `"<num> of <den> <what>"`; attach ≥ 1 real moment. If none qualifies, the hero is the longest observed rally clip.
4. **Similar moments (§10.3)** for each shot that lost the point.
5. Write `insights`, `moments`, comparison candidates; then `rpc('append_event', …'analysis.completed'…)` and set `state='completed'`.
6. Any exception → append `analysis.failed` with `reason='insights_failed:<short>'`.

### 10.3 Similar-moment ranking (exact)
- Filters (exact match): sport, ruleset_id, format, phase, camera_quality_grade bucket; exclude the query rally (and the query match when `scope=own` history is used for "worked for you").
- Distance in [0,1] = Σ wᵢ·dᵢ / Σ wᵢ over **observed** features (missing dropped, weights renormalised):
  focal position 0.35 (Euclidean / half-court diagonal); opponent geometry 0.30 (same; doubles use
  the nearest opponent); incoming source+landing zones 0.20 (Manhattan / max grid distance); shot
  family 0.10 (only when both user-confirmed: 0 same, 1 different); pressure bucket 0.05 (0/1).
- Keep candidates with ≥ 70% of total weight observed and distance ≤ 0.30; top 5 by distance.
- Clip choice: candidate's clip must be viewable by the caller (own, shared grant, or `reference_examples`); provenance order own > shared > team_reference > public_cc.
- Role: `worked_for_you` only if ≥ 20 comparable decisions, ≥ 5 per compared action, from ≥ 3 matches, counting ≤ 1 decision per rally; else `similar_moment`. `evidence = {won, of, matches, rallies}`.
- No candidate → empty list (the app shows "No similar moment yet"). Never fabricate.

## 11. SDK and MCP
- OpenAPI: `app.doc('/v1/openapi.json', { openapi: '3.0.4', info: {...}, servers: [{ url: '<SUPABASE_URL>/functions/v1/api' }] })`. Every `createRoute` path includes `/v1/...` under `basePath('/api')`.
- SDK: `npx @openapitools/openapi-generator-cli version-manager set 7.26.0`, then generate `typescript-fetch` into `sdk/` (command in `sdk/AGENTS.md`). Never edit `sdk/`.
- MCP (`mcp/`, stdio): tools (zod `z.object` inputs, read-only, call the deployed API with the developer's own JWT or API key from env):
  - `get_match_summary {match_id}` → score, hero insight caption, rally count, capability tiers, deep link.
  - `find_moments {match_id?, tags?[], player?, outcome?: 'won'|'lost', limit≤20}` → moments with deep links `elevate://m/{match}/r/{rally}?t_ms={t}` and https embed fallback.
  - `get_rally {match_id, rally_id}` → shots, events, review estimates (labelled MODEL ESTIMATE).
  - `get_capabilities {}` → tiers + model descriptors.
  Log only with `console.error`. Test with the real Claude host; if it fails, show the MCP Inspector.

## 12. `seed_demo_match` (Phase 3)
A Deno script (`supabase/functions/scripts/seed_demo_match.ts`, run locally with the secret key)
that loads Person 4's seed JSON (real clip, hand labels or real engine output; `engine_version`
says which) into the tables for a named demo user. Refuses input without `source.sha256` and a
rights field. Never generates values.

## 13. Edge cases you must handle (and how)
| Case | Expected behaviour |
|---|---|
| App retries `POST /v1/analyses` after a timeout | Same `Idempotency-Key` → same run, no second Modal job |
| Modal retries an event post | `(type, window_seq)` dedupe returns the existing event; no new seq |
| Events arrive out of order (window 4 before 3) | Accept: `seq` is arrival order; clients order by `seq`; coverage uses `emit_*` ranges |
| Broadcast fails or partition missing | Append still succeeds; app recovers via cursor polling |
| Client misses hints / reconnects | Cursor `after_seq` = last applied seq; gaps → refetch from last contiguous |
| User deletes match mid-analysis | Cascade deletes rows; internal routes then return 404; Modal stops on 404 |
| Anonymous user reinstalls | Old data unreachable unless they linked an identity; never merge silently |
| Upload completes but analysis never created | Row stays `uploaded`; app shows "ready to analyse" and retries |
| Analysis stuck in `queued`/`processing` | Person 1's health loop reports it; API exposes `updated_at` so the app can show "taking longer than usual" (no fake progress) |
| Request with secret key from the app | Impossible by design: the app has only the publishable key; never accept the secret key from clients |
| Payload too big for Realtime | Broadcast carries only `{schema_version, analysis_id, latest_seq}`; data via REST |
| Zod validation fails on `finalize` | 422 with details; analysis `failed` with `reason='invalid_vision_result'`; Person 1 fixes and re-runs a new analysis |
| Same R2 key reused | Keys contain the session/analysis/clip ID: never reused; `unique` constraints reject |
| Clock skew | Ordering never uses timestamps; only `seq` |

## 14. Never
Never disable RLS or add a policy `using (true)`. Never use the secret key for a user-facing query
without an explicit ownership check. Never put business logic in the client. Never compute
statistics in an LLM. Never write to `sdk/` by hand. Never `console.log` in the stdio MCP server.
Never invent seed data. Never change `docs/plan/contracts.md` without a DECISIONS entry.

## 15. Done checklist per phase (with real proof)
- **Phase 1:** R2 probe passes (paste curl output + HEAD size); `/v1/health` deployed (paste URL + response); migrations applied with RLS (paste `supabase db lint` output); `append_event` returns seq 1, 2, 3 for three calls (paste).
- **Phase 2:** a real phone upload (Person 2) completes; `POST /v1/analyses` dispatches the real Modal job (paste modal_call_id); events arrive in order and the cursor recovers after a missed hint (paste); `GET /v1/matches/{id}` returns `match.v1` that validates against the contract schema.
- **Phase 3:** finalize persists a real engine result; insights complete; a confirm updates the score; a comparison returns a real clip with provenance; TS SDK and MCP read the same `match_id` (paste outputs).
- **Phase 4:** developer key, embed token, share grant, delete cascade each proven with real calls.
