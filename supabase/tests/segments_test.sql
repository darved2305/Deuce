begin;
select plan(23);

insert into auth.users (instance_id, id, aud, role, email, encrypted_password, email_confirmed_at, created_at, updated_at)
values ('00000000-0000-0000-0000-000000000000', '11111111-1111-1111-1111-111111111111',
        'authenticated', 'authenticated', 'p3-segments-a@elevate.dev', '', now(), now(), now());
insert into auth.users (instance_id, id, aud, role, email, encrypted_password, email_confirmed_at, created_at, updated_at)
values ('00000000-0000-0000-0000-000000000000', '22222222-2222-2222-2222-222222222222',
        'authenticated', 'authenticated', 'p3-segments-b@elevate.dev', '', now(), now(), now());
insert into public.matches (id, owner_id, sport, format, ruleset_id, ruleset_version)
values ('m_segments', '11111111-1111-1111-1111-111111111111', 'badminton', 'singles', 'bwf_singles', 'v1');
insert into public.sessions (id, match_id, owner_id, mode, source, who_is_me, upload_mode)
values ('s_segments', 'm_segments', '11111111-1111-1111-1111-111111111111', 'match', '{}'::jsonb,
        '{"image_xy":[0.5,0.5],"t_ms":0}'::jsonb, 'segments');

select ok((select relrowsecurity from pg_class where oid = 'public.segments'::regclass), 'segments RLS enabled');
select ok(not has_table_privilege('authenticated', 'public.segments', 'insert'), 'authenticated cannot insert segments');
select ok(not has_table_privilege('authenticated', 'public.segments', 'update'), 'authenticated cannot update segments');
select ok(not has_table_privilege('authenticated', 'public.segments', 'delete'), 'authenticated cannot delete segments');
select ok(not has_function_privilege('authenticated', 'public.register_segment(text,uuid,integer,bigint,bigint,boolean,bigint,text,boolean)', 'execute'), 'register RPC is service-only');
select ok(not has_function_privilege('authenticated', 'public.r2_take_segment_url(text,uuid,integer,integer)', 'execute'), 'URL quota RPC is service-only');

select is((public.register_segment('s_segments', '11111111-1111-1111-1111-111111111111', 0, 0, 1000000, true, 100, repeat('a', 64), false)).seq, 0, 'segment 0 registers');
select throws_ok(
  $$select public.register_segment('s_segments', '11111111-1111-1111-1111-111111111111', 2, 2000000, 3000000, true, 100, repeat('b', 64), false)$$,
  'P0001', 'segment_gap', 'sequence gaps are rejected');
select throws_ok(
  $$select public.register_segment('s_segments', '11111111-1111-1111-1111-111111111111', 1, 1000000, 2000000, true, 314572701, repeat('b', 64), true)$$,
  'P0001', 'upload_too_large', 'G1 counts all declared segment bytes');
select is((public.register_segment('s_segments', '11111111-1111-1111-1111-111111111111', 0, 0, 1000000, true, 100, repeat('a', 64), false)).seq, 0, 'register replay returns the existing segment');
select is((select count(*)::int from public.segments where session_id = 's_segments'), 1, 'register replay creates no duplicate');

select is(public.r2_take_segment_url('s_segments', '11111111-1111-1111-1111-111111111111', 0, 3), 1, 'first segment URL is allowed');
select is(public.r2_take_segment_url('s_segments', '11111111-1111-1111-1111-111111111111', 0, 3), 2, 'second segment URL is allowed');
select is(public.r2_take_segment_url('s_segments', '11111111-1111-1111-1111-111111111111', 0, 3), 3, 'third segment URL is allowed');
select is(public.r2_take_segment_url('s_segments', '11111111-1111-1111-1111-111111111111', 0, 3), null, 'fourth segment URL is refused');
select is((select class_a from public.r2_usage_monthly), 3::bigint, 'refused URL adds no Class A usage');

select is((public.register_segment('s_segments', '11111111-1111-1111-1111-111111111111', 1, 1000000, 2000000, true, 314572700, repeat('b', 64), true)).seq, 1, 'exact G1 byte cap is allowed');
select ok((select final from public.segments where session_id = 's_segments' and seq = 1), 'final flag is stored');
select throws_ok(
  $$select public.register_segment('s_segments', '11111111-1111-1111-1111-111111111111', 2, 2000000, 3000000, true, 1, repeat('c', 64), false)$$,
  'P0001', 'segment_finalized', 'no segment can follow the final segment');
select is((public.r2_budget_snapshot('11111111-1111-1111-1111-111111111111')->>'storage_bytes')::bigint, 314572800::bigint, 'budget snapshot counts registered segment bytes');

set role authenticated;
select set_config('request.jwt.claim.sub', '11111111-1111-1111-1111-111111111111', true);
select is((select count(*)::int from public.segments where session_id = 's_segments'), 2, 'owner A can read both segments');
reset role;
set role authenticated;
select set_config('request.jwt.claim.sub', '22222222-2222-2222-2222-222222222222', true);
select is((select count(*)::int from public.segments where session_id = 's_segments'), 0, 'user B cannot read owner A segments');
select throws_ok(
  $$insert into public.segments (session_id, owner_id, seq, pts_start_us, pts_end_us, starts_on_sync_sample, bytes, sha256, final, r2_key) values ('s_segments', '22222222-2222-2222-2222-222222222222', 0, 0, 1, true, 1, repeat('c', 64), false, 'seg/s_segments/0.mp4')$$,
  '42501', null, 'user B cannot write segments');
reset role;

select * from finish();
rollback;
