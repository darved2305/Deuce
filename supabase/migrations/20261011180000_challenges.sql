-- Friends and challenges (D-261011-HW31). A challenge is one Reflex run on a match: the exact freeze
-- set, the creator's score and per-round results. Friends accept it (which grants read access to the
-- match through share_grants), play the same rounds and post an entry. Writes go through service_role
-- functions called by the API; the app only reads, under RLS, as the creator or a participant.

create table public.challenges (
  id text primary key default 'ch_' || replace(gen_random_uuid()::text,'-',''),
  match_id text not null references public.matches(id) on delete cascade,
  creator_id uuid not null references auth.users(id) on delete cascade,
  creator_name text not null,
  creator_score integer not null,
  creator_results jsonb not null,
  rounds jsonb not null,
  video_sha256 text not null,
  rematch_of text references public.challenges(id) on delete set null,
  status text not null default 'open',
  created_at timestamptz not null default now(),
  constraint challenges_status_check check (status in ('open','played')),
  constraint challenges_score_check check (creator_score between 0 and 100000),
  constraint challenges_name_check check (char_length(creator_name) between 1 and 24),
  constraint challenges_sha_check check (video_sha256 ~ '^[A-Fa-f0-9]{64}$'),
  constraint challenges_rounds_check check (jsonb_typeof(rounds) = 'array' and jsonb_array_length(rounds) between 3 and 50)
);

create table public.challenge_entries (
  challenge_id text not null references public.challenges(id) on delete cascade,
  player_id uuid not null references auth.users(id) on delete cascade,
  display_name text not null,
  score integer,
  round_results jsonb,
  created_at timestamptz not null default now(),
  played_at timestamptz,
  primary key (challenge_id, player_id),
  constraint challenge_entries_name_check check (char_length(display_name) between 1 and 24),
  constraint challenge_entries_score_check check (score is null or score between 0 and 100000),
  constraint challenge_entries_played_check check ((score is null) = (round_results is null) and (score is null) = (played_at is null))
);

create index challenges_match_id_idx on public.challenges(match_id);
create index challenges_creator_id_idx on public.challenges(creator_id);
create index challenges_rematch_of_idx on public.challenges(rematch_of);
create index challenge_entries_player_id_idx on public.challenge_entries(player_id);

-- Policy helpers are security definer so the two tables' policies cannot recurse into each other.
create or replace function private.is_challenge_creator(p_challenge_id text)
returns boolean language sql stable security definer set search_path = ''
as $$ select exists (select 1 from public.challenges c
      where c.id = p_challenge_id and c.creator_id = (select auth.uid())) $$;
create or replace function private.is_challenge_participant(p_challenge_id text)
returns boolean language sql stable security definer set search_path = ''
as $$ select exists (select 1 from public.challenge_entries e
      where e.challenge_id = p_challenge_id and e.player_id = (select auth.uid())) $$;
revoke all on function private.is_challenge_creator(text), private.is_challenge_participant(text)
  from public, anon;
grant execute on function private.is_challenge_creator(text), private.is_challenge_participant(text)
  to authenticated;

alter table public.challenges enable row level security;
create policy challenges_select on public.challenges for select to authenticated
  using (creator_id = (select auth.uid()) or (select private.is_challenge_participant(id)));

alter table public.challenge_entries enable row level security;
create policy challenge_entries_select on public.challenge_entries for select to authenticated
  using (player_id = (select auth.uid()) or (select private.is_challenge_creator(challenge_id)));

revoke all on table public.challenges, public.challenge_entries from public, anon, authenticated;
grant select on public.challenges, public.challenge_entries to authenticated;
grant all on public.challenges, public.challenge_entries to service_role;

-- Create a challenge as p_player. Errors: match_not_found (P0002), no_source_hash, unknown_event,
-- rematch_forbidden, rounds_mismatch (P0001).
create or replace function public.create_challenge(
  p_player uuid, p_match_id text, p_display_name text, p_rounds jsonb, p_score integer,
  p_results jsonb, p_rematch_of text
) returns text language plpgsql security definer set search_path = '' as $$
declare
  v_owner uuid;
  v_parent public.challenges;
  v_sha text;
  v_id text;
  v_event text;
