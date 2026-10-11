BEGIN;
SELECT plan(22);

INSERT INTO auth.users (instance_id, id, aud, role, email, encrypted_password,
  email_confirmed_at, created_at, updated_at)
VALUES ('00000000-0000-0000-0000-000000000000',
  '11111111-1111-1111-1111-111111111111', 'authenticated', 'authenticated',
  'p3-delete-a@elevate.dev', '', now(), now(), now()),
  ('00000000-0000-0000-0000-000000000000',
  '22222222-2222-2222-2222-222222222222', 'authenticated', 'authenticated',
  'p3-delete-b@elevate.dev', '', now(), now(), now());

INSERT INTO public.matches (id, owner_id, sport, format, ruleset_id, ruleset_version)
VALUES
  ('m_live', '11111111-1111-1111-1111-111111111111', 'badminton', 'singles', 'bwf_3x21', '2025-04'),
  ('m_completed', '11111111-1111-1111-1111-111111111111', 'badminton', 'singles', 'bwf_3x21', '2025-04'),
  ('m_failed', '11111111-1111-1111-1111-111111111111', 'badminton', 'singles', 'bwf_3x21', '2025-04'),
  ('m_lock', '11111111-1111-1111-1111-111111111111', 'badminton', 'singles', 'bwf_3x21', '2025-04');

INSERT INTO public.sessions (id, match_id, owner_id, mode, source, who_is_me)
VALUES
  ('s_live', 'm_live', '11111111-1111-1111-1111-111111111111', 'match', '{}'::jsonb, '{"image_xy":[1,1],"t_ms":0}'::jsonb),
  ('s_completed', 'm_completed', '11111111-1111-1111-1111-111111111111', 'match', '{}'::jsonb, '{"image_xy":[1,1],"t_ms":0}'::jsonb),
  ('s_failed', 'm_failed', '11111111-1111-1111-1111-111111111111', 'match', '{}'::jsonb, '{"image_xy":[1,1],"t_ms":0}'::jsonb),
  ('s_lock', 'm_lock', '11111111-1111-1111-1111-111111111111', 'match', '{}'::jsonb, '{"image_xy":[1,1],"t_ms":0}'::jsonb);

INSERT INTO public.analysis_runs (id, session_id, match_id, owner_id, idempotency_key, state)
VALUES
  ('a_live', 's_live', 'm_live', '11111111-1111-1111-1111-111111111111', 'live', 'awaiting_upload'),
  ('a_completed', 's_completed', 'm_completed', '11111111-1111-1111-1111-111111111111', 'completed', 'completed'),
  ('a_failed', 's_failed', 'm_failed', '11111111-1111-1111-1111-111111111111', 'failed', 'failed');

INSERT INTO public.uploads (session_id, owner_id, r2_key, r2_upload_id, bytes, sha256, status)
VALUES ('s_completed', '11111111-1111-1111-1111-111111111111',
  'orig/s_completed/source.mp4', 'upload-delete', 1, repeat('0', 64), 'completed');

INSERT INTO public.artifacts (id, analysis_id, match_id, owner_id, kind, r2_key)
VALUES
  ('art_delete_window', 'a_completed', 'm_completed', '11111111-1111-1111-1111-111111111111',
    'window_result', 'analysis/a_completed/0.3.0/windows/0.json'),
  ('art_delete_clip', 'a_completed', 'm_completed', '11111111-1111-1111-1111-111111111111',
    'clip', 'clips/m_completed/c_delete.mp4'),
  ('art_delete_poster', 'a_completed', 'm_completed', '11111111-1111-1111-1111-111111111111',
    'poster', 'clips/m_completed/c_delete.jpg');

SET LOCAL ROLE authenticated;
SET LOCAL request.jwt.claim.sub = '22222222-2222-2222-2222-222222222222';
SELECT is((SELECT count(*)::integer FROM public.matches WHERE id = 'm_live'), 0,
  'a second user cannot see the match through RLS');
SELECT is((SELECT count(*)::integer FROM public.analysis_runs WHERE match_id = 'm_live'), 0,
  'a second user cannot see its analysis runs through RLS');
SELECT throws_ok($$SELECT public.delete_match('m_live', '11111111-1111-1111-1111-111111111111')$$,
  '42501', NULL, 'a second user cannot execute the privileged delete RPC');
