# Phase 2 status · Person 2 (Android)

PASS means it ran on the A55 (SM-A556E, Android 16) or this machine in this session and the
output was seen. Started 2026-10-11 on main `735731e`. External blockers: `PHASE2_DEPENDENCIES.md`.

## Gates

| Gate | State | Evidence |
|---|---|---|
| G0 Baseline | **PASS** | main `735731e`: `assembleDebug lintDebug testDebugUnitTest` OK, 43/43 unit; A55 `am instrument` 8/8, the 5 timing tests ran on the real clip (status 0, none skipped) |
| G1 Setup | **PASS** (A55) | Slice 1 preflight (#31) + slice 2 setup flow below |
| G2 Upload | in progress | sessions/analyses routes now deployed (2026-10-11: `GET /v1/sessions/s_x` → 404 "session not found"); client being built |
| G3 Analysis | **BLOCKED** (P3, P1) | analyses/events routes 404; Modal submit not deployed |
| G4 Result | **BLOCKED** (P1, P3) | no window posted live; no artifact download route anywhere |
| G5 Replay (live) | **BLOCKED** (G4) | Phase 1 overlay works on real labels |
| G6 Complete | not started | |

## Slice 1 · video preflight ("Clip check")

What it does: right after a video opens (playback is never delayed), `MediaProbe` reads the
container with `MediaExtractor`: video/audio tracks, codec, coded size, rotation, duration,
container frame rate, HDR transfer, whether a decoder on this phone accepts the format, and the
timestamp of **every** video sample (sample table only, no sample data in memory). `PtsStats`
measures the real presentation spacing (median/min/max, gaps, VFR, mean → measured fps);
`Preflight` turns facts into findings: Block (no video, no frames, > 300 MiB upload cap G1), Warn
(this phone can't play the codec, cut short, HDR, below 720p, dropped frames), Info (VFR, no
audio, duration measured from frames, timing walk capped). "Decodable" means a phone decoder
accepts the format (mime, size, profile), not that it sustains the frame rate. A missing phone
decoder only warns: analysis decodes in the cloud. Damaged files that open but fail while being
read become a readable error, never a crash. Nothing here is a model output or a
guess about court visibility: there is no validated court-visibility method, so none is shown.

Runs in parallel with the sha256 fingerprint, so the Phase 1 annotation lookup is not delayed,
and stops when the user leaves or picks another video.

### Checks
| Check | Result |
|---|---|
| `./gradlew assembleDebug lintDebug testDebugUnitTest` | PASS · 62 unit tests (43 Phase 1 + 19 `PreflightTest`) · lint 0 errors (2 known warnings) |
| `PreflightTest` vectors | constant 50 fps; decode-order input; **real** EuroGames PTS (`engine/tests/data/eurogames_pts.json`, CC BY-SA 4.0) → gaps found, not VFR; 16/17 ms WebM spacing → ~60 fps; duplicate PTS; no duration; capped walk; every finding code; cap boundary (300.1 MiB) |
| A55 `MediaProbeCorpusTest` (real files) | PASS: six clips equal the ffprobe numbers in `proof/video-manifest.md` exactly (codec, size, frame count, median/min/max µs, gaps) and measure the stated rate ±0.5 fps; fails if a clip is missing; truncated MP4 → blocked `no_video` |
| A55 all instrumented tests | PASS 10/10 for this slice (status 0 each, none skipped) incl. the 5 Phase 1 timing tests |
| Fresh-context review (`slice-reviewer`) | 5 blocking findings, all fixed: crash on damaged-but-openable files; false block without a duration; "can't be analysed" for a phone-decoder gap; 1-interval fps (58.82 for 60 fps WebM); VFR overclaim. Optional fixes taken: negative PTS, capped walk, zero median, locale, size rounding, SecurityException passthrough |

### Probe results on the A55
| Clip | Facts read | Findings | Probe time |
|---|---|---|---|
| `h13g_singles.mp4` | avc1 1920×1080, 24,227 frames, 20000/20000/20000 µs, 0 gaps, audio 44.1 kHz | none | 6.8 s |
| `china_open_2025_final.webm` | vp09 1920×1080, 30,349 frames, 17000/16000/17000 µs, 0 gaps | none | 21.2 s |
| `sport_en_france_4k.webm` | vp09 3840×2160, 16,129 frames, 40000 µs, 0 gaps | too_large (752 MiB) | 20.4 s |
| `eurogames_bern_1.webm` | vp08 848×464, 767 frames, 33000/33000/67000 µs, 1 gap | low_res, pts_gaps | 0.9 s |
| `eurogames_bern_2.webm` | vp08 848×464, 2,904 frames, 3 gaps | low_res, pts_gaps | 3.6 s |
| `vfr_tabletennis_handheld.webm` | vp09 1080×1920 portrait, 573 frames, 38000/38000/39000 µs | none | 0.7 s |
| `corrupt_truncated_h13g.mp4` | no video track readable | no_video (blocked) | — |

### Device proof (real Photo Picker path, `proof/phase2/slice1/`, gitignored)
`01_h13g_clipcheck.png` ("20.0 ms frames (50 fps measured)", no issues; trail annotation still
"107 of 149"), `05_china_open.png` ("16.7 ms frames (60 fps measured)"), `02_eurogames1.png`
(below 720p, 1 gap), `03_corrupt.png` (can't be played + can't be analysed), `04_4k.png`
(752 MiB, upload cap).

### Known limits
- The full sample walk takes up to ~21 s on long WebM files; the card says "Checking the clip…"
  meanwhile. Fine for now (the session request needs it only at upload time).
- Mendeley portrait badminton clips (CC BY 4.0) are on the phone now (app corpus dir): two are
  4K HEVC rotated 90° HDR, one is Dolby Vision. HDR detection is unit-tested; on-device HDR rows
  arrive with slice 2.
- Picker on this A55 build asks for **Done** after a tap (Google Photo Picker), noted for UI scripts.

## Slice 2 · set up analysis (sport, court corners, who-is-me)

"Set up analysis" (shown once the clip check is done and not blocked) pauses the player and opens
a 4-step flow on the **paused frame**: sport (badminton only in this build; table tennis, tennis,
free play shown as "not in this build" because contracts §10 has no ruleset version for them),
four named corner handles with a 3× loupe while dragging, undo/reset, geometry checks before Next
(`CourtGeometry.check`), who-is-me tap, review ("Nothing is uploaded yet"). Output = the
contracts §2 fields `sport, format, ruleset, mode, calibration_input, who_is_me, free_play_seed`
in **source pixels after rotation** (contracts §0), validated against
`session.create.request.v1.schema.json` in `SetupResultJsonTest`.

| Check | Result |
|---|---|
| Unit | 83/83 (geometry 7, reducer 10, JSON-vs-schema 4, + earlier) · lint 0 errors |
| A55 instrumented | 11/11 status 0; `FrameGrabRotationTest`: rotated 4K HEVC HDR clips come back upright (3840×2160 rot 90 → 2160×3840); a 1.2 s Dolby Vision clip returns no frame → the flow shows "Couldn't read a still frame from this video" |
| A55 flow on `h13g_singles` (`proof/phase2/slice2/`) | paused at PTS 41.780000 → setup says "Frame at 41.78 s"; 4 corners tapped, loupe shown mid-drag (`05_loupe_drag.png`); review "Badminton · Singles · Match · bwf_3x21 (2025-04) · 4 of 4 placed · badminton_singles · you marked at 41.78 s"; back on the player at 0:41.78 with "Set up · Badminton singles · 4 court corners · you at 0:41.78"; landscape layout (`09_court_landscape.png`); no crash |
| Independent mapping check | the player's position in the **replay** screenshot (Media3 surface) converted to source px and back through the setup screen's `FitTransform` lands the "You" marker on the same player (`06_who_is_me.png`) |

Known limits: `h13g_singles` at 41.78 s does not show the near baseline, so the corners tapped in
the proof exercise the mapping, not a real calibration. "Tap to change" restarts the flow instead
of reloading the previous corners. TalkBack pass not done.

## Recorded engine run on the real video (G5 on recorded engine output)

Person 1's real TrackNetV3 run on `h13g_singles` window 1 (#37, `engine/eval/runs/h13g_w1`,
emit 37.00–43.00 s, 302 candidate rows on 204 of the 300 emitted frames) is copied into the app
at build time and offered only on a file whose sha256 matched the hand-label fixture for the same
clip. Row: "TrackNetV3 · recorded engine run · candidates · a candidate on 204 of 300 frames ·
highest confidence per frame (302 rows) · engine 0.1.0, not live". Drawn as cyan hollow rings with
dashed joins (never the solid orange of confirmed points), keyed to frame PTS like the hand trail.

