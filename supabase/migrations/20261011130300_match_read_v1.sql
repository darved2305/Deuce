ALTER TABLE public.analysis_events ADD COLUMN windows_expected integer, ADD
  CONSTRAINT analysis_events_windows_expected_check CHECK (windows_expected >=
  0);

ALTER TABLE public.vision_events ADD COLUMN player_id text, ADD COLUMN track_id
  text, ADD COLUMN image_xy jsonb;

CREATE policy matches_share_select ON public.matches FOR
SELECT TO authenticated USING ((SELECT private.has_share_grant(id)));
CREATE policy analysis_runs_share_select ON public.analysis_runs FOR
SELECT TO authenticated USING ((SELECT private.has_share_grant(match_id)));
CREATE policy artifacts_share_select ON public.artifacts FOR
SELECT TO authenticated USING ((SELECT private.has_share_grant(match_id)));
CREATE policy rallies_share_select ON public.rallies FOR
SELECT TO authenticated USING ((SELECT private.has_share_grant(match_id)));
CREATE policy shots_share_select ON public.shots FOR
SELECT TO authenticated USING ((SELECT private.has_share_grant(match_id)));
CREATE policy vision_events_share_select ON public.vision_events FOR
SELECT TO authenticated USING ((SELECT private.has_share_grant(match_id)));
CREATE policy reviews_share_select ON public.reviews FOR
SELECT TO authenticated USING ((SELECT private.has_share_grant(match_id)));
CREATE policy confirmed_points_share_select ON public.confirmed_points FOR
SELECT TO authenticated USING ((SELECT private.has_share_grant(match_id)));
CREATE policy corrections_share_select ON public.corrections FOR
SELECT TO authenticated USING ((SELECT private.has_share_grant(match_id)));
CREATE policy metrics_share_select ON public.metrics FOR
SELECT TO authenticated USING ((SELECT private.has_share_grant(match_id)));
CREATE policy insights_share_select ON public.insights FOR
SELECT TO authenticated USING ((SELECT private.has_share_grant(match_id)));

CREATE OR replace function public.append_event(p_analysis_id text, p_type text,
  p_fields jsonb) returns public.analysis_events language plpgsql SET
  search_path = '' AS $$
declare
  v_run public.analysis_runs;
  v_row public.analysis_events;
  v_next text;
  v_ws integer := (p_fields->>'window_seq')::integer;
  v_windows_expected integer := (p_fields->>'windows_expected')::integer;
begin
  select * into v_run from public.analysis_runs where id = p_analysis_id for update;
  if not found then raise exception 'analysis_not_found' using errcode = 'P0002'; end if;
  if p_type <> 'point.confirmed' then
    select * into v_row from public.analysis_events
      where analysis_id = p_analysis_id and type = p_type and window_seq is not distinct from v_ws;
    if found then return v_row; end if;
  end if;
  v_next := case p_type when 'analysis.queued' then 'queued' when 'analysis.started' then 'processing'
    when 'window.completed' then 'partial' when 'analysis.finalizing' then 'finalizing'
    when 'analysis.completed' then 'completed' when 'analysis.failed' then 'failed' end;
  if v_next is null and v_run.state in ('completed','failed') and p_type <> 'point.confirmed' then
    raise exception 'invalid_transition' using errcode = 'P0001', detail = v_run.state || ' is terminal';
  end if;
  if v_next is not null and not (v_run.state, v_next) in (
      ('uploaded','queued'), ('uploaded','failed'), ('queued','processing'), ('queued','partial'), ('queued','failed'),
      ('processing','partial'), ('processing','finalizing'), ('processing','failed'),
      ('partial','partial'), ('partial','finalizing'), ('partial','failed'),
      ('finalizing','completed'), ('finalizing','failed')) then
    raise exception 'invalid_transition' using errcode = 'P0001', detail = v_run.state || ' -> ' || v_next;
  end if;
  update public.analysis_runs set last_seq = last_seq + 1, updated_at = now(),
         state = coalesce(v_next, state),
         engine_version = coalesce(p_fields->>'engine_version', engine_version),
         failure_reason = case when v_next = 'failed' then p_fields->>'reason' else failure_reason end
   where id = p_analysis_id returning * into v_run;
  insert into public.analysis_events (analysis_id, match_id, owner_id, seq, type, window_seq,
    windows_expected, decode_start_us, decode_end_us, emit_start_us, emit_end_us,
    artifact_id, reason, engine_version)
  values (p_analysis_id, v_run.match_id, v_run.owner_id, v_run.last_seq, p_type, v_ws,
    v_windows_expected, (p_fields->>'decode_start_us')::bigint,
    (p_fields->>'decode_end_us')::bigint, (p_fields->>'emit_start_us')::bigint,
    (p_fields->>'emit_end_us')::bigint, p_fields->>'artifact_id', p_fields->>'reason',
    p_fields->>'engine_version')
  returning * into v_row;
  begin
    perform realtime.send(jsonb_build_object('schema_version',1,'analysis_id',p_analysis_id,'latest_seq',v_row.seq),
                          'analysis_seq', 'analysis:' || p_analysis_id, true);
  exception when others then raise warning 'broadcast_failed: %', sqlerrm;
  end;
  return v_row;
