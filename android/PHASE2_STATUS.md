# Phase 2 status · Person 2 (Android)

PASS means it ran on the A55 (SM-A556E, Android 16) or this machine in this session and the
output was seen. Started 2026-10-11 on main `735731e`. External blockers: `PHASE2_DEPENDENCIES.md`.

## Gates

| Gate | State | Evidence |
|---|---|---|
| G0 Baseline | **PASS** | main `735731e`: `assembleDebug lintDebug testDebugUnitTest` OK, 43/43 unit; A55 `am instrument` 8/8, the 5 timing tests ran on the real clip (status 0, none skipped) |
| G1 Setup | in progress | Slice 1 (preflight) below. Sport choice, court corners and who-is-me: next slice |
| G2 Upload | **BLOCKED** (P3) | sessions/upload routes return 404 on the deployed API |
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
