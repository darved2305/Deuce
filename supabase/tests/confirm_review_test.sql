begin;
select plan(20);
create temporary table b4b_confirm_response (result jsonb);
grant select, insert on b4b_confirm_response to service_role;

insert into auth.users (instance_id, id, aud, role, email, encrypted_password, email_confirmed_at, created_at, updated_at)
values
  ('00000000-0000-0000-0000-000000000000', '11111111-1111-1111-1111-111111111111',
   'authenticated', 'authenticated', 'p3-confirm-a@elevate.dev', '', now(), now(), now()),
  ('00000000-0000-0000-0000-000000000000', '22222222-2222-2222-2222-222222222222',
   'authenticated', 'authenticated', 'p3-confirm-b@elevate.dev', '', now(), now(), now());

insert into public.matches (id, owner_id, sport, format, ruleset_id, ruleset_version)
values
  ('m_confirm_a', '11111111-1111-1111-1111-111111111111', 'badminton', 'singles', 'bwf_3x21', '2025-04'),
  ('m_confirm_b', '22222222-2222-2222-2222-222222222222', 'badminton', 'singles', 'bwf_3x21', '2025-04');
insert into public.sessions (id, match_id, owner_id, mode, source, who_is_me)
values ('s_confirm_a', 'm_confirm_a', '11111111-1111-1111-1111-111111111111', 'match', '{}'::jsonb,
        '{"image_xy":[0,0],"t_ms":0}'::jsonb);
insert into public.analysis_runs (id, session_id, match_id, owner_id, idempotency_key)
values ('a_confirm_a', 's_confirm_a', 'm_confirm_a', '11111111-1111-1111-1111-111111111111', 'confirm');
insert into public.rallies (match_id, id, owner_id, analysis_id, index, start_ms, end_ms, server)
values
  ('m_confirm_a', 'r_confirm_1', '11111111-1111-1111-1111-111111111111', 'a_confirm_a', 1, 0, 1000, 'p_a'),
  ('m_confirm_a', 'r_confirm_2', '11111111-1111-1111-1111-111111111111', 'a_confirm_a', 2, 1000, 2000, 'p_b');
insert into public.reviews (match_id, id, owner_id, analysis_id, rally_id, t_ms, estimate)
values
  ('m_confirm_a', 'rv_confirm_1', '11111111-1111-1111-1111-111111111111', 'a_confirm_a', 'r_confirm_1', 900, 'IN'),
  ('m_confirm_a', 'rv_confirm_2', '11111111-1111-1111-1111-111111111111', 'a_confirm_a', 'r_confirm_2', 1900, 'OUT');

select ok(has_function_privilege(
  'service_role',
  'public.confirm_review(text,text,text,uuid,text,text,jsonb,jsonb)',
  'EXECUTE'
), 'service_role can execute confirm_review');
select ok(not has_function_privilege(
  'authenticated',
  'public.confirm_review(text,text,text,uuid,text,text,jsonb,jsonb)',
  'EXECUTE'
), 'authenticated cannot execute confirm_review');

