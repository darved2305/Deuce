# 11 · Decision log (after six adversarial debates)

Every major decision was attacked by an independent reviewer (Codex, GPT-6 Luna, max effort,
live web verification) acting as a skeptical engineer, judge and user. Full verdicts:
`docs/research/debates/`. This file records what we decided and why. Where a verdict was
conservative, we kept the **feature** and adopted the **honesty rule** (labels, gates,
abstention), because coding capacity is not our constraint but trust is.

## D1 · Mobile stack → native Kotlin + Jetpack Compose + Media3 (no Android Studio)

- **Decision:** Kotlin 2.4.20, Compose BOM 2026.09.00, Media3 1.11.1 (ExoPlayer, effect,
  transformer), Room 2.8.5, Glance 1.2.0, WorkManager 2.11.2, supabase-kt 3.8.0, AGP 9.3.3 /
  Gradle 9.5.0 / JDK 17. Built from the command line (`./gradlew assembleDebug`) by Person 2 on
  one Mac with the Android command-line SDK; GitHub Actions builds the APK as a backup. Nobody
  installs Android Studio.
- **Why:** the product's core is real footage with overlays glued to frames. Media3 exposes
  per-frame presentation timestamps and frame effects, and the same effect renders exported
  highlight videos. Expo SDK 57 bundles a Skia version with a reported Android video-frame bug
  (fixed only in 2.11.2, unverified with SDK 57), `expo-video` has no per-frame callback, Android
  widgets are not in SDK 57's `expo-widgets`, and EAS free build queues are unpredictable.
  Flutter would still need Kotlin for Media3.
- **Cost accepted:** Kotlin changes need a rebuild. Only Person 2 builds the app, so this does
  not slow the other three.
- **Escape hatch:** if the command-line SDK cannot be installed, build in GitHub Actions
  (free quota depends on whether the repo is public or private).

## D2 · Architecture → Hono on Supabase Edge Functions + Supabase + R2 + Modal

- **Decision:** one API (Hono on Supabase Edge Functions); Supabase Auth, Postgres (Mumbai
  `ap-south-1`) and Realtime; private Cloudflare R2 for video and result artifacts; Modal
  `Function.spawn()` for asynchronous GPU analysis (one job per analysis, ordered windows).
  Realtime Broadcast is only a "latest seq" wake-up hint; the durable event source is a REST
  cursor (`GET /v1/analyses/{id}/events?after_seq=`). No Cloudflare Worker, no queue, no SSE.
- **Upload (MVP):** the phone uploads the **original bytes** with resumable R2 multipart
  (8 MiB parts, 2 concurrent), while playing the original locally. The server splits into
  overlapping logical windows and emits events as windows complete.
- **Upgrade (Phase 4, behind a flag):** phone-side keyframe remux into independent ~6 s
  segment objects so analysis starts during upload. Ship only after it passes on real S23 FE
  and A55 gallery files; the server accepts both modes.
- **Developer platform scope:** OpenAPI 3.0.4 → TypeScript client (OpenAPI Generator
  7.26.0), read-only MCP (TS SDK 2.3.1, spec 2026-07-28), minimal embeddable replay. Python
  SDK, remote-MCP OAuth and webhooks come in Phase 4 or later.
- **Modal:** one operator account with the card (GPU requires it; Starter has 3 seats);
  broad `ap` region (×1.15) for containers, `routing_region="ap-south"` for submits; one warm T4
  only during demo windows.

## D3 · Vision engine → keep sports and cloud engine; tighten claims

- **Players:** D-FINE-S COCO, `dfine_s_coco.pth`, SHA256
  `48a6c8cc43eb57186843f752e2e8461ddd3326e0d3c575e71e6e960844683e89`, Apache-2.0, 640 input,
  10 to 15 fps sampling. RF-DETR-Small is the challenger.
- **Ball (badminton):** TrackNetV3 (MIT code and checkpoints) is the demo default. WASB-SBDT
  (MIT code; checkpoint terms unverified) is a Phase 4 challenger once its terms are checked;
  Person 4's held-out eval decides. Probe (small, eyeballed): WASB 51% candidate coverage at ~85%
  precision by eye; TrackNetV3 69% at ~50%. The manifest records each checkpoint's licence.
- **Tracking layer:** keep all heatmap candidates; constant-velocity Kalman tracklets on real
  PTS with gated Mahalanobis association; sport-specific process noise as configuration;
  interpolate at most one missing slot, drawn dashed and excluded from all metrics.
- **Free play:** TAPIR online checkpoint (`causal_tapir_checkpoint.npy`, Apache-2.0) seeded
  by one tap; pixel-space trail; SAM 2.1 as the mask alternative.
- **Court:** four named corner taps (auto-suggest deferred).
- **Geometry rule:** a homography maps **surface points only**. Airborne tracks stay in image
  space until an event estimator places a contact point on the surface.
- **Speed:** no km/h for airborne flight in this build. We show flight time (hit to landing,
  from timestamps) and, for free play, image-space px/s labelled as such.
- **Capability matrix:** every cell starts Experimental/Assisted and is promoted only by the
  held-out eval (Person 4) with clips, conditions, labels and hashes recorded.

## D4 · Coaching and memory → keep the film room; gate advice; no pgvector in V1

- Situation matching uses explicit filters, separate court and tabletop/floor planes,
  doubles roles, 180° canonical rotation, and the weighted ranker in `06` (weights are
  engineering defaults, never shown as confidence).
- Shot family is `unknown` unless the user confirms it (badminton heuristics are a later
  candidate).
