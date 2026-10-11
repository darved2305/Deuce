create table public.segments (
  session_id text not null references public.sessions(id) on delete cascade,
  owner_id uuid not null references auth.users(id) on delete cascade,
  seq integer not null,
  pts_start_us bigint not null,
  pts_end_us bigint not null,
  starts_on_sync_sample boolean not null,
  bytes bigint not null,
  sha256 text not null,
  final boolean not null,
  r2_key text not null unique,
  url_requests integer not null default 0,
  completed_at timestamptz,
  created_at timestamptz not null default now(),
  primary key (session_id, seq),
  constraint segments_seq_nonnegative check (seq >= 0),
  constraint segments_pts_start_nonnegative check (pts_start_us >= 0),
  constraint segments_pts_end_nonnegative check (pts_end_us >= 0),
  constraint segments_bytes_nonnegative check (bytes >= 0),
  constraint segments_sha256_format check (sha256 ~ '^[A-Fa-f0-9]{64}$'),
  constraint segments_r2_key_matches_seq check (r2_key = 'seg/' || session_id || '/' || seq::text || '.mp4'),
  constraint segments_url_requests_range check (url_requests between 0 and 3)
);

create index segments_owner_idx on public.segments(owner_id);

alter table public.segments enable row level security;
create policy segments_select on public.segments for select to authenticated
  using ((select auth.uid()) = owner_id);

revoke all on public.segments from anon, authenticated;
grant select on public.segments to authenticated;
grant all on public.segments to service_role;

create or replace function public.r2_budget_snapshot(p_owner uuid)
returns jsonb language sql stable set search_path = '' security definer as $$
  select jsonb_build_object(
    'sessions_today', (select count(*) from public.sessions
                        where owner_id = p_owner
                          and created_at >= date_trunc('day', now() at time zone 'utc') at time zone 'utc'),
    'uploads_today', (select count(*) from public.uploads
                       where created_at >= date_trunc('day', now() at time zone 'utc') at time zone 'utc')
      + (select count(*) from public.sessions
          where upload_mode = 'segments'
            and created_at >= date_trunc('day', now() at time zone 'utc') at time zone 'utc'),
    'storage_bytes',
      (select coalesce(sum(bytes), 0) from public.uploads
        where created_at > now() - interval '1 day' and status <> 'aborted')
      + (select coalesce(sum(bytes), 0) from public.segments
          where created_at > now() - interval '1 day')
      + (select coalesce(sum(bytes), 0) from public.artifacts
          where created_at > now() - interval '14 days'),
    'class_a', coalesce((select class_a from public.r2_usage_monthly
        where month = date_trunc('month', now() at time zone 'utc')::date), 0),
    'class_b', coalesce((select class_b from public.r2_usage_monthly
        where month = date_trunc('month', now() at time zone 'utc')::date), 0)
  ) $$;

create or replace function public.register_segment(
  p_session_id text,
  p_owner_id uuid,
  p_seq integer,
  p_pts_start_us bigint,
  p_pts_end_us bigint,
  p_starts_on_sync_sample boolean,
  p_bytes bigint,
  p_sha256 text,
  p_final boolean
)
returns public.segments language plpgsql set search_path = '' security definer as $$
declare
  v_segment public.segments;
  v_last_seq integer;
  v_total_bytes bigint;
  v_storage_bytes bigint;
begin
  perform 1 from public.sessions
   where id = p_session_id and owner_id = p_owner_id and upload_mode = 'segments'
   for update;
  if not found then raise exception 'session_not_found' using errcode = 'P0002'; end if;

  select * into v_segment from public.segments
   where session_id = p_session_id and seq = p_seq;
  if found then
    if v_segment.pts_start_us = p_pts_start_us
       and v_segment.pts_end_us = p_pts_end_us
       and v_segment.starts_on_sync_sample = p_starts_on_sync_sample
       and v_segment.bytes = p_bytes
       and v_segment.sha256 = lower(p_sha256)
       and v_segment.final = p_final then
      if v_segment.completed_at is not null then
        raise exception 'segment_completed' using errcode = 'P0001';
      end if;
      return v_segment;
    end if;
    raise exception 'segment_conflict' using errcode = 'P0001';
  end if;

  select coalesce(max(seq), -1) into v_last_seq from public.segments
   where session_id = p_session_id;
  if p_seq > v_last_seq + 1 or p_seq <= v_last_seq then
    raise exception 'segment_gap' using errcode = 'P0001';
  end if;
  if exists (select 1 from public.segments where session_id = p_session_id and final) then
    raise exception 'segment_finalized' using errcode = 'P0001';
  end if;

  select coalesce(sum(bytes), 0) into v_total_bytes from public.segments
   where session_id = p_session_id;
  if p_bytes > 314572800 or v_total_bytes + p_bytes > 314572800 then
    raise exception 'upload_too_large' using errcode = 'P0001';
  end if;

  select coalesce(sum(bytes), 0) into v_storage_bytes from (
    select bytes from public.uploads
     where created_at > now() - interval '1 day' and status <> 'aborted'
    union all
    select bytes from public.segments
     where created_at > now() - interval '1 day'
    union all
    select bytes from public.artifacts
     where created_at > now() - interval '14 days'
  ) as stored;
  if v_storage_bytes + p_bytes > 2147483648 then
    raise exception 'storage_budget_reached' using errcode = 'P0001';
  end if;

  insert into public.segments (
    session_id, owner_id, seq, pts_start_us, pts_end_us, starts_on_sync_sample,
    bytes, sha256, final, r2_key
  ) values (
    p_session_id, p_owner_id, p_seq, p_pts_start_us, p_pts_end_us,
    p_starts_on_sync_sample, p_bytes, lower(p_sha256), p_final,
    'seg/' || p_session_id || '/' || p_seq::text || '.mp4'
  ) returning * into v_segment;
  return v_segment;
end $$;

create or replace function public.r2_take_segment_url(
  p_session text,
  p_owner uuid,
  p_seq integer,
  p_limit integer
)
returns integer language plpgsql set search_path = '' security definer as $$
declare
  v_segment public.segments;
  v_total integer;
begin
  select * into v_segment from public.segments
   where session_id = p_session and owner_id = p_owner and seq = p_seq
   for update;
  if not found then raise exception 'segment_not_found' using errcode = 'P0002'; end if;
  if v_segment.completed_at is not null then
    raise exception 'segment_completed' using errcode = 'P0001';
  end if;
  if v_segment.url_requests >= p_limit then return null; end if;

  update public.segments set url_requests = url_requests + 1
   where session_id = p_session and seq = p_seq
   returning url_requests into v_total;
  perform public.r2_add_usage(1, 0);
  return v_total;
end $$;

revoke all on function public.register_segment(text,uuid,integer,bigint,bigint,boolean,bigint,text,boolean)
  from public, anon, authenticated;
revoke all on function public.r2_take_segment_url(text,uuid,integer,integer)
  from public, anon, authenticated;
grant execute on function public.register_segment(text,uuid,integer,bigint,bigint,boolean,bigint,text,boolean)
  to service_role;
grant execute on function public.r2_take_segment_url(text,uuid,integer,integer)
  to service_role;
