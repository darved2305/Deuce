-- pgTAP for create_analysis_run, claim_dispatch, session_spec (spec §7.1). Run: supabase test db
begin;
select plan(30);

-- fixtures (test role bypasses RLS for setup)
insert into auth.users (instance_id, id, aud, role, email, encrypted_password, email_confirmed_at, created_at, updated_at)
values ('00000000-0000-0000-0000-000000000000', '11111111-1111-1111-1111-111111111111',
        'authenticated', 'authenticated', 'p3-dispatch@elevate.dev', '', now(), now(), now());

insert into public.matches (owner_id, sport, format, ruleset_id, ruleset_version)
values ('11111111-1111-1111-1111-111111111111', 'badminton', 'singles', 'bwf_singles', 'v1');

-- one session per live run: analysis_runs_one_live_per_session forbids a second
-- non-terminal run on the same session
insert into public.sessions (id, match_id, owner_id, mode, source)
select 's_live', id, owner_id, 'match', '{"kind":"upload","bytes":1024,"sha256":"x"}'::jsonb
from public.matches limit 1;
insert into public.sessions (id, match_id, owner_id, mode, source)
select 's_term', id, owner_id, 'match', '{"kind":"upload","bytes":1024,"sha256":"x"}'::jsonb
from public.matches limit 1;
insert into public.sessions (id, match_id, owner_id, mode, source)
select 's_claim', id, owner_id, 'match', '{"kind":"upload","bytes":1024,"sha256":"x"}'::jsonb
from public.matches limit 1;
insert into public.sessions (id, match_id, owner_id, mode, source, calibration_input, who_is_me, rally_candidates, upload_mode)
select 's_spec', id, owner_id, 'match',
       '{"width":1920,"height":1080,"fps_nominal":60,"codec":"avc1","has_audio":true,"bytes":51203344,"sha256":"x"}'::jsonb,
       '{"method":"tap","plane":"court","template":"badminton_singles","frame_ms":1200}'::jsonb,
       '{"image_xy":[128,150],"t_ms":1200}'::jsonb,
       '[{"start_ms":4200,"end_ms":11800,"onsets":9,"state":"candidate"}]'::jsonb,
       'multipart'
from public.matches limit 1;

-- create_analysis_run: a new key inserts and returns the row
select ok((select (public.create_analysis_run('s_live', 'k1')).id) is not null,
    'new key returns a run row');
select is((select state from public.analysis_runs where session_id = 's_live' and idempotency_key = 'k1'),
    'uploaded', 'new run starts in state uploaded');
select is((select owner_id::text from public.analysis_runs where session_id = 's_live' and idempotency_key = 'k1'),
    '11111111-1111-1111-1111-111111111111', 'owner_id copied from the session');
select is((select match_id from public.analysis_runs where session_id = 's_live' and idempotency_key = 'k1'),
    (select match_id from public.sessions where id = 's_live'), 'match_id copied from the session');

-- same key -> same run, no second row
select is((select (public.create_analysis_run('s_live', 'k1')).id),
    (select id from public.analysis_runs where session_id = 's_live' and idempotency_key = 'k1'),
    'same idempotency key returns the same run id');
select is((select count(*)::int from public.analysis_runs where session_id = 's_live'),
    1, 'retry with the same key does not create a second run');

-- different key on a session with a live run -> the partial unique index raises
-- unique_violation, the function catches it and returns the live run instead
select is((select (public.create_analysis_run('s_live', 'k_other')).id),
    (select id from public.analysis_runs where session_id = 's_live' and idempotency_key = 'k1'),
    'different key on a live session returns the live run, not a new row');
select is((select count(*)::int from public.analysis_runs where session_id = 's_live'),
    1, 'no second run row exists on the live session');

-- a terminal run frees the session: a new key creates a new run
do $$
begin
  perform public.create_analysis_run('s_term', 'k1');
end $$;
update public.analysis_runs set state = 'failed', failure_reason = 'engine_crash'
  where session_id = 's_term';
select isnt((select (public.create_analysis_run('s_term', 'k2')).id),
    (select id from public.analysis_runs where session_id = 's_term' and idempotency_key = 'k1'),
    'new key after a terminal run creates a new run');
select is((select count(*)::int from public.analysis_runs where session_id = 's_term'),
    2, 'terminal run plus new run = 2 rows on the session');

-- unknown session -> P0002 session_not_found
select throws_ok(
    $$select public.create_analysis_run('s_missing', 'k')$$,
    'P0002', 'session_not_found', 'unknown session rejected');

