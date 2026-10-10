-- Reviews, metrics and insights (person-3-spec §5.4).
create table public.reviews (
  match_id text not null references public.matches(id) on delete cascade,
  id text not null, owner_id uuid not null references auth.users(id) on delete cascade,
  analysis_id text not null, rally_id text, event_id text,
  t_ms integer not null, evidence_frames jsonb not null default '[]',
  contact_xy_m jsonb, cov_m2 jsonb, line_id text, margin_m real, sigma_m real,
  estimate text not null,
  visibility text, model_ids jsonb not null default '[]', calibration_id text,
  confirmed_point_winner text, confirmed_by text, confirmed_at timestamptz,
  primary key (match_id, id),
  constraint reviews_estimate_check check (estimate in ('IN','OUT','TOO_CLOSE','UNOBSERVED'))
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
  kind text not null,
  caption text not null, count jsonb, visual jsonb,
  evidence_moment_ids jsonb not null default '[]',
  gate text not null,
  created_at timestamptz not null default now(),
  constraint insights_kind_check check (kind in ('hero','pattern','summary')),
  constraint insights_gate_check check (gate in ('descriptive','evidence'))
);

create index reviews_owner_id_idx on public.reviews(owner_id);
create index reviews_analysis_id_idx on public.reviews(analysis_id);
create index metrics_owner_id_idx on public.metrics(owner_id);
create index metrics_analysis_id_idx on public.metrics(analysis_id);
create index insights_owner_id_idx on public.insights(owner_id);

alter table public.reviews enable row level security;
create policy reviews_select on public.reviews for select to authenticated
  using (owner_id = (select auth.uid()));
alter table public.metrics enable row level security;
create policy metrics_select on public.metrics for select to authenticated
  using (owner_id = (select auth.uid()));
alter table public.insights enable row level security;
create policy insights_select on public.insights for select to authenticated
  using (owner_id = (select auth.uid()));

revoke all on table public.reviews, public.metrics, public.insights from anon, authenticated;
grant select on public.reviews, public.metrics, public.insights to authenticated;
grant all on public.reviews, public.metrics, public.insights to service_role;
