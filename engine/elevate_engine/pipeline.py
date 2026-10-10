"""Whole-video pipeline: ordered ~6 s windows, one shared background, tracking across windows.

1. plan_windows: emitted ranges tile the video in order; each window decodes overlap_ms more
   on both sides so TrackNet's 8-frame windows have context at the edges.
2. One background median for the whole video (sampled across it, like the official predict.py)
   is shared by every window.
3. Each window's candidates come from a parallel GPU container (Models.candidates).
4. The tracking layer runs once over all windows in time order, so tracks cross boundaries.
5. Each window becomes a window_result.v1 with the tracked rows inside its emitted range.

Per-window quality: ball_coverage = emitted frames with an observed tracked point / emitted
frames (the trail, after the tracking layer); camera_motion_px and blur_index as in
elevate_engine.window.
"""

from __future__ import annotations

from collections.abc import Sequence
from dataclasses import dataclass

from elevate_engine import window as win
from elevate_engine.sports import TrackingConfig
from elevate_engine.tracking import track

WINDOW_MS = 6000
OVERLAP_MS = 750


@dataclass(frozen=True)
class WindowPlan:
    seq: int
    emit: tuple[int, int]
    decode: tuple[int, int]


def plan_windows(
    start_ms: int, end_ms: int, window_ms: int = WINDOW_MS, overlap_ms: int = OVERLAP_MS
) -> list[WindowPlan]:
    """Emitted ranges [lo, hi) tiling [start_ms, end_ms) in order; decode ranges padded."""
    if end_ms <= start_ms:
        raise ValueError(f"empty range {start_ms}..{end_ms}")
    plans, lo, seq = [], start_ms, 0
    while lo < end_ms:
        hi = min(lo + window_ms, end_ms)
        decode = (max(start_ms, lo - overlap_ms), min(end_ms, hi + overlap_ms))
        plans.append(WindowPlan(seq, (lo, hi), decode))
        lo, seq = hi, seq + 1
    return plans


def assemble(
    analysis_id: str,
    plans: Sequence[WindowPlan],
    per_window: Sequence[dict],
    cfg: TrackingConfig,
    models: Sequence[str] = ("tracknet_v3",),
) -> tuple[list[dict], dict]:
    """per_window[i] (from Models.candidates for plans[i]): t_ms and cands of its emitted
    frames, plus measured camera_motion_px and blur_index. Returns (windows, tracker stats)."""
    t_all, c_all = [], []
    for plan, res in zip(plans, per_window, strict=True):
        if res["emit"] != list(plan.emit):
            raise ValueError(f"window {plan.seq}: result for {res['emit']}, planned {plan.emit}")
        t_all.extend(res["t_ms"])
        c_all.extend(res["cands"])
    if t_all != sorted(t_all) or len(set(t_all)) != len(t_all):
        raise ValueError("frame times across windows are not strictly increasing")
    rows, stats = track(t_all, [[tuple(c) for c in cands] for cands in c_all], cfg)
    windows = []
    for plan, res in zip(plans, per_window, strict=True):
        lo, hi = plan.emit
        inside = [r for r in rows if lo <= r[0] < hi]
        observed = {r[0] for r in inside if r[4] == "observed"}
        n = len(res["t_ms"])
        quality = {
            "ball_coverage": round(len(observed) / n, 4) if n else 0.0,
            "camera_motion_px": res["camera_motion_px"],
            "blur_index": res["blur_index"],
        }
        w = win.build(analysis_id, plan.seq, plan.decode, plan.emit, inside, quality, models)
        windows.append(w)
    return windows, stats
