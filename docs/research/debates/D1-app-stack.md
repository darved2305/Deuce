# D1 verdict

**Replace the Expo + Skia draft with a native Kotlin and Jetpack Compose Android app using Media3.** It is the best fit for the video timing, export, audio extraction, upload, and widget requirements. You can build it without Android Studio using Gradle and the Android command-line SDK on one build machine, with GitHub Actions as a free backup.

The tradeoff is native rebuilds after Kotlin changes. That is a better fit here than relying on a cross-platform video canvas whose frame timing and performance on your Exynos phones remain unverified. Keep analysis in the cloud. The phone handles media selection, playback, light audio work, upload, history, and export. **There are no on-device ML models in this stack.**

## What the Expo draft can and cannot do

### Skia video

**Verified:** Skia’s `useVideo` supports Android API 26+, and exposes the current video frame as a Skia image plus playback time, duration, frame rate, rotation, and size. Its controls include seeking in milliseconds, pause, looping, and volume. This is video playback with volume control, not a documented API for extracting audio from a selected movie or receiving a guaranteed frame-presentation callback. [Skia video docs](https://shopify.github.io/react-native-skia/docs/video/)

The SDK 57 bundle pins React Native `0.86.3`, Reanimated `4.5.1`, Worklets `0.10.1`, and `@shopify/react-native-skia` `2.6.2`. That Skia version has a reported Android bug where decoded frames are disposed before the canvas can use them, leaving the canvas black or frozen. The issue records the fix in Skia `2.11.2`; a separate Expo report also describes a React Native 0.86 incompatibility in the bundled Skia `2.6.2`. [Expo SDK 57 bundle map](https://raw.githubusercontent.com/expo/expo/sdk-57/packages/expo/bundledNativeModules.json), [Skia issue and fix release](https://github.com/Shopify/react-native-skia/issues/4000), [Expo SDK 57 Skia issue](https://github.com/expo/expo/issues/50733)

So the bundled version is the wrong version for this draft. Bumping Skia to at least `2.11.2` addresses those reported issues, but **an Expo SDK 57, RN 0.86, and Skia 2.11.2+ combination is not verified here on either team phone**. A separate Skia report describes a decoder crash on Qualcomm hardware; that does not establish a failure on the Exynos S23 FE or A55. [Skia Qualcomm issue](https://github.com/Shopify/react-native-skia/issues/3936)

**UNVERIFIED:** I found no published Skia `useVideo` benchmarks for 1080p60 or 4K on the Exynos 2200 or Exynos 1480. The arithmetic size of three uncompressed RGBA frames is about **24 MiB at 1080p** and **95 MiB at 4K**, per video. That is a memory-sizing estimate, not a measurement of Skia’s or the decoder’s actual memory use.

Even if it renders correctly, `currentTime` and `currentFrame` do not establish that overlays are synchronized to each frame’s presentation timestamp. The docs do not promise that. A shared Skia canvas is an attractive design, but it is not proof of per-frame alignment.

### Expo video, widgets, builds, and native modules

| Area | Verified on October 10, 2026 | Consequence |
|---|---|---|
| `expo-video` | SDK 57 recommends `~57.0.5`. It documents frame-accurate `currentTime` seeking, but its `timeUpdate` event is periodic, not a per-presented-frame callback. Two overlapping Android views using `cover` have a documented display issue; `textureView` is the workaround. | Good general playback. **No documented Android frame-PTS callback** for the overlay requirement. [SDK 57 video docs](https://docs.expo.dev/versions/v57.0.0/sdk/video/) |
| `expo-widgets` | SDK 57’s bundled version is `~57.0.23`; its widget docs describe iOS widgets and Live Activities, not Android home widgets. SDK 58 beta adds an Android implementation. | SDK 57 needs a community Android widget package or a Kotlin module. `react-native-android-widget` `0.22.1` exists, but its compatibility with SDK 57 and RN 0.86 is **UNVERIFIED**. [SDK 57 bundle map](https://raw.githubusercontent.com/expo/expo/sdk-57/packages/expo/bundledNativeModules.json), [SDK 57 widgets docs](https://docs.expo.dev/versions/v57.0.0/sdk/widgets/), [SDK 58 beta notes](https://expo.dev/changelog/sdk-58-beta) |
| EAS Build | Free plan is `$0`, with up to 15 Android and 15 iOS builds per month, one concurrent build, low-priority queue, and a 45-minute build timeout. The timeout is for the build itself; Expo publishes no fixed free-queue wait time. | Free builds meet the budget, but a 24-hour event should not depend on a queue for late native rebuilds. [EAS pricing](https://expo.dev/pricing), [EAS limits](https://docs.expo.dev/build-reference/limitations/) |
| 2026 EAS queue reports | Anecdotal reports vary from long delays to waits of several hours. They are not a forecast or service guarantee. | Plan to have the native binary before the event. [April queue report](https://www.reddit.com/r/expo/comments/1spzch2/android_build_time_in_eas_is_too_long/), [March delay report](https://www.reddit.com/r/expo/comments/1s2mzug/is_something_wrong_with_expo_builds_now/) |
| `eas build --local` | Android local builds require the Android SDK and NDK. Cloud EAS builds supply the build environment, so they do not require Android Studio on the developers’ machines. | EAS cloud satisfies the no-Studio constraint. EAS local does not eliminate SDK setup. [EAS local build docs](https://docs.expo.dev/build-reference/local-builds/) |
| Metro and development builds | After the first development build, JavaScript and asset edits can use Metro and Fast Refresh without a new binary. The phone must reach Metro over LAN or a tunnel. Native modules, native dependencies, and native configuration changes require a new build. | Four people can iterate on JS against a development build. Changes to the Kotlin modules proposed for audio, trimming, or uploads return the team to the build queue. [Expo development workflows](https://docs.expo.dev/develop/development-builds/development-workflows/) |
| Expo Kotlin modules | Expo Modules API supports Kotlin modules, and EAS can compile them in cloud builds. | The draft can ship Kotlin, but it does not make Kotlin changes rebuild-free. [Expo module API](https://docs.expo.dev/modules/module-api/), [Expo native module tutorial](https://docs.expo.dev/modules/config-plugin-and-native-module-tutorial/) |

**Dev-client build time: UNVERIFIED.** The EAS page gives a maximum build duration, not a typical dev-client duration. I would budget the 45-minute build limit plus an unbounded queue, rather than promise an active build time.

### Media and uploads in Expo

`react-native-video` v7 is in beta. Its documented playback events do not give this plan a clear advantage over `expo-video` for per-frame PTS-driven overlays. [Maintainer’s v7 beta announcement](https://github.com/TheWidlarzGroup/react-native-video/discussions/4884)

For SDK 57, the relevant Expo packages include `expo-file-system ~57.0.7`, `expo-audio ~57.0.5`, and `expo-background-task ~57.0.21` in the bundle map. **Version note:** the versioned BackgroundTask page currently displays `~57.0.16`, so the raw bundle map and rendered docs disagree. Its behavioral guidance is consistent: Android uses WorkManager, with a minimum 15-minute interval, and tasks are deferred by system conditions. That is unsuitable as the immediate progress path for a user-selected large video. [SDK 57 bundle map](https://raw.githubusercontent.com/expo/expo/sdk-57/packages/expo/bundledNativeModules.json), [SDK 57 BackgroundTask docs](https://docs.expo.dev/versions/v57.0.0/sdk/background-task/)

Expo FileSystem can upload and report progress. Its legacy Android sessions run in the background, but the API does not provide the app’s resumable chunk-upload protocol. The modern `UploadTask` is also not restored to JavaScript after the app is terminated. For a user-started long transfer, Android’s UIDT job API is designed to start promptly and show progress; WorkManager fits deferred retries. The server still needs to accept resumable chunks or offsets. [SDK 57 FileSystem legacy docs](https://docs.expo.dev/versions/v57.0.0/sdk/filesystem-legacy/), [FileSystem task lifecycle](https://docs.expo.dev/versions/latest/sdk/filesystem/), [Android data-transfer options](https://developer.android.com/develop/background-work/background-tasks/data-transfer-options), [Android UIDT jobs](https://developer.android.com/develop/background-work/background-tasks/uidt)

Expo Audio is an audio playback and recording API. For onset analysis of an existing video file, use a native demux/decode path rather than assuming the audio module extracts that movie’s track. [SDK 57 Audio docs](https://docs.expo.dev/versions/v57.0.0/sdk/audio/)

FFmpegKit was retired. FFmpegKitNext `9.0.0` is a source-only continuation without the prebuilt Maven/npm/CocoaPods binaries that would make it a low-friction drop-in. Use Media3 for Android trim, format conversion, and overlay export instead. [FFmpegKit retirement notice](https://github.com/arthenica/ffmpeg-kit), [FFmpegKitNext README](https://github.com/arthenica/ffmpeg-kit-next), [Media3 transformation docs](https://developer.android.com/media/media3/transformer/transformations)

## Strongest alternatives

| Stack | Strong points | Why I would not choose it for this build |
|---|---|---|
| **Kotlin + Compose** | Direct access to Media3 playback effects and timestamps, Transformer export, UIDT uploads, Android Photo Picker, and native widgets. Gradle can build from the command line without Android Studio. | Native code changes require compiling again. Choose one build captain or serialize builds in CI. [Android command-line build guide](https://developer.android.com/build/building-cmdline) |
| **Flutter** | Flutter `3.47.3` is the current stable line. `video_player 2.14.1` supports Android and iOS, and documents frame-accurate seeking. Impeller is a credible UI renderer. [Flutter install docs](https://docs.flutter.dev/install/manual), [video_player versions](https://pub.dev/packages/video_player/versions), [Impeller docs](https://docs.flutter.dev/perf/impeller) | The standard Dart video API does not provide the Media3 PTS effect path needed here, so Kotlin platform code is still required. Local Android builds need the Android SDK. Codemagic’s free individual plan offers 500 macOS M2 build minutes, but is not a four-person team plan. [Flutter setup guidance](https://docs.flutter.dev/install/troubleshoot), [Codemagic pricing](https://codemagic.io/pricing/) |
| **Lynx** | React-style UI and an experimental native video element. | Lynx’s own docs describe `<video>` as experimental and limited to online video resources. Its default time update is 0.33 seconds, not a per-frame timestamp. That does not match gallery-first offline-friendly video work. [Lynx video docs](https://lynxjs.org/next/api/elements/built-in/video.html) |
| **PWA/TWA** | The browser API `requestVideoFrameCallback()` exposes a frame’s media timestamp and can drive a canvas overlay. | MDN warns the callback can run a vsync late. A TWA still hosts web content from an owned website and requires Digital Asset Links; it does not supply the native widget, UIDT, or Media3 export path. Use it as a visual prototype if useful, not the product app. [MDN frame callback docs](https://developer.mozilla.org/en-US/docs/Web/API/HTMLVideoElement/requestVideoFrameCallback), [Android TWA guide](https://developer.android.com/develop/ui/views/layout/webapps/trusted-web-activities) |

Flutter is a reasonable runner-up if the team already has substantial Dart experience. A PWA has a useful frame callback for a narrow viewer prototype. Neither removes the need for native media integration for the full product.

## Recommended Android stack

The strongest part of the native option is that Media3 exposes the timing path the UI needs. `ExoPlayer.setVideoEffects` applies video effects to each frame; `VideoFrameMetadataListener` exposes presentation timestamps. Media3 Transformer supports trim, transmuxing, custom effects, and previewing effects in ExoPlayer. This supports building overlays keyed to frame time and reusing rendering logic for vertical highlight export. The effect APIs are marked `@UnstableApi`, so pin the version and keep that code isolated. **Perceived synchronization and speed on the two Exynos phones remain unverified until measured on-device.** [ExoPlayer video effects](https://developer.android.com/reference/androidx/media3/exoplayer/ExoPlayer), [frame metadata listener](https://developer.android.com/reference/androidx/media3/exoplayer/video/VideoFrameMetadataListener), [Media3 Transformer](https://developer.android.com/media/media3/transformer), [OverlayEffect API](https://developer.android.com/reference/androidx/media3/effect/OverlayEffect)

| Component | Exact version or API | Use |
|---|---|---|
| Kotlin Gradle plugin and Compose compiler plugin | `2.4.20` | Pin to the published full compatibility matrix with AGP `9.3.1`. Kotlin `2.4.21` was released October 8, but the current compatibility table explicitly lists `2.4.20`. [Kotlin compatibility table](https://kotlinlang.org/docs/gradle-configure-project.html), [Kotlin releases](https://kotlinlang.org/docs/releases.html), [Compose compiler plugin guide](https://kotlinlang.org/docs/compose-compiler-migration-guide.html) |
| Android Gradle Plugin / Gradle / JDK | `9.3.1` / `9.5.0` / `17` | AGP 9.3 uses Gradle 9.5 and JDK 17. The Kotlin compatibility table includes AGP through 9.3.1. [AGP 9.3 release notes](https://developer.android.com/build/releases/agp-9-3-0-release-notes), [AGP and Gradle versions](https://developer.android.com/build/releases/about-agp) |
| Jetpack Compose | BOM `2026.09.00`; UI/Foundation/Runtime `1.12.1`; Material 3 `1.4.0` | Premium app screens, custom court diagrams, transitions, controls, and history views. [Compose BOM](https://developer.android.com/develop/ui/compose/bom), [Compose release table](https://developer.android.com/jetpack/androidx/releases/compose) |
| Media3 | `1.11.1` for `media3-exoplayer`, `media3-ui`, `media3-effect`, `media3-transformer` | Gallery playback, frame-timed overlays, scrubbing, trimming/transmuxing, and burned-in highlight export. [Media3 release notes](https://developer.android.com/jetpack/androidx/releases/media3), [Transformer setup](https://developer.android.com/media/media3/transformer/getting-started) |
| Room | `2.8.5` | Persist match records, processing state, upload offsets, and history for comparisons across sessions. [Room releases](https://developer.android.com/jetpack/androidx/releases/room) |
| Glance AppWidget | `1.2.0` | Android home-screen widget. Use the stable release, not `1.3.0-alpha02`. [Glance releases](https://developer.android.com/jetpack/androidx/releases/glance) |
| Android platform APIs | Photo Picker, `MediaExtractor`, `MediaCodec`, UIDT, notifications, App Links, `ACTION_SEND` | Local video selection, track/metadata access, PCM onset calculation, long upload jobs, reminders, deep links, and sharing. [Photo Picker](https://developer.android.com/training/data-storage/shared/photopicker), [MediaExtractor](https://developer.android.com/reference/android/media/MediaExtractor), [UIDT](https://developer.android.com/develop/background-work/background-tasks/uidt) |

The selected Kotlin and AndroidX libraries are open source under Apache 2.0. [Kotlin license](https://github.com/JetBrains/kotlin/blob/master/license/README.md), [AndroidX license](https://github.com/androidx/androidx)

For overlays, draw the court lines, ball trail, player boxes, bounces, and uncertainty shapes inside a Media3 frame effect keyed by presentation time. Keep Compose for playback controls, cards, and the surrounding interface. Use Media3 Transformer’s overlay/effect path for export.

For comparison clips, use a shared scrubber and seek both players to the selected contact time. **Dual decode performance on the A55 and S23 FE is UNVERIFIED.** The safe demo fallback is one playing clip with the reference clip frozen at the contact frame.

Use the Android Photo Picker rather than requesting broad photo-library access. Use UIDT on Android 14+ for the visible, user-started upload and WorkManager for deferred retry. Store upload state locally and have the API support chunked resume. Compute an onset envelope from the selected file’s audio track in native code. The app’s local history can still be browsed during a network outage, but cloud analysis cannot finish offline. 

## Zero Android Studio development loop

1. **Assign one build captain.** On one Mac, install JDK 17, Android command-line SDK tools, platform tools, and the required SDK/build-tools version. Do not install Android Studio. The other three developers can use their preferred editors and agents; they do not need to compile locally.
2. **Build and install the shell first.** The captain creates the pinned Gradle project, runs `./gradlew assembleDebug`, then installs it on a connected phone using `adb`. Keep this as the first build task so dependency or toolchain problems surface early. Android documents Gradle wrapper builds from the terminal. [Command-line build guide](https://developer.android.com/build/building-cmdline)
3. **Split implementation work.** One person owns the Media3 player and frame effect; one owns Compose screens and court visualization; one owns upload jobs and media extraction; the captain integrates and runs the single active build. After a shared-screen API is stable, the other developers can continue in separate branches without running parallel heavy builds.
4. **Use one device loop.** The captain installs each integrated debug build over USB, checks logs with `adb logcat`, and records screenshots or short clips on both Exynos phones. There is no Metro or JS Fast Refresh; Kotlin edits require a new build. Compose Preview is not part of this workflow.
5. **Add GitHub Actions as the backup builder.** The workflow runs `./gradlew assembleDebug` and uploads the APK artifact. Standard runners are free for public repositories; GitHub Free includes 2,000 Actions minutes per month for private repositories. [GitHub Actions billing](https://docs.github.com/en/billing/concepts/product-billing/github-actions)
6. **Use the APK for the demo.** Install it directly on the two phones and include a local sample clip/result in the app so the demo opens without a QR code or network connection.

**Time estimates, not measurements:** command-line SDK setup on the build machine, **20–60 minutes**; first clean build, **5–15 minutes**; warm incremental build, **1–3 minutes**. These are planning ranges only. **Actual build times on your machines and this project are UNVERIFIED.** If no one may install even the command-line SDK, GitHub Actions can build the APK remotely, but each edit-to-phone cycle will take longer.

## Three biggest risks

1. **Exynos video performance:** there are no verified 1080p60, 4K, or two-player decode benchmarks for these phones. Prototype the frame effect on both devices early. Keep 1080p preview as the initial target and freeze the comparison clip if dual playback stutters.
2. **Effect timing and export:** Media3’s effect interfaces are unstable APIs, and a timestamp callback alone does not prove the overlay appears on the same display frame. Pin Media3 `1.11.1`, use the same time-indexed effect for playback and export, and validate slow motion, scrubbing, and exported frames on-device.
3. **Large uploads on unreliable internet:** platform background execution does not create a resumable server protocol. Persist job state, use visible UIDT progress, and implement chunked upload resume in the API. Keep a cached demo result so the on-site experience does not depend on finishing cloud analysis.

## Recommendation

Use **Kotlin 2.4.20 + Compose BOM 2026.09.00 + Media3 1.11.1**, built by Gradle without Android Studio. Prototype the Media3 frame effect on both Exynos phones before polishing the rest of the UI.

## Open questions only the team can answer

- Does “no Android Studio” allow one build captain to install the Android command-line SDK and JDK?
- Must both clips play continuously together, or is a frozen reference frame at the contact point acceptable if dual decode is poor?
- For the venue demo, must the live clip complete cloud analysis without reliable internet, or can it show a queued upload and a cached example result?