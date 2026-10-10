-- pgTAP for engine result table schema, grants, RLS and cascades.
begin;
select plan(60);

insert into auth.users (instance_id, id, aud, role, email, encrypted_password,
                        email_confirmed_at, created_at, updated_at)
values
  ('00000000-0000-0000-0000-000000000000', '11111111-1111-1111-1111-111111111111',
   'authenticated', 'authenticated', 'p3-results-a@elevate.dev', '', now(), now(), now()),
  ('00000000-0000-0000-0000-000000000000', '22222222-2222-2222-2222-222222222222',
   'authenticated', 'authenticated', 'p3-results-b@elevate.dev', '', now(), now(), now());

insert into public.matches (id, owner_id, sport, format, ruleset_id, ruleset_version)
values
  ('m_results_a', '11111111-1111-1111-1111-111111111111', 'badminton', 'singles', 'bwf_singles', 'v1'),
  ('m_results_b', '22222222-2222-2222-2222-222222222222', 'badminton', 'singles', 'bwf_singles', 'v1');
insert into public.sessions (id, match_id, owner_id, mode, source, who_is_me)
values ('s_results_a', 'm_results_a', '11111111-1111-1111-1111-111111111111', 'match', '{}'::jsonb,
        '{"image_xy":[0,0],"t_ms":0}'::jsonb);
insert into public.analysis_runs (id, session_id, match_id, owner_id, idempotency_key)
values ('a_results_a', 's_results_a', 'm_results_a', '11111111-1111-1111-1111-111111111111', 'results');

set local role service_role;
insert into public.rallies (match_id, id, owner_id, analysis_id, index, start_ms, end_ms)
values ('m_results_a', 'r_0007', '11111111-1111-1111-1111-111111111111', 'a_results_a', 1, 0, 1000);
insert into public.shots (match_id, id, owner_id, analysis_id, rally_id, index, player_id,
                          contact_ms, phase, roles, incoming, chosen, context, outcome, quality)
values ('m_results_a', 'sh_0001', '11111111-1111-1111-1111-111111111111', 'a_results_a',
        'r_0007', 1, 'p_1', 100, 'serve', '{}'::jsonb, '{}'::jsonb, '{}'::jsonb,
        '{}'::jsonb, '{}'::jsonb, '{}'::jsonb);
insert into public.vision_events (match_id, id, owner_id, analysis_id, rally_id, type, t_ms, state)
values ('m_results_a', 'e_0001', '11111111-1111-1111-1111-111111111111',
        'a_results_a', 'r_0007', 'contact', 100, 'observed');
insert into public.reviews (match_id, id, owner_id, analysis_id, t_ms, estimate)
values ('m_results_a', 'rv_0001', '11111111-1111-1111-1111-111111111111', 'a_results_a', 100, 'IN');
insert into public.metrics (match_id, owner_id, analysis_id, key, definition_version,
                            player_id, numerator, denominator, unit, tier)
values ('m_results_a', '11111111-1111-1111-1111-111111111111', 'a_results_a',
        'serve_accuracy', 1, 'p_1', 1, 1, 'ratio', 'core');
insert into public.insights (match_id, owner_id, kind, caption, gate)
values ('m_results_a', '11111111-1111-1111-1111-111111111111', 'hero', 'Observed result', 'descriptive');
select is((select id from public.rallies where match_id = 'm_results_a' and id = 'r_0007'),
          'r_0007', 'service_role stores and reads the rally child id verbatim');
reset role;

-- Every result table has RLS enabled.
select ok((select relrowsecurity from pg_class where oid = 'public.rallies'::regclass), 'rallies RLS enabled');
select ok((select relrowsecurity from pg_class where oid = 'public.shots'::regclass), 'shots RLS enabled');
select ok((select relrowsecurity from pg_class where oid = 'public.vision_events'::regclass), 'vision_events RLS enabled');
select ok((select relrowsecurity from pg_class where oid = 'public.reviews'::regclass), 'reviews RLS enabled');
select ok((select relrowsecurity from pg_class where oid = 'public.metrics'::regclass), 'metrics RLS enabled');
select ok((select relrowsecurity from pg_class where oid = 'public.insights'::regclass), 'insights RLS enabled');

