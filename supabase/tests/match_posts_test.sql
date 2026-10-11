begin;
select plan(30);

insert into auth.users (instance_id, id, aud, role, email, encrypted_password, email_confirmed_at, created_at, updated_at)
values ('00000000-0000-0000-0000-000000000000', '11111111-1111-4111-8111-111111111111', 'authenticated', 'authenticated', 'posts-a@elevate.dev', '', now(), now(), now()),
       ('00000000-0000-0000-0000-000000000000', '22222222-2222-4222-8222-222222222222', 'authenticated', 'authenticated', 'posts-b@elevate.dev', '', now(), now(), now()),
       ('00000000-0000-0000-0000-000000000000', '33333333-3333-4333-8333-333333333333', 'authenticated', 'authenticated', 'posts-c@elevate.dev', '', now(), now(), now());

insert into public.matches (id, owner_id, sport, format, ruleset_id, ruleset_version)
values ('m_posts_a', '11111111-1111-4111-8111-111111111111', 'badminton', 'singles', 'bwf_3x21', '2025-04');
insert into public.sessions (id, match_id, owner_id, mode, source, who_is_me)
values ('s_posts_a', 'm_posts_a', '11111111-1111-4111-8111-111111111111', 'match', '{"duration_ms":1200}'::jsonb, '{"image_xy":[0,0],"t_ms":0}'::jsonb);
insert into public.challenges (id, match_id, creator_id, creator_name, creator_score, creator_results, rounds, video_sha256)
values ('ch_posts_a', 'm_posts_a', '11111111-1111-4111-8111-111111111111', 'Owner A', 0, '[]'::jsonb, '[{},{},{}]'::jsonb, repeat('a', 64));
insert into public.challenge_entries (challenge_id, player_id, display_name)
values ('ch_posts_a', '22222222-2222-4222-8222-222222222222', 'Friend B');

create temp table post_ids (id text);
grant select, insert on post_ids to authenticated;

select ok((select relrowsecurity from pg_class where oid = 'public.posts'::regclass), 'posts RLS enabled');
select ok(has_table_privilege('authenticated', 'public.posts', 'select')
  and has_table_privilege('authenticated', 'public.posts', 'insert')
  and has_table_privilege('authenticated', 'public.posts', 'delete'), 'authenticated has select, insert and delete privileges');
select ok(not has_table_privilege('authenticated', 'public.posts', 'update'), 'authenticated cannot update posts');
select ok(not has_function_privilege('anon', 'private.is_friend(uuid)', 'execute'), 'anon cannot execute the friend helper');

set local role authenticated;
select set_config('request.jwt.claim.sub', '11111111-1111-4111-8111-111111111111', true);
select is((select count(*)::int from public.posts), 0, 'owner starts with no posts');
select lives_ok($$select public.create_match_post('m_posts_a', 'same caption')$$, 'owner creates a post');
insert into post_ids select public.create_match_post('m_posts_a', 'same caption');
insert into post_ids select public.create_match_post('m_posts_a', 'same caption');
select is((select count(distinct id)::int from post_ids), 1, 'same match and caption deduplicates within 60 seconds');
select is((select count(*)::int from public.posts), 1, 'deduplicated create stores one row');
select is((select summary->>'title' from public.posts where caption = 'same caption'), null::text, 'missing match title stays null');
select is((select summary->>'duration_ms' from public.posts where caption = 'same caption'), '1200', 'duration comes from the stored session');
select is((select summary->>'rally_count' from public.posts where caption = 'same caption'), null::text, 'unavailable rally count stays null');
select is((select summary->>'owner_display_name' from public.posts where caption = 'same caption'), 'Owner A', 'owner name comes from the challenge relation');
select is((select summary->'top_coach_points' from public.posts where caption = 'same caption'), '[]'::jsonb, 'missing insights do not create coach points');
select is((select summary->'landing_points_m' from public.posts where caption = 'same caption'), '[]'::jsonb, 'missing court landings do not create points');
select lives_ok($$insert into public.posts (owner_id, match_id, caption, summary)
  values ('11111111-1111-4111-8111-111111111111', 'm_posts_a', 'direct',
    '{"title":"client value","duration_ms":99999,"rally_count":999,"hit_count":999,"landings_in":999,"landings_out":999,"longest_rally_ms":999,"top_coach_points":["client"],"landing_points_m":[{"x_m":999,"y_m":999}],"owner_display_name":"client"}'::jsonb)$$,
  'owner insert overwrites client summary values');
select is((select summary->>'title' from public.posts where caption = 'direct'), null::text, 'client title is not stored');
select is((select summary->>'duration_ms' from public.posts where caption = 'direct'), '1200', 'client duration is not stored');
select lives_ok($$delete from public.posts where caption = 'direct'$$, 'owner deletes own post');
select is((select count(*)::int from public.posts), 1, 'owner delete removes the direct post');
reset role;

set local role authenticated;
select set_config('request.jwt.claim.sub', '22222222-2222-4222-8222-222222222222', true);
select is((select count(*)::int from public.posts where caption = 'same caption'), 1, 'friend reads the owner post');
select throws_ok($$insert into public.posts (owner_id, match_id, caption, summary)
  values ('11111111-1111-4111-8111-111111111111', 'm_posts_a', 'spoof', '{}'::jsonb)$$,
  '42501', 'post_owner_mismatch', 'friend cannot insert as the owner');
select lives_ok($$delete from public.posts where caption = 'same caption'$$, 'friend delete request is filtered by RLS');
select is((select count(*)::int from public.posts where caption = 'same caption'), 1, 'friend cannot delete the owner post');
reset role;

set local role authenticated;
select set_config('request.jwt.claim.sub', '33333333-3333-4333-8333-333333333333', true);
select is((select count(*)::int from public.posts), 0, 'non-friend sees no posts');
reset role;

set local role authenticated;
select set_config('request.jwt.claim.sub', '11111111-1111-4111-8111-111111111111', true);
select lives_ok($$delete from public.posts where caption = 'same caption'$$, 'owner deletes the post');
select is((select count(*)::int from public.posts), 0, 'deleted post is absent for its owner');
reset role;
set local role authenticated;
select set_config('request.jwt.claim.sub', '22222222-2222-4222-8222-222222222222', true);
select is((select count(*)::int from public.posts), 0, 'deleted post is hidden from the friend feed');
reset role;

set local role authenticated;
select set_config('request.jwt.claim.sub', '11111111-1111-4111-8111-111111111111', true);
select lives_ok($$select public.create_match_post('m_posts_a', 'daily-' || n::text) from generate_series(1, 20) n$$,
  'owner can create 20 posts in a UTC day');
select is((select count(*)::int from public.posts), 20, 'G12 stores at most 20 posts per owner per day');
select throws_ok($$select public.create_match_post('m_posts_a', 'daily-over')$$,
  'P0001', 'daily_post_limit', 'the 21st post is refused by the create RPC');
reset role;

select * from finish();
rollback;
