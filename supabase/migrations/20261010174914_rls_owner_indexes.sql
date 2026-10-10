-- Index owner_id columns RLS policies filter on (lane rule: index policy columns).
-- sessions.owner_id and analysis_runs.owner_id are already indexed in phase1_core.
create index if not exists matches_owner_id_idx on public.matches(owner_id);
create index if not exists uploads_owner_id_idx on public.uploads(owner_id);
create index if not exists analysis_events_owner_id_idx on public.analysis_events(owner_id);
create index if not exists artifacts_owner_id_idx on public.artifacts(owner_id);
create index if not exists idempotency_owner_id_idx on public.idempotency(owner_id);
