-- pgTAP for append_event + Phase 1 RLS (spec §5.3). Run: supabase test db
begin;
select plan(52);

-- fixtures (test role bypasses RLS for setup)
insert into auth.users (instance_id, id, aud, role, email, encrypted_password, email_confirmed_at, created_at, updated_at)
values ('00000000-0000-0000-0000-000000000000', '11111111-1111-1111-1111-111111111111',
        'authenticated', 'authenticated', 'p3-test@elevate.dev', '', now(), now(), now());

insert into public.matches (owner_id, sport, format, ruleset_id, ruleset_version)
values ('11111111-1111-1111-1111-111111111111', 'badminton', 'singles', 'bwf_singles', 'v1');
insert into public.sessions (match_id, owner_id, mode, source)
select id, owner_id, 'match', '{"kind":"upload","bytes":1024,"sha256":"x"}'::jsonb
from public.matches limit 1;
insert into public.analysis_runs (session_id, match_id, owner_id, idempotency_key)
select s.id, s.match_id, s.owner_id, 'k1' from public.sessions s limit 1;

-- run A: happy path
select is((select seq from public.append_event(
    (select id from public.analysis_runs where idempotency_key = 'k1'), 'analysis.queued', '{}'::jsonb)),
    1, 'first event gets seq 1');
select is((select state from public.analysis_runs where idempotency_key = 'k1'), 'queued',
    'analysis.queued -> queued');
select is((select seq from public.append_event(
    (select id from public.analysis_runs where idempotency_key = 'k1'), 'analysis.started',
    '{"engine_version":"eng-test-1"}'::jsonb)),
    2, 'second event gets seq 2');
select is((select state from public.analysis_runs where idempotency_key = 'k1'), 'processing',
    'analysis.started -> processing');
select is((select engine_version from public.analysis_runs where idempotency_key = 'k1'),
    'eng-test-1', 'engine_version stored on the run');
select is((select seq from public.append_event(
    (select id from public.analysis_runs where idempotency_key = 'k1'), 'window.completed',
    '{"window_seq":1}'::jsonb)),
    3, 'third event gets seq 3');
select is((select state from public.analysis_runs where idempotency_key = 'k1'), 'partial',
    'first window.completed -> partial');

-- dedupe: same (type, window_seq) returns the existing row, no new seq
select is((select seq from public.append_event(
    (select id from public.analysis_runs where idempotency_key = 'k1'), 'window.completed',
    '{"window_seq":1}'::jsonb)),
    3, 'retry of (window.completed, 1) returns existing seq 3');
select is((select last_seq from public.analysis_runs where idempotency_key = 'k1'), 3,
    'dedupe does not burn a seq');

-- out-of-order window_seq accepted (arrival order rules)
select is((select seq from public.append_event(
    (select id from public.analysis_runs where idempotency_key = 'k1'), 'window.completed',
    '{"window_seq":4}'::jsonb)),
    4, 'window 4 before 3 is accepted, seq 4');
select is((select seq from public.append_event(
    (select id from public.analysis_runs where idempotency_key = 'k1'), 'window.completed',
    '{"window_seq":3}'::jsonb)),
    5, 'window 3 arriving late still gets seq 5');

-- window.failed does not change state
select is((select seq from public.append_event(
    (select id from public.analysis_runs where idempotency_key = 'k1'), 'window.failed',
    '{"window_seq":2,"reason":"oom"}'::jsonb)),
    6, 'window.failed gets a seq');
select is((select state from public.analysis_runs where idempotency_key = 'k1'), 'partial',
    'window.failed leaves state partial');

-- invalid transition: partial -> completed
select throws_ok(
    $$select public.append_event((select id from public.analysis_runs where idempotency_key = 'k1'),
        'analysis.completed', '{}'::jsonb)$$,
    'P0001', 'invalid_transition', 'partial -> completed rejected');

-- point.confirmed is stateless and gets a seq
select is((select seq from public.append_event(
    (select id from public.analysis_runs where idempotency_key = 'k1'), 'point.confirmed',
    '{"rally_id":"r_1","winner":"p1"}'::jsonb)),
    7, 'point.confirmed gets seq 7');