RESET ROLE;

SELECT throws_ok($$SELECT public.delete_match('m_completed', '22222222-2222-2222-2222-222222222222')$$,
  'P0002', 'match_not_found', 'a mismatched owner is indistinguishable from a missing match');
SELECT is((SELECT count(*)::integer FROM public.matches WHERE id = 'm_completed'), 1,
  'a mismatched owner cannot delete the match');

SELECT throws_ok($$SELECT public.delete_match('m_live', '11111111-1111-1111-1111-111111111111')$$,
  'P0001', 'analysis_in_progress', 'awaiting_upload blocks match deletion');
UPDATE public.analysis_runs SET state = 'uploaded' WHERE id = 'a_live';
SELECT throws_ok($$SELECT public.delete_match('m_live', '11111111-1111-1111-1111-111111111111')$$,
  'P0001', 'analysis_in_progress', 'uploaded blocks match deletion');
UPDATE public.analysis_runs SET state = 'queued' WHERE id = 'a_live';
SELECT throws_ok($$SELECT public.delete_match('m_live', '11111111-1111-1111-1111-111111111111')$$,
  'P0001', 'analysis_in_progress', 'queued blocks match deletion');
UPDATE public.analysis_runs SET state = 'processing' WHERE id = 'a_live';
SELECT throws_ok($$SELECT public.delete_match('m_live', '11111111-1111-1111-1111-111111111111')$$,
  'P0001', 'analysis_in_progress', 'processing blocks match deletion');
UPDATE public.analysis_runs SET state = 'partial' WHERE id = 'a_live';
SELECT throws_ok($$SELECT public.delete_match('m_live', '11111111-1111-1111-1111-111111111111')$$,
  'P0001', 'analysis_in_progress', 'partial blocks match deletion');
UPDATE public.analysis_runs SET state = 'finalizing' WHERE id = 'a_live';
SELECT throws_ok($$SELECT public.delete_match('m_live', '11111111-1111-1111-1111-111111111111')$$,
  'P0001', 'analysis_in_progress', 'finalizing blocks match deletion');
SELECT is((SELECT count(*)::integer FROM public.matches WHERE id = 'm_live'), 1,
  'the match remains after every non-terminal analysis check');

SELECT is((public.create_analysis_run('s_lock', 'lock-test')).state, 'uploaded',
  'create_analysis_run creates a run for a live match');
SELECT ok(EXISTS (
  SELECT 1 FROM pg_locks
  WHERE pid = pg_backend_pid()
    AND locktype = 'relation'
    AND relation = 'public.matches'::regclass
    AND mode = 'RowShareLock'
    AND granted
), 'create_analysis_run takes a parent-match row lock');

SELECT is(public.delete_match('m_completed', '11111111-1111-1111-1111-111111111111'),
  ARRAY[
    'analysis/a_completed/0.3.0/windows/0.json',
    'clips/m_completed/c_delete.jpg',
    'clips/m_completed/c_delete.mp4',
    'orig/s_completed/source.mp4'
  ]::text[], 'completed analysis deletion returns only its recorded object keys');
SELECT throws_ok($$SELECT public.create_analysis_run('s_completed', 'after-delete')$$,
  'P0002', 'session_not_found', 'a deleted match cannot start a new analysis run');
SELECT is(public.delete_match('m_failed', '11111111-1111-1111-1111-111111111111'),
  ARRAY[]::text[], 'failed terminal analysis permits match deletion');

SELECT is((SELECT count(*)::integer FROM public.matches WHERE id = 'm_completed'), 0,
  'the match row is deleted');
SELECT is((SELECT count(*)::integer FROM public.sessions WHERE match_id = 'm_completed'), 0,
  'sessions cascade with the match');
SELECT is((SELECT count(*)::integer FROM public.uploads WHERE session_id = 's_completed'), 0,
  'upload metadata cascades with the match');
SELECT is((SELECT count(*)::integer FROM public.analysis_runs WHERE match_id = 'm_completed'), 0,
  'analysis rows cascade with the match');
SELECT is((SELECT count(*)::integer FROM public.artifacts WHERE match_id = 'm_completed'), 0,
  'artifact metadata cascades with the match');

SELECT * FROM finish();
ROLLBACK;
