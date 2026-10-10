# 04 · Vision engine (cloud), sports, and the low-resolution answer

Decided in debate D3 (`11-debates.md`). Probe evidence: §1 below (measured on an M4 Mac; scripts not in this repo).

## 1. Sports and what we claim

| Pack | Role | Ball tracker | Starting tier (promoted only by the held-out eval) |
|---|---|---|---|
| **Badminton** | Primary | **TrackNetV3** (MIT code + checkpoints) is the demo default; WASB-SBDT HRNet (MIT code; checkpoint terms UNVERIFIED) is a Phase 4 challenger once its terms are checked; the eval decides | Trail Experimental; court Assisted (4 taps); players Experimental; score Assisted |
| **Table tennis** | Secondary | none verified for phone footage in this build | Court (table) Assisted; score Assisted; ball Unsupported until a TT checkpoint passes a held-out clip |
| **Tennis** | Supported | WASB tennis (static elevated view only) | Trail Experimental on tested, rights-cleared setups |
| Squash, padel, pickleball | Listed | none | Unsupported until their sport packs exist (Designed) |
| **Free play** (no court) | Generalisation demo | TAPIR seeded by one tap | Pixel-space trail, Experimental |

### Measured so far (our probes, small samples, not benchmarks)

- WASB badminton on 9 single-stroke, 1–1.9 s, static, elevated clips from the Mendeley dataset
  (CC BY 4.0 at dataset level; capture device UNVERIFIED; portrait 4K/1080p, 30 fps): candidates
  in 186 of 366 processed frame slots (50.8% **candidate coverage**, not accuracy); ~85% precision
  judged **by eye** on 16 crops (not ground truth). These clips do not establish rally or match
  performance.
- TrackNetV3 on the same clips: 69% coverage, ~50% precision by eye (false positives on shoes,
  limbs, logos, crowd).
- WASB tennis on a static broadcast view: 11 of 12 sampled detections on the ball.
- WASB/TrackNetV3 on a handheld badminton clip and on a "kids playing catch" clip: unusable
  (0 hits on the catch ball). Sport-trained weights did not transfer to that footage.
- Apple Vision trajectory detection: not usable on phone footage.
- Letterboxing portrait video into the 512×288 model input shrank the shuttle to ~2 to 3 px
  and produced 0 detections; crop first.

## 2. Pipeline (per analysis; ordered windows)

| # | Stage | Method (training-free) | Output and honesty rule |
|---|---|---|---|
| 0 | Clip audit | Real PTS distribution, VFR, gaps, resolution, codec, blur and compression indicators, camera motion | `clip_quality`; lowers tiers when poor |
| 1 | Court / table | Four **named** corner taps (near-left, near-right, far-right, far-left) → homography (OpenCV) + residual; tabletop plane for TT; floor plane separately for TT players | `calibration` tier A/B/C. Homography maps **surface points only** |
| 2 | Players | **D-FINE-S COCO** (`dfine_s_coco.pth`, SHA256 `48a6c8cc…3e89`, Apache-2.0), 640 input, 10 to 15 fps sampling → ByteTrack-style association → foot point → court metres; "which one is me" tap anchors identity | ID switches flagged; person stats wait for confirmation |
| 3 | Ball / shuttle | Crop the confirmed court region at native resolution **before** the model's resize (portrait included); run the pack's tracker on 3-frame (WASB) or 8-frame (TrackNetV3) windows; keep **all** heatmap candidates | Candidates with confidence, image coords, PTS |
| 4 | Tracking layer | Constant-velocity Kalman tracklets `[x,y,vx,vy]` on real PTS deltas; gated Mahalanobis association + heatmap score; new tracklet on gate failure; sport-specific process noise (configuration); interpolate **at most one** missing slot, dashed, excluded from metrics/events/calls | Image-space trail; coverage, fragmentation, false-track duration reported |
| 5 | Audio | Spectral-flux onsets on the original audio | Event **candidates** only (echo and mic distance limit timing) |
| 6 | Events | Hit candidates: trajectory direction change near a player ± audio onset. Landing (badminton): shuttle tracked into visible floor contact (track loss is not a landing). Bounce (TT/tennis): contact estimated on the surface plane from incoming/outgoing path fit; TT side contacts excluded | Events carry `t_ms`, `t_sigma_ms`, cues, state (`candidate` unless confirmed) |
| 7 | Rallies | Sport gap thresholds over hit candidates; user can merge/split | Rally intervals |
| 8 | Score | Versioned rules engine (`@elevate/rules`, Person 4); proposed winner; user confirms | Never changes without confirmation |
| 9 | Metrics | Rally lengths, flight time (hit → landing timestamps), court coverage (calibration A), landing zones (observed landings only) | Numerator/denominator, tier, evidence IDs |
| 10 | Review estimates | `07` §3 computation for landings/bounces near lines | Model estimate + abstention |
| 11 | Clips | FFmpeg cuts per moment and rally (`12` §3), posters | Real clips with provenance |