select is((select state from public.analysis_runs where idempotency_key = 'k1'), 'partial',
    'point.confirmed does not change state');

-- finalizing -> completed
select is((select seq from public.append_event(
    (select id from public.analysis_runs where idempotency_key = 'k1'), 'analysis.finalizing',
    '{}'::jsonb)),
    8, 'analysis.finalizing seq 8');
select is((select state from public.analysis_runs where idempotency_key = 'k1'), 'finalizing',
    'partial -> finalizing');
select is((select seq from public.append_event(
    (select id from public.analysis_runs where idempotency_key = 'k1'), 'analysis.completed',
    '{}'::jsonb)),
    9, 'analysis.completed seq 9');
select is((select state from public.analysis_runs where idempotency_key = 'k1'), 'completed',
    'finalizing -> completed');

-- terminal: new state events rejected; point.confirmed still allowed; dedupe of old retry works
select throws_ok(
    $$select public.append_event((select id from public.analysis_runs where idempotency_key = 'k1'),
        'window.failed', '{"window_seq":9}'::jsonb)$$,
    'P0001', 'invalid_transition', 'terminal run rejects new state events');
select is((select seq from public.append_event(
    (select id from public.analysis_runs where idempotency_key = 'k1'), 'point.confirmed',
    '{"rally_id":"r_2","winner":"p2"}'::jsonb)),
    10, 'point.confirmed allowed after completed');
select is((select seq from public.append_event(
    (select id from public.analysis_runs where idempotency_key = 'k1'), 'window.completed',
    '{"window_seq":4}'::jsonb)),
    4, 'retry of an old event on a terminal run returns existing, not an error');

-- run B: uploaded -> failed via analysis.failed, failure_reason stored
insert into public.analysis_runs (session_id, match_id, owner_id, idempotency_key)
select s.id, s.match_id, s.owner_id, 'k2' from public.sessions s limit 1;
select is((select seq from public.append_event(
    (select id from public.analysis_runs where idempotency_key = 'k2'), 'analysis.failed',
    '{"reason":"dispatch_failed"}'::jsonb)),
    1, 'analysis.failed from uploaded, seq 1');
select is((select state from public.analysis_runs where idempotency_key = 'k2'), 'failed',
    'uploaded -> failed');
select is((select failure_reason from public.analysis_runs where idempotency_key = 'k2'),
    'dispatch_failed', 'failure_reason recorded');

-- missing analysis
select throws_ok(
    $$select public.append_event('a_missing', 'analysis.started', '{}'::jsonb)$$,
    'P0002', 'analysis_not_found', 'unknown analysis id rejected');

-- runs k3..k10 each get their own session: the one_live_per_session index forbids a
-- second non-terminal run on the same session (verified above by the failing batch insert)
do $$
begin
  for i in 3..10 loop
    with s as (
      insert into public.sessions (match_id, owner_id, mode, source)
      select m.id, m.owner_id, 'match', '{"kind":"upload","bytes":1024}'::jsonb
      from public.matches m where m.owner_id = '11111111-1111-1111-1111-111111111111' limit 1
      returning id, match_id, owner_id
    )
    insert into public.analysis_runs (session_id, match_id, owner_id, idempotency_key)
    select s.id, s.match_id, s.owner_id, 'k' || i from s;
  end loop;
end $$;

-- analysis.queued dedupe: the (type, NULL window_seq) path POST /v1/analyses relies on
select is((select seq from public.append_event(
    (select id from public.analysis_runs where idempotency_key = 'k3'), 'analysis.queued', '{}'::jsonb)),
    1, 'run k3 queued seq 1');
select is((select seq from public.append_event(
    (select id from public.analysis_runs where idempotency_key = 'k3'), 'analysis.queued', '{}'::jsonb)),
    1, 'retry of analysis.queued returns existing seq 1');
select is((select last_seq from public.analysis_runs where idempotency_key = 'k3'), 1,
    'queued dedupe does not burn a seq');

-- terminal via the transition table: a NEW window.completed on the completed run A
select throws_ok(
    $$select public.append_event((select id from public.analysis_runs where idempotency_key = 'k1'),
        'window.completed', '{"window_seq":99}'::jsonb)$$,
    'P0001', 'invalid_transition', 'new window.completed on completed run rejected by transition table');

