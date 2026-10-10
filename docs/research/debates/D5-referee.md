# D5 verdict

**Replace the 24-hour “AI referee” feature with an evidence-first bounce or landing review that gives an experimental estimate and asks the player to confirm the point.** Keep `IN`, `OUT`, `TOO CLOSE TO CALL`, and `UNOBSERVED` as useful outcomes, but the proposed math does not yet support a calibrated 95% call, and the current probes do not validate bounce or line-call accuracy.

| Decision | Verdict | Reason |
|---|---|---|
| Evidence replay and player confirmation | **KEEP** | It makes the output reviewable and keeps the official point separate from the model’s estimate. |
| Automatic line calls | **MODIFY** | Show a model estimate for clearly observed events. Abstain when the event or contact point is uncertain. Do not claim calibrated accuracy yet. |
| Current uncertainty formula | **REPLACE** | It does not specify homography parameter uncertainty, event-time covariance, or how the contact footprint enters. |
| Bounce/landing detection | **REPLACE** | Shuttle landing and ball bounce are different events. Image-vertical reversal, audio, and track loss are not sufficient grounds for a call. |
| College corridor demo | **MODIFY** | A taped-line bounce review is buildable as a prototype. It does not validate badminton, table tennis, or tennis officiating. |
| Live referee mode | **CUT from the 24-hour scope** | It adds capture, upload, inference, and latency failure modes without validated calls. Keep it as future work after post-game review is tested. |
| Verdict presentation | **KEEP, with changes** | Make the replay dramatic. Keep the evidence and uncertainty visible, and label the result as an estimate. |

## Verified claim ledger

