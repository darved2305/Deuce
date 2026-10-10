# Person 1 engine specification: real primitives on a moving camera

> **Status: proposed reference (D-261011-HW9), not yet adversarially reviewed.** Tonight's priority is the live demo path; use this after the demo is safe.

This is the proposed implementation contract for `@VrajVed`. It extends the current thin
TrackNet path toward Phases 2–3. It is a plan, not proof that the pipeline runs. Existing code is
identified as implemented; every new threshold below is an initial development setting. Tune
only on development clips. Never tune on held-out clips or labels.

## 1. Current engine boundary

The engine is Python 3.12 on Modal, PyAV 19.0.1, Modal SDK 1.6.1, NumPy, SciPy, Pillow, and
PyTorch 2.14.1 in the GPU group. The current GPU class is `Models` with `gpu="T4"`, `region="ap"`,
`cpu=4`, and `timeout=1800`; `@modal.enter()` loads verified weights. The current `analyze()` is
a CPU stub. `Models.track()` decodes the entire requested range into a list and returns raw
TrackNet candidates. It does not emit the full event/window stream. `video.frames_in_range()`
raises for nonzero `frame.rotation`.

Existing Modal functions and local entrypoints:

| Name | Current purpose | CLI or boundary |
|---|---|---|
| `audit_clip(name)` | Read stream metadata and packet PTS on CPU | `modal run -m elevate_engine.modal_app::main --clip <name>` |
| `fetch_weights(force=False)` | Fetch manifest assets and verify byte count and SHA256 | `modal run -m elevate_engine.modal_app::fetch_weights` |
| `check_models()` | Load checkpoints on T4 and report runtime/model descriptors | `modal run -m elevate_engine.modal_app::check_models` |
| `Models.track(clip, start_ms, end_ms)` | TrackNet candidates for a decoded range | Remote method used by `track_clip` |
| `track_clip(clip, start_s, end_s, out="")` | Run `Models.track`; optionally write JSON | `modal run -m elevate_engine.modal_app::track_clip --clip <name> --start-s <s> --end-s <s> --out <path>` |
| `submit(request)` | Authenticated Modal endpoint; currently spawns the stub `analyze` | Person 3 dispatches to the configured endpoint |
| `analyze(analysis_id, r2_key, session_spec)` | Stub today; becomes the ordered real analysis job | Internal Modal call from `submit` |
| `method_check.fetch_stdata()` | Fetch and verify the official test split into the eval volume | From `engine/`: `modal run eval/method_check.py::fetch_stdata` |
| `method_check.prepare()` | Check out the pinned upstream repository and prepare the eval data | From `engine/`: `modal run eval/method_check.py::prepare` |
| `method_check.run_official(mode)` | Run upstream TrackNet official evaluation, `full` or `tracknet` | From `engine/`: `modal run eval/method_check.py::run_official --mode tracknet` |
| `method_check.run_port(max_rallies=0)` | Compare the port frame by frame with upstream prediction | From `engine/`: `modal run eval/method_check.py::run_port`; run `run_official` first |
| `method_check.run_predict(clip)` | Upstream full-frame prediction for visual comparison | From `engine/`: `modal run eval/method_check.py::run_predict --clip <name>`; saves real-frame output to the evaluation volume |

The deployed boundary remains Person 3's internal API. Person 1 writes R2 objects and calls
`POST /internal/analyses/{id}/events`, `POST /internal/analyses/{id}/artifacts`, and
`POST /internal/analyses/{id}/finalize`. Person 1 never writes Postgres, assigns event `seq`,
sends Broadcasts, or confirms score. Use `window_result.v1` as frozen in `packages/contracts/src/window_result.ts`.

The current PR #22 method result matched the upstream output on 12,656 of 12,656 `stdata_test`
frames. That is method fidelity, not phone-video proof. PR #24 reports P 71.5%, R 82.2%, and F1
76.5% on the real `h13g_singles` hand labels. Its contact sheet shows repeated back-wall false
positives. The wider run and the full event pipeline remain `UNVERIFIED` in the supplied report.
Use the reports `research/out/plan-context/pr22-person1.md` and `pr24-person1.md` for provenance.

## 2. Shared conventions and data structures

All time is source presentation time relative to the first presented video frame. Preserve integer
`frame.pts` and `stream.time_base` internally; convert to contract `t_ms` only at the boundary.
Window ranges use microseconds. Never generate a timestamp from nominal FPS when real PTS exists.

Proposed internal records, which do not alter public JSON by themselves:

```text
FramePacket {
  frame_index: int, pts: int, time_base_num: int, time_base_den: int,
  t_ms: int, t_us: int, width_px: int, height_px: int,
  rotation_deg: int, rgb: uint8[height,width,3], valid_mask: bool[height,width],
  color_trc: string|null, transfer_status: "sdr"|"unsupported_hdr"|"unknown"
}

CameraFrame {
  frame_index: int, t_ms: int, source_to_stabilized_3x3: float[3,3],
  method: "identity"|"klt"|"orb_reanchor", status: string,
  candidate_count: int, inlier_count: int, inlier_ratio: float, reprojection_median_px: float|null,
  reprojection_p95_px: float|null, spatial_coverage: float,
  valid_fraction: float, path_confidence: float|null
}

TrackletState {
  track_id: str, state_xyvxvy: float[4], covariance: float[4,4],
  last_t_us: int, last_observed_t_ms: int, missed_slots: int,
  points: list[ObservedPoint], status: string
}

ObservedPoint {
  t_ms: int, x_px: float, y_px: float, confidence: float|null,
  state: "observed"|"candidate"|"inferred", evidence_frame_index: int
}
```

`confidence` is a model score or a named heuristic unless calibrated on independent real labels.
It is not a probability by default. `state="inferred"` marks interpolation. Keep source-image
coordinates for `window_result.v1.ball`; keep any stabilized geometry in `timeline.v1` and
`camera_path.v1`. Person 4 must approve both sidecar schemas in a separate contract PR before
they are emitted.

## 3. Ordered stages

### Stage 0. Decode and audit

