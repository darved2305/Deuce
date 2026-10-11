# engine/ · Person 1 (@VrajVed) · vision engine on Modal
Adds to the root AGENTS.md. Brief: `docs/lanes/person-1.md`. Method + validation: "Correct results first" below.

## Stack (pinned)
Python 3.12 (uv) · Modal SDK 1.6.1 · PyAV 19.0.1 (bundles FFmpeg) · PyTorch CUDA for T4 (pin in uv.lock) ·
OpenCV (pin) · models: `tracknet_v3`, `dfine_s_coco` (sha256 48a6c8cc…3e89), `tapir_causal`.

## Commands
- Checks: `uv run ruff check . && uv run ruff format --check . && uv run pytest -q`
- Run on Modal: `modal run engine/<file>.py::<fn>` · serve: `modal serve` · deploy (only from `main`, only you): `modal deploy -m <pkg.module>`
- Inspect: `modal app list` · `modal app logs <app>` · `modal container list` · `modal token info`
- Health loop (keep running in its own Claude Code session): `/loop 45m /engine-health`

## Modal rules (verified in Modal docs)
- `@modal.fastapi_endpoint` is PUBLIC by default: the `submit` endpoint must check the bearer token from a Modal Secret (or use `requires_proxy_auth=True`). Image needs `fastapi[standard]`.
- Web endpoints time out at 150 s: `submit` only calls `analyze.spawn(...)` and returns the call ID.
- Never pass video bytes as arguments (>2 MiB payloads go through Modal's US storage): pass the R2 key; read from R2 inside the job.
- Secrets only via `modal secret create` + `modal.Secret.from_name(...)`; read with `os.environ`. Never in code or logs.
- Weights: keep them in a Modal Volume (or bake with a pinned revision), verify sha256 at load, load once in `@modal.enter()`.
- Since Modal 1.0 there is no `Mount`: add local code with `image.add_local_python_source(...)`. Use `min_containers`, `max_containers`, `scaledown_window`.
- `gpu="T4"`; region broad `ap` only (narrow costs 1.75×). One warm container only during demo windows.
- Report results only through Person 3's `/internal/analyses/{id}/events|artifacts|finalize` (header `X-Internal-Token`). Never write Postgres, never Broadcast.

## Correct results first (the bar for every engine PR)
1. **Real clips only.** Pinned eval clips live in R2 (list in `engine/eval/clips.json`), hand-labelled by Person 4. Never synthetic frames, never invented detections.
2. **Golden run on every engine change:** run the pipeline on Modal on the pinned clips; produce metrics vs labels (shuttle precision/recall at stated pixel tolerance, hit F1 ± frames, landing error in cm, review calls vs slow-motion truth with call coverage and abstentions, flight-time error) and a contact sheet of detections drawn on real frames.
3. **No regression without a decision:** compare against `engine/eval/baseline.json` (last accepted run). Any metric worse beyond its tolerance blocks the PR unless a DECISIONS entry accepts it.
4. **Method fidelity (from the papers and our debates):** TrackNetV3 uses its multi-frame input + background + InpaintNet rectification as published; crop the confirmed court at native resolution BEFORE resizing (never letterbox portrait); keep all heatmap candidates; Kalman tracklets on real PTS; interpolate at most 1 frame (dashed, excluded from metrics); homography only for surface points; review estimates exactly as `docs/plan/07-ai-referee.md` §3. If you change a method, write a decision with the measured before/after.
5. PR `## Proof`: Modal app/run IDs, artifact links, the metrics table vs baseline, the contact sheet.

## Handheld stabilisation (off by default)
`elevate_engine/stabilise.py`, config `sports.StabiliseConfig` (`enabled=False`). TrackNetV3 assumes a fixed camera, so a window whose measured `camera_motion_px` (median consecutive-frame shift, source px) exceeds `min_motion_px` is warped onto its middle frame (KLT chain, per-frame KLT refinement against the reference, ORB re-anchor, identity fallback on failure), gets its own valid-pixel median background, and its candidates are mapped back to original source pixels. Steady windows are untouched. `window_result.v1` `quality` has no room for extra fields, so `stabilised` and `residual_motion_px` are logged (`window.stabilise`) only. Turn on per run with `modal run ... run_clip --stabilise`, or `StabiliseConfig.enabled` for `analyze`; either needs a Modal deploy to be live. Measure with `PYTHONPATH=. uv run python eval/stabilise_check.py CLIP START_S END_S OUT_DIR` (results in `eval/runs/stabilise_*`). Not done yet: the A3 static filter and Kalman tracker still run in source pixels, so on a stabilised window a wall mark moves with the camera; tracking in stabilised space then mapping back is the follow-up.

## Docs for this lane
`modal skills install --claude` (adds Modal's version-aligned skill) · https://modal.com/llms.txt · Context7 `/websites/modal` ·
TrackNetV3 https://github.com/qaz812345/TrackNetV3 · D-FINE https://github.com/Peterande/D-FINE · TAPIR https://github.com/google-deepmind/tapnet
