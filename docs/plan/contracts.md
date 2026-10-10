# Contracts: the shared language between Person 1, 2, 3 and 4

**Frozen at the start of Phase 1.** Every person builds against these shapes and the fixtures,
so nobody waits. Changes need all four to agree; bump the schema version; announce it.

## 0. Conventions

- **Time fields:** source presentation time measured from the first presented frame. Integer
  milliseconds (`*_ms`) in records, microseconds (`*_us`) for window ranges, uncertainty as
  `*_sigma_ms`.
- **Image space:** pixels at source resolution after applying the rotation matrix; origin
  top-left. The app converts taps from view coordinates to source coordinates (undo letterbox
  and scale, apply rotation) before sending them, and stores the frame time of the tap.
- **Surface planes:** `court` (metres; `x` across from the camera's left sideline, `y` along
  from the near baseline), `tabletop` (table tennis ball, 2.74 × 1.525 m), `floor` (players'
  feet; for table tennis separate from `tabletop`). A homography maps surface points only;
  airborne points stay in image space.
- **IDs:** `m_` match, `s_` session, `a_` analysis, `w_` window, `r_` rally, `sh_` shot,
  `e_` event, `p_` player, `t_` track, `mo_` moment, `c_` clip, `rv_` review, `cp_` confirmed
  point, `cr_` correction, `art_` artifact, `k_` developer key.
- **Provenance:** every derived field carries a sibling state `observed | candidate | inferred | user`.
  A value without a state is not part of `match.v1`.
- **Capability tier** (target): `core | assisted | experimental | unsupported`. **Status**
  (verified): `unknown | pass | unmet | could-not-run`, changed only by `fixtures/eval/RESULTS.md`.
- **Real clips rule:** visuals sit on real footage; synthetic visuals carry `source: "simulated"`.

## 1. API base, auth and errors

- One Supabase Edge Function named `api` (Hono). Public base:
  `{SUPABASE_URL}/functions/v1/api`. All routes below are relative to it and start with `/v1`.
  OpenAPI `servers.url` is the same base. `GET /v1/health`, `GET /v1/openapi.json`.
- **App:** `Authorization: Bearer <Supabase access JWT>` + `apikey: <publishable key>`.
  Anonymous sign-in first; history survives reinstall only after the user links an identity
  (the app says so until then).
- **Developers:** `X-API-Key: k_…` (stored hashed, scoped read-only by default).
  `POST /v1/developer-keys`, `DELETE /v1/developer-keys/{id}` (owner JWT).
- **Embeds:** `POST /v1/matches/{id}/embed-tokens` → read-only, match-scoped token with expiry;
  never an API key in a browser.
- **Internal (Modal → API):** `X-Internal-Token` (scoped secret), routes under `/internal`.
- Errors: `{error: {code, message, details?}}`; 409 for idempotency conflicts, 429 with
  top-level `retry_after_s` when quotas are spent. Mutations accept `Idempotency-Key`. Codes in use
  (code: HTTP status; `knownErrorCodes` in `packages/contracts`): `unauthorized` 401, `not_found` /
  `analysis_not_found` 404, `conflict` / `idempotency_conflict` / `analysis_run_conflict` 409,
  `upload_too_large` 413, `validation_failed` / `invalid_body` 422, `storage_budget_reached` /
  `daily_upload_limit` / `too_many_part_requests` / `daily_download_limit` 429, `internal` 500,
  `dispatch_in_flight` / `engine_unavailable` / `uploads_paused` / `storage_busy` 503; also
  `upload_parts_invalid` / `upload_not_complete` / `invalid_transition` 409 and `analysis_limit_reached` 429
  (D-261011-HW6, D-261011-HW13).
- `GET /v1/health` → `{"status": "ok"}`.
- RLS: every row carries `owner_id = auth.uid()` (or a share grant); Realtime private channels
  authorised by RLS on `realtime.messages` for the analysis owner.

## 2. Session and upload (Person 2 → Person 3)

`POST /v1/sessions`:

