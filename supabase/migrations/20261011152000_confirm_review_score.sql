create or replace function public.confirm_review(
  p_match_id text,
  p_review_id text,
  p_winner text,
  p_actor_id uuid,
  p_by_player_id text,
  p_idempotency_key text,
  p_score jsonb,
  p_confirmed_reviews jsonb
)
returns jsonb language plpgsql set search_path = '' as $$
declare
  v_match public.matches;
  v_review public.reviews;
  v_point public.confirmed_points;
  v_actual_confirmed_reviews jsonb;
begin
  select * into v_match
    from public.matches
    where id = p_match_id
    for update;
  if not found then
    raise exception 'match_not_found' using errcode = 'P0002';
  end if;
  if v_match.owner_id is distinct from p_actor_id then
    raise exception 'match_not_owned' using errcode = '42501';
  end if;

  select * into v_review
    from public.reviews
    where match_id = p_match_id and id = p_review_id
    for update;
  if not found or v_review.rally_id is null or v_review.owner_id is distinct from v_match.owner_id then
    raise exception 'review_not_found' using errcode = 'P0002';
  end if;

  select * into v_point
    from public.confirmed_points
    where match_id = p_match_id and idempotency_key = p_idempotency_key
    for update;
  if found then
    if v_point.review_id is distinct from p_review_id
       or v_point.winner is distinct from p_winner
       or v_point.confirmed_by is distinct from p_actor_id
       or v_review.confirmed_point_winner is distinct from p_winner
       or v_review.confirmed_by is distinct from p_by_player_id then
      raise exception 'idempotency_conflict' using errcode = 'P0001';
    end if;
    return jsonb_build_object(
      'point', jsonb_build_object(
        'confirmed_point_id', v_point.id,
        'rally_id', v_point.rally_id,
        'winner', v_point.winner,
        'by', p_by_player_id,
        'at', v_point.created_at,
        'review_id', v_point.review_id
      ),
      'created', false
    );
  end if;

  if v_review.confirmed_point_winner is not null then
    raise exception 'review_already_confirmed' using errcode = 'P0001';
  end if;
  if jsonb_typeof(p_score) is distinct from 'object'
     or jsonb_typeof(p_score->'timeline') is distinct from 'array'
     or jsonb_typeof(p_score->'games') is distinct from 'array'
     or jsonb_typeof(p_confirmed_reviews) is distinct from 'array' then
    raise exception 'invalid_confirmation_score' using errcode = '22023';
  end if;

  select coalesce(
    jsonb_agg(
      jsonb_build_object(
        'review_id', r.id,
        'rally_id', r.rally_id,
        'winner', case when r.id = p_review_id then p_winner else r.confirmed_point_winner end
      ) order by y.start_ms, y.id, r.id
    ),
    '[]'::jsonb
  ) into v_actual_confirmed_reviews
  from public.reviews r
  join public.rallies y on y.match_id = r.match_id and y.id = r.rally_id
  where r.match_id = p_match_id
    and (r.confirmed_point_winner is not null or r.id = p_review_id);

  if v_actual_confirmed_reviews is distinct from p_confirmed_reviews then
    raise exception 'score_stale' using errcode = 'P0001';
  end if;

  insert into public.confirmed_points (
    match_id, owner_id, rally_id, winner, confirmed_by, review_id, idempotency_key
  ) values (
    p_match_id, v_match.owner_id, v_review.rally_id, p_winner, p_actor_id, p_review_id,
    p_idempotency_key
  ) returning * into v_point;

  update public.reviews
     set confirmed_point_winner = p_winner,
         confirmed_by = p_by_player_id,
         confirmed_at = v_point.created_at
   where match_id = p_match_id and id = p_review_id;

  update public.matches set score = p_score where id = p_match_id;

  return jsonb_build_object(
    'point', jsonb_build_object(
      'confirmed_point_id', v_point.id,
      'rally_id', v_point.rally_id,
      'winner', v_point.winner,
      'by', p_by_player_id,
      'at', v_point.created_at,
      'review_id', v_point.review_id
    ),
    'created', true
  );
end $$;

revoke all on function public.confirm_review(text,text,text,uuid,text,text,jsonb,jsonb)
  from public, anon, authenticated;
grant execute on function public.confirm_review(text,text,text,uuid,text,text,jsonb,jsonb)
  to service_role;
