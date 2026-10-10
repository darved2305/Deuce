"""Run the TrackNet port on decoded video frames and emit window_result.v1 ball rows."""

from __future__ import annotations

from collections.abc import Iterator, Sequence

import numpy as np

from elevate_engine.tracknet import infer


def windows(model, median: np.ndarray, frames: Sequence[np.ndarray], seq_len: int, batch: int = 16):
    """Yield TrackNet's (seq_len, H, W) heatmaps for every step-1 window, in order.

    Input as the official dataset builds it: [median, f_i .. f_i+7] as (3, 288, 512) arrays,
    float64 / 255, then float32 on the GPU.
    """
    import torch

    n_win = len(frames) - seq_len + 1
    for start in range(0, n_win, batch):
        x = np.stack(
            [
                np.concatenate([median, *frames[i : i + seq_len]]) / 255.0
                for i in range(start, min(start + batch, n_win))
            ]
        )
        with torch.no_grad():
            yield from model(torch.from_numpy(x).float().cuda()).cpu().numpy()


def sample_background(path, start_ms: int, end_ms: int, samples: int = 300) -> np.ndarray:
    """Median of `samples` frames spread evenly over [start_ms, end_ms) of a video, as float32
    (3, 288, 512): one background for a whole analysis, like the official predict.py."""
    from elevate_engine.video import frames_in_range

    step = max(1, (end_ms - start_ms) // samples)
    picked = []
    for t in range(start_ms, end_ms, step):
        frame = next(frames_in_range(path, t, min(end_ms, t + step), infer.resize_chw_uint8), None)
        if frame is not None:
            picked.append(frame.image)
    if not picked:
        raise ValueError(f"no frames decoded in {start_ms}..{end_ms}")
    return np.median(np.stack(picked), axis=0).astype(np.float32)


def background(frames: Sequence[np.ndarray], max_samples: int = 300) -> np.ndarray:
    """Median of up to max_samples evenly spaced (3, 288, 512) frames, as float64.

    The official predict.py takes the median at source resolution and then resizes; we take it
    on the already-resized frames to bound memory. Same idea, not bit-identical.
    """
    step = max(1, len(frames) // max_samples)
    return np.median(np.stack(frames[::step]), axis=0)


def track(
    model, seq_len: int, frames: Sequence[np.ndarray], median: np.ndarray | None = None
) -> Iterator[list[infer.Blob]]:
    """All heatmap blobs per frame (largest box first), one list per input frame.

    median: a shared (3, 288, 512) background (whole-video pipeline); default: these frames'."""
    if len(frames) < seq_len:
        # No complete window: no heatmap, so no candidates (official behaviour), one empty list
        # per frame so callers can still zip frames with results.
        yield from ([] for _ in frames)
        return
    n_win = len(frames) - seq_len + 1
    # float64 like the official input path; a shared background travels as float32.
    bg = background(frames) if median is None else np.asarray(median, dtype=np.float64)
    heatmaps = infer.ensemble_stream(windows(model, bg, frames, seq_len), n_win)
    for heat in heatmaps:
        yield infer.blobs(heat)


def ball_rows(
    t_ms: Sequence[int], blobs_per_frame: Sequence[list[infer.Blob]], src_w: int, src_h: int
) -> list[list]:
    """window_result.v1 ball rows: [t_ms, x_px, y_px, confidence, "candidate"] per blob.

    Every blob is kept (engine plan: keep all candidates); x/y are box centres scaled from the
    512x288 model input to source pixels; confidence is the blob's peak heatmap value.
    """
    sx, sy = src_w / infer.WIDTH, src_h / infer.HEIGHT
    return [
        [t, round(b.cx * sx, 1), round(b.cy * sy, 1), round(b.peak, 4), "candidate"]
        for t, blobs in zip(t_ms, blobs_per_frame, strict=True)
        for b in blobs
    ]
