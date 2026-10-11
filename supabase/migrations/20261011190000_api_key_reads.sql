create or replace function public.get_match_v1_for_owner(
  p_match_id text,
  p_owner uuid
)
returns jsonb
language plpgsql stable security definer set search_path = ''
as $$
begin
  if not exists (
    select 1 from public.matches
    where id = p_match_id and owner_id = p_owner
  ) then
    return null;
  end if;
  return public.get_match_v1(p_match_id);
end $$;

revoke all on function public.get_match_v1_for_owner(text, uuid)
  from public, anon, authenticated;
grant execute on function public.get_match_v1_for_owner(text, uuid)
  to service_role;

create or replace function public.compare_shot_for_owner(
  p_owner uuid,
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
language plpgsql security definer set search_path = ''
as $$
declare
  v_previous_sub text;
begin
  if not exists (
    select 1 from public.matches
    where id = p_match_id and owner_id = p_owner
  ) then
    return;
  end if;
  if not exists (
    select 1 from public.shots
    where match_id = p_match_id and id = p_shot_id and owner_id = p_owner
  ) then
    return;
  end if;
  if p_scope is null or p_scope not in ('own', 'reference') then
    return;
  end if;

  v_previous_sub := pg_catalog.current_setting('request.jwt.claim.sub', true);
  perform pg_catalog.set_config('request.jwt.claim.sub', p_owner::text, true);
  return query
    select * from public.compare_shot(p_match_id, p_shot_id, p_scope, p_limit);
  perform pg_catalog.set_config(
    'request.jwt.claim.sub',
    coalesce(v_previous_sub, ''),
    true
  );
end $$;

revoke all on function public.compare_shot_for_owner(uuid, text, text, text, integer)
  from public, anon, authenticated;
grant execute on function public.compare_shot_for_owner(uuid, text, text, text, integer)
  to service_role;
