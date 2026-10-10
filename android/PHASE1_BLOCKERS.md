# Phase 1 · open dependencies on other lanes (Person 2)

Each item names what Android needs, why, the owner, and what the app does until it lands.

| # | Need | Owner | Until then |
|---|---|---|---|
| 1 | `fixtures/fixture_trail.json` (contracts §11): Person 4's hand-labelled shuttle points for the working segment of `h13g_singles` | Person 4 @HetanshWaghela | The app ships Person 2's own hand labels in `android/app/src/main/assets/fixtures/` (clicked by @darved2305 on decoded frames with `android/tools/labeller`, 40.00–43.98 s, visually checked). They are real labels with honest provenance, not Person 4's fixture. Person 4 may adopt them or replace them; the app loads any `elevate.trail_labels.v1` document. |
| 2 | A defined JSON shape for `fixture_trail.json` | Person 4 (contracts owner) | contracts §5 defines ball rows `[t_ms, x_px, y_px, confidence, state]` but not the fixture file. The Android loader reads `elevate.trail_labels.v1` (see `TrailLabelsParser.kt`), which adds what the app needs: `t_us` per frame, explicit "looked, not visible" nulls, the labelled range and frame count, and `accepted_file_sha256` to bind labels to a file. Proposal for Person 4: adopt this or a superset, through a decision. |
| 3 | `fixture_match.json`, `fixture_events.jsonl` | Person 4 | Not available, so `FixtureSource` exposes no rallies or event replay. Nothing is simulated. |
| 4 | `fixtures/clips.md` audio hash | Person 4 | Our format-140 fetch is 7,837,994 bytes, sha256 `5da67e01…9ff2`; `clips.md` pins 7,837,995 bytes, `52582f23…7b7`. The video stream matches exactly. Please re-check the audio hash or note which fetch command produced it. |
| 5 | ~~Any deployed API route~~ **Resolved (PR #11):** `GET /v1/health` is deployed; the app calls it | Person 3 @nikhilpravinpise | Sessions, analyses and the event cursor are not deployed yet (Phase 2); `analysisFor` stays `NotConnected`. |
| 8 | ~~Contract schemas~~ **Resolved (PR #10/#12):** `@elevate/contracts` 1.1.0 | Person 4 | The Kotlin readers are checked against `schemas/`. Still missing: fixture files, which `pnpm … check` would validate. |
| 6 | True VFR / slow-motion recording | Person 2 (team phones) | Record about 5 s of slow motion on the A55 and the S23 FE; probe it into `proof/video-manifest.md`. |
| 7 | ~~S23 FE on adb~~ not required (D-261011-DV2) | Person 2 | A55 is the proof device. |
