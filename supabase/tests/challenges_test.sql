-- pgTAP for friends and challenges: RLS (creator and participants only), accept creates the match
-- share grant, entries are scored once, rematches stay inside the old challenge.
begin;
select plan(46);

insert into auth.users (instance_id, id, aud, role, email, encrypted_password, email_confirmed_at, created_at, updated_at)
values ('00000000-0000-0000-0000-000000000000', '11111111-1111-1111-1111-111111111111', 'authenticated', 'authenticated', 'ch-a@elevate.dev', '', now(), now(), now()),
       ('00000000-0000-0000-0000-000000000000', '22222222-2222-2222-2222-222222222222', 'authenticated', 'authenticated', 'ch-b@elevate.dev', '', now(), now(), now()),
       ('00000000-0000-0000-0000-000000000000', '33333333-3333-3333-3333-333333333333', 'authenticated', 'authenticated', 'ch-c@elevate.dev', '', now(), now(), now());

insert into public.matches (id, owner_id, sport, format, ruleset_id, ruleset_version)
values ('m_ch_1', '11111111-1111-1111-1111-111111111111', 'badminton', 'singles', 'bwf_3x21', '2025-04');
insert into public.sessions (id, match_id, owner_id, mode, source, who_is_me)
values ('s_ch_1', 'm_ch_1', '11111111-1111-1111-1111-111111111111', 'match',
        jsonb_build_object('sha256', repeat('b', 64)), '{"image_xy":[1,1],"t_ms":0}'::jsonb);
insert into public.uploads (session_id, owner_id, r2_key, r2_upload_id, bytes, sha256, status)
values ('s_ch_1', '11111111-1111-1111-1111-111111111111', 'orig/s_ch_1/source.mp4', 'u', 1, repeat('c', 64), 'completed');
insert into public.analysis_runs (id, session_id, match_id, owner_id, idempotency_key, state)
values ('a_ch_1', 's_ch_1', 'm_ch_1', '11111111-1111-1111-1111-111111111111', 'k', 'completed');
insert into public.vision_events (match_id, id, owner_id, analysis_id, type, t_ms, state)
select 'm_ch_1', 'e_' || n, '11111111-1111-1111-1111-111111111111', 'a_ch_1',
       case when n % 2 = 1 then 'hit' else 'landing' end, n * 1000, 'observed'
  from generate_series(1, 8) n;

create temp table fx (rounds jsonb, results jsonb);
insert into fx select
  (select jsonb_agg(jsonb_build_object('hit_event_id', 'e_' || n, 'landing_event_id', 'e_' || (n + 1),
     'contact_ms', n * 1000, 'contact_sigma_ms', 8, 'ladder_us', jsonb_build_array(n * 1000000 - 250000)))
    from generate_series(1, 7, 2) n),
  (select jsonb_agg(jsonb_build_object('hit_event_id', 'e_' || n, 'pick', 'B2', 'reaction_ms', 500,
     'verdict', 'exact', 'points', 120)) from generate_series(1, 5, 2) n);
grant select on fx to authenticated, service_role;

select ok((select relrowsecurity from pg_class where relname = 'challenges' and relnamespace = 'public'::regnamespace), 'challenges RLS enabled');
select ok((select relrowsecurity from pg_class where relname = 'challenge_entries' and relnamespace = 'public'::regnamespace), 'challenge_entries RLS enabled');

-- create: owner only, hash from the upload, known events, three or more rounds is a contract rule
select lives_ok($$select public.create_challenge('11111111-1111-1111-1111-111111111111', 'm_ch_1', 'Hetansh',
  (select rounds from fx), 360, (select results from fx), null)$$, 'owner creates a challenge');
select is((select count(*)::int from public.challenges where match_id = 'm_ch_1'), 1, 'one challenge row');
select is((select video_sha256 from public.challenges), repeat('c', 64), 'video hash comes from the upload, not the app');
select is((select status from public.challenges), 'open', 'new challenge is open');
select throws_ok($$select public.create_challenge('22222222-2222-2222-2222-222222222222', 'm_ch_1', 'B',
  (select rounds from fx), 360, (select results from fx), null)$$, 'P0002', 'match_not_found',
  'a non-owner cannot challenge on someone else''s match');
