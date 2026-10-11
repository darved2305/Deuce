create or replace function private.has_compare_grant(p_match_id text)
returns boolean language sql stable security definer set search_path = ''
as $$
  select exists (
    select 1
    from public.share_grants g
    join public.matches m on m.id = g.match_id and m.owner_id = g.owner_id
    where g.match_id = p_match_id
      and g.grantee_id = (select auth.uid())
      and g.revoked_at is null
      and g.scope = 'compare'
  )
$$;
revoke all on function private.has_compare_grant(text) from public, anon;
grant execute on function private.has_compare_grant(text) to authenticated;

alter policy matches_select on public.matches
  using (
    owner_id = (select auth.uid())
    or (select private.has_share_grant(id))
  );

alter policy shots_select on public.shots
  using (
    owner_id = (select auth.uid())
    or (select private.has_share_grant(match_id))
  );

alter policy clips_select on public.clips
  using (
    owner_id = (select auth.uid())
    or (select private.has_share_grant(match_id))
    or (
      source in ('team_reference', 'public_cc')
      and artifact_id is not null
      and exists (
        select 1
        from public.reference_examples re
        where re.clip_id = clips.id
          and re.kind = clips.source
          and nullif(re.rights, '') is not null
          and (
            re.kind <> 'public_cc'
            or nullif(coalesce(re.attribution, clips.attribution), '') is not null
          )
      )
    )
  );

alter policy reference_examples_select on public.reference_examples
  using (
    owner_id = (select auth.uid())
    or (
      clip_id is not null
      and nullif(rights, '') is not null
    )
  );

