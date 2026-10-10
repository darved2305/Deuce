"""Pipeline step 0: clip audit from the file's real presentation timestamps (04-vision-engine §3.2).

Times follow contracts §0: milliseconds from the first presented frame.
"""

from __future__ import annotations

import statistics
from fractions import Fraction
from pathlib import Path

import av

# A frame step longer than this multiple of the nominal step is a long step.
LONG_FACTOR = 1.5
# Steps may jitter by this fraction of the nominal step (or two timebase ticks, whichever is
# larger) before the clip counts as variable frame rate.
VFR_TOLERANCE = 0.10


def audit_pts(pts: list[int], time_base: Fraction) -> dict:
    """Summarise frame timing from raw PTS values (stream timebase units, any order).

    An isolated long step is a gap (dropped frames). Two or more long steps in a row are a
    slow segment: the frame rate itself changed, so the clip is variable frame rate.
    """
    if len(pts) < 2:
        raise ValueError(f"need at least 2 frames to audit timing, got {len(pts)}")
    ordered = sorted(pts)
    tick_ms = float(time_base) * 1000
    steps_ms = [(b - a) * tick_ms for a, b in zip(ordered, ordered[1:], strict=False)]
    duplicates = sum(1 for s in steps_ms if s == 0)
    positive = [s for s in steps_ms if s > 0]
    if not positive:
        raise ValueError("all frames share one PTS; cannot audit timing")
    long_limit_ms = LONG_FACTOR * statistics.median(positive)

    def at_ms(i: int) -> int:
        return round((ordered[i] - ordered[0]) * tick_ms)

    gaps, slow_segments = [], []
    i = 0
    while i < len(steps_ms):
        if steps_ms[i] <= long_limit_ms:
            i += 1
            continue
        j = i
        while j < len(steps_ms) and steps_ms[j] > long_limit_ms:
            j += 1
        run = steps_ms[i:j]
        if len(run) == 1:
            gaps.append({"after_ms": at_ms(i), "step_ms": round(run[0], 3)})
        else:
            slow_segments.append(
                {
                    "start_ms": at_ms(i),
                    "end_ms": at_ms(j),
                    "steps": len(run),
                    "median_step_ms": round(statistics.median(run), 3),
                }
            )
        i = j

    regular = sorted(s for s in positive if s <= long_limit_ms)
    # Mean, not median: timebase rounding (e.g. 33/34 ms at 1/1000) biases the median.
    nominal_ms = statistics.fmean(regular)
    p05 = regular[int(0.05 * (len(regular) - 1))]
    p95 = regular[int(0.95 * (len(regular) - 1))]
    tolerance_ms = max(2 * tick_ms, VFR_TOLERANCE * nominal_ms)

    return {
        "frames": len(ordered),
        "duration_ms": round((ordered[-1] - ordered[0]) * tick_ms),
        "nominal_step_ms": round(nominal_ms, 3),
        "nominal_fps": round(1000 / nominal_ms, 3),
        "step_p05_ms": round(p05, 3),
        "step_p95_ms": round(p95, 3),
        "vfr": (p95 - p05) > tolerance_ms or bool(slow_segments),
        "gaps": gaps,
        "slow_segments": slow_segments,
        "duplicate_pts": duplicates,
    }


def probe(path: str | Path) -> dict:
    """Audit the first video stream from packet timestamps, without decoding every frame.

    Only the first frame is decoded, to read the display rotation.
    """
    with av.open(str(path)) as container:
        stream = container.streams.video[0]
        pts: list[int] = []
        packets_without_pts = 0
        rotation = None
        for packet in container.demux(stream):
            if packet.size == 0:  # end-of-stream flush packet
                continue
            if packet.pts is None:
                packets_without_pts += 1
            else:
                pts.append(packet.pts)
            if rotation is None:
                for frame in packet.decode():
                    rotation = frame.rotation
                    break
        report = {
            "codec": stream.codec_context.name,
            "width": stream.width,
            "height": stream.height,
            "time_base": str(stream.time_base),
            "container_fps": str(stream.average_rate) if stream.average_rate else None,
            "rotation_deg": rotation,
            "packets_without_pts": packets_without_pts,
            # A missing PTS leaves a hole that would look like a dropped frame.
            "timing_reliable": packets_without_pts == 0,
        }
        report.update(audit_pts(pts, stream.time_base))
    return report
