-- B1b control tables: user confirmations, audit corrections, consent, claims, and API keys.
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

create table public.corrections (
  id text primary key default 'cr_' || replace(gen_random_uuid()::text,'-',''),
  match_id text not null references public.matches(id) on delete cascade,
  owner_id uuid not null references auth.users(id) on delete cascade,
  target_type text not null, target_id text not null, field text not null,
  old text, new text, actor uuid not null references auth.users(id), reason text,
  created_at timestamptz not null default now()
);

create table public.consents (
  id text primary key default 'cs_' || replace(gen_random_uuid()::text,'-',''),
  match_id text not null references public.matches(id) on delete cascade,
  owner_id uuid not null references auth.users(id) on delete cascade,
  subject text not null,
  scope text not null,
  granted boolean not null default true, granted_by uuid not null references auth.users(id),
  note text, revoked_at timestamptz, created_at timestamptz not null default now(),
  constraint consents_scope_check
    check (scope in ('clip_compare','clip_embed','reference_library','retain_original'))
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
  key_hash text not null unique,
  scope text not null default 'read',
  label text, revoked_at timestamptz, created_at timestamptz not null default now(),
  constraint developer_keys_scope_check check (scope in ('read'))
);

alter table public.matches add column score jsonb;

create index confirmed_points_owner_id_idx on public.confirmed_points(owner_id);
create index confirmed_points_match_id_idx on public.confirmed_points(match_id);
create index corrections_owner_id_idx on public.corrections(owner_id);
create index corrections_match_id_idx on public.corrections(match_id);
create index consents_owner_id_idx on public.consents(owner_id);
create index consents_match_id_idx on public.consents(match_id);
create index claims_owner_id_idx on public.claims(owner_id);
create index claims_player_id_idx on public.claims(player_id);
create index developer_keys_owner_id_idx on public.developer_keys(owner_id);

alter table public.confirmed_points enable row level security;
create policy confirmed_points_select on public.confirmed_points for select to authenticated
  using (owner_id = (select auth.uid()));
create policy confirmed_points_insert on public.confirmed_points for insert to authenticated
  with check (
    owner_id = (select auth.uid())
    and confirmed_by = (select auth.uid())
    and exists (
      select 1 from public.matches m
      where m.id = match_id and m.owner_id = (select auth.uid())
    )
  );

alter table public.corrections enable row level security;
create policy corrections_select on public.corrections for select to authenticated
  using (owner_id = (select auth.uid()));
create policy corrections_insert on public.corrections for insert to authenticated
  with check (
    owner_id = (select auth.uid())
    and actor = (select auth.uid())
    and exists (
      select 1 from public.matches m
      where m.id = match_id and m.owner_id = (select auth.uid())
    )
  );

alter table public.consents enable row level security;
create policy consents_select on public.consents for select to authenticated
  using (owner_id = (select auth.uid()));

alter table public.claims enable row level security;
create policy claims_select on public.claims for select to authenticated
  using (owner_id = (select auth.uid()));

alter table public.developer_keys enable row level security;
create policy developer_keys_select on public.developer_keys for select to authenticated
  using (owner_id = (select auth.uid()));

revoke all on public.confirmed_points, public.corrections, public.consents, public.claims,
  public.developer_keys from anon, authenticated;
grant select, insert on public.confirmed_points, public.corrections to authenticated;
grant select on public.consents, public.claims to authenticated;
grant select (id, owner_id, scope, label, revoked_at, created_at)
  on public.developer_keys to authenticated;
grant all on public.confirmed_points, public.corrections, public.consents, public.claims,
  public.developer_keys to service_role;
