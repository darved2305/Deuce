# Track 09: Solve for phone-camera footage

**Verdict:** Make the original recording the measurement source. Preserve its frame timestamps, keep pixels at their recorded resolution, and only report ball, speed, or line metrics when the clip passes explicit quality gates. Use enhanced slow motion only on a separate viewing copy.

A practical recorder can offer two modes: **4K at 30 fps** for court and player detail, and **1080p at 120 fps** for ball motion and contact timing when the selected camera supports it and the venue is bright. Those modes serve different measurements. Android high-speed capture also removes manual exposure control, which limits how much we can control motion blur.

Fully offline Android ball tracking, speed, and line calls are **UNVERIFIED**. The published models below have no verified Android inference timings. A useful first scope is table tennis and badminton, using their sport-specific pretrained models and marking uncertain measurements as unavailable.

## What Android can capture

As of **2026-10-10**, the latest stable CameraX release is **1.6.2**. The high-speed recording API is stable. CameraX can query a camera’s high-speed capabilities with `Recorder.getHighSpeedVideoCapabilities(CameraInfo)` and configure a `HighSpeedVideoSessionConfig`. The available frame rates and sizes depend on the selected camera and must be queried at runtime. [CameraX release notes](https://developer.android.com/jetpack/androidx/releases/camera), [Recorder API](https://developer.android.com/reference/androidx/camera/video/Recorder), [HighSpeedVideoSessionConfig API](https://developer.android.com/reference/androidx/camera/video/HighSpeedVideoSessionConfig)

| Setting | Recommendation | Verified API behavior and measurement consequence |
|---|---|---|
| **60 fps** | Use regular CameraX recording where the camera advertises the frame-rate range. | This is distinct from constrained high-speed capture. Query the chosen camera’s supported ranges. |
| **120/240 fps** | Prefer CameraX 1.6.2 high-speed capture when available. | Constrained high-speed mode starts at 120 fps. Camera2 requires supported size/FPS combinations. Its high-speed session has limited output surfaces and forces AE, AWB, and AF to automatic, disabling manual capture controls. CameraX high-speed mode also restricts its preview and effects. Treat it as **record first, analyze after**. [Camera2 high-speed session](https://developer.android.com/reference/android/hardware/camera2/CameraDevice), [supported high-speed sizes and ranges](https://developer.android.com/reference/android/hardware/camera2/params/StreamConfigurationMap) |
| **Shutter and ISO** | In a normal 30/60 fps session, target a short exposure such as **1/500–1/2000 s** when lighting permits. | These are **engineering starting points**, not universal thresholds. Exact exposure time and ISO require the camera to advertise manual-sensor capability. CameraX can pass some Camera2 requests through `Camera2Interop`, which Android marks experimental and recommends testing. Manual exposure trades light for less blur. [CameraX Camera2 interop](https://developer.android.com/media/camera/camerax/architecture#camera2_interop), [CameraCharacteristics](https://developer.android.com/reference/android/hardware/camera2/CameraCharacteristics), [CaptureRequest exposure time](https://developer.android.com/reference/android/hardware/camera2/CaptureRequest) |
| **Focus, exposure, white balance** | Meter on court and then lock the supported controls in normal capture mode. | Whether each control is available depends on the individual camera. Constrained high-speed capture overrides AE/AWB/AF to automatic, so a locked manual setup cannot be assumed in 120/240 fps mode. |
| **50 Hz indoor lighting** | Request the camera’s 50 Hz anti-banding mode in auto exposure, then inspect a short test recording for bands. | Camera2 exposes `CONTROL_AE_ANTIBANDING_MODE_50HZ` when supported. If AE is off, anti-banding has no effect; the app must choose an exposure that avoids banding. High-speed mode’s automatic-control overrides mean the recorder must verify the result rather than promise a shutter setting. [CameraMetadata anti-banding](https://developer.android.com/reference/android/hardware/camera2/CameraMetadata), [CameraCharacteristics anti-banding modes](https://developer.android.com/reference/android/hardware/camera2/CameraCharacteristics) |
| **4K vs 1080p** | Prefer 4K30 for court geometry and player detail; prefer 1080p120 for fast ball events if the phone supports it. | At the same aspect ratio, 4K30 and 1080p120 both process about **249 million pixels per second**. That is a calculation, not a phone benchmark. High-speed modes may only offer lower resolutions, so query the actual camera. |
| **Bitrate and codec** | Keep H.264 as the compatibility path. Offer HEVC only when the device reports encoder support and test its output. | Android documents H.264 and HEVC support, but codec and profile support vary across devices. There is no universal bitrate that preserves a 2–8 px ball. **Estimated test ladder:** compare 20/30/40 Mb/s at 1080p60 and 40/60/80 Mb/s at 4K30. These are starting values for experiments, not verified camera defaults. [Android supported media formats](https://developer.android.com/media/platform/supported-formats) |
| **Stabilization** | On a tripod, disable electronic video stabilization when the camera allows it. If handheld stabilization is needed, recalibrate court geometry through the clip and reduce trust in line metrics. | Android documents that video stabilization warps frames and can change the crop region. This can move court lines between frames. Optical stabilization is separate; test it on the actual phone. [Camera2 video stabilization](https://developer.android.com/reference/android/hardware/camera2/CaptureRequest#CONTROL_VIDEO_STABILIZATION_MODE) |

**Which phones expose these controls?** A brand or model name is not enough to answer. Query the exact rear camera and lens at runtime. CameraX 1.6.2 exposes high-speed recording when that camera reports the capability; Camera2 interop or direct Camera2 is needed for controls such as manual shutter and anti-banding in ordinary sessions. Constrained 120/240 fps capture itself still overrides manual 3A controls, even when using Camera2.

At 30 fps, frames are **33.3 ms** apart; at 120 fps, they are **8.3 ms** apart; at 240 fps, **4.2 ms** apart. A rough blur model is:

`blur length in pixels ≈ image motion in pixels/second × exposure time in seconds`

For example, at 4,800 px/s, a 1/1000 s exposure makes roughly a 4.8 px streak. This is illustrative. Actual image motion depends on camera position, court coverage, ball speed, and perspective. A short shutter also demands more light, and constrained high-speed capture may not let the app set it.

### Capture coach

Before recording, show the team:

- The selected physical camera, lens, resolutions, and supported frame-rate/size pairs.
- A court framing overlay, with all relevant lines and both players visible.
- Whether the clip is 4K30, 1080p120, or another supported mode.
- A short test’s actual frame intervals, image brightness, flicker, sharpness, and camera shake.
- The farthest player’s pixel height and an estimated ball/shuttle pixel width, where the ball is visible.
- Whether stabilization is active and whether focus/exposure locks are available.

The coach should recommend **“4K30: court/player detail”** or **“1080p120: fast-action timing”**, rather than implying that one mode is best for every metric.

## Use the source video timestamps

For gallery imports, decode presentation timestamps and compute the frame-interval distribution. Android’s `MediaExtractor.getSampleTime()` returns each current sample’s presentation time in microseconds. Use those timestamps instead of assuming that a file’s nominal frame-rate field describes every frame. [MediaExtractor API](https://developer.android.com/reference/android/media/MediaExtractor#getSampleTime())

Record and report:

- Median, minimum, and maximum frame interval.
- Dropped or duplicate timestamps and long gaps.
- Resolution, codec, duration, and any available bitrate metadata.
- Audio/video offset, if measurable.
- Blur, exposure, court movement, and visible object size.

A slow-motion export may have retimed playback timestamps. In that case, the file may not reveal the sensor’s original capture cadence. If the import lacks original timing metadata, mark original capture fps **UNVERIFIED**.

Estimate blur and compression from decoded frames too. Bitrate alone cannot tell us whether a moving 3 px ball survived compression. Use edge sharpness and streak extent for blur; use blockiness, ringing, and detail loss for compression. Treat those as clip indicators, not ground-truth labels. If shutter metadata is missing, mark shutter time **UNVERIFIED**.

## Candidate models and libraries

| Candidate | Evidence and fit | Files, size, speed, licence |
|---|---|---|
| **OpenCV** for court lines, homography, frame differencing, and basic quality checks | Use a native-resolution frame, crop before inference, and map image points into court coordinates. **OpenCV 4.14.0** is the current 4.x release; OpenCV 5.0.0 is also available. Use 4.14.0 as the conservative integration target. [Release list](https://github.com/opencv/opencv/releases) | Apache 2.0; Android is a supported platform. No model weights. [OpenCV](https://opencv.org/) |
| **TrackNetV3** for badminton | Shuttlecock-specific temporal model. The official repository reports 97.51% accuracy, 98.56 F1, and 25.11 FPS on its shuttlecock test split. **The repository does not identify the hardware for that FPS number**, so it is not a phone benchmark. Its gap-filling module can predict missing trajectory points; those points must be labelled as inferred. [Official repo](https://github.com/qaz812345/TrackNetV3) | Checkpoints: `TrackNet_best.pt`, `InpaintNet_best.pt`, distributed in `TrackNetV3_ckpts.zip`. The repo explicitly says its code and pretrained checkpoints are MIT licensed. Exact checkpoint and zip sizes: **UNVERIFIED**. |
| **BlurBall** for table tennis | A 2026 CVPR Workshop model that predicts ball location, blur direction, and blur extent. Its released dataset contains 64,000 annotated table-tennis frames. This directly addresses motion blur, but the source does not verify performance on ordinary 30 fps phone footage. [Paper](https://openaccess.thecvf.com/content/CVPR2026W/CVsports/papers/Gossard_BlurBall_Joint_Ball_and_Motion_Blur_Estimation_for_Table_Tennis_CVPRW_2026_paper.pdf), [repo](https://github.com/cogsys-tuebingen/blurball) | Pretrained weights are linked from a separate Nextcloud share. Exact filenames, sizes, Android speed, and a separate weight-file licence: **UNVERIFIED**. The repository itself is MIT licensed. |
| **WASB-SBDT** for tennis or badminton experiments | Three-frame input resized to 288×512; the paper reports a 1.5M-parameter model. The model zoo supplies weights by sport/dataset. This is **not one six-sport zero-shot checkpoint**. The authors validate on HD/FHD video at 25–30 fps and report experiments on a V100 server, not Android. [Paper](https://arxiv.org/abs/2311.05237), [model zoo](https://github.com/nttcom/WASB-SBDT/blob/main/MODEL_ZOO.md) | The reported 1.5M parameters imply about **6 MB of raw FP32 weights**, calculated as parameters × 4 bytes. Actual checkpoint size is **UNVERIFIED**. Repository code is MIT; a separate checkpoint licence is **UNVERIFIED**. |
| **MonoTrack** as a smaller badminton alternative | Its paper uses a three-input, three-output design and reports 2.9M parameters versus 11.3M for TrackNetV2. That implies about **11.6 MB raw FP32 parameters**, calculated; it is not the checkpoint size. Android speed and a downloadable Android-ready package are **UNVERIFIED**. [Paper](https://openaccess.thecvf.com/content/CVPR2022W/CVSports/papers/Liu_MonoTrack_Shuttle_Trajectory_Reconstruction_From_Monocular_Badminton_Video_CVPRW_2022_paper.pdf) | Checkpoint bytes and standalone licence: **UNVERIFIED**. |
| **RIFE** for display-only interpolation | The official ECCV 2022 repository reports 30+ FPS for 2× 720p interpolation on an RTX 2080 Ti GPU. That is not an Android speed result. [Repo](https://github.com/hzwer/ECCV2022-RIFE), [paper](https://arxiv.org/abs/2011.06294) | MIT repo. Exact selected model file, size, and Android speed: **UNVERIFIED**. Never use its synthesized frames for measurement. |

For the hackathon, **BlurBall for table tennis and TrackNetV3 for badminton** are the clearest model-to-sport matches. Keep the sport choice explicit in the app. The source weights are PyTorch files, not drop-in Android assets. Conversion and runtime performance on the team’s phones are **UNVERIFIED**. Do not claim full offline ball analysis until a representative clip runs locally on the target phone.

## Ranked measurement strategy

The gains and costs below are ranked recommendations. Where a paper benchmark comes from a different dataset or device, it is identified as such.

| Rank | Technique | Measurement gain | Android cost and feasibility | Main risk |
|---:|---|---|---|---|
| 1 | **Stable framing and short exposure** | Reduces shake and blur before inference. This is the highest-value improvement because it preserves real signal. | Low app compute; requires a mount, light, and supported camera controls. | 120/240 fps mode may force automatic exposure. A dim or flickering venue can defeat it. |
| 2 | **PTS audit and clip-quality gates** | Prevents bad timestamps, gaps, and poor visibility from becoming false speed or contact measurements. | Low compute; suitable on phone through MediaExtractor and sampled-frame checks. | Retimed or edited imports can hide original sensor cadence. |
| 3 | **Native-resolution crops and ROI inference** | A crop uses the original pixels and avoids shrinking the whole court before inference. Track players/court first, then crop predicted ball regions with a safety margin. | Low-to-medium for crop and coordinate bookkeeping; detector inference may be high cost. | A bad ROI can crop out the ball. Keep a periodic full-frame search. |
| 4 | **Sliced/tiled inference** | Can make small objects occupy more of a detector’s input. SAHI reported AP gains of 5.1–6.8% on aerial-object datasets, not racket-sport ball footage. [SAHI paper](https://arxiv.org/abs/2202.06934) | High if each tile runs a model; server or selected-frame use is more realistic than every frame on an unbenchmarked phone. | More compute, duplicate detections, and no evidence that those aerial gains transfer to ball tracking. |
| 5 | **Temporal heatmaps** | Three or more frames supply motion context. WASB uses three frames; TrackNet-family models and MonoTrack also use temporal inputs. | Medium-to-high model cost. No verified Android timings for these checkpoints. | A temporal detector can still fail on phone compression, blur, occlusion, or different court views. |
| 6 | **Background subtraction/frame differencing** | A training-free motion prior can nominate candidate ball regions. Use court-line registration first when the camera moves. | Low-to-medium with OpenCV 4.14.0; useful as a second signal. | Camera shake, flicker, shadows, players, and compression create false motion blobs. |
| 7 | **Blur-streak measurement** | A streak’s centre, direction, and extent can contain real motion information. BlurBall explicitly predicts those attributes for table tennis. | Medium if using its model; manual streak fitting is cheaper on a small candidate crop. | Speed from streak length needs known exposure time and a known spatial scale. Unknown auto exposure, denoising, or codec processing makes it **UNVERIFIED**. |
| 8 | **Sub-pixel centre fitting** | Fit an intensity profile or model heatmap around a detected spot; aggregate estimates across observed frames. Long court-line edges can also be fit to fractional-pixel coordinates. | Low once a good candidate crop exists. | Fractional-pixel output is an estimate from real pixels, not extra detail. A 2 px object can still have large systematic error. |
| 9 | **Audio plus video and physics** | Audio onset can nominate a contact window; video confirms it and supplies position. A trajectory fit can interpolate only within observed points. | Audio onset and curve fitting are low-to-medium compute. | Echo, microphone distance, sync offset, and sport-specific aerodynamics limit event timing. |
| 10 | **RIFE interpolation or denoising for viewing** | Makes replay look smoother or clearer for the user. | High and unverified on Android for RIFE. | These operations synthesize or alter pixels. They improve display only and must stay out of measurement. |

### Audio and trajectory fitting

At 48 kHz, decoded PCM has a sample spacing of about **20.8 μs**. That is sample-grid resolution, not real contact-time accuracy. A racket impact detector still has uncertainty from the microphone, codec, room echo, and sound travel. Audio should mark a candidate event window, not assert sub-millisecond contact time.

Racket-sport research supports combining modalities. A 2011 tennis study reported improved hit detection after combining audio, trajectory, and event timing, including about 30 percentage points higher audio F-score and 10 points higher video F-score on one women’s doubles match. A 2021 tennis study trained paired audio and video hit detectors and used them to identify synchronization errors in a database of 504,300 frames from six hours of tennis video. Neither study proves sub-millisecond timing on a phone clip. [2011 ISCA paper](https://www.isca-archive.org/avsp_2011/huang11_avsp.html), [2021 audio-video study](https://arxiv.org/abs/2104.10116)

Fit trajectories using decoded timestamps, not frame numbers. Estimate the event time inside the interval bounded by observed points, then propagate detection and timing uncertainty. Use separate motion assumptions by sport. A simple parabola is only a starting model: badminton shuttlecock flight is strongly affected by quadratic drag and differs from a ball’s trajectory. [Badminton trajectory study](https://pmc.ncbi.nlm.nih.gov/articles/PMC3761540/)

For speed, report **px/s** if the clip lacks a defensible world scale. Court-plane speed can be mapped into metres after court calibration. Airborne 3D speed needs additional assumptions such as camera calibration, apparent ball size, or physics. If those assumptions dominate the answer, show an estimate with an interval or withhold it.

### Court geometry and abstention

Use court line edges and court intersections to estimate a homography from image coordinates `(u,v)` to court coordinates `(x,y)`. Four tapped court corners are configuration, not model training. Fit long line segments from multiple pixels where possible, and update the transform if the camera moves.

For local pixel uncertainty `Σuv`, propagate it through the homography:

`Σxy ≈ J Σuv Jᵀ + ΣH`

Here, `J` is the local Jacobian of the pixel-to-court mapping, and `ΣH` accounts for uncertainty in the homography. The metre error per pixel changes with image location and perspective. A fixed global “pixels to metres” scale will be wrong across the court.

For a line call, project the ball’s estimated impact location and its uncertainty onto the court-line normal. If the 95% interval overlaps the line, report **uncertain**. A homography maps the court plane; it does not directly map an airborne ball to 3D.

## Quality score for every clip and metric

Show a clip readiness score plus separate readiness for player tracking, ball tracking, court calls, and speed. Treat these as **heuristic readiness scores**, not probabilities of correctness. A calibrated probability would need labelled validation results.

For each metric, show the evidence behind its score:

- **Player:** visible player coverage, body height in pixels, and track gaps.
- **Ball:** detected-frame coverage, median ball width, blur length, and longest missing interval.
- **Lines:** court-fit residual, camera movement, and projected location uncertainty.
- **Speed:** actual timestamp cadence, number of observed points, calibration quality, and whether the value is planar or 3D-estimated.

Example: `Ball: partial | 18% of sampled frames detected | median 4 px | 30 fps | 9-frame gap | speed unavailable.` A weak metric must not be hidden by a strong player score.

## Minimum footage targets

No source establishes universal pixel or frame-rate cutoffs for phone footage across these sports. The thresholds below are **estimated hackathon acceptance targets**. Test them on the actual phones and venue; keep the product willing to abstain.

| Feature | Initial target | Expected result and limits |
|---|---|---|
| **Player tracking** | 720p30 minimum; 1080p30 preferred. Fixed landscape view, full court visible, and farthest player about 100 px tall or more. | Player position and court coverage can be useful. Pose-based technique feedback needs more pixels and less blur; exact cutoff is **UNVERIFIED**. |
| **Ball tracking** | 1080p120 or 4K60 when supported; bright, stable view. Aim for a ball/shuttle at least 6 px across in the measurement region. | At 2–5 px, a pretrained model may still propose detections, but stable localization and speed are not assured. At 30 fps, present only a sparse track when detections support it. |
| **Line calls** | Prefer 4K60; otherwise 1080p120 with a close, sharp view of the impact area. Show all relevant line markings and the actual ground contact. Aim for an 8 px ball region near impact or a clearly modelled streak. | Experimental single-camera call only. If the contact is hidden, the court transform is unstable, or the uncertainty interval crosses the line, report uncertain. |
| **Speed** | 1080p120/240, short exposure where controllable, calibrated court, and at least five observed points in one flight segment. | Start with planar speed. Report 3D speed only when depth assumptions and uncertainty are visible. With a 30 fps or retimed clip, fast-shot speed is generally unavailable. |

## Capture contract

1. **Mount the phone and keep it still.** Record landscape from an elevated end or side position that shows the players and relevant court lines. Keep a margin around the court.
2. **Use the main rear camera at 1×.** Avoid digital zoom, panning, lens switching, filters, HDR/night processing, and social-media re-encoding.
3. **Choose the mode for the metric.** Use 4K30 for court and player detail; use 1080p120 for fast action when supported and bright enough. A short preflight decides which mode the actual phone can sustain.
4. **Test exposure and lighting.** In ordinary capture, try 1/500–1/2000 s if supported. In high-speed mode, inspect a test clip because manual shutter is overridden. Check anti-banding and flicker under the venue lights.
5. **Keep the original file and timestamps.** Do not re-encode the measurement source. Save the chosen camera, mode, resolution, and available capture settings alongside it.
6. **Keep audio.** Request 48 kHz where supported, inspect the decoded sample rate and A/V offset, and use sound as event evidence with timing uncertainty.
7. **Run a five-second test.** Confirm court framing, far-player visibility, ball pixel width, actual frame intervals, blur, flicker, and stabilization before filming the full clip.

## Mentor answer

We can recover useful player movement, court position, and some ball events from a normal phone camera when the recording is stable and the ball survives the image. We will preserve the original frames and timestamps, use pretrained sport-specific models, and show how much footage supports each metric. Sound can help locate a hit, while court calibration and trajectory fitting can refine an observed track. When a clip cannot support speed or a line call, the app will say so. Smooth slow motion will be a viewing aid, never measurement evidence.

## Recommendation

Build the recorder and import quality gates first with **CameraX 1.6.2**, Camera2 capability queries, and **OpenCV 4.14.0** for geometry and lightweight image checks. Make the first ball-model experiments **BlurBall on table tennis** and **TrackNetV3 on badminton**. Treat higher-compute ball analysis as experimental until the exact checkpoint runs on the team’s target Android phones. Keep the capture and clip audit on-device; queue advanced analysis only if a free compute path is verified. Cloud GPU cost and free-tier capacity are **UNVERIFIED** in this track.

## Three biggest risks

1. **High-speed capture and lighting conflict.** Android constrained high-speed mode forces automatic exposure controls. Indoor flicker or low light can leave the ball blurred even at 120/240 fps.
2. **Model and phone domain gap.** The published models target sport-specific datasets, and none has a verified Android speed result here. Two-to-eight-pixel objects, compression, and phone-camera processing may reduce accuracy.
3. **Single-camera uncertainty.** Perspective and sparse timestamps make airborne speed and borderline line calls fragile. Audio can locate an event but room echo and sound travel limit precise timing.

## Open questions for the team

- Which exact Android phone models and rear lenses will be used? What frame-rate/resolution pairs do they expose?
- Which one or two sports can the team definitely film at the venue, especially whether a table-tennis table is available?
- Where can the phone be mounted, and how bright and flicker-prone are the venue lights?
- Must the demo analyze a newly recorded clip with no network, or can advanced processing use a verified free compute path?