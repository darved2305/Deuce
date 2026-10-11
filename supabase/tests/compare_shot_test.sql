begin;
select plan(28);

insert into auth.users (instance_id, id, aud, role, email, encrypted_password, email_confirmed_at, created_at, updated_at)
values
  ('00000000-0000-0000-0000-000000000000', '11111111-1111-1111-1111-111111111111', 'authenticated', 'authenticated', 'p3-compare-a@elevate.dev', '', now(), now(), now()),
  ('00000000-0000-0000-0000-000000000000', '22222222-2222-2222-2222-222222222222', 'authenticated', 'authenticated', 'p3-compare-b@elevate.dev', '', now(), now(), now());

insert into public.matches (id, owner_id, sport, format, ruleset_id, ruleset_version)
values
  ('m_cmp_rank_q', '11111111-1111-1111-1111-111111111111', 'badminton', 'singles', 'bwf_rank', '2025-04'),
  ('m_cmp_rank_1', '11111111-1111-1111-1111-111111111111', 'badminton', 'singles', 'bwf_rank', '2025-04'),
  ('m_cmp_rank_2', '11111111-1111-1111-1111-111111111111', 'badminton', 'singles', 'bwf_rank', '2025-04'),
  ('m_cmp_rank_3', '11111111-1111-1111-1111-111111111111', 'badminton', 'singles', 'bwf_rank', '2025-04'),
  ('m_cmp_rank_4', '11111111-1111-1111-1111-111111111111', 'badminton', 'singles', 'bwf_rank', '2025-04'),
  ('m_cmp_rank_5', '11111111-1111-1111-1111-111111111111', 'badminton', 'singles', 'bwf_rank', '2025-04'),
  ('m_cmp_rank_6', '11111111-1111-1111-1111-111111111111', 'badminton', 'singles', 'bwf_rank', '2025-04'),
  ('m_cmp_threshold_q', '11111111-1111-1111-1111-111111111111', 'badminton', 'singles', 'bwf_threshold', '2025-04'),
  ('m_cmp_threshold_weight', '11111111-1111-1111-1111-111111111111', 'badminton', 'singles', 'bwf_threshold', '2025-04'),
  ('m_cmp_threshold_boundary', '11111111-1111-1111-1111-111111111111', 'badminton', 'singles', 'bwf_threshold', '2025-04'),
  ('m_cmp_threshold_under', '11111111-1111-1111-1111-111111111111', 'badminton', 'singles', 'bwf_threshold', '2025-04'),
  ('m_cmp_threshold_over', '11111111-1111-1111-1111-111111111111', 'badminton', 'singles', 'bwf_threshold', '2025-04'),
  ('m_cmp_threshold_wrong', '11111111-1111-1111-1111-111111111111', 'badminton', 'singles', 'bwf_other', '2025-04'),
  ('m_cmp_worked_q', '11111111-1111-1111-1111-111111111111', 'badminton', 'singles', 'bwf_worked', '2025-04'),
  ('m_cmp_worked_candidate', '11111111-1111-1111-1111-111111111111', 'badminton', 'singles', 'bwf_worked', '2025-04'),
  ('m_cmp_hist_1', '11111111-1111-1111-1111-111111111111', 'badminton', 'singles', 'bwf_worked', '2025-04'),
  ('m_cmp_hist_2', '11111111-1111-1111-1111-111111111111', 'badminton', 'singles', 'bwf_worked', '2025-04'),
  ('m_cmp_hist_3', '11111111-1111-1111-1111-111111111111', 'badminton', 'singles', 'bwf_worked', '2025-04'),
  ('m_cmp_shared_q', '11111111-1111-1111-1111-111111111111', 'badminton', 'singles', 'bwf_shared', '2025-04'),
  ('m_cmp_shared_compare', '22222222-2222-2222-2222-222222222222', 'badminton', 'singles', 'bwf_shared', '2025-04'),
  ('m_cmp_shared_read', '22222222-2222-2222-2222-222222222222', 'badminton', 'singles', 'bwf_shared', '2025-04'),
  ('m_cmp_shared_revoked', '22222222-2222-2222-2222-222222222222', 'badminton', 'singles', 'bwf_shared', '2025-04'),
  ('m_cmp_reference_q', '11111111-1111-1111-1111-111111111111', 'badminton', 'singles', 'bwf_reference', '2025-04'),
  ('m_cmp_reference_team', '22222222-2222-2222-2222-222222222222', 'badminton', 'singles', 'bwf_reference', '2025-04'),
  ('m_cmp_reference_public', '22222222-2222-2222-2222-222222222222', 'badminton', 'singles', 'bwf_reference', '2025-04'),
  ('m_cmp_doubles_q', '11111111-1111-1111-1111-111111111111', 'badminton', 'doubles', 'bwf_doubles', '2025-04'),
  ('m_cmp_doubles_candidate', '11111111-1111-1111-1111-111111111111', 'badminton', 'doubles', 'bwf_doubles', '2025-04'),
  ('m_cmp_empty_q', '11111111-1111-1111-1111-111111111111', 'badminton', 'singles', 'bwf_empty', '2025-04');

