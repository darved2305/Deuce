-- R2 budget guards (docs/ops/cloudflare-budget.md, D-261011-HW1, D-261011-HW2).
-- Counters and atomic checks the API uses before handing out R2 work. All of it is
-- service-role only: RLS is on and there are no user policies or grants.

-- G5/G10: Class A and Class B operations we cause, per UTC month.
create table public.r2_usage_monthly (
  month date primary key,                       -- first day of the UTC month
  class_a bigint not null default 0 check (class_a >= 0),
  class_b bigint not null default 0 check (class_b >= 0),
  updated_at timestamptz not null default now()
);
alter table public.r2_usage_monthly enable row level security;   -- service only

-- G6: download URLs issued per user per UTC day.
create table public.r2_usage_daily (
  owner_id uuid not null references auth.users(id) on delete cascade,
  day date not null,
  downloads integer not null default 0 check (downloads >= 0),
  primary key (owner_id, day)
);
alter table public.r2_usage_daily enable row level security;      -- service only

-- G4: part URLs issued for one upload (limit is 3 x part count, enforced by the RPC).
alter table public.uploads add column part_urls_issued integer not null default 0
  check (part_urls_issued >= 0);

revoke all on public.r2_usage_monthly, public.r2_usage_daily from anon, authenticated;
grant all on public.r2_usage_monthly, public.r2_usage_daily to service_role;

-- Adds to the current UTC month's counters.
create or replace function public.r2_add_usage(p_class_a bigint, p_class_b bigint)
returns void language sql set search_path = '' security definer as $$
  insert into public.r2_usage_monthly as u (month, class_a, class_b)
  values (date_trunc('month', now() at time zone 'utc')::date,
          greatest(p_class_a, 0), greatest(p_class_b, 0))
  on conflict (month) do update
    set class_a = u.class_a + greatest(p_class_a, 0),
        class_b = u.class_b + greatest(p_class_b, 0),
        updated_at = now() $$;

-- Everything the create/pause checks need in one round trip.
--   sessions_today / uploads_today: UTC day, this owner / all owners (G3)
--   storage_bytes: originals created in the last day (not aborted) + artifacts of the last
--                  14 days, matching the lifecycle rules (G2, G10)
--   class_a, class_b: this UTC month (G5, G10)
create or replace function public.r2_budget_snapshot(p_owner uuid)
returns jsonb language sql stable set search_path = '' security definer as $$
  select jsonb_build_object(
    'sessions_today', (select count(*) from public.sessions
                        where owner_id = p_owner
                          and created_at >= date_trunc('day', now() at time zone 'utc') at time zone 'utc'),
    'uploads_today', (select count(*) from public.uploads
                       where created_at >= date_trunc('day', now() at time zone 'utc') at time zone 'utc'),
    'storage_bytes',
      (select coalesce(sum(bytes), 0) from public.uploads
        where created_at > now() - interval '1 day' and status <> 'aborted')
      + (select coalesce(sum(bytes), 0) from public.artifacts
          where created_at > now() - interval '14 days'),
    'class_a', coalesce((select class_a from public.r2_usage_monthly
        where month = date_trunc('month', now() at time zone 'utc')::date), 0),
    'class_b', coalesce((select class_b from public.r2_usage_monthly
        where month = date_trunc('month', now() at time zone 'utc')::date), 0)
  ) $$;

-- G4: atomically takes p_count part URLs for an owner's upload if the running total stays
-- within p_limit; counts them as Class A. Returns the new total, or null when refused.
create or replace function public.r2_take_part_urls(p_session text, p_owner uuid, p_count integer, p_limit integer)
returns integer language plpgsql set search_path = '' security definer as $$
declare v_total integer;
begin
  update public.uploads set part_urls_issued = part_urls_issued + p_count
   where session_id = p_session and owner_id = p_owner
     and part_urls_issued + p_count <= p_limit
   returning part_urls_issued into v_total;
  if v_total is null then return null; end if;
  perform public.r2_add_usage(p_count, 0);
  return v_total;
end $$;

-- G6: atomically takes one download URL for the owner's UTC day if under p_limit; counts it
-- as Class B. Returns the day's new total, or null when the daily limit is reached.
create or replace function public.r2_take_download(p_owner uuid, p_limit integer)
returns integer language plpgsql set search_path = '' security definer as $$
declare v_total integer;
begin
  insert into public.r2_usage_daily as d (owner_id, day, downloads)
  values (p_owner, (now() at time zone 'utc')::date, 0)
  on conflict (owner_id, day) do nothing;
  update public.r2_usage_daily set downloads = downloads + 1
   where owner_id = p_owner and day = (now() at time zone 'utc')::date and downloads < p_limit
   returning downloads into v_total;
  if v_total is null then return null; end if;
  perform public.r2_add_usage(0, 1);
  return v_total;
end $$;

revoke all on function public.r2_add_usage(bigint,bigint) from public, anon, authenticated;
revoke all on function public.r2_budget_snapshot(uuid) from public, anon, authenticated;
revoke all on function public.r2_take_part_urls(text,uuid,integer,integer) from public, anon, authenticated;
revoke all on function public.r2_take_download(uuid,integer) from public, anon, authenticated;
grant execute on function public.r2_add_usage(bigint,bigint) to service_role;
grant execute on function public.r2_budget_snapshot(uuid) to service_role;
grant execute on function public.r2_take_part_urls(text,uuid,integer,integer) to service_role;
grant execute on function public.r2_take_download(uuid,integer) to service_role;
