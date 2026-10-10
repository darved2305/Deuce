# 07 · AI referee: an evidence assistant (mentor ask: "AI judge / referee")

Decided in debate D5 (`11-debates.md`).

## 1. Position

The referee identifies candidate landing/bounce events, shows the supporting frames and a
surface map, and gives a **model estimate**: IN, OUT, TOO CLOSE TO CALL or UNOBSERVED (the
shape of BWF's Instant Review: IN / OUT / NO DECISION). **The player confirms or corrects the
point; only a confirmation changes the score.** The estimate and the confirmation are stored
separately. No "95%" or accuracy wording until the held-out bounce set (Person 4) shows the
empirical coverage; then we quote that measured result with its conditions.

## 2. Events per sport

| Sport | Event | Evidence required | Abstain (UNOBSERVED) when |
|---|---|---|---|
| Badminton | First shuttle-to-floor landing | Shuttle tracked into visible floor contact; contact point estimated on the floor plane | Track ends at an occluder, frame edge or gap; contact hidden. Track loss is not a landing |
| Table tennis | First ball contact with the tabletop | Track before and after; contact estimated on the tabletop plane; change of path relative to the surface normal corroborates; audio corroborates | Could be a side contact; ball hidden; geometry can't separate top from side |
| Tennis | First ball contact with the court | Fit incoming and outgoing paths; estimate first contact point | Contact hidden; weak path fit; range touches the line |
| Free play (taped line) | Ball contact with the floor | Same as tennis on the taped-rectangle plane | Same |

Audio is corroboration only. Image-vertical velocity reversal alone is never a bounce. A fixed
"two of three cues" vote is not confidence.

## 3. The computation (implement exactly; units in brackets)

Map the estimated **surface contact point** `p = (u, v)` [px] with homography `H`
(normalised `h33 = 1`):

```text
a = h11·u + h12·v + h13        b = h21·u + h22·v + h23        c = h31·u + h32·v + 1
q = (x, y) = (a/c, b/c)                                                     [m]

Jp = [[(h11·c − h31·a)/c², (h12·c − h32·a)/c²],
      [(h21·c − h31·b)/c², (h22·c − h32·b)/c²]]                             [m/px]

Σq = Jp Σp Jpᵀ  +  Jθ Σθ Jθᵀ  +  v vᵀ σt²  +  Σmodel                         [m²]
```

- `Σp` [px²]: contact-point covariance from the path fit.
- `θ = (h11 … h32)`, `Σθ` from the calibration (re-tap variance or residuals); `Jθ` by finite
  differences.
- `σt` [s]: event-time uncertainty; frame-only timing `1/(√12·f)` (4.81 ms at 60 fps); `v`
  [m/s] the surface-plane velocity at contact. Skip this term if the path-fit covariance
  already includes timing.
- `Σmodel`: only from measured validation residuals; never invented.

Line test: `q0` on the rule boundary, `n` unit normal into the legal region,
`d = nᵀ(q − q0)` [m]; contact footprint offset `ρ` only when modelled (`ρ = 0` if `q` is already
the first contact point; never substitute the ball radius); `m = d + ρ`;
`σm² = nᵀ Σq n + σline² + σρ²`. With `k = 1.96` (conditional on validated, roughly normal
errors):

```text
IN                 if m − k·σm ≥ 0
OUT                if m + k·σm < 0
TOO CLOSE TO CALL  otherwise
UNOBSERVED         if no contact point was observed
```

At corners or where several lines matter, test samples against the full legal polygon or
abstain. Legal regions come from the rules pack (singles/doubles, service courts).

## 4. Review screen

Before / impact / after frames (scrubbable, tick per frame); surface map zoomed to the line
with the estimated contact area; the verdict chip **MODEL ESTIMATE · OUT**, the margin range
only when not abstaining; which lines this camera angle can review ("near sidelines and near
baseline from this angle"); **Confirm point** / **Correct**. Every review is stored with
evidence frames, the estimate and who confirmed.

## 5. Camera guidance for reviews

Phone on a stand on the line normal, main camera, line running across the frame, bounce area
near the image centre; resolution along the line normal matters most. Illustrative geometry
(not measured): at 1.5 m height and 1.5 m from the line with a 43° vertical FOV, ~457 px/m,
so a 2 cm line spans ~9 px.

## 6. Corridor demo (single-line bounce-review prototype)

High-contrast ball, a measured tape stripe and a taped rectangle (4 corners for the floor
homography), fixed S23 FE in the highest audited mode. Independent reference: the A55 recording
the same bounces in slow motion (mode confirmed by auditing a test file) beside a measured floor
grid, so each bounce's true side of the line is known. Record several attempts at the venue, run them through the real engine, cache the
genuine results; a fresh live attempt is optional. Present it as a prototype that processes
footage shot here, not as validated officiating for any sport.

## 7. Cut from this build

Live spoken calls (latency unmeasured; the ITF response-time reference is not our measurement),
service-fault, foot-fault, let and net-touch detection.

## 8. Record

`ReviewEvent { review_id, match_id, rally_id, event_id, t_ms, evidence_frames[], contact_xy_m,
cov_m2, line_id, margin_m, sigma_m, estimate: IN|OUT|TOO_CLOSE|UNOBSERVED, visibility,
model_ids[], calibration_id, confirmed_point_winner?, confirmed_by?, confirmed_at? }`.
`ConfirmedPoint` events are separate and are the only input to the score.

## 9. Never say

"Hawk-Eye accurate", "official", "certified", "works from any angle", "detects service
faults", or any accuracy number without the test set and setup it came from.