insert into public.sessions (id, match_id, owner_id, mode, source, who_is_me)
select 's_' || substring(m.id from 3), m.id, m.owner_id, 'match', '{}'::jsonb,
  '{"image_xy":[0,0],"t_ms":0}'::jsonb
from public.matches m
where m.id like 'm_cmp_%';

insert into public.analysis_runs (id, session_id, match_id, owner_id, idempotency_key, players)
select
  'a_' || substring(m.id from 3),
  's_' || substring(m.id from 3),
  m.id,
  m.owner_id,
  'cmp_' || m.id,
  '[{"player_id":"p_me","label":"you"}]'::jsonb
from public.matches m
where m.id like 'm_cmp_%';

insert into public.shots (
  match_id, id, owner_id, analysis_id, rally_id, index, player_id,
  contact_ms, phase, reference_plane, coordinate_frame_version,
  roles, incoming, chosen, context, outcome, quality
)
values
  (
    'm_cmp_rank_q', 'sh_cmp_rank_q', '11111111-1111-1111-1111-111111111111', 'a_cmp_rank_q', 'r_cmp_rank_q', 1, 'p_me',
    100, 'rally', 'court', 1,
    '{"focal":{"player_id":"p_me","xy_m":[1,1],"state":"observed","conf":0.9},"opponent":{"player_id":"p_opp","xy_m":[3,4],"state":"observed","conf":0.8}}'::jsonb,
    '{"source_zone":"F1","landing_zone":"B3","zones_state":"observed","family":"unknown"}'::jsonb,
    '{"target_zone":"B2","landing_xy_m":[4,5],"landing_state":"observed","family":"clear"}'::jsonb,
    '{"score_before":[0,0],"pressure_bucket":"low","rally_len_so_far":2,"state":"observed"}'::jsonb,
    '{"point_winner":"p_me","shots_to_end":1,"state":"user"}'::jsonb,
    '{"camera_quality_grade":"A"}'::jsonb
  ),
  (
    'm_cmp_threshold_q', 'sh_cmp_threshold_q', '11111111-1111-1111-1111-111111111111', 'a_cmp_threshold_q', 'r_cmp_threshold_q', 1, 'p_me',
    100, 'rally', 'court', 1,
    '{"focal":{"player_id":"p_me","xy_m":[0,0],"state":"observed","conf":0.9},"opponent":{"player_id":"p_opp","xy_m":[1,1],"state":"observed","conf":0.8}}'::jsonb,
    '{"source_zone":"F1","landing_zone":"B3","zones_state":"observed","family":"unknown"}'::jsonb,
    '{"target_zone":"B2","landing_xy_m":[4,5],"landing_state":"observed","family":"clear"}'::jsonb,
    '{"score_before":[0,0],"pressure_bucket":"low","rally_len_so_far":2,"state":"observed"}'::jsonb,
    '{"point_winner":"p_me","shots_to_end":1,"state":"user"}'::jsonb,
    '{"camera_quality_grade":"A"}'::jsonb
  ),
  (
    'm_cmp_worked_q', 'sh_cmp_worked_q', '11111111-1111-1111-1111-111111111111', 'a_cmp_worked_q', 'r_cmp_worked_q', 1, 'p_me',
    100, 'rally', 'court', 1,
    '{"focal":{"player_id":"p_me","xy_m":[1,1],"state":"observed","conf":0.9},"opponent":{"player_id":"p_opp","xy_m":[3,4],"state":"observed","conf":0.8}}'::jsonb,
    '{"source_zone":"F1","landing_zone":"B3","zones_state":"observed","family":"unknown"}'::jsonb,
    '{"target_zone":"B2","landing_xy_m":[4,5],"landing_state":"observed","family":"clear"}'::jsonb,
    '{"score_before":[0,0],"pressure_bucket":"low","rally_len_so_far":2,"state":"observed"}'::jsonb,
    '{"point_winner":"p_me","shots_to_end":1,"state":"user"}'::jsonb,
    '{"camera_quality_grade":"A"}'::jsonb
  ),
  (
    'm_cmp_shared_q', 'sh_cmp_shared_q', '11111111-1111-1111-1111-111111111111', 'a_cmp_shared_q', 'r_cmp_shared_q', 1, 'p_me',
    100, 'rally', 'court', 1,
    '{"focal":{"player_id":"p_me","xy_m":[1,1],"state":"observed","conf":0.9},"opponent":{"player_id":"p_opp","xy_m":[3,4],"state":"observed","conf":0.8}}'::jsonb,
    '{"source_zone":"F1","landing_zone":"B3","zones_state":"observed","family":"unknown"}'::jsonb,
    '{"target_zone":"B2","landing_xy_m":[4,5],"landing_state":"observed","family":"clear"}'::jsonb,
    '{"score_before":[0,0],"pressure_bucket":"low","rally_len_so_far":2,"state":"observed"}'::jsonb,
    '{"point_winner":"p_me","shots_to_end":1,"state":"user"}'::jsonb,
    '{"camera_quality_grade":"A"}'::jsonb
  ),
  (
    'm_cmp_reference_q', 'sh_cmp_reference_q', '11111111-1111-1111-1111-111111111111', 'a_cmp_reference_q', 'r_cmp_reference_q', 1, 'p_me',
    100, 'rally', 'court', 1,
    '{"focal":{"player_id":"p_me","xy_m":[1,1],"state":"observed","conf":0.9},"opponent":{"player_id":"p_opp","xy_m":[3,4],"state":"observed","conf":0.8}}'::jsonb,
    '{"source_zone":"F1","landing_zone":"B3","zones_state":"observed","family":"unknown"}'::jsonb,
    '{"target_zone":"B2","landing_xy_m":[4,5],"landing_state":"observed","family":"clear"}'::jsonb,
    '{"score_before":[0,0],"pressure_bucket":"low","rally_len_so_far":2,"state":"observed"}'::jsonb,
    '{"point_winner":"p_me","shots_to_end":1,"state":"user"}'::jsonb,
    '{"camera_quality_grade":"A"}'::jsonb
  ),
  (
    'm_cmp_empty_q', 'sh_cmp_empty_q', '11111111-1111-1111-1111-111111111111', 'a_cmp_empty_q', 'r_cmp_empty_q', 1, 'p_me',
    100, 'rally', 'court', 1,
    '{"focal":{"player_id":"p_me","xy_m":[1,1],"state":"observed","conf":0.9},"opponent":{"player_id":"p_opp","xy_m":[3,4],"state":"observed","conf":0.8}}'::jsonb,
    '{"source_zone":"F1","landing_zone":"B3","zones_state":"observed","family":"unknown"}'::jsonb,
    '{"target_zone":"B2","landing_xy_m":[4,5],"landing_state":"observed","family":"clear"}'::jsonb,
    '{"score_before":[0,0],"pressure_bucket":"low","rally_len_so_far":2,"state":"observed"}'::jsonb,
    '{"point_winner":"p_me","shots_to_end":1,"state":"user"}'::jsonb,
    '{"camera_quality_grade":"A"}'::jsonb
  );

