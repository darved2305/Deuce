create table public.posts (
  id text primary key default 'po_' || replace(gen_random_uuid()::text, '-', ''),
  owner_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
  match_id text not null references public.matches(id) on delete cascade,
  caption text,
  summary jsonb not null,
  created_at timestamptz not null default now(),
  constraint posts_caption_length_check check (caption is null or char_length(caption) <= 140),
  constraint posts_summary_object_check check (jsonb_typeof(summary) = 'object')
);

create index posts_owner_id_idx on public.posts(owner_id);
create index posts_match_id_idx on public.posts(match_id);
create index posts_created_at_id_idx on public.posts(created_at desc, id desc);
create index posts_owner_match_created_at_idx on public.posts(owner_id, match_id, created_at desc, id desc);

create schema if not exists private;
revoke all on schema private from public, anon;
grant usage on schema private to authenticated;

create or replace function private.is_friend(p_player_id uuid)
returns boolean language sql stable security definer set search_path = ''
as $$
  select exists (
    select 1
    from public.challenges c
    join public.challenge_entries e on e.challenge_id = c.id
    where (c.creator_id = (select auth.uid()) and e.player_id = p_player_id)
       or (e.player_id = (select auth.uid()) and c.creator_id = p_player_id)
  )
$$;
revoke all on function private.is_friend(uuid) from public, anon;
grant execute on function private.is_friend(uuid) to authenticated;

create or replace function private.match_post_summary(p_match_id text, p_owner_id uuid)
returns jsonb language plpgsql stable security definer set search_path = '' as $$
declare
  v_match jsonb := public.get_match_v1(p_match_id);
  v_duration_ms integer;
  v_owner_display_name text;
begin
  select case when count(*) = 1 then max(nullif(s.source->>'duration_ms', '')::integer) end
    into v_duration_ms
    from public.sessions s
    where s.match_id = p_match_id;

  select n.display_name into v_owner_display_name
    from (
      select c.creator_name as display_name, c.created_at as changed_at
        from public.challenges c
       where c.creator_id = p_owner_id
      union all
      select e.display_name, e.created_at
        from public.challenge_entries e
       where e.player_id = p_owner_id
    ) n
   order by n.changed_at desc, n.display_name
   limit 1;

  return jsonb_build_object(
    'title', null::text,
    'duration_ms', v_duration_ms,
    'rally_count', case when v_match is null then null else jsonb_array_length(coalesce(v_match->'rallies', '[]'::jsonb)) end,
    'hit_count', case when v_match is null then null else (
      select count(*)::integer
        from jsonb_array_elements(coalesce(v_match->'events', '[]'::jsonb)) e(value)
       where e.value->>'type' = 'hit' and e.value->>'state' in ('observed', 'user')
    ) end,
    'landings_in', case when v_match is null then null else (
      select count(*)::integer
        from jsonb_array_elements(coalesce(v_match->'reviews', '[]'::jsonb)) r(value)
       where r.value->>'estimate' = 'IN'
    ) end,
    'landings_out', case when v_match is null then null else (
      select count(*)::integer
        from jsonb_array_elements(coalesce(v_match->'reviews', '[]'::jsonb)) r(value)
       where r.value->>'estimate' = 'OUT'
    ) end,
    'longest_rally_ms', (
      select max((r.value->>'end_ms')::integer - (r.value->>'start_ms')::integer)
        from jsonb_array_elements(coalesce(v_match->'rallies', '[]'::jsonb)) r(value)
    ),
    'top_coach_points', coalesce((
      select jsonb_agg(points.caption order by points.ordinality)
        from (
          select insight.value->>'caption' as caption, insight.ordinality
            from jsonb_array_elements(coalesce(v_match->'insights', '[]'::jsonb))
              with ordinality as insight(value, ordinality)
           where jsonb_typeof(insight.value->'caption') = 'string'
           order by insight.ordinality
           limit 3
        ) points
    ), '[]'::jsonb),
    'landing_points_m', coalesce((
      select jsonb_agg(jsonb_build_object(
        'x_m', (event.value->'contact_xy_m'->>0)::numeric,
        'y_m', (event.value->'contact_xy_m'->>1)::numeric
      ) order by event.value->>'t_ms', event.value->>'event_id')
        from jsonb_array_elements(coalesce(v_match->'events', '[]'::jsonb)) event(value)
       where event.value->>'type' = 'landing'
         and event.value->>'plane' = 'court'
         and event.value->>'state' in ('observed', 'user')
         and jsonb_typeof(event.value->'contact_xy_m') = 'array'
    ), '[]'::jsonb),
    'owner_display_name', v_owner_display_name
  );