- **"Better action" vs "similar moment":** the full gate is built (≥20 clean comparable
  decisions, ≥5 per compared action, ≥3 matches, ≤1 decision per rally). Below the gate the
  second clip is labelled **SIMILAR MOMENT**; above it, **WORKED FOR YOU (n/N)**. The demo will
  honestly sit below the gate.
- Memory: Supabase Postgres, structured SQL, deterministic ranking; no `pgvector` in V1.
- LLM: optional Cloudflare Workers AI `@cf/openai/gpt-oss-20b` server-side for one-line
  captions; no tool-calling dependency; deterministic templates when unavailable. Never send
  video, names or faces. Gemini free and OpenRouter free are excluded for privacy.
- Simulation: Read (predict landing zone; exact / adjacent / wrong / unscored) and Decide
  (target legality + defender spacing covered / adjacent / separated; historical counts only
  above the gate). No composite decision score, no win probability.

## D5 · AI referee → evidence assistant with a model estimate; human confirms

- States: IN, OUT, TOO CLOSE TO CALL, UNOBSERVED, always labelled **Model estimate**; the score
  changes only on a player's confirmation; the estimate and the confirmation are stored
  separately.
- Exact computation in `07` (contact point on the surface plane, Jacobians for the point and
  the homography parameters, timing term, footprint, legal polygon).
- "95%" wording is not used until Person 4's held-out bounce set shows the empirical coverage.
- Sport events differ: badminton = visible shuttle-to-floor landing (track loss is not a
  landing); table tennis = tabletop contact (sides excluded); tennis = first court contact.
- Live spoken calls: cut from this build.
- Corridor demo: single-line bounce-review prototype, ground truth from the second phone's
  240 fps slow-motion as an independent reference camera plus a measured grid.

## D6 · Product, moat, demo, name

- Promise: "Import a match. See it on your own footage with evidence-linked analysis. Confirm
  or correct. Keep one record across the app and the developer platform. Unclear measurements
  are withheld."
- Moat: pitched as **a wedge today that is designed to compound** (evidence-linked,
  consented history + corrections; organiser distribution), with the compounding stated as
  our thesis, not as an achieved fact.
- PS checklist: rows P1–P4 and P12 are in the PS text (D6 only saw an excerpt); kept. All six
  sports listed in the matrix, unsupported ones visibly unsupported. Acceptance tests carry a
  clip, conditions, ground truth, denominator and threshold, and results are recorded as
  pass / unmet / unknown / could-not-run.
- Daily-life features (widget, reminders, challenges, coach loop) stay in the product and are
  built in Phase 4; they are not in the critical demo path.
- Name: Vantage, Hindsight, Sightline, Second Look and Ghostline all collide. Shortlist to
  screen: CourtMemory, RallyMirror, MatchEcho (team vote + store/domain/trademark check).
- UI concept numbers are illustrative fixtures (canvas note + README).

## Final reviews R1–R3 (consistency, architecture walk-through, parallel work)

Three more independent reviews read the whole pack. Fixes applied:
- **One writer per thing.** Person 1's Modal job never writes Postgres; it reports through
  `/internal/analyses/{id}/events|artifacts|finalize`. Person 3 alone assigns `seq`, sends the
  Broadcast `analysis_seq`, persists the vision result, runs insights, and appends the single
  `analysis.completed`.
- **API base and auth defined:** Edge Function `api` at `{SUPABASE_URL}/functions/v1/api`; app JWT,
  developer `X-API-Key`, match-scoped embed tokens, `X-Internal-Token` for Modal; anonymous users
  must link an identity before history survives reinstall.
- **Contract precision:** exclusive `after_seq`; `decode_window_us` vs `emit_range_us`;
  `window.failed` and `point.confirmed` events; no `cancelled` state; canonical `ReviewEvent`;
  per-field provenance; one model descriptor with `source_url`; canonical model IDs
  (`tracknet_v3`, `dfine_s_coco`, `tapir_causal`); `analysis_runs` as the table name;
  deterministic R2 keys; calibration template must match singles/doubles.
- **Scope honesty:** squash, padel and pickleball unsupported until their packs exist; win
  probability excluded; tiers are targets and statuses start `unknown`; review test requires a
  pre-set call-coverage threshold; capture modes are targets until a team-phone file is audited;
  the Mendeley probe is candidate coverage on short clips, not match evidence; the racket demo
  beat needs a named rights-cleared clip or is skipped.
- **Pins:** Python 3.12 (PyAV 19.0.1 requires it), FFmpeg 9.0.2, Modal 1.6.1, AGP 9.3.3,
  WorkManager 2.11.2, supabase-kt 3.8.0, Hono 4.13.13, `@hono/zod-openapi` 1.6.3, `aws4fetch`
  1.0.20, OpenAPI Generator 7.26.0, `@modelcontextprotocol/server` 2.3.1.
- **Parallel work:** Person 2 owns Android Gradle files; Person 4 owns the root workspace and CI;
  Person 3 owns `seed_demo_match`; the Python rules mirror was removed (TS canonical, Kotlin port,
  shared vectors); Phase 2 starts with a thin end-to-end path before features; Person 4's scope
  was trimmed (one ruleset first; embed and remaining rulesets in Phase 4).

## Open decisions for the team

1. Product name (shortlist above).
2. Modal: Person 1 is the operator; agree the card and a team spend ceiling.
3. Rights for demo footage: Mendeley clips are CC BY 4.0 at dataset level but show identifiable
   tournament players; prefer team-recorded footage where possible.
4. Default badminton ruleset: BWF 3×21 now, BWF 3×15 from 4 Jan 2027, or casual.
5. Cloud retention: proposed default deletes the server copy of an original after analysis
   unless the user keeps it; clips and posters kept with consent. Confirm or change.
6. The named, rights-cleared badminton clip for the demo, and who verifies its attribution.
