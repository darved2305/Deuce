-- pgTAP for B1 hardening: score honesty trigger, provenance checks, share-grant owner check.
begin;
select plan(9);

insert into auth.users (instance_id, id, aud, role, email, encrypted_password, email_confirmed_at, created_at, updated_at)
values
  ('00000000-0000-0000-0000-000000000000', '11111111-1111-1111-1111-111111111111',
   'authenticated', 'authenticated', 'p3-b1-a@elevate.dev', '', now(), now(), now()),
  ('00000000-0000-0000-0000-000000000000', '22222222-2222-2222-2222-222222222222',
   'authenticated', 'authenticated', 'p3-b1-b@elevate.dev', '', now(), now(), now());

insert into public.matches (id, owner_id, sport, format, ruleset_id, ruleset_version)
values
  ('m_b1', '11111111-1111-1111-1111-111111111111', 'badminton', 'singles', 'bwf_singles', 'v1'),
  ('m_b1_share', '11111111-1111-1111-1111-111111111111', 'badminton', 'singles', 'bwf_singles', 'v1'),
  ('m_b1_ok', '11111111-1111-1111-1111-111111111111', 'badminton', 'singles', 'bwf_singles', 'v1');
insert into public.sessions (id, match_id, owner_id, mode, source, who_is_me)
values ('s_b1', 'm_b1', '11111111-1111-1111-1111-111111111111', 'match', '{}'::jsonb,
        '{"image_xy":[0,0],"t_ms":0}'::jsonb);
insert into public.analysis_runs (id, session_id, match_id, owner_id, idempotency_key)
values ('a_b1', 's_b1', 'm_b1', '11111111-1111-1111-1111-111111111111', 'b1');

-- grant owner_id is not the match owner: must not count
insert into public.share_grants (id, match_id, owner_id, grantee_id, scope)
values ('sg_b1_bad', 'm_b1_share', '22222222-2222-2222-2222-222222222222',
        '22222222-2222-2222-2222-222222222222', 'read');
-- grant owner_id is the match owner: counts
insert into public.share_grants (id, match_id, owner_id, grantee_id, scope)
values ('sg_b1_ok', 'm_b1_ok', '11111111-1111-1111-1111-111111111111',
        '22222222-2222-2222-2222-222222222222', 'read');

-- seed a score through the only writer allowed: the service role claim
set local request.jwt.claims = '{"role":"service_role"}';
update public.matches set score = '{"a":1}'::jsonb where id = 'm_b1';
set local request.jwt.claims = '';

set local role authenticated;
set local request.jwt.claims = '{"sub":"11111111-1111-1111-1111-111111111111","role":"authenticated"}';
insert into public.matches (id, owner_id, sport, format, ruleset_id, ruleset_version, score)
values ('m_b1_auth', '11111111-1111-1111-1111-111111111111', 'badminton', 'singles',
        'bwf_singles', 'v1', '{"a":9}'::jsonb);
select ok((select score is null from public.matches where id = 'm_b1_auth'),
  'authenticated insert with a score gets score null');
update public.matches set score = '{"a":2}'::jsonb where id = 'm_b1';
select is((select score from public.matches where id = 'm_b1'), '{"a":1}'::jsonb,
  'authenticated update cannot change score');
reset role;

-- privileged writer carrying an authenticated claim still cannot move score
update public.matches set score = '{"a":2}'::jsonb where id = 'm_b1';
select is((select score from public.matches where id = 'm_b1'), '{"a":1}'::jsonb,
  'non-service update keeps the old score');
-- service role is the only score writer
set local request.jwt.claims = '{"role":"service_role"}';
update public.matches set score = '{"a":3}'::jsonb where id = 'm_b1';
select is((select score from public.matches where id = 'm_b1'), '{"a":3}'::jsonb,
  'service_role can write matches.score');
set local request.jwt.claims = '';

select throws_ok($$insert into public.rallies (match_id,id,owner_id,analysis_id,index,start_ms,end_ms,state)
  values ('m_b1','r_b1_bad','11111111-1111-1111-1111-111111111111','a_b1',1,0,1,'guess')$$,
  '23514', null, 'invalid rallies.state rejected');
select lives_ok($$insert into public.rallies (match_id,id,owner_id,analysis_id,index,start_ms,end_ms,state)
  values ('m_b1','r_b1_user','11111111-1111-1111-1111-111111111111','a_b1',2,0,1,'user')$$,
  'valid rallies.state user accepted');
select throws_ok($$insert into public.shots (match_id,id,owner_id,analysis_id,rally_id,index,player_id,
    contact_ms,phase,roles,incoming,chosen,context,outcome,quality,flight_time_state)
  values ('m_b1','sh_b1_bad','11111111-1111-1111-1111-111111111111','a_b1','r_b1_user',1,'p_1',1,
    'serve','{}','{}','{}','{}','{}','{}','guess')$$,
  '23514', null, 'invalid shots.flight_time_state rejected');

set local role authenticated;
set local request.jwt.claims = '{"sub":"22222222-2222-2222-2222-222222222222","role":"authenticated"}';
select is((select private.has_share_grant('m_b1_share')), false,
  'has_share_grant false when grant owner_id does not own the match');
select is((select private.has_share_grant('m_b1_ok')), true,
  'has_share_grant true when grant owner_id owns the match');
reset role;

select * from finish();
rollback;
