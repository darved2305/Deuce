# Track 02: Mobile app stack

**Verdict:** Build the player app with **Expo SDK 57, React Native 0.86.3, React 19.2.3, TypeScript, and a custom development build**. Use `expo-video` for replay, `expo-image-picker` for gallery import, `expo-sqlite` for saved analysis, and Reanimated plus SVG for the interface and trajectory overlays.

For the 24-hour demo, keep full video analysis on the server. Make the installed app useful without venue internet by bundling a properly licensed sample video and its matching analysis data. A fully offline analysis of arbitrary videos across racket sports is **UNVERIFIED**; the phone runtimes alone do not establish that capability.

Flutter is a credible alternative if the team already knows Dart. Native SwiftUI and Compose offer the most direct media and ML access, but require two app implementations. I would not start with KMP, Lynx, or Valdi for this event.

**Verification boundary:** I checked current official documentation, package registries, and release pages live on 2026-10-10. Context7 did not initialize in this environment, so I used those sources directly. I did not run a device benchmark. Claims about 60 fps on your eventual phones and full offline racket analysis are **UNVERIFIED**.

## Framework comparison

| Stack | Current versions checked | Fit for this event |
|---|---|---|
| **Expo / React Native** | Expo `57.0.27`, React Native `0.86.3`, React `19.2.3` | Best balance of a single app, TypeScript agent workflow, native extensions, and a quick local device install. SDK 57 is stable; SDK 58 remains on Expo’s `next` tag. [Expo npm versions](https://www.npmjs.com/package/expo?activeTab=versions), [SDK reference](https://docs.expo.dev/versions/latest/), [SDK 58 beta notice](https://expo.dev/changelog/sdk-58-beta) |
| **Flutter** | Flutter `3.47.0`; `video_player 2.14.1`; `tflite_flutter 0.12.1` | Strong single-codebase UI and drawing via `CustomPainter`. Its video plugin does not document a frame-accurate seeking guarantee. The Flutter TFLite plugin is on LiteRT 1.4.0 per its changelog, behind the current native LiteRT 2.3.0 release. Choose it if your team already works in Dart. [Flutter releases](https://docs.flutter.dev/release/release-notes), [video_player](https://pub.dev/packages/video_player/versions), [tflite_flutter](https://pub.dev/packages/tflite_flutter), [LiteRT releases](https://developers.google.com/edge/litert/releases/litert_oct) |
| **Native SwiftUI + Kotlin/Compose** | Xcode 27 / Swift 6.4; Compose `1.12.1`; Kotlin `2.4.21`; Media3 `1.11.1` | Highest access to native video and ML APIs. AVFoundation and Media3 both expose exact seeking controls. This means two UI codebases, two build paths, and more integration work for a four-person demo team. [Xcode release notes](https://developer.apple.com/documentation/Xcode-Release-Notes/xcode-27-release-notes), [Compose releases](https://developer.android.com/jetpack/androidx/releases/compose), [Kotlin releases](https://kotlinlang.org/docs/releases.html), [Media3](https://developer.android.com/jetpack/androidx/releases/media3), [AVPlayer seeking](https://developer.apple.com/documentation/avfoundation/controlling-the-transport-behavior-of-a-player) |
| **Kotlin Multiplatform / Compose Multiplatform** | Compose Multiplatform `1.12.1`; Kotlin `2.4.21` | Stable on Android and iOS, with a shared UI. Camera, video, permissions, and ML still need platform-specific work. That limits the time saved in a 24-hour project. [Compatibility and versions](https://kotlinlang.org/docs/multiplatform/compose-compatibility-and-versioning.html), [Compose Multiplatform 1.12.1](https://kotlinlang.org/docs/multiplatform/whats-new-compose-112.html) |
| **Lynx** | Lynx `4.1.0`; `@lynx-js/react 0.126.0` | Interesting agent documentation, but the prebuilt iOS Explorer is simulator-focused; a real iPhone path requires building from source. The needed camera, video-overlay, and on-device ML combination is **UNVERIFIED**. [Lynx versions](https://lynxjs.org/versions), [quick start](https://lynxjs.org/guide/start/quick-start.html), [AI tooling](https://lynxjs.org/ai/) |
| **Valdi** | `beta-0.2.0` | Too early for this deadline. It is explicitly beta and uses a different component and lifecycle model from React, despite using TypeScript/TSX. [Valdi releases](https://github.com/Snapchat/Valdi/releases), [Valdi AI rules](https://github.com/Snapchat/Valdi/blob/main/.cursorrules) |

There is no public head-to-head benchmark for how Claude Code or Codex perform on the same mobile task across these stacks. My Expo recommendation is an **inference** from your agent-heavy workflow: it keeps the app in TypeScript, has an Expo MCP integration for current docs and package compatibility, and has `agent-device` support for Codex and Claude Code. If your four developers already have deep Flutter or native experience, that could change the choice. [Expo MCP](https://docs.expo.dev/mcp/), [agent-device and Expo](https://docs.expo.dev/agents/agent-device/)

## Expo baseline and package choices

SDK 57 pairs React Native 0.86 with React 19.2.3. It requires Node 22.13 or later, supports Android 7+ and iOS 16.4+, and specifies Xcode 26.4+. SDK 55 and later use the New Architecture. Do not manually override React Native to its newer standalone release; use the version paired with SDK 57. [Expo SDK reference](https://docs.expo.dev/versions/latest/), [SDK 55 changelog](https://expo.dev/changelog/sdk-55)

SDK 57 plus Xcode 27 needs attention: Expo’s SDK 57 changelog says iOS 27 builds need the scene-based lifecycle. SDK 57.0.23 and later provide an opt-in through `expo-build-properties`. For a 24-hour build, use Xcode 26.4 if available, or confirm this Xcode 27 setting in an early device build. [Expo SDK 57 changelog](https://expo.dev/changelog/sdk-57)

The SDK 57 branch’s package map gives these compatible versions. Use `npx expo install` so Expo selects the matching versions, then commit the package lockfile. Expo packages are MIT licensed; the listed React Native community packages are also MIT licensed. [SDK 57 bundled package map](https://raw.githubusercontent.com/expo/expo/sdk-57/packages/expo/bundledNativeModules.json), [Expo npm metadata](https://www.npmjs.com/package/expo?activeTab=versions)

| Package | SDK 57 version | Role |
|---|---:|---|
| `expo` | `57.0.27` | Stable Expo SDK |
| `expo-router` | `~57.0.25` | File-based navigation |
| `expo-dev-client` | `~57.0.19` | Your app’s native development build |
| `expo-image-picker` | `~57.0.20` | Select a match video from Photos or Gallery |
| `expo-video` | `~57.0.5` | Native video playback and seeking |
| `react-native-svg` | `15.15.4` | Court drawing and trajectory paths |
| `react-native-reanimated` | `4.5.1` | Smooth UI animation |
| `react-native-worklets` | `0.10.1` | Reanimated and native worklet support |
| `expo-sqlite` | `~57.0.4` | Persistent local match and analysis metadata |
| `expo-file-system` | `~57.0.7` | Keep selected or demo media in app storage |
| `expo-haptics` | `~57.0.3` | iOS and Android tactile feedback |
| `expo-widgets` | `~57.0.23`, optional | iOS widgets and Live Activities |

`expo-video` is the right first choice for playback. It supports exact requested-time seeking by default through `currentTime`; its docs also warn that tighter seek tolerance can slow repeated scrubbing. It does not promise 60 fps overlay performance, so that claim must be tested on your actual phones. Set a `timeUpdateEventInterval` if overlay state follows playback; its default of zero emits no time updates. [SDK 57 video docs](https://docs.expo.dev/versions/v57.0.0/sdk/video/)

Use SVG paths for the first court and trajectory overlays. Expo’s SDK 57 package map pins Skia to `2.6.2`; an Expo issue records an Android `makeImageFromView` failure with React Native 0.86 that was fixed in Skia 2.11.1. A normal Skia canvas may still work, but its compatibility with your exact SDK 57 build is **UNVERIFIED**. If you want Skia, pin a newer compatible version and test Android before integrating it into the main demo. [SDK 57 Skia docs](https://docs.expo.dev/versions/v57.0.0/sdk/skia/), [Expo issue #50733](https://github.com/expo/expo/issues/50733)

`expo-av` is deprecated and no longer patched; Expo replaced its video and audio APIs with `expo-video` and `expo-audio` in SDK 55. `react-native-video` is another option, with `6.19.3` as the stable version checked, but it adds a separate native playback dependency without a demonstrated need here. [Expo SDK 55 changelog](https://expo.dev/changelog/sdk-55), [react-native-video v6 installation](https://github.com/TheWidlarzGroup/react-native-video/blob/master/docs/versioned_docs/version-6.x/installation.md)

## Native camera and ML options

These packages are real options for later device inference, but I would not make any of them a blocker for the first player build.

| Package | Current version and license | What it gives you | Cost or risk here |
|---|---|---|---|
| `react-native-vision-camera` | `5.2.3`, MIT | Native camera using AVFoundation and CameraX. Its v5 live frame API is called Frame Output; older versions called it Frame Processors. It depends on Nitro modules. [Docs](https://visioncamera.margelo.com/docs), [Frame Output](https://visioncamera.margelo.com/docs/frame-output), [npm](https://www.npmjs.com/package/react-native-vision-camera?activeTab=versions) | Useful for secondary live capture. It does not solve reading frames from a gallery video for post-game processing, and needs a custom development build. Exact SDK 57 compatibility is **UNVERIFIED**. |
| `react-native-fast-tflite` | `3.0.1`, MIT; Nitro `0.37.1` | Runs `.tflite` models and supports platform delegates. Version 3 migrated to Nitro modules. [Fast TFLite releases](https://github.com/margelo/react-native-fast-tflite/releases), [Nitro](https://github.com/margelo/nitro/releases) | Requires native build integration. Only use after testing the chosen model, its asset loading, and delegates on an iPhone and Android phone. |
| `react-native-executorch` | `0.10.5`, MIT | Runs `.pte` models offline in a React Native app. Requires New Architecture, iOS 17+, Android 13+, and a development build. [npm](https://www.npmjs.com/package/react-native-executorch), [camera integration](https://docs.swmansion.com/react-native-executorch/docs/extensions/camera-integration) | Its OS minimums exclude some phones supported by the Expo SDK. Model-specific performance and suitability for racket tracking are **UNVERIFIED**. |
| `onnxruntime-react-native` | `1.24.3`, MIT | ONNX Runtime for React Native. [npm versions](https://www.npmjs.com/package/onnxruntime-react-native?activeTab=versions), [Microsoft docs](https://onnxruntime.ai/docs/get-started/with-javascript/react-native.html) | The published React Native package was last updated seven months ago at the time checked. Compatibility with Expo 57 and your chosen model is **UNVERIFIED**. |
| `@op-engineering/op-sqlite` | `18.2.5`, MIT | A broader SQLite package with optional extensions. [npm](https://www.npmjs.com/package/%40op-engineering/op-sqlite?activeTab=code) | More native dependency surface than needed for local match metadata. Start with `expo-sqlite`. |

**Model file, size, license, and speed:** none is selected for the recommended mobile app. That is deliberate: its first version renders analysis results from a server or local fixture rather than running CV on-device. The exact model checkpoint, file size, license, inference time, frame rate, and battery use are therefore **UNVERIFIED**. A runtime license does not establish the license of the model weights.

## Offline behavior and shared analysis logic

**Recommended split, as an architectural inference:**

- **Phone:** import local video, play it, render timestamps/events/player paths, store match metadata and analysis data locally, and retry uploads when connectivity returns.
- **Server:** run the full Python vision pipeline and return a versioned analysis record.
- **Demo fallback:** bundle a permissively licensed video and its matching analysis JSON in the installed app. This lets the replay, court overlay, stats, and event navigation work without Wi-Fi. A judge-selected video cannot receive fresh server analysis without a working connection.

`expo-video` has a persistent cache that can play cached video offline, but its docs say the system may evict that cache. Do not rely on it for the demo’s required clip; bundle or explicitly copy the clip into app storage. `expo-sqlite` persists data across restarts. [Video cache behavior](https://docs.expo.dev/versions/v57.0.0/sdk/video/), [SQLite](https://docs.expo.dev/versions/v57.0.0/sdk/sqlite/)

To share logic between phone and server, share the **contract**, not the full CV implementation. Use an OpenAPI or JSON Schema contract for a `MatchAnalysis` record. It can include sport, timestamps in milliseconds, normalized court coordinates, player paths, detected events, confidence, and media dimensions. Generate the TypeScript client types from it. Keep video decoding, tracking, and inference in the Python service. Put only small display transforms in TypeScript. If you later choose a model supported on both server and phone, compare ONNX, TFLite, or ExecuTorch for that particular model and validate the same input/output fixtures on both runtimes. Sharing a C++ or Rust library now would add FFI and build work without helping the primary replay flow.

## Free plans and installing on real phones

The Expo EAS Free plan is `$0`. It currently includes 15 Android and 15 iOS cloud builds per month, one concurrent build, a low-priority queue, a 45-minute build timeout, and 60 minutes of CI/CD Workflows. EAS publishes no numeric free-queue wait time or service guarantee, so queue time is **UNVERIFIED**. Local builds avoid that queue. [Expo pricing](https://expo.dev/pricing), [EAS limitations](https://docs.expo.dev/build-reference/limitations/)

For iOS, a free Apple Account with Xcode can install locally built apps on a personal device. Apple’s Personal Team currently allows 10 App IDs, 3 devices, and 3 installed apps per device; profiles expire after 7 days, after which the app must be reinstalled. TestFlight requires the paid Apple Developer Program, listed at `$99 USD/year`, so do not make it part of this plan. Expo says local Xcode builds are the route for installing an iPhone development build without a paid Apple Developer membership; cloud iOS device builds require paid membership credentials. [Apple account limits](https://developer.apple.com/help/account/basics/about-your-developer-account), [Apple Developer Program](https://developer.apple.com/programs/), [Expo local development builds](https://docs.expo.dev/develop/development-builds/introduction/), [Expo iOS device builds](https://docs.expo.dev/tutorial/eas/ios-development-build-for-devices/)

Android APKs can be installed directly with `adb install`; this does not require Google Play publishing. [Android ADB](https://developer.android.com/tools/adb)

### Fastest local first install

Start with a local development build. Expo Go is a fixed runtime and cannot load arbitrary native dependencies or your custom native modules. A development build contains your app’s native code. [Expo development build FAQ](https://docs.expo.dev/develop/development-builds/faq/)

```bash
npx create-expo-app@latest rally-player --template default@sdk-57
cd rally-player
npx expo install expo-dev-client expo-video expo-image-picker expo-sqlite expo-file-system expo-haptics react-native-svg react-native-reanimated react-native-worklets

# Android phone with USB debugging enabled
npx expo run:android --device

# iPhone connected to a Mac with Xcode, Personal Team signing, and Developer Mode
npx expo run:ios --device
```

The `run` commands generate native projects when needed, compile and install the app, then start Metro. JavaScript-only edits after that use Metro and do not require another native rebuild. Native package or app configuration changes need another native build. [Expo local app development](https://docs.expo.dev/guides/local-app-development/)

For the final demo, make and install a signed build with the JavaScript bundle and sample assets embedded, then verify it launches with Wi-Fi off. A development build that depends on Metro is not a reliable venue demo when the network is uncertain. Expo documents `--variant release` for Android and `--configuration Release` for iOS, but warns that these local production outputs are not signed for app-store submission; configure local Android signing and Xcode Personal Team signing, then rehearse the resulting installs. Native build duration on your machines is **UNVERIFIED**. [Expo local build options](https://docs.expo.dev/guides/local-app-development/)

## Agent tooling

| Tool | Current checked version/status | Use |
|---|---|---|
| `agent-device` | npm `0.21.24`, MIT | Best primary local agent loop. It works with Codex and Claude Code, supports physical Android/iOS plus emulators and simulators, and can inspect UI state, screenshots, logs, and performance. Use one main device-control tool. [Expo guide](https://docs.expo.dev/agents/agent-device/), [npm](https://www.npmjs.com/package/agent-device) |
| Expo MCP | Official remote server at `https://mcp.expo.dev/mcp`; Expo account required | Reads Expo documentation and helps install SDK-compatible packages. Local screenshots and automation are limited to iOS simulators, not physical iPhones. Some MCP capabilities have paid-plan requirements, so stay with free documented tools. [Expo MCP docs](https://docs.expo.dev/mcp/) |
| Maestro | CLI `2.11.0`, Apache-2.0 | Free local YAML UI flows. Supports physical Android; iOS automation is simulator-only. Do not rely on Maestro for testing the physical iPhone. [Releases](https://github.com/mobile-dev-inc/Maestro/releases), [install](https://docs.maestro.dev/maestro-cli/how-to-install-maestro-cli) |
| Mobile MCP | npm `@mobilenext/mobile-mcp 1.0.8`, Apache-2.0 | Optional fallback for device control across iOS and Android. It overlaps with `agent-device`, so do not install both unless a needed function is missing. [npm](https://www.npmjs.com/package/%40mobilenext/mobile-mcp) |
| MobileBuildMCP | `2.7.1`, MIT; formerly XcodeBuildMCP | Mac-only aid for inspecting iOS builds, logs, and native-module issues. Use when Xcode errors need focused debugging. [Releases](https://github.com/getsentry/MobileBuildMCP/releases) |

Expo MCP is tooling for coding agents. It is separate from the product’s own developer API or MCP server.

## Starter repository layout

```text
apps/
  player/                    # Expo SDK 57 TypeScript app
    app/                      # Expo Router screens
    src/features/replay/
    src/components/court/
    assets/demo/
      match.mp4
      LICENSE.md

packages/
  match-contract/
    openapi.yaml
    match-analysis.schema.json
    fixtures/demo-analysis.json
  player-core/
    courtCoordinates.ts
    eventTimeline.ts

services/
  analysis-api/               # Python API and job endpoints
  vision/                     # Python video/CV pipeline
    models/manifest.yaml       # Populate only after choosing licensed weights
```

Keep `ios/` and `android/` generated through Expo CNG and config plugins. Avoid hand-editing generated native files as the default workflow.

## Recommendation

Choose **Expo SDK 57** and keep the first phone release focused on gallery import, smooth replay, timestamped analysis overlays, local storage, and an offline bundled sample. Use a Python API for full video analysis. Build locally on a Mac for free iPhone installation and install the Android APK directly.

## Three biggest risks

1. **iOS signing and expiry:** a free Personal Team build needs a Mac and Xcode, supports only a small device set, and expires after 7 days.
2. **Video and overlay performance:** exact seeking is supported, but 60 fps trajectory drawing and smooth scrubbing are not verified across the team’s unknown phones and video codecs.
3. **Offline analysis scope:** the proposed phone can replay and show cached analysis offline; fresh full CV analysis of arbitrary videos without a server is not verified.

## Open questions only your team can answer

- Does someone have a Mac with Xcode 26.4, or Xcode 27 and time to configure SDK 57 scene support, plus an available iPhone and Android phone?
- Has the team shipped more work in TypeScript/React Native, Flutter/Dart, or native Swift and Kotlin?
- Does “offline” require fresh analysis of any judge-selected video, or is an offline, pre-analyzed public sample acceptable for the demo?
- What are the team phones’ models and OS versions, and are you willing to raise the minimum to iOS 17 and Android 13 if an on-device ML library requires it?