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
    order by abs(numerator::float / denominator - 0.5) desc,
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
    select r.id as rally_id, c.end_ms - c.start_ms as duration_ms,
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

revoke all on function public.run_insights(text) from public, anon, authenticated;
grant execute on function public.run_insights(text) to service_role;
