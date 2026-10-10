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
       and v_who_is_me#>'{image_xy}' is not null and jsonb_array_length(v_h) = 9 then
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
    if v_rotate then
      v_shot := jsonb_set(v_shot, '{roles,focal,xy_m}', jsonb_build_array(
        v_width - (v_shot#>>'{roles,focal,xy_m,0}')::numeric,
        v_length - (v_shot#>>'{roles,focal,xy_m,1}')::numeric));
      v_shot := jsonb_set(v_shot, '{roles,opponent,xy_m}', jsonb_build_array(
        v_width - (v_shot#>>'{roles,opponent,xy_m,0}')::numeric,
        v_length - (v_shot#>>'{roles,opponent,xy_m,1}')::numeric));
      v_shot := jsonb_set(v_shot, '{chosen,landing_xy_m}', jsonb_build_array(
        v_width - (v_shot#>>'{chosen,landing_xy_m,0}')::numeric,
        v_length - (v_shot#>>'{chosen,landing_xy_m,1}')::numeric));
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
    if v_rotate and v_row->'contact_xy_m' is not null then
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

  -- review rows deferred pending contract amendment
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

create or replace function public.run_insights(p_analysis_id text)
returns void language plpgsql set search_path = '' security definer as $$
declare
  v_run public.analysis_runs;
  v_metric public.metrics;
  v_clip record;
  v_moment text;
  v_clip_count integer;
begin
  select * into v_run
    from public.analysis_runs where id = p_analysis_id for update;
  if not found then
    raise exception 'analysis_not_found' using errcode = 'P0002';
  end if;
  if exists (select 1 from public.analysis_events
      where analysis_id = p_analysis_id and type = 'analysis.completed') then
    update public.analysis_runs set finalized_at = coalesce(finalized_at, now())
      where id = p_analysis_id;
    return;
  end if;

  select * into v_metric from public.metrics
    where analysis_id = p_analysis_id and tier <> 'unsupported' and denominator >= 5
    order by abs(numerator::numeric / denominator - 0.5) desc,
      denominator desc, key asc, player_id asc
    limit 1;
  if found then
    select m.id into v_moment from public.moments m
      where m.match_id = v_run.match_id and m.rally_id in (
        select r.id from public.rallies r
          where r.match_id = v_run.match_id and r.analysis_id = p_analysis_id
      )
      order by case when exists (
        select 1 from public.shots s
          where s.match_id = m.match_id and s.id = m.shot_id
            and s.analysis_id = p_analysis_id and s.player_id = v_metric.player_id
      ) then 0 else 1 end, m.start_ms, m.id
      limit 1;
    if v_moment is null then
      raise exception 'no_evidence_moment';
    end if;
    insert into public.insights (
      match_id, owner_id, kind, caption, count, visual, evidence_moment_ids, gate
    ) values (
      v_run.match_id, v_run.owner_id, 'hero',
      v_metric.numerator || ' of ' || v_metric.denominator || ' ' || replace(v_metric.key, '_', ' '),
      jsonb_build_object('num', v_metric.numerator, 'den', v_metric.denominator),
      jsonb_build_object('type', v_metric.key,
        'filter', jsonb_build_object('player', v_metric.player_id)),
      jsonb_build_array(v_moment), 'descriptive'
    );
  else
    select r.id as rally_id, r.end_ms - r.start_ms as duration_ms,
        c.id as clip_id, c.owner_label
      into v_clip
      from public.rallies r join public.clips c
        on c.match_id = r.match_id and (c.id = r.clip_id or c.rally_id = r.id)
      join public.artifacts a on a.id = c.artifact_id and a.match_id = c.match_id
      where r.match_id = v_run.match_id and r.analysis_id = p_analysis_id
        and r.state = 'observed' and c.artifact_id is not null
        and a.r2_key like 'clips/' || v_run.match_id || '/%'
      order by c.end_ms - c.start_ms desc, r.index asc, r.id asc, c.id asc
      limit 1;
    if found then
      select count(*)::integer into v_clip_count
        from public.rallies r join public.clips c
          on c.match_id = r.match_id and (c.id = r.clip_id or c.rally_id = r.id)
        join public.artifacts a on a.id = c.artifact_id and a.match_id = c.match_id
        where r.match_id = v_run.match_id and r.analysis_id = p_analysis_id
          and r.state = 'observed' and c.artifact_id is not null
          and a.r2_key like 'clips/' || v_run.match_id || '/%';
      select m.id into v_moment from public.moments m
        where m.match_id = v_run.match_id and m.rally_id = v_clip.rally_id
        order by m.start_ms, m.id limit 1;
      if v_moment is null then
        raise exception 'no_evidence_moment';
      end if;
      insert into public.insights (
        match_id, owner_id, kind, caption, count, visual, evidence_moment_ids, gate
      ) values (
        v_run.match_id, v_run.owner_id, 'hero',
        'Longest of ' || v_clip_count || ' observed rally clips',
        jsonb_build_object('num', 1, 'den', v_clip_count),
        jsonb_build_object('type', 'clip',
          'filter', jsonb_build_object('player', v_clip.owner_label)),
        jsonb_build_array(v_moment), 'descriptive'
      );
    end if;
  end if;

  perform public.append_event(p_analysis_id, 'analysis.completed', '{}'::jsonb);
  update public.analysis_runs set state = 'completed', finalized_at = coalesce(finalized_at, now()),
      updated_at = now()
    where id = p_analysis_id;
end $$;

revoke all on function public.ingest_vision_result(text,jsonb) from public, anon, authenticated;
revoke all on function public.run_insights(text) from public, anon, authenticated;
grant execute on function public.ingest_vision_result(text,jsonb) to service_role;
grant execute on function public.run_insights(text) to service_role;