insert into public.shots (
  match_id, id, owner_id, analysis_id, rally_id, index, player_id,
  contact_ms, phase, reference_plane, coordinate_frame_version,
  roles, incoming, chosen, context, outcome, quality
)
select
  m.id,
  'sh_cmp_rank_' || i::text,
  m.owner_id,
  'a_' || substring(m.id from 3),
  'r_cmp_rank_' || i::text,
  i,
  'p_me',
  i * 100,
  'rally',
  'court',
  1,
  jsonb_build_object(
    'focal', jsonb_build_object('player_id', 'p_me', 'xy_m', jsonb_build_array(1::numeric + i::numeric / 10, 1::numeric), 'state', 'observed', 'conf', 0.9),
    'opponent', jsonb_build_object('player_id', 'p_opp', 'xy_m', jsonb_build_array(3, 4), 'state', 'observed', 'conf', 0.8)
  ),
  '{"source_zone":"F1","landing_zone":"B3","zones_state":"observed","family":"unknown"}'::jsonb,
  '{"target_zone":"B2","landing_xy_m":[4,5],"landing_state":"observed","family":"clear"}'::jsonb,
  '{"score_before":[0,0],"pressure_bucket":"low","rally_len_so_far":2,"state":"observed"}'::jsonb,
  '{"point_winner":"p_opp","shots_to_end":1,"state":"candidate"}'::jsonb,
  '{"camera_quality_grade":"A"}'::jsonb