end $$;
revoke ALL ON function public.append_event(text,text,jsonb)
FROM public, anon, authenticated;
grant execute ON function public.append_event(text,text,jsonb) TO service_role;

CREATE OR replace function public.ingest_vision_result(p_analysis_id text,
  p_result jsonb) returns void language plpgsql SET search_path = '' security
  definer AS $$
declare
  v_run public.analysis_runs;
  v_who_is_me jsonb;
  v_h jsonb;
  v_template text;
  v_width numeric;
  v_length numeric;
  v_y numeric;
  v_den numeric;
  v_rotate boolean := false;
  v_row jsonb;
  v_shot jsonb;
  v_reason text;
begin
  select * into v_run
    from public.analysis_runs where id = p_analysis_id for update;
  if not found then
    raise exception 'analysis_not_found' using errcode = 'P0002';
  end if;
  if v_run.finalized_at is not null or v_run.state = 'completed' then
    raise exception 'already_finalized' using errcode = 'P0001';
  end if;
  if p_result->>'match_id' is distinct from v_run.match_id
     or p_result#>>'{analysis,analysis_id}' is distinct from p_analysis_id then
    raise exception 'invalid_vision_result' using errcode = 'P0001';
  end if;

  select s.who_is_me into v_who_is_me
    from public.sessions s where s.id = v_run.session_id;
  v_template := p_result#>>'{calibration,template}';
  if p_result#>>'{calibration,plane}' = 'court' then
    case v_template
      when 'badminton_singles' then v_width := 5.18; v_length := 13.40;
      when 'badminton_doubles' then v_width := 6.10; v_length := 13.40;
      when 'tennis_singles' then v_width := 8.23; v_length := 23.77;
      when 'tennis_doubles' then v_width := 10.97; v_length := 23.77;
      else v_width := null; v_length := null;
    end case;
    v_h := p_result#>'{calibration,H}';
    if v_width is not null and v_length is not null
       and jsonb_array_length(case when jsonb_typeof(v_who_is_me->'image_xy') = 'array'
         then v_who_is_me->'image_xy' else '[]'::jsonb end) = 2
       and jsonb_array_length(case when jsonb_typeof(v_h) = 'array'
         then v_h else '[]'::jsonb end) = 9 then
      v_den := (v_h->>6)::numeric * (v_who_is_me#>>'{image_xy,0}')::numeric
        + (v_h->>7)::numeric * (v_who_is_me#>>'{image_xy,1}')::numeric
        + (v_h->>8)::numeric;
      if v_den <> 0 then
        v_y := ((v_h->>3)::numeric * (v_who_is_me#>>'{image_xy,0}')::numeric
          + (v_h->>4)::numeric * (v_who_is_me#>>'{image_xy,1}')::numeric
          + (v_h->>5)::numeric) / v_den;
        v_rotate := v_y > v_length / 2;
      end if;
    end if;
  end if;

  for v_row in select value from jsonb_array_elements(coalesce(p_result->'rallies', '[]'::jsonb))
  loop
    insert into public.rallies (
      match_id, id, owner_id, analysis_id, index, start_ms, end_ms, shot_ids,
      server, winner, winner_state, end_reason, ball_coverage, clip_id, state
    ) values (
      v_run.match_id, v_row->>'rally_id', v_run.owner_id, p_analysis_id,
      (v_row->>'index')::integer, (v_row->>'start_ms')::integer,
      (v_row->>'end_ms')::integer, v_row->'shot_ids', v_row->>'server',
      v_row->>'winner', v_row->>'winner_state', v_row->>'end_reason',
      (v_row->>'ball_coverage')::real, v_row->>'clip_id', v_row->>'state'
    );
  end loop;

  for v_row in select value from jsonb_array_elements(coalesce(p_result->'shots', '[]'::jsonb))
  loop
    v_shot := v_row;
    if v_rotate and v_shot->>'reference_plane' = 'court' then
      if jsonb_array_length(case
        when jsonb_typeof(v_shot#>'{roles,focal,xy_m}') = 'array'
        then v_shot#>'{roles,focal,xy_m}' else '[]'::jsonb end) = 2 then
        v_shot := jsonb_set(v_shot, '{roles,focal,xy_m}', jsonb_build_array(
          v_width - (v_shot#>>'{roles,focal,xy_m,0}')::numeric,
          v_length - (v_shot#>>'{roles,focal,xy_m,1}')::numeric));
      end if;
      if jsonb_array_length(case
        when jsonb_typeof(v_shot#>'{roles,opponent,xy_m}') = 'array'
        then v_shot#>'{roles,opponent,xy_m}' else '[]'::jsonb end) = 2 then
        v_shot := jsonb_set(v_shot, '{roles,opponent,xy_m}', jsonb_build_array(
          v_width - (v_shot#>>'{roles,opponent,xy_m,0}')::numeric,
          v_length - (v_shot#>>'{roles,opponent,xy_m,1}')::numeric));
      end if;
      if jsonb_array_length(case
        when jsonb_typeof(v_shot#>'{chosen,landing_xy_m}') = 'array'
        then v_shot#>'{chosen,landing_xy_m}' else '[]'::jsonb end) = 2 then
        v_shot := jsonb_set(v_shot, '{chosen,landing_xy_m}', jsonb_build_array(
          v_width - (v_shot#>>'{chosen,landing_xy_m,0}')::numeric,
          v_length - (v_shot#>>'{chosen,landing_xy_m,1}')::numeric));
      end if;
      v_shot := jsonb_set(v_shot, '{incoming}', (v_shot->'incoming') || jsonb_build_object(
        'source_zone', case v_shot#>>'{incoming,source_zone}'
          when 'F1' then 'B3' when 'F2' then 'B2' when 'F3' then 'B1'
          when 'M1' then 'M3' when 'M3' then 'M1'
          when 'B1' then 'F3' when 'B2' then 'F2' when 'B3' then 'F1'
          else v_shot#>>'{incoming,source_zone}' end,
        'landing_zone', case v_shot#>>'{incoming,landing_zone}'
          when 'F1' then 'B3' when 'F2' then 'B2' when 'F3' then 'B1'
          when 'M1' then 'M3' when 'M3' then 'M1'
          when 'B1' then 'F3' when 'B2' then 'F2' when 'B3' then 'F1'
          else v_shot#>>'{incoming,landing_zone}' end));
      v_shot := jsonb_set(v_shot, '{chosen}', (v_shot->'chosen') || jsonb_build_object(
        'target_zone', case v_shot#>>'{chosen,target_zone}'
          when 'F1' then 'B3' when 'F2' then 'B2' when 'F3' then 'B1'
          when 'M1' then 'M3' when 'M3' then 'M1'
          when 'B1' then 'F3' when 'B2' then 'F2' when 'B3' then 'F1'
          else v_shot#>>'{chosen,target_zone}' end));
    end if;
    insert into public.shots (
      match_id, id, owner_id, analysis_id, rally_id, index, player_id,
      contact_ms, contact_sigma_ms, phase, reference_plane, coordinate_frame_version,
      roles, incoming, chosen, flight_time_ms, flight_time_state, context, outcome,
      quality, clip_id
    ) values (
      v_run.match_id, v_shot->>'shot_id', v_run.owner_id, p_analysis_id,
      v_shot->>'rally_id', (v_shot->>'index')::integer, v_shot->>'player_id',
      (v_shot->>'contact_ms')::integer, (v_shot->>'contact_sigma_ms')::integer,
      v_shot->>'phase', v_shot->>'reference_plane',
      (v_shot->>'coordinate_frame_version')::integer, v_shot->'roles',
      v_shot->'incoming', v_shot->'chosen', (v_shot->>'flight_time_ms')::integer,
      v_shot->>'flight_time_state', v_shot->'context', v_shot->'outcome',
      v_shot->'quality', v_shot->>'clip_id'
    );
  end loop;

  for v_row in select value from jsonb_array_elements(coalesce(p_result->'events', '[]'::jsonb))
  loop
    if v_rotate and v_row->>'plane' = 'court'
       and jsonb_array_length(case when jsonb_typeof(v_row->'contact_xy_m') = 'array'
         then v_row->'contact_xy_m' else '[]'::jsonb end) = 2 then
      v_row := jsonb_set(v_row, '{contact_xy_m}', jsonb_build_array(
        v_width - (v_row#>>'{contact_xy_m,0}')::numeric,
        v_length - (v_row#>>'{contact_xy_m,1}')::numeric));
    end if;
    insert into public.vision_events (
      match_id, id, owner_id, analysis_id, rally_id, type, player_id, track_id,
      t_ms, t_sigma_ms, plane, contact_xy_m, cov_m2, image_xy, cues, state, evidence
    ) values (
      v_run.match_id, v_row->>'event_id', v_run.owner_id, p_analysis_id,
      v_row->>'rally_id', v_row->>'type', v_row->>'player_id', v_row->>'track_id',
      (v_row->>'t_ms')::integer, (v_row->>'t_sigma_ms')::integer, v_row->>'plane',
      v_row->'contact_xy_m', v_row->'cov_m2', v_row->'image_xy',
      coalesce(v_row->'cues', '[]'::jsonb), v_row->>'state',
      coalesce(v_row->'evidence', '[]'::jsonb)
    );
  end loop;

  for v_row in select value from jsonb_array_elements(coalesce(p_result->'reviews', '[]'::jsonb))
  loop
    insert into public.reviews (
      match_id, id, owner_id, analysis_id, rally_id, event_id, t_ms,
      evidence_frames, contact_xy_m, cov_m2, line_id, margin_m, sigma_m,
      estimate, visibility, model_ids, calibration_id,
      confirmed_point_winner, confirmed_by, confirmed_at
    ) values (
      v_run.match_id, v_row->>'review_id', v_run.owner_id, p_analysis_id,
      v_row->>'rally_id', v_row->>'event_id', (v_row->>'t_ms')::integer,
      coalesce(v_row->'evidence_frames', '[]'::jsonb), v_row->'contact_xy_m',
      v_row->'cov_m2', v_row->>'line_id', (v_row->>'margin_m')::real,
      (v_row->>'sigma_m')::real, v_row->>'estimate', v_row->>'visibility',
      coalesce(v_row->'model_ids', '[]'::jsonb), v_row->>'calibration_id',
      v_row->>'confirmed_point_winner', v_row->>'confirmed_by',
      (v_row->>'confirmed_at')::timestamptz
    );
  end loop;

  for v_row in select value from jsonb_array_elements(coalesce(p_result->'metrics', '[]'::jsonb))
  loop
    insert into public.metrics (
      match_id, owner_id, analysis_id, key, definition_version, player_id,
      numerator, denominator, unit, tier, source_event_ids
    ) values (
      v_run.match_id, v_run.owner_id, p_analysis_id, v_row->>'key',
      (v_row->>'definition_version')::integer, v_row->>'player_id',
      (v_row->>'numerator')::integer, (v_row->>'denominator')::integer,
      v_row->>'unit', v_row->>'tier', coalesce(v_row->'source_event_ids', '[]'::jsonb)
    );
  end loop;

  for v_row in select value from jsonb_array_elements(coalesce(p_result->'moments', '[]'::jsonb))
  loop
    insert into public.moments (
      id, match_id, owner_id, rally_id, shot_id, start_ms, end_ms, contact_ms, tags, clip_id
    ) values (
      v_row->>'moment_id', v_run.match_id, v_run.owner_id, v_row->>'rally_id',
      v_row->>'shot_id', (v_row->>'start_ms')::integer, (v_row->>'end_ms')::integer,
      (v_row->>'contact_ms')::integer, coalesce(v_row->'tags', '[]'::jsonb),
      v_row->>'clip_id'
    );
  end loop;

  for v_row in select value from jsonb_array_elements(coalesce(p_result->'clips', '[]'::jsonb))
  loop
    insert into public.clips (
      id, match_id, owner_id, shot_id, rally_id, source, rights, owner_label,
      start_ms, end_ms, contact_ms, artifact_id, poster_artifact_id, local, attribution
    ) values (
      v_row->>'clip_id', v_run.match_id, v_run.owner_id, v_row->>'shot_id',
      v_row->>'rally_id', v_row->>'source', v_row->>'rights', v_row->>'owner',
      (v_row->>'start_ms')::integer, (v_row->>'end_ms')::integer,
      (v_row->>'contact_ms')::integer, v_row#>>'{remote,artifact_id}',
      v_row#>>'{remote,poster_artifact_id}', v_row->'local', v_row->'attribution'
    );
  end loop;

  update public.analysis_runs
     set players = p_result->'players', calibration = p_result->'calibration',
         capabilities = p_result->'capabilities', models = p_result#>'{analysis,models}',
         engine_version = coalesce(engine_version, p_result#>>'{analysis,engine_version}'),
         finalized_at = coalesce(finalized_at, now()), updated_at = now()
   where id = p_analysis_id;
  perform public.append_event(p_analysis_id, 'analysis.finalizing', '{}'::jsonb);
  begin
    perform public.run_insights(p_analysis_id);
  exception when others then
    v_reason := left(sqlerrm, 120);
    perform public.append_event(p_analysis_id, 'analysis.failed',
      jsonb_build_object('reason', 'insights_failed:' || v_reason));
  end;
end $$;

CREATE OR replace function public.get_match_v1(p_match_id text) returns jsonb
  language plpgsql stable security invoker SET search_path = '' AS $$
declare
  v_match public.matches%rowtype;
  v_run public.analysis_runs%rowtype;
  v_user_player_count integer;
  v_user_player_id text;
  v_ball_track_id text;
  v_player_tracks_id text;
begin
  select * into v_match from public.matches where id = p_match_id;
  if not found then return null; end if;

  select * into v_run from public.analysis_runs
    where match_id = p_match_id
    order by created_at desc, id desc
    limit 1;
  if not found then return null; end if;

  select count(*)::integer, min(player.value->>'player_id')
    into v_user_player_count, v_user_player_id
    from jsonb_array_elements(case
      when jsonb_typeof(v_run.players) = 'array' then v_run.players
      else '[]'::jsonb
    end) as player(value)
    where player.value->>'identity' = 'user_confirmed';
  if v_user_player_count <> 1 or v_user_player_id is null
     or v_user_player_id !~ '^p_[A-Za-z0-9_-]+$' then
    v_user_player_id := null;
  end if;

  select a.id into v_ball_track_id from public.artifacts a
    where a.analysis_id = v_run.id and a.kind = 'ball_track'
    order by a.created_at desc, a.id desc limit 1;
  select a.id into v_player_tracks_id from public.artifacts a
    where a.analysis_id = v_run.id and a.kind = 'player_tracks'
    order by a.created_at desc, a.id desc limit 1;

  return jsonb_build_object(
    'schema', 'match.v1',
    'match_id', v_match.id,
    'session_ids', coalesce((
      select jsonb_agg(s.session_id order by s.session_id)
      from (
        select distinct r.session_id from public.analysis_runs r
        where r.match_id = v_match.id
      ) s
    ), '[]'::jsonb),
    'sport', v_match.sport,
    'format', v_match.format,
    'ruleset', jsonb_build_object('id', v_match.ruleset_id, 'version', v_match.ruleset_version),
    'players', case when jsonb_typeof(v_run.players) = 'array'
      then v_run.players else '[]'::jsonb end,
    'calibration', v_run.calibration,
    'analysis', jsonb_build_object(
      'analysis_id', v_run.id,
      'engine_version', v_run.engine_version,
      'state', v_run.state,
      'models', case when jsonb_typeof(v_run.models) = 'array'
        then v_run.models else '[]'::jsonb end,
      'artifacts', jsonb_build_object(
        'ball_track', v_ball_track_id,
        'player_tracks', v_player_tracks_id
      )
    ),
    'capabilities', v_run.capabilities,
    'rallies', coalesce((
      select jsonb_agg(jsonb_build_object(
        'rally_id', r.id,
        'index', r.index,
        'start_ms', r.start_ms,
        'end_ms', r.end_ms,
        'shot_ids', r.shot_ids,
        'server', r.server,
        'winner', r.winner,
        'winner_state', r.winner_state,
        'end_reason', r.end_reason,
        'ball_coverage', r.ball_coverage,
        'clip_id', r.clip_id,
        'state', r.state
      ) order by r.index, r.start_ms, r.id)
      from public.rallies r
      where r.match_id = v_match.id and r.analysis_id = v_run.id
    ), '[]'::jsonb),
    'shots', coalesce((
      select jsonb_agg(jsonb_build_object(
        'shot_id', s.id,
        'rally_id', s.rally_id,
        'index', s.index,
        'player_id', s.player_id,
        'contact_ms', s.contact_ms,
        'contact_sigma_ms', s.contact_sigma_ms,
        'phase', s.phase,
        'reference_plane', s.reference_plane,
        'coordinate_frame_version', s.coordinate_frame_version,
        'roles', s.roles,
        'incoming', s.incoming,
        'chosen', s.chosen,
        'flight_time_ms', s.flight_time_ms,
        'flight_time_state', s.flight_time_state,
        'context', s.context,
        'outcome', s.outcome,
        'quality', s.quality,
        'clip_id', s.clip_id
      ) order by s.rally_id, s.index, s.contact_ms, s.id)
      from public.shots s
      where s.match_id = v_match.id and s.analysis_id = v_run.id
    ), '[]'::jsonb),
    'events', coalesce((
      select jsonb_agg(jsonb_strip_nulls(jsonb_build_object(
        'event_id', e.id,
        'type', e.type,
        't_ms', e.t_ms,
        't_sigma_ms', e.t_sigma_ms,
        'rally_id', e.rally_id,
        'player_id', e.player_id,
        'track_id', e.track_id,
        'plane', e.plane,
        'contact_xy_m', e.contact_xy_m,
        'cov_m2', e.cov_m2,
        'image_xy', e.image_xy,
        'cues', coalesce(e.cues, '[]'::jsonb),
        'state', e.state,
        'evidence', coalesce(e.evidence, '[]'::jsonb)
      )) order by e.t_ms, e.id)
      from public.vision_events e
      where e.match_id = v_match.id and e.analysis_id = v_run.id
    ), '[]'::jsonb),
    'reviews', coalesce((
      select jsonb_agg(jsonb_build_object(
        'review_id', r.id,
        'match_id', r.match_id,
        'rally_id', r.rally_id,
        'event_id', r.event_id,
        't_ms', r.t_ms,
        'evidence_frames', r.evidence_frames,
        'contact_xy_m', r.contact_xy_m,
        'cov_m2', r.cov_m2,
        'line_id', r.line_id,
        'margin_m', r.margin_m,
        'sigma_m', r.sigma_m,
        'estimate', r.estimate,
        'visibility', r.visibility,
        'model_ids', r.model_ids,
        'calibration_id', r.calibration_id,
        'confirmed_point_winner', r.confirmed_point_winner,
        'confirmed_by', r.confirmed_by,
        'confirmed_at', r.confirmed_at
      ) order by r.t_ms, r.id)
      from public.reviews r
      where r.match_id = v_match.id and r.analysis_id = v_run.id
    ), '[]'::jsonb),
    'confirmed_points', case when v_user_player_id is null then '[]'::jsonb else coalesce((
      select jsonb_agg(jsonb_build_object(
        'confirmed_point_id', p.id,
        'rally_id', p.rally_id,
        'winner', p.winner,
        'by', v_user_player_id,
        'at', p.created_at,
        'review_id', p.review_id
      ) order by p.created_at, p.id)
      from public.confirmed_points p
      where p.match_id = v_match.id and p.owner_id = v_run.owner_id
        and p.confirmed_by = v_run.owner_id and p.review_id is not null
    ), '[]'::jsonb) end,
    'score', coalesce(v_match.score, '{"timeline":[],"games":[]}'::jsonb),
    'metrics', coalesce((
      select jsonb_agg(jsonb_build_object(
        'key', m.key,
        'definition_version', m.definition_version,
        'player_id', m.player_id,
        'numerator', m.numerator,
        'denominator', m.denominator,
        'unit', m.unit,
        'tier', m.tier,
        'source_event_ids', coalesce(m.source_event_ids, '[]'::jsonb)
      ) order by m.key, m.definition_version, m.player_id)
      from public.metrics m
      where m.match_id = v_match.id and m.analysis_id = v_run.id
    ), '[]'::jsonb),
    'insights', coalesce((
      select jsonb_agg(jsonb_build_object(
        'insight_id', i.id,
        'kind', i.kind,
        'caption', i.caption,
        'count', i.count,
        'visual', i.visual,
        'evidence_moment_ids', coalesce(i.evidence_moment_ids, '[]'::jsonb),
        'gate', i.gate
      ) order by i.created_at, i.id)
      from public.insights i
      where i.match_id = v_match.id and exists (
        select 1
        from jsonb_array_elements_text(coalesce(i.evidence_moment_ids, '[]'::jsonb)) as evidence(moment_id)
        join public.moments m on m.id = evidence.moment_id and m.match_id = i.match_id
        where exists (
          select 1 from public.rallies r
          where r.match_id = m.match_id and r.id = m.rally_id and r.analysis_id = v_run.id
        )
      )
    ), '[]'::jsonb),
    'moments', coalesce((
      select jsonb_agg(jsonb_build_object(
        'moment_id', m.id,
        'rally_id', m.rally_id,
        'shot_id', m.shot_id,
        'start_ms', m.start_ms,
        'end_ms', m.end_ms,
        'contact_ms', m.contact_ms,
        'tags', coalesce(m.tags, '[]'::jsonb),
        'clip_id', m.clip_id
      ) order by m.start_ms, m.id)
      from public.moments m
      where m.match_id = v_match.id and exists (
        select 1 from public.rallies r
        where r.match_id = m.match_id and r.id = m.rally_id and r.analysis_id = v_run.id
      )
    ), '[]'::jsonb),
    'clips', coalesce((
      select jsonb_agg(jsonb_build_object(
        'clip_id', c.id,
        'source', c.source,
        'rights', c.rights,
        'owner', c.owner_label,
        'match_id', c.match_id,
        'start_ms', c.start_ms,
        'end_ms', c.end_ms,
        'contact_ms', c.contact_ms,
        'remote', jsonb_build_object(
          'artifact_id', c.artifact_id,
          'poster_artifact_id', c.poster_artifact_id
        ),
        'local', c.local,
        'attribution', c.attribution
      ) order by c.start_ms, c.id)
      from public.clips c
      where c.match_id = v_match.id and (
        exists (
          select 1 from public.rallies r
          where r.match_id = c.match_id and r.analysis_id = v_run.id and r.id = c.rally_id
        ) or exists (
          select 1 from public.shots s
          where s.match_id = c.match_id and s.analysis_id = v_run.id and s.id = c.shot_id
        )
      )
    ), '[]'::jsonb),
    'corrections', case when v_user_player_id is null then '[]'::jsonb else coalesce((
      select jsonb_agg(jsonb_build_object(
        'correction_id', c.id,
        'target', jsonb_build_object('type', c.target_type, 'id', c.target_id),
        'field', c.field,
        'old', c.old,
        'new', c.new,
        'actor', v_user_player_id,
        'at', c.created_at,
        'reason', c.reason
      ) order by c.created_at, c.id)
      from public.corrections c
      where c.match_id = v_match.id and c.owner_id = v_run.owner_id
        and c.actor = v_run.owner_id and c.reason is not null
    ), '[]'::jsonb) end
  );
end $$;

revoke ALL ON function public.get_match_v1(text)
FROM public, anon, authenticated;
grant execute ON function public.get_match_v1(text) TO authenticated;
