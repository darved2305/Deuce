-- pgTAP for B1b confirmation, corrections, consents, claims, developer keys, and score.
begin;
select plan(45);

insert into auth.users (instance_id, id, aud, role, email, encrypted_password, email_confirmed_at, created_at, updated_at)
values
  ('00000000-0000-0000-0000-000000000000', '11111111-1111-1111-1111-111111111111',
   'authenticated', 'authenticated', 'p3-control-a@elevate.dev', '', now(), now(), now()),
  ('00000000-0000-0000-0000-000000000000', '22222222-2222-2222-2222-222222222222',
   'authenticated', 'authenticated', 'p3-control-b@elevate.dev', '', now(), now(), now());

insert into public.matches (id, owner_id, sport, format, ruleset_id, ruleset_version)
values
  ('m_control_a', '11111111-1111-1111-1111-111111111111', 'badminton', 'singles', 'bwf_singles', 'v1'),
  ('m_control_b', '22222222-2222-2222-2222-222222222222', 'badminton', 'singles', 'bwf_singles', 'v1');

insert into public.confirmed_points
  (id, match_id, owner_id, rally_id, winner, confirmed_by, idempotency_key)
values ('cp_fixture', 'm_control_a', '11111111-1111-1111-1111-111111111111', 'r_fixture', 'p1',
        '11111111-1111-1111-1111-111111111111', 'fixture-key');
insert into public.corrections
  (id, match_id, owner_id, target_type, target_id, field, actor)
values ('cr_fixture', 'm_control_a', '11111111-1111-1111-1111-111111111111', 'event', 'ev_fixture', 'winner',
        '11111111-1111-1111-1111-111111111111');
insert into public.consents (id, match_id, owner_id, subject, scope, granted_by)
values ('cs_fixture', 'm_control_a', '11111111-1111-1111-1111-111111111111', 'player_1', 'clip_compare',
        '11111111-1111-1111-1111-111111111111');
insert into public.claims (id, owner_id, player_id, pattern)
values ('cl_fixture', '11111111-1111-1111-1111-111111111111', 'player_1', 'fixture-pattern');
insert into public.developer_keys (id, owner_id, key_hash)
values ('k_fixture', '11111111-1111-1111-1111-111111111111', 'fixture-hash');

select ok((select relrowsecurity from pg_class
  where relname = 'confirmed_points' and relnamespace = 'public'::regnamespace), 'confirmed_points RLS enabled');
select ok((select relrowsecurity from pg_class
  where relname = 'corrections' and relnamespace = 'public'::regnamespace), 'corrections RLS enabled');
select ok((select relrowsecurity from pg_class
  where relname = 'consents' and relnamespace = 'public'::regnamespace), 'consents RLS enabled');
select ok((select relrowsecurity from pg_class
  where relname = 'claims' and relnamespace = 'public'::regnamespace), 'claims RLS enabled');
select ok((select relrowsecurity from pg_class
  where relname = 'developer_keys' and relnamespace = 'public'::regnamespace), 'developer_keys RLS enabled');

select has_index('confirmed_points', 'confirmed_points_owner_id_idx', 'confirmed_points.owner_id indexed');
select has_index('confirmed_points', 'confirmed_points_match_id_idx', 'confirmed_points.match_id indexed');
select has_index('corrections', 'corrections_owner_id_idx', 'corrections.owner_id indexed');
select has_index('corrections', 'corrections_match_id_idx', 'corrections.match_id indexed');
select has_index('consents', 'consents_owner_id_idx', 'consents.owner_id indexed');
select has_index('consents', 'consents_match_id_idx', 'consents.match_id indexed');
select has_index('claims', 'claims_owner_id_idx', 'claims.owner_id indexed');
select has_index('claims', 'claims_player_id_idx', 'claims.player_id indexed');
select has_index('developer_keys', 'developer_keys_owner_id_idx', 'developer_keys.owner_id indexed');

select ok(exists (select 1 from information_schema.columns
  where table_schema = 'public' and table_name = 'matches' and column_name = 'score'), 'matches.score exists');
select is((select format_type(a.atttypid, a.atttypmod)
  from pg_attribute a where a.attrelid = 'public.matches'::regclass and a.attname = 'score' and not a.attisdropped),
  'jsonb', 'matches.score is jsonb');
select is((select is_nullable from information_schema.columns
  where table_schema = 'public' and table_name = 'matches' and column_name = 'score'),
  'YES', 'matches.score is nullable');
select ok((select column_default is null from information_schema.columns
  where table_schema = 'public' and table_name = 'matches' and column_name = 'score'),
  'matches.score has no default');

set local role authenticated;
select set_config('request.jwt.claim.sub', '11111111-1111-1111-1111-111111111111', true);
select is((select count(*)::int from public.confirmed_points), 1, 'owner A can select own confirmed points');
select is((select count(*)::int from public.corrections), 1, 'owner A can select own corrections');
select is((select count(*)::int from public.consents), 1, 'owner A can select own consents');
select is((select count(*)::int from public.claims), 1, 'owner A can select own claims');
select is((select count(*)::int from public.developer_keys), 1, 'owner A can select own developer keys');

select lives_ok($$insert into public.corrections
  (id, match_id, owner_id, target_type, target_id, field, actor)
  values ('cr_owner_insert', 'm_control_a', '11111111-1111-1111-1111-111111111111',
          'event', 'ev_owner', 'winner', '11111111-1111-1111-1111-111111111111')$$,
  'owner A can insert a correction for own match as actor A');