begin
  select owner_id into v_owner from public.matches where id = p_match_id;
  if not found then raise exception 'match_not_found' using errcode = 'P0002'; end if;
  if p_rematch_of is null then
    if v_owner <> p_player then raise exception 'match_not_found' using errcode = 'P0002'; end if;
  else
    select * into v_parent from public.challenges where id = p_rematch_of;
    if not found or v_parent.match_id <> p_match_id
       or not (v_parent.creator_id = p_player or exists (
         select 1 from public.challenge_entries e
          where e.challenge_id = p_rematch_of and e.player_id = p_player)) then
      raise exception 'rematch_forbidden' using errcode = 'P0001';
    end if;
    if v_parent.rounds <> p_rounds then
      raise exception 'rounds_mismatch' using errcode = 'P0001';
    end if;
  end if;

  select u.sha256 into v_sha from public.sessions s join public.uploads u on u.session_id = s.id
   where s.match_id = p_match_id order by s.created_at desc limit 1;
  if v_sha is null then
    select s.source->>'sha256' into v_sha from public.sessions s
     where s.match_id = p_match_id and s.source ? 'sha256' order by s.created_at desc limit 1;
  end if;
  if v_sha is null then raise exception 'no_source_hash' using errcode = 'P0001'; end if;

  for v_event in
    select r->>k from jsonb_array_elements(p_rounds) r, unnest(array['hit_event_id','landing_event_id']) k
  loop
    if not exists (select 1 from public.vision_events ve where ve.match_id = p_match_id and ve.id = v_event) then
      raise exception 'unknown_event' using errcode = 'P0001', detail = v_event;
    end if;
  end loop;
  if exists (select 1 from jsonb_array_elements(p_results) x
              where not (p_rounds @> jsonb_build_array(jsonb_build_object('hit_event_id', x->>'hit_event_id')))) then
    raise exception 'unknown_event' using errcode = 'P0001', detail = 'result for a round that is not in the freeze set';
  end if;

  insert into public.challenges (match_id, creator_id, creator_name, creator_score, creator_results,
    rounds, video_sha256, rematch_of)
  values (p_match_id, p_player, coalesce(p_display_name, 'Player'), p_score, p_results, p_rounds, v_sha, p_rematch_of)
  returning id into v_id;
  return v_id;
end $$;

-- Accept as p_player: records the entry and grants read access to the match. Idempotent.
-- Errors: challenge_not_found (P0002; also for a rematch the caller was not part of), own_challenge (P0001).
create or replace function public.accept_challenge(p_player uuid, p_challenge_id text, p_display_name text)
returns void language plpgsql security definer set search_path = '' as $$
declare
  v_c public.challenges;
  v_owner uuid;
begin
  select * into v_c from public.challenges where id = p_challenge_id;
  if not found then raise exception 'challenge_not_found' using errcode = 'P0002'; end if;
  if v_c.creator_id = p_player then raise exception 'own_challenge' using errcode = 'P0001'; end if;
  if v_c.rematch_of is not null and not exists (
       select 1 from public.challenges p where p.id = v_c.rematch_of
          and (p.creator_id = p_player or exists (
            select 1 from public.challenge_entries e where e.challenge_id = p.id and e.player_id = p_player))) then
    raise exception 'challenge_not_found' using errcode = 'P0002';
  end if;

  insert into public.challenge_entries (challenge_id, player_id, display_name)
  values (p_challenge_id, p_player, coalesce(p_display_name, 'Player'))
  on conflict (challenge_id, player_id) do update
    set display_name = coalesce(p_display_name, public.challenge_entries.display_name)
    where public.challenge_entries.score is null;

  select owner_id into v_owner from public.matches where id = v_c.match_id;
  if v_owner <> p_player then
    insert into public.share_grants (match_id, owner_id, grantee_id, scope)
    values (v_c.match_id, v_owner, p_player, 'read')
    on conflict (match_id, grantee_id, scope) do update set revoked_at = null;
  end if;
end $$;

-- Post p_player's run. Errors: not_participant (P0002), already_played (P0001) unless the same run is
-- posted again, unknown_event (P0001).
create or replace function public.submit_challenge_entry(
  p_player uuid, p_challenge_id text, p_display_name text, p_score integer, p_results jsonb
) returns void language plpgsql security definer set search_path = '' as $$
declare
  v_c public.challenges;
  v_e public.challenge_entries;
