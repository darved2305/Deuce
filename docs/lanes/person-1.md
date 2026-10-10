# Person 1 · Vision engine (Modal job)

Paste this file into your coding agent as the kickoff prompt.

## Mission
Turn an uploaded racket-sports video into trustworthy, evidence-linked vision data: court
geometry, players, shuttle/ball trail, hit and landing candidates, rallies, review estimates
with honest uncertainty, and real clips. Everything the app shows is drawn from your output over
real footage.

## Read first
`docs/plan/contracts.md` (§3–7), `docs/plan/04-vision-engine.md`, `docs/plan/07-ai-referee.md` §2–3,
`docs/plan/12-real-clips.md` §3, `docs/plan/03-architecture-and-streaming.md` §5, `docs/plan/11-debates.md`
(D3, D5). Probe results are summarised in `docs/plan/04-vision-engine.md` §1 (the probe scripts are not in
this repo). For weight loading and pre/post-processing, read the official inference code in the
TrackNetV3 (github.com/qaz812345/TrackNetV3) and WASB-SBDT (github.com/nttcom/WASB-SBDT) repos,
then write it properly in `engine/`.
Clips: `fixtures/clips.md` (D-261010-HW1): the working clip, the held-out clip and the labelled
TrackNetV3 test split, with fetch commands. Reproduce the published TrackNetV3 numbers on that test
split before tuning anything.

## You own
`engine/`, the Modal app, its `submit` endpoint and secret, clip cutting.

## First things (before any engine code)
1. **Authenticate Modal as the team's single operator:** `uv tool install modal==1.6.1` (or `pip install modal==1.6.1`), then `modal setup` (opens the browser) and check with `modal token info`. Add the card in the Modal dashboard (GPU requires it) and agree the spend ceiling with the team; record it in DECISIONS.md. Never share the token; nobody else deploys Modal.
2. **Install Modal's agent skill:** `modal skills install --claude` (and plain `modal skills install` for Codex).
3. **Start the 45-minute health loop** in a separate Claude Code session opened in the repo:
   `/loop 45m /engine-health` (the skill is in `.claude/skills/engine-health/`). It checks that Modal jobs are healthy, that results on the pinned real clips have not regressed, that the method still matches the papers and our decisions, and that work is in scope. Treat `HEALTH: ACTION NEEDED` as the top priority.
   If that session cannot stay open, run it headless on a timer with macOS launchd (every 2700 s):
   `claude -p "/engine-health" --permission-mode acceptEdits >> ~/elevate-engine-health.log 2>&1`
   in a LaunchAgent plist (`StartInterval` 2700, `WorkingDirectory` = this repo).
4. **Correct results come before speed or features.** Every engine PR carries the golden run on real clips vs `engine/eval/baseline.json` (see `engine/AGENTS.md` "Correct results first").

## Interfaces (frozen)
- `submit` (Modal `@modal.fastapi_endpoint(method="POST")`, which is PUBLIC by default: check the bearer token from a Modal Secret on every call):
  body `{analysis_id, r2_key, session_spec}` → `analyze.spawn(analysis_id, …)` → returns the
  call ID. One job per analysis.
- The job reads the original from R2 (scoped credentials in Modal Secrets), processes ~6 s windows
  with 0.5–1 s overlap **in order**, writes `window_result.v1` artifacts to the deterministic keys
  in `contracts.md` §4, and reports through Person 3's internal API: `/internal/analyses/{id}/events`,
  `/artifacts`, `/finalize`. You never write Postgres directly and never send Broadcasts.
- `finalize` carries the vision part of `match.v1` (no insights) and the clip artifact IDs.
  Person 3 completes the analysis.
- Every output validates against `packages/contracts` JSON Schemas in CI.

