# D3 verdict report: vision engine, sports, and low resolution

**Verdict: KEEP the sport priorities and cloud engine. MODIFY the capability claims.** The current probes support experiments, not the draft’s “Core” ball tracking, line calls, speed, or score-from-video claims. The strongest plan is a small, honest product: analyze a rights-cleared racket-sport clip, show only validated evidence, and use a fresh college clip for a user-seeded, pixel-space ball trail.

I reviewed the [vision-engine draft](/Users/Hetansh/Github/elevate1.0/docs/04-vision-engine.md), [PS fundamentals](/Users/Hetansh/Github/elevate1.0/docs/01-ps-fundamentals.md), related product, app, coaching, and referee drafts, the listed research reports, and the local probe outputs. The evidence paths include the [footage manifest](/Users/Hetansh/Github/elevate1.0/research/probes/footage/SOURCES.md), [WASB badminton probe code](/Users/Hetansh/Github/elevate1.0/research/probes/balltrack/wasb_infer.py), [nine badminton outputs](/Users/Hetansh/Github/elevate1.0/research/probes/balltrack/out_static), [static tennis output](/Users/Hetansh/Github/elevate1.0/research/probes/balltrack/out/wasb_tennis_on_artlabss_mps.json), and [cloud-streaming report](/Users/Hetansh/Github/elevate1.0/research/out/16-cloud-streaming.md). I made no workspace changes.

## Claim ledger

