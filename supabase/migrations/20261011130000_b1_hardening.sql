-- B1 hardening: score honesty trigger, provenance checks, share-grant owner check.

-- matches.score changes only via confirms (service role). Defence in depth behind the
-- API: a non-service role insert drops the value and an update keeps the old value.
-- auth.role() fails closed when no JWT claim is set.
create or replace function private.guard_match_score()
returns trigger language plpgsql security definer set search_path = '' as $$
begin
  if auth.role() is distinct from 'service_role' then
    if tg_op = 'INSERT' then
      new.score := null;
    else
      new.score := old.score;
    end if;
  end if;
  return new;
end $$;
revoke all on function private.guard_match_score() from public, anon, authenticated;

create trigger matches_score_guard
  before insert or update on public.matches
  for each row execute function private.guard_match_score();

-- Provenance checks: same enum as rallies.winner_state and vision_events.state
-- (provenanceStateSchema in packages/contracts/src/common.ts).
alter table public.rallies
  add constraint rallies_state_check
  check (state in ('observed','candidate','inferred','user'));
alter table public.shots
  add constraint shots_flight_time_state_check
  check (flight_time_state in ('observed','candidate','inferred','user'));

-- Share-grant defence in depth: a grant counts only when its owner_id owns the match.
create or replace function private.has_share_grant(p_match_id text)
returns boolean language sql stable security definer set search_path = ''
as $$ select exists (select 1 from public.share_grants g
      where g.match_id = p_match_id and g.grantee_id = (select auth.uid())
        and g.revoked_at is null and g.scope in ('read','compare')
        and g.owner_id = (select m.owner_id from public.matches m where m.id = p_match_id)) $$;
revoke all on function private.has_share_grant(text) from public, anon;
grant execute on function private.has_share_grant(text) to authenticated;
