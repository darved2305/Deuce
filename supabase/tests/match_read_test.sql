BEGIN;
SELECT plan(35);

INSERT INTO auth.users (instance_id, id, aud, role, email, encrypted_password,
  email_confirmed_at, created_at, updated_at)
VALUES ('00000000-0000-0000-0000-000000000000',
  '11111111-1111-1111-1111-111111111111', 'authenticated', 'authenticated',
  'p3-match-read-a@elevate.dev', '', now(), now(), now()),
  ('00000000-0000-0000-0000-000000000000',
  '22222222-2222-2222-2222-222222222222', 'authenticated', 'authenticated',
  'p3-match-read-b@elevate.dev', '', now(), now(), now()),
  ('00000000-0000-0000-0000-000000000000',
  '33333333-3333-3333-3333-333333333333', 'authenticated', 'authenticated',
  'p3-match-read-c@elevate.dev', '', now(), now(), now());

CREATE function pg_temp.ready_run(p_match text, p_session text, p_analysis text)
  returns void language plpgsql AS $$
begin
  insert into public.matches (id, owner_id, sport, format, ruleset_id, ruleset_version)
  values (p_match, '11111111-1111-1111-1111-111111111111', 'badminton', 'singles', 'bwf_3x21', '2025-04');
  insert into public.sessions (id, match_id, owner_id, mode, source, who_is_me)
  values (p_session, p_match, '11111111-1111-1111-1111-111111111111', 'match', '{}'::jsonb,
          '{"image_xy":[1,12],"t_ms":0}'::jsonb);
  insert into public.analysis_runs (id, session_id, match_id, owner_id, idempotency_key)
  values (p_analysis, p_session, p_match, '11111111-1111-1111-1111-111111111111', p_analysis);
  perform public.append_event(p_analysis, 'analysis.queued', '{}'::jsonb);
  perform public.append_event(p_analysis, 'analysis.started',
    jsonb_build_object('engine_version', 'engine-test', 'windows_expected', 7));
end
$$;

CREATE function pg_temp.minimal_vision_result(p_match text, p_analysis text)
  returns jsonb language sql immutable AS $$
  select jsonb_build_object(
    'schema', 'match.v1',
    'match_id', p_match,
    'session_ids', '[]'::jsonb,
    'sport', 'badminton',
    'format', 'singles',
    'ruleset', jsonb_build_object('id', 'bwf_3x21', 'version', '2025-04'),
    'players', '[]'::jsonb,
    'calibration', 'null'::jsonb,
    'analysis', jsonb_build_object(
      'analysis_id', p_analysis,
      'engine_version', 'engine-test',
      'state', 'processing',
      'models', '[]'::jsonb,
      'artifacts', jsonb_build_object('ball_track', 'art_ball', 'player_tracks', 'art_players')
    ),
    'capabilities', jsonb_build_object(
      'court', jsonb_build_object('tier', 'core', 'status', 'unknown'),
      'ball', jsonb_build_object('tier', 'core', 'status', 'unknown'),
      'reviews', jsonb_build_object('tier', 'core', 'status', 'unknown'),
      'score', jsonb_build_object('tier', 'core', 'status', 'unknown')
    ),
    'rallies', '[]'::jsonb,
    'shots', '[]'::jsonb,
    'events', '[]'::jsonb,
    'reviews', '[]'::jsonb,
    'confirmed_points', '[]'::jsonb,
    'score', jsonb_build_object('timeline', '[]'::jsonb, 'games', '[]'::jsonb),
    'metrics', '[]'::jsonb,
    'moments', '[]'::jsonb,
    'clips', '[]'::jsonb,
    'corrections', '[]'::jsonb
  )
$$;

SELECT pg_temp.ready_run('m_read', 's_read', 'a_read');
UPDATE public.analysis_runs
SET players =
  '[{"player_id":"p_owner","track_ids":["t_1"],"label":"you","identity":"user_confirmed"}]'::jsonb,
  calibration = 'null'::jsonb, capabilities =
  '{"court":{"tier":"core","status":"unknown"},"ball":{"tier":"core","status":"unknown"},"reviews":{"tier":"core","status":"unknown"},"score":{"tier":"core","status":"unknown"}}'::jsonb,
  models = '[]'::jsonb
WHERE id = 'a_read';
INSERT INTO public.artifacts (id, analysis_id, match_id, owner_id, kind, r2_key)
VALUES ('art_ball_read', 'a_read', 'm_read',
  '11111111-1111-1111-1111-111111111111', 'ball_track',
  'analysis/a_read/engine/tracks/ball.json'), ('art_players_read', 'a_read',
  'm_read', '11111111-1111-1111-1111-111111111111', 'player_tracks',
  'analysis/a_read/engine/tracks/players.json');