**Algorithm and calls.** Open the original once with `av.open(path)`, select
`container.streams.video[0]`, preserve `stream.time_base`, and decode through
`container.demux(video=video_stream, audio=audio_stream)` or `container.decode(video=0)` while
processing the same input stream. Use `frame.pts`, not `frame.dts` or `frame.index`, to build
presentation order. The existing `video.first_pts()`, `video.frames_in_range()`,
`video.source_size()`, and `audit.probe()` are the current baseline. `frames_in_range()` seeks
backward to a keyframe and decodes forward; the full job should replace repeated opens/range lists
with one sequential decoder.

Run `audit.audit_pts()` over demuxed PTS. Preserve its initial rules: `LONG_FACTOR=1.5`,
`BASE_PERCENTILE=0.10`, and `VFR_TOLERANCE=0.10`. It reports frame count, duration, nominal step,
step p05/p95, `vfr`, gaps, slow segments, and duplicate PTS. A packet without PTS marks
`timing_reliable=false`. `frame.rotation` is currently rejected. The new path must normalize
supported right-angle rotations with a verified image rotation and rotate the coordinate frame
before inference; until implemented, fail the analysis visibly instead of using wrong geometry.
`frame.to_ndarray(format="rgb24")` is the current SDR conversion. HDR transfer handling is not
implemented; preserve the original and set capability unavailable if no verified HLG-to-display
mapping is in the pipeline.

**Initial parameters.** Do not drop or synthesize frames. Retain source PTS exactly. Keep
`stream.thread_type="AUTO"`, as in `video.frames_in_range()`. VFR/gap thresholds are the existing
`audit.py` constants above, not new product thresholds. Any conversion from source color to RGB
must record the transfer metadata and the conversion path.

**Structures and outputs.** One `FramePacket` per decoded video frame, in PTS order. Per frame:
`frame_index`, original `pts`, rational `time_base`, `t_ms`, `t_us`, source dimensions, rotation,
decoded RGB, valid-pixel mask, and color-transfer status. Per window: audit summary and
`window_result.v1.quality.camera_motion_px` and `blur_index` are required numeric fields in the
current strict schema, but the engine code does not yet define their measurement. Person 1 and
Person 4 must settle their formulas or make unavailable values explicit in a separate contract
amendment before the first valid full window. Never put a placeholder scalar in either field.

**Failure and confidence.** Missing PTS, nonmonotonic presentation order that cannot be
reconciled, too few frames, unsupported rotation, corrupt packets, and unsupported HDR are
visible failures or withheld capabilities. Do not substitute average FPS. `audit.timing_reliable`
is a validity flag, not a confidence score.

**Regression versus real proof.** Extend `engine/tests/test_video.py` and
`engine/tests/test_audit.py` with deterministic PTS fixtures for missing PTS, rotated metadata,
VFR, duplicates, and gaps. `engine/tests/test_audit.py` already covers real EuroGames PTS and a
real-clip probe behind `ELEVATE_TEST_CLIP`; that is regression/evidence for the auditor only. Real
proof is a Modal `audit_clip` report and a decoded-frame manifest for each required phone source.
The proposed tests do not prove rotation, HDR, or phone behavior.

### Stage 1. Always-on camera path

**Algorithm and calls.** Stabilization runs on every frame. A stationary camera is the identity
path, not a skipped or feature-gated stage. Pick the first frame with usable court support as the
stable reference. On half-resolution grayscale images, mask to the user-tapped or detected court
region. Seed corners with `cv.goodFeaturesToTrack`; track them forward and backward using
`cv.calcOpticalFlowPyrLK`. Reject failed tracks and forward/backward disagreement. Estimate a
frame-to-frame homography with
`cv.findHomography(current_points, previous_points, cv.RANSAC, 1.0, maxIters=2000,
confidence=0.995)`. This maps current-frame pixels to previous-frame pixels. Compose transforms
as `H_ref_from_current = H_ref_from_previous @ H_previous_from_current`, so
`source_to_stabilized_3x3` always maps current source pixels into the stable reference. Re-anchor weak KLT tracks
with `cv.ORB_create(nfeatures=1500, scaleFactor=1.2, nlevels=8)`,
`cv.BFMatcher.create(cv.NORM_HAMMING)`, `knnMatch(k=2)`, a 0.75 Lowe ratio filter, and stable
court-line correspondences. Accumulate transforms into the stable reference. Smooth
projected court corners or control points, not the nine homography coefficients.

When the measured global motion remains within the static classifier, still write a per-frame
record with the exact identity matrix and `method="identity"`. Initial static classifier settings
are at most 0.25 848-equivalent pixels per frame and 2 pixels per one-second block; re-anchor
every 0.5–1 second or sooner when support degrades. These are S1 development settings, not
validated thresholds. Initial optical-flow settings are `maxCorners=500`, `qualityLevel=0.01`,
`minDistance=8`, `blockSize=7`, `winSize=(21,21)`, and `maxLevel=3`; initial forward/backward
error gate is 1.5 processing pixels. These feature settings are proposals and must be tuned only
on dev clips. OpenCV 4.13 API signatures were checked, but `engine/pyproject.toml` does not pin an
OpenCV package. The runtime package and all exact OpenCV parameter behavior remain `UNVERIFIED`
until Person 1 pins and checks them.

For robust fits, start with at least 30 inliers, inlier ratio at least 0.25, and median reprojection
error at most 1 pixel at the 848-equivalent processing scale. Start `findHomography` at RANSAC
reprojection threshold 1.0 pixel, `maxIters=2000`, and `confidence=0.995`. Require at least 80%
valid court overlap for a usable stabilized crop. S1 proposed these inlier and overlap starting
values; the Roadmap lists 85% valid court area as a stricter target. Keep both as development
criteria until the team lead chooses the acceptance value. These numbers are targets, not measured
engine results. Re-anchor every 0.5–1 second. (S1; Roadmap §2; OpenCV 4.13 [feature tracking],
[ORB], and [calib3d] docs.)

