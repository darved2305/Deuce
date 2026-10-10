create or replace function public.ingest_vision_result(p_analysis_id text, p_result jsonb)
returns void language plpgsql set search_path = '' security definer as $$
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
      match_id, id, owner_id, analysis_id, rally_id, type, t_ms, t_sigma_ms,
      plane, contact_xy_m, cov_m2, cues, state, evidence
    ) values (
      v_run.match_id, v_row->>'event_id', v_run.owner_id, p_analysis_id,
      v_row->>'rally_id', v_row->>'type', (v_row->>'t_ms')::integer,
      (v_row->>'t_sigma_ms')::integer, v_row->>'plane', v_row->'contact_xy_m',
      v_row->'cov_m2', coalesce(v_row->'cues', '[]'::jsonb), v_row->>'state',
      coalesce(v_row->'evidence', '[]'::jsonb)
    );
  end loop;

  -- Contract issue #20 requires confirmed fields; skip estimate-only reviews without inventing them.
  for v_row in select value from jsonb_array_elements(coalesce(p_result->'reviews', '[]'::jsonb))
  loop
    if nullif(v_row->>'confirmed_point_winner', '') is null
       or nullif(v_row->>'confirmed_by', '') is null
       or nullif(v_row->>'confirmed_at', '') is null then
      continue;
    end if;
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

revoke all on function public.ingest_vision_result(text,jsonb) from public, anon, authenticated;
grant execute on function public.ingest_vision_result(text,jsonb) to service_role;
