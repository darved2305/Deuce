-- G6 follow-up (D-261011-NP2, issue #159): a download URL re-issued for the same artifact to
-- the same owner while the previous URL is still valid does not spend the daily cap again.
-- The daily cap itself moves from 200 to 1,000 in the API's BUDGET constant (p_limit is a
-- function argument, so no SQL change is needed for it).

-- The last issue time per (owner, artifact): the dedupe window lives here.
create table public.r2_download_issued (
  owner_id uuid not null references auth.users(id) on delete cascade,
  artifact_id text not null,
  issued_at timestamptz not null,
  primary key (owner_id, artifact_id)
);
alter table public.r2_download_issued enable row level security;      -- service only

revoke all on public.r2_download_issued from anon, authenticated;
grant all on public.r2_download_issued to service_role;

-- G6: atomically takes one download URL for the owner's UTC day if under p_limit; counts it
-- as Class B. A still-valid earlier URL for the same (owner, artifact) makes the take free:
-- the day total is returned unchanged and the validity window slides to now. Returns the
-- day's new total, or null when the daily limit is reached.
drop function public.r2_take_download(uuid, integer);
create function public.r2_take_download(p_owner uuid, p_limit integer, p_artifact text, p_ttl integer)
returns integer language plpgsql set search_path = '' security definer as $$
declare v_issued timestamptz;
        v_total integer;
begin
  select i.issued_at into v_issued
    from public.r2_download_issued i
   where i.owner_id = p_owner and i.artifact_id = p_artifact
   for update;
  if v_issued is not null and v_issued > now() - make_interval(secs => p_ttl) then
    update public.r2_download_issued set issued_at = now()
     where owner_id = p_owner and artifact_id = p_artifact;
    perform public.r2_add_usage(0, 1);
    select d.downloads into v_total from public.r2_usage_daily d
     where d.owner_id = p_owner and d.day = (now() at time zone 'utc')::date;
    return coalesce(v_total, 0);
  end if;
  insert into public.r2_usage_daily as d (owner_id, day, downloads)
  values (p_owner, (now() at time zone 'utc')::date, 0)
  on conflict (owner_id, day) do nothing;
  update public.r2_usage_daily set downloads = downloads + 1
   where owner_id = p_owner and day = (now() at time zone 'utc')::date and downloads < p_limit
   returning downloads into v_total;
  if v_total is null then return null; end if;
  insert into public.r2_download_issued as i (owner_id, artifact_id, issued_at)
  values (p_owner, p_artifact, now())
  on conflict (owner_id, artifact_id) do update set issued_at = excluded.issued_at;
  perform public.r2_add_usage(0, 1);
  return v_total;
end $$;

revoke all on function public.r2_take_download(uuid,integer,text,integer) from public, anon, authenticated;
grant execute on function public.r2_take_download(uuid,integer,text,integer) to service_role;