-- dedupe key is (type, window_seq): window.failed ws=1 after window.completed ws=1 must append
do $$
begin
  perform public.append_event((select id from public.analysis_runs where idempotency_key = 'k4'),
    'analysis.queued', '{}'::jsonb);
  perform public.append_event((select id from public.analysis_runs where idempotency_key = 'k4'),
    'analysis.started', '{}'::jsonb);
  perform public.append_event((select id from public.analysis_runs where idempotency_key = 'k4'),
    'window.completed', '{"window_seq":1}'::jsonb);
end $$;
select is((select seq from public.append_event(
    (select id from public.analysis_runs where idempotency_key = 'k4'), 'window.failed',
    '{"window_seq":1}'::jsonb)),
    4, 'window.failed for the same window_seq appends (key includes type)');

-- remaining transitions from spec 7.2 (one run each, assert final state)
-- k5: queued -> partial (window.completed before started)
do $$
begin
  perform public.append_event((select id from public.analysis_runs where idempotency_key = 'k5'),
    'analysis.queued', '{}'::jsonb);
  perform public.append_event((select id from public.analysis_runs where idempotency_key = 'k5'),
    'window.completed', '{"window_seq":1}'::jsonb);
end $$;
select is((select state from public.analysis_runs where idempotency_key = 'k5'), 'partial',
    'queued -> partial via first window.completed');
-- k6: processing -> finalizing (skips partial)
do $$
begin
  perform public.append_event((select id from public.analysis_runs where idempotency_key = 'k6'),
    'analysis.queued', '{}'::jsonb);
  perform public.append_event((select id from public.analysis_runs where idempotency_key = 'k6'),
    'analysis.started', '{}'::jsonb);
  perform public.append_event((select id from public.analysis_runs where idempotency_key = 'k6'),
    'analysis.finalizing', '{}'::jsonb);
end $$;
select is((select state from public.analysis_runs where idempotency_key = 'k6'), 'finalizing',
    'processing -> finalizing without partial');
-- k7: queued -> failed
do $$
begin
  perform public.append_event((select id from public.analysis_runs where idempotency_key = 'k7'),
    'analysis.queued', '{}'::jsonb);
  perform public.append_event((select id from public.analysis_runs where idempotency_key = 'k7'),
    'analysis.failed', '{"reason":"engine_crash"}'::jsonb);
end $$;
select is((select state || ':' || failure_reason from public.analysis_runs where idempotency_key = 'k7'),
    'failed:engine_crash', 'queued -> failed with reason');
-- k8: processing -> failed
do $$
begin
  perform public.append_event((select id from public.analysis_runs where idempotency_key = 'k8'),
    'analysis.queued', '{}'::jsonb);
  perform public.append_event((select id from public.analysis_runs where idempotency_key = 'k8'),
    'analysis.started', '{}'::jsonb);
  perform public.append_event((select id from public.analysis_runs where idempotency_key = 'k8'),
    'analysis.failed', '{"reason":"gpu_oom"}'::jsonb);
end $$;
select is((select state from public.analysis_runs where idempotency_key = 'k8'), 'failed',
    'processing -> failed');
-- k9: partial -> failed
do $$
begin
  perform public.append_event((select id from public.analysis_runs where idempotency_key = 'k9'),
    'analysis.queued', '{}'::jsonb);
  perform public.append_event((select id from public.analysis_runs where idempotency_key = 'k9'),
    'analysis.started', '{}'::jsonb);
  perform public.append_event((select id from public.analysis_runs where idempotency_key = 'k9'),
    'window.completed', '{"window_seq":1}'::jsonb);
  perform public.append_event((select id from public.analysis_runs where idempotency_key = 'k9'),
    'analysis.failed', '{"reason":"engine_crash"}'::jsonb);
end $$;
select is((select state from public.analysis_runs where idempotency_key = 'k9'), 'failed',
    'partial -> failed');