| Draft claim or assumption | Status | Finding |
|---|---|---|
| BWF’s Instant Review System uses `IN`, `OUT`, and `NO DECISION` | **CONFIRMED** | The BWF technical instructions use those outcomes. They also say the line judge waits until the shuttle touches the floor before calling. This supports the draft’s review states and the shuttle-landing event model. [BWF Instructions to Technical Officials](https://system.bwfbadminton.com/documents/folder_1_81/Statutes/CHAPTER-4---RULES-OF-THE-GAME/Section%204.1.1%20-%20Instructions%20to%20Technical%20Officials%20%28ITTO%29%20-1.pdf) |
| Badminton should be detected as a bounce | **WRONG** | Badminton’s event is the shuttle’s landing. There is no ball-bounce reversal to detect. A disappearing track alone is not proof of landing. [BWF Instructions to Technical Officials](https://system.bwfbadminton.com/documents/folder_1_81/Statutes/CHAPTER-4---RULES-OF-THE-GAME/Section%204.1.1%20-%20Instructions%20to%20Technical%20Officials%20%28ITTO%29%20-1.pdf) |
| TT can use the table surface as its legal plane | **CONFIRMED** | The ITTF defines the playing surface as the top of the table and excludes the vertical sides. It specifies 2 cm lines and a minimum 16 cm service toss. The plane and line geometry matter; a side hit must not be treated as a tabletop bounce. [ITTF Statutes 2026](https://db.ittf.com/sites/default/files/public/2026-02/2026_Statutes_v1_consolidated_tc.pdf) |
| Tennis balls touching a line are in | **CONFIRMED** | The 2026 Rules of Tennis say a ball touching a line is regarded as touching the court bounded by that line. That rule does not establish that this app can locate the impact accurately. [ITF Rules of Tennis 2026](https://www.itftennis.com/media/7221/2026-rules-of-tennis-english.pdf) |
| Tennis contact can be represented by a fixed ball-radius correction | **WRONG** | ITF evaluation procedures describe contact area and apparent landing evidence as affected by speed, spin, deformation, angle, surface, and sliding. A generic radius offset is not a validated contact model. [ITF Electronic Line Calling Evaluation Procedures, v28.1](https://www.itftennis.com/media/15990/elc-evaluation-procedures-v281-jan-2026.pdf) |
| `Σ_court ≈ JΣ_pxJᵀ + Σ_H` is a complete uncertainty calculation | **WRONG as written** | The homography term needs to be defined. If `Σ_H` means covariance of homography parameters, it must be propagated through the Jacobian with cross-covariances. Timing and footprint uncertainty also need explicit terms. NIST describes first-order covariance propagation through a Jacobian. [NIST TN 1297, Appendix A](https://www.nist.gov/pml/nist-technical-note-1297/nist-tn-1297-appendix-law-propagation-uncertainty) |
| A `1.96σ` interval is automatically a 95% interval | **WRONG as an unconditional rule** | `k≈2` corresponds to about 95% only when the distribution is suitably normal and the combined uncertainty is a reliable standard-deviation estimate. Neither condition has been established for the probes here. [NIST coverage factors](https://pml.nist.gov/cuu/Uncertainty/coverage.html) |
| The draft’s camera geometry implies roughly 5 cm near and 60 cm far uncertainty | **UNVERIFIED, illustrative estimate** | Those values depend on assumed FOV, camera pose, 2 px localization error, speed, and timing. They are not device measurements or validated call errors. The assumptions are in the [low-resolution report](</Users/Hetansh/Github/elevate1.0/research/out/09-low-resolution.md>) and [referee report](</Users/Hetansh/Github/elevate1.0/research/out/11-ai-referee.md>). |
| The S23 FE or A55 will record the demo at 1080p60 | **UNVERIFIED on the actual devices** | Samsung’s India A55 specification lists UHD 4K at 30 fps and HD slow motion at 240 fps, but that does not establish the requested 1080p60 mode. The S23 FE India specification confirms the Exynos 2200 model, but the cited specification does not establish that capture mode either. Check the actual camera app and saved file metadata on the demo phone. [Samsung Galaxy A55 specifications](https://www.samsung.com/in/smartphones/galaxy-a/galaxy-a55-5g-awesome-iceblue-256gb-sm-a556elbhins/), [Samsung Galaxy S23 FE specifications](https://www.samsung.com/in/support/mobile-devices/specifications-for-the-new-galaxy-s23-fe/) |
| The draft’s `0.3 ± 0.2s` live target is validated for this app | **WRONG as a product claim** | ITF uses that as an appropriate response-time reference for Silver/Bronze electronic line-calling evaluation. It is not a measurement of this app’s capture-to-verdict latency. The same ITF procedures describe tennis impact as occurring in under 0.01 seconds, while 60 fps samples every 0.0167 seconds. [ITF ELC Evaluation Procedures, v28.1](https://www.itftennis.com/media/15990/elc-evaluation-procedures-v281-jan-2026.pdf) |
| Existing probes validate bounce or line calls | **WRONG** | The inspected probes test ball tracking, not contact location, bounce classification, or calls against ground truth. The tracked-clip and model findings are documented in [CV algorithms](</Users/Hetansh/Github/elevate1.0/research/out/04-cv-algorithms.md>), [footage sources](</Users/Hetansh/Github/elevate1.0/research/probes/footage/SOURCES.md>), and [referee report](</Users/Hetansh/Github/elevate1.0/research/out/11-ai-referee.md>). |

## Exact computation to implement

A homography maps points on a plane. Map the estimated **surface contact point** to court coordinates, in metres. Do not apply the court-plane homography directly to the airborne ball or shuttle centroid.

Let the image contact point be `p = (u, v)` in pixels. Normalize the homography so `h33 = 1`, when that parameterization is valid:

```text
a = h11*u + h12*v + h13
b = h21*u + h22*v + h23
c = h31*u + h32*v + 1

q = (x, y) = (a/c, b/c) metres
```

The image-point Jacobian, in metres per pixel, is:

```text
Jp = [
  [(h11*c - h31*a)/c², (h12*c - h32*a)/c²],
  [(h21*c - h31*b)/c², (h22*c - h32*b)/c²]
]
```

Let `θ = (h11, h12, h13, h21, h22, h23, h31, h32)` be the homography parameters, and estimate their covariance `Σθ` from calibration. Let `Σp` be contact-point covariance in pixel². Then, under an independence assumption:

```text
Σq = Jp Σp Jpᵀ + Jθ Σθ Jθᵀ + vxy vxyᵀ σt² + Σmodel
```

Units for `Σq` and its terms are m². `Jθ` is the derivative of the mapped point with respect to the homography parameters; use analytic derivatives or finite differences. `Σmodel` is allowed only when measured from validation data. Do not invent a value for it.

For a frame-only event timestamp at actual frame rate `f` with unknown position within the frame interval:

```text
σt = 1 / (√12 · f) seconds
```

At 60 fps, that is 4.81 ms. At an assumed normal speed of 10 m/s, the timing contribution along the line normal is 4.81 cm at one standard deviation. This speed is an illustration, not a measurement. Use video presentation timestamps where available. If the trajectory-fit covariance already includes event-time error, do not add the timing term again.

For one isolated line, let `q0` be a point on the rule boundary and `n` a unit normal pointing into the legal region. Define:

```text
d = nᵀ(q - q0) metres
```

Define the legal polygon using the sport’s actual rules, including legal line strips. If `q` is an anchor for a modeled contact footprint `K`, let:

```text
ρ = max over r in K of (nᵀr) metres
m = d + ρ
```

If `q` is already the estimated first contact point, set `ρ = 0`. Do not substitute the whole ball radius for an unobserved contact footprint. For a line decision, propagate uncertainty in the contact point, boundary, and footprint into `σm`; under independence this includes `nᵀΣq n`, `σline²`, and `σρ²`, plus any known cross terms.

When the errors have been shown to be approximately normal and calibrated against held-out ground truth, calculate:

```text
interval = [μm - 1.96·σm, μm + 1.96·σm]

IN                  if the lower bound is >= 0
OUT                 if the upper bound is < 0
TOO CLOSE TO CALL   otherwise
UNOBSERVED          if the event or contact point was not observed
```

The bounds are in metres. `1.96` is not a 95% guarantee until the error distribution and coverage have been validated. For the hackathon, label the result **Model estimate** and show an estimated range. Use `TOO CLOSE TO CALL` conservatively; do not display “95% confidence” or an unvalidated “OUT by 9 cm ± 3 cm” claim. At corners or where multiple lines matter, evaluate samples against the full legal polygon or abstain; a single-line normal is insufficient.

OpenCV is a suitable geometry library candidate: its current official release list shows **5.0.0**, its projection documentation covers `findHomography`, and its license is Apache-2.0. Pin the exact build used by the backend before implementation. [OpenCV releases](https://github.com/opencv/opencv/releases), [projection documentation](https://docs.opencv.org/5.0/main_modules/d_projection.html), [license](https://github.com/opencv/opencv/blob/5.x/LICENSE).

## Sport-specific event logic

| Sport | Event to estimate | Evidence required | Abstain when |
|---|---|---|---|
| **Badminton, primary** | First observed shuttle-to-floor landing | Track the shuttle into visible floor contact; estimate the shuttle’s contact location on the floor plane. BWF line-judge timing supports waiting for floor contact. | The track ends at an occluder, frame edge, or unexplained gap; contact is hidden; floor point cannot be mapped. Track loss alone is not a landing. |
| **Table tennis, secondary** | First ball contact with the tabletop plane | Track before and after the candidate event; estimate the contact on the top-plane. A change in trajectory relative to the surface normal can corroborate the event. Treat audio as a candidate cue only. | Contact could be with a vertical side, the ball is hidden, or event geometry cannot distinguish tabletop from side. |
| **Tennis, supported** | First ball contact with the court plane | Fit the incoming and outgoing path and estimate the first contact point and patch. Tennis near-line calls need a stronger abstention rule because the patch and apparent mark can shift with impact conditions. | Contact is hidden, path fit is weak, or the uncertainty range intersects the line. Keep service and foot faults out of scope. |

For TT and tennis, raw image-vertical velocity reversal is not the world-surface-normal velocity. Estimate contact relative to the actual surface plane. Audio may corroborate a candidate event; a fixed “two of three cues” vote does not establish confidence because the cues may be correlated and have not been calibrated.

## Demo feasibility and setup

**A clear bounce may be visible at 1080p60 from a fixed phone near the taped line, but reliable event detection and line calls are unverified.** No inspected probe tests bounce detection on the S23 FE or A55, and 60 fps can place the actual impact between frames. The ITF timing reference says tennis impact is under 10 ms; a 60 fps frame interval is 16.7 ms. A trajectory can sometimes support an inferred event, but the app must show that it inferred it.

A reasonable geometry starting point is a fixed stand about **1.5 m high and 1.5 m horizontally from the line**, aimed at the bounce region. This is an estimate from pinhole geometry, not a phone test. With an assumed 43° vertical FOV and a 1080-pixel image axis along the line normal, the central scale is about 457 px/m. A 20 mm stripe would span about 9 pixels. The actual FOV, crop, orientation, and saved resolution need to be measured on the chosen phone. Put the camera on the line normal, make the line run across the frame, use the main camera rather than an ultra-wide lens, and place the bounce area near the image centre. Orient the longest available pixel dimension along the line normal if the capture mode preserves it.

For controlled testing, use a flat surface, a measured line width, a visible grid, and an independent high-speed reference camera to record the contact location. Validate that reference setup and frame cadence. A chalk or damp mark is not ground truth for tennis: ITF notes that impact marks can be misleading because sliding and rolling can separate the mark from initial impact. [ITF ELC Evaluation Procedures, v28.1](https://www.itftennis.com/media/15990/elc-evaluation-procedures-v281-jan-2026.pdf)

For the judging demo, record several actual corridor attempts on the venue phone and analyze those clips through the real engine. Keep the resulting analyses cached locally for replay, since venue connectivity is unknown and the product’s analysis runs in the cloud. A newly recorded clip can be an optional live-upload attempt. Do not insert expected answers as model output. Describe the result as a **single-line bounce-review prototype**, not a validated sport referee.

## Models and implementation evidence

| Component | Version, file and size | License and speed evidence | D5 use |
|---|---|---|---|
| **OpenCV geometry** | Official release list currently shows **5.0.0**. No model weights. | Apache-2.0. `findHomography` is documented for projective plane mapping. [Release list](https://github.com/opencv/opencv/releases), [docs](https://docs.opencv.org/5.0/main_modules/d_projection.html), [license](https://github.com/opencv/opencv/blob/5.x/LICENSE) | Use for court/table geometry; it does not solve event detection or airborne-point mapping. |
| **WASB-SBDT** | Checkpoint files in the probe workspace: `wasb_badminton_best.pth.tar` and `wasb_tennis_best.pth.tar`, **5.8 MB each**. No pinned semantic version found in the research. [Badminton weight](</Users/Hetansh/Github/elevate1.0/research/probes/models/wasb/weights/wasb_badminton_best.pth.tar>), [tennis weight](</Users/Hetansh/Github/elevate1.0/research/probes/models/wasb/weights/wasb_tennis_best.pth.tar>). | Code license is MIT; checkpoint terms are **UNVERIFIED**. Team report measured about 50 fps on M4/MPS for static broadcast tennis, with 11 of 12 sampled detections judged correct; those are small internal probes, not an accuracy benchmark. It performed poorly on handheld badminton clips. [Repository](https://github.com/nttcom/WASB-SBDT), [model zoo](https://github.com/nttcom/WASB-SBDT/blob/main/MODEL_ZOO.md), [license](https://github.com/nttcom/WASB-SBDT/blob/main/LICENSE.md), [team speed notes](</Users/Hetansh/Github/elevate1.0/research/out/16-cloud-streaming.md>) | Candidate for fixed-camera video experiments. Do not base the corridor demo on it or present its tracking speed as bounce performance. |
| **TrackNetV3** | Repository names `TrackNet_best.pt` and `InpaintNet_best.pt`. File sizes and a pinned release version are **UNVERIFIED**. | Repository reports 25.11 fps on its test setup, with hardware unspecified. Its license says MIT for code and checkpoints. A team probe reported about 69% frame detection and 50% precision on nine elevated-phone badminton clips; that is a small probe, not officiating validation. [Repository and reported results](https://github.com/qaz812345/TrackNetV3), [license](https://github.com/qaz812345/TrackNetV3/blob/master/LICENSE), [team probe report](</Users/Hetansh/Github/elevate1.0/research/out/04-cv-algorithms.md>) | Candidate for frozen-weight shuttle tracking experiments. It does not establish landing or line-call accuracy. |

The repository FPS figures do not predict cloud latency or target-phone capture performance. No model in the inspected evidence has a validated line-call benchmark for these informal clips.

## Strong alternatives and attacks

**Strongest alternative:** make the player the line judge and the model the evidence assistant. The app detects a candidate event, shows the replay and court map, then records the player-confirmed point. This preserves trust and still lets the demo show useful intelligence. A fully automatic referee has more drama and less friction, but its false calls would be hard to defend without independent ground truth and calibrated error.

**Senior engineer:** the current design assumes it can turn an event in an image into a court coordinate. The homography only maps points on the calibrated surface; an airborne centroid is not such a point. The probes do not establish contact localization, bounce classification, or uncertainty coverage.

**Hackathon judge:** a taped corridor line can demonstrate that the app processes footage filmed at the venue. It cannot prove cross-sport generalization, an officiating system, or the “no training” promise by itself. The model, event evidence, and limitations need to be visible in the demo.

**Player or coach:** a confident but wrong call destroys trust. The player needs to replay the moment, see which parts of the track were observed versus inferred, and correct the saved point. The correction should update confirmed match history without rewriting the model’s original estimate.

## Required edits

1. **In [07-ai-referee.md](</Users/Hetansh/Github/elevate1.0/docs/07-ai-referee.md>), replace “calibrated call” and the opening scope paragraph with:**

   > The AI referee is an evidence assistant. It identifies candidate bounce or landing events, shows the supporting video and surface map, and records a model estimate. The player confirms or corrects the point. Use `IN`, `OUT`, `TOO CLOSE TO CALL`, and `UNOBSERVED`; keep the model estimate separate from the player-confirmed point. Do not claim calibrated accuracy until held-out ground-truth tests demonstrate it.

2. **Replace the current uncertainty paragraph with the exact computation in “Exact computation to implement” above.** Include pixel, metre, and second units, covariance for homography parameters, event time, and footprint. Mark the `1.96` interval conditional on validated normal errors and coverage.

3. **Replace the sport event table with the sport-specific table above.** Remove the raw image-vertical reversal as a sufficient bounce rule, the track-termination landing rule, and the “two of three cues” verdict rule. Retain audio only as candidate-event corroboration.

4. **Replace the corridor demo instructions with:**

   > Demo a single-line bounce-review prototype using a high-contrast ball, a measured stripe, fixed phone, and bright lighting. The phone mode, event detection, and line estimate must be tested on the actual demo device. Record multiple venue clips and process them through the real analysis engine. Keep their genuine results cached for replay. Show the model’s estimate, event evidence, map, and player correction. Do not describe the corridor clip as validation of badminton, table tennis, tennis, or official line-call accuracy.

5. **Remove live voice calls from the 24-hour demo.** Replace with:

   > Live referee mode is future work. Reconsider it after post-game event detection and location estimates have been tested against independent ground truth, and after capture-to-result latency has been measured on the target phone and network. The 0.3 ± 0.2 second ITF response-time reference is not this app’s measured latency.

6. **Update [04-vision-engine.md](</Users/Hetansh/Github/elevate1.0/docs/04-vision-engine.md>)** to say that a homography maps surface points only. Keep airborne tracks in image coordinates until an event/contact estimator places the contact point on the surface. Give `UNOBSERVED` when evidence cannot identify contact.

7. **Update [05-app-experience.md](</Users/Hetansh/Github/elevate1.0/docs/05-app-experience.md>)** so the verdict view says “Model estimate,” separates observed track from inferred track, shows a compact replay and uncertainty range, and offers confirm/correct actions. Remove the live spoken-call demo promise.

8. **Update [01-ps-fundamentals.md](</Users/Hetansh/Github/elevate1.0/docs/01-ps-fundamentals.md>)** to clarify that “analysed live” means the clip is captured at the venue and analyzed by the cloud engine. Cached results support the demo when venue connectivity fails.

## Recommendation

Build a **post-game, human-confirmed event review** with a polished replay, court or surface map, experimental model estimate, and correction record. Keep automated line calls as a capability label to earn through validation.

## 3 biggest risks

1. A wrong or hidden contact point produces a false line estimate even when the ball tracker looks plausible.
2. The target phone’s actual capture mode, exposure, and frame timestamps may differ from the assumed 1080p60 setup.
3. Cloud upload or processing may fail during the demo, and caching can only help if the clip was genuinely analyzed beforehand.

## Open questions only the team can answer

1. Which phone and rear lens will record the corridor demo, and what resolution, frame rate, and orientation does its saved video actually use?
2. What ball and stripe width will be used, and what camera height and distance can the corridor stand hold?
3. Can the team use an independent high-speed reference camera and measured grid to establish bounce ground truth?
4. Is the team comfortable presenting this as an experimental bounce-review prototype, with no claim of referee accuracy?