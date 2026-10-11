BEGIN;
SELECT plan(5);

-- Real engine events are state 'candidate' and carry no 'plane'; the frozen post summary must count
-- them honestly (238 hits are not "0 hits"), and landings in/out stay null without reviews.
INSERT INTO auth.users (instance_id, id, aud, role, email, encrypted_password,
  email_confirmed_at, created_at, updated_at)
VALUES ('00000000-0000-0000-0000-000000000000',
  '11111111-1111-1111-1111-111111111111', 'authenticated', 'authenticated',
  'p3-post-summary@elevate.dev', '', now(), now(), now());

INSERT INTO public.matches (id, owner_id, sport, format, ruleset_id, ruleset_version)
VALUES ('m_ps', '11111111-1111-1111-1111-111111111111', 'badminton', 'singles', 'bwf_3x21', '2025-04');
INSERT INTO public.sessions (id, match_id, owner_id, mode, source, who_is_me)
VALUES ('s_ps', 'm_ps', '11111111-1111-1111-1111-111111111111', 'match',
  '{"duration_ms":484540}'::jsonb, '{"image_xy":[1,12],"t_ms":0}'::jsonb);
INSERT INTO public.analysis_runs (id, session_id, match_id, owner_id, idempotency_key)
VALUES ('a_ps', 's_ps', 'm_ps', '11111111-1111-1111-1111-111111111111', 'a_ps');
SELECT public.append_event('a_ps', 'analysis.queued', '{}'::jsonb);
SELECT public.append_event('a_ps', 'analysis.started', jsonb_build_object('engine_version', 'engine-test'));
UPDATE public.analysis_runs
SET players = '[]'::jsonb, calibration = 'null'::jsonb, models = '[]'::jsonb, capabilities =
  '{"court":{"tier":"core","status":"unknown"},"ball":{"tier":"core","status":"unknown"},"reviews":{"tier":"core","status":"unknown"},"score":{"tier":"core","status":"unknown"}}'::jsonb
WHERE id = 'a_ps';
INSERT INTO public.artifacts (id, analysis_id, match_id, owner_id, kind, r2_key)
VALUES ('art_ball_ps', 'a_ps', 'm_ps', '11111111-1111-1111-1111-111111111111', 'ball_track',
  'analysis/a_ps/engine/tracks/ball.json');

INSERT INTO public.vision_events (match_id, id, owner_id, analysis_id, type,
  t_ms, t_sigma_ms, image_xy, cues, state, evidence, contact_xy_m)
VALUES
  ('m_ps', 'e_h1', '11111111-1111-1111-1111-111111111111', 'a_ps', 'hit', 100, 10,
   '[1,2]'::jsonb, '["trajectory"]'::jsonb, 'candidate', '[{"t_ms":100}]'::jsonb, null),
  ('m_ps', 'e_h2', '11111111-1111-1111-1111-111111111111', 'a_ps', 'hit', 900, 10,
   '[3,4]'::jsonb, '["trajectory"]'::jsonb, 'candidate', '[{"t_ms":900}]'::jsonb, null),
  ('m_ps', 'e_l1', '11111111-1111-1111-1111-111111111111', 'a_ps', 'landing', 1500, 10,
   '[5,6]'::jsonb, '["trajectory"]'::jsonb, 'candidate', '[{"t_ms":1500}]'::jsonb, '[1.5,3.0]'::jsonb);

SET local role authenticated;
SELECT set_config('request.jwt.claim.sub', '11111111-1111-1111-1111-111111111111', TRUE);
INSERT INTO public.posts (owner_id, match_id, caption, summary)
VALUES ('11111111-1111-1111-1111-111111111111', 'm_ps', 'candidates', '{}'::jsonb);

SELECT is((SELECT (summary->>'hit_count')::int FROM public.posts WHERE caption = 'candidates'), 2,
  'candidate hits are counted (real engine detections)');
SELECT is((SELECT jsonb_array_length(summary->'landing_points_m') FROM public.posts WHERE caption = 'candidates'), 1,
  'a candidate landing with court metres and no plane field becomes a landing point');
SELECT is((SELECT summary->'landing_points_m'->0->>'x_m' FROM public.posts WHERE caption = 'candidates'), '1.5',
  'landing point keeps the stored court metres');
SELECT is((SELECT summary->'landings_in' FROM public.posts WHERE caption = 'candidates'), 'null'::jsonb,
  'landings in is null when the match has no reviews');
SELECT is((SELECT summary->'landings_out' FROM public.posts WHERE caption = 'candidates'), 'null'::jsonb,
  'landings out is null when the match has no reviews');

SELECT * FROM finish();
ROLLBACK;