select lives_ok($$insert into public.confirmed_points
  (id, match_id, owner_id, rally_id, winner, confirmed_by, idempotency_key)
  values ('cp_owner_insert', 'm_control_a', '11111111-1111-1111-1111-111111111111',
          'r_owner', 'p1', '11111111-1111-1111-1111-111111111111', 'owner-key')$$,
  'owner A can insert a confirmed point for own match as confirmer A');
select throws_ok($$insert into public.corrections
  (id, match_id, owner_id, target_type, target_id, field, actor)
  values ('cr_other_match', 'm_control_b', '11111111-1111-1111-1111-111111111111',
          'event', 'ev_other', 'winner', '11111111-1111-1111-1111-111111111111')$$,
  '42501', NULL, 'owner A cannot insert a correction for owner B match');
select throws_ok($$insert into public.confirmed_points
  (id, match_id, owner_id, rally_id, winner, confirmed_by, idempotency_key)
  values ('cp_other_match', 'm_control_b', '11111111-1111-1111-1111-111111111111',
          'r_other', 'p1', '11111111-1111-1111-1111-111111111111', 'other-match-key')$$,
  '42501', NULL, 'owner A cannot insert a confirmed point for owner B match');
select throws_ok($$insert into public.corrections
  (id, match_id, owner_id, target_type, target_id, field, actor)
  values ('cr_other_actor', 'm_control_a', '11111111-1111-1111-1111-111111111111',
          'event', 'ev_actor', 'winner', '22222222-2222-2222-2222-222222222222')$$,
  '42501', NULL, 'owner A cannot insert a correction with actor B');
select throws_ok($$insert into public.confirmed_points
  (id, match_id, owner_id, rally_id, winner, confirmed_by, idempotency_key)
  values ('cp_other_actor', 'm_control_a', '11111111-1111-1111-1111-111111111111',
          'r_actor', 'p1', '22222222-2222-2222-2222-222222222222', 'other-actor-key')$$,
  '42501', NULL, 'owner A cannot insert a confirmed point with confirmer B');
select throws_ok($$insert into public.confirmed_points
  (id, match_id, owner_id, rally_id, winner, confirmed_by, idempotency_key)
  values ('cp_duplicate', 'm_control_a', '11111111-1111-1111-1111-111111111111',
          'r_duplicate', 'p2', '11111111-1111-1111-1111-111111111111', 'owner-key')$$,
  '23505', NULL, 'duplicate confirmed point idempotency key is rejected');

select throws_ok($$update public.corrections set reason = 'edited' where id = 'cr_owner_insert'$$,
  '42501', NULL, 'authenticated cannot update corrections');
select throws_ok($$delete from public.corrections where id = 'cr_owner_insert'$$,
  '42501', NULL, 'authenticated cannot delete corrections');
select throws_ok($$update public.confirmed_points set winner = 'p2' where id = 'cp_owner_insert'$$,
  '42501', NULL, 'authenticated cannot update confirmed points');
select throws_ok($$delete from public.confirmed_points where id = 'cp_owner_insert'$$,
  '42501', NULL, 'authenticated cannot delete confirmed points');
select throws_ok($$insert into public.consents (match_id, owner_id, subject, scope, granted_by)
  values ('m_control_a', '11111111-1111-1111-1111-111111111111', 'player_1', 'clip_compare',
          '11111111-1111-1111-1111-111111111111')$$,
  '42501', NULL, 'authenticated cannot insert consents');
select throws_ok($$insert into public.claims (owner_id, player_id, pattern)
  values ('11111111-1111-1111-1111-111111111111', 'player_1', 'new-pattern')$$,
  '42501', NULL, 'authenticated cannot insert claims');
select throws_ok($$insert into public.developer_keys (owner_id, key_hash)
  values ('11111111-1111-1111-1111-111111111111', 'auth-hash')$$,
  '42501', NULL, 'authenticated cannot insert developer keys');
select throws_ok($$select key_hash from public.developer_keys where id = 'k_fixture'$$,
  '42501', NULL, 'authenticated cannot select developer key hashes');
reset role;

set local role authenticated;
select set_config('request.jwt.claim.sub', '22222222-2222-2222-2222-222222222222', true);
select is((select count(*)::int from public.confirmed_points), 0, 'owner B cannot see owner A confirmed points');
select is((select count(*)::int from public.corrections), 0, 'owner B cannot see owner A corrections');
select is((select count(*)::int from public.consents), 0, 'owner B cannot see owner A consents');
select is((select count(*)::int from public.claims), 0, 'owner B cannot see owner A claims');
select is((select count(*)::int from public.developer_keys), 0, 'owner B cannot see owner A developer keys');
reset role;

set local role service_role;
select throws_ok($$insert into public.developer_keys (id, owner_id, key_hash, scope)
  values ('k_write_scope', '11111111-1111-1111-1111-111111111111', 'write-scope-hash', 'write')$$,
  '23514', NULL, 'developer key scope write is rejected by CHECK');
select throws_ok($$insert into public.consents (id, match_id, owner_id, subject, scope, granted_by)
  values ('cs_other_scope', 'm_control_a', '11111111-1111-1111-1111-111111111111',
          'player_1', 'other', '11111111-1111-1111-1111-111111111111')$$,
  '23514', NULL, 'consent scope other is rejected by CHECK');
reset role;

select * from finish();
rollback;