```json
{
  "sport": "badminton", "format": "singles",
  "ruleset": {"id": "bwf_3x21", "version": "2025-04"},
  "mode": "match",
  "source": {"width": 1920, "height": 1080, "rotation": 0, "fps_nominal": 60, "duration_ms": 20480,
             "pts": {"median_interval_us": 16667, "min_us": 16600, "max_us": 33400, "gaps": 0, "vfr": false},
             "codec": "avc1", "has_audio": true, "audio_rate_hz": 48000,
             "device": "SM-S711B", "bytes": 51203344, "sha256": "…"},
  "calibration_input": {"method": "tap", "plane": "court", "template": "badminton_singles",
             "points_px": {"near_left": [16,196], "near_right": [334,196], "far_right": [248,34], "far_left": [102,34]},
             "frame_ms": 1200},
  "who_is_me": {"image_xy": [128,150], "t_ms": 1200},
  "free_play_seed": null,
  "rally_candidates": [{"start_ms": 4200, "end_ms": 11800, "onsets": 9, "state": "candidate"}],
  "upload_mode": "multipart"
}
```

The court `template` always matches `format` (`badminton_singles` / `badminton_doubles`).
Free play: `"sport": "free_play"`, `calibration_input` is a taped rectangle on the `floor` plane
or null, `"free_play_seed": {"t_ms": 2400, "image_xy": [812, 431]}`.

Response: `{session_id, match_id, upload: {upload_id, r2_key: "orig/{session_id}/source.mp4",
part_size: 8388608, max_concurrency: 2}}`.

- `POST /v1/sessions/{id}/upload/parts {part_numbers: [1,2,3]}` → `{parts: [{n, url, expires_at}]}`,
  presigned `PUT` URLs (short expiry; re-requestable for the same part).
- `POST /v1/sessions/{id}/upload/complete {parts: [{n, etag}]}` → `{status: "completed", bytes}`;
  idempotent, a repeated call returns the same completed state.
- `POST /v1/sessions/{id}/upload/abort` → `{status: "aborted"}`.
- `GET /v1/sessions/{id}` → `{session_id, match_id, mode, upload_mode, source, created_at, upload:
  {status, part_size, bytes, part_count, completed_at} | null}`.
- Chunked upload, `upload_mode: "segments"` (core, D-261011-HW3, D-261011-HW8). The phone remuxes the
  original into ~6 s keyframe-aligned segments, times kept on the original timeline:
  - `POST /v1/sessions/{id}/segments {seq, pts_start_us, pts_end_us, starts_on_sync_sample, bytes,
    sha256, final}` → `{seq, r2_key: "seg/{session_id}/{seq}.mp4", put_url, expires_at}`. `seq` is at
    most the highest registered seq + 1 (else 409 `segment_gap`); re-registering a seq is idempotent.
    `final: true` marks the last segment. Up to 2 segments in flight.
  - `POST /v1/sessions/{id}/segments/{seq}/complete` → the stored `SegmentManifest` plus
    `completed_at`; idempotent. After segment 0 completes, the app calls `POST /v1/analyses` as for a
    whole-file upload.
  - Engine: `POST {MODAL_SUBMIT_URL}` carries segment 0's `r2_key`; the engine then polls
    `GET /internal/analyses/{id}/segments?after_seq={n}` → `{segments: [SegmentManifest], final_seq |
    null}` (the contiguous completed prefix, in order) and never lists R2.
  - `SegmentManifest {seq, pts_start_us, pts_end_us, starts_on_sync_sample, bytes, sha256, final,
    r2_key}`. Whole-file multipart stays as the fallback.

## 3. Analysis and events

`POST /v1/analyses {session_id}` + `Idempotency-Key` → `{analysis_id, state}`. A unique
constraint on `(session_id, idempotency_key)` and a stored Modal call ID guarantee one GPU job
per analysis. Person 3 calls Person 1's Modal endpoint `POST {MODAL_SUBMIT_URL}` with
`Authorization: Bearer <modal secret>` and body `{analysis_id, r2_key, session_spec}`.