from generate_series(1, 6) as series(i)
join public.matches m on m.id = 'm_cmp_rank_' || i::text;

insert into public.clips (id, match_id, owner_id, shot_id, source, rights, start_ms, end_ms, artifact_id)
select
  'c_cmp_rank_' || i::text,
  m.id,
  m.owner_id,
  'sh_cmp_rank_' || i::text,
  'own',
  'own',
  0,
  1000,
  'art_cmp_rank_' || i::text
from generate_series(1, 6) as series(i)
join public.matches m on m.id = 'm_cmp_rank_' || i::text;

insert into public.shots (
  match_id, id, owner_id, analysis_id, rally_id, index, player_id,
  contact_ms, phase, reference_plane, coordinate_frame_version,
  roles, incoming, chosen, context, outcome, quality
)
select
  m.id,
  'sh_cmp_threshold_' || v.kind,
  m.owner_id,
  'a_' || substring(m.id from 3),
  'r_cmp_threshold_' || v.kind,
  1,
  'p_me',
  100,
  'rally',
  'court',
  1,
  jsonb_build_object(
    'focal', jsonb_build_object('player_id', 'p_me', 'xy_m', jsonb_build_array(0::numeric, d.diagonal * v.focal_delta), 'state', 'observed', 'conf', 0.9),
    'opponent', jsonb_build_object('player_id', 'p_opp', 'xy_m', jsonb_build_array(1::numeric + d.diagonal * v.opponent_delta, 1::numeric), 'state', 'observed', 'conf', 0.8)
  ),
  jsonb_build_object(
    'source_zone', v.source_zone,
    'landing_zone', v.landing_zone,
    'zones_state', v.zones_state,
    'family', 'unknown'
  ),
  jsonb_build_object(
    'target_zone', 'B2',
    'landing_xy_m', jsonb_build_array(4, 5),
    'landing_state', 'observed',
    'family', v.family
  ),
  jsonb_build_object(
    'score_before', jsonb_build_array(0, 0),
    'pressure_bucket', v.pressure_bucket,
    'rally_len_so_far', 2,
    'state', v.context_state
  ),
  '{"point_winner":"p_opp","shots_to_end":1,"state":"candidate"}'::jsonb,
  '{"camera_quality_grade":"A"}'::jsonb
from (values
  ('weight', 'm_cmp_threshold_weight', 0.10::numeric, 0.20::numeric, 'observed', 'smash', 'high', 'observed', 'F2', 'B2'),
  ('boundary', 'm_cmp_threshold_boundary', 0.60::numeric, 0.00::numeric, 'candidate', 'unknown', 'low', 'observed', 'F1', 'B3'),
  ('under', 'm_cmp_threshold_under', 0.00::numeric, 0.00::numeric, 'candidate', 'unknown', 'low', 'candidate', 'F1', 'B3'),
  ('over', 'm_cmp_threshold_over', 0.61::numeric, 0.00::numeric, 'candidate', 'unknown', 'low', 'observed', 'F1', 'B3'),
  ('wrong', 'm_cmp_threshold_wrong', 0.00::numeric, 0.00::numeric, 'observed', 'clear', 'low', 'observed', 'F1', 'B3')
) as v(kind, match_id, focal_delta, opponent_delta, zones_state, family, pressure_bucket, context_state, source_zone, landing_zone)
join public.matches m on m.id = v.match_id
cross join lateral (
  select sqrt(power(5.18::numeric, 2) + power(13.40::numeric / 2, 2)) as diagonal
) d;

insert into public.clips (id, match_id, owner_id, shot_id, source, rights, start_ms, end_ms, artifact_id)
select
  'c_cmp_threshold_' || v.kind,
  m.id,
  m.owner_id,
  'sh_cmp_threshold_' || v.kind,
  'own',
  'own',
  0,
  1000,
  'art_cmp_threshold_' || v.kind
from (values
  ('weight', 'm_cmp_threshold_weight'),
  ('boundary', 'm_cmp_threshold_boundary'),
  ('under', 'm_cmp_threshold_under'),
  ('over', 'm_cmp_threshold_over'),
  ('wrong', 'm_cmp_threshold_wrong')
) as v(kind, match_id)
join public.matches m on m.id = v.match_id;