-- claim_dispatch: first claim wins the lease, second claim inside it gets no row
insert into public.analysis_runs (id, session_id, match_id, owner_id, idempotency_key)
select 'a_claim', s.id, s.match_id, s.owner_id, 'k_claim'
from public.sessions s where s.id = 's_claim';

select is((select (public.claim_dispatch('a_claim')).id),
    'a_claim', 'first claim returns the run');
select is((select dispatch_attempts from public.analysis_runs where id = 'a_claim'),
    1, 'dispatch_attempts incremented to 1');
select is((select dispatching from public.analysis_runs where id = 'a_claim'),
    true, 'dispatching flag set on claim');
select ok((select (public.claim_dispatch('a_claim')).id) is null,
    'second claim inside the 60 s lease returns no row');

-- dead dispatcher: a claim older than 60 s is reclaimed
update public.analysis_runs set dispatch_claimed_at = now() - interval '61 seconds'
  where id = 'a_claim';
select is((select (public.claim_dispatch('a_claim')).id),
    'a_claim', 'expired lease is reclaimed');
select is((select dispatch_attempts from public.analysis_runs where id = 'a_claim'),
    2, 'reclaim increments dispatch_attempts to 2');

-- a dispatched run (modal_call_id set) is never claimable again
update public.analysis_runs set modal_call_id = 'mc_test_1', dispatching = false
  where id = 'a_claim';
select ok((select (public.claim_dispatch('a_claim')).id) is null,
    'run with modal_call_id set is not claimable');

-- session_spec: contracts 2 key set, sport/format/ruleset read from the match
select is((select array_agg(k order by k) from jsonb_object_keys(public.session_spec('s_spec')) k),
    '{calibration_input,format,free_play_seed,mode,rally_candidates,ruleset,source,sport,upload_mode,who_is_me}'::text[],
    'session_spec returns the contracts 2 key set');
select is(public.session_spec('s_spec')->>'sport', 'badminton',
    'sport comes from the match row');
select is(public.session_spec('s_spec')->'ruleset',
    '{"id":"bwf_singles","version":"v1"}'::jsonb,
    'ruleset carries the match ruleset id and version');

-- grants: service_role can execute all three, authenticated/anon cannot
select is((select count(*)::int from information_schema.role_routine_grants
    where routine_schema = 'public' and routine_name = 'create_analysis_run'
      and grantee = 'service_role' and privilege_type = 'EXECUTE'),
    1, 'service_role may execute create_analysis_run');
select is((select count(*)::int from information_schema.role_routine_grants
    where routine_schema = 'public' and routine_name = 'create_analysis_run'
      and grantee = 'authenticated' and privilege_type = 'EXECUTE'),
    0, 'authenticated may not execute create_analysis_run');
select is((select count(*)::int from information_schema.role_routine_grants
    where routine_schema = 'public' and routine_name = 'create_analysis_run'
      and grantee = 'anon' and privilege_type = 'EXECUTE'),
    0, 'anon may not execute create_analysis_run');
select is((select count(*)::int from information_schema.role_routine_grants
    where routine_schema = 'public' and routine_name = 'claim_dispatch'
      and grantee = 'service_role' and privilege_type = 'EXECUTE'),
    1, 'service_role may execute claim_dispatch');
select is((select count(*)::int from information_schema.role_routine_grants
    where routine_schema = 'public' and routine_name = 'claim_dispatch'
      and grantee = 'authenticated' and privilege_type = 'EXECUTE'),
    0, 'authenticated may not execute claim_dispatch');
select is((select count(*)::int from information_schema.role_routine_grants
    where routine_schema = 'public' and routine_name = 'claim_dispatch'
      and grantee = 'anon' and privilege_type = 'EXECUTE'),
    0, 'anon may not execute claim_dispatch');
select is((select count(*)::int from information_schema.role_routine_grants
    where routine_schema = 'public' and routine_name = 'session_spec'
      and grantee = 'service_role' and privilege_type = 'EXECUTE'),
    1, 'service_role may execute session_spec');
select is((select count(*)::int from information_schema.role_routine_grants
    where routine_schema = 'public' and routine_name = 'session_spec'
      and grantee = 'authenticated' and privilege_type = 'EXECUTE'),
    0, 'authenticated may not execute session_spec');
select is((select count(*)::int from information_schema.role_routine_grants
    where routine_schema = 'public' and routine_name = 'session_spec'
      and grantee = 'anon' and privilege_type = 'EXECUTE'),
    0, 'anon may not execute session_spec');

select * from finish();
rollback;