INSERT INTO public.vision_events (match_id, id, owner_id, analysis_id, type,
  t_ms, t_sigma_ms, player_id, track_id, image_xy, cues, state, evidence)
VALUES ('m_read', 'e_hit_read', '11111111-1111-1111-1111-111111111111',
  'a_read', 'hit', 100, 10, 'p_owner', 't_1', '[1,2]'::jsonb,
  '["trajectory"]'::jsonb, 'candidate', '[{"t_ms":100}]'::jsonb);
INSERT INTO public.confirmed_points (id, match_id, owner_id, rally_id, winner,
  confirmed_by, review_id, idempotency_key)
VALUES ('cp_read', 'm_read', '11111111-1111-1111-1111-111111111111', 'r_read',
  'p_opponent', '11111111-1111-1111-1111-111111111111', 'rv_read', 'read-key');
INSERT INTO public.corrections (id, match_id, owner_id, target_type, target_id,
  field, old, new, actor, reason)
VALUES ('cr_read', 'm_read', '11111111-1111-1111-1111-111111111111', 'event',
  'e_hit_read', 'state', 'candidate', 'user',
  '11111111-1111-1111-1111-111111111111', 'corrected label');

SELECT is((SELECT windows_expected FROM public.analysis_events WHERE analysis_id
  = 'a_read' AND type = 'analysis.started'), 7,
  'append_event stores windows_expected');

SET local role authenticated;
SELECT set_config('request.jwt.claim.sub',
  '22222222-2222-2222-2222-222222222222', TRUE);
SELECT is(public.get_match_v1('m_read'), NULL::jsonb,
  'a second user without a grant gets no match.v1');
SELECT is((SELECT count(*)::integer FROM public.analysis_runs WHERE match_id =
  'm_read'), 0, 'a second user without a grant sees no analysis run');
SELECT is((SELECT count(*)::integer FROM public.vision_events WHERE match_id =
  'm_read'), 0, 'a second user without a grant sees no result rows');
reset role;

INSERT INTO public.share_grants (id, match_id, owner_id, grantee_id, scope)
VALUES ('sg_read', 'm_read', '11111111-1111-1111-1111-111111111111',
  '22222222-2222-2222-2222-222222222222', 'read');

SET local role authenticated;
SELECT set_config('request.jwt.claim.sub',
  '22222222-2222-2222-2222-222222222222', TRUE);
SELECT is(public.get_match_v1('m_read')->>'match_id', 'm_read',
  'an active match-scope grant returns the match');
SELECT is(public.get_match_v1('m_read')#>>'{analysis,state}', 'processing',
  'match.v1 retains the in-progress analysis state');
SELECT is(public.get_match_v1('m_read')->'calibration', 'null'::jsonb,
  'match.v1 preserves null calibration');
SELECT is(public.get_match_v1('m_read')#>>'{events,0,type}', 'hit',
  'share grant exposes hit events');
SELECT is(public.get_match_v1('m_read')#>>'{events,0,player_id}', 'p_owner',
  'hit player id is retained');
SELECT is(public.get_match_v1('m_read')#>>'{events,0,track_id}', 't_1',
  'hit track id is retained');
SELECT is(public.get_match_v1('m_read')#>'{events,0,image_xy}', '[1,2]'::jsonb,
  'hit image coordinates are retained');
SELECT is(jsonb_array_length(public.get_match_v1('m_read')->'session_ids'), 1,
  'session ids come from the visible analysis runs');
SELECT is(public.get_match_v1('m_read')#>>'{analysis,artifacts,ball_track}',
  'art_ball_read', 'match.v1 includes the stored ball-track artifact id');
SELECT is(public.get_match_v1('m_read')#>>'{analysis,artifacts,player_tracks}',
  'art_players_read', 'match.v1 includes the stored player-track artifact id');
SELECT is(public.get_match_v1('m_read')#>>'{confirmed_points,0,by}', 'p_owner',
  'confirmed point audit user maps to the user-confirmed player id');
SELECT is(public.get_match_v1('m_read')#>>'{corrections,0,actor}', 'p_owner',
  'correction audit user maps to the user-confirmed player id');
SELECT is(public.get_match_v1('m_read')->'score',
  '{"timeline":[],"games":[]}'::jsonb,
  'an unset score is represented by the empty contract score');
SELECT is((SELECT count(*)::integer FROM public.vision_events WHERE match_id =
  'm_read'), 1, 'a share reader can select the match result event');