States: `awaiting_upload, uploaded, queued, processing, partial, finalizing, completed, failed`.
`partial` = first user-visible results exist while work continues. The analysis stays
`finalizing` until Person 3 has committed every record and insight; then Person 3 appends exactly
one `analysis.completed`.

**Writes from Modal go through Person 3's internal API** (Person 1 never writes Postgres):

- `POST /internal/analyses/{id}/events {type, window_seq?, decode_window_us?, emit_range_us?, artifact_id?, reason?, engine_version?}`
  → Person 3 assigns `seq` in one transaction, stores the row, then Broadcasts. The engine posts only
  `analysis.started`, `window.completed`, `window.failed`, `analysis.failed`; `window_seq` is required
  on the two window types (the dedupe key is `(type, window_seq)`).
- `POST /internal/analyses/{id}/artifacts {kind, r2_key, bytes, sha256}` → `{artifact_id}`.
- `POST /internal/analyses/{id}/finalize {vision_result: <match.v1 without insights>, clip_artifact_ids: []}`.

Event row (`analysis_events`):

```json
{"event_id": "e_…", "analysis_id": "a_…", "match_id": "m_…", "seq": 13, "schema_version": 1,
 "type": "window.completed", "window_seq": 3,
 "decode_window_us": [12000000, 18750000], "emit_range_us": [12000000, 18000000],
 "artifact_id": "art_…", "engine_version": "0.3.0", "created_at": "…"}
```

Types: `analysis.queued`, `analysis.started`, `window.completed`, `window.failed` (with `reason`),
`analysis.partial`, `analysis.finalizing`, `analysis.completed`, `analysis.failed`, and the domain
event `point.confirmed`. Only `emit_range_us` advances visible coverage. Every type can appear in the
cursor; `engine_version` is absent or null on events the API emits itself.

`GET /v1/analyses/{id}` → `{analysis_id, session_id, match_id, state, engine_version | null,
last_seq, failure_reason | null, created_at, updated_at, finalized_at | null}` (never the Modal call
ID or dispatch columns).

Realtime: private channel `analysis:{analysis_id}`, Broadcast event name `analysis_seq`, payload
exactly `{"schema_version": 1, "analysis_id": "a_…", "latest_seq": 13}`. It is a hint only.

Cursor: `GET /v1/analyses/{id}/events?after_seq={n}&limit=100` → `{events, next_after_seq,
has_more, state}`. `after_seq` is exclusive and equals the last contiguous `seq` already
applied (`0` for the first page); `next_after_seq` is the highest returned `seq`, or the input
cursor if none. Clients order and dedupe by `seq`, refetch on gaps, and poll while the analysis
screen is visible.

## 4. Artifacts and keys

Deterministic R2 keys: `orig/{session_id}/source.mp4`, `seg/{session_id}/{seq}.mp4`,
`analysis/{analysis_id}/{engine_version}/windows/{window_seq}.json`,
`analysis/{analysis_id}/{engine_version}/tracks/{kind}.json`,
`clips/{match_id}/{clip_id}.mp4`, `clips/{match_id}/{clip_id}.jpg`.
`GET /v1/artifacts/{id}` returns a short-lived signed URL after an ownership or share-grant check.

## 5. Window result (`window_result.v1`, Modal → R2)

```json
{"schema": "window_result.v1", "analysis_id": "a_…", "window_seq": 3,
 "decode_window_us": [12000000, 18750000], "emit_range_us": [12000000, 18000000],
 "models": ["tracknet_v3", "dfine_s_coco"],
 "ball": [[12016, 812.4, 233.1, 0.91, "observed"], [12033, 818.9, 240.2, null, "inferred"]],
 "players": [[12000, "t_1", 410, 520, 470, 700, 2.31, 3.05, 0.97]],
 "onsets": [[12420, 0.82]],
 "events": [{"event_id": "e_…", "type": "hit", "t_ms": 12421, "t_sigma_ms": 9, "track_id": "t_2",
             "image_xy": [790, 210], "cues": ["trajectory", "audio"], "state": "candidate"}],
 "quality": {"ball_coverage": 0.54, "camera_motion_px": 0.8, "blur_index": 0.31}}
```