| Draft claim | Status | Evidence and correction |
|---|---|---|
| WASB badminton detected the shuttle in 51% of frames | **CONFIRMED, narrowly** | The nine single-stroke clips have 186 detections in 366 processed slots, or 50.8%. This is output coverage, not accuracy or match-level tracking. The source manifest says these clips are only 1–1.9 seconds each. [Probe outputs](/Users/Hetansh/Github/elevate1.0/research/probes/balltrack/out_static) and [footage manifest](/Users/Hetansh/Github/elevate1.0/research/probes/footage/SOURCES.md). |
| WASB badminton precision was about 85% | **UNVERIFIED** | The figure is an eyeballed crop estimate without point-level ground truth. Do not present it as measured precision. |
| WASB tennis got 11 of 12 sampled static-view detections right | **CONFIRMED as a small manual sample; broader accuracy UNVERIFIED** | The supplied sample and local contact sheets are not a held-out sequence evaluation. The saved MPS JSON has 748 candidate detections over 900 frames, but no truth labels. [Probe output](/Users/Hetansh/Github/elevate1.0/research/probes/balltrack/out/wasb_tennis_on_artlabss_mps.json). |
| The Mendeley dataset proves badminton tracking works on phone matches | **WRONG** | The dataset page lists 884 videos and CC BY 4.0, but describes player-movement analysis. The nine clips used in this probe are single strokes, not full-match ball-tracking ground truth. Dataset size and license do not establish tracker accuracy. [Mendeley dataset record](https://data.mendeley.com/datasets/3sp4xntp34) |
| Sport-specific weights transferred to the catch clip | **WRONG for the tested sample** | The local contact sheets show no visually verified ball hits on the tested catch clip, despite many candidate points. Phrase this as “failed on this clip and configuration,” not a universal impossibility. [Catch probe](/Users/Hetansh/Github/elevate1.0/research/probes/balltrack/out_static/catch_kids_wasb_ten_std.json). |
| Static framing matters more than resolution | **WRONG as a general claim** | The tested clips vary in camera motion, sport, frame rate, view, resolution, and object size. They do not isolate camera motion from resolution. Keep fixed framing as a capture recommendation, not a universal ranking of factors. |
| A court homography recovers the ball’s airborne court position and speed | **WRONG** | A planar homography maps points on one plane. It does not recover the height or metric 3D path of an airborne ball. Use it for court-plane points such as player foot positions. Do not derive flight speed or line calls from it alone. [OpenCV homography guide](https://docs.opencv.org/5.0/tutorials/features/homography/homography.html) |
| BWF badminton changes from 3×21 to 3×15 on 4 January 2027 | **CONFIRMED for the international transition** | BWF approved the change for that date. BAI has also published domestic 3×15 implementation circulars for some age groups in 2026, so local rules need an explicit profile or user choice. [BWF announcement](https://bwfbadminton.com/news-single/2026/04/25/bwf-members-approve-3x15-scoring-system/), [BAI news archive](https://www.badmintonindia.org/beta/news/). |
| Modal is a free, four-person cloud GPU deployment | **WRONG as stated** | Starter currently includes $30/month compute and three workspace seats. GPU use requires a valid payment method. The T4 and L4 are available, but a card, seat limit, and any regional surcharge matter to the “everything free” constraint. [Modal pricing](https://modal.com/pricing), [GPU requirements](https://modal.com/docs/guide/gpu). |

## Decisions

### 1. Sports and proof boundary: **KEEP the sport choices; MODIFY their evidence labels**

Badminton remains the best primary choice because public datasets and analysis schemas are richer. ShuttleSet contains 36,492 strokes across 44 professional matches and 18 shot classes, but it is tactical stroke-level data, not proof that a pretrained detector tracks shuttles in phone footage. [ShuttleSet paper](https://arxiv.org/abs/2306.04948)

Keep table tennis as secondary and tennis as supported by configuration. Remove “WASB tennis weights as zero-shot table-tennis fallback.” No supplied evidence establishes that transfer. The table-tennis footage in the manifest is handheld, vertical, and variable-frame-rate. Tennis broadcast footage is technically useful but its manifest warns that the broadcast content remains copyrighted and is for research use. Do not use it as public demo footage without separate permission. [Footage manifest](/Users/Hetansh/Github/elevate1.0/research/probes/footage/SOURCES.md)

The college catch or bottle-hit clip can demonstrate a fresh, non-racket object passing through a user-seeded tracker. It does not prove general racket-sport performance or automatic generalization. The demo should say exactly what it shows.

### 2. Cloud person detector: **REPLACE the open candidate list with D-FINE-S COCO**

D-FINE-S is the best default for this sprint because its exact checkpoint, size, digest, and license are verifiable. Its official T4 result is close to RF-DETR-Small’s published latency while using a smaller checkpoint and fewer parameters. Neither COCO score proves detection of tiny players at the far end of a phone-recorded court.

| Candidate | Version, file, and license | Published result | T4/L4 and small-player evidence |
|---|---|---|---|
| **D-FINE-S COCO, recommended** | Official D-FINE code; no semver release verified. `dfine_s_coco.pth`, 41.8 MB, SHA256 `48a6c8cc43eb57186843f752e2e8461ddd3326e0d3c575e71e6e960844683e89`. Code repo and Hugging Face checkpoint list Apache-2.0. | 10M parameters, COCO AP 48.5, 640×640. Official latency is 3.49 ms on a T4, batch 1, FP16 TensorRT 10.4. | T4 benchmark is vendor-reported, not a Modal measurement. L4 speed and full-court small-person accuracy are **UNVERIFIED**. [D-FINE model zoo](https://github.com/Peterande/D-FINE), [checkpoint and digest](https://huggingface.co/Peterande/D-FINE/blob/main/dfine_s_coco.pth) |
| RF-DETR-Small, strongest alternative | `rfdetr==1.11.2` is the current release I verified. `model.safetensors`, 129 MB on the official Hugging Face model page. Apache-2.0 for the core code and weights. | 32.1M parameters, 53.0 COCO AP, 512×512, 3.5 ms on T4 TensorRT FP16. | Higher aggregate COCO AP, same reported T4 latency. L4 and small-player match-view accuracy are **UNVERIFIED**. [Release](https://github.com/roboflow/rf-detr/releases), [benchmarks](https://github.com/roboflow/rf-detr/blob/develop/docs/learn/run/detection.md), [model file](https://huggingface.co/Roboflow/rf-detr-small/tree/main) |
| RT-DETRv2-S | Official repo is Apache-2.0. `rtdetrv2_r18vd_120e_coco_rerun_48.1.pth`; checkpoint byte size and separate weight license are **UNVERIFIED**. | 20M parameters, 48.1 COCO AP, 640 input, 217 FPS or about 4.61 ms on T4 TensorRT FP16. | L4 and target small-player accuracy **UNVERIFIED**. [Official model table](https://github.com/lyuwenyu/RT-DETR/blob/main/rtdetrv2_pytorch/README.md) |
| YOLOX-S | Official repo is Apache-2.0. Exact first-party checkpoint byte size and independent weight terms are **UNVERIFIED**. | 9M parameters, 40.5 COCO AP, 640 input, 9.8 ms on V100 in the official model zoo. | No T4/L4 result verified for this model; small-player match-view accuracy **UNVERIFIED**. [Official YOLOX repo](https://github.com/Megvii-BaseDetection/YOLOX) |

**Proposed detector:** D-FINE-S COCO, 640 input, person class only, immutable checkpoint digest in the worker manifest. Choose it for compactness and artifact provenance, not because its court-player accuracy is established. Keep RF-DETR-Small as the accuracy challenger if the larger 129 MB weight is acceptable. Do not report either COCO AP as small-player accuracy.

### 3. Ball model and sparse detections: **MODIFY**

Keep TrackNetV3 as the deployable badminton research candidate because its official README states that both code and pretrained checkpoints are MIT-licensed. The documented files include `TrackNet_best.pt` and `InpaintNet_best.pt`; their exact bundle size is **UNVERIFIED**. TrackNetV3 uses multi-frame inputs and background estimation. It is a candidate, not a Core tracker. [TrackNetV3 README](https://github.com/qaz812345/TrackNetV3)

Keep WASB-SBDT as a local research comparison only until the exact checkpoint’s redistribution and use terms are clear. The code license is MIT, but the repo links to external checkpoint files and does not establish the terms for the measured weight file. The team-reported approximately 5.8 MB weight size and approximately 50 FPS on M4/MPS are not a verified cloud result. More importantly, the saved tennis MPS run reports 9.43 total frames/second for 900 frames. The static-phone outputs vary from about 7 to 46 frames/second across clips. Reconcile the benchmark setup before using any one speed figure. [WASB model zoo](https://github.com/nttcom/WASB-SBDT/blob/main/MODEL_ZOO.md), [WASB code license](https://github.com/nttcom/WASB-SBDT/blob/main/LICENSE.md), [saved MPS run](/Users/Hetansh/Github/elevate1.0/research/probes/balltrack/out/wasb_tennis_on_artlabss_mps.json)

**Concrete association layer, proposed and unvalidated:**

1. Preserve each heatmap candidate, confidence, source frame timestamp, and original image coordinate. Do not collapse the heatmap to one “best” point before association.
2. Build short 2D tracklets with a constant-velocity Kalman state `[x, y, vx, vy]` using real PTS deltas. Associate candidates by gated Mahalanobis distance plus heatmap score. Start a new tracklet when the gate fails.
3. Use sport-specific process noise as a configuration value, not a trained or fitted venue model. Badminton can change direction and decelerate sharply, so do not force a smooth physical trajectory through a hit or occlusion.
4. Interpolate at most one missing analysis slot for display, only between confirmed detections. Draw it dashed and exclude it from speed, contact, landing, and referee calculations. Longer gaps break the track. These are proposed guardrails, not validated thresholds.
5. Report separate track coverage, manually labelled point precision/recall, track fragmentation, and false track duration. Until a held-out evaluation exists, label the trail **Experimental**.

The strongest alternative is TrackNetV3’s richer temporal context and MIT checkpoint grant. It may produce more candidate points, but the probe’s manual precision estimate is too weak to promote it to Core. The skeptical CV question is: “What is your measured false-positive rate per rally, not the fraction of frames with a candidate?”

### 4. Free-play tap tracking: **REPLACE the SAM 2.1 / TAPIR choice list with TAPIR as the primary**

Use Google DeepMind TAPIR’s online checkpoint `causal_tapir_checkpoint.npy`, 127 MB, JAX, Apache-2.0. TAPIR’s own repo reports about 17 FPS at 480×480 on a Quadro RTX 4000. That is a published cross-hardware reference, not a T4/L4 benchmark. The model emits point tracks plus occlusion and expected-distance signals, so a point seed is a closer match to “tap the ball once” than segmentation. [TAPIR repo and benchmark](https://github.com/google-deepmind/tapnet), [official checkpoint list and file size](https://huggingface.co/google/tapnet/tree/main)

Use it on short free-play clips: freeze a frame, tap the visible red ball, upload the segment, and draw a pixel-space trail over the local original. If confidence drops or the ball disappears, stop or ask for a new tap. Do not promise automatic re-identification. No m/s, court position, or score comes from this mode.

SAM 2.1 is the strongest alternative if the UI needs a mask. Its tiny model has 38.9M parameters and a reported 91.2 FPS on A100, with Apache-2.0 code and weights. Its published speed is not a T4/L4 result, and its task is video segmentation, not point tracking. Exact checkpoint bytes are **UNVERIFIED**. A tiny, blurred ball may not yield a stable mask. [SAM 2.1 official repo](https://github.com/facebookresearch/sam2)

### 5. Court registration and low-resolution answer: **KEEP manual taps; REPLACE the “never throw away pixels” claim**

For a racket clip, four named corner taps are the right demo default. They are configuration, not training. Auto-suggested corners could reduce setup friction, but false corners silently corrupt every metric; line detection is not necessary for this demo. Add an optional line overlay later and require user confirmation.

Replace the low-resolution strategy with:

- Preserve the original video and its presentation timestamps.
- Keep the phone still on a stand or stable surface for racket footage. Treat this as a capture recommendation supported by the team’s small probe, not proof that camera motion universally matters more than resolution.
- Crop around a user-confirmed court or play region before resize. A crop only helps when it gives the model more pixels for the target. The model still resizes its input; do not say it “never throws away pixels.”
- Keep full-frame search available when a crop could exclude the object. Test the actual input shapes and crop path. The 6 px threshold, 100 px player height, 1080p60 target, and 4K30 suggestion are **unvalidated estimates**, not universal thresholds.
- For the college clip, select “Free play.” Do not require a court or display court metrics.

The frame timestamp and quality checks are valuable. The stronger alternative is automatic court fitting, but it adds a fragile dependency with little value for a clip that may have no court.

### 6. Events, speed, and referee: **REPLACE the current visual-physics claims**

A reversal in vertical image velocity is not proof of a bounce. Badminton needs a landing event, not a “bounce.” Audio can produce an event candidate, but echo and microphone distance prevent sample-rate precision from becoming event-time precision. A homography only applies to the court plane; do not use it to claim airborne speed, an impact coordinate, or an IN/OUT ruling. [OpenCV homography guide](https://docs.opencv.org/5.0/tutorials/features/homography/homography.html)

For this build, emit event candidates with timestamp, source frames, and evidence type. Let the user confirm score changes. Do not label an event “observed” simply because two heuristics agree. Keep line calls and metric speed unsupported until calibrated single-camera geometry and held-out labels validate them. `px/s` is acceptable for free play if labelled as image motion, not physical speed.

This is where a skeptical player or coach will attack first: a confident wrong score or line call breaks trust faster than a missing chart. The demo should make one trustworthy visual open its supporting clip, not generate more numbers from sparse points.

### 7. Capability matrix: **REPLACE every unsupported Core claim**

The current matrix contradicts the fundamentals doc’s definition of Core as built and working on held-out footage. The same engine version string proves version identity only. It does not prove tracking quality or two-sport support.

**Proposed capability ceiling for the demo, not a report of implemented features:**

| Mode | Court | Players | Ball/shuttle | Events and score | Speed and calls |
|---|---|---|---|---|---|
| Badminton | Assisted, four taps | Experimental until evaluated | Experimental, TrackNetV3 candidate | Candidate events, user-confirmed score | Unsupported |
| Table tennis | Assisted, four taps | Experimental until evaluated | Unsupported until a TT checkpoint passes a held-out clip | User-confirmed score | Unsupported |
| Tennis | Assisted, four taps | Experimental until evaluated | Experimental only on a tested, rights-cleared clip | Candidate events, user-confirmed score | Unsupported |
| Free play | None | Not required | Assisted after one TAPIR seed, pixel trail only | None | Pixel motion only |

Promote any cell only after recording exact clips, conditions, manual labels, model/checkpoint hash, precision/recall, and failure cases. The [fundamentals doc](/Users/Hetansh/Github/elevate1.0/docs/01-ps-fundamentals.md) should change C1’s proof from “same engine version string” to “same build, sport-specific rules/config, and held-out evidence for both sports.”

### 8. Fifty-minute cloud throughput: **MODIFY the promise; keep segmented processing**

A 50-minute 1080p60 source contains 180,000 frames. At 8-second segments, that is 375 segments without overlap, or about 400–428 windows with 0.5–1 second overlap. These counts are arithmetic, not benchmark results.

For D-FINE-S at the official 3.49 ms T4 figure, sampling players at 10–15 FPS means 30,000–45,000 images for the full video. Pure detector inference is about 105–157 seconds. That excludes decode, resizing, tracking, worker startup, and storage.

For the full ball pass, no Modal T4/L4 WASB or TrackNetV3 speed is verified. These are **throughput scenarios**, not estimates of measured performance:

| Assumed end-to-end ball throughput | Full 50-minute pass | T4 base GPU cost | L4 base GPU cost |
|---:|---:|---:|---:|
| 30 processed frames/s | 100 min | $0.984 | $1.332 |
| 60 processed frames/s | 50 min | $0.492 | $0.666 |
| 120 processed frames/s | 25 min | $0.246 | $0.333 |

Costs use Modal’s current base rates of $0.000164/s for T4 and $0.000222/s for L4. They exclude CPU, memory, startup, storage, regional pricing, and retries. L4 speeds for these models are **UNVERIFIED**. [Modal pricing](https://modal.com/pricing)

Keep the [segment-first architecture](/Users/Hetansh/Github/elevate1.0/research/out/16-cloud-streaming.md): play the gallery original immediately; upload independently readable short segments; dispatch only completed segments; overlay partial results using source timestamps; resume after a network interruption. Show truthful stages and bytes/ETA. Never show fake percentage progress. If the live clip cannot upload, cached results must be labelled cached.

## Steelman and attacks

The strongest alternative is a more automatic pipeline: detect the court, run sport-specific ball models, infer contacts, score, and show line calls. It looks like the complete PS solution and could reduce user taps. It also depends on exactly the failure points the current evidence has not validated: court fit, tiny-object detection, occlusion, bounce timing, and metric calibration.

A skeptical CV professor will ask for ground-truth precision and recall on full rallies, exact camera and resolution conditions, and how an airborne point became a court-plane coordinate. The current probes cannot answer those questions. A skeptical judge will challenge “AI referee,” “zero-shot across sports,” and “works on new venue” if the only fresh clip is a single user-tapped red ball. A real coach will ask to open the clip behind a recommendation and will distrust an event or player statistic that came from a guessed trajectory.

For the 24-hour build, the premium experience should come from local playback, clear evidence states, a polished trail overlay, and clip-linked findings. Auto-suggested corners, a complete referee, all sports, full-match 3D speed, and model-generated recommendations are too much to claim and validate in that time.

## Required edits

1. **Replace §1’s badminton evidence paragraph with:**

   > “In nine short, single-stroke badminton clips, WASB produced detections in 186 of 366 processed frame slots (50.8% coverage). This is candidate coverage, not measured accuracy. The estimated precision from visual inspection is not ground-truth precision. These clips do not establish full-rally or match performance. Badminton remains our primary sport because public tactical data is richer, not because phone-view shuttle tracking is solved.”

2. **Replace the §1 model choices with:**

   > “Badminton ball tracking: TrackNetV3 is the MIT-licensed research candidate. It remains Experimental until it passes a held-out full-rally evaluation. WASB-SBDT is a local comparison only until the exact checkpoint’s license and digest are recorded. Table tennis has no verified ball tracker in this build. Tennis ball tracking is Experimental on the exact tested, rights-cleared camera setup. Free play uses a one-point TAPIR seed and returns a pixel-space trail only.”

3. **Replace the detector candidate row in §2 with:**

   > “Players: D-FINE-S COCO, checkpoint `dfine_s_coco.pth`, SHA256 `48a6c8cc43eb57186843f752e2e8461ddd3326e0d3c575e71e6e960844683e89`. Run at 640×640, sample at 10–15 FPS, then associate person boxes with court-plane footpoints. Apache-2.0 code and checkpoint. The published 3.49 ms T4 TensorRT result is detector inference only. Court-view small-person accuracy and L4 throughput are unverified. Ask the user to confirm which player is theirs; flag ID switches.”

4. **Replace the §3 lead claim, physics row, and capture contract with:**

   > “Preserve the original frames and timestamps. Crop a user-confirmed region only when that crop gives the model more target pixels, then apply the model’s documented input resize. Do not claim that cropping or multi-frame context restores lost detail. Use a fixed camera as a capture recommendation. All pixel-size and frame-rate thresholds are provisional until evaluated on the exact phone and clip.”
   >
   > “Use image-space 2D tracklets for observed points. A court homography maps the court plane only. Do not derive airborne 3D speed or line calls from it. Interpolate no more than one missing analysis slot for display, mark it dashed, and exclude it from metrics and events.”
   >
   > “For a racket clip, recommend a stable phone and four named court-corner taps. Gallery import is the primary flow. For a clip without a court, use Free play and TAPIR with one user tap. Disable court metrics.”

5. **Replace §4’s Core cells and §5’s demo proof with the proposed capability ceiling above.** In §5 replace “proves beyond racket sports” with:

   > “A fresh college clip demonstrates one user-seeded point track on a non-racket object. It does not establish automatic cross-sport or venue generalization. The result includes the model/checkpoint hash, original timestamps, observed points, gaps, and uncertainty state.”

6. **Update related docs and license rows.** Remove the unsupported AI-referee claims in [07-ai-referee.md](/Users/Hetansh/Github/elevate1.0/docs/07-ai-referee.md); make line calls and physical speed Unsupported for the demo. Change [05-app-experience.md](/Users/Hetansh/Github/elevate1.0/docs/05-app-experience.md) from auto-suggested court corners to four user taps as the demo path. Correct missing `03-architecture.md` and `03-architecture-and-streaming.md` references; link the current [cloud-streaming report](/Users/Hetansh/Github/elevate1.0/research/out/16-cloud-streaming.md) until a single architecture document exists. In the license table, record model-code and checkpoint licenses separately, exact weight filenames, hashes, and bytes. Mark any unknown weight terms **UNVERIFIED**.

## Recommendation

Build the Android gallery flow around immediate local playback and resumable, segment-by-segment cloud analysis. Use D-FINE-S for player boxes, TrackNetV3 only as an Experimental badminton candidate after confirming its files, and TAPIR for a fresh, short free-play clip. Use manual court taps. Ship no automatic line calls or physical speed claims. Make each visual open the clip and label observed, interpolated, and user-confirmed evidence distinctly.

## 3 biggest risks

1. **Tracking evidence is too narrow.** Single-stroke clips and an unlabelled static-tennis sample do not establish full-match performance or handheld-phone performance.
2. **The fresh demo depends on connectivity and account setup.** Modal requires a payment method and its free plan has three seats for a four-person team; no end-to-end T4/L4 throughput has been measured.
3. **Overclaiming hurts trust.** Airborne geometry, guessed scoring, unverified model weights, and AI line calls can make a polished demo look inaccurate.

## Open questions only the team can answer

1. Which rights-cleared racket-sport clip will be the live analysis example, given the current candidate clips’ resolution, camera view, and reuse limits?
2. Can one person own the Modal deployment, and is a payment method acceptable under the team’s “everything free” constraint?
3. Which badminton rules profile should the app default to for the intended users: casual rules, the applicable BAI format, or a user-selected ruleset?
4. Can the college ball demo use a stable phone placement and a visible, high-contrast ball so the TAPIR seed is reliable?