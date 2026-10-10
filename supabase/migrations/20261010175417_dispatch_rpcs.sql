-- Analyses dispatch RPCs (person-3-spec 7.1, D-261010-NP1)
-- create_analysis_run: idempotent run creation; returns the existing row for a repeated
-- key or the live run under a different key (partial unique index violations are caught).
-- claim_dispatch: atomic 60 s lease so only one dispatcher fires the Modal submit.
-- session_spec: the contracts 2 body assembled from the stored session + match rows.

create or replace function public.create_analysis_run(p_session_id text, p_idempotency_key text)
returns public.analysis_runs language plpgsql set search_path = '' security definer as $$
declare v_run public.analysis_runs; v_session public.sessions;
begin
  select * into v_session from public.sessions where id = p_session_id;
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
  select * into v_run from public.analysis_runs            -- live run under a different key
    where session_id = p_session_id and state not in ('failed','completed');
  if found then return v_run; end if;
  raise exception 'analysis_run_conflict' using errcode = 'P0001';
end $$;

create or replace function public.claim_dispatch(p_analysis_id text)
returns public.analysis_runs language sql set search_path = '' security definer as $$
  update public.analysis_runs
     set dispatching = true, dispatch_attempts = dispatch_attempts + 1,
         dispatch_claimed_at = now(), updated_at = now()
   where id = p_analysis_id and state = 'queued' and modal_call_id is null
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
