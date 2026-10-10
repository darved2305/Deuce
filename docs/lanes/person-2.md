# Person 2 · Android app (Kotlin + Jetpack Compose + Media3)

Paste this file into your coding agent as the kickoff prompt.

## Mission
Build the app players fall for: pick a video, it plays instantly, analysis arrives on the **real
footage** with overlays glued to the frames, one clear takeaway, every number one tap from the
frames that prove it, and an honest "not sure" whenever the evidence is weak. Dark, video-first,
almost no text.

## Read first
`docs/plan/05-app-experience.md`, `docs/plan/12-real-clips.md`, `docs/plan/contracts.md` (§0–3, 7–8),
`docs/plan/03-architecture-and-streaming.md` §2–3, `docs/plan/07-ai-referee.md` §4, `docs/plan/11-debates.md` D1.
UI concept: `docs/ui/` (illustrations stand in for footage; numbers are illustrative). It is a starting point, not final: you own the final UI design (`docs/ui/` is in your lane) and can change layouts, motion and styling freely; the product rules in `AGENTS.md` stay fixed.

## You own
`android/` including `settings.gradle.kts`, the Gradle wrapper and the version catalog; the
Kotlin port of `@elevate/rules`.

## Stack (pin exactly; no Android Studio)
Kotlin **2.4.20** · AGP **9.3.3** · Gradle **9.5.0** · JDK **17** · Compose BOM **2026.09.00**
(UI/Foundation 1.12.1, Material 3 1.4.0) · Media3 **1.11.1** (`exoplayer`, `ui`, `effect`,
`transformer`; effect APIs need `@OptIn(UnstableApi::class)`) · Room **2.8.5** · Glance **1.2.0** ·
WorkManager **2.11.2** · supabase-kt **3.8.0** (Auth + Realtime) · OkHttp + kotlinx.serialization
(models generated from the contracts JSON Schema; pin in the catalog).

Setup without Android Studio: `brew tap android/tap && brew install android-cli`, then
`android sdk install platform-tools platforms/android-36` (exact ids: `android sdk list --all`) and
`android skills add --all --agent claude-code` (official Android agent skills). JDK 17.
Dev loop: `./gradlew assembleDebug lintDebug testDebugUnitTest` → `adb install -r` → `adb logcat`.
GitHub Actions builds the debug APK on every merge to `main` as a backup.
AGP 9 traps: never apply `org.jetbrains.kotlin.android` (built-in Kotlin); Room via KSP, not kapt;
minSdk 26 (supabase-kt); all Media3 modules on the same version; `setVideoEffects` before `prepare()`.

## How you ship
In slices, straight to `main`, through the pipeline in `docs/plan/10-build-plan.md` §6: small slice → your lane's checks → agent code review → PR → squash-merge on green CI → deployed. Unfinished work hides behind a flag. If you break `main`, fixing it comes first.

## Phase 1 · Foundations (de-risk the heart of the product)
1. Gradle project builds and installs on the S23 FE and A55.
2. Photo Picker → ExoPlayer plays a gallery video.
3. **Overlay spike:** a Media3 frame effect (`OverlayEffect` / `TextureOverlay`, drawing keyed to
   `presentationTimeUs`) draws `fixture_trail.json`; verify at 1×, 0.25× and while seeking on both
   phones. The clip is the working segment in `fixtures/clips.md` (copy it to both phones' galleries).
4. `MatchRepository` with `FixtureSource` (Person 4's hand-labelled **real** clip + timed replay of
   `fixture_events.jsonl`) and `ApiSource`. No invented data anywhere in the app.

## Phase 2 · First real result
- Add match: Photo Picker; preflight (MediaExtractor PTS stats, rotation, codec); audio onsets →
  rally candidates (dotted, "from sound"); four named corner handles with a loupe, converted to
  source coordinates; who-is-me tap; Free play ball tap; "what this clip supports" card.
- Upload: R2 multipart (8 MiB parts, 2 concurrent) through presigned part URLs; persist upload ID,
  parts and ETags in Room; resume after process death; user-initiated data transfer job
  (API 34+, `RUN_USER_INITIATED_JOBS`) with a notification; WorkManager for deferred retry.
- Live analysis: subscribe to `analysis:{id}` (`analysis_seq`) → cursor fetch; order and dedupe by
  `seq`; real bytes, rate, ETA, stages; never a fake percentage.
- Overview and rally replay on fixtures, then on live data: trail (observed solid, one-slot
  dashed, gaps), landing marks, foot paths, mini court, flight-time tag, frame step with a haptic
  tick, playback at 0.25× / 0.5× / 1×.

## Phase 3 · Full loop
- **Chunked upload (D-261011-HW3, judges' ask):** remux the original into ~6 s keyframe-aligned
  segments (MediaExtractor + MediaMuxer, stream copy), upload in order with one presigned PUT
  each, 2 in flight, retry each at most 3 times (Cloudflare rule G11). Show the trail for finished
  segments while later ones upload. Measure keyframe spacing on real S23 FE and A55 files first;
  keep whole-file multipart as the fallback.
- Review screen (`07` §4): three frames, surface map, `MODEL ESTIMATE` chip, Confirm / Correct.
- Compare: two real clips synced at `contact_ms`, one scrubber, provenance and role chips; A55
  fallback (one plays, the other steps).
- Room cache for offline history; Kotlin rules port passing the shared vectors.
- Deep link `elevate://m/{match_id}/r/{rally_id}?t_ms={t}` with an intent filter: opens the rally
  and seeks.

## Phase 4 · Depth and extras
Matches history + search chips, Today, Glance widget, notifications, highlight export (Media3
Transformer with the same overlay effect), simulation screens, voice query, motion polish.

## Phase 5 · Demo-ready
Release APK on both phones; airplane-mode checks; DeX/HDMI mirroring path.

## Non-negotiables
Overlays keyed to presentation time, never timers. Show flight time, never physical speed (free
play may show image-space px/s, labelled). Every insight opens its clip. Every comparison shows
provenance and role. `MODEL ESTIMATE` on review verdicts; score changes only on confirmation.
`SIMULATED` on synthetic visuals. Haptics: tick, click, heavy only. Test everything on the A55.
