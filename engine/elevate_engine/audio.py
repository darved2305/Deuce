"""Audio onset candidates aligned to the video presentation-time origin."""

from __future__ import annotations

from pathlib import Path

import av
import numpy as np

from elevate_engine.video import first_pts


def relative_time_ms(
    pts: int, time_base: float, video_first_pts: int, video_time_base: float
) -> int:
    """Convert a stream PTS to milliseconds from the first presented video frame."""
    return round(_relative_seconds(pts, time_base, video_first_pts, video_time_base) * 1000)


def _relative_seconds(
    pts: int, time_base: float, video_first_pts: int, video_time_base: float
) -> float:
    return pts * time_base - video_first_pts * video_time_base


def _decode_mono_range(
    path: str | Path, start_ms: int, end_ms: int, sample_rate: int
) -> tuple[np.ndarray, int]:
    """Decode audio into a mono float buffer on the video-relative sample grid."""
    if start_ms < 0 or end_ms < start_ms:
        raise ValueError(f"invalid audio range {start_ms}..{end_ms}")
    if sample_rate <= 0:
        raise ValueError(f"sample_rate must be positive, got {sample_rate}")

    first_sample = round(start_ms * sample_rate / 1000)
    end_sample = round(end_ms * sample_rate / 1000)
    samples = np.zeros(max(0, end_sample - first_sample), dtype=np.float32)
    if not len(samples):
        return samples, first_sample

    with av.open(str(path)) as container:
        if not container.streams.audio or not container.streams.video:
            return samples, first_sample

        video_first, video_tick = first_pts(container)
        video_origin_s = video_first * video_tick
        audio_stream = container.streams.audio[0]
        audio_tick = float(audio_stream.time_base)
        target_s = video_origin_s + start_ms / 1000
        container.seek(round(target_s / audio_tick), stream=audio_stream, backward=True)

        resampler = av.AudioResampler(format="flt", layout="mono", rate=sample_rate)
        range_end_s = video_origin_s + end_ms / 1000

        def copy_frame(frame) -> bool:
            if frame.pts is None or frame.time_base is None:
                return True
            relative_start_s = _relative_seconds(
                frame.pts, float(frame.time_base), video_first, video_tick
            )
            frame_start_s = video_origin_s + relative_start_s
            if frame_start_s >= range_end_s:
                return False
            mono = np.asarray(frame.to_ndarray(), dtype=np.float32).reshape(-1)
            frame_first = round(relative_start_s * sample_rate)
            src_lo = max(0, first_sample - frame_first)
            dst_lo = max(0, frame_first - first_sample)
            count = min(len(mono) - src_lo, len(samples) - dst_lo)
            if count > 0:
                samples[dst_lo : dst_lo + count] = mono[src_lo : src_lo + count]
            return True

        for decoded in container.decode(audio_stream):
            for converted in resampler.resample(decoded):
                if not copy_frame(converted):
                    return samples, first_sample
        for converted in resampler.resample(None):
            if not copy_frame(converted):
                break

    return samples, first_sample


def detect_onsets(
    mono: np.ndarray,
    sample_rate: int,
    *,
    offset_ms: float = 0,
    frame_ms: float = 20,
    hop_ms: float = 5,
    threshold_window_ms: float = 500,
    threshold_k: float = 3.0,
    min_gap_ms: float = 80,
) -> list[list[int | float]]:
    """Detect spectral-flux peaks, with strength normalized within this decoded range."""
    signal = np.asarray(mono, dtype=np.float32).reshape(-1)
    if sample_rate <= 0:
        raise ValueError(f"sample_rate must be positive, got {sample_rate}")
    if not len(signal):
        return []
    n_fft = max(8, round(sample_rate * frame_ms / 1000))
    hop = max(1, round(sample_rate * hop_ms / 1000))
    window = np.hanning(n_fft).astype(np.float32)
    pad = n_fft // 2
    padded = np.pad(signal, (pad, pad))
    starts = np.arange(0, len(signal), hop)
    offsets = np.arange(n_fft)
    frames = padded[starts[:, None] + offsets]
    magnitude = np.abs(np.fft.rfft(frames * window, axis=1))
    flux = np.zeros(len(magnitude), dtype=np.float64)
    if len(flux) > 1:
        flux[1:] = np.maximum(magnitude[1:] - magnitude[:-1], 0).sum(axis=1)

    radius = max(1, round(threshold_window_ms / hop_ms / 2))
    thresholds = np.empty(len(flux), dtype=np.float64)
    for i in range(len(flux)):
        local = flux[max(0, i - radius) : min(len(flux), i + radius + 1)]
        median = float(np.median(local))
        mad = float(np.median(np.abs(local - median)))
        thresholds[i] = median + threshold_k * 1.4826 * mad

    peaks = [
        i
        for i in range(1, len(flux) - 1)
        if flux[i] > thresholds[i] and flux[i] >= flux[i - 1] and flux[i] > flux[i + 1]
    ]
    gap_frames = max(1, round(min_gap_ms / hop_ms))
    chosen: list[int] = []
    for i in sorted(peaks, key=lambda index: (-flux[index], index)):
        if all(abs(i - previous) >= gap_frames for previous in chosen):
            chosen.append(i)
    chosen.sort()
    peak_max = max((flux[i] for i in chosen), default=0.0)
    # A centered STFT window first sees a transient before its center reaches the transient.
    frame_latency_ms = n_fft * 500 / sample_rate
    return [
        [
            round(offset_ms + starts[i] * 1000 / sample_rate + frame_latency_ms),
            float(np.clip(flux[i] / peak_max, 0, 1)) if peak_max else 0.0,
        ]
        for i in chosen
    ]


def onsets(
    path: str | Path,
    start_ms: int,
    end_ms: int,
    *,
    sample_rate: int = 16_000,
    frame_ms: float = 20,
    hop_ms: float = 5,
    threshold_window_ms: float = 500,
    threshold_k: float = 3.0,
    min_gap_ms: float = 80,
) -> list[list[int | float]]:
    """Decode a video's audio range and return ``[t_ms, strength]`` onset candidates.

    ``t_ms`` uses contract §0 time: from the first presented video frame. Strength is
    spectral flux relative to the strongest selected peak in the decoded range.
    """
    mono, first_sample = _decode_mono_range(path, start_ms, end_ms, sample_rate)
    offset_ms = first_sample * 1000 / sample_rate
    found = detect_onsets(
        mono,
        sample_rate,
        offset_ms=offset_ms,
        frame_ms=frame_ms,
        hop_ms=hop_ms,
        threshold_window_ms=threshold_window_ms,
        threshold_k=threshold_k,
        min_gap_ms=min_gap_ms,
    )
    return [row for row in found if start_ms <= row[0] < end_ms]
