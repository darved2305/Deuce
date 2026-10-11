-- pgTAP for the R2 budget counters (migration r2_budget). Run: supabase test db
begin;
select plan(24);

insert into auth.users (instance_id, id, aud, role, email, encrypted_password, email_confirmed_at, created_at, updated_at)
values ('00000000-0000-0000-0000-000000000000', '22222222-2222-2222-2222-222222222222',
        'authenticated', 'authenticated', 'p3-budget@elevate.dev', '', now(), now(), now());
insert into public.matches (id, owner_id, sport, format, ruleset_id, ruleset_version)
values ('m_b', '22222222-2222-2222-2222-222222222222', 'badminton', 'singles', 'bwf_singles', 'v1');
insert into public.sessions (id, match_id, owner_id, mode, source, who_is_me)
values ('s_b', 'm_b', '22222222-2222-2222-2222-222222222222', 'match', '{}'::jsonb,
        '{"image_xy":[0.5,0.5],"t_ms":0}'::jsonb);
insert into public.uploads (session_id, owner_id, r2_key, r2_upload_id, bytes, sha256, status)
values ('s_b', '22222222-2222-2222-2222-222222222222', 'orig/s_b/source.mp4', 'UP', 1000, 'x', 'initiated');

-- RLS is on with no user policies and no user grants
select ok((select relrowsecurity from pg_class where oid = 'public.r2_usage_monthly'::regclass), 'monthly: RLS on');
select ok((select relrowsecurity from pg_class where oid = 'public.r2_usage_daily'::regclass), 'daily: RLS on');
select ok(not has_table_privilege('authenticated', 'public.r2_usage_monthly', 'select'), 'monthly: no user grant');
select ok(not has_function_privilege('authenticated', 'public.r2_take_part_urls(text,uuid,integer,integer)', 'execute'), 'rpc: not executable by users');

-- r2_add_usage accumulates in one row per UTC month
select public.r2_add_usage(2, 0);
select public.r2_add_usage(3, 5);
select is((select class_a from public.r2_usage_monthly), 5::bigint, 'class_a accumulates');
select is((select class_b from public.r2_usage_monthly), 5::bigint, 'class_b accumulates');
select is((select count(*) from public.r2_usage_monthly), 1::bigint, 'one row per month');

-- snapshot
select is((public.r2_budget_snapshot('22222222-2222-2222-2222-222222222222')->>'sessions_today')::int, 1, 'snapshot: sessions today');
select is((public.r2_budget_snapshot('22222222-2222-2222-2222-222222222222')->>'uploads_today')::int, 1, 'snapshot: uploads today');
select is((public.r2_budget_snapshot('22222222-2222-2222-2222-222222222222')->>'storage_bytes')::bigint, 1000::bigint, 'snapshot: storage counts the open upload');
select is((public.r2_budget_snapshot('22222222-2222-2222-2222-222222222222')->>'class_a')::int, 5, 'snapshot: class_a');
update public.uploads set status = 'aborted' where session_id = 's_b';
select is((public.r2_budget_snapshot('22222222-2222-2222-2222-222222222222')->>'storage_bytes')::bigint, 0::bigint, 'snapshot: aborted uploads free their bytes');

-- G4: limit applies atomically and counts Class A only on success
select is(public.r2_take_part_urls('s_b', '22222222-2222-2222-2222-222222222222', 2, 3), 2, 'take 2 of 3');
select is(public.r2_take_part_urls('s_b', '22222222-2222-2222-2222-222222222222', 2, 3), null, 'a 4th URL is refused');
select is((select class_a from public.r2_usage_monthly), 7::bigint, 'refusal adds no Class A');

-- G6 (D-261011-NP2): per-owner daily cap on counted takes; a re-issue for the same artifact
-- while the previous URL is still valid is free and slides the window
select ok((select relrowsecurity from pg_class where oid = 'public.r2_download_issued'::regclass), 'issued: RLS on');
select ok(not has_function_privilege('authenticated', 'public.r2_take_download(uuid,integer,text,integer)', 'execute'), 'take_download rpc: not executable by users');
select is(public.r2_take_download('22222222-2222-2222-2222-222222222222', 3, 'art_a', 300), 1, 'first download taken');
select is(public.r2_take_download('22222222-2222-2222-2222-222222222222', 3, 'art_a', 300), 1, 're-issue within validity is free');
select is(public.r2_take_download('22222222-2222-2222-2222-222222222222', 3, 'art_b', 300), 2, 'a different artifact counts');
update public.r2_download_issued set issued_at = now() - interval '10 minutes'
 where owner_id = '22222222-2222-2222-2222-222222222222' and artifact_id = 'art_a';
select is(public.r2_take_download('22222222-2222-2222-2222-222222222222', 3, 'art_a', 300), 3, 're-issue after validity counts again');
select is(public.r2_take_download('22222222-2222-2222-2222-222222222222', 3, 'art_c', 300), null, 'a new artifact at the cap is refused');
select is(public.r2_take_download('22222222-2222-2222-2222-222222222222', 3, 'art_a', 300), 3, 'a still-valid re-issue stays free at the cap');
select is((select class_b from public.r2_usage_monthly), 10::bigint, 'every issued URL still counts as Class B');

select * from finish();
rollback;
