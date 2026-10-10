alter table public.reviews
  add constraint reviews_confirmation_fields_all_or_none_check
  check (
    (confirmed_point_winner is null and confirmed_by is null and confirmed_at is null)
    or
    (confirmed_point_winner is not null and confirmed_by is not null and confirmed_at is not null)
  );

alter table public.metrics
  drop constraint metrics_pkey;

alter table public.metrics
  alter column player_id drop not null;

alter table public.metrics
  add constraint metrics_match_key_definition_player_unique
  unique nulls not distinct (match_id, key, definition_version, player_id);
