---
name: engine-health
description: Person 1's recurring engine check. Verifies Modal jobs are healthy, results on real clips have not regressed, the method still matches the papers, and work is in scope. Run every 45 minutes with `/loop 45m /engine-health`.
disable-model-invocation: true
---
You are checking the vision engine (lane p1). Read-only unless a step says otherwise. Report in at most 20 lines, blocking items first. Never invent numbers: every number you report comes from a command you ran in this run.

**1. Modal is healthy (real runs, not assumptions)**
- `modal token info` (fail loudly if not authenticated: tell the user to run `modal setup`).
- `modal app list`: our app deployed? any app or container stuck, crash-looping, or a GPU container left warm outside a demo window (`min_containers` > 0)?
- `modal app logs <our app>` for the last window: errors, timeouts, OOM, `window.failed`, retries.
- If Person 3's API is reachable, check recent analyses: any stuck in `queued`/`processing`/`finalizing` far longer than their peers, or `failed` with reason. Report analysis IDs.
- Spend: compare current Modal usage with the team ceiling in DECISIONS.md (dashboard Usage page if no CLI command is available: say so, do not guess).

**2. Results on real clips have not regressed**
- If `engine/` changed since the last check (`git log --since="50 minutes ago" -- engine/`), run the golden evaluation on Modal over the pinned real clips in `engine/eval/clips.json` and compare with `engine/eval/baseline.json`: shuttle precision/recall, hit F1, landing error (cm), review calls vs slow-motion truth (wrong calls, coverage, abstentions), flight-time error.
- Any metric worse than its tolerance = BLOCKING. Show before → after and the commit that caused it.
- Look at the newest contact sheet (detections drawn on real frames). Flag detections on shoes, logos, crowd, or tracks that jump.

**3. Method fidelity (best method, as decided)**
Check the code still does, and report any deviation as BLOCKING unless a DECISIONS entry allows it:
- TrackNetV3 run as published (multi-frame input, background estimate, InpaintNet rectification); D-FINE-S for players; TAPIR for free play; no Ultralytics.
- Court crop at native resolution BEFORE resizing; portrait video never letterboxed.
- All heatmap candidates kept; Kalman tracklets on real PTS; at most 1 interpolated frame, excluded from metrics.
- Homography used only for surface points; review estimates follow `docs/plan/07-ai-referee.md` §3; no "95%" wording; no km/h.
- Nothing synthetic anywhere (no generated frames, no fake detections, no placeholder numbers).

**4. Scope and expectations**
- Compare the last 45 minutes of commits and the open branch with `docs/lanes/person-1.md` (current phase) and DECISIONS.md. Flag work outside the phase, outside `engine/`, or contradicting a decision.
- Check the contract: outputs still validate against `packages/contracts` schemas / `docs/plan/contracts.md` §5–7.
- Name the single next most important task for the current phase.

End with: `HEALTH: OK` or `HEALTH: ACTION NEEDED` + the blocking list.