create or replace function public.compare_shot(
  p_match_id text,
  p_shot_id text,
  p_scope text,
  p_limit integer
)
returns table (
  shot_id text,
  clip_id text,
  distance numeric,
  matched_features text[],
  role text,
  evidence jsonb,
  provenance text,
  attribution text
)
language sql stable security invoker set search_path = ''
as $$
  with query_shot as (
    select
      s.match_id,
      s.id as shot_id,
      s.rally_id,
      s.analysis_id,
      s.player_id,
      s.phase,
      s.reference_plane,
      s.roles,
      s.incoming,
      s.chosen,
      s.context,
      s.outcome,
      s.quality,
      m.owner_id,
      m.sport,
      m.ruleset_id,
      m.format
    from public.shots s
    join public.matches m on m.id = s.match_id
    where s.match_id = p_match_id
      and s.id = p_shot_id
      and s.owner_id = (select auth.uid())
      and m.owner_id = (select auth.uid())
  ),
  query_is_player as (
    select exists (
      select 1
      from query_shot q
      join public.analysis_runs ar on ar.id = q.analysis_id
      cross join lateral jsonb_array_elements(
        case when jsonb_typeof(ar.players) = 'array' then ar.players else '[]'::jsonb end
      ) as player_record(player)
      where player_record.player->>'player_id' = q.player_id
        and player_record.player->>'label' = 'you'
    ) as value
  ),
  candidate_sources as (
    select
      s.match_id,
      s.id as shot_id,
      s.rally_id,
      s.analysis_id,
      s.player_id,
      s.phase,
      s.reference_plane,
      s.roles,
      s.incoming,
      s.chosen,
      s.context,
      s.outcome,
      s.quality,
      m.owner_id,
      m.sport,
      m.ruleset_id,
      m.format,
      c.id as clip_id,
      'own'::text as provenance,
      c.attribution
    from public.shots s
    join public.matches m on m.id = s.match_id and m.owner_id = s.owner_id
    join public.clips c on c.match_id = s.match_id and c.shot_id = s.id
    where p_scope = 'own'
      and s.owner_id = (select auth.uid())
      and c.owner_id = (select auth.uid())
      and c.source = 'own'
      and (c.artifact_id is not null or nullif(c.local->>'source_uri', '') is not null)

    union all

    select
      s.match_id,
      s.id as shot_id,
      s.rally_id,
      s.analysis_id,
      s.player_id,
      s.phase,
      s.reference_plane,
      s.roles,
      s.incoming,
      s.chosen,
      s.context,
      s.outcome,
      s.quality,
      m.owner_id,
      m.sport,
      m.ruleset_id,
      m.format,
      c.id as clip_id,
      'shared'::text as provenance,
      c.attribution
    from public.shots s
    join public.matches m on m.id = s.match_id and m.owner_id = s.owner_id
    join public.clips c on c.match_id = s.match_id and c.shot_id = s.id
    where p_scope = 'shared'
      and m.owner_id <> (select auth.uid())
      and c.owner_id = m.owner_id
      and c.source in ('own', 'shared')
      and c.artifact_id is not null
      and (select private.has_compare_grant(s.match_id))

    union all

    select
      c.match_id,
      coalesce(c.shot_id, re.shot->>'shot_id') as shot_id,
      re.shot->>'rally_id' as rally_id,
      null::text as analysis_id,
      re.shot->>'player_id' as player_id,
      re.shot->>'phase' as phase,
      re.shot->>'reference_plane' as reference_plane,
      re.shot->'roles' as roles,
      re.shot->'incoming' as incoming,
      re.shot->'chosen' as chosen,
      re.shot->'context' as context,
      re.shot->'outcome' as outcome,
      re.shot->'quality' as quality,
      c.owner_id,
      re.sport,
      re.ruleset_id,
      re.format,
      c.id as clip_id,
      re.kind as provenance,
      coalesce(re.attribution, c.attribution) as attribution
    from public.reference_examples re
    join public.clips c on c.id = re.clip_id
    where p_scope = 'reference'
      and re.clip_id is not null
      and nullif(re.rights, '') is not null
      and c.source = re.kind
      and c.artifact_id is not null
      and coalesce(c.shot_id, re.shot->>'shot_id') ~ '^sh_[A-Za-z0-9_-]+$'
      and (
        re.kind <> 'public_cc'
        or nullif(coalesce(re.attribution, c.attribution), '') is not null
      )
  ),
  history_sources as (
    select
      s.match_id,
      s.id as shot_id,
      s.rally_id,
      s.analysis_id,
      s.player_id,
      s.phase,
      s.reference_plane,
      s.roles,
      s.incoming,
      s.chosen,
      s.context,
      s.outcome,
      s.quality,
      m.owner_id,
      m.sport,
      m.ruleset_id,
      m.format,
      null::text as clip_id,
      null::text as provenance,
      null::text as attribution
    from public.shots s
    join public.matches m on m.id = s.match_id and m.owner_id = s.owner_id
    join public.analysis_runs ar on ar.id = s.analysis_id and ar.owner_id = s.owner_id
    cross join query_shot q
    cross join query_is_player qp
    where qp.value
      and s.owner_id = (select auth.uid())
      and m.id <> q.match_id
      and exists (
        select 1
        from jsonb_array_elements(
          case when jsonb_typeof(ar.players) = 'array' then ar.players else '[]'::jsonb end
        ) as player_record(player)
        where player_record.player->>'player_id' = s.player_id
          and player_record.player->>'label' = 'you'
      )
  ),
  comparison_rows as (
    select
      'query'::text as record_kind,
      q.match_id,
      q.shot_id,
      q.rally_id,
      q.analysis_id,
      q.player_id,
      q.phase,
      q.reference_plane,
      q.roles,
      q.incoming,
      q.chosen,
      q.context,
      q.outcome,
      q.quality,
      q.owner_id,
      q.sport,
      q.ruleset_id,
      q.format,
      null::text as clip_id,
      null::text as provenance,
      null::text as attribution
    from query_shot q

    union all

    select
      'candidate'::text,
      c.match_id,
      c.shot_id,
      c.rally_id,
      c.analysis_id,
      c.player_id,
      c.phase,
      c.reference_plane,
      c.roles,
      c.incoming,
      c.chosen,
      c.context,
      c.outcome,
      c.quality,
      c.owner_id,
      c.sport,
      c.ruleset_id,
      c.format,
      c.clip_id,
      c.provenance,
      c.attribution
    from candidate_sources c
    cross join query_shot q
    where c.sport = q.sport
      and c.ruleset_id = q.ruleset_id
      and c.format = q.format
      and c.phase = q.phase
      and c.quality->>'camera_quality_grade' = q.quality->>'camera_quality_grade'
      and not (
        c.match_id = q.match_id
        and c.rally_id is not distinct from q.rally_id
      )

    union all

    select
      'history'::text,
      h.match_id,
      h.shot_id,
      h.rally_id,
      h.analysis_id,
      h.player_id,
      h.phase,
      h.reference_plane,
      h.roles,
      h.incoming,
      h.chosen,
      h.context,
      h.outcome,
      h.quality,
      h.owner_id,
      h.sport,
      h.ruleset_id,
      h.format,
      h.clip_id,
      h.provenance,
      h.attribution
    from history_sources h
    cross join query_shot q
    where h.sport = q.sport
      and h.ruleset_id = q.ruleset_id
      and h.format = q.format
      and h.phase = q.phase
      and h.quality->>'camera_quality_grade' = q.quality->>'camera_quality_grade'
  ),
  focal_positions as (
    select
      r.*,
      case
        when r.roles->'focal'->>'state' = 'observed'
          and jsonb_typeof(r.roles#>'{focal,xy_m}') = 'array'
        then case
          when jsonb_array_length(r.roles#>'{focal,xy_m}') = 2
            and jsonb_typeof(r.roles#>'{focal,xy_m,0}') = 'number'
          then (r.roles#>>'{focal,xy_m,0}')::numeric
        end
      end as focal_x,
      case
        when r.roles->'focal'->>'state' = 'observed'
          and jsonb_typeof(r.roles#>'{focal,xy_m}') = 'array'
        then case
          when jsonb_array_length(r.roles#>'{focal,xy_m}') = 2
            and jsonb_typeof(r.roles#>'{focal,xy_m,1}') = 'number'
          then (r.roles#>>'{focal,xy_m,1}')::numeric
        end
      end as focal_y,
      nullif(r.chosen->>'family', 'unknown') as action_family
    from comparison_rows r
  ),
  positioned_rows as (
    select
      f.*,
      opponent.position_x as opponent_x,
      opponent.position_y as opponent_y
    from focal_positions f
    left join lateral (
      select coordinates.position_x, coordinates.position_y
      from jsonb_each(
        case when jsonb_typeof(f.roles) = 'object' then f.roles else '{}'::jsonb end
      ) as role_entry(role_name, role_value)
      cross join lateral (
        select
          case
            when jsonb_typeof(role_entry.role_value->'xy_m') = 'array'
            then case
              when jsonb_array_length(role_entry.role_value->'xy_m') = 2
                and jsonb_typeof(role_entry.role_value#>'{xy_m,0}') = 'number'
              then (role_entry.role_value#>>'{xy_m,0}')::numeric
            end
          end as position_x,
          case
            when jsonb_typeof(role_entry.role_value->'xy_m') = 'array'
            then case
              when jsonb_array_length(role_entry.role_value->'xy_m') = 2
                and jsonb_typeof(role_entry.role_value#>'{xy_m,1}') = 'number'
              then (role_entry.role_value#>>'{xy_m,1}')::numeric
            end
          end as position_y
      ) as coordinates
      where role_entry.role_name in ('opponent', 'opponent_left', 'opponent_right')
        and role_entry.role_value->>'state' = 'observed'
        and f.focal_x is not null
        and f.focal_y is not null
        and coordinates.position_x is not null
        and coordinates.position_y is not null
      order by
        power(coordinates.position_x - f.focal_x, 2)
          + power(coordinates.position_y - f.focal_y, 2),
        role_entry.role_name
      limit 1
    ) as opponent on true
  ),
  zone_coordinates as (
    select
      p.*,
      case
        when p.incoming->>'zones_state' = 'observed'
          and p.sport in ('badminton', 'tennis')
          and p.incoming->>'source_zone' ~ '^[FMB][123]$'
        then case left(p.incoming->>'source_zone', 1)
          when 'F' then 0 when 'M' then 1 when 'B' then 2 end
        when p.incoming->>'zones_state' = 'observed'
          and p.sport = 'table_tennis'
          and p.incoming->>'source_zone' in ('S', 'L')
        then case p.incoming->>'source_zone' when 'S' then 0 when 'L' then 1 end
      end as source_zone_row,
      case
        when p.incoming->>'zones_state' = 'observed'
          and p.sport in ('badminton', 'tennis')
          and p.incoming->>'source_zone' ~ '^[FMB][123]$'
        then substring(p.incoming->>'source_zone' from 2)::integer
        when p.incoming->>'zones_state' = 'observed'
          and p.sport = 'table_tennis'
          and p.incoming->>'source_zone' in ('S', 'L')
        then 0
      end as source_zone_column,
      case
        when p.incoming->>'zones_state' = 'observed'
          and p.sport in ('badminton', 'tennis')
          and p.incoming->>'landing_zone' ~ '^[FMB][123]$'
        then case left(p.incoming->>'landing_zone', 1)
          when 'F' then 0 when 'M' then 1 when 'B' then 2 end
        when p.incoming->>'zones_state' = 'observed'
          and p.sport = 'table_tennis'
          and p.incoming->>'landing_zone' in ('S', 'L')
        then case p.incoming->>'landing_zone' when 'S' then 0 when 'L' then 1 end
      end as landing_zone_row,
      case
        when p.incoming->>'zones_state' = 'observed'
          and p.sport in ('badminton', 'tennis')
          and p.incoming->>'landing_zone' ~ '^[FMB][123]$'
        then substring(p.incoming->>'landing_zone' from 2)::integer
        when p.incoming->>'zones_state' = 'observed'
          and p.sport = 'table_tennis'
          and p.incoming->>'landing_zone' in ('S', 'L')
        then 0
      end as landing_zone_column
    from positioned_rows p
  ),
  query_metadata as (
    select
      q.*,
      case q.sport
        when 'badminton' then case q.format when 'singles' then 5.18::numeric when 'doubles' then 6.10::numeric end
        when 'tennis' then case q.format when 'singles' then 8.23::numeric when 'doubles' then 10.97::numeric end
      end as court_width,
      case q.sport
        when 'badminton' then 13.40::numeric
        when 'tennis' then 23.77::numeric
      end as court_length
    from zone_coordinates q
    where q.record_kind = 'query'
  ),
  distance_components as (
    select
      r.*,
      case
        when q.court_width is not null
          and q.court_length is not null
          and q.reference_plane = 'court'
          and r.reference_plane = 'court'
          and q.focal_x is not null and q.focal_y is not null
          and r.focal_x is not null and r.focal_y is not null
        then least(
          1::numeric,
          sqrt(power(r.focal_x - q.focal_x, 2) + power(r.focal_y - q.focal_y, 2))
            / sqrt(power(q.court_width, 2) + power(q.court_length / 2, 2))
        )
      end as focal_distance,
      case
        when q.court_width is not null
          and q.court_length is not null
          and q.reference_plane = 'court'
          and r.reference_plane = 'court'
          and (
            q.format <> 'doubles'
            or (
              q.roles ? 'opponent_left' and q.roles ? 'opponent_right'
              and r.roles ? 'opponent_left' and r.roles ? 'opponent_right'
            )
          )
          and q.opponent_x is not null and q.opponent_y is not null
          and r.opponent_x is not null and r.opponent_y is not null
        then least(
          1::numeric,
          sqrt(power(r.opponent_x - q.opponent_x, 2) + power(r.opponent_y - q.opponent_y, 2))
            / sqrt(power(q.court_width, 2) + power(q.court_length / 2, 2))
        )
      end as opponent_distance,
      case
        when q.incoming->>'zones_state' = 'observed'
          and r.incoming->>'zones_state' = 'observed'
          and q.source_zone_row is not null and r.source_zone_row is not null
          and q.landing_zone_row is not null and r.landing_zone_row is not null
          and q.sport in ('badminton', 'tennis')
        then (
          abs(r.source_zone_row - q.source_zone_row)
          + abs(r.source_zone_column - q.source_zone_column)
          + abs(r.landing_zone_row - q.landing_zone_row)
          + abs(r.landing_zone_column - q.landing_zone_column)
        )::numeric / 8
        when q.incoming->>'zones_state' = 'observed'
          and r.incoming->>'zones_state' = 'observed'
          and q.source_zone_row is not null and r.source_zone_row is not null
          and q.landing_zone_row is not null and r.landing_zone_row is not null
          and q.sport = 'table_tennis'
        then (
          (case when r.source_zone_row = q.source_zone_row then 0 else 1 end)
          + (case when r.landing_zone_row = q.landing_zone_row then 0 else 1 end)
        )::numeric / 2
      end as zones_distance,
      case
        when q.action_family is not null and r.action_family is not null
        then case when q.action_family = r.action_family then 0::numeric else 1::numeric end
      end as family_distance,
      case
        when q.context->>'state' = 'observed'
          and r.context->>'state' = 'observed'
          and nullif(q.context->>'pressure_bucket', '') is not null
          and nullif(r.context->>'pressure_bucket', '') is not null
        then case
          when q.context->>'pressure_bucket' = r.context->>'pressure_bucket' then 0::numeric
          else 1::numeric
        end
      end as pressure_distance
    from zone_coordinates r
    cross join query_metadata q
    where r.record_kind in ('candidate', 'history')
  ),
  feature_weights as (
    select
      d.*,
      case when d.focal_distance is not null then 0.35::numeric else 0::numeric end as focal_weight,
      case when d.opponent_distance is not null then 0.30::numeric else 0::numeric end as opponent_weight,
      case when d.zones_distance is not null then 0.20::numeric else 0::numeric end as zones_weight,
      case when d.family_distance is not null then 0.10::numeric else 0::numeric end as family_weight,
      case when d.pressure_distance is not null then 0.05::numeric else 0::numeric end as pressure_weight
    from distance_components d
  ),
  scored as (
    select
      w.*,
      w.focal_weight + w.opponent_weight + w.zones_weight + w.family_weight + w.pressure_weight
        as observed_weight,
      (
        w.focal_weight * coalesce(w.focal_distance, 0)
        + w.opponent_weight * coalesce(w.opponent_distance, 0)
        + w.zones_weight * coalesce(w.zones_distance, 0)
        + w.family_weight * coalesce(w.family_distance, 0)
        + w.pressure_weight * coalesce(w.pressure_distance, 0)
      ) / nullif(
        w.focal_weight + w.opponent_weight + w.zones_weight + w.family_weight + w.pressure_weight,
        0
      ) as distance,
      array_remove(array[
        case when w.focal_weight > 0 then 'focal_position'::text end,
        case when w.opponent_weight > 0 then 'opponent_geometry'::text end,
        case when w.zones_weight > 0 then 'zones'::text end,
        case when w.family_weight > 0 then 'shot_family'::text end,
        case when w.pressure_weight > 0 then 'pressure_bucket'::text end
      ], null) as matched_features
    from feature_weights w
  ),
  accepted as (
    select *
    from scored
    where observed_weight >= 0.70::numeric
      and distance <= 0.30::numeric
  ),
  history_decisions as (
    select
      a.*,
      a.chosen->>'target_zone' as action_zone,
      a.action_family as action_family_key,
      row_number() over (
        partition by a.match_id, a.rally_id
        order by a.distance, a.shot_id
      ) as rally_decision
    from accepted a
    where a.record_kind = 'history'
      and a.outcome->>'state' = 'user'
      and a.chosen->>'landing_state' = 'observed'
      and nullif(a.chosen->>'target_zone', '') is not null
  ),
  history_deduplicated as (
    select * from history_decisions where rally_decision = 1
  ),
  history_totals as (
    select count(*)::integer as decisions, count(distinct match_id)::integer as matches
    from history_deduplicated
  ),
  evidence_by_action as (
    select
      action_zone,
      action_family_key,
      count(*)::integer as decision_count,
      (count(*) filter (where outcome->>'point_winner' = player_id))::integer as won,
      count(distinct match_id)::integer as matches,
      count(distinct rally_id)::integer as rallies
    from history_deduplicated
    group by action_zone, action_family_key
  ),
  accepted_candidates as (
    select
      a.shot_id,
      a.clip_id,
      a.distance,
      a.matched_features,
      case
        when coalesce(e.decision_count, 0) >= 5
          and coalesce(e.matches, 0) >= 3
          and totals.decisions >= 20
          and totals.matches >= 3
        then 'worked_for_you'
        else 'similar_moment'
      end as role,
      jsonb_build_object(
        'won', coalesce(e.won, 0),
        'of', coalesce(e.decision_count, 0),
        'matches', coalesce(e.matches, 0),
        'rallies', coalesce(e.rallies, 0)
      ) as evidence,
      a.provenance,
      a.attribution,
      case a.provenance
        when 'own' then 0
        when 'shared' then 1
        when 'team_reference' then 2
        when 'public_cc' then 3
        else 4
      end as provenance_order
    from accepted a
    cross join history_totals totals
    left join evidence_by_action e
      on e.action_zone = a.chosen->>'target_zone'
      and e.action_family_key is not distinct from a.action_family
    where a.record_kind = 'candidate'
  )
  select
    c.shot_id,
    c.clip_id,
    c.distance,
    c.matched_features,
    c.role,
    c.evidence,
    c.provenance,
    c.attribution
  from accepted_candidates c
  order by c.distance, c.provenance_order, c.shot_id, c.clip_id
  limit greatest(0, least(coalesce(p_limit, 3), 5))
$$;

revoke all on function public.compare_shot(text, text, text, integer) from public, anon;
grant execute on function public.compare_shot(text, text, text, integer) to authenticated;
