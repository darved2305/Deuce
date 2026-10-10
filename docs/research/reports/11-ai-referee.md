# Track 11: AI judge/referee from one phone

## Verdict

Build a **post-game referee assistant**: the player selects a video, challenges a point, and sees the bounce frames, a court map, an uncertainty area, and an **IN / OUT / TOO CLOSE TO CALL** result. A player confirms the point before the score changes.

Use **table tennis for the live demo** if a table is available. It has the strongest match between a compact playing surface, published video-analysis research, and a clear review flow. Support badminton and tennis through rule profiles, but mark their camera decisions experimental until tested on your own phones and footage.

A single phone can provide useful evidence for clear bounces. The evidence does not support promising an official referee, reliable close calls from any angle, or full-match offline Android analysis today.

## 1. Decisions and one-camera feasibility

The ratings below are engineering estimates. They depend on a fixed camera, visible ball or shuttle, court calibration, frame rate, and unobstructed footage.

| Sport | Decisions and observability from one phone |
|---|---|
| **Tennis** | **Bounce and line:** a post-game review can help when the ball and court are clear. A low camera behind one baseline has poor far-court depth resolution; a side view has poor resolution across the court’s width. **Service let/net:** possible only when the net and landing are visible; a single view may miss contact. **Foot fault:** low confidence unless both feet and the baseline are visible at the instant of service. **Score/server/side:** reliable after a player confirms the point and match setup. Tennis rules count a ball touching any part of a line as in. [ITF Rules of Tennis, 2026](https://www.itftennis.com/media/7221/2026-rules-of-tennis-english.pdf) |
| **Badminton** | **Shuttle landing:** possible to review obvious in/out cases when the shuttle is visible; close calls are poor because the shuttle is small, fast, and often occluded. **Service height and foot faults:** weak from one ordinary view. BWF rules require the whole shuttle below **1.15 m** when struck, and some part of both server’s and receiver’s feet to remain in contact with the court in a stationary position during service. Height needs a calibrated 3D reference. **Net faults, lets, and touches:** often hidden or dependent on context. **Score/server/side:** reliable after confirmation and correct singles/doubles setup. BWF’s official Instant Review System is challenge based and can return **IN, OUT, or NO DECISION**. [BWF Laws](https://system.bwfbadminton.com/documents/folder_1_81/Statutes/CHAPTER-4---RULES-OF-THE-GAME/SECTION%204.1-%20Laws%20of%20Badminton.pdf), [BWF Instructions to Technical Officials](https://system.bwfbadminton.com/documents/folder_1_81/Statutes/CHAPTER-4---RULES-OF-THE-GAME/Section%204.1.1%20-%20Instructions%20to%20Technical%20Officials%20%28ITTO%29.pdf) |
| **Table tennis** | **Table bounce and edge:** a fixed view of the table makes bounce review a good first target. The ITTF defines the playing surface as the top of the table; the vertical sides do not count. A camera must distinguish a top-surface contact from a side contact. **Net service let:** may be reviewable if net and ball remain visible. **Double bounce and obstruction:** often occluded by players or hands. **Service legality:** a normal view may not show the required upward toss of at least **16 cm**, whether the ball was hidden, or whether it was above and behind the end line. **Score/server/side:** reliable after confirmation and correct singles/doubles setup. [ITTF Statutes, 2026](https://documents.ittf.sport/sites/default/files/public/2026-02/2026_Statutes_v1_consolidated_clean.pdf) |

The practical split is: **camera evidence for visible bounces, deterministic rules for score and service order, human confirmation for the point result**. A rule engine can track server and side accurately after setup; computer vision does not need to infer them from every frame.

## 2. Current officiating systems and benchmarks

### Tennis

The current ITF classified ELC list includes Hawk-Eye, Foxtenn, and Bolt6 at Gold, and PlayReplay and Zenniz at Silver. Classification applies to particular court settings, surfaces, and review or real-time operation. The ITF procedure includes accuracy tests, choreographed play, match play, shadow operation, live use, and monitoring. [ITF classified ELC systems](https://www.itftennis.com/en/about-us/tennis-tech/classified-elc-systems/), [ITF ELC Evaluation Procedures, revision 28.1, January 2026](https://www.itftennis.com/media/15990/elc-evaluation-procedures-v281-jan-2026.pdf)

The current Gold criteria require 100% correct decisions for impacts from 100 mm in to 100 mm out, within a ±5 mm tolerance; average discrepancy per line must be no more than 5 mm, and maximum discrepancy no more than 10 mm. Silver criteria require 80% success within 25 mm either side of a line, and 95% from 25–100 mm. For live calls, the procedure gives a Gold response target of 0.15 ± 0.05 seconds and 0.3 ± 0.2 seconds for Silver and Bronze. These are system classification requirements, not performance figures for a phone app. [ITF ELC Evaluation Procedures](https://www.itftennis.com/media/15990/elc-evaluation-procedures-v281-jan-2026.pdf)

Hawk-Eye’s published description uses footage from multiple cameras around the court to estimate ball trajectory and impact location. Sony describes the review being replayed as a court visualization. [Sony, Hawk-Eye overview](https://www.sony.com/en/SonyInfo/technology/stories/entries/Hawk-Eye/)

### Badminton

BWF rules define player challenges of line calls when an Instant Review System is operating, including an **IN / OUT / NO DECISION** result. That is a useful design precedent for abstention. [BWF Instructions to Technical Officials](https://system.bwfbadminton.com/documents/folder_1_81/Statutes/CHAPTER-4---RULES-OF-THE-GAME/Section%204.1.1%20-%20Instructions%20to%20Technical%20Officials%20%28ITTO%29.pdf)

A Stupa Sports announcement says its IRS received BWF homologation and was deployed at a 2026 tournament; it describes high-speed cameras. This is **vendor-reported**. I did not verify a public BWF test protocol or independent accuracy report for that system. [Stupa Sports announcement](https://www.linkedin.com/posts/stupasports_badminton-sportstech-aiinsports-activity-7437028517332135936-jQuV)

### Table tennis

The 2026 ITTF Statutes permit Table Tennis Review (TTR) for appeals on questions of fact, with the final decision made by a TTR official. The ITTF announced an enhanced TTR deployment at the 2025 World Cup, including reviews for edge balls, service net contacts, and service legality. That tournament announcement does not establish the exact setup at every current event. [ITTF Statutes, 2026](https://documents.ittf.sport/sites/default/files/public/2026-02/2026_Statutes_v1_consolidated_clean.pdf), [ITTF TTR announcement](https://www.ittf.com/2025/04/13/enhanced-table-tennis-review-system-to-feature-at-ittf-mens-and-womens-world-cup-macao-2025/)

### Consumer phone app: SwingVision

SwingVision says its line calls are **97% accurate for close calls within 10 cm of a line**, with an ideal setup and 60 fps recording. This is the company’s claim; I did not verify independent peer-reviewed line-call validation. Its published import guidance recommends 1080p at 60 fps, a stable camera at least five feet above the ground, and both baselines in view. [SwingVision accuracy and setup](https://swing.vision/subscribe), [SwingVision import guide](https://swing.vision/guides/import-existing-footage/1000)

SwingVision’s ITF PAT certificate is for iOS version 11.9.6, issued in October 2025. It describes a full-court iPhone or iPad setup and an optional tablet interface for scorekeeping and challenges of the last four impacts. PAT approval is distinct from ELC classification; SwingVision does not appear on the current ITF classified ELC list. [ITF PAT certificate](https://www.itftennis.com/media/15293/pat-approval-certificate-swingvision.pdf), [ITF classified ELC systems](https://www.itftennis.com/en/about-us/tennis-tech/classified-elc-systems/)

SwingVision says it remains iOS-only as of September 2026. It can import Android footage through its Mac or iPad app. Its published free plan includes 8 recording hours per month, access to recordings for seven days, highlight exports up to five minutes, occasional ads, and line-call features. It is a useful reference for the interaction, but not a native Android implementation for the team. [SwingVision Android update and current requirements](https://swing.vision/newsletters/android-update), [SwingVision free plan](https://swing.vision/newsletters/free-tier)

## 3. Single-camera research and usable models

| System | Verified result and conditions | Fit for this project |
|---|---|---|
| **RacketVision / MS-TrackNetV3** | The 2026 AAAI benchmark covers badminton, tennis, and table tennis. Its dataset contains 1,672 clips and 435,179 frames from 942 professional broadcasts; 20% of frames were manually annotated. At 1920×1080, the four-frame multi-sport model with background modeling reports ball-tracking MDE of **3.41 px for table tennis, 1.96 px for tennis, and 1.54 px for badminton**. These are pixel tracking errors, not line-call accuracy or centimetres on a phone court. [AAAI 2026 paper](https://ojs.aaai.org/index.php/AAAI/article/download/37362/41324) | Best starting research candidate for the three selected sports. The benchmark is broadcast footage, so phone-angle generalization is **UNVERIFIED**. |
| **RacketVision files and environment** | The repository lists Python 3.10, PyTorch 2.1.2, torchvision 0.16.2, and a CUDA 12.1 example, with optional MMEngine/MMCV/MMDetection/MMPose dependencies. The repository code is MIT licensed. The Hugging Face checkpoint directory is 617 MB total; its ball tracker file `balltrack_best.pth` is 46.6 MB, racket pose `best_PCK_epoch_90.pth` is 107 MB, and sport trajectory predictors are about 17.3–17.6 MB each. The Hub marks the `.pth` files as unsafe pickle objects. **Checkpoint license: UNVERIFIED. Android runtime and speed: UNVERIFIED.** [RacketVision README](https://github.com/OrcustD/RacketVision), [code license](https://github.com/OrcustD/RacketVision/blob/main/LICENSE), [checkpoint files](https://huggingface.co/linfeng302/RacketVision-Models/tree/main/checkpoints) | Use these version pins as the authors’ environment, not as a claim that they are the latest versions. Do not bundle or ship the checkpoints until their license is verified. The paper and repo do not establish Android performance. |
| **TTNet** | The 2020 paper reports a 120 fps table-tennis dataset, 97.0% event-spotting accuracy, 2 px ball-detection RMSE with 97.5% accuracy on its test data, and under 6 ms per input tensor on a consumer-grade GPU. Those results are not an Android benchmark. [TTNet paper](https://arxiv.org/abs/2004.09927) | Strong table-tennis research baseline. **Official deployable checkpoint, checkpoint license, and Android performance: UNVERIFIED.** |
| **MonoTrack** | This monocular badminton system reconstructs 3D shuttle trajectories from broadcast-style video using court dimensions, player pose, shuttle tracking, shot placement, and motion physics. Its modified shuttle detector has 2.9M parameters and reports 88.6% accuracy, compared with 84.0% for TrackNetV2. The current repo instructs users to train HitNet. Its license is Adobe Research License for noncommercial research use. [MonoTrack paper](https://openaccess.thecvf.com/content/CVPR2022W/CVSports/papers/Liu_MonoTrack_Shuttle_Trajectory_Reconstruction_From_Monocular_Badminton_Video_CVPRW_2022_paper.pdf), [repo and license](https://github.com/jhwang7628/monotrack) | Useful proof that one-view 3D estimates can be inferred with court and physics priors. It is not an out-of-box product dependency under the no-training requirement, and the license restricts use to noncommercial research. |
| **Monocular tennis ELC prototype** | A 2021 paper reports 99.4% across its 394 samples, but only 81.8% on its 11 bounce samples. The bounce subset is too small to establish reliable line calling. [Paper abstract](https://arxiv.org/abs/2107.09255) | Treat it as a feasibility proof, not a credible accuracy benchmark. |

**Verified:** single-view research can track balls or shuttles and can estimate trajectories in constrained video.  
**UNVERIFIED:** that the cited weights work on ordinary Android match footage, run quickly enough on the team’s phone, or produce calibrated line-call decisions. The papers’ tracking metrics do not settle those questions.

## 4. Uncertainty model

A player should see an uncertainty area on the court, not a bare confidence percentage.

For a point on a flat playing surface, a pinhole camera approximation gives image scale along the camera’s viewing direction:

\[
s(d)=\frac{f_y h}{(d\cos\theta+h\sin\theta)^2}
\]

Here `h` is camera height, `θ` is downward camera tilt, `d` is ground distance from the camera, and `f_y` is focal length in pixels. If the ball position is uncertain by `σ_px` pixels, the corresponding local ground uncertainty is approximately `σ_px / s(d)`. This maps points **on the ground plane**. A bounce still needs to be estimated from multiple frames; the visible airborne ball is not itself the ground contact point.

**Illustrative estimate, not a phone measurement:**

- Assume a 1080p phone frame with 43° vertical field of view, a 1.5 m camera height, 20° downward tilt, and a two-pixel one-sigma localization error.
- For a camera 2 m behind a tennis baseline, the near baseline maps to about 359 pixels per metre, making the pixel-only error about **6 mm**. The far baseline, 25.77 m from the camera, maps to about 3.36 pixels per metre, making the same error about **59 cm**.
- For a side view at mid-court, 18 m outside the near sideline, with 1.5 m height, 5° downward tilt, 70° horizontal field of view, and 1920 horizontal pixels, the baseline-normal direction maps to about 3.8–4.6 cm per two-pixel error. The sideline-normal direction maps to about **32 cm** per two-pixel error.

The side view improves the court-length direction and hurts the across-court direction. The rear view does the reverse.

Frame timing adds another estimate. If the bounce time is only known to the nearest frame, its quantization standard deviation is `1/(√12 × fps)`. At an assumed 10 m/s speed normal to the line, this corresponds to about **9.6 cm at 30 fps, 4.8 cm at 60 fps, and 2.4 cm at 120 fps**. Actual motion fitting may reduce this term; motion blur, dropped frames, camera shake, lens distortion, court calibration error, occlusion, and bounce fitting can increase total error.

For the 60 fps example, combining the assumed pixel and timing terms in quadrature gives roughly:

| View and line direction | Illustrative one-sigma error |
|---|---:|
| Rear view, near baseline | 4.8 cm |
| Rear view, far baseline | 59.7 cm |
| Side view, baseline direction | 6.1–6.7 cm |
| Side view, sideline direction | 32.1 cm |

These are optimistic geometry examples. They omit calibration and bounce-fit error. The assumed two-pixel error is not measured on the team’s phone, and RacketVision’s MDE cannot be substituted directly for a standard deviation.

For an estimated court point with covariance matrix `Σ` and line normal `n`, compute uncertainty normal to the line as `σₙ = √(nᵀΣn)`. Call **IN** or **OUT** only when the calibrated impact interval is fully on that side of the line. If the interval crosses the line, or the ball is hidden, return **TOO CLOSE TO CALL** or **UNOBSERVED**. For tennis, the call must account for the ball footprint and impact, not just the ball-centre pixel; the ITF notes that ball compression, speed, spin, and sliding complicate the true contact location. [ITF ELC procedures](https://www.itftennis.com/media/15990/elc-evaluation-procedures-v281-jan-2026.pdf)

## 5. Product spec, score rules, and offline split

### Review flow

1. Import a match from the phone gallery. Choose sport, singles or doubles, and the competition’s scoring profile.
2. Let the player tap four court or table corners and confirm the visible lines. This is geometric configuration, not model training.
3. Show a match timeline with rally candidates. Let the player select a disputed point or scrub to the relevant time.
4. Show three pieces of evidence together: the video before/at/after bounce, a top-down court map with the estimated landing point, and an uncertainty ellipse. Display **IN / OUT / TOO CLOSE TO CALL / UNOBSERVED**.
5. Ask the player to confirm or correct the point winner. Then update score, server, and side through the rules engine. Preserve the evidence and correction history.

Use “model estimate” and “player confirmed” as separate records. Do not silently convert a model call into a score.

### Rules engine

Keep the rules engine deterministic and independent from computer vision. The vision layer may propose a bounce or event; only a confirmed winner, fault, or let changes the score.

- **Badminton:** As of 10 October 2026, the BWF Laws say best of three to 21, win by two at 20-all, and first to 30 at 29-all. The BWF’s new 3×15 format is scheduled to take effect on **4 January 2027**, so store scoring as a selectable rules profile. Track rally winner serving next, singles court by server score parity, doubles rotation, and end changes. Confirm the local competition profile rather than assuming BWF international rules. [BWF Laws](https://system.bwfbadminton.com/documents/folder_1_81/Statutes/CHAPTER-4---RULES-OF-THE-GAME/SECTION%204.1-%20Laws%20of%20Badminton.pdf), [Japan Badminton Association’s notice of the BWF effective date](https://www.badminton.or.jp/corporate/news/detail/2156)
- **Table tennis:** First to 11, win by two at 10-all; best of an odd number of games. Service changes every two points, then every point at 10-all. Doubles needs the starting server/receiver order and subsequent rotation. [ITTF Statutes, 2026](https://documents.ittf.sport/sites/default/files/public/2026-02/2026_Statutes_v1_consolidated_clean.pdf)
- **Tennis:** Standard games use love/15/30/40 and deuce; a set is normally first to six by two, with a tie-break at 6-all under the tie-break set format. Match format can be best of three or five sets, and tie-break rules affect service order and ends. Make the event format explicit during setup. [ITF Rules of Tennis, 2026](https://www.itftennis.com/media/7221/2026-rules-of-tennis-english.pdf)

### Offline and live operation

Keep video playback, court setup, evidence review, score confirmation, and rule transitions local. This makes those paths usable without venue internet.

For model processing, use a hybrid design: run the selected short review segment on-device if the chosen model is validated there; otherwise queue analysis for a server when the connection returns. Preserve local review and scorekeeping while inference is unavailable. **Full-match offline analysis on Android, model export, processing speed, heat, and battery use are all UNVERIFIED.** No free inference service or free-tier capacity is assumed here.

Live voice calls should remain experimental. A proposed target is speech within **300 ms** of a clear bounce, referencing the ITF Silver/Bronze response target. The Gold reference is 150 ± 50 ms. The team has not measured either target on Android. Emit “Out” only for a confidently clear call; use an abstention cue for uncertain or occluded cases. [ITF ELC procedures](https://www.itftennis.com/media/15990/elc-evaluation-procedures-v281-jan-2026.pdf)

### API and SDK record

Return evidence rather than an opaque verdict. A review event should include:

```text
sport, ruleset, match_id, rally_id, video_timestamp
evidence_frame_ids, court_xy, line_id, signed_distance
uncertainty_covariance, call_status, visibility_status
model_id, model_version, calibration_id, player_override
```

The score module consumes a separate confirmed event such as `confirmed_winner` or `let` and returns before/after match state. SDK and MCP consumers should receive the same evidence record. This gives the team a credible product moat: traceable calls, clear abstention, player corrections, and a persistent match record.

## 6. College demo script

Use a table-tennis table if one is available. Otherwise, tape a small rectangle on the corridor floor and describe it as a **bounce-review prototype**, not a validated sport court.

**Before the demo**

- Mount the phone on a tripod at roughly 1.5 m. Keep the table or taped boundary, ball, and likely bounce area visible.
- Record three controlled examples: clearly inside, clearly outside, and near the line or partly occluded. Use a ruler or marked tape to record the actual landing positions.
- Prefer 60 or 120 fps if the phone supports it. Save the clip locally so the demo does not depend on a QR code or venue internet.
- Use your actual inference output. Do not prefill the expected labels as if the model produced them. If the uncertainty ellipse is not calibrated, label it “approximate” and do not show a percentage.

**On stage**

1. Open the local clip, choose table tennis, and tap the four table corners.
2. Select the disputed bounce from the timeline.
3. Scrub the before, impact, and after frames.
4. Show the top-down landing estimate and uncertainty area.
5. Show a clear **IN** or **OUT** for a wide-margin example; show **TOO CLOSE TO CALL** for the near-line or occluded example.
6. Ask the player to confirm the point winner, then show the next server and score update.
7. Briefly enable airplane mode and replay the local clip and score state.

A corridor demo validates the interaction and evidence display. It does not validate regulation-court accuracy.

## 7. Claims to avoid

- “Hawk-Eye accuracy,” “ITF certified,” or “official referee.”
- “97% accurate” without stating that SwingVision reports that figure for its own ideal 60 fps setup and close calls within 10 cm.
- “Works across all racket sports,” “works on any phone,” or “works on any court without setup.”
- “No setup.” Tapping court corners is configuration; it is still required.
- “Automatically detects all service faults, lets, foot faults, or net touches.”
- “Detects a 1.15 m badminton service fault” from an uncalibrated single view.
- “One-camera 3D trajectory is measured truth.” Single-view trajectory reconstruction depends on priors and assumptions.
- “Full match runs offline on Android” or “live calls are real time” before measurement on the target phone.
- Any score update that hides whether it came from the model or a player confirmation.

## Recommendation

Build **Referee Assist** around post-game challenge review. Use table tennis for the first working bounce-review demo. Implement selectable rules profiles for table tennis, tennis, and badminton, but expose line calls only at the confidence level supported by the actual camera and test data. Keep inference optional when offline, and make confirmed scoring and evidence review work locally.

### 3 biggest risks

1. **Camera geometry and visibility:** far-court compression, small fast balls or shuttles, occlusion, and motion blur can make close calls impossible.
2. **Android deployment:** the strongest research references do not establish a mobile runtime, phone speed, full-match processing time, battery use, or thermal behavior. RacketVision checkpoint licensing is also **UNVERIFIED**.
3. **Trust and rule variation:** false certainty can damage trust, and competition scoring profiles can change, including badminton’s scheduled 2027 transition.

### Open questions only the team can answer

- Will a table-tennis table be available at the college, and which phone can record 60 or 120 fps?
- Which badminton rules profile should the first version default to: the applicable Mumbai/BAI competition profile or BWF international rules?
- Is local-only video processing a hard product requirement, or can a server process queued clips when connectivity returns?
- Who will record and ground-truth the demo bounces before the event?