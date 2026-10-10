# Person 3 spec · API, database, events, insights, SDK, MCP (@nikhilpravinpise)

**For your coding agent: implement this exactly.** Every design decision for this lane is made
here. If something you need is missing or contradicts `docs/plan/contracts.md`, STOP and ask
Person 4 (or write a `DECISIONS.md` entry). Do not invent tables, columns, routes, states, error
codes or behaviour. Do not "improve" the design while implementing it.

Verified against current docs on 2026-10-10 (Supabase, Hono, Cloudflare R2, aws4fetch, MCP SDK v2).
Items marked **VERIFY FIRST** must be smoke-tested before you build on them.

Amended by **D-261010-NP1** (2026-10-10): applies `docs/AUDIT-FINDINGS.md` items 17-25 - mount fix,
tested `append_event`, dispatch RPCs + lease, full RLS, data-model additions, `ingest_vision_result`,
insights as SQL (not a Modal job), vendored contracts, `supabase/scripts/`, larger API surface.

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
   heavy compute in the API; insights run as SQL inside `ingest_vision_result` (§10, D-261010-NP1).
6. Before using any API you have not used yet in this repo: Context7 (`/supabase/supabase`,
   `/honojs/middleware`, `/mhart/aws4fetch`, `/websites/ts_sdk_modelcontextprotocol_io_v2`) or the
   llms.txt files in `supabase/AGENTS.md`.

