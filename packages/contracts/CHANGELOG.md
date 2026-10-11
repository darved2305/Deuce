# Changelog

## 1.10.0

Issue #65 B6 reads (D-261011-HW16): `matches.page.v1`, `rallies.page.v1`, `rally.detail.v1`,
`moments.page.v1`, `capabilities.v1`; one `{items, next_cursor}` envelope reusing match.v1 pieces.

## 1.9.0

Issue #77 (D-261011-HW15): `modal.submit.request.v1` adds optional `match_id` and `session_id` so the
engine can build `vision_result`; `match.v1.analysis.artifacts.ball_track` / `player_tracks` are
nullable (null until a whole-match track artifact exists).

## 1.8.0

Issues #65, #66, #63 (D-261011-HW14): `match.v1.calibration` nullable; calibration `H` is image px ->
court m, `H_cov` 81 numbers or null, `tier` A|B|C; session create response is multipart or segments;
window player rows' `foot_x_m`/`foot_y_m` nullable (withheld); match events: `rally_id` optional plus
`player_id`/`track_id` (hit candidates are events with `type: "hit"`); optional `windows_expected` on
events.

## 1.7.1

`knownErrorCodes` adds the codes the API emits (Person 3, #29 and #28): `upload_parts_invalid`,
`upload_not_complete`, `invalid_transition` (409), `analysis_limit_reached` (429), `storage_busy` (503).

## 1.7.0

Person 3's Phase 3 blockers (#20 follow-up, D-261011-HW12): review `confirmed_point_winner`, `confirmed_by`,
`confirmed_at` are nullable together (an engine estimate is unconfirmed); metric `player_id` is nullable
(match-level metrics).

## 1.6.0

- `rules.state.v1` optionally records `doubles_right_player`, the player occupying each side's
  right service court, so the rules engine can preserve doubles partner positions across service
  changes (D-261011-HW7).

## 1.5.0

Chunked real-time upload contract (D-261011-HW3, D-261011-HW8): `segment.manifest.v1` gains `final`
and a typed `seg/{session_id}/{seq}.mp4` key; new `segment.register.request.v1`,
`segment.register.response.v1`, `segment.complete.response.v1`, `internal.segments.page.v1`;
`modal.submit.request.v1` accepts segment 0's key; error code `segment_gap` (409); key builder
`buildSegmentKey`.

## 1.4.0

Contracts amendment for issue #20 (D-261011-HW6). Corrections made before any deployed reader
consumes these shapes, so they stay `.v1`:
- `internal.event.request.v1`: optional `engine_version`; `window_seq` required on
  `window.completed` and `window.failed`.
- `analysis.event.v1` and `analysis.events.page.v1`: every §3 event type (the 1.0.0 to 1.3.0 schemas
  wrongly allowed only the engine's four); `engine_version` optional and nullable.
- `upload.parts.response.v1`: `{parts: [{n, url, expires_at}]}` instead of a bare URL list.
- New: `analysis.get.response.v1`, `session.get.response.v1`, `upload.complete.response.v1`,
  `upload.abort.response.v1`, `health.response.v1`, and `knownErrorCodes` (code to HTTP status).

## 1.3.0

- `elevate.trail_labels.v1` is the shape of `fixture_trail.json` (contracts §11, D-261011-HW4): Person 2's
  hand-label format, with the same rules as the app's parser. `fixtures/fixture_trail.json` holds
  Person 2's real labels for `h13g_singles` 40.00-43.98 s, and a test keeps the app's copy identical.

## 1.2.0

- `upload_mode` accepts `segments` as well as `multipart` (chunked real-time analysis, D-261011-HW3).
  The database already accepts both. Segment routes and the `final` flag land in the contracts
  amendment.

## 1.1.0

- Match the database (Person 3's `supabase/migrations` checks) exactly, and test it: sessions,
  matches and rules use the 4 built sports; format adds `none` (free play only, and free play is
  always `none`); `mode` is `match | practice | free_play`; artifact `kind` is
  `window_result | ball_track | player_tracks | match_vision | clip | poster`. Sport packs keep
  all 7 sports.

## 1.0.0

- Initial package version for contracts v1, as recorded by D-012.
- Tests keep the spec and the schemas in step: every fenced JSON example in `contracts.md` must
  validate, and every enum written in its prose (provenance, tiers, statuses, analysis states,
  event types, model IDs, review estimates, ID prefixes) must equal the schema enum.

### Open questions for the contracts amendment (D-012 sign-off)

- §0 says every derived value carries a sibling `state`, but the §7 `metrics` example has none.
  The schema follows the example (no `state` on metrics) until the amendment decides.
- §1 does not say where `retry_after_s` sits on a 429; it is an optional top-level field.
- §2 does not define the upload-parts response envelope; it is an array of URLs.
- §3 does not define the Modal submit response; Person 1 returns
  `{analysis_id, modal_call_id}` (PR #5). Add it as `modal.submit.response.v1`.
- §7 `H_cov`, `attribution`, correction `old`/`new` have no non-null type yet.
- §9 `zone_grid` and court-template `lines[]` have no defined shape yet.
- Named components: schemas are inlined in OpenAPI output; add `.meta({ id })` names when the SDK
  needs readable type names (additive).
