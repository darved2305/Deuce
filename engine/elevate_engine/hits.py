"""Candidate hit events from observed shuttle direction changes and audio onsets."""

from __future__ import annotations

import hashlib
import math
from collections.abc import Sequence

import numpy as np


def _observed_rows(trail: Sequence[Sequence]) -> list[list]:
    rows = [
        row
        for row in trail
        if len(row) >= 5 and row[4] == "observed" and np.isfinite(row[1:3]).all()
    ]
    rows.sort(key=lambda row: int(row[0]))
    unique: dict[int, list] = {}
    for row in rows:
        unique[int(row[0])] = row
    return [unique[t] for t in sorted(unique)]


def shuttle_track_id(analysis_id: str) -> str:
    """The track id window events use for the shuttle trail of one analysis (not a player)."""
    return "t_" + hashlib.sha256(f"{analysis_id}:shuttle-trail".encode()).hexdigest()[:16]


def detect_direction_changes(
    trail: Sequence[Sequence],
    *,
    velocity_window: int = 2,
    min_angle_deg: float = 45,
    min_displacement_px: float = 8.0,  # ~200 px/s over 40 ms at 1080p: real flight, not jitter
    min_gap_ms: int = 100,
    max_secant_ms: int = 150,  # a secant longer than this spans a tracking gap or a switch
    apex_speed_px_per_ms: float = 0.375,  # ~15 px per 40 ms vertical: slower is a flight apex
) -> list[dict]:
    """Find robust local reversals in observed rows only.

    A three-row median suppresses single-frame location noise. Direction uses secants across
    ``velocity_window`` observations on either side, so a missing frame does not alter the
    timing basis. A vertical-velocity sign change or a turn of at least ``min_angle_deg`` is
    sufficient. ``image_xy`` is kept from the actual observed trail row at the candidate time.
    """
    rows = _observed_rows(trail)
    if velocity_window < 1:
        raise ValueError("velocity_window must be at least 1")
    if len(rows) < 2 * velocity_window + 1:
        return []

    times = np.asarray([int(row[0]) for row in rows], dtype=np.int64)
    xy = np.asarray([[float(row[1]), float(row[2])] for row in rows], dtype=np.float64)
    smooth = xy.copy()
    for i in range(1, len(xy) - 1):
        smooth[i] = np.median(xy[i - 1 : i + 2], axis=0)

    candidates = []
    cos_limit = math.cos(math.radians(min_angle_deg))
    for i in range(velocity_window, len(rows) - velocity_window):
        before = smooth[i] - smooth[i - velocity_window]
        after = smooth[i + velocity_window] - smooth[i]
        before_dt = int(times[i] - times[i - velocity_window])
        after_dt = int(times[i + velocity_window] - times[i])
        if before_dt <= 0 or after_dt <= 0:
            continue
        if max(before_dt, after_dt) > max_secant_ms:
            continue
        before_v = before / before_dt
        after_v = after / after_dt
        before_norm, after_norm = float(np.linalg.norm(before)), float(np.linalg.norm(after))
        if min(before_norm, after_norm) < min_displacement_px:
            continue
        # Apex of a flight: rising (image y decreasing) then falling, same horizontal direction,
        # slowly on both sides. That is gravity, not a racket contact. A far-court hit also
        # goes from rising to falling with the same x direction in the image, but the shuttle
        # is fast on both sides of the contact, so only a slow reversal counts as an apex.
        slow_reversal = min(abs(float(before_v[1])), abs(float(after_v[1]))) < apex_speed_px_per_ms
        if before[1] < 0 < after[1] and before[0] * after[0] >= 0 and slow_reversal:
            continue
        vertical_reversal = (
            before[1] * after[1] < 0
            and min(abs(float(before[1])), abs(float(after[1]))) >= min_displacement_px
        )
        cosine = float(np.dot(before, after) / (before_norm * after_norm))
        turning_angle = math.acos(float(np.clip(cosine, -1, 1)))
        large_turn = cosine <= cos_limit
        if not vertical_reversal and not large_turn:
            continue
        support_ms = int(times[i + velocity_window] - times[i - velocity_window])
        candidates.append(
            {
                "t_ms": int(times[i]),
                "image_xy": [float(xy[i, 0]), float(xy[i, 1])],
                "t_sigma_ms": max(1, math.ceil(support_ms / 4)),
                "score": max(
                    turning_angle,
                    math.atan2(abs(before_v[1] - after_v[1]), abs(before_v[0] - after_v[0]) + 1e-9),
                ),
            }
        )

    accepted = []
    for candidate in sorted(candidates, key=lambda item: (-item["score"], item["t_ms"])):
        if all(abs(candidate["t_ms"] - other["t_ms"]) >= min_gap_ms for other in accepted):
            accepted.append(candidate)
    return [{key: value for key, value in item.items() if key != "score"}
            for item in sorted(accepted, key=lambda item: item["t_ms"])]  # fmt: skip