select throws_ok($$select public.create_challenge('11111111-1111-1111-1111-111111111111', 'm_ch_1', 'A',
  '[{"hit_event_id":"e_999","landing_event_id":"e_2","contact_ms":1,"contact_sigma_ms":1,"ladder_us":[1]},
    {"hit_event_id":"e_1","landing_event_id":"e_2","contact_ms":1,"contact_sigma_ms":1,"ladder_us":[1]},
    {"hit_event_id":"e_3","landing_event_id":"e_4","contact_ms":1,"contact_sigma_ms":1,"ladder_us":[1]}]'::jsonb,
  0, '[]'::jsonb, null)$$, 'P0001', 'unknown_event', 'a round on an event the match does not have is refused');

-- RLS: before anyone accepts, only the creator reads it
create temp table ids as select id from public.challenges;
grant select on ids to authenticated, service_role;
set role authenticated;
select set_config('request.jwt.claim.sub', '11111111-1111-1111-1111-111111111111', true);
select is((select count(*)::int from public.challenges), 1, 'creator reads own challenge');
select is((select get_challenge_v1((select id from ids))->'viewer'->>'role'), 'creator', 'creator view says creator');
select is((select jsonb_array_length(get_challenge_v1((select id from ids))->'rounds')), 4, 'view carries the exact freeze set');
reset role;
set role authenticated;
select set_config('request.jwt.claim.sub', '22222222-2222-2222-2222-222222222222', true);
select is((select count(*)::int from public.challenges), 0, 'a non-participant cannot read the challenge');
select is((select get_challenge_v1((select id from ids))), null, 'a non-participant gets no view');
select is((select count(*)::int from public.matches where id = 'm_ch_1'), 0, 'friend cannot read the match before accepting');
select throws_ok($$insert into public.challenges (match_id, creator_id, creator_name, creator_score, creator_results, rounds, video_sha256)
  values ('m_ch_1', '22222222-2222-2222-2222-222222222222', 'x', 1, '[]', '[]', repeat('a', 64))$$, '42501', null,
  'the app cannot write challenges directly');
reset role;
select throws_ok($$select public.accept_challenge('11111111-1111-1111-1111-111111111111', (select id from ids), 'Me')$$,
  'P0001', 'own_challenge', 'the creator cannot accept their own challenge');
select throws_ok($$select public.accept_challenge('22222222-2222-2222-2222-222222222222', 'ch_nope', 'B')$$,
  'P0002', 'challenge_not_found', 'accepting an unknown challenge fails');

-- accept: entry and match-scope share grant
select lives_ok($$select public.accept_challenge('22222222-2222-2222-2222-222222222222', (select id from ids), 'Asha')$$, 'friend accepts');
select lives_ok($$select public.accept_challenge('22222222-2222-2222-2222-222222222222', (select id from ids), 'Asha')$$, 'accept is idempotent');
select is((select count(*)::int from public.challenge_entries), 1, 'one entry after accepting twice');
select is((select count(*)::int from public.share_grants where match_id = 'm_ch_1'
   and grantee_id = '22222222-2222-2222-2222-222222222222' and scope = 'read' and revoked_at is null
   and owner_id = '11111111-1111-1111-1111-111111111111'), 1, 'accept created the read grant on the match');
set role authenticated;
select set_config('request.jwt.claim.sub', '22222222-2222-2222-2222-222222222222', true);
select is((select count(*)::int from public.matches where id = 'm_ch_1'), 1, 'friend reads the match through the grant');
select is((select count(*)::int from public.vision_events where match_id = 'm_ch_1'), 8, 'friend reads the match events through the grant');
select is((select count(*)::int from public.challenges), 1, 'participant reads the challenge');
select is((select get_challenge_v1((select id from ids))->'viewer'->>'role'), 'opponent', 'opponent view says opponent');
select is((select get_challenge_v1((select id from ids))->'entries'->0->>'score'), null, 'not played yet: score is null');
reset role;

-- a third person sees nothing, and gets no grant by reading
set role authenticated;
select set_config('request.jwt.claim.sub', '33333333-3333-3333-3333-333333333333', true);
select is((select count(*)::int from public.challenges), 0, 'third person cannot read the challenge');
select is((select count(*)::int from public.challenge_entries), 0, 'third person cannot read entries');
select is((select count(*)::int from public.matches where id = 'm_ch_1'), 0, 'third person cannot read the match');
reset role;

