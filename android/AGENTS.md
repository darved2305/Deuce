# android/ · Person 2 (@darved2305) · Android app
Adds to the root AGENTS.md. Brief: `docs/lanes/person-2.md`. You own `android/` including Gradle files.

## Stack (pinned in gradle/libs.versions.toml)
Kotlin 2.4.20 · AGP 9.3.x with Gradle 9.5.0 (AGP 9.4 needs Gradle 9.6: bump together) · JDK 17 · Compose BOM 2026.09.00 ·
Media3 1.11.1 (`exoplayer`, `ui`, `effect`, `transformer`, all the same version) · Room 2.8.5 (KSP) · Glance 1.2.0 ·
WorkManager 2.11.2 · supabase-kt 3.8.0 (+ Ktor engine with WebSockets) · minSdk 26.

## Setup without Android Studio
`brew tap android/tap && brew install android-cli` → `android sdk install platform-tools platforms/android-36` (check exact ids with `android sdk list --all`) → `android skills add --all --agent claude-code` (official Android skills) · JDK 17.

## Commands (run from android/)
- Checks: `./gradlew assembleDebug lintDebug testDebugUnitTest`
- Phone loop: `adb install -r app/build/outputs/apk/debug/app-debug.apk` · `adb logcat`
- Upload job test: `adb shell cmd jobscheduler run -f <package> <jobId>`

## Rules (verified in Android docs)
- AGP 9 has built-in Kotlin: do NOT apply `org.jetbrains.kotlin.android`; use KSP (not kapt) for Room; `kotlin { compilerOptions {} }`.
- Media3 effects are `@UnstableApi`: `@OptIn(UnstableApi::class)`; call `setVideoEffects` before `prepare()`; overlays via `OverlayEffect` / `CanvasOverlay.onDraw(canvas, presentationTimeUs)` keyed to presentation time, never timers. `MetadataRetriever` moved to `media3.inspector`.
- Transformer: one application thread; `cancel()` when the user leaves export. Verify the overlay lines up identically in preview and export.
- Uploads: user-initiated data transfer job (API 34+, `RUN_USER_INITIATED_JOBS`, `setUserInitiated(true)`, `setNotification` in `onStartJob`); WorkManager below 34; persist uploadId/parts/ETags in Room.
- The app holds only the Supabase URL + publishable key. Never a secret key.
- Realtime: `channel("analysis:<id>") { isPrivate = true }`, event `analysis_seq` is a hint; always fetch the events cursor.
- Show flight time, never km/h. `MODEL ESTIMATE` on verdicts. Nothing synthetic on screen.

## UI
`docs/ui/` holds the first UI concept (8 screens, design tokens). It is a starting point, **not
final**: you own the final design and may change anything (record big changes in DECISIONS.md).
What stays fixed are the product rules: real footage under every overlay, one-tap evidence,
denominators, `MODEL ESTIMATE` on verdicts, `SIMULATED` on synthetic visuals, almost no text.

## Proof
Screen recording on the S23 FE or A55 with real footage (and the real backend for live features).

## Docs
Context7 `/websites/developer_android_media_media3`, `/androidx/media`, `/supabase-community/supabase-kt` · `android docs search "<q>"` · https://kotlinlang.org/llms.txt