def _nearest_row(rows: Sequence[Sequence], t_ms: int, tolerance_ms: int) -> list | None:
    if not rows:
        return None
    row = min(rows, key=lambda value: (abs(int(value[0]) - t_ms), int(value[0])))
    return row if abs(int(row[0]) - t_ms) <= tolerance_ms else None


def _fuse_time(t1: int, sigma1: int, t2: int, sigma2: int) -> tuple[int, int]:
    weight1, weight2 = 1 / sigma1**2, 1 / sigma2**2
    time_ms = round((t1 * weight1 + t2 * weight2) / (weight1 + weight2))
    sigma_ms = max(1, round(math.sqrt(1 / (weight1 + weight2))))
    return time_ms, sigma_ms


def estimate_av_offset_ms(
    turns: Sequence[dict],
    onsets: Sequence[Sequence],
    *,
    search_ms: int = 250,
    min_pairs: int = 6,
    max_iqr_ms: float = 60.0,
) -> int:
    """Audio-minus-video lag: the median of (strongest onset within search_ms - turn time) over
    the given turns, or 0 when it is not well determined (fewer than min_pairs pairs, or an
    interquartile range above max_iqr_ms). Measured on h13g 31-91 s: median 130 ms, IQR
    110-145 ms over 33 pairs; the turn timing uses symmetric secants, so the lag is the clip's
    audio/video offset, not a detection bias."""
    lags = []
    for turn in turns:
        near = [o for o in onsets if len(o) >= 2 and abs(int(o[0]) - turn["t_ms"]) <= search_ms]
        if near:
            lags.append(int(max(near, key=lambda o: float(o[1]))[0]) - turn["t_ms"])
    if len(lags) < min_pairs:
        return 0
    q1, med, q3 = np.percentile(lags, [25, 50, 75])
    return int(round(med)) if q3 - q1 <= max_iqr_ms else 0


def hit_candidates(
    trail: Sequence[Sequence],
    onsets: Sequence[Sequence],
    analysis_id: str,
    window_seq: int,
    *,
    tolerance_ms: int = 100,
    onset_sigma_ms: int = 10,
    min_event_gap_ms: int = 300,  # two hits need the shuttle to cross: ~300 ms minimum
    onset_offset_ms: int = 0,
) -> list[dict]:
    """Fuse observed trajectory turns and nearby audio onsets into schema-shaped hits.

    ``track_id`` names the observed shuttle trail for this analysis. It does not associate a
    player; the current tracking output does not expose per-track IDs or player linkage.
    Onsets are shifted by -onset_offset_ms (estimate_av_offset_ms) before matching and fusing.
    """
    rows = _observed_rows(trail)
    turns = detect_direction_changes(trail)
    peaks = sorted(
        [(int(row[0]) - onset_offset_ms, float(row[1])) for row in onsets if len(row) >= 2],
        key=lambda item: item[0],
    )
    pairs = sorted(
        (abs(turn["t_ms"] - peak[0]), ti, pi)
        for ti, turn in enumerate(turns)
        for pi, peak in enumerate(peaks)
        if abs(turn["t_ms"] - peak[0]) <= tolerance_ms
    )
    matched_turns: dict[int, int] = {}
    matched_peaks: set[int] = set()
    for _delta, ti, pi in pairs:
        if ti not in matched_turns and pi not in matched_peaks:
            matched_turns[ti] = pi
            matched_peaks.add(pi)

    track_id = shuttle_track_id(analysis_id)
    candidates = []

    def add_candidate(t_ms: int, sigma_ms: int, xy: Sequence[float], cues: list[str]) -> None:
        event_hash = hashlib.sha256(f"{analysis_id}:{window_seq}:{t_ms}".encode()).hexdigest()[:24]
        candidates.append(
            {
                "event_id": f"e_{event_hash}",
                "type": "hit",
                "t_ms": t_ms,
                "t_sigma_ms": sigma_ms,
                "track_id": track_id,
                "image_xy": [float(xy[0]), float(xy[1])],
                "cues": cues,
                "state": "candidate",
            }
        )

    for ti, turn in enumerate(turns):
        peak_index = matched_turns.get(ti)
        event_time, sigma = turn["t_ms"], turn["t_sigma_ms"]
        cues = ["trajectory"]
        if peak_index is not None:
            event_time, sigma = _fuse_time(
                turn["t_ms"], turn["t_sigma_ms"], peaks[peak_index][0], onset_sigma_ms
            )
            cues.append("audio")
        nearest = _nearest_row(rows, event_time, tolerance_ms)
        if nearest is not None:
            add_candidate(event_time, sigma, nearest[1:3], cues)

    # Audio-only onsets stay in window_result.onsets but do not become hit events: on real
    # footage many are footsteps or neighbouring courts. Merge turns closer than the gap.
    kept: list[dict] = []
    for event in sorted(candidates, key=lambda e: (-len(e["cues"]), e["t_ms"])):
        if all(abs(event["t_ms"] - other["t_ms"]) >= min_event_gap_ms for other in kept):
            kept.append(event)
    return sorted(kept, key=lambda event: (event["t_ms"], event["event_id"]))