## 1. What you own
```
supabase/
  config.toml                         # [functions.api] verify_jwt = false
  migrations/<ts>_<name>.sql          # Supabase CLI timestamps; never edit a merged one
  tests/<name>_test.sql               # pgTAP, run by `supabase test db`
  functions/
    .env                              # local only (gitignored)
    api/
      deno.json                       # pinned npm:/jsr: imports; fmt/lint exclude *_vendor
      index.ts                        # OpenAPIHono for /v1, mounted on `new Hono().route('/api', api)`; Deno.serve(root.fetch)
      routes/{health,sessions,analyses,matches,reviews,artifacts,internal,developer}.ts
      lib/{auth,db,r2,modal,events,errors,ids,state}.ts
      lib/rules_vendor/               # GENERATED from packages/rules - never edit
      lib/contracts_vendor/           # GENERATED from packages/contracts - never edit
      _test/*_test.ts
  scripts/                            # all Deno scripts live here, not under functions/
    sync-rules.ts                     # packages/rules/src -> functions/api/lib/rules_vendor
    sync-contracts.ts                 # packages/contracts -> functions/api/lib/contracts_vendor
    r2_probe.ts                       # Phase 1 R2 multipart probe
    stub_analysis.ts                  # StubAnalysis: replays the real fixture via /internal
    seed_demo_match.ts                # Phase 3 demo seed (§12)
    gen_sdk.sh                        # regenerates sdk/ from the deployed openapi.json
services/insights/                    # UNUSED in V1: insights are SQL functions (§10, D-261010-NP1)
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
| `/v1/health`, `/v1/openapi.json` | none | - | - |
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
  dispatch_attempts integer not null default 0, dispatch_claimed_at timestamptz,
  last_seq integer not null default 0, finalized_at timestamptz,
  players jsonb, calibration jsonb, capabilities jsonb, models jsonb,   -- set by ingest_vision_result
  failure_reason text, created_at timestamptz not null default now(), updated_at timestamptz not null default now(),
  unique (session_id, idempotency_key)
);
-- at most one non-terminal run per session
create unique index analysis_runs_one_live_per_session
  on public.analysis_runs(session_id) where state not in ('failed','completed');
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
-- replay safety for POST /v1/sessions and other Idempotency-Key mutations (stores the response body)
create table public.idempotency (
  key text not null,
  owner_id uuid not null references auth.users(id) on delete cascade,
  endpoint text not null,
  request_hash text not null,                -- sha256 of the canonical request body
  status_code integer not null default 200,
  response jsonb not null,
  created_at timestamptz not null default now(),
  primary key (key, owner_id, endpoint)
);
create index on public.sessions(owner_id); create index on public.analysis_runs(owner_id);
create index on public.analysis_events(analysis_id, seq); create index on public.artifacts(match_id);
create index on public.idempotency(created_at);   -- rows older than 24 h are ignored
```
RLS (same migration), every policy written in full; explicit grants so PostgREST sees only what
each role may do. `sessions` insert must check the referenced match is owned (audit #22):
```sql
alter table public.matches enable row level security;
create policy matches_select on public.matches for select to authenticated using ((select auth.uid()) = owner_id);
create policy matches_insert on public.matches for insert to authenticated
  with check ((select auth.uid()) = owner_id);

alter table public.sessions enable row level security;
create policy sessions_select on public.sessions for select to authenticated using ((select auth.uid()) = owner_id);
create policy sessions_insert on public.sessions for insert to authenticated
  with check ((select auth.uid()) = owner_id
              and exists (select 1 from public.matches m
                          where m.id = match_id and m.owner_id = (select auth.uid())));

alter table public.uploads enable row level security;
create policy uploads_select on public.uploads for select to authenticated using ((select auth.uid()) = owner_id);

alter table public.analysis_runs enable row level security;
create policy analysis_runs_select on public.analysis_runs for select to authenticated using ((select auth.uid()) = owner_id);

alter table public.analysis_events enable row level security;
create policy analysis_events_select on public.analysis_events for select to authenticated using ((select auth.uid()) = owner_id);

alter table public.artifacts enable row level security;
create policy artifacts_select on public.artifacts for select to authenticated using ((select auth.uid()) = owner_id);

alter table public.idempotency enable row level security;   -- no user policies: service only

grant select on public.matches, public.sessions, public.uploads, public.analysis_runs,
  public.analysis_events, public.artifacts to authenticated;
grant insert on public.matches, public.sessions to authenticated;
grant all on public.matches, public.sessions, public.uploads, public.analysis_runs,
  public.analysis_events, public.artifacts, public.idempotency to service_role;
-- uploads/analysis_runs/analysis_events/artifacts have NO user insert/update/delete:
-- writes go through the admin client or RLS-checked user inserts only. Deletes likewise.
```

### 5.3 Event append (the only way to write events)
The tested version from the audit (D-261010-NP1): row lock, `(type, window_seq)` dedupe inside the
lock so Modal retries return the existing row, the transition table enforced, run state +
`engine_version` + `failure_reason` updated in the same statement, Broadcast failure a warning.
```sql
create or replace function public.append_event(p_analysis_id text, p_type text, p_fields jsonb)
returns public.analysis_events language plpgsql set search_path = '' as $$
declare
  v_run public.analysis_runs; v_row public.analysis_events; v_next text;
  v_ws int := (p_fields->>'window_seq')::int;
begin
  select * into v_run from public.analysis_runs where id = p_analysis_id for update;
  if not found then raise exception 'analysis_not_found' using errcode = 'P0002'; end if;
  if p_type <> 'point.confirmed' then
    select * into v_row from public.analysis_events
      where analysis_id = p_analysis_id and type = p_type and window_seq is not distinct from v_ws;
    if found then return v_row; end if;
  end if;
  v_next := case p_type when 'analysis.queued' then 'queued' when 'analysis.started' then 'processing'
    when 'window.completed' then 'partial' when 'analysis.finalizing' then 'finalizing'
    when 'analysis.completed' then 'completed' when 'analysis.failed' then 'failed' end;
  if v_next is null and v_run.state in ('completed','failed') and p_type <> 'point.confirmed' then
    raise exception 'invalid_transition' using errcode = 'P0001', detail = v_run.state || ' is terminal';
  end if;
  if v_next is not null and not (v_run.state, v_next) in (
      ('uploaded','queued'), ('uploaded','failed'), ('queued','processing'), ('queued','partial'), ('queued','failed'),
      ('processing','partial'), ('processing','finalizing'), ('processing','failed'),
      ('partial','partial'), ('partial','finalizing'), ('partial','failed'),
      ('finalizing','completed'), ('finalizing','failed')) then
    raise exception 'invalid_transition' using errcode = 'P0001', detail = v_run.state || ' -> ' || v_next;
  end if;
  update public.analysis_runs set last_seq = last_seq + 1, updated_at = now(),
         state = coalesce(v_next, state),
         engine_version = coalesce(p_fields->>'engine_version', engine_version),
         failure_reason = case when v_next = 'failed' then p_fields->>'reason' else failure_reason end
   where id = p_analysis_id returning * into v_run;
  insert into public.analysis_events (analysis_id, match_id, owner_id, seq, type, window_seq,
    decode_start_us, decode_end_us, emit_start_us, emit_end_us, artifact_id, reason, engine_version)
  values (p_analysis_id, v_run.match_id, v_run.owner_id, v_run.last_seq, p_type, v_ws,
    (p_fields->>'decode_start_us')::bigint, (p_fields->>'decode_end_us')::bigint,
    (p_fields->>'emit_start_us')::bigint, (p_fields->>'emit_end_us')::bigint,
    p_fields->>'artifact_id', p_fields->>'reason', p_fields->>'engine_version')
  returning * into v_row;
  begin
    perform realtime.send(jsonb_build_object('schema_version',1,'analysis_id',p_analysis_id,'latest_seq',v_row.seq),
                          'analysis_seq', 'analysis:' || p_analysis_id, true);
  exception when others then raise warning 'broadcast_failed: %', sqlerrm;
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
### 5.4 Phase 3 tables (columns exactly as in `contracts.md` §7, D-261010-NP1)
Child IDs supplied by Person 1 are kept verbatim; their primary key is `(match_id, id)`.
`vision_events` is the table for the `match.v1` `events` array (one name everywhere).
```sql
create table public.rallies (
  match_id text not null references public.matches(id) on delete cascade,
  id text not null, owner_id uuid not null references auth.users(id) on delete cascade,
  analysis_id text not null references public.analysis_runs(id) on delete cascade,
  index integer not null, start_ms integer not null, end_ms integer not null,
  shot_ids jsonb not null default '[]', server text, winner text,
  winner_state text check (winner_state in ('observed','candidate','inferred','user')),
  end_reason text, ball_coverage real, clip_id text, state text not null default 'observed',
  primary key (match_id, id)
);
create table public.shots (
  match_id text not null references public.matches(id) on delete cascade,
  id text not null, owner_id uuid not null references auth.users(id) on delete cascade,
  analysis_id text not null, rally_id text not null, index integer not null,
  player_id text not null, contact_ms integer not null, contact_sigma_ms integer,
  phase text not null check (phase in ('serve','return','rally')),
  reference_plane text, coordinate_frame_version integer,
  roles jsonb not null, incoming jsonb not null, chosen jsonb not null,
  flight_time_ms integer, flight_time_state text,
  context jsonb not null, outcome jsonb not null, quality jsonb not null,
  clip_id text,
  primary key (match_id, id)
);
create table public.vision_events (          -- the match.v1 "events" array
  match_id text not null references public.matches(id) on delete cascade,
  id text not null, owner_id uuid not null references auth.users(id) on delete cascade,
  analysis_id text not null, rally_id text, type text not null,
  t_ms integer not null, t_sigma_ms integer, plane text,
  contact_xy_m jsonb, cov_m2 jsonb, cues jsonb not null default '[]',
  state text not null check (state in ('observed','candidate','inferred','user')),
  evidence jsonb not null default '[]',
  primary key (match_id, id)
);
create table public.reviews (
  match_id text not null references public.matches(id) on delete cascade,
  id text not null, owner_id uuid not null references auth.users(id) on delete cascade,
  analysis_id text not null, rally_id text, event_id text,
  t_ms integer not null, evidence_frames jsonb not null default '[]',
  contact_xy_m jsonb, cov_m2 jsonb, line_id text, margin_m real, sigma_m real,
  estimate text not null check (estimate in ('IN','OUT','TOO_CLOSE','UNOBSERVED')),
  visibility text, model_ids jsonb not null default '[]', calibration_id text,
  confirmed_point_winner text, confirmed_by text, confirmed_at timestamptz,
  primary key (match_id, id)
);
create table public.confirmed_points (
  id text primary key default 'cp_' || replace(gen_random_uuid()::text,'-',''),
  match_id text not null references public.matches(id) on delete cascade,
  owner_id uuid not null references auth.users(id) on delete cascade,
  rally_id text not null, winner text not null,
  confirmed_by uuid not null references auth.users(id), review_id text,
  idempotency_key text not null,
  created_at timestamptz not null default now(),
  unique (match_id, idempotency_key)
);
create table public.metrics (
  match_id text not null references public.matches(id) on delete cascade,
  owner_id uuid not null references auth.users(id) on delete cascade,
  analysis_id text not null, key text not null, definition_version integer not null,
  player_id text, numerator integer not null, denominator integer not null,
  unit text not null, tier text not null, source_event_ids jsonb not null default '[]',
  primary key (match_id, key, definition_version, player_id)
);
create table public.insights (
  id text primary key default 'in_' || replace(gen_random_uuid()::text,'-',''),
  match_id text not null references public.matches(id) on delete cascade,
  owner_id uuid not null references auth.users(id) on delete cascade,
  kind text not null check (kind in ('hero','pattern','summary')),
  caption text not null, count jsonb, visual jsonb,
  evidence_moment_ids jsonb not null default '[]',
  gate text not null check (gate in ('descriptive','evidence')),
  created_at timestamptz not null default now()
);
create table public.moments (
  id text primary key default 'mo_' || replace(gen_random_uuid()::text,'-',''),
  match_id text not null references public.matches(id) on delete cascade,
  owner_id uuid not null references auth.users(id) on delete cascade,
  rally_id text, shot_id text, start_ms integer not null, end_ms integer not null,
  contact_ms integer, tags jsonb not null default '[]', clip_id text
);
create table public.clips (
  id text primary key default 'c_' || replace(gen_random_uuid()::text,'-',''),
  match_id text not null references public.matches(id) on delete cascade,
  owner_id uuid not null references auth.users(id) on delete cascade,
  shot_id text, rally_id text,
  source text not null check (source in ('own','shared','team_reference','public_cc')),
  rights text not null,                          -- 'own' | licence string | 'team-consented'
  owner_label text,                              -- contract "owner": the player label shown
  start_ms integer not null, end_ms integer not null, contact_ms integer,
  artifact_id text, poster_artifact_id text, local jsonb, attribution text,
  created_at timestamptz not null default now()
);
create table public.corrections (
  id text primary key default 'cr_' || replace(gen_random_uuid()::text,'-',''),
  match_id text not null references public.matches(id) on delete cascade,
  owner_id uuid not null references auth.users(id) on delete cascade,
  target_type text not null, target_id text not null, field text not null,
  old text, new text, actor uuid not null references auth.users(id), reason text,
  created_at timestamptz not null default now()
);
create table public.share_grants (
  id text primary key default 'sg_' || replace(gen_random_uuid()::text,'-',''),
  match_id text not null references public.matches(id) on delete cascade,
  owner_id uuid not null references auth.users(id) on delete cascade,    -- grantor
  grantee_id uuid not null references auth.users(id) on delete cascade,
  scope text not null check (scope in ('read','compare')),
  revoked_at timestamptz, created_at timestamptz not null default now(),
  unique (match_id, grantee_id, scope)
);
create table public.consents (
  id text primary key default 'cs_' || replace(gen_random_uuid()::text,'-',''),
  match_id text not null references public.matches(id) on delete cascade,
  owner_id uuid not null references auth.users(id) on delete cascade,
  subject text not null,                           -- player_id or 'match'
  scope text not null check (scope in ('clip_compare','clip_embed','reference_library','retain_original')),
  granted boolean not null default true, granted_by uuid not null references auth.users(id),
  note text, revoked_at timestamptz, created_at timestamptz not null default now()
);
create table public.reference_examples (
  id text primary key default 'rx_' || replace(gen_random_uuid()::text,'-',''),
  owner_id uuid not null references auth.users(id) on delete cascade,   -- curator
  kind text not null check (kind in ('team_reference','public_cc')),
  clip_id text references public.clips(id) on delete set null,
  sport text not null, ruleset_id text, format text,
  shot jsonb not null,                             -- the comparable shot record
  rights text not null, attribution text, created_at timestamptz not null default now()
);
create table public.claims (
  id text primary key default 'cl_' || replace(gen_random_uuid()::text,'-',''),
  owner_id uuid not null references auth.users(id) on delete cascade,
  player_id text not null, pattern text not null,
  support_count integer not null default 0, counter_count integer not null default 0,
  first_seen_match_id text references public.matches(id) on delete set null,
  last_seen_match_id text references public.matches(id) on delete set null,
  valid_from date, valid_to date, evidence jsonb not null default '[]',
  created_at timestamptz not null default now(), updated_at timestamptz not null default now()
);
create table public.developer_keys (
  id text primary key default 'k_' || replace(gen_random_uuid()::text,'-',''),
  owner_id uuid not null references auth.users(id) on delete cascade,
  key_hash text not null unique,                   -- sha256 hex of the presented key
  scope text not null default 'read' check (scope in ('read')),
  label text, revoked_at timestamptz, created_at timestamptz not null default now()
);
-- matches also gains: score jsonb (recomputed on every confirm: the contiguous prefix of
-- rallies with a user-confirmed winner, through vendored @elevate/rules).
alter table public.matches add column score jsonb;
```
Every table: `owner_id` + RLS select for owner (plus share-grant select for `clips` and
`moments`: `or exists (select 1 from public.share_grants g where g.match_id = <t>.match_id
and g.grantee_id = (select auth.uid()) and g.scope in ('read','compare') and g.revoked_at is null)`),
writes only via admin client except `corrections` and `confirmed_points` (owner insert `with check`).
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
| `POST /v1/sessions/{id}/upload/abort` | - | 200 `{status:'aborted'}` | Calls R2 Abort; status `aborted`. |

### 6.3 Validation and edge cases (expected behaviour)
- `source.bytes` > 2 GB (2147483648) → 413 `upload_too_large`. `part_size` fixed at 8 MiB; part count = ceil(bytes / 8 MiB); part numbers outside 1..count → 400 `invalid_part_number`.
- Template must match format (`badminton_singles` with `singles`) → else 422 `calibration_template_mismatch`.
- Duplicate `POST /v1/sessions` with the same `Idempotency-Key` header → return the stored first
  response verbatim (`idempotency` table, key + `request_hash` + response body kept 24 h). Same key
  with a different body → 409 `idempotency_conflict`. Missing `Idempotency-Key` → 422
  `validation_failed`.
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

### 7.1 `POST /v1/analyses` `{session_id}` + required `Idempotency-Key` (D-261010-NP1)
1. Session owned by caller (else 404); upload `completed` → else 409 `upload_not_complete`;
   `Idempotency-Key` present → else 422 `validation_failed`.
2. `rpc create_analysis_run(p_session_id, p_idempotency_key)`: `insert … on conflict
   (session_id, idempotency_key) do nothing returning *`; on a partial-unique-index conflict (a
   live run exists under a different key) it loads and returns that run instead. Existing or live
   run → 200 `{analysis_id, state}` without dispatching again.
3. New row → append `analysis.queued` **before** any dispatch attempt (the durable record that a
   GPU job is owed even if the claim/call fails).
4. `rpc claim_dispatch(p_analysis_id)`: atomic
   `update … set dispatching=true, dispatch_attempts=dispatch_attempts+1, dispatch_claimed_at=now()
   where id=$1 and modal_call_id is null and (dispatching=false or dispatch_claimed_at < now() - interval '60 seconds')
   returning *`. No row → another dispatcher is in flight: return current state (still 202; the
   queued event is durable).
5. Call `POST MODAL_SUBMIT_URL` with `Authorization: Bearer MODAL_SUBMIT_TOKEN`, body
   `{analysis_id, r2_key, session_spec}` where `session_spec` is the contracts §2 body assembled by
   the `session_spec(p_session_id)` SQL function from the stored session row, timeout 10 s. Retry
   on network error/5xx only: 0.5 s, 1 s, 2 s. Never retry 4xx.
6. Success → store `modal_call_id`, `dispatching=false`, return 202 `{analysis_id, state:'queued'}`.
7. All retries fail → `state='failed'`, `failure_reason='dispatch_failed'`, append `analysis.failed`,
   return 502 `engine_unavailable`. A fresh `Idempotency-Key` then creates a new run (the partial
   index no longer blocks it).

```sql
create or replace function public.create_analysis_run(p_session_id text, p_idempotency_key text)
returns public.analysis_runs language plpgsql set search_path = '' security definer as $$
declare v_run public.analysis_runs; v_session public.sessions;
begin
  select * into v_session from public.sessions where id = p_session_id;
  if not found then raise exception 'session_not_found' using errcode = 'P0002'; end if;
  insert into public.analysis_runs (session_id, match_id, owner_id, idempotency_key)
    values (p_session_id, v_session.match_id, v_session.owner_id, p_idempotency_key)
    on conflict (session_id, idempotency_key) do nothing returning * into v_run;
  if found then return v_run; end if;
  select * into v_run from public.analysis_runs
    where session_id = p_session_id and idempotency_key = p_idempotency_key;
  if found then return v_run; end if;
  select * into v_run from public.analysis_runs            -- live run under a different key
    where session_id = p_session_id and state not in ('failed','completed');
  if found then return v_run; end if;
  raise exception 'analysis_run_conflict' using errcode = 'P0001';
end $$;

create or replace function public.claim_dispatch(p_analysis_id text)
returns public.analysis_runs language plpgsql set search_path = '' security definer as $$
  update public.analysis_runs
     set dispatching = true, dispatch_attempts = dispatch_attempts + 1,
         dispatch_claimed_at = now(), updated_at = now()
   where id = p_analysis_id and modal_call_id is null
     and (dispatching = false or dispatch_claimed_at < now() - interval '60 seconds')
   returning * $$;

create or replace function public.session_spec(p_session_id text)
returns jsonb language sql stable set search_path = '' as $$
  select jsonb_build_object('sport', s.sport, 'format', s.format, 'mode', s.mode,
    'ruleset', jsonb_build_object('id', s.ruleset_id, 'version', s.ruleset_version),
    'source', s.source, 'calibration_input', s.calibration_input, 'who_is_me', s.who_is_me,
    'free_play_seed', s.free_play_seed, 'rally_candidates', s.rally_candidates,
    'upload_mode', s.upload_mode)
  -- sessions need sport/format/ruleset columns copied from the match for this to be pure SQL;
  -- simplest: read them from the joined match row.
  from (select m.sport, m.format, m.ruleset_id, m.ruleset_version, s.mode, s.source,
               s.calibration_input, s.who_is_me, s.free_play_seed, s.rally_candidates, s.upload_mode
          from public.sessions s join public.matches m on m.id = s.match_id
         where s.id = p_session_id) s $$;
revoke all on function public.create_analysis_run(text,text) from public, anon, authenticated;
revoke all on function public.claim_dispatch(text) from public, anon, authenticated;
revoke all on function public.session_spec(text) from public, anon, authenticated;
grant execute on function public.create_analysis_run(text,text) to service_role;
grant execute on function public.claim_dispatch(text) to service_role;
grant execute on function public.session_spec(text) to service_role;
```

### 7.2 Allowed state transitions (enforced inside `append_event`; reject others with `invalid_transition`)
```
uploaded   -> queued | failed
queued     -> processing | partial | failed
processing -> partial | finalizing | failed
partial    -> partial | finalizing | failed
finalizing -> completed | failed
completed, failed -> (terminal; point.confirmed still appends)
```
Event type → state: `analysis.queued`→queued; `analysis.started`→processing;
`window.completed`→partial (the first one flips it; later windows keep `partial` via the
`partial→partial` row); `analysis.finalizing`→finalizing; `analysis.completed`→completed;
`analysis.failed`→failed. `window.failed` and `point.confirmed` do not change state.
`analysis.partial` stays in the enum but is **not emitted in V1**.

### 7.3 Internal routes (Modal only, `X-Internal-Token`)
| Route | Body | Behaviour |
|---|---|---|
| `POST /internal/analyses/{id}/events` | `{type, window_seq?, decode_window_us?: [us,us], emit_range_us?: [us,us], artifact_id?, reason?, engine_version?}` | Contract-shaped arrays mapped to the four `*_us` columns (audit #18). Type ∈ Modal-allowed only: `analysis.started`, `window.completed`, `window.failed`, `analysis.failed` - never `analysis.queued` (dispatch), `analysis.finalizing` (ingest), `analysis.completed` (insights), `analysis.partial`, or `point.confirmed` → 422 `validation_failed`. `rpc append_event` dedupes `(type, window_seq)` inside the row lock: Modal retries return the existing row, no new `seq`. Unknown run → 404 `not_found` (Modal stops on 404). |
| `POST /internal/analyses/{id}/artifacts` | `{kind, r2_key, bytes, sha256}` | `r2_key` must start with `analysis/{id}/` or `clips/{match_id}/` → else 422. Insert; return `{artifact_id}`. Same key twice → return existing. |
| `POST /internal/analyses/{id}/finalize` | `{vision_result, clip_artifact_ids}` | zod-validate `vision_result` against `match.v1` minus insights → 422 `validation_failed` and mark the run `failed` with `failure_reason='invalid_vision_result'`. Then `rpc ingest_vision_result(p_analysis_id, p_result)`: row lock, `finalized_at` set → 409 `already_finalized`; ONE transaction inserts rallies/shots/vision_events/reviews/metrics/moments/clips plus `players/calibration/capabilities/models` jsonb on the run; applies canonical orientation to stored shot records; appends `analysis.finalizing`; runs `run_insights` (§10) which appends the single `analysis.completed` (or `analysis.failed` with `reason='insights_failed:<short>'`). |

```sql
create or replace function public.ingest_vision_result(p_analysis_id text, p_result jsonb)
returns void language plpgsql set search_path = '' security definer as $$
-- row-lock the run; raise 'already_finalized' when finalized_at is set;
-- insert every child row (ids from p_result kept verbatim, pk (match_id,id));
-- store players/calibration/capabilities/models jsonb on the run; set finalized_at;
-- call append_event('analysis.finalizing'), then run_insights(p_analysis_id).
$$;
revoke all on function public.ingest_vision_result(text,jsonb) from public, anon, authenticated;
grant execute on function public.ingest_vision_result(text,jsonb) to service_role;
```

### 7.4 Events cursor `GET /v1/analyses/{id}/events?after_seq=N&limit=100`
`after_seq` exclusive (default 0), `limit` 1..500 (default 100). Returns
`{events:[…ordered by seq], next_after_seq: <max seq returned or N>, has_more, state}`. Owner only.
Each event is returned in contract shape: `decode_window_us`/`emit_range_us` as `[start,end]`
arrays (the four columns are storage, not wire shape).

## 8. Matches, reviews, comparisons

| Route | Behaviour |
|---|---|
| `GET /v1/capabilities` | Sport × feature tier + status plus model descriptors, served from the vendored `capabilities.json` (Person 4's `RESULTS.md` output; until it lands, the static matrix with every `status:'unknown'`). |
| `GET /v1/matches` | List the caller's matches (cursor pagination; each row: id, sport, format, played_at, latest analysis state, score). |
| `GET /v1/matches/{id}` | `get_match_v1(id)` via user client (RLS). 404 if not visible. While analysis not `completed`, return what exists with `analysis.state`. |
| `GET /v1/matches/{id}/rallies?cursor=` | Rally list for the match (id, start/end, winner, shot count, clip_id). |
| `GET /v1/matches/{id}/rallies/{rid}` | Rally with its shots, vision events, reviews, clip artifact ids. |
| `GET /v1/matches/{id}/moments?filters=` | Moments for one match with typed filters (tags, player, outcome). |
| `GET /v1/moments?filters=` | Cross-match moments for the caller (typed filters; limit ≤ 20; backs `find_moments` in MCP). |
| `GET /v1/analyses/{id}` | `{analysis_id, state, last_seq, engine_version, failure_reason, updated_at}` - feeds the app's honest "taking longer than usual" (no fake progress). |
| `GET /v1/sessions/{id}` | Session + upload status (`initiated`/`completed`/`aborted`, parts stored) so the app can resume an interrupted upload. |
| `GET /v1/players/{id}/history?metric=&range=` | Metric trend across the caller's comparable matches; each point carries its comparability tier (A calibrated / B directional / C qualitative) and engine_version. |
| `GET /v1/artifacts/{id}` | Ownership (or share grant) check → presigned GET (expires 300 s) → `{url, expires_at}`. |
| `POST /v1/matches/{id}/reviews/{rvid}/confirm` `{winner}` + `Idempotency-Key` | Winner must be a player of the match → else 422. Insert `confirmed_points` (actor = JWT user), recompute `matches.score` as the contiguous prefix of rallies with a user-confirmed winner via the vendored `@elevate/rules`, append `point.confirmed` to the match's latest analysis. Same key twice → same response. Never change the score from a model estimate. |
| `POST /v1/matches/{id}/corrections` | Append-only correction rows; never update engine rows. |
| `GET /v1/matches/{mid}/shots/{shot_id}/comparisons?scope=own|shared|reference&limit=3` | §10.3 output via `compare_shot`, real clips only. |
| `POST /v1/matches/{id}/share-grants` · `DELETE /v1/matches/{id}/share-grants/{gid}` (Phase 4) | Owner grants another user `read`/`compare` on this match's clips+moments; revoke sets `revoked_at` (policies close reads immediately). |
| `POST /v1/matches/{id}/embed-tokens` (Phase 4) | Owner → HMAC-SHA256(`EMBED_SIGNING_KEY`) over `{match_id, exp}`; read-only, match-scoped. |
| `POST /v1/developer-keys` · `DELETE /v1/developer-keys/{id}` (Phase 3) | Owner JWT; returns the plaintext `k_…` once, stores sha256; `X-API-Key` auth on read routes only → mutations 403 `read_only_key`. |
| `GET /v1/export/{match_id}.rally` (Phase 4) | RallyPack: zip with `manifest.json` (format version, hashes, consent scope, clip signed-URL references) + `match.json`. No video bytes through the Edge Function. |
| `DELETE /v1/matches/{id}` (Phase 4) | Owner only; delete R2 objects under `orig/`, `analysis/`, `clips/` for the match, then the row (cascades). Return 202 and finish asynchronously if slow. |

`@elevate/rules` in Deno: `supabase/scripts/sync-rules.ts` copies `packages/rules/src` into
`api/lib/rules_vendor/` with a `// GENERATED from packages/rules - do not edit` header; CI checks it
is up to date. `supabase/scripts/sync-contracts.ts` does the same for `packages/contracts` zod into
`api/lib/contracts_vendor/` (audit #23). The vendored files must stay zero-dependency with explicit
`.ts` import specifiers so Deno runs them unbundled (Person 4 keeps `packages/rules` that way).
Your tests run Person 4's golden vectors against the vendored copies.

## 9. Error format and codes
`{ "error": { "code": "<snake_case>", "message": "<human>", "details": {…} } }`. Codes used:
`unauthorized`(401), `not_found`(404), `read_only_key`(403), `validation_failed`(422, zod details),
`calibration_template_mismatch`(422), `invalid_part_number`(400), `upload_too_large`(413),
`upload_not_complete`(409), `upload_parts_invalid`(409), `invalid_transition`(409),
`already_finalized`(409), `idempotency_conflict`(409, same key different body), `storage_busy`(503),
`engine_unavailable`(502), `quota_exhausted`(429 with `retry_after_s`). Use one Hono `defaultHook`
for zod errors; every handler passes an explicit status code (`c.json(body, 200)`).

## 10. Insights: deterministic SQL inside `ingest_vision_result` (D-261010-NP1)
No Python service in V1 (only Person 1 deploys Modal; a second trigger path would need new
secrets for a deterministic job SQL does in the same transaction). `run_insights(p_analysis_id)`
is called by `ingest_vision_result` after `analysis.finalizing`:
1. **Canonical orientation** is applied while storing shot records in `ingest_vision_result`:
   rotate `x → W − x`, `y → L − y` when the focal player started on the far half (W/L from the
   calibration court template's dimensions); never mirror; handedness kept; zones per contracts §9.
2. **Hero insight:** among `metrics` with `tier <> 'unsupported'` and `denominator ≥ 5`, rank by
   `abs(numerator::float / denominator - 0.5)` desc, then `denominator` desc, then `key` asc;
   caption `"<num> of <den> <what>"`; attach ≥ 1 real `moments` id as `evidence_moment_ids`;
   `gate='descriptive'`. If none qualifies, the hero is the longest observed rally clip.
3. **Moments** are written by `ingest_vision_result` from the vision result; `run_insights` only
   tags/links them (insights, hero evidence). Comparison candidates are computed at query time by
   `compare_shot` (§10.3), not materialised.
4. `run_insights` appends exactly one `analysis.completed` and sets state `completed`.
5. Any exception → `append_event('analysis.failed', reason='insights_failed:<short>')`.

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
- OpenAPI: `api.doc('/v1/openapi.json', { openapi: '3.0.4', info: {...}, servers: [{ url: '<SUPABASE_URL>/functions/v1/api' }] })`. Every `createRoute` path is `/v1/...` on an `OpenAPIHono()` app **without** `basePath`; mount with `const root = new Hono().route('/api', api); Deno.serve(root.fetch)` (audit #17: `basePath('/api')` makes the spec emit `/api/v1/...` and generated SDKs call `/api/api/v1`).
- SDK: `npx @openapitools/openapi-generator-cli version-manager set 7.26.0`, then generate `typescript-fetch` into `sdk/` (command in `sdk/AGENTS.md`). Never edit `sdk/`.
- MCP (`mcp/`, stdio): tools (zod `z.object` inputs, read-only, call the deployed API with the developer's own JWT or API key from env):
  - `get_match_summary {match_id}` → score, hero insight caption, rally count, capability tiers, deep link.
  - `find_moments {match_id?, tags?[], player?, outcome?: 'won'|'lost', limit≤20}` → moments with deep links `elevate://m/{match}/r/{rally}?t_ms={t}` and https embed fallback.
  - `get_rally {match_id, rally_id}` → shots, events, review estimates (labelled MODEL ESTIMATE).
  - `get_capabilities {}` → tiers + model descriptors.
  Log only with `console.error`. Test with the real Claude host; if it fails, show the MCP Inspector.

## 12. `seed_demo_match` (Phase 3)
A Deno script (`supabase/scripts/seed_demo_match.ts`, run locally with the secret key)
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
| `POST /v1/analyses` with a new `Idempotency-Key` while a run is live | The partial unique index allows one non-terminal run per session → return the live run (200), never a second GPU job |
| Modal posts `analysis.queued`/`analysis.finalizing`/`analysis.completed`/`point.confirmed` | 422 `validation_failed` - those types belong to dispatch, ingest, insights and confirm respectively |
| `analysis.partial` event | Reserved in the enum, never emitted in V1; first `window.completed` flips state to `partial` |
| Two requests race `claim_dispatch` | One wins the atomic update; the loser returns current state (queued event already durable) |
| Dispatcher dies after claiming | `dispatch_claimed_at` lease (60 s) lets the next request reclaim |

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
