-- Honest post summaries (#165 follow-up, D-261011-HW34, D-007).
-- Real engine hit and landing events are state 'candidate', so the first version counted 0 hits and
-- no landing points on every real match. Count candidates too (they are real detections, labelled as
-- such in the app), accept landings without a 'plane' field when they carry court metres, and return
-- null landings in/out when the match has no reviews instead of a misleading 0. Existing posts keep
-- their frozen snapshot; only new posts use this.
create or replace function private.match_post_summary(p_match_id text, p_owner_id uuid)
returns jsonb language plpgsql stable security definer set search_path = '' as $$
declare
  v_match jsonb := public.get_match_v1(p_match_id);
  v_duration_ms integer;
  v_owner_display_name text;
begin
  select case when count(*) = 1 then max(
           case when s.source->>'duration_ms' ~ '^[0-9]{1,9}$' then (s.source->>'duration_ms')::integer end
         ) end
    into v_duration_ms
    from public.sessions s
    where s.match_id = p_match_id;

  select n.display_name into v_owner_display_name
    from (
      select c.creator_name as display_name, c.created_at as changed_at
        from public.challenges c
       where c.creator_id = p_owner_id
      union all
      select e.display_name, e.created_at
        from public.challenge_entries e
       where e.player_id = p_owner_id
    ) n
   order by n.changed_at desc, n.display_name
   limit 1;

  return jsonb_build_object(
    'title', null::text,
    'duration_ms', v_duration_ms,
    'rally_count', case when v_match is null then null else jsonb_array_length(coalesce(v_match->'rallies', '[]'::jsonb)) end,
    'hit_count', case when v_match is null then null else (
      select count(*)::integer
        from jsonb_array_elements(coalesce(v_match->'events', '[]'::jsonb)) e(value)
       where e.value->>'type' = 'hit' and e.value->>'state' in ('observed', 'user', 'candidate')
    ) end,
    'landings_in', case when v_match is null or jsonb_array_length(coalesce(v_match->'reviews', '[]'::jsonb)) = 0 then null else (
      select count(*)::integer
        from jsonb_array_elements(coalesce(v_match->'reviews', '[]'::jsonb)) r(value)
       where r.value->>'estimate' = 'IN'
    ) end,
    'landings_out', case when v_match is null or jsonb_array_length(coalesce(v_match->'reviews', '[]'::jsonb)) = 0 then null else (
      select count(*)::integer
        from jsonb_array_elements(coalesce(v_match->'reviews', '[]'::jsonb)) r(value)
       where r.value->>'estimate' = 'OUT'
    ) end,
    'longest_rally_ms', (
      select max((r.value->>'end_ms')::integer - (r.value->>'start_ms')::integer)
        from jsonb_array_elements(coalesce(v_match->'rallies', '[]'::jsonb)) r(value)
    ),
    'top_coach_points', coalesce((
      select jsonb_agg(points.caption order by points.ordinality)
        from (
          select insight.value->>'caption' as caption, insight.ordinality
            from jsonb_array_elements(coalesce(v_match->'insights', '[]'::jsonb))
              with ordinality as insight(value, ordinality)
           where jsonb_typeof(insight.value->'caption') = 'string'
           order by insight.ordinality
           limit 3
        ) points
    ), '[]'::jsonb),
    'landing_points_m', coalesce((
      select jsonb_agg(jsonb_build_object(
        'x_m', (event.value->'contact_xy_m'->>0)::numeric,
        'y_m', (event.value->'contact_xy_m'->>1)::numeric
      ) order by event.value->>'t_ms', event.value->>'event_id')
        from jsonb_array_elements(coalesce(v_match->'events', '[]'::jsonb)) event(value)
       where event.value->>'type' = 'landing'
         and coalesce(event.value->>'plane', 'court') = 'court'
         and event.value->>'state' in ('observed', 'user', 'candidate')
         and jsonb_typeof(event.value->'contact_xy_m') = 'array'
         and jsonb_typeof(event.value->'contact_xy_m'->0) = 'number'
         and jsonb_typeof(event.value->'contact_xy_m'->1) = 'number'
    ), '[]'::jsonb),
    'owner_display_name', v_owner_display_name
  );
end $$;
revoke all on function private.match_post_summary(text, uuid) from public, anon, authenticated;