insert into public.shots (
  match_id, id, owner_id, analysis_id, rally_id, index, player_id,
  contact_ms, phase, reference_plane, coordinate_frame_version,
  roles, incoming, chosen, context, outcome, quality
)
values
  (
    'm_cmp_worked_candidate', 'sh_cmp_worked_candidate', '11111111-1111-1111-1111-111111111111', 'a_cmp_worked_candidate', 'r_cmp_worked_candidate', 1, 'p_me',
    100, 'rally', 'court', 1,
    '{"focal":{"player_id":"p_me","xy_m":[1,1],"state":"observed","conf":0.9},"opponent":{"player_id":"p_opp","xy_m":[3,4],"state":"observed","conf":0.8}}'::jsonb,
    '{"source_zone":"F1","landing_zone":"B3","zones_state":"observed","family":"unknown"}'::jsonb,
    '{"target_zone":"B2","landing_xy_m":[4,5],"landing_state":"observed","family":"clear"}'::jsonb,
    '{"score_before":[0,0],"pressure_bucket":"low","rally_len_so_far":2,"state":"observed"}'::jsonb,
    '{"point_winner":"p_opp","shots_to_end":1,"state":"candidate"}'::jsonb,
    '{"camera_quality_grade":"A"}'::jsonb
  );
insert into public.clips (id, match_id, owner_id, shot_id, source, rights, start_ms, end_ms, artifact_id)
values ('c_cmp_worked_candidate', 'm_cmp_worked_candidate', '11111111-1111-1111-1111-111111111111', 'sh_cmp_worked_candidate', 'own', 'own', 0, 1000, 'art_cmp_worked_candidate');

insert into public.shots (
  match_id, id, owner_id, analysis_id, rally_id, index, player_id,
  contact_ms, phase, reference_plane, coordinate_frame_version,
  roles, incoming, chosen, context, outcome, quality
)
select
  m.id,
  'sh_cmp_hist_' || lpad(i::text, 2, '0'),
  m.owner_id,
  'a_' || substring(m.id from 3),
  'r_cmp_hist_' || lpad(i::text, 2, '0'),
  i,
  'p_me',
  i * 100,
  'rally',
  'court',
  1,
  '{"focal":{"player_id":"p_me","xy_m":[1,1],"state":"observed","conf":0.9},"opponent":{"player_id":"p_opp","xy_m":[3,4],"state":"observed","conf":0.8}}'::jsonb,
  '{"source_zone":"F1","landing_zone":"B3","zones_state":"observed","family":"unknown"}'::jsonb,
  '{"target_zone":"B2","landing_xy_m":[4,5],"landing_state":"observed","family":"clear"}'::jsonb,
  '{"score_before":[0,0],"pressure_bucket":"low","rally_len_so_far":2,"state":"observed"}'::jsonb,
  jsonb_build_object('point_winner', case when i <= 12 then 'p_me' else 'p_opp' end, 'shots_to_end', 1, 'state', 'user'),
  '{"camera_quality_grade":"A"}'::jsonb
from generate_series(1, 20) as series(i)
join public.matches m on m.id = case
  when i <= 7 then 'm_cmp_hist_1'
  when i <= 14 then 'm_cmp_hist_2'
  else 'm_cmp_hist_3'
end;

insert into public.shots (
  match_id, id, owner_id, analysis_id, rally_id, index, player_id,
  contact_ms, phase, reference_plane, coordinate_frame_version,
  roles, incoming, chosen, context, outcome, quality
)
select
  m.id,
  'sh_cmp_shared_' || v.kind,
  m.owner_id,
  'a_' || substring(m.id from 3),
  'r_cmp_shared_' || v.kind,
  1,
  'p_me',
  100,
  'rally',
  'court',
  1,
  '{"focal":{"player_id":"p_me","xy_m":[1,1],"state":"observed","conf":0.9},"opponent":{"player_id":"p_opp","xy_m":[3,4],"state":"observed","conf":0.8}}'::jsonb,
  '{"source_zone":"F1","landing_zone":"B3","zones_state":"observed","family":"unknown"}'::jsonb,
  '{"target_zone":"B2","landing_xy_m":[4,5],"landing_state":"observed","family":"clear"}'::jsonb,
  '{"score_before":[0,0],"pressure_bucket":"low","rally_len_so_far":2,"state":"observed"}'::jsonb,
  '{"point_winner":"p_opp","shots_to_end":1,"state":"candidate"}'::jsonb,
  '{"camera_quality_grade":"A"}'::jsonb
from (values
  ('active', 'm_cmp_shared_compare'),
  ('read', 'm_cmp_shared_read'),
  ('revoked', 'm_cmp_shared_revoked')
) as v(kind, match_id)
join public.matches m on m.id = v.match_id;

