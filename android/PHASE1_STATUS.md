# Phase 1 status · Person 2 (Android)

PASS means it was run on the phone or the machine in this session and the output was seen.
Updated 2026-10-11 00:30, rebased on main 80c5b0a (contracts 1.1.0, API skeleton). Device: Galaxy A55 SM-A556E, Android 16 (API 36), Exynos 1480.
**S23 FE: not required for Phase 1 (owner decision D-261011-DV2); every S23 FE row is NOT RUN.**

## What works (A55)
- The Gradle project builds from the command line (Kotlin 2.4.20, AGP 9.3.3 built-in Kotlin, Gradle
  9.5.0 wrapper with a checksum-verified distribution, JDK 17, Compose BOM 2026.09.00, Media3 1.11.1).
- System Photo Picker, videos only, with no storage permission. The file is validated before
  playback (MediaMetadataRetriever); failures show the reason.
- ExoPlayer playback: play/pause, seek slider, time/duration, ¼× ½× 1×, replay, back to import,
  picking another video, portrait and landscape layouts, rotation and background/resume that keep
  the exact paused frame.
- Trail drawn **inside the video frames** by a Media3 `OverlayEffect`/`CanvasOverlay`, keyed only to
  each frame's `presentationTimeUs`. Only hand-labelled positions are drawn; gaps stay gaps;
  nothing is interpolated. The trail toggle redraws the held frame while paused.
- Labels are bound to the exact file by sha256. Any other video shows "No annotations for this video".
- `MatchRepository` → `FixtureSource` (real hand labels) / `ApiSource`. `ApiSource` makes one real
  call, `GET /v1/health`, to Person 3's deployed API. On the A55: "Elevate cloud online · 724 ms";
  with wifi and data off: "unreachable · no network" (`slice5/20_backend_status.png`,
  `slice5/21_backend_offline.png`). The URL comes from the untracked `local.properties`
  (`elevate.apiBaseUrl`); builds without it say "not configured". There are no analysis calls
  yet: those routes are not deployed (Phase 2).
- Kotlin readers for `@elevate/contracts` 1.1.0: `error.envelope.v1`, `realtime.hint.v1`,
  `analysis.event.v1`, `analysis.events.page.v1`, `window_result.v1` (+ typed ball rows).
  `ContractSchemaTest` compares them with `packages/contracts/schemas/*.schema.json` (field sets,
  required ⇒ non-null) and decodes the fenced examples in `contracts.md`. It already caught one
  drift: `engine_version` is required on every event.
- Debug builds stamp each frame's own PTS into the frame (proof aid).
- Event handling for contracts §3 (`EventLog`): seq order, dedupe, stop at gaps, `after_seq`, state,
  coverage from `emit_range_us`. `EventReplay` replays a recorded `fixture_events.jsonl` with its
  recorded `created_at` spacing. Unit-tested on test vectors only: **no real event file exists yet**
  (Person 4), so the app replays nothing today. Any `*.jsonl` in `assets/fixtures` is picked up.

## Checks run (final code)
| Command | Result |
|---|---|
| `./gradlew --version` | Gradle 9.5.0, launcher JVM 17.0.20.1 |
| `./gradlew clean assembleDebug` | PASS |
| `./gradlew testDebugUnitTest` | PASS 43/43 (TimeMapping 8, TrailLabelsParser 10, repository 6, annotation text 2, ContractSchema 5, ApiSource 5, EventLog/replay 7) |
| `./gradlew lintDebug` | PASS: 0 errors; 2 warnings kept on purpose (below) |
| instrumented, `am instrument` on the A55 | PASS 8/8: ImportScreen ×3, OverlayTimingTest ×5 (real clip, real decoder) |

`OverlayTimingTest` needs `h13g_singles.mp4` in `/sdcard/Android/data/com.elevate.app/files/`; without
it, it **skips**. `connectedDebugAndroidTest` uninstalls the app and deletes that file, so the timing
tests are run with `installDebug installDebugAndroidTest` + `adb shell am instrument -w …`.

What the timing tests prove on the A55: after paused seeks to 41.0, 40.5, 43.98, 40.0 and 42.4 s, the
first frame the overlay draws has exactly that PTS. At 0.25× and 1×, 50 consecutive frames
arrive in order with exactly 20 ms spacing (no skips or duplicates). The bundled labels resolve for
the decoded frames. Toggling the trail while paused redraws the same frame (43.300000 s).