**Structures and outputs.** Per frame, emit `CameraFrame` with `source_to_stabilized_3x3`,
`method`, `status`, `candidate_count`, `inlier_count`, `inlier_ratio`, `reprojection_median_px`,
`reprojection_p95_px`, `spatial_coverage`, `valid_fraction`, and heuristic `path_confidence`.
`source_to_stabilized_3x3` maps a source pixel
into stable-reference pixels. Per window, emit summary residuals, valid-area fraction, identity
fraction, failed-frame fraction, and `camera_motion_px` only according to a definition approved
for the existing quality field. Full rows go to `camera_path.v1` at
`analysis/{analysis_id}/{engine_version}/tracks/camera_path.json` after Person 4 lands the
contract. `window_result.v1` stays unchanged.

**Failure and confidence.** Reject NaN, singular, noninvertible, spatially concentrated, or
high-residual transforms. A weak frame gets `status="low_confidence"` or `status="failed"`, no
fresh transform, and no downstream stabilized metric. Do not copy the previous transform and
claim it as a new observation. `path_confidence` is a heuristic until calibrated. A planar
homography cannot remove rolling shutter, parallax, radial distortion, or independent player
motion.

**Regression versus real proof.** Add unit tests for identity, known synthetic matrices, fit
outliers, singular matrices, low inlier count, and accumulated transform direction. Tests use
numeric fixtures only and are not product proof. Real proof requires a fixed real clip for the
identity case and at least one rights-cleared real handheld development clip with held-out court
points. Report transform residual p50/p95, valid court area, frame failure fraction, drift at
start/middle/end, and CPU/memory. Never tune on held-out points. (S1; Roadmap §4; PR #24 is only
the current static-candidate baseline.)

### Stage 2. Court model

**Algorithm and calls.** Begin with the existing four named taps
`near_left`, `near_right`, `far_right`, `far_left` from `session_spec.calibration_input`. Map those
points to the matching `court_templates` geometry in stable-reference coordinates. Use
`cv.getPerspectiveTransform` with stable-reference image points as source and court-metre points
as destination. This produces an image-to-surface `H`, matching the computation in
`docs/plan/07-ai-referee.md` §3. Use `cv.perspectiveTransform` to map points. Build a line support
mask from `cv.Canny` and `cv.HoughLinesP`; compare
`cv.createLineSegmentDetector` as a separate dev option. Cluster line orientations and intersect
only supported court lines. Refine stable-frame-to-court correspondences with
`cv.findHomography(..., cv.RANSAC, ...)`. Never fill an unseen line because it appears in a
standard court template. The current plan permits court metres only when the selected template,
format, and visible scale are constrained. `calibration.H` maps stable-reference image pixels to
court metres. Use its inverse only to draw court geometry back onto an image.

Initial `Canny` values are low/high thresholds 50/150 with aperture 3. Initial Hough values follow
the OpenCV tutorial: `rho=1`, `theta=pi/180`, `threshold=50`, `minLineLength=50`,
`maxLineGap=10`, scaled to the processing image. Start homography refinement at a 1-pixel
processing-scale RANSAC threshold and the same inlier support gate as Stage 1. These are
development settings; the OpenCV tutorial also documents a 3-pixel default, which is not an
accuracy claim. Tune on dev clips only. (OpenCV 4.13 [HoughLinesP], [transform], [calib3d];
`docs/plan/04-vision-engine.md` §2.)

**Structures and outputs.** Per frame, a `CourtFrame` stores supported projected line segments,
visible polygon, stable-to-source transform reference, and visibility status. Per window, update
the existing `match.v1.calibration` fields `H`, `H_cov`, `residual_px`, `visible_fraction`, and
`tier`; report the selected `template`, `plane`, and `method`. Add detailed per-frame geometry to
`timeline.v1` only after its separate contract lands. Keep court line coordinates in metres only
where the contract's court surface is actually observed and calibrated.

**Failure and confidence.** If the view is partial, the template is ambiguous, the taps are
degenerate, or the line fit is unsupported, retain user taps as `method="tap"`, lower the tier,
and withhold court metres for unobserved corners. A plausible matrix is not proof of court
visibility. `residual_px` is a residual, not a calibrated probability.

**Regression versus real proof.** Test named point ordering, forward/inverse matrix round trips,
partial court abstention, degenerate taps, and coordinate transforms in `engine/tests/`. Real
proof uses a rights-cleared full court and a real partial-court clip with independent line
intersections. Report withheld-point residual and correct tier classification. A hand-tap
round-trip test cannot prove auto court detection.

### Stage 3. Shuttle on the stabilized court crop

**Algorithm and calls.** Warp each supported source frame into the stable reference with
`cv.warpPerspective(source_to_stabilized_3x3)`. Warp a valid-pixel mask in parallel. Crop the
supported court at native stabilized resolution before resizing to TrackNet's current 512×288
input. Build the background median from valid pixels only. Preserve the current checkpoint's
sequence length from `tracknet.model.from_checkpoint`; the checked PR #22 model uses eight-frame
context. Keep `tracknet.run.windows(..., batch=16)`, ensemble predictions in presentation order,
and `tracknet.infer.blobs(..., threshold=0.5)` to retain every connected region. Map candidate
centres through the inverse stable-to-source transform before writing the existing source-pixel
ball tuple. Do not use InpaintNet to make an invisible shuttle look observed.