insert into public.clips (id, match_id, owner_id, shot_id, source, rights, start_ms, end_ms, artifact_id)
select
  'c_cmp_shared_' || v.kind,
  m.id,
  m.owner_id,
  'sh_cmp_shared_' || v.kind,
  'own',
  'own',
  0,
  1000,
  'art_cmp_shared_' || v.kind
from (values
  ('active', 'm_cmp_shared_compare'),
  ('read', 'm_cmp_shared_read'),
  ('revoked', 'm_cmp_shared_revoked')
) as v(kind, match_id)
join public.matches m on m.id = v.match_id;

insert into public.share_grants (id, match_id, owner_id, grantee_id, scope, revoked_at)
values
  ('sg_cmp_active', 'm_cmp_shared_compare', '22222222-2222-2222-2222-222222222222', '11111111-1111-1111-1111-111111111111', 'compare', null),
  ('sg_cmp_read', 'm_cmp_shared_read', '22222222-2222-2222-2222-222222222222', '11111111-1111-1111-1111-111111111111', 'read', null),
  ('sg_cmp_revoked', 'm_cmp_shared_revoked', '22222222-2222-2222-2222-222222222222', '11111111-1111-1111-1111-111111111111', 'compare', now());

insert into public.clips (id, match_id, owner_id, shot_id, source, rights, start_ms, end_ms, artifact_id, attribution)
values
  ('c_cmp_ref_public', 'm_cmp_reference_public', '22222222-2222-2222-2222-222222222222', 'sh_ref_a_public', 'public_cc', 'CC-BY-4.0', 0, 1000, 'art_cmp_ref_public', 'Attribution for the reference clip'),
  ('c_cmp_ref_team', 'm_cmp_reference_team', '22222222-2222-2222-2222-222222222222', 'sh_ref_z_team', 'team_reference', 'team-consented', 0, 1000, 'art_cmp_ref_team', null);

insert into public.reference_examples (id, owner_id, kind, clip_id, sport, ruleset_id, format, shot, rights, attribution)
values
  (
    'rx_cmp_public', '22222222-2222-2222-2222-222222222222', 'public_cc', 'c_cmp_ref_public', 'badminton', 'bwf_reference', 'singles',
    '{"rally_id":"r_ref_public","player_id":"p_me","phase":"rally","reference_plane":"court","roles":{"focal":{"player_id":"p_me","xy_m":[1,1],"state":"observed","conf":0.9},"opponent":{"player_id":"p_opp","xy_m":[3,4],"state":"observed","conf":0.8}},"incoming":{"source_zone":"F1","landing_zone":"B3","zones_state":"observed","family":"unknown"},"chosen":{"target_zone":"B2","landing_xy_m":[4,5],"landing_state":"observed","family":"clear"},"context":{"score_before":[0,0],"pressure_bucket":"low","rally_len_so_far":2,"state":"observed"},"outcome":{"point_winner":"p_me","shots_to_end":1,"state":"user"},"quality":{"camera_quality_grade":"A"}}'::jsonb,
    'CC-BY-4.0', 'Attribution for the reference clip'
  ),
  (
    'rx_cmp_team', '22222222-2222-2222-2222-222222222222', 'team_reference', 'c_cmp_ref_team', 'badminton', 'bwf_reference', 'singles',
    '{"rally_id":"r_ref_team","player_id":"p_me","phase":"rally","reference_plane":"court","roles":{"focal":{"player_id":"p_me","xy_m":[1,1],"state":"observed","conf":0.9},"opponent":{"player_id":"p_opp","xy_m":[3,4],"state":"observed","conf":0.8}},"incoming":{"source_zone":"F1","landing_zone":"B3","zones_state":"observed","family":"unknown"},"chosen":{"target_zone":"B2","landing_xy_m":[4,5],"landing_state":"observed","family":"clear"},"context":{"score_before":[0,0],"pressure_bucket":"low","rally_len_so_far":2,"state":"observed"},"outcome":{"point_winner":"p_me","shots_to_end":1,"state":"user"},"quality":{"camera_quality_grade":"A"}}'::jsonb,
    'team-consented', null
  );