set local role authenticated;
set local request.jwt.claims = '{"sub":"11111111-1111-1111-1111-111111111111","role":"authenticated"}';
select is((select count(*)::int from public.rallies where match_id = 'm_results_a'), 1, 'owner A reads their rally');
select is((select count(*)::int from public.shots where match_id = 'm_results_a'), 1, 'owner A reads their shot');
select is((select count(*)::int from public.vision_events where match_id = 'm_results_a'), 1, 'owner A reads their vision event');
select is((select count(*)::int from public.reviews where match_id = 'm_results_a'), 1, 'owner A reads their review');
select is((select count(*)::int from public.metrics where match_id = 'm_results_a'), 1, 'owner A reads their metric');
select is((select count(*)::int from public.insights where match_id = 'm_results_a'), 1, 'owner A reads their insight');

set local request.jwt.claims = '{"sub":"22222222-2222-2222-2222-222222222222","role":"authenticated"}';
select is((select count(*)::int from public.rallies where match_id = 'm_results_a'), 0, 'user B cannot see owner A rallies');
select is((select count(*)::int from public.shots where match_id = 'm_results_a'), 0, 'user B cannot see owner A shots');
select is((select count(*)::int from public.vision_events where match_id = 'm_results_a'), 0, 'user B cannot see owner A vision events');
select is((select count(*)::int from public.reviews where match_id = 'm_results_a'), 0, 'user B cannot see owner A reviews');
select is((select count(*)::int from public.metrics where match_id = 'm_results_a'), 0, 'user B cannot see owner A metrics');
select is((select count(*)::int from public.insights where match_id = 'm_results_a'), 0, 'user B cannot see owner A insights');

-- Authenticated receives SELECT only; each attempted write is denied.
select throws_ok($$insert into public.rallies (match_id,id,owner_id,analysis_id,index,start_ms,end_ms) values ('m_results_b','r_user','22222222-2222-2222-2222-222222222222','a_results_a',1,0,1)$$, '42501', NULL, 'authenticated cannot insert rallies');
select throws_ok($$update public.rallies set state = 'observed' where match_id = 'm_results_a' and id = 'r_0007'$$, '42501', NULL, 'authenticated cannot update rallies');
select throws_ok($$delete from public.rallies where match_id = 'm_results_a' and id = 'r_0007'$$, '42501', NULL, 'authenticated cannot delete rallies');
select throws_ok($$insert into public.shots (match_id,id,owner_id,analysis_id,rally_id,index,player_id,contact_ms,phase,roles,incoming,chosen,context,outcome,quality) values ('m_results_b','sh_user','22222222-2222-2222-2222-222222222222','a_results_a','r_0007',1,'p_1',1,'serve','{}','{}','{}','{}','{}','{}')$$, '42501', NULL, 'authenticated cannot insert shots');
select throws_ok($$update public.shots set contact_ms = 1 where match_id = 'm_results_a' and id = 'sh_0001'$$, '42501', NULL, 'authenticated cannot update shots');
select throws_ok($$delete from public.shots where match_id = 'm_results_a' and id = 'sh_0001'$$, '42501', NULL, 'authenticated cannot delete shots');
select throws_ok($$insert into public.vision_events (match_id,id,owner_id,analysis_id,type,t_ms,state) values ('m_results_b','e_user','22222222-2222-2222-2222-222222222222','a_results_a','contact',1,'observed')$$, '42501', NULL, 'authenticated cannot insert vision_events');
select throws_ok($$update public.vision_events set t_ms = 1 where match_id = 'm_results_a' and id = 'e_0001'$$, '42501', NULL, 'authenticated cannot update vision_events');
select throws_ok($$delete from public.vision_events where match_id = 'm_results_a' and id = 'e_0001'$$, '42501', NULL, 'authenticated cannot delete vision_events');
select throws_ok($$insert into public.reviews (match_id,id,owner_id,analysis_id,t_ms,estimate) values ('m_results_b','rv_user','22222222-2222-2222-2222-222222222222','a_results_a',1,'IN')$$, '42501', NULL, 'authenticated cannot insert reviews');
select throws_ok($$update public.reviews set estimate = 'IN' where match_id = 'm_results_a' and id = 'rv_0001'$$, '42501', NULL, 'authenticated cannot update reviews');
select throws_ok($$delete from public.reviews where match_id = 'm_results_a' and id = 'rv_0001'$$, '42501', NULL, 'authenticated cannot delete reviews');
select throws_ok($$insert into public.metrics (match_id,owner_id,analysis_id,key,definition_version,player_id,numerator,denominator,unit,tier) values ('m_results_b','22222222-2222-2222-2222-222222222222','a_results_a','x',1,'p_1',0,1,'ratio','core')$$, '42501', NULL, 'authenticated cannot insert metrics');
select throws_ok($$update public.metrics set unit = 'ratio' where match_id = 'm_results_a' and key = 'serve_accuracy'$$, '42501', NULL, 'authenticated cannot update metrics');
select throws_ok($$delete from public.metrics where match_id = 'm_results_a' and key = 'serve_accuracy'$$, '42501', NULL, 'authenticated cannot delete metrics');
select throws_ok($$insert into public.insights (match_id,owner_id,kind,caption,gate) values ('m_results_b','22222222-2222-2222-2222-222222222222','hero','x','descriptive')$$, '42501', NULL, 'authenticated cannot insert insights');
select throws_ok($$update public.insights set caption = 'x' where match_id = 'm_results_a'$$, '42501', NULL, 'authenticated cannot update insights');
select throws_ok($$delete from public.insights where match_id = 'm_results_a'$$, '42501', NULL, 'authenticated cannot delete insights');
reset role;

