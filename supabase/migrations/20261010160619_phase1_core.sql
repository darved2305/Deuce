-- Phase 1 core (person-3-spec §5.2 + §5.3, D-261010-NP1)
-- matches, sessions, uploads, analysis_runs, analysis_events, artifacts, idempotency
-- RLS in the same migration that creates each table. append_event is the only event writer.

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

-- RLS (same migration), one policy per operation to authenticated; (select auth.uid());
-- writes to uploads/analysis_runs/analysis_events/artifacts go through the admin client only.
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

-- Event append (the only way to write events). Row lock serialises seq; (type, window_seq)
-- dedupe inside the lock returns the existing row on Modal retries; transition table enforced;
-- run state + engine_version + failure_reason updated in the same statement; a Broadcast
-- failure is a warning, never a failure of the append.
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

-- Realtime authorisation: clients may only read broadcast hints on analysis topics they own.
-- Never ALTER TABLE realtime.messages; policies only.
create policy analysis_hint_receive on realtime.messages for select to authenticated
using (
  realtime.messages.extension in ('broadcast')
  and realtime.topic() like 'analysis:%'
  and exists (select 1 from public.analysis_runs r
              where r.id = substring(realtime.topic() from 10) and r.owner_id = (select auth.uid()))
);
