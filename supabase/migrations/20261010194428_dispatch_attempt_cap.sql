-- Dispatch retry cap (person-3-spec 7.1, D-261010-NP2): a run may be claimed at most
-- 5 times; afterwards the route marks it failed with failure_reason='dispatch_failed'.

create or replace function public.claim_dispatch(p_analysis_id text)
returns public.analysis_runs language sql set search_path = '' security definer as $$
  update public.analysis_runs
     set dispatching = true, dispatch_attempts = dispatch_attempts + 1,
         dispatch_claimed_at = now(), updated_at = now()
   where id = p_analysis_id and state = 'queued' and modal_call_id is null
     and dispatch_attempts < 5
     and (dispatching = false or dispatch_claimed_at < now() - interval '60 seconds')
   returning * $$;
revoke all on function public.claim_dispatch(text) from public, anon, authenticated;
grant execute on function public.claim_dispatch(text) to service_role;