-- entries: only participants, scored once
select throws_ok($$select public.submit_challenge_entry('33333333-3333-3333-3333-333333333333', (select id from ids), 'C', 100, (select results from fx))$$,
  'P0002', 'not_participant', 'a non-participant cannot post an entry');
select throws_ok($$select public.submit_challenge_entry('22222222-2222-2222-2222-222222222222', (select id from ids), 'Asha', 100,
  '[{"hit_event_id":"e_6","pick":null,"reaction_ms":null,"verdict":"too_slow","points":0}]'::jsonb)$$,
  'P0001', 'unknown_event', 'a result for a shot outside the freeze set is refused');
select lives_ok($$select public.submit_challenge_entry('22222222-2222-2222-2222-222222222222', (select id from ids), 'Asha', 100,
  (select results from fx))$$, 'friend posts a score');
select is((select status from public.challenges), 'played', 'challenge is played once an entry has a score');
select lives_ok($$select public.submit_challenge_entry('22222222-2222-2222-2222-222222222222', (select id from ids), 'Asha', 100,
  (select results from fx))$$, 're-posting the same run is fine');
select throws_ok($$select public.submit_challenge_entry('22222222-2222-2222-2222-222222222222', (select id from ids), 'Asha', 999,
  (select results from fx))$$, 'P0001', 'already_played', 'a different score after playing is refused');

-- views and friends from each side
set role authenticated;
select set_config('request.jwt.claim.sub', '11111111-1111-1111-1111-111111111111', true);
select is((select get_challenge_v1((select id from ids))->'entries'->0->>'outcome'), 'creator', 'creator 360 beats 100');
select is((select jsonb_array_length(public.list_friends_v1())), 1, 'creator has one friend');
select is((select public.list_friends_v1()->0->>'display_name'), 'Asha', 'friend name is the one sent with the entry');
select is((select (public.list_friends_v1()->0) - 'player_id' - 'last_challenge_at'),
  '{"wins":1,"ties":0,"played":1,"losses":0,"challenges":1,"display_name":"Asha"}'::jsonb, 'creator record 1-0');
reset role;
set role authenticated;
select set_config('request.jwt.claim.sub', '22222222-2222-2222-2222-222222222222', true);
select is((select (public.list_friends_v1()->0) - 'player_id' - 'last_challenge_at'),
  '{"wins":0,"ties":0,"played":1,"losses":1,"challenges":1,"display_name":"Hetansh"}'::jsonb, 'friend record 0-1 from their side');
reset role;

-- rematch: only people already in the old challenge, same match and rounds
select throws_ok($$select public.create_challenge('33333333-3333-3333-3333-333333333333', 'm_ch_1', 'C',
  (select rounds from fx), 10, (select results from fx), (select id from ids))$$, 'P0001', 'rematch_forbidden',
  'a stranger cannot start a rematch');
select throws_ok($$select public.create_challenge('22222222-2222-2222-2222-222222222222', 'm_ch_1', 'Asha',
  (select jsonb_build_array(rounds->0, rounds->1, rounds->2) from fx), 10, (select results from fx), (select id from ids))$$,
  'P0001', 'rounds_mismatch', 'a rematch plays the same rounds');
select lives_ok($$select public.create_challenge('22222222-2222-2222-2222-222222222222', 'm_ch_1', 'Asha',
  (select rounds from fx), 400, (select results from fx), (select id from ids))$$, 'the opponent starts a rematch');
select throws_ok($$select public.accept_challenge('33333333-3333-3333-3333-333333333333',
  (select id from public.challenges where rematch_of is not null), 'C')$$, 'P0002', 'challenge_not_found',
  'a stranger cannot accept a rematch (no new access to the match)');
select lives_ok($$select public.accept_challenge('11111111-1111-1111-1111-111111111111',
  (select id from public.challenges where rematch_of is not null), 'Hetansh')$$, 'the match owner accepts the rematch');
select is((select count(*)::int from public.share_grants where match_id = 'm_ch_1'), 1, 'the owner needs no grant on their own match');

select * from finish();
rollback;