## Stack (pin exactly in `engine/pyproject.toml` / lockfile)
Python **3.12** · Modal SDK **1.6.1** · PyAV **19.0.1** with FFmpeg **9.0.2** (record build flags;
LGPL by default) · PyTorch with CUDA for T4 (pin) · OpenCV (pin) · numpy/scipy.
Models (kept in a Modal Volume or baked with a pinned revision, sha256 verified at load, loaded once in `@modal.enter()`; described with the model descriptor, `contracts.md` §6):
- **TrackNetV3** (`tracknet_v3`; `TrackNet_best.pt`, `InpaintNet_best.pt`; MIT code and weights) —
  the badminton default for the demo.
- **D-FINE-S COCO** (`dfine_s_coco.pth`, 41,841,422 bytes, SHA256
  `48a6c8cc43eb57186843f752e2e8461ddd3326e0d3c575e71e6e960844683e89`, Apache-2.0), 640 input,
  person class, 10–15 fps sampling.
- **TAPIR** (`tapir_causal`; `causal_tapir_checkpoint.npy`, 127,043,639 bytes, Apache-2.0) for free play.
- **WASB-SBDT HRNet** (badminton, tennis; MIT code, weights UNVERIFIED) — challenger in Phase 4,
  enabled only after its weight terms are checked.

## How you ship
In slices, straight to `main`, through the pipeline in `docs/plan/10-build-plan.md` §6: small slice → your lane's checks → agent code review → PR → squash-merge on green CI → deployed. Unfinished work hides behind a flag. If you break `main`, fixing it comes first.

## Pipeline
0. Audit real PTS (VFR, gaps), rotation, blur/compression indicators, camera motion.
1. Homography from the four named taps (template must match format), residual, `H_cov` from tap
   jitter; tier A/B/C. Surface points only.
2. Players: D-FINE → association across windows → foot point in metres; anchor "me" from the tap;
   flag identity switches.
3. Ball: crop the confirmed court region at native resolution **before** resizing (never letterbox
   portrait video); keep all heatmap candidates.
4. Tracking layer: constant-velocity Kalman `[x,y,vx,vy]` on PTS deltas; gated Mahalanobis +
   heatmap score; new tracklet on gate failure; sport process noise as configuration; interpolate
   at most one slot (dashed, excluded from metrics, events and reviews).
5. Audio onsets (spectral flux) as candidate cues.
6. Events: hit candidates; badminton landing only on visible floor contact; table tennis/tennis
   bounce from incoming/outgoing path fit on the surface plane.
7. Rallies; score **proposal** using the rules vectors (Person 3 owns the authoritative score from
   confirmations); flight times; metrics with numerator/denominator.
8. Review estimates exactly as `07` §3. Never output "95%" wording.
9. Clips: FFmpeg per moment (contact ± 1.5 s) and per rally + posters; `contact_ms` stored.
10. Free play: TAPIR from the seed; stop and ask for a new tap when occluded; pixel trail; floor
    bounce estimates only when a taped rectangle was tapped.

## Phases
| Phase | Done when |
|---|---|
| 1 · Foundations | Image deployed with manifest; `submit` answers with bearer auth; CLI produces TrackNetV3 candidates and D-FINE boxes on the selected clip |
| 2 · First real result | Thin path first (one window posted via `/internal`), then the full window pipeline; the app shows the real trail |
| 3 · Full loop | Chunked analysis: start on segment 0, process each segment as its manifest arrives, overlap from the previous segment's tail (D-261011-HW3); finalize with rallies, landings, review estimates and real clips; free-play TAPIR trail on a corridor clip |
| 4 · Depth and extras | Throughput, quality gates, capability numbers with Person 4; WASB challenger if cleared |
| 5 · Demo-ready | Every fallback clip genuinely analysed and labelled |

## Already learned (don't repeat)
Apple Vision trajectories fail on phone video. Sport-trained weights gave 0 hits on a catch clip,
so free play uses TAPIR. TrackNetV3's background model struggles on very short or moving clips.
WASB eval code asserts CUDA. Earlier M4 speed figures are unreconciled; measure on Modal. Google
Drive weights: `curl` the `drive.usercontent.google.com/download?id=…&confirm=t` URL.
