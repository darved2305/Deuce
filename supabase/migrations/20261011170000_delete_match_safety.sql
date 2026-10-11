create or replace function public.create_analysis_run(p_session_id text, p_idempotency_key text)
returns public.analysis_runs language plpgsql set search_path = '' security definer as $$
declare
  v_run public.analysis_runs;
  v_session public.sessions;
begin
  select * into v_session from public.sessions where id = p_session_id;
  if not found then raise exception 'session_not_found' using errcode = 'P0002'; end if;

  perform 1 from public.matches where id = v_session.match_id for update;
  if not found then raise exception 'session_not_found' using errcode = 'P0002'; end if;

  begin
    insert into public.analysis_runs (session_id, match_id, owner_id, idempotency_key)
      values (p_session_id, v_session.match_id, v_session.owner_id, p_idempotency_key)
      on conflict (session_id, idempotency_key) do nothing returning * into v_run;
  exception when unique_violation then v_run := null;
  end;
  if v_run.id is not null then return v_run; end if;
  select * into v_run from public.analysis_runs
    where session_id = p_session_id and idempotency_key = p_idempotency_key;
  if found then return v_run; end if;
  select * into v_run from public.analysis_runs
    where session_id = p_session_id and state not in ('failed','completed');
  if found then return v_run; end if;
  raise exception 'analysis_run_conflict' using errcode = 'P0001';
end $$;

create or replace function public.delete_match(p_match_id text, p_owner_id uuid)
returns text[] language plpgsql set search_path = '' security definer as $$
declare
  v_keys text[];
begin
  perform 1 from public.matches
    where id = p_match_id and owner_id = p_owner_id
    for update;
  if not found then raise exception 'match_not_found' using errcode = 'P0002'; end if;

  if exists (
    select 1 from public.analysis_runs
    where match_id = p_match_id and state not in ('completed', 'failed')
  ) then
    raise exception using errcode = 'P0001', message = 'analysis_in_progress';
  end if;

  if exists (
    select 1
    from public.uploads u
    join public.sessions s on s.id = u.session_id
    where s.match_id = p_match_id
      and (
        s.id !~ '^s_[A-Za-z0-9_-]+$'
        or u.r2_key is distinct from ('orig/' || s.id || '/source.mp4')
      )
  ) then
    raise exception 'invalid_r2_key' using errcode = '22023';
  end if;

  if exists (
    select 1 from public.artifacts a
    where a.match_id = p_match_id
      and not (
        (
          a.analysis_id is not null
          and a.analysis_id ~ '^a_[A-Za-z0-9_-]+$'
          and (
            a.r2_key ~ ('^analysis/' || a.analysis_id || '/[^/]+/windows/[0-9]+[.]json$')
            or a.r2_key ~ ('^analysis/' || a.analysis_id || '/[^/]+/tracks/[^/]+[.]json$')
          )
        )
        or (
          left(a.r2_key, char_length('clips/' || p_match_id || '/')) =
            'clips/' || p_match_id || '/'
          and a.r2_key ~ '^clips/m_[A-Za-z0-9_-]+/c_[A-Za-z0-9_-]+[.](mp4|jpg)$'
        )
      )
  ) then
    raise exception 'invalid_r2_key' using errcode = '22023';
  end if;

  select coalesce(array_agg(r2_key order by r2_key), array[]::text[])
    into v_keys
    from (
      select u.r2_key from public.uploads u
        join public.sessions s on s.id = u.session_id
        where s.match_id = p_match_id
      union
      select a.r2_key from public.artifacts a where a.match_id = p_match_id
    ) recorded;

  delete from public.matches where id = p_match_id and owner_id = p_owner_id;
  if not found then raise exception 'match_not_found' using errcode = 'P0002'; end if;
  return v_keys;
end $$;

revoke all on function public.create_analysis_run(text,text) from public, anon, authenticated;
grant execute on function public.create_analysis_run(text,text) to service_role;
revoke all on function public.delete_match(text,uuid) from public, anon, authenticated;
grant execute on function public.delete_match(text,uuid) to service_role;