set local role service_role;
set local request.jwt.claims = '{"role":"service_role"}';
insert into b4b_confirm_response (result)
select public.confirm_review(
  'm_confirm_a', 'rv_confirm_1', 'p_a', '11111111-1111-1111-1111-111111111111', 'p_me',
  'confirm-key',
  '{"timeline":[{"rally_id":"r_confirm_1","after":[1,0],"server_next":"p_a"}],"games":[]}'::jsonb,
  '[{"review_id":"rv_confirm_1","rally_id":"r_confirm_1","winner":"p_a"}]'::jsonb
);
select is((select result#>>'{point,winner}' from b4b_confirm_response),
          'p_a', 'RPC returns the confirmed winner');
select is((select result#>>'{point,by}' from b4b_confirm_response),
          'p_me', 'wire by is the mapped match player');
select ok((select (result->>'created')::boolean from b4b_confirm_response),
          'new confirmation is reported as created');
select is((select count(*)::int from public.confirmed_points where match_id = 'm_confirm_a'),
          1, 'confirmation inserts one confirmed_points row');
select is((select confirmed_by from public.confirmed_points where idempotency_key = 'confirm-key'),
          '11111111-1111-1111-1111-111111111111'::uuid, 'confirmed_points keeps the auth UUID audit actor');
select is((select confirmed_point_winner from public.reviews where id = 'rv_confirm_1'),
          'p_a', 'review winner is updated');
select is((select confirmed_by from public.reviews where id = 'rv_confirm_1'),
          'p_me', 'review by is the mapped player ID');
select ok((select confirmed_at is not null from public.reviews where id = 'rv_confirm_1'),
          'review confirmation timestamp is set');
select is((select score from public.matches where id = 'm_confirm_a'),
  '{"timeline":[{"rally_id":"r_confirm_1","after":[1,0],"server_next":"p_a"}],"games":[]}'::jsonb,
  'score is updated through the service-role RPC');
with first as materialized (
  select public.confirm_review(
    'm_confirm_a', 'rv_confirm_1', 'p_a', '11111111-1111-1111-1111-111111111111', 'p_me',
    'confirm-key',
    '{"timeline":[{"rally_id":"r_confirm_1","after":[1,0],"server_next":"p_a"}],"games":[]}'::jsonb,
    '[{"review_id":"rv_confirm_1","rally_id":"r_confirm_1","winner":"p_a"}]'::jsonb
  ) as result
), replay as materialized (
  select public.confirm_review(
    'm_confirm_a', 'rv_confirm_1', 'p_a', '11111111-1111-1111-1111-111111111111', 'p_me',
    'confirm-key', '{}'::jsonb,
    '[]'::jsonb
  ) as result from first
)
select is((select result from replay), (select result from first), 'same RPC key returns the same point');
select is((select count(*)::int from public.confirmed_points where match_id = 'm_confirm_a'),
          1, 'RPC replay does not insert another point');
select throws_ok($$select public.confirm_review(
    'm_confirm_a', 'rv_confirm_2', 'p_b', '11111111-1111-1111-1111-111111111111', 'p_me',
    'stale-key',
    '{"timeline":[{"rally_id":"r_confirm_1","after":[1,0],"server_next":"p_a"}],"games":[]}'::jsonb,
    '[{"review_id":"rv_confirm_2","rally_id":"r_confirm_2","winner":"p_b"}]'::jsonb
  )$$, 'P0001', 'score_stale', 'stale score snapshots are rejected before writes');
select is((select count(*)::int from public.confirmed_points where idempotency_key = 'stale-key'),
          0, 'stale score rejection inserts no point');
select is((select confirmed_point_winner from public.reviews where id = 'rv_confirm_2'),
          null, 'stale score rejection does not confirm the review');
reset role;
set local request.jwt.claims = '';

set local role authenticated;
set local request.jwt.claims = '{"sub":"22222222-2222-2222-2222-222222222222","role":"authenticated"}';
select is((select count(*)::int from public.matches where id = 'm_confirm_a'),
          0, 'second user cannot read the match through RLS');
select is((select count(*)::int from public.reviews where match_id = 'm_confirm_a'),
          0, 'second user cannot read the reviews through RLS');
select is((select count(*)::int from public.confirmed_points where match_id = 'm_confirm_a'),
          0, 'second user cannot read confirmed points through RLS');
reset role;
set local request.jwt.claims = '';
set local role authenticated;
set local request.jwt.claims = '{"sub":"11111111-1111-1111-1111-111111111111","role":"authenticated"}';
select is((select count(*)::int from public.confirmed_points where match_id = 'm_confirm_a'),
          1, 'owner can read the confirmed point');
reset role;

select * from finish();
rollback;
