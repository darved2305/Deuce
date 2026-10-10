create table public.share_grants (
  id text primary key default 'sg_' || replace(gen_random_uuid()::text,'-',''),
  match_id text not null references public.matches(id) on delete cascade,
  owner_id uuid not null references auth.users(id) on delete cascade,
  grantee_id uuid not null references auth.users(id) on delete cascade,
  scope text not null,
  revoked_at timestamptz, created_at timestamptz not null default now(),
  unique (match_id, grantee_id, scope),
  constraint share_grants_scope_check check (scope in ('read','compare'))
);

create schema if not exists private;
revoke all on schema private from public, anon;
create or replace function private.has_share_grant(p_match_id text)
returns boolean language sql stable security definer set search_path = ''
as $$ select exists (select 1 from public.share_grants g
      where g.match_id = p_match_id and g.grantee_id = (select auth.uid())
        and g.revoked_at is null and g.scope in ('read','compare')) $$;
revoke all on function private.has_share_grant(text) from public, anon;
grant usage on schema private to authenticated;
grant execute on function private.has_share_grant(text) to authenticated;

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
  source text not null,
  rights text not null,
  owner_label text,
  start_ms integer not null, end_ms integer not null, contact_ms integer,
  artifact_id text, poster_artifact_id text, local jsonb, attribution text,
  created_at timestamptz not null default now(),
  constraint clips_source_check check (source in ('own','shared','team_reference','public_cc'))
);

create table public.reference_examples (
  id text primary key default 'rx_' || replace(gen_random_uuid()::text,'-',''),
  owner_id uuid not null references auth.users(id) on delete cascade,
  kind text not null,
  clip_id text references public.clips(id) on delete set null,
  sport text not null, ruleset_id text, format text,
  shot jsonb not null, rights text not null, attribution text, created_at timestamptz not null default now(),
  constraint reference_examples_kind_check check (kind in ('team_reference','public_cc'))
);

create index share_grants_owner_id_idx on public.share_grants(owner_id);
create index share_grants_grantee_id_idx on public.share_grants(grantee_id);
create index moments_owner_id_idx on public.moments(owner_id);
create index moments_match_id_idx on public.moments(match_id);
create index clips_owner_id_idx on public.clips(owner_id);
create index clips_match_id_idx on public.clips(match_id);
create index reference_examples_owner_id_idx on public.reference_examples(owner_id);
create index reference_examples_clip_id_idx on public.reference_examples(clip_id);

alter table public.share_grants enable row level security;
create policy share_grants_select on public.share_grants for select to authenticated
  using (owner_id = (select auth.uid()));

alter table public.moments enable row level security;
create policy moments_select on public.moments for select to authenticated
  using (owner_id = (select auth.uid()) or (select private.has_share_grant(match_id)));

alter table public.clips enable row level security;
create policy clips_select on public.clips for select to authenticated
  using (owner_id = (select auth.uid()) or (select private.has_share_grant(match_id)));

alter table public.reference_examples enable row level security;
create policy reference_examples_select on public.reference_examples for select to authenticated
  using (owner_id = (select auth.uid()));

revoke all on table public.share_grants, public.moments, public.clips, public.reference_examples
  from public, anon, authenticated;
grant select on public.share_grants, public.moments, public.clips, public.reference_examples
  to authenticated;
grant all on public.share_grants, public.moments, public.clips, public.reference_examples
  to service_role;