## Device matrix
| Test | A55 | S23 FE | Evidence (`android/proof/`, gitignored binaries) |
|---|---|---|---|
| Install APK | PASS | NOT RUN | `adb install -r` → Success |
| Launch without crash | PASS | NOT RUN | `am start -W` TotalTime 829–2607 ms; crash buffer empty |
| Import badminton video | PASS | NOT RUN | picker → `h13g_singles`, `china_open_2025_final` |
| Play/pause | PASS | NOT RUN | `slice5/A55_final_proof.mp4` |
| Seek forward/backward | PASS | NOT RUN | recording + OverlayTimingTest |
| 0.25× playback | PASS | NOT RUN | recording: PTS 39.56→42.56 s over 12 s of recording |
| 1× playback | PASS | NOT RUN | recording + OverlayTimingTest |
| Overlay–frame alignment | PASS (see note) | NOT RUN | `slice2/11_paused_in_labels.png`, `_toggle2.png` |
| Portrait video | PASS | NOT RUN | `vfr_tabletennis_handheld` (portrait table tennis; no portrait badminton yet) |
| Landscape video | PASS | NOT RUN | `h13g_singles`, `china_open_2025_final`, `sport_en_france_4k` |
| Device rotation | PASS | NOT RUN | `slice5/_rotation_pts.png`: same PTS 43.300000 in all 4 states |
| Background/resume | PASS | NOT RUN | Home→icon, Home→am start, Recents: same position and frame |
| Repeated video selection | PASS | NOT RUN | h13g → China Open → corrupt → h13g |
| Malformed/unsupported media | PASS | NOT RUN | `slice5/05_corrupt_file.png`: "This video can't be played · ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE" |
| 4K VP9 decode with effects | PASS (plays) | NOT RUN | `sport_en_france_4k` played; dropped frames not measured |
| Rotated (−90°) 4K HEVC 10-bit **HDR** (HLG) phone clip | PASS as "plays, trail not available for HDR" | NOT RUN | `slice5/08_portrait_hevc4k_rot90_hdr.png`; SDR↔HDR switching 5×, 0 errors |
| True VFR / slow motion | NOT RUN | NOT RUN | no such clip yet |

Alignment note: at PTS 43.300000 the label (1082.7, 589.5) source px maps to (520, 737) in the
screenshot's video area, and the drawn point is at about (519, 736). With the trail off, the white
shuttle is visible under the same spot. That is a visual check on one frame, not a
frame-by-frame audit of all 200.

## Measured performance (A55, `h13g_singles` 1080p50 H.264, effect pipeline on)
- Open → first frame drawn by the overlay: first open 874 / 911 ms (two runs); later opens 314–417 ms.
- Paused seek → target frame drawn: 204–736 ms (keyframe distance dependent).
- Measured in `OverlayTimingTest.measureOpenAndSeekLatency` against an off-screen ImageReader surface.
  Not measured: on-screen frame rate, dropped frames, memory.

## Bugs found and fixed this session
1. Every pick failed: `Unexpected runtime error` ← `setVideoEffects(REDRAW)` needs Media3's
   replayable cache, reachable only through the library-restricted `PlaybackVideoGraphWrapper`
   (lint `RestrictedApi`). Fix: while paused, an exact seek to the held frame's own PTS (from
   `VideoFrameMetadataListener`); ExoPlayer skips seeks to the current position, so the target is
   chosen to differ.
2. Trail toggle had no effect while paused (seek to the current position was a no-op): same fix,
   now covered by `togglingTheTrailWhilePausedRedrawsTheSameFrame`.
3. Relaunch from the icon after Home showed "Add your first match": a second activity instance was
   created → `launchMode="singleTop"`.
4. After resume or rotation the held frame jumped 1–3 frames ahead of the paused position (a
   pre-decoded frame was shown) → re-seek on `onSurfaceSizeChanged` while paused.

5. HDR (HLG/PQ) video failed in the overlay shader: Media3 1.11.1 treats every BitmapOverlay on HDR
   as Ultra HDR and requires a gainmap (OverlayShaderProgram.java:129). HDR files now play without
   the effect and say "Trail not available for HDR video yet". **The trail on HDR is a Phase 2 item.**
6. After an HDR clip, the next SDR clip failed (`DefaultVideoFrameProcessor.checkColors`): the player
   is rebuilt when the colour class changes.
7. Review fixes (slice-reviewer): the annotation row now shows "seen in 107 of 149 labelled frames ·
   51 of 200 not labelled" (it showed "of 200" before, which hid that 51 frames are unlabelled); the
   parser rejects unknown provenance states and requires `user` together with `hand-labelled`; the
   fingerprint step also catches SecurityException; the redraw is skipped while `playWhenReady`.

