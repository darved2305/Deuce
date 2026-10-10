# Elevate · Android (Person 2)

Kotlin 2.4.20 · AGP 9.3.3 (built-in Kotlin) · Gradle 9.5.0 · JDK 17 · Compose BOM 2026.09.00 ·
Media3 1.11.1. One `:app` module, application ID `com.elevate.app`, minSdk 26, targetSdk 36,
compileSdk 37 (the pinned Compose UI 1.12.1 and core-ktx 1.19.1 require compiling against 37).

## Build on a new machine (Windows, no Android Studio)
1. JDK 17 (e.g. Temurin) → `JAVA_HOME`.
2. Android command-line tools → `%LOCALAPPDATA%\Android\Sdk\cmdline-tools\latest`, then
   `android sdk install platform-tools platforms/android-36 platforms/android-37.0 build-tools/36.1.0`.
3. `android/local.properties`: `sdk.dir=C\:/Users/<you>/AppData/Local/Android/Sdk` (colon escaped).
4. From `android/`: `./gradlew assembleDebug lintDebug testDebugUnitTest`.
5. Phone: Developer options → USB debugging on → `adb install -r app/build/outputs/apk/debug/app-debug.apk`
   → `adb shell am start -n com.elevate.app/.MainActivity`.

## Layout
| Path | Job |
|---|---|
| `playback/PlayerController.kt` | the one ExoPlayer; installs the overlay effect before `prepare()` |
| `playback/TrailOverlay.kt` | Media3 `CanvasOverlay`: draws labels for the frame's `presentationTimeUs` only |
| `playback/TimeMapping.kt` | pure time maths (µs/ms, clip offset, frame matching from the labels' own spacing) |
| `playback/VideoInspector.kt` | reads name, size, dimensions, rotation, duration and sha256 from the picked file |
| `domain/` | `TrailTrack`, `Provenance`, `AnnotationState`, `MatchRepository` |
| `data/fixture/` | `TrailLabelsParser` (strict) + `FixtureSource` (bundled real labels in `assets/fixtures`) |
| `data/api/ApiSource.kt` | Phase 2 boundary; reports `NotConnected` |
| `ui/` | `ReplayViewModel`, Import and Replay screens, theme |
| `tools/labeller/` | frame extraction with verified PTS + browser labeller for real hand labels |

Debug builds stamp each frame's own `presentationTimeUs` into the frame (bottom right), drawn by
the same overlay as the trail, so a screenshot proves which timestamp a drawn point belongs to.
Status, tests and device evidence: `PHASE1_STATUS.md`. Footage: `proof/video-manifest.md`.