Bug found and fixed on the real file: `WindowResultStore` rejected **both** rows whenever a frame
had two candidates (93 frames lost, including the real shuttle at 37.02 s). Several candidates
per frame are now kept and the highest confidence wins; contradictory observed rows are still
rejected. `RecordedEngineRunTest` pins 204 frames and the 37.02 s pick on the real file.

A55: paused at PTS 41.280000, the candidate ring is drawn at display (443, 596) = source
(922.5, 723.8) through the video rect (`proof/phase2/engine_run/01_engine_candidates_41s.png`,
zoom `02_zoom_41s.png`). Against the hand labels: 1.4–7.2 px on 41.10–41.30 s; 70 of 77 frames that
have both within 15 px.

Also fixed: in portrait the controls sheet squeezed the video once a row was added; it is now
capped at 55 % of the height and scrolls.

## End-to-end run on the A55 against the deployed backend (2026-10-11)

Pick `h13g_singles` → clip check → setup (4 corners, you at 41.26 s) → **Upload & analyse**:

| Step | Result |
|---|---|
| Session created on the deployed API | `s_3df758f240434f74820e829033966138` |
| Upload over LTE (Airtel) | 10 min, **0 of 8 parts confirmed**, then `SSLHandshakeException` on `upload/parts` → stopped. Two bugs found: URL fetch outside the per-part retry (fixed, regression test); rate/ETA from socket-buffered bytes showed "0.4 MiB/s · 4 min left" while ~0.02 MiB/s was confirmed (fixed: rate, ETA and bar from server-confirmed parts only) |
| App reinstalled (process death) → restored from disk → Retry over Wi-Fi | notification **"Uploaded · server confirmed 63,698,735 bytes · 60.7 MiB stored"** (= the file's size) |
| `POST /v1/analyses` | analysis `a_73b8c407edb6440a953f0204796370e6`, event 1 `analysis.queued` at 03:37:29; Analysing screen shows the real stages |
| Relaunch | upload restored as completed, start re-sent with the same Idempotency-Key → **same analysis** (no duplicate GPU job); "0.0 of 484.5 s analysed" (duration from the saved session request) |
| Engine | **no `analysis.started` / `window.completed` after the run** → Person 1 (engine posting events) and Person 3 (`GET /v1/artifacts/{id}`, `GET /v1/matches/{id}`) |

Also fixed during the run: a retry after a failure ended at once on the stale "failed" progress
value (the waiter now follows the saved upload phase; device-verified path: completed → start).