Known limit (from review): `reassertPausedFrame` seeks to position − 1 ms. If you pause mid-frame
during playback, the held frame can move to the next frame. The trail always matches the frame
shown.

## Real labels
`app/src/main/assets/fixtures/trail_h13g_singles_40000_43980.json`: clicked by @darved2305 on 200
decoded frames, 40.00–43.98 s (PTS from ffmpeg showinfo, cross-checked with ffprobe, constant 20 ms).
Every point was checked visually on 2–4× crops: 107 points kept, 34 removed (off the shuttle), 17
"not visible" marks removed (shuttle visible), 42 "not visible" kept. Nothing was moved or added.
Re-click pass (2026-10-11, @darved2305): 51 frames re-clicked, visually checked again: 47 kept, 4
removed (48 off by about 11 px; 136 and 174 reused old coordinates on empty court; 137 marked not
visible but the shuttle is in the server's hand). 196 of 200 frames labelled (153 points, 43 not visible, 4
unlabelled: 48, 136, 137, 174). That version waits in `android/tools/labeller/handoff/` for Person 4
to copy into `fixtures/fixture_trail.json`. Since D-261011-HW4 the app has no copy of its own: Gradle
`copyRealFixtures` puts `fixtures/fixture_trail*.json` and `fixture_events*.jsonl` into its assets at
build time, so today it ships Person 4's 149-frame file ("107 of 149").

## Decisions taken in the Android lane
- compileSdk 37: required by the pinned Compose UI 1.12.1 and core-ktx 1.19.1. targetSdk stays 36.
- The overlay canvas is downscaled for frames over 1920 px on the long side (4K → half size,
  OverlaySettings scale 2), to avoid 33 MB-per-frame uploads.
- Lint: the version-freshness checks are disabled because versions are pinned by D-001. Kept as
  warnings: `ObsoleteSdkInt` (moving the adaptive icon out of `-v26` broke aapt; tried) and
  `DataExtractionRules` (the rules apply to API 31+; `allowBackup=false` covers older versions).

## Not done / gates still open
- S23 FE: not required (D-261011-DV2); unverified.
- True VFR/slow-motion clip; portrait *badminton* clip (the Mendeley dataset is downloading).
- Person 4's `fixture_trail.json`, `fixture_match.json` and `fixture_events.jsonl` (see `PHASE1_BLOCKERS.md`).
- 4 frames unlabelled (48, 136, 137, 174); optional re-click.
- Independent review: done (`slice-reviewer`); 4 blocking findings addressed. Decision entry D-261011-DV1 proposed (needs Person 4).
- Debug builds stamp PTS into frames: demos must use a release build.
- Accessibility: TalkBack labels exist on controls; no full TalkBack pass or font-scale check yet.

## Media3 1.11.1 facts this code relies on (read in the `-sources.jar`, not assumed)
| Fact | Source | Also checked on device |
|---|---|---|
| `CanvasOverlay` reuses one bitmap per size; `onDraw` must clear it | `effect/CanvasOverlay.java` `getBitmap()` | trail clears between frames (screenshots) |
| BitmapOverlay re-uploads only when the bitmap's generation ID changes | `effect/BitmapOverlay.java` `getTextureId()` | none |
| Overlay size on frame = overlay/frame size × `OverlaySettings.scale` | `effect/OverlayMatrixProvider.java` `getTransformationMatrix()` | 1080p trail at label px (alignment note) |
| Decoder applies rotation; effect frames are upright | `exoplayer/video/MediaCodecVideoRenderer.java` ~L1937 ("The decoder applies the rotation…"), L2674 `KEY_ROTATION` | rotated HEVC clip displays upright (HDR, so no trail on it yet) |
| Effect `presentationTimeUs` = buffer time + adjustment (−stream start) = media time | `PlaybackVideoGraphWrapper.java` L1033; `MediaCodecVideoRenderer.getBufferTimestampAdjustmentUs()` L2169 | `OverlayTimingTest`: seeks land on exact source PTS |
| `setVideoEffects(REDRAW)` needs the replayable cache, exposed only via restricted `PlaybackVideoGraphWrapper.Builder.setEnableReplayableCache` | `effect/DefaultVideoFrameProcessor.java` L686; `MediaCodecVideoRenderer.createPlaybackVideoGraphWrapper` (TODO b/391109644) | crash reproduced, then avoided |
| HDR input + BitmapOverlay requires an Ultra HDR gainmap | `effect/OverlayShaderProgram.java` `findHdrTypes()`, L129 | crash reproduced on HLG clip, then avoided |
