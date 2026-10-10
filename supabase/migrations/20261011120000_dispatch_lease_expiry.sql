-- A capped dispatch fails only after the fifth claim's lease has expired.
create or replace function public.claim_dispatch(p_analysis_id text)
returns public.analysis_runs language plpgsql set search_path = '' security definer as $$
declare
  v_run public.analysis_runs;
begin
  select * into v_run
    from public.analysis_runs as r
   where r.id = p_analysis_id
   for update;
  if not found or v_run.state <> 'queued' or v_run.modal_call_id is not null then
    return null;
  end if;
  if v_run.dispatching and (v_run.dispatch_claimed_at is null or
      v_run.dispatch_claimed_at >= now() - interval '60 seconds') then
    return null;
  end if;

  if v_run.dispatch_attempts >= 5 then
    perform public.append_event(
      p_analysis_id,
      'analysis.failed',
      pg_catalog.jsonb_build_object('reason', 'dispatch_failed')
    );
    update public.analysis_runs as r
       set dispatching = false, updated_at = now()
     where r.id = p_analysis_id
     returning r.* into v_run;
    return v_run;
  end if;

  update public.analysis_runs as r
     set dispatching = true, dispatch_attempts = dispatch_attempts + 1,
         dispatch_claimed_at = now(), updated_at = now()
   where r.id = p_analysis_id
   returning r.* into v_run;
  return v_run;
end
$$;
revoke all on function public.claim_dispatch(text) from public, anon, authenticated;
grant execute on function public.claim_dispatch(text) to service_role;
