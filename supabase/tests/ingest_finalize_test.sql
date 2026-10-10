begin;
select plan(37);

insert into auth.users (instance_id, id, aud, role, email, encrypted_password, email_confirmed_at, created_at, updated_at)
values ('00000000-0000-0000-0000-000000000000', '11111111-1111-1111-1111-111111111111',
        'authenticated', 'authenticated', 'p3-finalize@elevate.dev', '', now(), now(), now());

create function pg_temp.ready_run(p_match text, p_session text, p_analysis text)
returns void language plpgsql as $$
begin
  insert into public.matches (id, owner_id, sport, format, ruleset_id, ruleset_version)
    values (p_match, '11111111-1111-1111-1111-111111111111', 'badminton', 'singles', 'bwf_singles', 'v1');
  insert into public.sessions (id, match_id, owner_id, mode, source, who_is_me)
    values (p_session, p_match, '11111111-1111-1111-1111-111111111111', 'match',
      '{"kind":"upload","bytes":1024}'::jsonb, '{"image_xy":[1,12],"t_ms":0}'::jsonb);
  insert into public.analysis_runs (id, session_id, match_id, owner_id, idempotency_key)
    values (p_analysis, p_session, p_match, '11111111-1111-1111-1111-111111111111', p_analysis);
  perform public.append_event(p_analysis, 'analysis.queued', '{}'::jsonb);
  perform public.append_event(p_analysis, 'analysis.started', '{}'::jsonb);
end $$;

create function pg_temp.finalize_payload(
  p_match text, p_analysis text, p_rallies jsonb, p_shots jsonb, p_events jsonb,
  p_metrics jsonb, p_moments jsonb, p_clips jsonb
) returns jsonb language sql immutable as $$
  select jsonb_build_object(
    'match_id', p_match,
    'analysis', jsonb_build_object('analysis_id', p_analysis, 'engine_version', 'engine-test', 'models', '[]'::jsonb),
    'players', '[]'::jsonb,
    'calibration', jsonb_build_object('plane', 'court', 'template', 'badminton_singles',
      'H', jsonb_build_array(1,0,0,0,1,0,0,0,1)),
    'capabilities', '{}'::jsonb,
    'rallies', p_rallies,
    'shots', p_shots,
    'events', p_events,
    'reviews', jsonb_build_array(jsonb_build_object(
      'review_id', 'rv_engine_13', 'match_id', p_match, 'rally_id', 'r_engine_07',
      'event_id', 'e_engine_11', 't_ms', 1100, 'evidence_frames', '[1100]'::jsonb,
      'contact_xy_m', '[1,2]'::jsonb, 'cov_m2', '[[0,0],[0,0]]'::jsonb,
      'line_id', 'left_sideline_singles', 'margin_m', 0.1, 'sigma_m', 0.02,
      'estimate', 'IN', 'visibility', 'visible', 'model_ids', '[]'::jsonb,
      'calibration_id', 'cal_1'
    )),
    'metrics', p_metrics,
    'moments', p_moments,
    'clips', p_clips
  )
$$;

select pg_temp.ready_run('m_ingest', 's_ingest', 'a_ingest');
select public.ingest_vision_result('a_ingest', pg_temp.finalize_payload(
  'm_ingest', 'a_ingest',
  '[{"rally_id":"r_engine_07","index":1,"start_ms":0,"end_ms":5000,"shot_ids":["sh_engine_09"],"server":"p_me","winner":"p_me","winner_state":"candidate","end_reason":"landing","ball_coverage":0.8,"clip_id":null,"state":"observed"}]'::jsonb,
  '[{"shot_id":"sh_engine_09","rally_id":"r_engine_07","index":0,"player_id":"p_me","contact_ms":1000,"contact_sigma_ms":20,"phase":"serve","reference_plane":"court","coordinate_frame_version":1,"roles":{"focal":{"player_id":"p_me","xy_m":[1,2],"state":"observed","conf":0.9},"opponent":{"player_id":"p_opp","xy_m":[2,3],"state":"observed","conf":0.8}},"incoming":{"source_zone":"F1","landing_zone":"B1","zones_state":"observed","family":"clear"},"chosen":{"target_zone":"B2","landing_xy_m":[4,5],"landing_state":"observed","family":"clear"},"flight_time_ms":500,"flight_time_state":"observed","context":{"score_before":[0,0],"pressure_bucket":"low","rally_len_so_far":0,"state":"observed"},"outcome":{"point_winner":"p_me","shots_to_end":1,"state":"candidate"},"quality":{"camera_quality_grade":"A"},"clip_id":null}]'::jsonb,
  '[{"event_id":"e_engine_11","type":"landing","t_ms":1100,"t_sigma_ms":10,"rally_id":"r_engine_07","plane":"court","contact_xy_m":[1,2],"cov_m2":[[0,0],[0,0]],"cues":[],"state":"candidate","evidence":[]} ]'::jsonb,
  '[{"key":"z_lower","definition_version":1,"player_id":"p_me","numerator":3,"denominator":5,"unit":"ratio","tier":"core","source_event_ids":[]},{"key":"b_tie","definition_version":1,"player_id":"p_me","numerator":9,"denominator":10,"unit":"ratio","tier":"core","source_event_ids":[]},{"key":"a_tie","definition_version":1,"player_id":"p_me","numerator":1,"denominator":10,"unit":"ratio","tier":"experimental","source_event_ids":[]},{"key":"unsupported_high","definition_version":1,"player_id":"p_me","numerator":10,"denominator":5,"unit":"ratio","tier":"unsupported","source_event_ids":[]}]'::jsonb,
  '[{"moment_id":"mo_engine_12","rally_id":"r_engine_07","shot_id":"sh_engine_09","start_ms":900,"end_ms":1400,"contact_ms":1000,"tags":["landing"],"clip_id":"c_r_1"}]'::jsonb,
  '[]'::jsonb
));

