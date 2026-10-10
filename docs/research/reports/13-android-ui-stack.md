# Verdict

Choose **C: native Kotlin + Jetpack Compose for Android**. Keep video decode, audio extraction, inference, and timestamp handling on the native side. Compose has enough visual range for the premium UI you want, while Media3 and ONNX Runtime fit the video and analysis work more directly than a JS or Dart bridge.

**Offline analysis is technically feasible, but its end-to-end speed and accuracy on your demo phone are UNVERIFIED.** The app can import, decode, run a bundled model, draw overlays, and export without a network connection. That does not prove a whole-match analysis will finish quickly or reliably on a mid-range phone.

## Recommended Android stack

| Layer | Recommendation | Current version and evidence |
|---|---|---|
| UI | Kotlin + Jetpack Compose, with custom design tokens, Canvas overlays, and Compose animations | Compose BOM `2026.09.00`; Compose UI/Foundation/Animation `1.12.1`; Material 3 `1.4.0`. These are stable versions listed in the [Compose BOM](https://developer.android.com/develop/ui/compose/bom) and [Android release updates](https://developer.android.com/latest-updates). AndroidX is Apache-2.0 licensed. |
| Gallery import | Android Photo Picker, limited to videos | Use `ActivityResultContracts.PickVisualMedia` with video filtering. Android documents it as a permissionless media-selection path: [Photo Picker](https://developer.android.com/training/data-storage/shared/photo-picker), [API reference](https://developer.android.com/reference/androidx/activity/result/contract/ActivityResultContracts.PickVisualMedia). |
| Playback and export | Media3 ExoPlayer for playback; Media3 Transformer and Effect for export | `1.11.1` stable, released September 10, 2026. `OverlayEffect` can burn graphics into the output video, but is marked `@UnstableApi`: [Media3 release notes](https://developer.android.com/jetpack/androidx/releases/media3), [editing guide](https://developer.android.com/media/implement/editing-app), [OverlayEffect API](https://developer.android.com/reference/androidx/media3/effect/OverlayEffect). AndroidX Media is Apache-2.0 licensed. |
| Frame timing | Media3 `VideoFrameMetadataListener`; use presentation timestamps as the overlay clock | The callback exposes frame presentation time before rendering: [API reference](https://developer.android.com/reference/androidx/media3/exoplayer/video/VideoFrameMetadataListener). `SeekParameters.EXACT` supports exact seeking, though it can be slower than seeking to a nearby sync point: [API reference](https://developer.android.com/reference/androidx/media3/exoplayer/SeekParameters). |
| Decode for analysis | Android `MediaExtractor` and `MediaCodec`, called from Kotlin | Keep video frames and audio PCM in the native analysis path. Do not send frame arrays through a UI bridge. |
| On-device ML | ONNX Runtime Android with XNNPACK, CPU-first | `com.microsoft.onnxruntime:onnxruntime-android:1.31.0`, released October 9. The Android package includes XNNPACK; its operators and performance depend on the model, so benchmark the actual model: [release](https://github.com/microsoft/onnxruntime/releases), [Maven artifact and license](https://central.sonatype.com/artifact/com.microsoft.onnxruntime/onnxruntime-android), [Android execution providers](https://onnxruntime.ai/docs/build/eps.html), [XNNPACK guidance](https://onnxruntime.ai/docs/execution-providers/Xnnpack-ExecutionProvider.html). The runtime is MIT licensed. |
| Optional live capture | CameraX | `1.6.2` stable per [Android release updates](https://developer.android.com/latest-updates). Defer it while post-game import is the main flow. |
| Optional home widget | Glance | `1.2.0` stable; `1.3.0` is still alpha. Defer widgets until the match flow works: [Glance release notes](https://developer.android.com/jetpack/androidx/releases/glance). |
| Shaders | AGSL `RuntimeShader` for optional polish | Available from Android API 33. Treat it as a visual enhancement with a fallback, not a requirement: [RuntimeShader API](https://developer.android.com/reference/android/graphics/RuntimeShader), [AGSL guide](https://developer.android.com/develop/ui/views/graphics/agsl). |

For future iOS support, preserve the match and event data schema and keep model artifacts in ONNX format. Build a thin SwiftUI viewer later. Start with a Kotlin analysis layer; move algorithms into shared C++ only if profiling or an actual iOS implementation justifies the extra build and FFI work. Rust adds another language boundary without a clear advantage for this Android-first sprint. ONNX Runtime publishes Android and iOS installation paths: [install guide](https://onnxruntime.ai/docs/install/).

### Model and runtime limits

The runtime version does not establish that a particular sport model will work. As a concrete pretrained candidate, Ultralytics now lists `yolo26n.pt` and `yolo26n-seg.pt`; its YOLO26 page reports **2.4M parameters and 5.5 GFLOPs** for the detection `yolo26n` variant. The published CPU ONNX figure is **38.9 ± 0.7 ms per 640-pixel image**. That is a CPU benchmark, not an Android-phone measurement. The page does not give the downloadable model file size, so the `.pt` and exported `.onnx` sizes are **UNVERIFIED** until you export and measure them. [YOLO26 model details and benchmarks](https://docs.ultralytics.com/models/yolo26).

The candidate weights are offered under **AGPL-3.0 or Enterprise terms**. That is separate from ONNX Runtime’s MIT license. Review the model license before distributing the app. The published benchmark also does not prove that the model tracks a small fast ball or shuttlecock in phone-shot match footage. [YOLO26 licensing and model list](https://docs.ultralytics.com/models/yolo26).

Google’s current alternative is LiteRT `2.2.0`, Apache-2.0 licensed. It is reasonable if the selected model is already available in a compatible LiteRT format. For your existing ONNX-oriented plan, ONNX Runtime is the simpler first choice. ExecuTorch is another option, but its Java/Kotlin `Module` API is documented as experimental; QNN should be treated as a later, Qualcomm-specific optimization rather than the default path. [LiteRT releases](https://github.com/google-ai-edge/LiteRT/releases), [ExecuTorch getting started](https://github.com/pytorch/executorch/blob/main/docs/source/getting-started.md), [QNN provider requirements](https://onnxruntime.ai/docs/execution-providers/QNN-ExecutionProvider.html).

## Framework comparison

These are **estimated team-fit scores**, not benchmark results. I weighted the criteria 30% UI, 35% native video/ML, 20% agent workflow, 10% time to a real-phone APK, and 5% iOS later. The weighting reflects your stated priority on correctness and demo quality over coding speed.

| Option | Current starting set | UI / native / agents / APK / iOS | Weighted score | Assessment |
|---|---|---:|---:|---|
| **A. Expo / React Native** | Expo SDK `57.0.27`, React Native `0.86` line, Reanimated `4.5.1`, Skia `2.6.2`, `expo-video` `57.0.5` | 4.4 / 3.5 / 4.8 / 4.7 / 5.0 | **4.2** | Strongest JavaScript-agent workflow and iOS path. Kotlin Expo Modules can host the engine, but video and ML still create a native boundary. Expo’s SDK 57 video docs document an Android overlapping-video issue with `contentFit="cover"` and a `TextureView` workaround, relevant to overlays and dual video: [SDK 57 package matrix](https://docs.expo.dev/versions/latest/), [video](https://docs.expo.dev/versions/v57.0.0/sdk/video/), [Reanimated](https://docs.expo.dev/versions/v57.0.0/sdk/reanimated/), [Skia](https://docs.expo.dev/versions/v57.0.0/sdk/skia/). |
| **B. Flutter** | Flutter `3.47.7`, `video_player` `2.14.1`, `flutter_animate` `4.5.2`, `home_widget` `0.10.0` | 4.5 / 3.8 / 4.7 / 4.2 / 5.0 | **4.3** | Excellent drawing and animation control. Impeller is the default renderer on Android API 29+, with Vulkan and OpenGL fallback, but that does not guarantee 120 Hz on your target phone: [Impeller](https://docs.flutter.dev/perf/impeller). Use Pigeon or a platform channel for native decoding and timestamps; keep frames out of Dart. Flutter now has official Flutter/Dart agent plugins, including Codex and Claude setup, so its agent-workflow gap is smaller than older plans suggest: [Flutter AI tools](https://docs.flutter.dev/ai/get-started). |
| **C. Kotlin + Jetpack Compose** | Compose BOM `2026.09.00`, Media3 `1.11.1`, ONNX Runtime Android `1.31.0` | 4.5 / 5.0 / 4.1 / 3.8 / 2.0 | **4.4** | Best fit for native video timing, playback surfaces, analysis, and export. Strongest integration story, while the visual ceiling is high enough. Android Studio’s built-in Agent Mode is Gemini-based and supports MCP; your Claude/Codex agents can still work directly in the project, but do not get that specific Studio agent experience: [Agent Mode](https://developer.android.com/studio/gemini/agent-mode), [MCP support](https://developer.android.com/studio/gemini/add-mcp-server). |
| **D. Compose Multiplatform** | Compose Multiplatform `1.12.1`; its current Material 3 artifact is `1.12.0-alpha03` | 4.5 / 4.2 / 3.9 / 3.3 / 4.8 | **4.2** | Stable for Android and iOS UI, with more UI sharing later. The current Material 3 dependency is alpha, and Android Media3/ML work remains platform-specific. This adds multiplatform setup without removing the hardest Android integration work: [release notes](https://github.com/JetBrains/compose-multiplatform/releases), [platform support](https://kotlinlang.org/docs/multiplatform/supported-platforms.html). |

The polished-app examples support the claim that each UI framework can ship at scale. They do **not** provide a controlled, same-phone, 120 Hz comparison. Android’s case studies include Google Drive, SoundCloud, and adidas CONFIRMED using Compose: [Android Compose case studies](https://developer.android.com/newsletter/android-dev/2024/content/july-case-studies). Flutter’s showcase documents the My BMW app: [BMW case study](https://flutter.dev/showcase/bmw). Shopify has described production React Native use, then its 2026 Shop app migration to native; its own report cites native 120 FPS scrolling on Pixel, but that is a company case study, not an independent device benchmark: [Shopify on React Native](https://shopify.engineering/five-years-of-react-native-at-shopify), [Shop app migration](https://shopify.engineering/shop-app-migration).

Agent tooling is close between Expo and Flutter. Expo’s official tools include documentation, dependency, and build workflows: [Expo skills and MCP](https://github.com/expo/skills). Flutter’s official plugin provides skills and a Dart MCP server for agents including Codex and Claude: [Flutter AI setup](https://docs.flutter.dev/ai/get-started). Android Studio’s MCP integration is specifically for its Gemini agent. These tools improve coding assistance; they do not replace running the app on your phone.

## Playback and engine design

Use a single timeline authority for each comparison view. Keep frame presentation timestamps with each track point and event. Draw overlays against the currently presented frame time, not wall-clock time or inference completion time. Media3’s metadata callback provides that frame PTS; exact seeks are available but may cost latency. For exports, use Media3 Transformer’s overlay effect rather than recording a screen composition.

For video display, prototype both Media3 surface types on the target phone. Android recommends `SurfaceView` for ordinary playback because it has lower power use and more accurate timing; `TextureView` is useful when transforms and UI compositing require it. That tradeoff matters for your drawn court and trajectory overlays: [Media3 surface guidance](https://developer.android.com/media/media3/ui/surface).

Keep the analysis contract small and native: a request with a local video URI and sport/court configuration, progress events, and a result containing timestamped detections, tracks, and match events. The UI should receive those records, not decoded frames. Start inference on sampled frames with CPU/XNNPACK. Add GPU or vendor NPU execution only after a model and phone benchmark shows a benefit.

**Fully offline:** achievable as an app architecture if the model is bundled locally and no API call is required. **Whole-match completion time, thermal behavior, model quality, runtime APK growth, and exact model file size are UNVERIFIED.** Ultralytics’ CPU result is not a phone result, and no phone benchmark for your selected model has been established here.

## Top five pitfalls and fixes

1. **Video and Compose overlays may composite differently across devices.** Test `SurfaceView` and `TextureView` on the actual demo phone. Use `TextureView` where the overlays need clipping, transforms, or UI compositing; keep the lower-power `SurfaceView` option for plain playback. [Media3 surface guidance](https://developer.android.com/media/media3/ui/surface).

2. **Two players can drift or compete for decoder resources.** Give the primary player the clock; seek the comparison clip from that timestamp and test simultaneous playback on the target device. If it stutters, keep one clip playing and show the matched action as a paused or user-triggered comparison. Frame PTS is the sync basis, not inference wall time. [Frame metadata API](https://developer.android.com/reference/androidx/media3/exoplayer/video/VideoFrameMetadataListener).

3. **Full-video inference can overheat, exhaust memory, or take too long.** Sample frames, process incrementally, avoid retaining decoded frames, and show useful partial results. Begin with XNNPACK CPU inference. Do not promise full-match “boom” results until measured on the actual phone. [XNNPACK guidance](https://onnxruntime.ai/docs/execution-providers/Xnnpack-ExecutionProvider.html).

4. **A runtime benchmark does not prove sports tracking quality or model rights.** YOLO26’s published number is CPU ONNX, not Android; its page does not publish model file bytes and its weights have AGPL/Enterprise terms. Export and measure the exact artifact, test the selected sport clip, and make the license decision before distribution. [YOLO26 details](https://docs.ultralytics.com/models/yolo26).

5. **120 Hz is an 8.33 ms frame budget, not a framework feature.** Keep expensive blur and shader effects off frequently moving full-screen layers. Profile a release APK on the target phone, and test the exact 60/90/120 Hz modes the phone supports. AGSL needs an API 33+ fallback. [AGSL API](https://developer.android.com/reference/android/graphics/RuntimeShader).

The core Android stack requires no paid cloud service. AndroidX libraries are Apache-2.0 and ONNX Runtime is MIT. Expo’s EAS Free plan lists 15 Android and 15 iOS builds per month, but local Android builds are also available: [EAS pricing](https://expo.dev/pricing), [local builds](https://docs.expo.dev/build-reference/local-builds/). Do not make the Flutter path depend on Rive for its visual identity: Rive’s free plan does not include runtime asset exports; its Cadet plan is listed at $9 per seat monthly with annual billing or $17 month-to-month: [Rive pricing](https://www.rive.app/pricing).

## Recommendation

Build the Android app in **Kotlin + Jetpack Compose**, with Media3 `1.11.1`, ONNX Runtime Android `1.31.0` using XNNPACK, and the Android Photo Picker. Keep analysis and timestamps native; expose timestamped result records to Compose. Defer widgets, Wear OS, live capture, and shared C++/Rust until the post-game flow works.

## Three biggest risks

1. The selected pretrained model may miss the ball or shuttlecock in real phone footage.
2. Dual video, overlays, and full-match inference may exceed the target phone’s decoder, memory, or thermal budget.
3. The model’s license and exported artifact size may not fit your distribution plan.

## Open questions only your team can answer

- Which exact Android phone will run the judge demo?
- Which sport and college-shot clip are you committing to for the live demo?
- Are you willing to release the project under AGPL-compatible terms if the best-fitting model requires it?