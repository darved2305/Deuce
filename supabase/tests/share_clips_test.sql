-- pgTAP for share grants, moments, clips, and reference examples.
begin;
select plan(42);

-- fixtures (test role bypasses RLS for setup)
insert into auth.users (instance_id, id, aud, role, email, encrypted_password, email_confirmed_at, created_at, updated_at)
values ('00000000-0000-0000-0000-000000000000', '11111111-1111-1111-1111-111111111111',
        'authenticated', 'authenticated', 'p3-share-a@elevate.dev', '', now(), now(), now());
insert into auth.users (instance_id, id, aud, role, email, encrypted_password, email_confirmed_at, created_at, updated_at)
values ('00000000-0000-0000-0000-000000000000', '22222222-2222-2222-2222-222222222222',
        'authenticated', 'authenticated', 'p3-share-b@elevate.dev', '', now(), now(), now());

insert into public.matches (id, owner_id, sport, format, ruleset_id, ruleset_version)
values ('m_share_1', '11111111-1111-1111-1111-111111111111', 'badminton', 'singles', 'bwf_singles', 'v1'),
       ('m_share_2', '11111111-1111-1111-1111-111111111111', 'badminton', 'singles', 'bwf_singles', 'v1');
insert into public.clips (id, match_id, owner_id, source, rights, start_ms, end_ms)
values ('c_share_1', 'm_share_1', '11111111-1111-1111-1111-111111111111', 'own', 'own', 100, 200),
       ('c_share_2', 'm_share_2', '11111111-1111-1111-1111-111111111111', 'own', 'own', 300, 400),
       ('c_delete', 'm_share_1', '11111111-1111-1111-1111-111111111111', 'own', 'own', 500, 600);
insert into public.moments (id, match_id, owner_id, start_ms, end_ms)
values ('mo_share_1', 'm_share_1', '11111111-1111-1111-1111-111111111111', 100, 200),
       ('mo_share_2', 'm_share_2', '11111111-1111-1111-1111-111111111111', 300, 400);
insert into public.reference_examples (id, owner_id, kind, clip_id, sport, shot, rights)
values ('rx_share', '11111111-1111-1111-1111-111111111111', 'team_reference', 'c_delete',
        'badminton', '{}'::jsonb, 'team-consented');

select ok((select relrowsecurity from pg_class
    where relname = 'share_grants' and relnamespace = 'public'::regnamespace), 'share_grants RLS enabled');
select ok((select relrowsecurity from pg_class
    where relname = 'moments' and relnamespace = 'public'::regnamespace), 'moments RLS enabled');
select ok((select relrowsecurity from pg_class
    where relname = 'clips' and relnamespace = 'public'::regnamespace), 'clips RLS enabled');
select ok((select relrowsecurity from pg_class
    where relname = 'reference_examples' and relnamespace = 'public'::regnamespace), 'reference_examples RLS enabled');

set role authenticated;
select set_config('request.jwt.claim.sub', '11111111-1111-1111-1111-111111111111', true);
select is((select count(*)::int from public.clips where id = 'c_share_1'), 1,
    'owner A can select own clip');
select is((select count(*)::int from public.moments where id = 'mo_share_1'), 1,
    'owner A can select own moment');
select is((select count(*)::int from public.reference_examples where id = 'rx_share'), 1,
    'owner A can select own reference example');
reset role;

set role authenticated;
select set_config('request.jwt.claim.sub', '22222222-2222-2222-2222-222222222222', true);
select is((select count(*)::int from public.clips where match_id = 'm_share_1'), 0,
    'user B sees no clip before a grant');
select is((select count(*)::int from public.moments where match_id = 'm_share_1'), 0,
    'user B sees no moment before a grant');
reset role;

insert into public.share_grants (id, match_id, owner_id, grantee_id, scope)
values ('sg_read', 'm_share_1', '11111111-1111-1111-1111-111111111111',
        '22222222-2222-2222-2222-222222222222', 'read');
set role authenticated;
select set_config('request.jwt.claim.sub', '11111111-1111-1111-1111-111111111111', true);
select is((select count(*)::int from public.share_grants where id = 'sg_read'), 1,
    'grant owner A can select own share grant');
reset role;
set role authenticated;
select set_config('request.jwt.claim.sub', '22222222-2222-2222-2222-222222222222', true);
select is((select count(*)::int from public.clips where id = 'c_share_1'), 1,
    'user B sees A clip on a match with active read grant');
select is((select count(*)::int from public.moments where id = 'mo_share_1'), 1,
    'user B sees A moment on a match with active read grant');
select is((select count(*)::int from public.share_grants where id = 'sg_read'), 0,
    'user B cannot see the share grant row');
select is((select count(*)::int from public.clips where match_id = 'm_share_2'), 0,
    'grant on match 1 does not expose A clip on match 2');
select is((select count(*)::int from public.moments where match_id = 'm_share_2'), 0,
    'grant on match 1 does not expose A moment on match 2');
reset role;

update public.share_grants set revoked_at = now() where id = 'sg_read';
set role authenticated;
select set_config('request.jwt.claim.sub', '22222222-2222-2222-2222-222222222222', true);
select is((select count(*)::int from public.clips where id = 'c_share_1'), 0,
    'user B loses clip access when read grant is revoked');
select is((select count(*)::int from public.moments where id = 'mo_share_1'), 0,
    'user B loses moment access when read grant is revoked');
reset role;

