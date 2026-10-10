-- Engine result tables (person-3-spec §5.4). Child IDs remain verbatim.
create table public.rallies (
  match_id text not null references public.matches(id) on delete cascade,
  id text not null, owner_id uuid not null references auth.users(id) on delete cascade,
  analysis_id text not null references public.analysis_runs(id) on delete cascade,
  index integer not null, start_ms integer not null, end_ms integer not null,
  shot_ids jsonb not null default '[]', server text, winner text,
  winner_state text,
  end_reason text, ball_coverage real, clip_id text, state text not null default 'observed',
  primary key (match_id, id),
  constraint rallies_winner_state_check
    check (winner_state in ('observed','candidate','inferred','user'))
);

create table public.shots (
  match_id text not null references public.matches(id) on delete cascade,
  id text not null, owner_id uuid not null references auth.users(id) on delete cascade,
  analysis_id text not null, rally_id text not null, index integer not null,
  player_id text not null, contact_ms integer not null, contact_sigma_ms integer,
  phase text not null,
  reference_plane text, coordinate_frame_version integer,
  roles jsonb not null, incoming jsonb not null, chosen jsonb not null,
  flight_time_ms integer, flight_time_state text,
  context jsonb not null, outcome jsonb not null, quality jsonb not null,
  clip_id text,
  primary key (match_id, id),
  constraint shots_phase_check check (phase in ('serve','return','rally'))
);

create table public.vision_events (
  match_id text not null references public.matches(id) on delete cascade,
  id text not null, owner_id uuid not null references auth.users(id) on delete cascade,
  analysis_id text not null, rally_id text, type text not null,
  t_ms integer not null, t_sigma_ms integer, plane text,
  contact_xy_m jsonb, cov_m2 jsonb, cues jsonb not null default '[]',
  state text not null,
  evidence jsonb not null default '[]',
  primary key (match_id, id),
  constraint vision_events_state_check check (state in ('observed','candidate','inferred','user'))
);

create index rallies_owner_id_idx on public.rallies(owner_id);
create index rallies_analysis_id_idx on public.rallies(analysis_id);
create index shots_owner_id_idx on public.shots(owner_id);
create index shots_analysis_id_idx on public.shots(analysis_id);
create index vision_events_owner_id_idx on public.vision_events(owner_id);
create index vision_events_analysis_id_idx on public.vision_events(analysis_id);

alter table public.rallies enable row level security;
create policy rallies_select on public.rallies for select to authenticated
  using (owner_id = (select auth.uid()));
alter table public.shots enable row level security;
create policy shots_select on public.shots for select to authenticated
  using (owner_id = (select auth.uid()));
alter table public.vision_events enable row level security;
create policy vision_events_select on public.vision_events for select to authenticated
  using (owner_id = (select auth.uid()));

revoke all on table public.rallies, public.shots, public.vision_events from anon, authenticated;
grant select on public.rallies, public.shots, public.vision_events to authenticated;
grant all on public.rallies, public.shots, public.vision_events to service_role;
