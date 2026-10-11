"""Whole-video pipeline: ordered ~6 s windows, one shared background, tracking across windows.

1. plan_windows: emitted ranges tile the video in order; each window decodes overlap_ms more
   on both sides so TrackNet's 8-frame windows have context at the edges.
2. One background median for the whole video (sampled across it, like the official predict.py)
   is shared by every window.
3. Each window's candidates come from a parallel GPU container (Models.candidates).
4. Static-candidate filter (A3): within each window, a candidate that sits within 12 px of the
   same spot in more than 40% of the window's frames is dropped (back-wall marks).
5. The tracking layer runs over the windows in time order, so tracks cross boundaries.
6. Each window becomes a window_result.v1 with the tracked rows inside its emitted range.
7. Audio onsets (measured per emitted range) and candidate hits (elevate_engine.hits: an
   observed trajectory turn, fused with a nearby onset when there is one) go in the same
   window. A hit's track_id names the shuttle trail until player tracks exist.

Per-window quality: ball_coverage = emitted frames with an observed tracked point / emitted
frames (the trail, after the tracking layer); camera_motion_px and blur_index as in
elevate_engine.window.
"""

from __future__ import annotations

from collections.abc import Sequence
from dataclasses import dataclass

from elevate_engine import landings
from elevate_engine import window as win
from elevate_engine.hits import (
    detect_direction_changes,
    estimate_av_offset_ms,
    hit_candidates,
    shuttle_track_id,
)
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


STATIC_RADIUS_PX = 12.0  # A3 (docs/plan/13 §A3), set on dev clips only
STATIC_SHARE = 0.4


def static_filter(
    cands: Sequence[Sequence], radius_px: float = STATIC_RADIUS_PX, share: float = STATIC_SHARE
) -> tuple[list[list], int]:
    """A3: drop candidates that sit on one spot in more than `share` of the window's frames.

    cands[i] is frame i's candidate list [(x, y, conf), ...]. Returns (filtered, dropped)."""
    import numpy as np

    n = len(cands)
    flat = [(i, c) for i, frame in enumerate(cands) for c in frame]
    if not n or not flat:
        return [list(f) for f in cands], 0
    xy = np.array([[c[0], c[1]] for _, c in flat], dtype=float)
    frame_of = np.array([i for i, _ in flat])
    near = np.linalg.norm(xy[:, None, :] - xy[None, :, :], axis=2) <= radius_px
    keep = []
    for k in range(len(flat)):
        frames_here = len(set(frame_of[near[k]].tolist()))
        keep.append(frames_here / n <= share)
    out: list[list] = [[] for _ in cands]
    for k, (i, c) in enumerate(flat):
        if keep[k]:
            out[i].append(c)
    return out, sum(1 for k in keep if not k)


def track_windows(per_window: Sequence[dict], cfg: TrackingConfig) -> tuple[list[list], dict]:
    """Static filter per window, then the tracking layer over all given windows in order."""
    t_all, c_all, dropped = [], [], 0
    for res in per_window:
        filtered, d = static_filter(res["cands"])
        dropped += d
        t_all.extend(res["t_ms"])
        c_all.extend(filtered)
    if t_all != sorted(t_all) or len(set(t_all)) != len(t_all):
        raise ValueError("frame times across windows are not strictly increasing")
    rows, stats = track(t_all, [[tuple(c) for c in cands] for cands in c_all], cfg)
    return rows, {**stats, "static_filter_dropped": dropped}


def window_result(
    analysis_id: str,
    plan: WindowPlan,
    res: dict,
    rows: Sequence[list],
    models: Sequence[str],
    onsets: Sequence[Sequence[int | float]] = (),
    events: Sequence[dict] = (),
    players: Sequence[list] = (),
) -> dict:
    """window_result.v1 for one planned window from the tracked rows inside its range."""
    lo, hi = plan.emit
    if res["emit"] != list(plan.emit):
        raise ValueError(f"window {plan.seq}: result for {res['emit']}, planned {plan.emit}")
    inside = [r for r in rows if lo <= r[0] < hi]
    observed = {r[0] for r in inside if r[4] == "observed"}
    n = len(res["t_ms"])
    quality = {
        "ball_coverage": round(len(observed) / n, 4) if n else 0.0,
        "camera_motion_px": res["camera_motion_px"],
        "blur_index": res["blur_index"],
    }
    return win.build(analysis_id, plan.seq, plan.decode, plan.emit, inside, quality, models,
                     onsets, events, players)  # fmt: skip


def window_hits(
    analysis_id: str, k: int, rows: Sequence[list], results: Sequence[dict]
) -> tuple[list[list], list[dict]]:
    """Audio onsets, candidate hits and candidate landings for window k, from tracked rows and
    the onsets of the given windows (k and its neighbours), so a turn near a boundary still finds
    its onset; a landing needs QUIET_MS without the shuttle inside the known range.
    window.build keeps only those inside window k's emitted range. A result without an
    "onsets" key (no audio measured) gives trajectory-only candidates."""
    onsets_ = sorted(o for res in results for o in res.get("onsets", []))
    lo, hi = results[0]["emit"][0], results[-1]["emit"][1]
    span_rows = [r for r in rows if lo <= r[0] < hi]
    offset = estimate_av_offset_ms(detect_direction_changes(span_rows), onsets_)
    landed = landings.detect_landings(span_rows, known_until_ms=hi)
    landing_events = landings.window_events(landed, analysis_id, k, shuttle_track_id(analysis_id))
    hits = hit_candidates(span_rows, onsets_, analysis_id, k, onset_offset_ms=offset)
    events = hits + landing_events
    return onsets_, sorted(events, key=lambda e: (e["t_ms"], e["event_id"]))


def window_for_posting(
    analysis_id: str, plans: Sequence[WindowPlan], results: Sequence[dict], k: int,
    cfg: TrackingConfig, models: Sequence[str] = ("tracknet_v3",),
) -> dict:  # fmt: skip
    """Window k tracked over its neighbours k-1..k+1 only (bounded work per window). On the
    h13g working clip this equals tracking the whole range at once for every window."""
    lo, hi = max(0, k - 1), min(len(results), k + 2)
    rows, _ = track_windows(results[lo:hi], cfg)
    onsets_, events = window_hits(analysis_id, k, rows, results[lo:hi])
    return window_result(analysis_id, plans[k], results[k], rows, models, onsets_, events)


def assemble(
    analysis_id: str,
    plans: Sequence[WindowPlan],
    per_window: Sequence[dict],
    cfg: TrackingConfig,
    models: Sequence[str] = ("tracknet_v3",),
) -> tuple[list[dict], dict]:
    """per_window[i] (from Models.candidates for plans[i]): t_ms and cands of its emitted
    frames, plus measured camera_motion_px and blur_index. Returns (windows, tracker stats)."""
    rows, stats = track_windows(per_window, cfg)
    windows = [
        window_result(analysis_id, plan, res, rows, models,
                      *window_hits(analysis_id, plan.seq, rows,
                                   per_window[max(0, plan.seq - plans[0].seq - 1):
                                              plan.seq - plans[0].seq + 2]))
        for plan, res in zip(plans, per_window, strict=True)
    ]  # fmt: skip
    return windows, stats
