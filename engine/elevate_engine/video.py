"""Decode a time range of a video into frames stamped with real presentation times.

Times follow contracts §0: milliseconds from the first presented frame of the stream.
"""

from __future__ import annotations

from collections.abc import Callable, Iterator
from dataclasses import dataclass
from pathlib import Path

import av
import numpy as np


@dataclass(frozen=True)
class Frame:
    t_ms: int
    image: np.ndarray  # whatever `transform` returned for the decoded RGB frame


def first_pts(container: av.container.InputContainer) -> tuple[int, float]:
    """PTS of the first presented frame and the stream timebase in seconds per tick."""
    stream = container.streams.video[0]
    container.seek(0, stream=stream)
    first = next(container.decode(stream))
    return first.pts, float(stream.time_base)


def frames_in_range(
    path: str | Path,
    start_ms: int,
    end_ms: int,
    transform: Callable[[np.ndarray], np.ndarray] = lambda rgb: rgb,
) -> Iterator[Frame]:
    """Yield every frame with start_ms <= t_ms < end_ms, in presentation order.

    Seeks to the keyframe before start_ms and decodes forward, so no frame is skipped or
    duplicated; frames before start_ms are decoded but not yielded.
    """
    with av.open(str(path)) as container:
        stream = container.streams.video[0]
        stream.thread_type = "AUTO"
        pts0, tick = first_pts(container)
        container.seek(pts0 + int(start_ms / 1000 / tick), stream=stream, backward=True)
        for frame in container.decode(stream):
            if frame.pts is None:
                continue
            t_ms = round((frame.pts - pts0) * tick * 1000)
            if t_ms < start_ms:
                continue
            if t_ms >= end_ms:
                break
            if frame.rotation:
                # to_ndarray does not rotate; coordinates would be wrong. Not supported yet.
                raise NotImplementedError(f"video rotated {frame.rotation} degrees")
            yield Frame(t_ms, transform(frame.to_ndarray(format="rgb24")))


def source_size(path: str | Path) -> tuple[int, int]:
    """(width, height) of the first video stream as decoded (rotation is not applied)."""
    with av.open(str(path)) as container:
        stream = container.streams.video[0]
        return stream.width, stream.height


def duration_ms(path: str | Path) -> int:
    """End of the last frame in ms from the first presented frame (packet PTS, no decoding)."""
    with av.open(str(path)) as container:
        stream = container.streams.video[0]
        pts = sorted(p.pts for p in container.demux(stream) if p.size and p.pts is not None)
        if len(pts) < 2:
            raise ValueError("need at least 2 frames")
        tick = float(stream.time_base)
        step = float(np.median(np.diff(pts[-50:]))) if len(pts) > 2 else pts[1] - pts[0]
        return round((pts[-1] - pts[0] + step) * tick * 1000)
