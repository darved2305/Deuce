# Verdict

Build the demo around **one useful match insight that opens the exact rally behind it**, then let the player replay it, try a prediction, and export a highlight. Make the review genuinely usable in airplane mode by keeping the video and its analysis on the phone.

Do **not** promise fresh, full-match analysis in airplane mode until it runs successfully on the actual demo phone. Current platform APIs provide useful building blocks for local video, pose, and object tracking, but I could not verify a pretrained system that does reliable, full-match analysis across all six racket sports from one phone camera with no sport or venue training. That capability is **UNVERIFIED**.

The safest offline demo is an honestly labelled, already analyzed match. If fresh on-device analysis works by demo day, make it a bonus. A local developer flow can show the same analysis record through an SDK and MCP, without depending on venue internet.

## What premium sports and fitness products do well

These are product-pattern references, not proof that their analytics work offline or run on a stock phone.

| Reference | Pattern worth borrowing |
|---|---|
| [SwingVision](https://swing.vision/guides) and [PB Vision](https://pb.vision/) | Keep video close to the stats. PB Vision describes shot exploration, highlights, visual markup, and 3D game recreation. |
| [Wingfield Video Suite](https://www.wingfield.io/en/products/video-suite) | Remove dead time and make it quick to loop, slow down, and find tactical moments. |
| [F1 TV](https://www.formula1.com/en-us/subscribe-to-f1-tv) and [F1 Live Timing](https://www.formula1.com/en/timing/f1-live) | Pair the live image with a map and data layers the viewer can open when they want more detail. |
| [NBA player tracking stats](https://www.nba.com/stats/players/speed-distance) | Put movement measures such as speed and distance in a navigable stats experience. The NBA notes that tracking stats are unavailable for some games, a useful precedent for showing data availability clearly. |
| [Oura readiness contributors](https://support.ouraring.com/hc/en-us/articles/360057791533-Readiness-Contributors) and [WHOOP Recovery](https://www.whoop.com/us/en/recovery/) | Lead with a compact summary, then let people inspect the contributors and trends. Oura’s personal baseline also depends on having history, so avoid implying personalized trends for a brand-new user. |
| [2026 Apple Design Award winners](https://developer.apple.com/design/awards/) | The NBA Vision Pro experience uses a tabletop court with live stats; Tide Guide uses charts and animation to explain data. Treat these as visual references, not a requirement to build AR. |

## What is verified, and what is not

**Verified:** Native APIs can select video, play and export clips, run supported models locally, trigger haptics, and communicate between nearby devices. Google’s [Nearby Connections](https://developers.google.com/nearby/connections/overview) supports offline, peer-to-peer discovery and transfers; its [platform page](https://developers.google.com/nearby/) lists both Android and iOS.

**UNVERIFIED:** End-to-end accuracy and processing time for fresh, six-sport match analysis on the team’s actual phone. Apple’s [Vision tracking request](https://developer.apple.com/documentation/vision/vntrackobjectrequest) tracks an object once it has been identified; it is not itself a specialized shuttle or ball detector. [Core ML](https://developer.apple.com/documentation/coreml) can use the CPU, GPU, or Neural Engine, and Android [ML Kit object detection](https://developers.google.com/ml-kit/vision/object-detection) and [pose detection](https://developers.google.com/ml-kit/vision/pose-detection/android) supply mobile building blocks. None of those references establishes a complete racket-sports pipeline.

Google’s pose documentation reports about 30 FPS for its bundled model on a Pixel 3 XL, and about 23 FPS on CPU or 30 FPS on GPU for its more accurate model. Those are vendor-reported pose figures, not a benchmark for tracking a match, detecting contacts, or running on the event phone. **No end-to-end speed for the proposed analysis is verified.**

For video selection, Apple’s [PhotosPicker documentation](https://developer.apple.com/documentation/PhotosUI/PhotosPicker) explicitly warns that loading can fail if the selected asset must be downloaded from iCloud and there is no network. Preload a local sample and check that any judge-provided clip is actually on-device before promising airplane-mode analysis.

**Verified export APIs:** Apple’s [AVAssetExportSession](https://developer.apple.com/documentation/avfoundation/avassetexportsession) supports local media export. Android [Media3 Transformer](https://developer.android.com/media/media3/transformer) supports trimming, cropping, and custom effects; the current Android getting-started documentation lists **Media3 Transformer 1.11.1**. No cloud processing or API free-tier quota needs to sit in the recommended demo path.

## Candidate wow moments

Effort, live risk, and premium scores below are **estimates**, not measured results. Effort means a focused slice on top of a working app and video viewer: S = 1–3, M = 4–8, L = 8–16, XL = 16–32+ person-hours. They do not include building and validating a complete match-analysis model from scratch. Premium score is a subjective 1–5 estimate.

| Idea | Purpose and how it works | Offline status | Effort, live risk, premium |
|---|---|---|---|
| **1. Insight with proof clip** | Show one useful, defensible match finding. Tapping it seeks to the supporting rally in the video. Store event timestamps and metric provenance alongside the local report; use AVPlayer on iOS or Media3 playback on Android. | Yes, once the video and report are local. | M, low, **5/5** |
| **2. Read the Shot** | Pause just before a detected or manually marked contact. The player predicts direction, then reveals the clip and result. This becomes a small training exercise, not a decorative animation. | Yes, for an indexed local clip. Do not claim automatic contact detection unless it was verified. | M, medium-low on a prepared clip, **5/5** |
| **3. Airplane-mode match review** | Turn off network access and explore a locally stored match, timeline, court view, and clips. Clearly say **“saved analysis”** if it was processed earlier. | Yes for cached review. Fresh inference is conditional and **UNVERIFIED**. | M cached, XL fresh, low cached risk / very high fresh risk, **5/5** |
| **4. Broadcast-style court replay** | Overlay timestamped player tracks and bounce markers on a court view. A calibrated 2D top-down map can be compelling; label it as 2D rather than implying measured 3D trajectory. | Yes, if event/track data is local. | M, medium, **5/5** |
| **5. Ghost replay** | Compare two rallies by aligning their court positions and paths, or show the original clips side by side. Useful for a coach discussing positioning or a player comparing a pattern. | Yes, once both rallies are indexed. | M–L, medium-high because track errors affect the comparison, **4/5** |
| **6. Export a vertical highlight** | Trim an indexed rally, add restrained score/time overlays, and export a real video file using [AVAssetExportSession](https://developer.apple.com/documentation/avfoundation/avassetexportsession) or [Media3 Transformer 1.11.1](https://developer.android.com/media/media3/transformer/getting-started). | Yes, for local media and event data. | M, medium, **5/5** |
| **7. Developer SDK and local MCP** | Use one match record for an SDK replay component and read-only MCP tools such as `get_match_summary` and `find_events`. MCP supports structured data, text, images, audio, and resource links; a client’s display of video is not guaranteed by the spec. Return a timestamp or local resource link, then open the clip in your player. | The local server can work without venue internet. A Claude Desktop round trip that is fully offline is **UNVERIFIED**. | M–L, medium, **5/5 for developer judges** |
| **8. Judge-provided gallery video** | Select a local video using the system picker, validate that it is a usable racket-sports view, then show what the app can extract. Keep a known local sample as a fallback. | Conditional. An iCloud-only asset may need a network download. | L–XL, very high, **5/5 if it works** |
| Haptics and bounce audio | Use a short optional haptic for a selected impact or scrub point. Core Haptics supports combined audio and haptics; Android exposes `VibrationEffect`. Avoid haptics on every hit. | Yes when event times are available locally. | M, low-medium, **3/5** |
| Phone-to-phone companion | Use Nearby Connections to send an analysis or drive a companion screen. Both phones need the app and permissions. For simple sharing, use [AirDrop](https://support.apple.com/en-lamr/guide/security/sec2261183f4/web) or [Quick Share](https://support.google.com/pixelphone/answer/2781895?hl=en), with device and radio requirements checked in advance. | Local transfer is supported, but the radios must be available. Airplane mode may require Wi-Fi or Bluetooth to be turned back on. | L, high, **4/5** |
| Ask Your Match by voice | Ground answers in a deterministic local query over recorded events; return clip timestamps as evidence. Apple [Foundation Models](https://developer.apple.com/documentation/FoundationModels) requires a supported Apple Intelligence device. Offline speech recognition is conditional on language and device; Android’s [on-device recognizer](https://developer.android.com/reference/android/speech/SpeechRecognizer) also requires an availability check. | Possible on eligible devices, not dependable across unknown judge phones. | L, medium-high, **5/5 if grounded** |
| Local LLM for the developer demo | Optional laptop path: pre-download Ollama’s `qwen3:4b` model, listed at **2.5 GB**; the upstream [Qwen3-4B model card](https://huggingface.co/Qwen/Qwen3-4B) lists **Apache-2.0**. Have the model call read-only local match tools. Its speed on your laptop is **UNVERIFIED** until tested. | Yes after installation and model download. | M–L, medium, **4/5** |
| AR tabletop rally | Place a small court visualization on a table using ARKit/RealityKit or ARCore. Keep the rally path 2D or 2.5D unless height is actually measured. ARCore requires supported-device and service checks, so it is not a safe core moment. | Conditional on device support and preloaded AR components. | L–XL, high, **4/5** |
| Live Activity / Dynamic Island | Show analysis progress on iPhone’s Lock Screen or Dynamic Island via [ActivityKit](https://developer.apple.com/documentation/activitykit/displaying-live-data-with-live-activities). Apple documents a 4 KB limit for Live Activity content state. Useful polish, but iOS-only and not a core wow moment. | Local app updates can work offline. | S–M, low-medium, **3/5** |

### Ranked shortlist

This ranking favors useful product depth plus a low chance of live-demo failure. It is an estimate.

1. **Insight with the proof rally.** Strongest player and coach value. Every claim should open the clip that supports it.
2. **Read the Shot.** A judge can participate in a replay rather than just watch a dashboard.
3. **Airplane-mode review of a saved analysis.** Makes the offline promise visible and testable without implying fresh inference.
4. **Broadcast-style 2D court replay.** Visually clear, provided the tracking and court calibration are honest.
5. **Vertical highlight export.** Produces a useful artifact the player can keep or share.
6. **Ghost replay.** Offers coaching depth if the two tracks are reliable enough to compare.
7. **Local SDK and MCP query.** Makes the developer platform tangible; keep the result structured and tied to a rally.
8. **Judge-video ingestion as a fallback branch.** Attempt it only if the clip is local, readable, and within the demo’s validated input conditions.

## Five-minute demo run sheet

Use a **permissively licensed, locally stored sample** as the reliable path. Wikimedia Commons has a [tennis-match category](https://commons.wikimedia.org/wiki/Category:Tennis_matches), but check the license and attribution on the exact file before using it. **No specific demo clip or license has been verified yet.** Commons explains its [reuse requirements](https://commons.wikimedia.org/wiki/Commons:Reusing_content_outside_Wikimedia).

| Time | Presenter and action |
|---|---|
| **0:00–0:25** | Presenter 1 introduces the problem: useful match information should be available after a game, without a special venue installation. Presenter 2 shows that the phone is in airplane mode. |
| **0:25–0:55** | Presenter 2 opens the preloaded local video and saved analysis. If a judge has a suitable video already stored locally, offer that as a branch; otherwise stay on the sample. State whether the result is cached or freshly computed. |
| **0:55–1:40** | Presenter 3 shows the single verified insight. Tap it to seek to its supporting rally. If the app does not yet compute a trustworthy insight, label the prepared record as a demo fixture instead of inventing a statistic. |
| **1:40–2:30** | Presenter 2 replays the point slowly, opens the court view, and invites a judge to try **Read the Shot**. Reveal the actual continuation from the clip. |
| **2:30–3:10** | Presenter 3 compares a second rally or exports a vertical highlight. Show the actual saved output, not a simulated share screen. |
| **3:10–4:35** | Presenter 4 runs a local SDK call or MCP query against the same match record. Show the structured finding and its event timestamp, then open the clip in the product player. If using Claude Desktop, do not describe the language-model response as offline unless that has been verified. |
| **4:35–5:00** | Presenter 1 closes with the spec card: tested device and input clip, whether analysis was fresh or saved, source and license, supported capability level, and known limitations. |

### Choreography and hardware

Use a wired phone-to-projector connection as the primary display path. Apple documents wired display connections for iPhone; Android external-display support varies by model, so test the exact device and adapter beforehand. [Apple wired display guide](https://support.apple.com/guide/iphone/connect-to-a-display-with-a-cable-iph93af723b9/27/ios), [Pixel external display support](https://support.google.com/pixelphone/answer/2865484?hl=en).

Give each presenter one role: host, phone operator, coach/analysis narrator, and developer-platform presenter. Keep a printed spec card near the phone. It should state the exact clip source, device tested, fresh-versus-saved analysis status, and what is measured versus experimental. Avoid QR codes and any step that asks the room to install an app or connect to venue Wi-Fi.

## Premium UI principles

1. **Lead with one answer.** Use one primary finding on the match summary, then let the player open its evidence. Avoid showing every metric at once.
2. **Make evidence one tap away.** A score, coaching cue, or comparison should seek to a clip or rally. Preserve original video as the source of truth.
3. **Reveal depth progressively.** Summary first, rally timeline next, then shot or player detail. The deeper screens should answer a clear follow-up question.
4. **Use the court as an analysis view, not decoration.** Make player paths and bounce markers correspond to timestamped data. Distinguish missing or uncertain tracking from a measured path.
5. **Let video dominate playback.** Keep controls legible over the image and secondary information visually quiet. Apple’s [video guidance](https://developer.apple.com/design/human-interface-guidelines/playing-video) and [dark-mode guidance](https://developer.apple.com/design/human-interface-guidelines/dark-mode) are useful references.
6. **Keep motion purposeful.** Animate a transition to show where the timeline moved or which shot was selected. Respect reduced-motion settings; Apple recommends motion be purposeful and optional in its [motion guidance](https://developer.apple.com/design/human-interface-guidelines/motion).
7. **Use a small, consistent haptic vocabulary.** For example, one light tick for selection and one short impact for a chosen replay moment. Make it optional; Apple advises against excessive or inconsistent haptics in its [haptics guidance](https://developer.apple.com/design/human-interface-guidelines/playing-haptics).
8. **Show data status clearly.** Use labels such as “measured,” “estimated,” “saved analysis,” or “tracking unavailable.” Do not present an experimental ball or shuttle track as a confirmed speed reading.
9. **Make the export feel finished.** Put the selected rally and restrained overlay into a real video file. Give the player a clear local save and share action.
10. **Separate app and developer depth.** Keep match review uncluttered. Put schema, capability status, and API output in the developer surface.

## Recommendation

Build the first demo slice around the **local match record**: video, timestamped events, provenance, summary, and clips. Use that same record for the app’s insight and replay, the vertical export, and the SDK/MCP demo. Treat “works offline” as two separate claims: saved match review can be demonstrated first; fresh full-match inference can be claimed only after testing it on the target device with airplane mode enabled.

### Three biggest risks

1. **Model accuracy and latency.** Full single-camera analysis across six sports is **UNVERIFIED**; current detection and pose APIs are only building blocks.
2. **Video suitability and rights.** A judge’s clip might need downloading, show an unsuitable angle, or lack permission for the demo. The exact public video and its license are still **UNVERIFIED**.
3. **Demo-device dependencies.** OS support, local LLM speed, AR eligibility, second-phone setup, and projector adapters vary. A weak link can consume the demo; keep these features optional and test the actual hardware.

### Open questions for the team

- Which phone and OS will be the primary demo target, and what second phone and laptop are available?
- Which sport and specific locally stored public video will anchor the demo?
- Is the offline claim “saved analysis works offline,” or will you attempt to prove fresh inference on-device?
- Which display cable and adapter can you test with the actual projector?
- Do you want the MCP moment to use Claude Desktop with network status disclosed, or a preloaded local model whose speed you have measured?