Ball rows `[t_ms, x_px, y_px, confidence|null, state]` (image space). Player rows
`[t_ms, track_id, x1, y1, x2, y2, foot_x_m, foot_y_m, confidence]`.

## 6. Model descriptor (one shape for artifacts, `match.v1` and `/v1/capabilities`)

`{id, name, source_url, file, sha256, bytes, code_license, weights_license | "UNVERIFIED", trained_on[], used_for[]}`.
Canonical IDs: `tracknet_v3`, `wasb_hrnet_badminton`, `wasb_hrnet_tennis`, `dfine_s_coco`,
`tapir_causal`. Example: `{"id": "dfine_s_coco", "file": "dfine_s_coco.pth", "sha256":
"48a6c8cc43eb57186843f752e2e8461ddd3326e0d3c575e71e6e960844683e89", "bytes": 41841422,
"code_license": "Apache-2.0", "weights_license": "Apache-2.0", "trained_on": ["COCO"]}`.

## 7. The canonical match record `match.v1`

Person 1 produces the vision part through `/internal/…/finalize`; Person 3 adds insights,
moments and comparisons and serves the whole record.

```json
{
  "schema": "match.v1", "match_id": "m_…", "session_ids": ["s_…"],
  "sport": "badminton", "format": "singles", "ruleset": {"id": "bwf_3x21", "version": "2025-04"},
  "players": [{"player_id": "p_me", "track_ids": ["t_1"], "label": "you", "identity": "user_confirmed"},
              {"player_id": "p_arjun", "track_ids": ["t_2"], "label": "opponent", "identity": "user_named"}],
  "calibration": {"calibration_id": "cal_…", "plane": "court", "template": "badminton_singles", "method": "tap",
                  "H": [1,0,0,0,1,0,0,0,1], "H_cov": null, "residual_px": 1.8, "visible_fraction": 1.0, "tier": "A"},
  "analysis": {"analysis_id": "a_…", "engine_version": "0.3.0", "state": "completed",
               "models": ["tracknet_v3", "dfine_s_coco"],
               "artifacts": {"ball_track": "art_…", "player_tracks": "art_…"}},
  "capabilities": {"court": {"tier": "assisted", "status": "unknown"},
                   "ball": {"tier": "experimental", "status": "unknown"},
                   "reviews": {"tier": "experimental", "status": "unknown"},
                   "score": {"tier": "assisted", "status": "unknown"}},
  "rallies": [{"rally_id": "r_007", "start_ms": 18300, "end_ms": 26900, "shot_ids": ["sh_…"],
               "server": "p_me", "winner": "p_arjun", "winner_state": "user", "end_reason": "landing",
               "ball_coverage": 0.61, "clip_id": "c_r_007", "state": "observed"}],
  "shots": [{"shot_id": "sh_…", "rally_id": "r_007", "index": 5, "player_id": "p_me",
             "contact_ms": 21430, "contact_sigma_ms": 12, "phase": "rally",
             "reference_plane": "court", "coordinate_frame_version": 1,
             "roles": {"focal": {"player_id": "p_me", "xy_m": [1.1, 1.4], "state": "observed", "conf": 0.9},
                       "opponent": {"player_id": "p_arjun", "xy_m": [4.2, 10.9], "state": "observed", "conf": 0.8}},
             "incoming": {"source_zone": "B3", "landing_zone": "F1", "zones_state": "candidate", "family": "unknown"},
             "chosen": {"target_zone": "M2", "landing_xy_m": [3.0, 9.6], "landing_state": "observed", "family": "unknown"},
             "flight_time_ms": 1180, "flight_time_state": "observed",
             "context": {"score_before": [14, 16], "pressure_bucket": "late", "rally_len_so_far": 5, "state": "observed"},
             "outcome": {"point_winner": "p_arjun", "shots_to_end": 1, "state": "user"},
             "quality": {"camera_quality_grade": "A"}, "clip_id": "c_sh_…"}],
  "events": [{"event_id": "e_…", "type": "landing", "t_ms": 26870, "t_sigma_ms": 9, "rally_id": "r_007",
              "plane": "court", "contact_xy_m": [0.36, 2.10], "cov_m2": [[0.0009, 0], [0, 0.0016]],
              "cues": ["trajectory", "audio"], "state": "candidate",
              "evidence": [{"t_ms": 26853}, {"t_ms": 26870}, {"t_ms": 26887}]}],
  "reviews": [{"review_id": "rv_…", "match_id": "m_…", "rally_id": "r_007", "event_id": "e_…", "t_ms": 26870,
               "evidence_frames": [26853, 26870, 26887], "contact_xy_m": [0.36, 2.10], "cov_m2": [[0.0009, 0], [0, 0.0016]],
               "line_id": "left_sideline_singles", "margin_m": 0.41, "sigma_m": 0.03,
               "estimate": "IN", "visibility": "visible", "model_ids": ["tracknet_v3"], "calibration_id": "cal_…",
               "confirmed_point_winner": "p_arjun", "confirmed_by": "p_me", "confirmed_at": "…"}],
  "confirmed_points": [{"confirmed_point_id": "cp_…", "rally_id": "r_007", "winner": "p_arjun", "by": "p_me", "at": "…", "review_id": "rv_…"}],
  "score": {"timeline": [{"rally_id": "r_007", "after": [14, 17], "server_next": "p_arjun"}],
            "games": [[21, 17], [19, 21], [21, 15]]},
  "metrics": [{"key": "clear_landing_midcourt_rate", "definition_version": 1, "player_id": "p_me",
               "numerator": 7, "denominator": 9, "unit": "ratio", "tier": "experimental", "source_event_ids": ["e_…"]}],
  "insights": [{"insight_id": "in_…", "kind": "hero", "caption": "7 of 9 observed clears landed mid-court",
                "count": {"num": 7, "den": 9}, "visual": {"type": "landing_map", "filter": {"player": "p_me"}},
                "evidence_moment_ids": ["mo_…"], "gate": "descriptive"}],
  "moments": [{"moment_id": "mo_…", "rally_id": "r_007", "shot_id": "sh_…", "start_ms": 19930,
               "end_ms": 22930, "contact_ms": 21430, "tags": ["back_corner", "lost_point"], "clip_id": "c_sh_…"}],
  "clips": [{"clip_id": "c_sh_…", "source": "own", "rights": "own", "owner": "p_me", "match_id": "m_…",
             "start_ms": 19930, "end_ms": 22930, "contact_ms": 21430,
             "remote": {"artifact_id": "art_…", "poster_artifact_id": "art_…"},
             "local": {"source_uri": "content://media/…", "offset_ms": 19930}, "attribution": null}],
  "corrections": [{"correction_id": "cr_…", "target": {"type": "rally", "id": "r_007"}, "field": "winner",
                   "old": "p_me", "new": "p_arjun", "actor": "p_me", "at": "…", "reason": "proposal wrong"}]
}
```