-- k10: finalizing -> failed
do $$
begin
  perform public.append_event((select id from public.analysis_runs where idempotency_key = 'k10'),
    'analysis.queued', '{}'::jsonb);
  perform public.append_event((select id from public.analysis_runs where idempotency_key = 'k10'),
    'analysis.started', '{}'::jsonb);
  perform public.append_event((select id from public.analysis_runs where idempotency_key = 'k10'),
    'analysis.finalizing', '{}'::jsonb);
  perform public.append_event((select id from public.analysis_runs where idempotency_key = 'k10'),
    'analysis.failed', '{"reason":"insights_failed:x"}'::jsonb);
end $$;
select is((select state from public.analysis_runs where idempotency_key = 'k10'), 'failed',
    'finalizing -> failed');


-- RLS enforcement (not just the flag): owner isolation + insert checks as user B
insert into auth.users (instance_id, id, aud, role, email, encrypted_password, email_confirmed_at, created_at, updated_at)
values ('00000000-0000-0000-0000-000000000000', '22222222-2222-2222-2222-222222222222',
        'authenticated', 'authenticated', 'p3-test-b@elevate.dev', '', now(), now(), now());
insert into public.matches (owner_id, sport, format, ruleset_id, ruleset_version)
values ('22222222-2222-2222-2222-222222222222', 'badminton', 'singles', 'bwf_singles', 'v1');

set role authenticated;
select set_config('request.jwt.claim.sub', '22222222-2222-2222-2222-222222222222', true);
select is((select count(*)::int from public.matches where owner_id = '11111111-1111-1111-1111-111111111111'),
    0, 'RLS: user B cannot select user A matches');
select throws_matching(
    $$insert into public.sessions(match_id,owner_id,mode,source)
      values ('m_does_not_exist','22222222-2222-2222-2222-222222222222','match','{}'::jsonb)$$,
    'row-level security policy', 'RLS: session insert fails when match is not owned by the caller');
select lives_ok(
    $$insert into public.sessions(match_id,owner_id,mode,source)
      select id, '22222222-2222-2222-2222-222222222222', 'match', '{}'::jsonb
      from public.matches where owner_id = '22222222-2222-2222-2222-222222222222' limit 1$$,
    'RLS: session insert into own match allowed');
select throws_matching(
    $$insert into public.analysis_events(analysis_id,match_id,owner_id,seq,type)
      values ('a_none','m_none','22222222-2222-2222-2222-222222222222',99,'analysis.started')$$,
    'row-level security policy', 'RLS: direct analysis_events insert denied (admin-only writes)');
reset role;

-- grants: service_role can execute, authenticated/anon/public cannot
select is((select count(*)::int from information_schema.role_routine_grants
    where routine_schema = 'public' and routine_name = 'append_event'
      and grantee = 'service_role' and privilege_type = 'EXECUTE'),
    1, 'service_role may execute append_event');
select is((select count(*)::int from information_schema.role_routine_grants
    where routine_schema = 'public' and routine_name = 'append_event'
      and grantee = 'authenticated' and privilege_type = 'EXECUTE'),
    0, 'authenticated may not execute append_event');
select is((select count(*)::int from information_schema.role_routine_grants
    where routine_schema = 'public' and routine_name = 'append_event'
      and grantee = 'anon' and privilege_type = 'EXECUTE'),
    0, 'anon may not execute append_event');

-- RLS enabled on every Phase 1 table
select ok((select relrowsecurity from pg_class
    where relname = 'matches' and relnamespace = 'public'::regnamespace), 'matches RLS enabled');
select ok((select relrowsecurity from pg_class
    where relname = 'sessions' and relnamespace = 'public'::regnamespace), 'sessions RLS enabled');
select ok((select relrowsecurity from pg_class
    where relname = 'uploads' and relnamespace = 'public'::regnamespace), 'uploads RLS enabled');
select ok((select relrowsecurity from pg_class
    where relname = 'analysis_runs' and relnamespace = 'public'::regnamespace), 'analysis_runs RLS enabled');
select ok((select relrowsecurity from pg_class
    where relname = 'analysis_events' and relnamespace = 'public'::regnamespace), 'analysis_events RLS enabled');
select ok((select relrowsecurity from pg_class
    where relname = 'artifacts' and relnamespace = 'public'::regnamespace), 'artifacts RLS enabled');
select ok((select relrowsecurity from pg_class
    where relname = 'idempotency' and relnamespace = 'public'::regnamespace), 'idempotency RLS enabled');

select * from finish();
rollback;