**Free play pack:** the user freezes a frame and taps the ball; TAPIR
(`causal_tapir_checkpoint.npy`, 127 MB, Apache-2.0) tracks the point with occlusion
signals; the trail stops (and asks for a new tap) when confidence drops. Pixel-space only,
no court metrics. If a taped rectangle is visible and tapped as 4 corners, bounce contact
points on the floor can be mapped to metres (Experimental).

## 3. The low-resolution answer (mentor ask)

Phone video has fewer pixels, fewer frames per second, more compression and more blur than
the multi-camera systems pros use. Our approach, in order of real gain:

1. **Capture coach (own recorder) and capture contract (gallery):** phone still, landscape,
   1× lens, full court in frame, good light, flicker check; 1080p60 and 4K30 are target modes
   until a recorded file from each team phone is audited for real frame rate, timestamps and
   resolution.
   (Supported by our probes: static footage worked, handheld did not. Pixel and fps thresholds
   are provisional until the eval.)
2. **Timestamp audit:** use the file's real presentation timestamps; detect VFR, gaps and
   retimed slow motion before any timing metric.
3. **Crop before resize:** crop the user-confirmed court/table region at native resolution so
   the small ball occupies more of the model's input; keep a periodic full-frame pass.
4. **Temporal models + a tracking layer:** multi-frame heatmaps propose candidates; the Kalman
   layer links them and rejects implausible ones.
5. **Audio as event evidence:** racket hits and bounces are loud; onsets mark candidate times
   between frames (not sub-millisecond truth).
6. **Measured uncertainty and abstention:** pixel and timing uncertainty propagated to metres
   on the surface plane (`07`); calls and metrics withheld when the evidence is too weak.
7. **Display-only enhancement:** smoothed slow motion for viewing, labelled, never measured.

What we do not claim: that cropping or multi-frame context restores lost detail, that 3D speed
is measurable from one uncalibrated view, or any universal pixel threshold.

## 4. Capability matrix (target tiers; every status starts `unknown` and is set only by `fixtures/eval/RESULTS.md`)

| | Court | Players | Ball trail | Hits / rallies | Review estimates | Speed | Score |
|---|---|---|---|---|---|---|---|
| Badminton | Assisted | Experimental | Experimental | Assisted | Experimental | Flight time only | Assisted |
| Table tennis | Assisted | Experimental | Unsupported | Assisted (audio) | Unsupported | — | Assisted |
| Tennis | Assisted | Experimental | Experimental (static) | Assisted | Experimental | Flight time only | Assisted |
| Squash / padel / pickleball | Unsupported | Unsupported | Unsupported | Unsupported | Unsupported | — | Unsupported |
| Free play | — | — | Assisted (one tap) | — | Experimental (taped line) | px/s (image) | — |

Promotion to Core requires: named held-out clips, capture conditions, labels, checkpoint hash,
precision/recall and failure examples, recorded in `fixtures/eval/RESULTS.md`.

## 5. Proving "no training"

Model manifest in the app and API: name, source URL, checkpoint file, SHA256, bytes, code
licence, checkpoint licence (or UNVERIFIED), and the sports the authors trained it on. Wording:
"No venue-specific labels, no fine-tuning. Frozen public models, named, with what they were
trained on. Everything else is geometry, audio, rules and one-tap configuration."

## 6. Licences and rights

| Item | Status |
|---|---|
| TrackNetV3 | MIT code and checkpoints (stated in repo) |
| WASB-SBDT | MIT code; checkpoint terms UNVERIFIED (recorded in manifest) |
| D-FINE-S | Apache-2.0 code and checkpoint |
| TAPIR | Apache-2.0 |
| RacketVision checkpoints | UNVERIFIED; pickle-flagged; not used |
| Ultralytics YOLO | AGPL; not used (network service) |
| Demo footage | Team-recorded (consented) first; Mendeley clips CC BY 4.0 at dataset level but identifiable tournament players, so verify before public display; broadcast footage never |
