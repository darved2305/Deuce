# 06 · Coaching intelligence: film room, real-clip comparisons, memory, opponent, simulation

Decided in debate D4 (`11-debates.md`). Order: analysis and evidence first; comparisons;
history; opponent analysis and simulation in Phase 4.

## 1. The atom: a shot record

One record per contact, written by the finalizer (`contracts.md` §4 `shots`):

- identity: `match_id, rally_id, index, player_id, contact_ms, contact_sigma_ms, clip_id`
- frame of reference: `sport, ruleset_version, format, reference_plane` (`court` | `tabletop`),
  `coordinate_frame_version`; player feet always on the `floor` plane (for TT, separate from
  the tabletop plane used for ball targets)
- roles: `focal`, and `opponent` (singles) or `partner`, `opponent_left`, `opponent_right`
  (doubles), each with position (m), team, side, tracking confidence
- incoming: `source_zone`, `landing_zone`, `family` (`unknown` unless user-confirmed)
- chosen: `target_zone`, `landing_xy_m` (observed landings only), `family` (`unknown` unless confirmed)
- context: `score_before`, `pressure_bucket`, `phase` (serve / return / rally), `rally_len_so_far`
- outcome: `point_winner`, `shots_to_end`
- quality: `camera_quality_grade`, per-field state (`observed` / `candidate` / `inferred` / `user`)

**Canonical orientation:** rotate the whole court 180° so the focal team is in the bottom half
(`x → W − x`, `y → L − y`); never mirror the image; keep handedness.

**Zones:** badminton and tennis halves 3×3 (left/centre/right × front/middle/back; service
lines are overlays, not cells); table tennis 3×2 on the tabletop.

## 2. Analysis first (what the overview computes)

| Analysis | Input | Shown as | Gate |
|---|---|---|---|
| Rally summary | rallies, hit candidates | barcode, length distribution, longest rally clip | always |
| Movement | player tracks (m) | coverage map, recovery to base after shots | calibration tier A |
| Placement | observed landings | landing map per zone, counts | ≥5 observed landings |
| Where points ended | confirmed winners by zone and rally length | court map with n per zone | ≥5 points in a zone |
| Pressure | outcomes by score bucket | small multiples vs baseline | labelled exploratory |

## 3. Comparisons with real clips

### 3.1 Finding similar moments (deterministic, no training)
1. **Filters (exact):** sport, ruleset version, format, phase, camera-quality bucket; exclude
   the query rally (and the query match when building a personal pattern).
2. **Distance (normalised to [0,1], missing features dropped and weights renormalised):**
   focal position 0.35 (Euclidean / half-court diagonal); opponent/partner geometry 0.30;
   incoming source+landing zone pair 0.20 (Manhattan / max grid distance); confirmed shot
   family 0.10 (0 same, 1 different); pressure bucket 0.05.
3. Require ≥70% of weight observed; return up to 5 with distance ≤ 0.30, showing which features
   matched. Weights and cutoffs are engineering defaults, never shown as confidence.
4. No match → "No similar moment yet" (never widen silently).

### 3.2 From similar moment to "worked for you"
Group matched moments by the action taken (target zone; family when confirmed). Show counts
with denominators, distinct rallies and matches. Display rules:

| Evidence | Second clip label | Caption style |
|---|---|---|
| Below the gate | **SIMILAR MOMENT** | "From this spot in this match: you won 2 of 3" (descriptive) |
| Gate met: ≥20 clean comparable decisions, ≥5 per compared action, ≥3 matches, ≤1 decision counted per rally | **WORKED FOR YOU · n/N** | "Deep clear from here: won 9 of 12 across 4 matches" |
| Coach-confirmed | **COACH PICK** | coach's one line |

These thresholds are provisional product guardrails, not sports science; associations are not
causes. Clip choice and rights rules: `12-real-clips.md` §4.

## 4. Memory across many sessions

- **Canonical store:** Supabase Postgres (Mumbai). Immutable `analysis_runs`; append-only
  `corrections`; re-analysis by a newer engine creates a new run plus an event correspondence
  map; summaries are rebuildable caches with source IDs.
- **Retrieval:** structured SQL plus the deterministic ranker above (no `pgvector` in V1).
- **Player claims, not prose:** e.g. "backhand errors under wide forehand pressure" with support
  count, counter-examples, validity dates; updated after each match by code, never by the LLM.
- **Comparability per metric:** A calibrated / B directional / C qualitative, from capture
  quality and calibration; trends only across comparable sessions; engine-version changes marked.
- **Assistant functions (read-only, SQL does all counting):** `list_sessions(filters)`,
  `search_moments(filters)`, `get_metric_trend(metric, range)`,
  `compare_sessions(a, b, metric)`, `get_moment(id)`. Natural-language questions are parsed into
  editable filter chips first. Every returned ID is validated.
- **LLM (optional):** Workers AI `@cf/openai/gpt-oss-20b`, server-side, writes at most one
  sentence from returned facts and IDs; no tool calling required; deterministic templates
  when unavailable; never receives video, audio, names or emails.

## 5. Opponent analysis (Phase 4)

Tendencies `P(target zone | source zone, incoming zone, phase, pressure)` from the opponent's
shot records, as tiles with n and distinct matches; ≥10 opportunities in a cell to show "seen
in this match", ≈30 across ≥3 matches to call it a tendency; "weakness" never inferred from
placement alone. Output: a 3-tile scouting card before a rematch, each opening a reel.

## 6. Game simulation (Phase 4)

The team's idea (freeze at the opponent's hit, choose your shot) has an information gap: at
that instant the incoming flight is not visible yet. Two beats keep the idea and fix the gap:

1. **Read:** freeze just before the opponent's contact (configurable offset); tap the expected
   landing zone; reveal the real flight. Score `exact` / `adjacent` / `wrong` only against a
   reliable observed landing, else `unscored`.
2. **Decide:** freeze before your contact; tap a target zone. Show legality (inside / outside)
   and defender spacing at that frame (`covered` same cell, `adjacent` one cell, `separated`
   two+; nearest defender in doubles). Historical counts appear only above the gate.
3. **Reveal:** the real continuation plays; if a real clip of a different choice from a similar
   moment exists, it plays beside, labelled by §3.2 rules.

No composite "decision score", no win probability. Modes: solo from your matches; challenge a
friend with the same freeze; coach assigns 5 freezes.

## 7. Claims we never make

"Optimal shot", "will win", "weak side" without its gate, "AI predicts your opponent", any
improvement percentage.