Current `tracknet.run.background()` uses `step=max(1,len(frames)//max_samples)` with
`max_samples=300`; the floor-stride calculation can retain more than 300 samples for some window
lengths. Correct it to select at most 300 distinct, evenly spaced valid frames. It feeds the
median plus frame sequence and produces candidates from all connected heatmap regions over 0.5.
Keep that behavior as the baseline while comparing valid-mask median behavior. The model score is
`Blob.peak`, not a calibrated probability. (Current code:
`tracknet/infer.py`, `tracknet/run.py`, `tracknet/model.py`, `modal_app.py`; PR #22 and PR #24.)

**Structures and outputs.** Preserve `window_result.v1.ball` rows as
`[t_ms, x_px, y_px, confidence|null, state]`, with source-image pixels, `confidence=Blob.peak`,
and initial `state="candidate"` until a tracking/evidence rule promotes only observed detections.
Per frame, track all `Blob {cx,cy,area,peak}` values plus candidate order internally. Per window,
report frames, frames with candidates, candidate count, ball coverage, raw-vs-stabilized precision,
recall, F1, false positives per minute, and localization error. `timeline.v1` may reference stable
geometry and tracklet IDs after its contract is approved; do not change the tuple shape locally.

**Failure and confidence.** If fewer frames than the checkpoint context are available, preserve
the current behavior of no candidates. If court support or warp validity is weak, run raw
TrackNet only as a labelled fallback and mark stabilized geometry unavailable. Keep multiple
candidates until Stage 4 resolves them. An empty candidate list is not evidence the shuttle was
absent unless the detector path and coverage are valid.

**Regression versus real proof.** Keep `engine/tests/test_tracknet.py` for official ensemble,
blob, output order, and threshold behavior. Keep `engine/tests/test_video.py` for frame-time
alignment. Add crop-boundary and valid-mask tests. Run `engine/eval/score_trail.py` against the
real hand-labelled fixture and generate a real contact sheet with `engine/eval/contact_sheet.py`.
On `h13g_singles`, PR #24 is the measured starting baseline. The proposed A/B pass target is at
least 30% fewer false positives with at most a 5 percentage-point recall loss versus that
baseline, cited to S1 §5 and the engine Roadmap §4. Report P/R/F1 and every metric denominator.
Repeat on rights-cleared handheld development footage. Do not tune on the held-out segment.

### Stage 4. Tracking layer to remove static false positives

**Algorithm and calls.** Use a constant-velocity Kalman state `[x,y,vx,vy]` in stable court-image
pixels. For `dt=(t_us-last_t_us)/1e6`, use transition matrix
`F=[[1,0,dt,0],[0,1,0,dt],[0,0,1,0],[0,0,0,1]]`, position observation matrix
`H=[[1,0,0,0],[0,1,0,0]]`, and white-acceleration process covariance
`Q=q*[[dt^4/4,0,dt^3/2,0],[0,dt^4/4,0,dt^3/2],[dt^3/2,0,dt^2,0],[0,dt^3/2,0,dt^2]]`.
Start `q=25 px^2/s^4`; initialize position covariance from the first candidate region and velocity
variance to `2500 px^2/s^2`. Set measurement covariance to diagonal `R`, with each position
variance starting at `max(1 px^2, box_width*box_height/64) / max(score,0.1)`. These are dev-only
initial settings, not validated values. Propagate covariance and update from each TrackNet
candidate. Associate candidates with a Mahalanobis gate plus a heatmap-score term. Start a new
tracklet when all assignments fail. If a candidate
repeats at the same stable background position with no consistent incoming or outgoing velocity,
accumulate a background-stationarity penalty; do not drop it on a single frame.

Initial policy: at most one missing sample may be interpolated, and only as a dashed `inferred`
point. Exclude interpolated points from events, precision/recall, coverage numerator, and referee
calls. Start the stationary-candidate test at three consecutive consistent observations and
compare the penalty gate on development clips. Position tolerance remains `UNVERIFIED` until the
dev labels are reviewed. Start with squared Mahalanobis distance `d² <= 9.21` for a two-dimensional
position residual (nominal 99% chi-square gate, not an empirical detector result). Compare
alternatives on dev clips only. No thresholds may be fit to held-out clips.
The one-slot fill rule comes from `docs/plan/04-vision-engine.md` §2; the repeated back-wall spot
is measured in PR #24.

**Structures and outputs.** One `TrackletState` per live candidate path. Per frame, emit observed
candidate rows unchanged in the contract ball tuple and an internal association record with
`track_id`, assignment cost, `status`, and `missed_slots`. Per window, summarize observed-point
coverage, track fragmentation, false-track duration, and static-rejection counts. Put diagnostics
in `timeline.v1`, not a private extension of `window_result.v1`.

**Failure and confidence.** A failed gate starts another tracklet; it does not snap the point to
the prior path. Mark ambiguous identity as `candidate`. Do not turn a persistent bright wall
feature into an observed shuttle track. Do not mark a slow real shuttle as static without
directional evidence. Confidence is the combination of detector score and association support,
not a probability until calibrated on real labels.

**Regression versus real proof.** Add deterministic tests for `dt` from irregular PTS, missing
samples, ambiguous assignments, track splits, static repeat suppression, and slow-motion control
cases. Real proof is a same-clip comparison on PR #24 labels with contact sheets before and after
the tracking layer. Report false positives by class, false positives per minute, recall loss,
track duration, and all visible labels. The PR #24 back-wall spot must no longer dominate the
candidate ranking without suppressing labelled shuttle points. Tune only on dev clips.

### Stage 5. Sampled players with D-FINE-S

**Algorithm and calls.** Run canonical model ID `dfine_s_coco` from the verified manifest, sample
frames by source PTS at an initial 10 frames per second, and resize/letterbox to the plan's 640
input while preserving the inverse scale and padding. Run the detector only after shuttle/camera
path initialization, so player motion is not mistaken for camera motion. Filter COCO `person`
boxes; compute foot point at the bottom-center of each box. Associate sampled boxes using a
ByteTrack-style high/low score pass and predicted box overlap, then carry track state across
windows. Start at IoU 0.2 and center displacement no greater than half the box diagonal. Initial
high-score pass at `score >= 0.5`, low-score recovery at `0.25 <= score < 0.5`, and reject below
0.25. Only a high-score detection can create a new identity; a low-score detection can extend an
existing predicted box match. These score cutoffs and association thresholds are proposals, not
measured. Tune only on development boxes. The exact upstream D-FINE Python invocation is not vendored or pinned in
the current `engine/` package; define an internal `predict_people(batch_rgb)` adapter and pin the
upstream revision/API before implementation. Mark that adapter `UNVERIFIED` until verified.

The source plan specifies D-FINE-S COCO, `dfine_s_coco.pth`, 640 input, and 10–15 fps sampling;
the manifest pins checkpoint bytes, SHA256, Apache-2.0 licenses, and model ID. ( `docs/plan/04-vision-engine.md` §2;
`engine/elevate_engine/models.json`; official D-FINE repository's inference entrypoints.)

**Structures and outputs.** Per sampled frame, keep raw `{t_ms,x1_px,y1_px,x2_px,y2_px,score,
class_id}` and the associated `track_id`. Per window, populate existing `window_result.v1.players`
rows exactly as `[t_ms,track_id,x1,y1,x2,y2,foot_x_m,foot_y_m,confidence]`. Set `foot_x_m` and
`foot_y_m` only when calibration is adequate; the current tuple requires numbers, so Person 4
must amend it to allow `null` or define an explicit unsupported representation before the first
uncalibrated player row is emitted. Do not silently write zero metres. Identity comes only from
the user's `session_spec.who_is_me` tap plus later confirmation; keep identity suggestion separate
from track identity.

**Failure and confidence.** Missing weights or hash mismatch fails model initialization before
serving input, as `weights.load_checkpoints()` already requires. Low-confidence boxes are omitted
from metric aggregates but remain available as candidates if the approved schema permits. On
occlusion or ID ambiguity, retain anonymous track IDs and mark identity uncertain. Do not output
metres without supported calibration or treat a detector score as probability.

**Regression versus real proof.** Add tests for letterbox inversion, foot-point calculation,
sample schedule over irregular PTS, association and ID-switch flags. Real proof needs hand-labelled
real singles and doubles frames, box precision/recall, ID switches per minute, foot-point error,
and contact sheets. Run on a real Modal T4 with the actual manifest checkpoint; COCO validation
or tests are not phone-footage proof. Tune only on dev labels.

### Stage 6. Hits and rallies

**Algorithm and calls.** Read original audio from the same PyAV container. Use
`container.demux([video_stream, audio_stream])`, decode each packet, and order audio/video frames
by their real `pts` and `time_base`. Convert audio with `AudioFrame.to_ndarray()` and preserve
`sample_rate` and channel layout. If source rates differ, use
`av.audio.resampler.AudioResampler(format="fltp", layout="mono", rate=16000)`; otherwise keep
the source rate and record it. For consecutive FFT magnitude vectors `M_t` and `M_(t-1)`, compute
positive spectral flux as
`sum(max(0, M_t[k]-M_(t-1)[k])) / (sum(M_(t-1)[k])+1e-8)`. Initial feature window 2,048 samples,
hop 512 samples, and onset threshold at local
median plus 3 median absolute deviations are development settings; exact sample-rate handling and
scaling are `UNVERIFIED` until real audio clips are inspected. An onset is a candidate cue, never
a confirmed hit.

Join an onset to (a) a shuttle velocity direction change and (b) a nearby player track. Start with
an association radius of 100 ms and a direction change of 45 degrees, as proposed in
`docs/plan/13-additional-plan.md` Person 1 A5. Emit `cues` such as `"trajectory"`, `"audio"`, and
`"player_nearby"`; do not convert cue count into a probability. Rally boundaries use the sport
pack gap rule once Person 4 lands `bwf_3x21`; until then rally start/end are candidates and the
player can merge or split them.

**Structures and outputs.** Per frame, retain candidate shuttle direction changes and player
distance in source time. Per window, append `onsets` as `[t_ms, spectral_flux_score]` and `events`
using current `windowEventSchema`: `event_id`, `type`, `t_ms`, `t_sigma_ms`, `track_id`,
`image_xy`, `cues`, and `state`. For hit candidates, `image_xy` is the observed shuttle point.
`track_id` identifies the shuttle trajectory track; player association stays as a cue until the
contract adds a role field. Finalized `match.v1.events`, `shots`, and `rallies` retain current
fields and provenance states. Emit `shots.flight_time_ms` only when both hit and landing are
observed. The current contract requires a number, so Person 4 must make it optional/null while
preserving `flight_time_state` before a shot without an observed landing can be serialized. Do not
output speed in km/h.

**Failure and confidence.** Echoes, background noise, missing audio, quiet hits, occlusion, and
missing shuttle points reduce cues. Without a supported candidate, withhold the hit. A long PTS
gap increases `t_sigma_ms`; never report sub-frame certainty. A lost track is not a landing.

**Regression versus real proof.** Add tests for spectral-flux indexing, audio/video PTS alignment,
direction change, missing audio, and candidate dedupe. Real proof needs consented real clips with
hand-labelled hit times and rally boundaries. Report hit precision/recall/F1, hit-time error in
frames, rally-boundary error, and denominator. The trail fixture alone cannot pass because it has
no hit labels. Tune only on dev clips.

### Stage 7. Events and window results

**Algorithm and calls.** Build each `window_result.v1` from only the source PTS interval in
`emit_range_us`. A window may decode context outside its emit range; it must never emit a second
copy of a frame or event from overlap. Serialize with the current generated schema. Write the exact
bytes to `analysis/{analysis_id}/{engine_version}/windows/{window_seq}.json`, then register that
object through `POST /internal/analyses/{id}/artifacts`, and post
`POST /internal/analyses/{id}/events` with `type="window.completed"`, `window_seq`,
`decode_window_us`, `emit_range_us`, and the returned `artifact_id`. Person 3 assigns `seq`.
For a failed window, send `window.failed` with required `reason` and `window_seq` after the
contract amendment makes it required. Keep retries idempotent by `(type,window_seq)`.

**Structures and outputs.** Per frame, candidate arrays remain source-time aligned. Per window,
write exact fields from `window_result.v1`: `schema`, `analysis_id`, `window_seq`,
`decode_window_us`, `emit_range_us`, `models`, `ball`, `players`, `onsets`, `events`, and
`quality.ball_coverage`, `quality.camera_motion_px`, `quality.blur_index`. Quality values must
have documented definitions and denominators in the sidecar catalog; do not calculate camera
motion as a guessed scalar. After final processing, call `/internal/analyses/{id}/finalize` with the
vision `match.v1` body and real clip artifact IDs.

**Failure and confidence.** Schema validation failure stops publication and produces a visible
analysis failure. Do not drop an invalid field, fill missing values with plausible defaults, or
write a partially shaped artifact. Treat each detector output as `candidate` unless its evidence
supports an observed state. One failed window does not become a blank success; use its explicit
failure event.

**Regression versus real proof.** Add schema serialization, overlap dedupe, cursor retry, and
source-range tests. Tests establish payload stability only. Real proof is one deployed full-engine
Modal result with output artifact links, exact event cursor rows, source clip hash, contact sheets,
and label metrics. Person 4 creates `fixture_window_0003.json` only by copying real
`window_result.v1` from this full-engine run with `window_seq=3`; the paired
`fixture_events.jsonl` comes from its real event cursor. PR #24's `track_clip` output is not
complete enough to serve either fixture.

### Stage 8. Chunked processing across segments

**Algorithm and calls.** Consume the ordered `SegmentManifest` sequence from the proposed Person 3
internal route. Its fields are `seq`, `pts_start_us`, `pts_end_us`, `starts_on_sync_sample`,
`bytes`, `sha256`, `r2_key`, and `final`; contract amendment is required before use. Process one
analysis in order. Keep a six-second active window and 0.5–1 second overlap, plus the TrackNet
context frames. Decode the first segment once; for every following segment, use `av.open()` and
`container.seek()` only when needed to start from its preceding keyframe. Reconcile each segment's
local PTS against manifest `pts_start_us`; do not assume segment-local PTS is continuous. Use
`emit_range_us` to exclude overlap output. Back-pressure the decoder when GPU inference is behind.

Carry stable-reference ID and last camera transform, court model and uncertainty, KLT reference
points, active shuttle Kalman state, sampled-player associations, background sample state, last
emitted PTS, last emitted event keys, and audio feature overlap across segment boundaries. At a
camera reframe, reset local feature tracks but preserve the original timeline and stable reference
only if the new transform can be re-anchored; otherwise start a new path epoch and withhold
cross-epoch geometry until it is reconciled. Do not silently reset a path or reuse stale state.

**Structures and outputs.** Per decoded frame, attach `segment_seq` and normalized global PTS.
Per window, preserve `decode_window_us` and `emit_range_us`; output exactly one artifact and one
`window.completed` event for each emitted window. The `final` manifest marks end-of-input only; it
does not imply analysis completion before all expected windows and Person 3 finalization.

**Failure and confidence.** Buffer out-of-order manifests until the next expected `seq`. Missing
segments wait and then fail visibly with the missing `seq`; corrupted hash or incompatible media
fails that segment. Do not bridge an unknown gap with interpolated geometry. Use whole-file
multipart as the current fallback and state why segmented processing was unavailable.

**Regression versus real proof.** Add tests for PTS offset reconciliation, segment ordering,
duplicate overlap, missing segment, reframe reset, and state carry. Real proof needs actual phone
segments from Person 2, live Person 3 registration, and a Modal result arriving before the final
segment upload completes. Compare timestamps and candidate output with the same real whole-file
run, within one source frame, and show zero duplicate events. Report latency, peak memory, CPU/GPU
time, and actual spend. Do not count a synthetic segment split as live proof.

### Stage 9. Stabilized replay

**Algorithm and calls.** This is a view artifact made from original frames. Apply the recorded
per-frame stable transform with `cv.warpPerspective`, preserve the original presentation PTS,
and pair raw and stabilized views with the same source timestamp. Draw only measured court lines
and observed/candidate shuttle points in their matching coordinate frames; use dashed drawing for
an inferred point. A side-by-side preview can use the current approved FFmpeg CLI with
`-filter_complex hstack=inputs=2` after verifying the encoder and dimensions in the deployed
Modal image. The `hstack` path and exact codec are `UNVERIFIED` until a real Modal render runs.

**Structures and outputs.** Per frame, use the original PTS plus its transform row. Per preview,
produce one real `clips/{match_id}/{clip_id}.mp4` and poster, using the existing clip provenance
schema and `/internal/analyses/{id}/artifacts`. The preview is not `camera_path.v1` or a new
measurement. No generated court, synthesized shuttle, or re-timed measurement clip.

**Failure and confidence.** If a transform is invalid, preserve the source video and show raw
overlays only. If rendering or seeking changes PTS alignment, do not register the preview. Never
describe a smoothed playback frame as observed at a different source time.

**Regression versus real proof.** Add deterministic frame-to-transform indexing and seek tests.
Real proof requires a fixed and a handheld real clip, inspection at start/middle/end and after
seeks, source-PTS overlay alignment, and playback on A55 or S23 FE. Capture the actual screen
recording and contact sheet; a generated test frame is regression evidence only.

### Stage 10. Landing estimate and referee inputs

**Algorithm and calls.** A badminton landing is an observed shuttle entering visible floor
contact, not track loss. For observed contact, first map source image coordinates through
`source_to_stabilized_3x3`, then use `cv.perspectiveTransform` with the image-to-court `H` to map
stable image coordinates to court metres. Propagate image-point,
calibration, and timing uncertainty into `cov_m2`; keep contact time uncertainty in `t_sigma_ms`.
For a possible in-flight landing, fit a local trajectory only when the observed samples, real PTS,
and court geometry constrain it. Return a conditional landing ellipse and residual. Physics
coefficients and uncertainty calibration are `UNVERIFIED`; tune only on development clips.

Compute signed distance `margin_m` to the observed court line and `sigma_m` from the projected
covariance. Apply the plan's existing `k=1.96` interval. If the interval crosses the line, return
`TOO_CLOSE`; if contact was not observed, return `UNOBSERVED`. A referee result is a `MODEL
ESTIMATE`; only a player confirmation can change the score. (`docs/plan/07-ai-referee.md` §3; contracts
§0 and §8.)

**Structures and outputs.** Per observed contact, produce a `match.v1.events` entry with
`event_id`, `type`, `t_ms`, `t_sigma_ms`, `rally_id`, `plane`, `contact_xy_m`, `cov_m2`, `cues`,
`state`, and evidence frame timestamps. Per review, use existing `reviewSchema` fields
`review_id`, `match_id`, `rally_id`, `event_id`, `t_ms`, `evidence_frames`, `contact_xy_m`,
`cov_m2`, `line_id`, `margin_m`, `sigma_m`, `estimate`, `visibility`, `model_ids`, and
`calibration_id`. Proposed landing predictions need a separately approved additive schema field;
do not encode a predicted point as observed `contact_xy_m`.

**Failure and confidence.** If contact is occluded, outside frame, calibration is partial, or
covariance cannot be supported, emit no line call. Distinguish prediction from observed contact.
Report coverage and abstentions beside any accuracy. Never optimize a threshold after inspecting
held-out calls.

**Regression versus real proof.** Add tests for homography inversion, covariance propagation,
line-side interval crossing, and abstention. Real proof requires a consented real slow-motion clip,
a measured court grid, independently labelled contact and line outcomes, a predeclared call
coverage threshold from Person 4, and zero wrong IN/OUT calls at that coverage. Report contact
error, flight-time error, coverage, and abstention separately. Do not claim referee accuracy from
`fixture_trail.json`, which has no landing-side labels.

## 4. Modal execution layout and state

Keep the existing `Models` class as the single T4 model container. Initial layout is
`gpu="T4"`, `region="ap"`, `cpu=4`, `timeout=1800`, and a proposed explicit `memory=4096` MiB
request. CPU audit/decoding, image warp, PTS alignment, and next-window preparation overlap with
GPU inference for the current window. Use one ordered job per analysis and cap concurrent analysis
containers at one for the demo path. Load verified model weights once in `@modal.enter()` and
cache the loaded TrackNet and D-FINE adapters for that container. The exact memory request is a
starting budget from S1, not a measured need. Profile peak resident memory and revise only from
real dev runs. Modal's current docs expose `memory`, `max_containers`, and `min_containers`; verify
the signatures against pinned Modal 1.6.1 before implementation.

Use a PTS-bounded ring containing the active six-second window, 0.5–1 second context overlap, and
the model's eight-frame sequence context. Start with a 512 MiB decoded-frame byte cap as a
development guard; on pressure, pause decoding and drain inference instead of dropping frames.
This cap is an engineering proposal, not a measured memory result. Track the valid background
sample without retaining every decoded frame. Use one CPU/GPU pipeline; do not spawn unbounded
per-frame futures. Keep `max_containers=1` for the demo analysis path. Set a warm container only
for an explicitly scheduled demo window, then return `min_containers=0`; no always-warm GPU.

The existing Modal estimate in the engine Roadmap is $0.000164 per T4-second, $0.0000131 per
physical CPU-core-second, and $0.00000222 per GiB-second. These are planning rates; actual region
multiplier, memory usage, and runtime remain `UNVERIFIED`. Record Modal call ID, cold/warm state,
GPU seconds, CPU seconds, requested memory, peak resident memory, and cost for every real run.
Stop before the $30 project cap in D-261010-VV1. No new model, GPU class, service, or continuous
warm pool is included.

## 5. Regression tests and real proof

Regression tests live under `engine/tests/`. Current useful tests are `test_video.py`,
`test_audit.py`, `test_tracknet.py`, `test_weights.py`, `test_clips.py`, and `test_submit.py`.
Add unit tests for each stage's deterministic logic and run the lane's requested checks when code
is implemented. These tests do not meet D-009's merge proof bar.

From `engine/`, the existing scoring/visual scripts accept these inputs:

```sh
python eval/score_trail.py <labels.json> <result.json>
python eval/contact_sheet.py <clip> <result.json> <out.jpg> --times <comma-separated-t_ms> --labels <labels.json>
```

Real proof for the engine slice includes:

- A real Modal call ID and the exact source clip SHA256.
- `audit_clip` output with source PTS and rotation/transfer status.
- `camera_path.v1` and `timeline.v1` from that same analysis after contracts land.
- `window_result.v1` artifact and exact event cursor rows from the deployed `/internal` path.
- Contact sheets over real source frames and hand labels. Use `engine/eval/contact_sheet.py` with
  source times and `engine/eval/score_trail.py` for the existing trail-label schema.
- Precision, recall, F1, localization error, false positives per minute, and the explicit labelled
  denominator. For player output, add box precision/recall, ID switches per minute, and foot-point
  error. For events, add timing and boundary error. Never report an unlabelled metric as a pass.
- A same-real-clip whole-file/segmented comparison before the segment slice is claimed complete.
- Actual Modal costs and memory from logs, under D-261010-VV1.

The first real full-engine Modal analysis on a rights-cleared development clip is the only source
for Person 4's `fixture_window_0003.json` and its paired `fixture_events.jsonl`. PR #24's
`track_clip` run contains detector candidates only and cannot produce those fixtures.

## 6. Slice list

Keep each slice to about 300 changed lines or fewer, one behavior, and one lane. The item numbers
match `docs/plan/13-additional-plan.md`.

| Slice | Tier/phase | Scope | Done when |
|---|---|---|---|
| P1-A0 audit/rotation boundary | A / Phase 2 | Single-reader PTS manifest and explicit rotation/HDR support states | Real phone file audit reports exact PTS, rotation and transfer status; unsupported input fails visibly |
| P1-A1 identity camera path | A / Phase 2 | Per-frame transform records, identity path, KLT/RANSAC and re-anchor | Real fixed clip yields identity records; handheld dev clip meets declared residual/valid-area target |
| P1-A2 court model | A / Phase 2 | Tap-initialized supported court lines and tier/abstention | Real full and partial views report supported geometry and withhold unsupported metres |
| P1-A3 stabilized TrackNet crop | A / Phase 2 | Valid-mask warp/crop, same checkpoint, side-by-side eval | Real contact sheets show no worse recall than agreed target and measured false-positive change |
| P1-A4 tracking layer | A / Phase 2 | PTS-aware Kalman tracklets and static candidate diagnostics | Back-wall spot is diagnosed; labelled shuttle recall stays within the agreed gate |
| P1-A5 sampled players | A / Phase 2 | D-FINE adapter, sampled boxes, anonymous association | Real singles/doubles clip reports box and ID metrics; no uncalibrated metre values |
| P1-A6 hits/rallies | A / Phase 2 | Audio onset and trajectory candidate join | Real labelled clip reports hit timing and rally boundary metrics; candidates remain confirmable |
| P1-A7 window/event output | A / Phase 2 | Validate/write exact `window_result.v1`, register and post events | Deployed real Modal output and cursor agree on IDs, ranges, and artifact hashes |
| P1-A8 segment state carry | A / Phase 3 | Ordered segment feed, overlap suppression and persistent state | Real phone segments produce first window early, no duplicate events, and whole-file PTS agreement |
| P1-A9 stabilized replay | A / Phase 3 | Paired source/stable real preview | Real device replay aligns after seek and segment joins |
| P1-B1 landing/referee input | B / Phase 4 | Observed contact, covariance, conditional estimate and abstention | Independent real grid labels meet a predeclared coverage gate with zero wrong IN/OUT calls |
| P1-C1 pose/contact extras | C / only if time | Licensed pose research only | License and real labels are cleared; output remains candidate and can be withheld |
| P1-C2 single-view height | C / only if time | No 3D value in the current scope | Remains unavailable unless a separate measurement design and real proof exist |

## 7. Edge cases

| Case | Required behavior | Capability / proof state |
|---|---|---|
| VFR | Use each `frame.pts`; `audit_pts()` identifies slow segments and gaps; windows are time-based | Existing audit logic is tested; full end-to-end VFR path remains `UNVERIFIED` until a real phone Modal run |
| Rotation | Normalize right-angle metadata before coordinate geometry or stop with a visible unsupported result | Current `frames_in_range()` rejects nonzero `frame.rotation`; normalization is new work |
| HDR HLG | Preserve the source; use a documented tone-map path only after real device/output comparison | `UNVERIFIED`; withhold overlay if colors/coordinates cannot be checked |
| Slow motion | Preserve source PTS and expose slower intervals; never assume nominal FPS or infer extra frames | Audit detects sustained step changes; phone slow-motion behavior remains `UNVERIFIED` |
| Portrait | Crop the supported court at native resolution before TrackNet resize; rotate into upright coordinates once | Required by `04-vision-engine.md`; real portrait badminton proof remains `UNVERIFIED` |
| Partial court | Use only observed/tapped support; lower tier and withhold hidden corners, scale, and line calls | Do not synthesize unseen court geometry |
| Doubles occlusion | Keep anonymous tracks, flag ID switches, stop metre aggregates when foot projection is hidden | Requires real hand-labelled doubles proof; not supplied by the current trail fixture |
| Lights and glare | Keep candidate regions; use temporal and background evidence; abstain if glare overlaps the shuttle path | Report false positives per minute and recall; do not erase a candidate without evidence |
| WhatsApp 848p | Use decoded source dimensions and PTS; report compression/blur quality; do not upscale claims | EuroGames 848×464 is a real low-resolution dev clip, but WhatsApp transcoding remains `UNVERIFIED` |
| Camera reframe mid-clip | Detect re-anchor failure, start a new path epoch if needed, and withhold cross-epoch metres until reconciled | No interpolation across a transform discontinuity; real reframe proof required |

## 8. Never

- Never gate the camera path off. A fixed camera emits an identity transform for every frame.
- Never use nominal FPS when real PTS is available.
- Never call `frame.dts` presentation time or silently ignore orientation.
- Never fill a missing shuttle point and count it as an observation. At most one inferred slot is
  dashed and excluded from metrics, events, and calls.
- Never drop TrackNet candidates before measuring the static-spot false-positive rule.
- Never report a confidence score as a probability without calibration and a denominator.
- Never map an airborne shuttle point to court metres using a surface homography.
- Never infer landing from track loss, use synthetic/generated footage as proof, or report a
  synthetic result as a real result.
- Never emit a line verdict that changes score. A player confirms; the display says `MODEL
  ESTIMATE` and supports `IN`, `OUT`, `TOO_CLOSE`, or `UNOBSERVED`.
- Never tune any threshold on held-out clips or labels.
- Never post directly to Postgres or Broadcast from Modal.
- Never exceed the $30 Modal ceiling, keep a GPU warm outside demo windows, or increase Cloudflare
  usage beyond D-261011-HW2's 10% auto-pause and 20% hard budget.

## 9. API references checked for this specification

The OpenCV names used above were checked against the official [OpenCV 4.13 feature tracking],
[ORB], [image transforms], and [calib3d] references. OpenCV itself is not in
`engine/pyproject.toml`, so a package pin and exact runtime binding remain `UNVERIFIED`. The
PyAV demux/decode and seek calls match the official [PyAV 19.0.0 container reference]; the repo
pins PyAV 19.0.1, so the final implementation must check the pinned version. Modal resource
fields were checked against the current official [Modal App reference]; re-check against the
project's pinned 1.6.1 before editing code. D-FINE's official repository lists its `torch_inf.py`
entrypoint, but this repo has not pinned the upstream implementation revision; the adapter stays
`UNVERIFIED` until it is.

[OpenCV 4.13 feature tracking]: https://docs.opencv.org/4.13.0/dd/d1a/group__imgproc__feature.html
[ORB]: https://docs.opencv.org/4.13.0/db/d95/classcv_1_1ORB.html
[image transforms]: https://docs.opencv.org/4.13.0/da/d54/group__imgproc__transform.html
[calib3d]: https://docs.opencv.org/4.13.0/d2/d28/calib3d_8hpp.html
[HoughLinesP]: https://docs.opencv.org/4.13.0/d9/db0/tutorial_hough_lines.html
[PyAV 19.0.0 container reference]: https://pyav.basswood.io/docs/stable/api/container.html
[PyAV audio frames and resampler]: https://pyav.basswood.io/docs/stable/api/audio.html
[Modal App reference]: https://modal.com/docs/sdk/py/latest/App
[D-FINE official repository]: https://github.com/Peterande/D-FINE
