"""Assemble one window_result.v1 (contracts §5) from measured engine outputs.

Every number is measured on the real frames of the window; nothing is filled in:
- ball_coverage: fraction of emitted frames with at least one ball candidate.
- camera_motion_px: median global shift between consecutive frames (phase correlation on
  512x288 grey frames, sub-pixel peak by parabolic fit), scaled to source pixels. Near 0
  means a still camera.
- blur_index: 1 / (1 + var(Laplacian) / 100) on 512x288 grey frames, median over the window.
  0 = sharp, towards 1 = blurry. A defined engine measure, comparable across windows of the
  engine, not a standard unit. Player rows are supplied by the calibrated player tracking stage.
"""

from __future__ import annotations

from collections.abc import Sequence
from pathlib import Path

import numpy as np
from scipy import ndimage

SCHEMA_PATH = (
    Path(__file__).resolve().parents[2] / "packages/contracts/schemas/window_result.v1.schema.json"
)


def grey(chw: np.ndarray) -> np.ndarray:
    """(3, H, W) RGB (0..255) -> (H, W) float luminance."""
    r, g, b = (chw[i].astype(np.float64) for i in range(3))
    return 0.299 * r + 0.587 * g + 0.114 * b


def _subpixel(c_m: float, c_0: float, c_p: float) -> float:
    """Parabolic peak offset from three samples around a maximum, in [-0.5, 0.5]."""
    denom = c_m - 2 * c_0 + c_p
    return 0.0 if denom == 0 else float(np.clip(0.5 * (c_m - c_p) / denom, -0.5, 0.5))


def global_shift(a: np.ndarray, b: np.ndarray) -> float:
    """Magnitude of the translation between two grey frames, by phase correlation (pixels)."""
    fa, fb = np.fft.fft2(a - a.mean()), np.fft.fft2(b - b.mean())
    cross = fa * np.conj(fb)
    corr = np.fft.ifft2(cross / (np.abs(cross) + 1e-9)).real
    py, px = np.unravel_index(np.argmax(corr), corr.shape)
    h, w = corr.shape
    fy = _subpixel(corr[(py - 1) % h, px], corr[py, px], corr[(py + 1) % h, px])
    fx = _subpixel(corr[py, (px - 1) % w], corr[py, px], corr[py, (px + 1) % w])
    dy = (py - h if py > h // 2 else py) + fy
    dx = (px - w if px > w // 2 else px) + fx
    return float(np.hypot(dx, dy))


def blur(g: np.ndarray) -> float:
    return 1.0 / (1.0 + float(ndimage.laplace(g).var()) / 100.0)


def quality(frames: Sequence[np.ndarray], frames_with_ball: int, source_scale: float) -> dict:
    """Window quality from the emitted (3, 288, 512) frames; source_scale = source width / 512.

    Raises on fewer than 2 frames: there is nothing to measure, and 0.0 would claim a sharp,
    still window that was never seen.
    """
    if len(frames) < 2:
        raise ValueError(f"need at least 2 emitted frames to measure quality, got {len(frames)}")
    greys = [grey(f) for f in frames]
    shifts = [global_shift(a, b) for a, b in zip(greys, greys[1:], strict=False)]
    return {
        "ball_coverage": round(frames_with_ball / len(frames), 4),
        "camera_motion_px": round(float(np.median(shifts)) * source_scale, 2),
        "blur_index": round(float(np.median([blur(g) for g in greys])), 4),
    }


def build(
    analysis_id: str,
    window_seq: int,
    decode_ms: tuple[int, int],
    emit_ms: tuple[int, int],
    ball_rows: list[list],
    quality_: dict,
    models: Sequence[str],
    onsets: Sequence[Sequence[int | float]] = (),
    events: Sequence[dict] = (),
    players: Sequence[list] = (),
) -> dict:
    """The window object, keeping only rows inside the emitted range."""
    lo, hi = emit_ms
    return {
        "schema": "window_result.v1",
        "analysis_id": analysis_id,
        "window_seq": window_seq,
        "decode_window_us": [decode_ms[0] * 1000, decode_ms[1] * 1000],
        "emit_range_us": [lo * 1000, hi * 1000],
        "models": list(models),
        "ball": [r for r in ball_rows if lo <= r[0] < hi],
        "players": [r for r in players if lo <= r[0] < hi],
        "onsets": [list(row) for row in onsets if lo <= row[0] < hi],
        "events": [dict(event) for event in events if lo <= event["t_ms"] < hi],
        "quality": quality_,
    }


def validate(window: dict) -> None:
    """Raise if the window does not match packages/contracts window_result.v1."""
    from elevate_engine.api import validate as validate_contract

    validate_contract("window_result.v1", window)


def from_file(
    tracknet,
    seq_len: int,
    path: Path,
    analysis_id: str,
    window_seq: int,
    emit_ms: tuple[int, int],
    overlap_ms: int = 750,
) -> tuple[dict, dict]:
    """Decode [emit_start, emit_end + overlap) of a video, run TrackNet, measure quality.

    Returns (window_result.v1, summary). Shared by the offline window_clip command and analyze.
    """
    from elevate_engine.tracknet import infer
    from elevate_engine.tracknet import run as tracknet_run
    from elevate_engine.video import frames_in_range, source_size

    lo, hi = emit_ms
    decode = (lo, hi + overlap_ms)
    decoded = list(frames_in_range(path, *decode, infer.resize_chw_uint8))
    t_ms = [f.t_ms for f in decoded]
    frames = [f.image for f in decoded]
    blobs = list(tracknet_run.track(tracknet, seq_len, frames))
    src_w, src_h = source_size(path)
    rows = tracknet_run.ball_rows(t_ms, blobs, src_w, src_h)
    emitted = [i for i, t in enumerate(t_ms) if lo <= t < hi]
    q = quality(
        [frames[i] for i in emitted], sum(1 for i in emitted if blobs[i]), src_w / infer.WIDTH
    )
    window = build(analysis_id, window_seq, decode, (lo, hi), rows, q, ["tracknet_v3"])
    summary = {"frames_decoded": len(t_ms), "frames_emitted": len(emitted),
               "ball_rows": len(window["ball"]), "quality": q}  # fmt: skip
    return window, summary