insert into public.share_grants (id, match_id, owner_id, grantee_id, scope)
values ('sg_compare', 'm_share_1', '11111111-1111-1111-1111-111111111111',
        '22222222-2222-2222-2222-222222222222', 'compare');
set role authenticated;
select set_config('request.jwt.claim.sub', '22222222-2222-2222-2222-222222222222', true);
select is((select count(*)::int from public.clips where id = 'c_share_1'), 1,
    'user B sees A clip on a match with active compare grant');
select is((select count(*)::int from public.moments where id = 'mo_share_1'), 1,
    'user B sees A moment on a match with active compare grant');
reset role;

update public.share_grants set revoked_at = now() where id = 'sg_compare';
set role authenticated;
select set_config('request.jwt.claim.sub', '22222222-2222-2222-2222-222222222222', true);
select is((select count(*)::int from public.clips where id = 'c_share_1'), 0,
    'user B loses clip access when compare grant is revoked');
select is((select count(*)::int from public.moments where id = 'mo_share_1'), 0,
    'user B loses moment access when compare grant is revoked');

select throws_ok(
    $$insert into public.share_grants (id, match_id, owner_id, grantee_id, scope)
      values ('sg_b_insert', 'm_share_1', '22222222-2222-2222-2222-222222222222',
        '11111111-1111-1111-1111-111111111111', 'read')$$,
    '42501', null, 'user B cannot insert share grants');
select throws_ok(
    $$insert into public.moments (id, match_id, owner_id, start_ms, end_ms)
      values ('mo_b_insert', 'm_share_1', '22222222-2222-2222-2222-222222222222', 1, 2)$$,
    '42501', null, 'user B cannot insert moments');
select throws_ok(
    $$insert into public.clips (id, match_id, owner_id, source, rights, start_ms, end_ms)
      values ('c_b_insert', 'm_share_1', '22222222-2222-2222-2222-222222222222', 'own', 'own', 1, 2)$$,
    '42501', null, 'user B cannot insert clips');
select throws_ok(
    $$insert into public.reference_examples (id, owner_id, kind, sport, shot, rights)
      values ('rx_b_insert', '22222222-2222-2222-2222-222222222222', 'team_reference',
        'badminton', '{}'::jsonb, 'team-consented')$$,
    '42501', null, 'user B cannot insert reference examples');
select ok(not has_table_privilege('authenticated', 'public.share_grants', 'INSERT')
    and not has_table_privilege('authenticated', 'public.share_grants', 'UPDATE')
    and not has_table_privilege('authenticated', 'public.share_grants', 'DELETE'),
    'authenticated has no write privileges on share_grants');
select ok(not has_table_privilege('authenticated', 'public.moments', 'INSERT')
    and not has_table_privilege('authenticated', 'public.moments', 'UPDATE')
    and not has_table_privilege('authenticated', 'public.moments', 'DELETE'),
    'authenticated has no write privileges on moments');
select ok(not has_table_privilege('authenticated', 'public.clips', 'INSERT')
    and not has_table_privilege('authenticated', 'public.clips', 'UPDATE')
    and not has_table_privilege('authenticated', 'public.clips', 'DELETE'),
    'authenticated has no write privileges on clips');
select ok(not has_table_privilege('authenticated', 'public.reference_examples', 'INSERT')
    and not has_table_privilege('authenticated', 'public.reference_examples', 'UPDATE')
    and not has_table_privilege('authenticated', 'public.reference_examples', 'DELETE'),
    'authenticated has no write privileges on reference_examples');
reset role;

select ok(not has_function_privilege('anon', 'private.has_share_grant(text)', 'EXECUTE'),
    'anon cannot execute private.has_share_grant');

delete from public.clips where id = 'c_delete';
select ok((select clip_id is null from public.reference_examples where id = 'rx_share'),
    'deleting a clip sets reference_examples.clip_id to null');

select throws_ok(
    $$insert into public.share_grants (id, match_id, owner_id, grantee_id, scope)
      values ('sg_bad_scope', 'm_share_1', '11111111-1111-1111-1111-111111111111',
        '22222222-2222-2222-2222-222222222222', 'write')$$,
    '23514', null, 'share_grants rejects an invalid scope');
select throws_ok(
    $$insert into public.clips (id, match_id, owner_id, source, rights, start_ms, end_ms)
      values ('c_bad_source', 'm_share_1', '11111111-1111-1111-1111-111111111111', 'stock', 'own', 1, 2)$$,
    '23514', null, 'clips rejects an invalid source');
select throws_ok(
    $$insert into public.reference_examples (id, owner_id, kind, sport, shot, rights)
      values ('rx_bad_kind', '11111111-1111-1111-1111-111111111111', 'own',
        'badminton', '{}'::jsonb, 'team-consented')$$,
    '23514', null, 'reference_examples rejects an invalid kind');

select has_index('share_grants', 'share_grants_owner_id_idx', 'share_grants.owner_id indexed');
select has_index('share_grants', 'share_grants_grantee_id_idx', 'share_grants.grantee_id indexed');
select has_index('moments', 'moments_owner_id_idx', 'moments.owner_id indexed');
select has_index('moments', 'moments_match_id_idx', 'moments.match_id indexed');
select has_index('clips', 'clips_owner_id_idx', 'clips.owner_id indexed');
select has_index('clips', 'clips_match_id_idx', 'clips.match_id indexed');
select has_index('reference_examples', 'reference_examples_owner_id_idx', 'reference_examples.owner_id indexed');
select has_index('reference_examples', 'reference_examples_clip_id_idx', 'reference_examples.clip_id indexed');

select * from finish();
rollback;