begin
  select * into v_c from public.challenges where id = p_challenge_id;
  if not found then raise exception 'not_participant' using errcode = 'P0002'; end if;
  select * into v_e from public.challenge_entries
   where challenge_id = p_challenge_id and player_id = p_player for update;
  if not found then raise exception 'not_participant' using errcode = 'P0002'; end if;
  if exists (select 1 from jsonb_array_elements(p_results) x
              where not (v_c.rounds @> jsonb_build_array(jsonb_build_object('hit_event_id', x->>'hit_event_id')))) then
    raise exception 'unknown_event' using errcode = 'P0001';
  end if;
  if v_e.score is not null then
    if v_e.score = p_score and v_e.round_results = p_results then return; end if;
    raise exception 'already_played' using errcode = 'P0001';
  end if;
  update public.challenge_entries
     set score = p_score, round_results = p_results, played_at = now(),
         display_name = coalesce(p_display_name, display_name)
   where challenge_id = p_challenge_id and player_id = p_player;
  update public.challenges set status = 'played' where id = p_challenge_id;
end $$;

revoke all on function public.create_challenge(uuid,text,text,jsonb,integer,jsonb,text),
  public.accept_challenge(uuid,text,text),
  public.submit_challenge_entry(uuid,text,text,integer,jsonb) from public, anon, authenticated;
grant execute on function public.create_challenge(uuid,text,text,jsonb,integer,jsonb,text),
  public.accept_challenge(uuid,text,text),
  public.submit_challenge_entry(uuid,text,text,integer,jsonb) to service_role;

-- challenge.v1 as the caller may see it (RLS decides: the creator sees every entry, an opponent only
-- their own). Null when the caller is not in the challenge.
create or replace function public.get_challenge_v1(p_id text)
returns jsonb language sql stable security invoker set search_path = '' as $$
  select jsonb_build_object(
    'id', c.id, 'match_id', c.match_id, 'status', c.status, 'video_sha256', c.video_sha256,
    'rematch_of', c.rematch_of, 'rounds', c.rounds,
    'creator', jsonb_build_object('player_id', c.creator_id, 'display_name', c.creator_name,
      'score', c.creator_score, 'round_results', c.creator_results),
    'entries', coalesce((
      select jsonb_agg(jsonb_build_object(
        'player_id', e.player_id, 'display_name', e.display_name, 'score', e.score,
        'round_results', e.round_results,
        'outcome', case when e.score is null then null
                        when c.creator_score > e.score then 'creator'
                        when c.creator_score < e.score then 'opponent' else 'tie' end,
        'accepted_at', e.created_at, 'played_at', e.played_at) order by e.created_at, e.player_id)
      from public.challenge_entries e where e.challenge_id = c.id), '[]'::jsonb),
    'viewer', jsonb_build_object('player_id', (select auth.uid()),
      'role', case when c.creator_id = (select auth.uid()) then 'creator' else 'opponent' end),
    'created_at', c.created_at)
  from public.challenges c where c.id = p_id $$;

-- friends.response.v1 items for the caller: every person on the other side of a challenge, with the
-- head-to-head of the challenges both have played.
create or replace function public.list_friends_v1()
returns jsonb language sql stable security invoker set search_path = '' as $$
  with pairs as (
    select e.player_id as other_id, e.display_name as other_name, c.creator_score as mine, e.score as theirs,
           e.created_at as at
      from public.challenges c join public.challenge_entries e on e.challenge_id = c.id
     where c.creator_id = (select auth.uid())
    union all
    select c.creator_id, c.creator_name, e.score, c.creator_score, c.created_at
      from public.challenges c join public.challenge_entries e on e.challenge_id = c.id
     where e.player_id = (select auth.uid())
  )
  select coalesce(jsonb_agg(f order by f->>'last_challenge_at' desc, f->>'player_id'), '[]'::jsonb)
  from (
    select jsonb_build_object(
      'player_id', other_id,
      'display_name', (array_agg(other_name order by at desc))[1],
      'challenges', count(*),
      'played', count(*) filter (where mine is not null and theirs is not null),
      'wins', count(*) filter (where mine > theirs),
      'losses', count(*) filter (where mine < theirs),
      'ties', count(*) filter (where mine = theirs),
      'last_challenge_at', max(at)) as f
    from pairs group by other_id
  ) q $$;