Review estimate enum `IN | OUT | TOO_CLOSE | UNOBSERVED`; `TOO_CLOSE` renders as
"TOO CLOSE TO CALL". Score changes only from `confirmed_points`. An engine estimate carries
`confirmed_point_winner`, `confirmed_by`, `confirmed_at` as `null`; a confirmation sets all three, and
`confirmed_by` is the match player mapped to the signed-in account (`who_is_me`). Metric `player_id` is
`null` for match-level metrics (D-261011-HW12).

## 8. Other endpoints (full list in `08-developer-platform.md`)

- `POST /v1/matches/{id}/reviews/{rvid}/confirm {winner}` + `Idempotency-Key` → `confirmed_point`
  (actor from the JWT) and a `point.confirmed` event.
- `GET /v1/matches/{mid}/shots/{shot_id}/comparisons?scope=own|shared|reference&limit=3` (rank the top 5,
  return `min(limit, 5)`, default 3; candidates filtered on `ruleset_id`; `scope=shared` uses existing
  grants only in Phase 3) →
  `{query_shot_id, candidates: [{shot_id, clip_id, distance, matched_features[], role:
  "similar_moment"|"worked_for_you"|"coach_pick"|"simulated", evidence: {won, of, matches, rallies},
  provenance: "own"|"shared"|"team_reference"|"public_cc", attribution}]}`.