-- Named constraints preserve the contracts test's column-keyed enum scan.
select throws_ok($$insert into public.rallies (match_id,id,owner_id,analysis_id,index,start_ms,end_ms,winner_state) values ('m_results_a','r_bad','11111111-1111-1111-1111-111111111111','a_results_a',2,0,1,'guess')$$, '23514', NULL, 'bad rally winner_state rejected');
select throws_ok($$insert into public.shots (match_id,id,owner_id,analysis_id,rally_id,index,player_id,contact_ms,phase,roles,incoming,chosen,context,outcome,quality) values ('m_results_a','sh_bad','11111111-1111-1111-1111-111111111111','a_results_a','r_0007',2,'p_1',1,'smash','{}','{}','{}','{}','{}','{}')$$, '23514', NULL, 'bad shot phase rejected');
select throws_ok($$insert into public.vision_events (match_id,id,owner_id,analysis_id,type,t_ms,state) values ('m_results_a','e_bad','11111111-1111-1111-1111-111111111111','a_results_a','contact',1,'guess')$$, '23514', NULL, 'bad vision_events state rejected');
select throws_ok($$insert into public.reviews (match_id,id,owner_id,analysis_id,t_ms,estimate) values ('m_results_a','rv_bad','11111111-1111-1111-1111-111111111111','a_results_a',1,'MAYBE')$$, '23514', NULL, 'bad review estimate rejected');
select throws_ok($$insert into public.insights (match_id,owner_id,kind,caption,gate) values ('m_results_a','11111111-1111-1111-1111-111111111111','other','x','descriptive')$$, '23514', NULL, 'bad insight kind rejected');
select throws_ok($$insert into public.insights (match_id,owner_id,kind,caption,gate) values ('m_results_a','11111111-1111-1111-1111-111111111111','hero','x','proof')$$, '23514', NULL, 'bad insight gate rejected');

delete from public.matches where id = 'm_results_a';
select is((select count(*)::int from public.rallies where match_id = 'm_results_a'), 0, 'match delete cascades to rallies');
select is((select count(*)::int from public.shots where match_id = 'm_results_a'), 0, 'match delete cascades to shots');
select is((select count(*)::int from public.vision_events where match_id = 'm_results_a'), 0, 'match delete cascades to vision_events');
select is((select count(*)::int from public.reviews where match_id = 'm_results_a'), 0, 'match delete cascades to reviews');
select is((select count(*)::int from public.metrics where match_id = 'm_results_a'), 0, 'match delete cascades to metrics');
select is((select count(*)::int from public.insights where match_id = 'm_results_a'), 0, 'match delete cascades to insights');

select has_index('rallies', 'rallies_owner_id_idx', 'rallies owner_id indexed');
select has_index('shots', 'shots_owner_id_idx', 'shots owner_id indexed');
select has_index('vision_events', 'vision_events_owner_id_idx', 'vision_events owner_id indexed');
select has_index('reviews', 'reviews_owner_id_idx', 'reviews owner_id indexed');
select has_index('metrics', 'metrics_owner_id_idx', 'metrics owner_id indexed');
select has_index('insights', 'insights_owner_id_idx', 'insights owner_id indexed');
select has_index('rallies', 'rallies_analysis_id_idx', 'rallies analysis_id indexed');
select has_index('shots', 'shots_analysis_id_idx', 'shots analysis_id indexed');
select has_index('vision_events', 'vision_events_analysis_id_idx', 'vision_events analysis_id indexed');
select has_index('reviews', 'reviews_analysis_id_idx', 'reviews analysis_id indexed');
select has_index('metrics', 'metrics_analysis_id_idx', 'metrics analysis_id indexed');

select * from finish();
rollback;
