"""Measure stabilisation on a real clip, CPU only (no models): residual camera motion before and
after, and runtime per window.

    cd engine && PYTHONPATH=. uv run python eval/stabilise_check.py \\
        CLIP START_S END_S OUT_DIR [--windows N]

Windows are the pipeline's 6 s emitted ranges (pipeline.plan_windows) decoded with the pipeline's
0.75 s overlap, resized to 512x288 as TrackNet gets them. Motion is window.global_shift (phase
correlation) median over consecutive frames, in source px. Each window is run through the gate
as configured (min_motion_px) and also forced (gate 0) so a steady clip's behaviour is visible.
Writes OUT_DIR/stabilise.json (manifest + numbers). No D-FINE person masks here (needs a GPU).
"""

from __future__ import annotations

import argparse
import dataclasses
import hashlib
import json
import platform
import sys
import time
from pathlib import Path

import cv2
import numpy as np

from elevate_engine import pipeline, stabilise
from elevate_engine import window as win
from elevate_engine.sports import StabiliseConfig
from elevate_engine.tracknet import infer
from elevate_engine.video import frames_in_range, source_size


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def median_shift(greys, scale: float) -> float:
    return (
        float(np.median([win.global_shift(a, b) for a, b in zip(greys, greys[1:], strict=False)]))
        * scale
    )


def one_window(path: Path, plan, scale: float, cfg: StabiliseConfig) -> dict:
    t0 = time.perf_counter()
    frames = [f.image for f in frames_in_range(path, *plan.decode, infer.resize_chw_uint8)]
    t_decode = time.perf_counter() - t0
    greys = [win.grey(f) for f in frames]
    before = median_shift(greys, scale)  # window.py's camera_motion_px, on the decode range
    ref = greys[len(greys) // 2]
    before_ref = float(np.median([win.global_shift(ref, g) for g in greys])) * scale
    gated = stabilise.stabilise_window(frames, before, cfg)
    forced_cfg = dataclasses.replace(cfg, min_motion_px=0.0)
    t1 = time.perf_counter()
    forced = stabilise.stabilise_window(frames, before, forced_cfg)  # residual over all frames
    t_full = time.perf_counter() - t1
    t1 = time.perf_counter()  # production setting: residual logged from 60 sampled pairs
    stabilise.stabilise_window(frames, before, dataclasses.replace(forced_cfg, residual_samples=60))
    t_stab = time.perf_counter() - t1
    t2 = time.perf_counter()
    stabilise.track_path([stabilise.to_grey(f) for f in frames], None, forced_cfg)
    t_path = time.perf_counter() - t2
    out = {"emit_ms": list(plan.emit), "frames": len(frames), "decode_s": round(t_decode, 2),
           "camera_motion_px_before": round(before, 3),
           "shift_to_reference_px_before": round(before_ref, 3), "gate_applied": gated.applied,
           "gate_report": {k: v for k, v in gated.report.items() if k != "camera_motion_px"},
           "forced_report": forced.report,
           "stabilise_window_s": round(t_stab, 2),
           "stabilise_window_full_residual_s": round(t_full, 2),
           "estimate_path_s": round(t_path, 2)}  # fmt: skip
    if forced.applied:
        after = forced.report.get("residual_motion_px")
        out["residual_motion_px_after_512"] = after
        out["residual_motion_px_after_source"] = None if after is None else round(after * scale, 3)
        to_ref = forced.report.get("residual_to_reference_px")
        out["shift_to_reference_px_after"] = None if to_ref is None else round(to_ref * scale, 3)
        # Same crop-free number, measured on the stabilised frames window.py would receive.
        out["camera_motion_px_after_full_frame"] = round(
            median_shift([win.grey(f) for f in forced.frames], scale), 3
        )
    return out  # fmt: skip


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("clip")
    ap.add_argument("start_s", type=float)
    ap.add_argument("end_s", type=float)
    ap.add_argument("out_dir")
    ap.add_argument("--windows", type=int, default=3)
    a = ap.parse_args()
    path = Path(a.clip)
    src_w, src_h = source_size(path)
    scale = src_w / infer.WIDTH
    cfg = StabiliseConfig(enabled=True, residual_samples=0)  # residual over every frame
    plans = pipeline.plan_windows(int(a.start_s * 1000), int(a.end_s * 1000))[: a.windows]
    rows = [one_window(path, p, scale, cfg) for p in plans]
    forced = [r for r in rows if "residual_motion_px_after_source" in r]
    summary = {
        "median_before_source_px": round(float(np.median(
            [r["camera_motion_px_before"] for r in rows])), 3),
        "median_after_source_px": round(float(np.median(
            [r["residual_motion_px_after_source"] for r in forced
             if r["residual_motion_px_after_source"] is not None] or [np.nan])), 3),
        "median_shift_to_reference_before_source_px": round(float(np.median(
            [r["shift_to_reference_px_before"] for r in rows])), 3),
        "median_shift_to_reference_after_source_px": round(float(np.median(
            [r["shift_to_reference_px_after"] for r in forced
             if r["shift_to_reference_px_after"] is not None] or [np.nan])), 3),
        "median_estimate_path_s_per_window": round(float(np.median(
            [r["estimate_path_s"] for r in rows])), 2),
        "median_stabilise_window_s": round(float(np.median(
            [r["stabilise_window_s"] for r in rows])), 2),
        "gate_applied_windows": f"{sum(r['gate_applied'] for r in rows)}/{len(rows)}",
    }  # fmt: skip
    out = Path(a.out_dir)
    out.mkdir(parents=True, exist_ok=True)
    manifest = {
        "clip": path.name, "clip_sha256": sha256(path), "source_size": [src_w, src_h],
        "range_s": [a.start_s, a.end_s],
        "command": "cd engine && PYTHONPATH=. uv run python eval/stabilise_check.py "
        + " ".join(sys.argv[1:]),
        "config": dataclasses.asdict(cfg), "opencv": cv2.__version__,
        "machine": f"{platform.machine()} {platform.system()}, CPU, no GPU, no person masks",
        "metric": "median over consecutive frames of window.global_shift (phase correlation, "
                  "512x288 grey), times source_width/512; after = over the area all warped "
                  "frames cover",
        "summary": summary, "windows": rows,
    }  # fmt: skip
    (out / "stabilise.json").write_text(json.dumps(manifest, indent=2) + "\n")
    print(json.dumps(summary, indent=2))


if __name__ == "__main__":
    main()