- `POST /v1/matches/{id}/share-grants`, `DELETE /v1/matches/{id}/share-grants/{gid}`,
  `DELETE /v1/matches/{id}` (cascades through originals, artifacts, clips, posters, summaries).
- Phase 4: `POST /v1/matches/{id}/simulation-runs {moment_id, mode: "read"|"decide", target_zone?}`
  → `{result: "exact"|"adjacent"|"wrong"|"unscored" | {legal, spacing: "covered"|"adjacent"|"separated"}, evidence_ids[], continuation_clip_id}`;
  `GET /v1/matches/{id}/opponent-scout?opponent_id=` → tiles `{pattern, n, matches, clip_ids[]}`.

## 9. Zones and sport packs

Zones: badminton and tennis halves 3 × 3 (left / centre / right from the hitter's view ×
front / middle / back) → `F1…B3`; badminton front = net to short service line (1.98 m), back =
last 1.5 m; tennis back = last 2.5 m; table tennis halves 3 × 2 (`S`/`L`, split at 0.685 m).
Service courts are rule overlays.

`SportPack {sport, formats[], court_templates{format: {length_m, width_m, lines[]}}, ruleset_ids[],
ball_profile {diameter_mm, behaviour: "ball"|"shuttle"}, event_vocabulary[], zone_grid,
tracker_ids[]}`. Built packs: **badminton** (court, rules, tracker), **table tennis** (table,
rules; no tracker), **tennis** (court, rules, static-view tracker), **free play** (no court,
TAPIR). Squash, padel and pickleball packs are Designed: listed as unsupported until built.

## 10. Rules package `@elevate/rules` (`packages/rules`, Person 4)

`RulesState {ruleset_id, ruleset_version, sport, format, teams: [[player_id…], [player_id…]],
games: [[a, b]…], current: [a, b], server: player_id, service_court: "right"|"left", ends:
{team0: "near"|"far"}, status: "in_progress"|"game_over"|"match_over"}`. Functions:
`applyPoint(state, winningTeam)`, `undo(state, history)`, `server(state)`, `courtSide(state)`,
`isGameOver(state)`, `isMatchOver(state)`. Golden vectors `vectors/{ruleset}.json` =
`[{initial, winningTeam, expected}]`, shared by the TypeScript package (used by Person 3) and
Person 2's Kotlin port. Rulesets: `bwf_3x21` first; then `casual_21`, `ittf_11`, `tennis_bo3_tb`,
`bwf_3x15` (from 4 Jan 2027), `free_play`.

## 11. Fixtures are real (D-008)

Nothing synthetic. The shared fixtures are built from a **real, rights-cleared clip**, labelled by
hand by Person 4 (every hand-labelled value carries `state: "user"`, `engine_version: "hand-labelled"`).
They are replaced by real engine output (`engine_version` = the engine's version) as soon as it
exists. No invented values, ever.

Start of Phase 1 (small, enough for everyone to start): `fixture_trail.json` (hand-labelled shuttle
points with frame timestamps for one rally of the real clip; shape `elevate.trail_labels.v1` in
`packages/contracts`, D-261011-HW4), `fixture_match.json` (`match.v1` for
that clip: rallies, landings, winners as labelled), `fixture_events.jsonl` (the event sequence a
real analysis of that clip emits, with `seq` order), `fixture_window_0003.json` (one window of the
labelled clip in `window_result.v1` shape).
During Phase 1: `fixture_freeplay.json` (a real corridor clip with hand-marked ball points and the
three real bounces with their slow-motion truth) and `fixture_comparison.json` (two real clips of
the same situation from the team's drills).