insert into public.shots (
  match_id, id, owner_id, analysis_id, rally_id, index, player_id,
  contact_ms, phase, reference_plane, coordinate_frame_version,
  roles, incoming, chosen, context, outcome, quality
)
values
  (
    'm_cmp_doubles_q', 'sh_cmp_doubles_q', '11111111-1111-1111-1111-111111111111', 'a_cmp_doubles_q', 'r_cmp_doubles_q', 1, 'p_me',
    100, 'rally', 'court', 1,
    '{"focal":{"player_id":"p_me","xy_m":[2,4],"state":"observed","conf":0.9},"opponent":{"player_id":"p_opp","xy_m":[2,9],"state":"observed","conf":0.8}}'::jsonb,
    '{"source_zone":"F1","landing_zone":"B3","zones_state":"observed","family":"unknown"}'::jsonb,
    '{"target_zone":"B2","landing_xy_m":[4,5],"landing_state":"observed","family":"clear"}'::jsonb,
    '{"score_before":[0,0],"pressure_bucket":"low","rally_len_so_far":2,"state":"observed"}'::jsonb,
    '{"point_winner":"p_me","shots_to_end":1,"state":"user"}'::jsonb,
    '{"camera_quality_grade":"A"}'::jsonb
  ),
  (
    'm_cmp_doubles_candidate', 'sh_cmp_doubles_candidate', '11111111-1111-1111-1111-111111111111', 'a_cmp_doubles_candidate', 'r_cmp_doubles_candidate', 1, 'p_me',
    100, 'rally', 'court', 1,
    '{"focal":{"player_id":"p_me","xy_m":[2,4],"state":"observed","conf":0.9},"opponent":{"player_id":"p_opp","xy_m":[2,9],"state":"observed","conf":0.8}}'::jsonb,
    '{"source_zone":"F1","landing_zone":"B3","zones_state":"observed","family":"unknown"}'::jsonb,
    '{"target_zone":"B2","landing_xy_m":[4,5],"landing_state":"observed","family":"clear"}'::jsonb,
    '{"score_before":[0,0],"pressure_bucket":"low","rally_len_so_far":2,"state":"observed"}'::jsonb,
    '{"point_winner":"p_opp","shots_to_end":1,"state":"candidate"}'::jsonb,
    '{"camera_quality_grade":"A"}'::jsonb
  );
insert into public.clips (id, match_id, owner_id, shot_id, source, rights, start_ms, end_ms, artifact_id)
values ('c_cmp_doubles_candidate', 'm_cmp_doubles_candidate', '11111111-1111-1111-1111-111111111111', 'sh_cmp_doubles_candidate', 'own', 'own', 0, 1000, 'art_cmp_doubles_candidate');

set local role authenticated;
set local request.jwt.claims = '{"sub":"11111111-1111-1111-1111-111111111111","role":"authenticated"}';

select ok(has_function_privilege('authenticated', 'public.compare_shot(text,text,text,integer)', 'EXECUTE'), 'authenticated can execute comparisons');
select ok(not has_function_privilege('anon', 'public.compare_shot(text,text,text,integer)', 'EXECUTE'), 'anon cannot execute comparisons');
select ok(not (select prosecdef from pg_proc where oid = 'public.compare_shot(text,text,text,integer)'::regprocedure), 'compare_shot runs as the invoker');
select ok(private.has_compare_grant('m_cmp_shared_compare'), 'active compare grant is recognized');
select ok(not private.has_compare_grant('m_cmp_shared_read'), 'read-only grant does not authorize comparisons');
select ok(not private.has_compare_grant('m_cmp_shared_revoked'), 'revoked compare grant is ignored');