select is((select id from public.rallies where match_id = 'm_ingest' and id = 'r_engine_07'),
  'r_engine_07', 'rally id is retained verbatim');
select is((select id from public.shots where match_id = 'm_ingest' and id = 'sh_engine_09'),
  'sh_engine_09', 'shot id is retained verbatim');
select is((select id from public.vision_events where match_id = 'm_ingest' and id = 'e_engine_11'),
  'e_engine_11', 'events are stored in vision_events with their verbatim ids');
select is((select count(*)::integer from public.analysis_events
  where analysis_id = 'a_ingest' and event_id = 'e_engine_11'),
  0, 'vision events are not inserted as analysis events');
select is((select count(*)::integer from public.reviews where match_id = 'm_ingest'),
  1, 'engine review estimate is persisted');
select is((select (roles#>>'{focal,xy_m,0}')::numeric from public.shots
  where match_id = 'm_ingest' and id = 'sh_engine_09'), 4.18::numeric,
  'far-side focal x is rotated by court width');
select is((select (roles#>>'{focal,xy_m,1}')::numeric from public.shots
  where match_id = 'm_ingest' and id = 'sh_engine_09'), 11.4::numeric,
  'far-side focal y is rotated by court length');
select is((select (roles#>>'{opponent,xy_m,0}')::numeric from public.shots
  where match_id = 'm_ingest' and id = 'sh_engine_09'), 3.18::numeric,
  'far-side opponent x is rotated by court width');
select is((select (roles#>>'{opponent,xy_m,1}')::numeric from public.shots
  where match_id = 'm_ingest' and id = 'sh_engine_09'), 10.4::numeric,
  'far-side opponent y is rotated by court length');
select is((select (chosen#>>'{landing_xy_m,0}')::numeric from public.shots
  where match_id = 'm_ingest' and id = 'sh_engine_09'), 1.18::numeric,
  'far-side landing x is rotated by court width');
select is((select (chosen#>>'{landing_xy_m,1}')::numeric from public.shots
  where match_id = 'm_ingest' and id = 'sh_engine_09'), 8.4::numeric,
  'far-side landing y is rotated by court length');
select is((select (contact_xy_m->>0)::numeric from public.vision_events
  where match_id = 'm_ingest' and id = 'e_engine_11'), 4.18::numeric,
  'far-side event coordinate is rotated');
select is((select (contact_xy_m->>1)::numeric from public.vision_events
  where match_id = 'm_ingest' and id = 'e_engine_11'), 11.4::numeric,
  'far-side event y is rotated by court length');
select is((select incoming->>'source_zone' from public.shots
  where match_id = 'm_ingest' and id = 'sh_engine_09'), 'B3',
  'far-side incoming source zone follows the rotated §9 grid');
select is((select incoming->>'landing_zone' from public.shots
  where match_id = 'm_ingest' and id = 'sh_engine_09'), 'F3',
  'far-side incoming landing zone follows the rotated §9 grid');
select is((select chosen->>'target_zone' from public.shots
  where match_id = 'm_ingest' and id = 'sh_engine_09'), 'F2',
  'far-side chosen target zone follows the rotated §9 grid');
select is((select caption from public.insights where match_id = 'm_ingest' and kind = 'hero'),
  '1 of 10 a tie', 'hero ranks by distance from one half, denominator, then key');
select is((select evidence_moment_ids->>0 from public.insights
  where match_id = 'm_ingest' and kind = 'hero'), 'mo_engine_12',
  'hero evidence refers to a persisted moment');
select is((select state from public.analysis_runs where id = 'a_ingest'), 'completed',
  'insights completes the run');
select is((select count(*)::integer from public.analysis_events
  where analysis_id = 'a_ingest' and type = 'analysis.completed'), 1,
  'one completed event is appended');
select throws_ok(
  $$select public.ingest_vision_result('a_ingest', '{}'::jsonb)$$,
  'P0001', 'already_finalized', 'a repeated ingest raises already_finalized');

select pg_temp.ready_run('m_fallback', 's_fallback', 'a_fallback');
insert into public.artifacts (id, analysis_id, match_id, owner_id, kind, r2_key, bytes, sha256)
values
  ('art_short', 'a_fallback', 'm_fallback', '11111111-1111-1111-1111-111111111111', 'clip', 'clips/m_fallback/c_r_short.mp4', 100, repeat('a',64)),
  ('art_short_poster', 'a_fallback', 'm_fallback', '11111111-1111-1111-1111-111111111111', 'poster', 'clips/m_fallback/c_r_short.jpg', 10, repeat('b',64)),
  ('art_long', 'a_fallback', 'm_fallback', '11111111-1111-1111-1111-111111111111', 'clip', 'clips/m_fallback/c_r_long.mp4', 100, repeat('c',64)),
  ('art_long_poster', 'a_fallback', 'm_fallback', '11111111-1111-1111-1111-111111111111', 'poster', 'clips/m_fallback/c_r_long.jpg', 10, repeat('d',64));
select public.ingest_vision_result('a_fallback', pg_temp.finalize_payload(
  'm_fallback', 'a_fallback',
  '[{"rally_id":"r_short","index":1,"start_ms":0,"end_ms":2000,"shot_ids":[],"server":"p_me","winner":"p_me","winner_state":"observed","end_reason":"landing","ball_coverage":1,"clip_id":"c_r_short","state":"observed"},{"rally_id":"r_long","index":2,"start_ms":0,"end_ms":6000,"shot_ids":[],"server":"p_me","winner":"p_me","winner_state":"observed","end_reason":"landing","ball_coverage":1,"clip_id":"c_r_long","state":"observed"}]'::jsonb,
  '[]'::jsonb,
  '[]'::jsonb,
  '[{"key":"unsupported","definition_version":1,"player_id":"p_me","numerator":10,"denominator":10,"unit":"ratio","tier":"unsupported","source_event_ids":[]}]'::jsonb,
  '[{"moment_id":"mo_short","rally_id":"r_short","shot_id":"sh_unused_1","start_ms":0,"end_ms":1000,"contact_ms":500,"tags":[],"clip_id":"c_r_short"},{"moment_id":"mo_long","rally_id":"r_long","shot_id":"sh_unused_2","start_ms":0,"end_ms":1000,"contact_ms":500,"tags":[],"clip_id":"c_r_long"}]'::jsonb,
  '[{"clip_id":"c_r_short","source":"own","rights":"own","owner":"p_me","match_id":"m_fallback","start_ms":0,"end_ms":2000,"contact_ms":1000,"rally_id":"r_short","remote":{"artifact_id":"art_short","poster_artifact_id":"art_short_poster"},"local":{"source_uri":"content://short","offset_ms":0},"attribution":null},{"clip_id":"c_r_long","source":"own","rights":"own","owner":"p_me","match_id":"m_fallback","start_ms":0,"end_ms":6000,"contact_ms":3000,"rally_id":"r_long","remote":{"artifact_id":"art_long","poster_artifact_id":"art_long_poster"},"local":{"source_uri":"content://long","offset_ms":0},"attribution":null}]'::jsonb
));
select is((select caption from public.insights where match_id = 'm_fallback' and kind = 'hero'),
  'Longest of 2 observed rally clips', 'fallback chooses an observed clip when no metric qualifies');
select is((select evidence_moment_ids->>0 from public.insights
  where match_id = 'm_fallback' and kind = 'hero'), 'mo_long',
  'fallback evidence refers to a real moment from the selected rally');
select is((select count(*)::integer from public.analysis_events
  where analysis_id = 'a_fallback' and type = 'analysis.completed'), 1,
  'fallback also appends exactly one completed event');

select pg_temp.ready_run('m_failure', 's_failure', 'a_failure');
select public.ingest_vision_result('a_failure', pg_temp.finalize_payload(
  'm_failure', 'a_failure', '[]'::jsonb, '[]'::jsonb, '[]'::jsonb,
  '[{"key":"eligible_without_moment","definition_version":1,"player_id":"p_me","numerator":1,"denominator":5,"unit":"ratio","tier":"core","source_event_ids":[]}]'::jsonb,
  '[]'::jsonb, '[]'::jsonb
));
select is((select state from public.analysis_runs where id = 'a_failure'), 'failed',
  'insights exception fails the run');
select ok((select reason like 'insights_failed:%' from public.analysis_events
  where analysis_id = 'a_failure' and type = 'analysis.failed'),
  'failure event includes the insights_failed reason');
select is((select count(*)::integer from public.analysis_events
  where analysis_id = 'a_failure' and type = 'analysis.failed'), 1,
  'insights failure appends one failed event');

select is((select count(*)::integer from information_schema.role_routine_grants
  where routine_schema = 'public' and routine_name = 'ingest_vision_result'
    and grantee = 'service_role' and privilege_type = 'EXECUTE'), 1,
  'service_role can execute ingest_vision_result');
select is((select count(*)::integer from information_schema.role_routine_grants
  where routine_schema = 'public' and routine_name = 'ingest_vision_result'
    and grantee in ('public','anon','authenticated') and privilege_type = 'EXECUTE'), 0,
  'ingest_vision_result is revoked from public, anon, and authenticated');
select is((select count(*)::integer from information_schema.role_routine_grants
  where routine_schema = 'public' and routine_name = 'run_insights'
    and grantee = 'service_role' and privilege_type = 'EXECUTE'), 1,
  'service_role can execute run_insights');

select ok((select confirmed_point_winner is null and confirmed_by is null and confirmed_at is null
  from public.reviews where match_id = 'm_ingest' and id = 'rv_engine_13'),
  'engine review estimate retains null confirmation fields');

select pg_temp.ready_run('m_review_set', 's_review_set', 'a_review_set');
select public.ingest_vision_result('a_review_set',
  pg_temp.finalize_payload('m_review_set', 'a_review_set', '[]'::jsonb, '[]'::jsonb,
    '[]'::jsonb, '[]'::jsonb, '[]'::jsonb, '[]'::jsonb) ||
  jsonb_build_object('reviews', jsonb_build_array(jsonb_build_object(
    'review_id', 'rv_confirmed', 'match_id', 'm_review_set', 'rally_id', 'r_confirmed',
    'event_id', 'e_confirmed', 't_ms', 1200, 'evidence_frames', '[1190,1200,1210]'::jsonb,
    'contact_xy_m', '[1,2]'::jsonb, 'cov_m2', '[[0,0],[0,0]]'::jsonb,
    'line_id', 'left_sideline_singles', 'margin_m', 0.1, 'sigma_m', 0.02,
    'estimate', 'IN', 'visibility', 'visible', 'model_ids', '[]'::jsonb,
    'calibration_id', 'cal_1', 'confirmed_point_winner', 'p_opponent',
    'confirmed_by', 'p_me', 'confirmed_at', '2026-10-11T12:00:00Z'
  ))));
select is((select count(*)::integer from public.reviews
  where match_id = 'm_review_set' and id = 'rv_confirmed'), 1,
  'review with all confirmations is persisted');
select ok((select confirmed_point_winner = 'p_opponent' and confirmed_by = 'p_me'
    and confirmed_at = '2026-10-11T12:00:00Z'::timestamptz
  from public.reviews where match_id = 'm_review_set' and id = 'rv_confirmed'),
  'review confirmation values are retained');
select throws_ok(
  $$insert into public.reviews (match_id, id, owner_id, analysis_id, t_ms, estimate, confirmed_point_winner)
    values ('m_review_set', 'rv_partial', '11111111-1111-1111-1111-111111111111',
      'a_review_set', 1300, 'IN', 'p_opponent')$$,
  '23514', null, 'review constraint rejects a partial confirmation');

insert into public.metrics (match_id, owner_id, analysis_id, key, definition_version,
                            player_id, numerator, denominator, unit, tier)
values ('m_review_set', '11111111-1111-1111-1111-111111111111', 'a_review_set',
        'match_level_rate', 1, null, 1, 1, 'ratio', 'core');
select ok((select player_id is null from public.metrics
  where match_id = 'm_review_set' and key = 'match_level_rate'),
  'match-level metric with null player id is stored');
select throws_ok(
  $$insert into public.metrics (match_id, owner_id, analysis_id, key, definition_version,
      player_id, numerator, denominator, unit, tier)
    values ('m_review_set', '11111111-1111-1111-1111-111111111111', 'a_review_set',
      'match_level_rate', 1, null, 1, 1, 'ratio', 'core')$$,
  '23505', null, 'duplicate null-player metric key is rejected');
select is((select is_nullable from information_schema.columns
  where table_schema = 'public' and table_name = 'metrics' and column_name = 'player_id'),
  'YES', 'metrics.player_id is nullable');

select * from finish();
rollback;