end $$;
revoke all on function private.match_post_summary(text, uuid) from public, anon, authenticated;

create or replace function private.prepare_match_post()
returns trigger language plpgsql security definer set search_path = '' as $$
declare
  v_day_start timestamptz;
  v_day_end timestamptz;
  v_count integer;
begin
  if new.owner_id is distinct from (select auth.uid()) then
    raise exception 'post_owner_mismatch' using errcode = '42501';
  end if;
  if not exists (
    select 1 from public.matches m where m.id = new.match_id and m.owner_id = new.owner_id
  ) then
    raise exception 'match_not_found' using errcode = 'P0002';
  end if;

  new.id := 'po_' || replace(pg_catalog.gen_random_uuid()::text, '-', '');
  new.created_at := now();
  v_day_start := (pg_catalog.date_trunc('day', pg_catalog.timezone('UTC', now()))) at time zone 'UTC';
  v_day_end := v_day_start + interval '1 day';
  perform pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended(
    new.owner_id::text || ':' || pg_catalog.to_char(v_day_start at time zone 'UTC', 'YYYY-MM-DD'), 0
  ));
  select count(*)::integer into v_count
    from public.posts p
   where p.owner_id = new.owner_id and p.created_at >= v_day_start and p.created_at < v_day_end;
  if v_count >= 20 then
    raise exception 'daily_post_limit' using errcode = 'P0001';
  end if;

  new.summary := private.match_post_summary(new.match_id, new.owner_id);
  return new;
end $$;
revoke all on function private.prepare_match_post() from public, anon, authenticated;
create trigger posts_prepare_before_insert
before insert on public.posts
for each row execute function private.prepare_match_post();

alter table public.posts enable row level security;
create policy posts_select on public.posts for select to authenticated
  using (owner_id = (select auth.uid()) or (select private.is_friend(owner_id)));
create policy posts_insert on public.posts for insert to authenticated
  with check (owner_id = (select auth.uid()));
create policy posts_delete on public.posts for delete to authenticated
  using (owner_id = (select auth.uid()));

revoke all on table public.posts from public, anon, authenticated;
grant select, insert, delete on public.posts to authenticated;
grant all on public.posts to service_role;

create or replace function public.create_match_post(p_match_id text, p_caption text)
returns text language plpgsql security invoker set search_path = '' as $$
declare
  v_owner_id uuid := (select auth.uid());
  v_day_start timestamptz;
  v_day_end timestamptz;
  v_count integer;
  v_post_id text;
begin
  if v_owner_id is null or not exists (
    select 1 from public.matches m where m.id = p_match_id and m.owner_id = v_owner_id
  ) then
    raise exception 'match_not_found' using errcode = 'P0002';
  end if;

  v_day_start := (pg_catalog.date_trunc('day', pg_catalog.timezone('UTC', now()))) at time zone 'UTC';
  v_day_end := v_day_start + interval '1 day';
  perform pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended(
    v_owner_id::text || ':' || pg_catalog.to_char(v_day_start at time zone 'UTC', 'YYYY-MM-DD'), 0
  ));

  select p.id into v_post_id
    from public.posts p
   where p.owner_id = v_owner_id
     and p.match_id = p_match_id
     and p.caption is not distinct from p_caption
     and p.created_at >= now() - interval '60 seconds'
   order by p.created_at desc, p.id desc
   limit 1;
  if found then return v_post_id; end if;

  select count(*)::integer into v_count
    from public.posts p
   where p.owner_id = v_owner_id and p.created_at >= v_day_start and p.created_at < v_day_end;
  if v_count >= 20 then
    raise exception 'daily_post_limit' using errcode = 'P0001';
  end if;

  insert into public.posts (owner_id, match_id, caption)
  values (v_owner_id, p_match_id, p_caption)
  returning id into v_post_id;
  return v_post_id;
end $$;
revoke all on function public.create_match_post(text, text) from public, anon, authenticated;
grant execute on function public.create_match_post(text, text) to authenticated;