select is((select count(*)::int from public.compare_shot('m_cmp_rank_q', 'sh_cmp_rank_q', 'own', 99)), 5, 'limit is capped at five');
select results_eq(
  $$select shot_id from public.compare_shot('m_cmp_rank_q', 'sh_cmp_rank_q', 'own', 99)$$,
  $$values ('sh_cmp_rank_1'), ('sh_cmp_rank_2'), ('sh_cmp_rank_3'), ('sh_cmp_rank_4'), ('sh_cmp_rank_5')$$,
  'ranked candidates are ordered by their exact distance'
);
select is((select count(*)::int from public.compare_shot('m_cmp_rank_q', 'sh_cmp_rank_q', 'own', 3)), 3, 'requested limit below five is respected');
select ok(abs((select distance from public.compare_shot('m_cmp_threshold_q', 'sh_cmp_threshold_q', 'own', 5) where shot_id = 'sh_cmp_threshold_weight') - 0.295::numeric) < 0.00000001::numeric, 'distance uses all five specified weights');
select is(
  (select matched_features from public.compare_shot('m_cmp_threshold_q', 'sh_cmp_threshold_q', 'own', 5) where shot_id = 'sh_cmp_threshold_weight'),
  array['focal_position', 'opponent_geometry', 'zones', 'shot_family', 'pressure_bucket']::text[],
  'all observed comparison features are reported'
);
select ok(abs((select distance from public.compare_shot('m_cmp_threshold_q', 'sh_cmp_threshold_q', 'own', 5) where shot_id = 'sh_cmp_threshold_boundary') - 0.30::numeric) < 0.00000001::numeric, 'distance at the 0.30 boundary is included');
select is(
  (select matched_features from public.compare_shot('m_cmp_threshold_q', 'sh_cmp_threshold_q', 'own', 5) where shot_id = 'sh_cmp_threshold_boundary'),
  array['focal_position', 'opponent_geometry', 'pressure_bucket']::text[],
  'exactly 70 percent observed weight is included'
);
select is((select count(*)::int from public.compare_shot('m_cmp_threshold_q', 'sh_cmp_threshold_q', 'own', 5) where shot_id = 'sh_cmp_threshold_under'), 0, 'less than 70 percent observed weight is excluded');
select is((select count(*)::int from public.compare_shot('m_cmp_threshold_q', 'sh_cmp_threshold_q', 'own', 5) where shot_id = 'sh_cmp_threshold_over'), 0, 'distance above 0.30 is excluded');
select is((select count(*)::int from public.compare_shot('m_cmp_threshold_q', 'sh_cmp_threshold_q', 'own', 5) where shot_id = 'sh_cmp_threshold_wrong'), 0, 'ruleset_id mismatch is excluded');
select is(
  (select matched_features from public.compare_shot('m_cmp_doubles_q', 'sh_cmp_doubles_q', 'own', 5) where shot_id = 'sh_cmp_doubles_candidate'),
  array['focal_position', 'zones', 'shot_family', 'pressure_bucket']::text[],
  'doubles without separate opponent positions omit only the unavailable opponent feature'
);
select is((select role from public.compare_shot('m_cmp_worked_q', 'sh_cmp_worked_q', 'own', 5) where shot_id = 'sh_cmp_worked_candidate'), 'worked_for_you', 'evidence gate enables worked_for_you');
select is(
  (select evidence from public.compare_shot('m_cmp_worked_q', 'sh_cmp_worked_q', 'own', 5) where shot_id = 'sh_cmp_worked_candidate'),
  '{"won":12,"of":20,"matches":3,"rallies":20}'::jsonb,
  'worked-for-you evidence counts confirmed wins, decisions, matches, and rallies'
);
select is((select provenance from public.compare_shot('m_cmp_rank_q', 'sh_cmp_rank_q', 'own', 5) limit 1), 'own', 'own scope returns own provenance');
select results_eq(
  $$select shot_id || ':' || provenance from public.compare_shot('m_cmp_shared_q', 'sh_cmp_shared_q', 'shared', 5)$$,
  $$values ('sh_cmp_shared_active:shared')$$,
  'shared scope returns only a candidate with an active compare grant'
);
select is((select count(*)::int from public.compare_shot('m_cmp_shared_q', 'sh_cmp_shared_q', 'shared', 5) where shot_id = 'sh_cmp_shared_read'), 0, 'read-only share grants are not comparison grants');
select is((select count(*)::int from public.compare_shot('m_cmp_shared_q', 'sh_cmp_shared_q', 'shared', 5) where shot_id = 'sh_cmp_shared_revoked'), 0, 'revoked shares do not return candidates');
select results_eq(
  $$select provenance || ':' || shot_id from public.compare_shot('m_cmp_reference_q', 'sh_cmp_reference_q', 'reference', 5)$$,
  $$values ('team_reference:sh_ref_z_team'), ('public_cc:sh_ref_a_public')$$,
  'team references rank ahead of public CC when distances tie'
);
select is((select attribution from public.compare_shot('m_cmp_reference_q', 'sh_cmp_reference_q', 'reference', 5) where provenance = 'public_cc'), 'Attribution for the reference clip', 'public CC attribution is returned');
select is((select count(*)::int from public.compare_shot('m_cmp_empty_q', 'sh_cmp_empty_q', 'own', 5)), 0, 'no suitable candidate returns an empty set');

reset role;
set local role authenticated;
set local request.jwt.claims = '{"sub":"22222222-2222-2222-2222-222222222222","role":"authenticated"}';
select is((select count(*)::int from public.matches where id = 'm_cmp_shared_q'), 0, 'a second user cannot read an unshared query match');
select is((select count(*)::int from public.compare_shot('m_cmp_shared_q', 'sh_cmp_shared_q', 'shared', 5)), 0, 'a second user cannot compare another owner’s query shot');

reset role;
select * from finish();
rollback;