SELECT throws_ok($$insert into public.corrections
  (id, match_id, owner_id, target_type, target_id, field, actor)
  values ('cr_share_write', 'm_read', '22222222-2222-2222-2222-222222222222',
          'event', 'e_hit_read', 'state', '22222222-2222-2222-2222-222222222222')$$,
  '42501', NULL, 'a share reader cannot write corrections');
reset role;

UPDATE public.analysis_runs
SET players = '[]'::jsonb
WHERE id = 'a_read';
SET local role authenticated;
SELECT set_config('request.jwt.claim.sub',
  '11111111-1111-1111-1111-111111111111', TRUE);
SELECT is(jsonb_array_length(public.get_match_v1('m_read')->'confirmed_points'),
  0, 'confirmed points are omitted when the user-confirmed player is missing');
SELECT is(jsonb_array_length(public.get_match_v1('m_read')->'corrections'), 0,
  'corrections are omitted when the user-confirmed player is missing');
reset role;

UPDATE public.analysis_runs
SET players =
  '[{"player_id":"p_owner","track_ids":[],"label":"you","identity":"user_confirmed"},{"player_id":"p_other","track_ids":[],"label":"you too","identity":"user_confirmed"}]'::jsonb
WHERE id = 'a_read';
SET local role authenticated;
SELECT set_config('request.jwt.claim.sub',
  '11111111-1111-1111-1111-111111111111', TRUE);
SELECT is(jsonb_array_length(public.get_match_v1('m_read')->'confirmed_points'),
  0,
  'confirmed points are omitted when the user-confirmed player is ambiguous');
SELECT is(jsonb_array_length(public.get_match_v1('m_read')->'corrections'), 0,
  'corrections are omitted when the user-confirmed player is ambiguous');
reset role;

SELECT pg_temp.ready_run('m_minimal', 's_minimal', 'a_minimal');
SELECT lives_ok($$select public.ingest_vision_result(
  'a_minimal', pg_temp.minimal_vision_result('m_minimal', 'a_minimal'))$$,
  'the 1.8.0 minimal finalize payload is ingested');
SELECT is((SELECT calibration FROM public.analysis_runs WHERE id = 'a_minimal'),
  'null'::jsonb, 'null calibration round-trips through finalize');
SELECT is((SELECT state FROM public.analysis_runs WHERE id = 'a_minimal'),
  'completed', 'the minimal finalize payload completes the analysis');
SELECT is(jsonb_array_length((SELECT players FROM public.analysis_runs WHERE id
  = 'a_minimal')), 0, 'minimal finalize keeps the empty players array');
SELECT is(public.get_match_v1('m_minimal')#>'{analysis,artifacts,ball_track}',
  'null'::jsonb, 'a missing ball-track artifact is represented as null');
SELECT is(public.get_match_v1('m_minimal')#>'{analysis,artifacts,player_tracks}',
  'null'::jsonb, 'missing player-track artifacts are represented as null');

SELECT pg_temp.ready_run('m_hit', 's_hit', 'a_hit');
SELECT lives_ok($$select public.ingest_vision_result(
  'a_hit', pg_temp.minimal_vision_result('m_hit', 'a_hit') || jsonb_build_object(
    'events', jsonb_build_array(jsonb_build_object(
      'event_id', 'e_hit', 'type', 'hit', 't_ms', 21430, 't_sigma_ms', 10,
      'image_xy', '[922.5,723.8]'::jsonb, 'player_id', 'p_owner', 'track_id', 't_1',
      'cues', '["audio","trajectory"]'::jsonb, 'state', 'candidate',
      'evidence', '[{"t_ms":21430}]'::jsonb
    ))
  ))$$, 'a hit event is persisted by finalize');
SELECT is((SELECT type FROM public.vision_events WHERE match_id = 'm_hit' AND id
  = 'e_hit'), 'hit', 'finalize persists hit type in vision_events');
SELECT is((SELECT player_id FROM public.vision_events WHERE match_id = 'm_hit'
  AND id = 'e_hit'), 'p_owner', 'finalize persists hit player id');
SELECT is((SELECT track_id FROM public.vision_events WHERE match_id = 'm_hit'
  AND id = 'e_hit'), 't_1', 'finalize persists hit track id');
SELECT is((SELECT image_xy FROM public.vision_events WHERE match_id = 'm_hit'
  AND id = 'e_hit'), '[922.5,723.8]'::jsonb,
  'finalize persists hit image coordinates');
SELECT is((SELECT rally_id FROM public.vision_events WHERE match_id = 'm_hit'
  AND id = 'e_hit'), NULL::text, 'finalize permits a hit without a rally id');

SELECT *
FROM finish();
ROLLBACK;
