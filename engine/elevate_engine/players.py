"""Associate D-FINE person detections and build contract-valid player rows.

Tracks use source-pixel boxes and time-aware centre prediction. The identity-switch count is a
geometric ambiguity signal: boxes alone cannot confirm whether two visually similar players
actually exchanged identities.
"""

from __future__ import annotations

from collections import defaultdict
from collections.abc import Sequence
from dataclasses import dataclass
from itertools import combinations

import numpy as np
from scipy.optimize import linear_sum_assignment

from elevate_engine import calibration

_INVALID_COST = 1e6
_MAX_SPEED_PX_PER_MS = 2.5
_SWITCH_AMBIGUITY_MARGIN = 0.08
# A person's box height cannot double between frames ~80 ms apart. On h13g at 42520 ms the far
# player stood right behind the near one: the near track's box overlapped the far box (IoU 0.37)
# and the centres were close, but the heights were 343 vs 166 px. Without this gate the near
# track jumped to the far player (5.4 m -> 13.2 m court y in one step).
_MAX_HEIGHT_RATIO = 1.6


@dataclass(frozen=True)
class PlayerTrack:
    """A retained track with observed D-FINE rows and a stable window_result track id."""

    track_id: str
    detections: tuple[tuple[int, float, float, float, float, float], ...]
    observed_duration_ms: float

    @property
    def first_t_ms(self) -> int:
        return self.detections[0][0]


@dataclass
class _Tracklet:
    index: int
    detections: list[tuple[int, float, float, float, float, float]]


def _normalise_detections(
    detections: Sequence[Sequence[float]],
) -> list[tuple[int, float, float, float, float, float]]:
    rows = []
    for index, row in enumerate(detections):
        if len(row) != 6:
            raise ValueError(f"person detection {index} must have 6 values, got {len(row)}")
        t_raw, x1, y1, x2, y2, score = (float(v) for v in row)
        if not np.all(np.isfinite([t_raw, x1, y1, x2, y2, score])):
            raise ValueError(f"person detection {index} must contain only finite numbers")
        if t_raw < 0 or not t_raw.is_integer():
            raise ValueError(f"person detection {index} t_ms must be a non-negative integer")
        if x2 <= x1 or y2 <= y1:
            raise ValueError(f"person detection {index} must have a positive-area box")
        rows.append((int(t_raw), x1, y1, x2, y2, score, index))
    rows.sort(key=lambda row: (row[0], row[6]))
    return [row[:6] for row in rows]


def _centre(box: Sequence[float]) -> np.ndarray:
    return np.array([(box[0] + box[2]) / 2, (box[1] + box[3]) / 2], dtype=float)


def _foot(box: Sequence[float]) -> np.ndarray:
    return np.array([(box[0] + box[2]) / 2, box[3]], dtype=float)


def _height_ratio(a: Sequence[float], b: Sequence[float]) -> float:
    ha, hb = max(a[3] - a[1], 1e-6), max(b[3] - b[1], 1e-6)
    return max(ha, hb) / min(ha, hb)


def _iou(a: Sequence[float], b: Sequence[float]) -> float:
    ix1, iy1 = max(a[0], b[0]), max(a[1], b[1])
    ix2, iy2 = min(a[2], b[2]), min(a[3], b[3])
    intersection = max(0.0, ix2 - ix1) * max(0.0, iy2 - iy1)
    area_a = max(0.0, a[2] - a[0]) * max(0.0, a[3] - a[1])
    area_b = max(0.0, b[2] - b[0]) * max(0.0, b[3] - b[1])
    union = area_a + area_b - intersection
    return intersection / union if union else 0.0


def _predicted_box(track: _Tracklet, t_ms: int, max_prediction_px: float) -> tuple[float, ...]:
    last = track.detections[-1]
    dx = dy = 0.0
    if len(track.detections) >= 2:
        previous = track.detections[-2]
        elapsed = last[0] - previous[0]
        if elapsed > 0:
            velocity = (_centre(last[1:5]) - _centre(previous[1:5])) / elapsed
            speed = float(np.linalg.norm(velocity))
            if speed > _MAX_SPEED_PX_PER_MS:
                velocity *= _MAX_SPEED_PX_PER_MS / speed
            displacement = velocity * (t_ms - last[0])
            distance = float(np.linalg.norm(displacement))
            if distance > max_prediction_px:
                displacement *= max_prediction_px / distance
            dx, dy = (float(v) for v in displacement)
    return last[1] + dx, last[2] + dy, last[3] + dx, last[4] + dy


def _association_cost(
    predicted_box: Sequence[float],
    detection: Sequence[float],
    max_center_distance_px: float,
) -> float:
    if _height_ratio(predicted_box, detection[1:5]) > _MAX_HEIGHT_RATIO:
        return _INVALID_COST
    overlap = _iou(predicted_box, detection[1:5])
    # Foot point (bottom centre): depth on court separates near and far players far better than
    # the box centre, and it is the point the calibration projects.
    distance = float(np.linalg.norm(_foot(predicted_box) - _foot(detection[1:5])))
    if distance > max_center_distance_px and overlap < 0.05:
        return _INVALID_COST
    distance_cost = min(distance / max_center_distance_px, 2.0)
    return 0.55 * (1.0 - overlap) + 0.45 * distance_cost


def _observed_duration(detections: Sequence[Sequence[float]], typical_step_ms: float) -> float:
    """Sum observed intervals, limiting a tolerated occlusion to one nominal frame interval."""
    cap_ms = max(typical_step_ms * 1.5, 1.0)
    return float(
        sum(min(b[0] - a[0], cap_ms) for a, b in zip(detections, detections[1:], strict=False))
    )


def track_players(
    detections: Sequence[Sequence[float]],
    *,
    format_: str = "singles",
    max_gap_ms: int = 500,
    min_track_length: int = 3,
    max_center_distance_px: float = 300.0,
) -> tuple[list[PlayerTrack], dict]:
    """Associate [t_ms, x1, y1, x2, y2, score] rows into stable player tracks.

    Hungarian costs combine predicted-box IoU and predicted-centre distance. A track remains
    eligible through `max_gap_ms` after its last observed box. Only the two longest observed
    tracks for singles or four for doubles are returned.
    """
    if format_ not in {"singles", "doubles"}:
        raise ValueError("format_ must be 'singles' or 'doubles'")
    if max_gap_ms < 0 or min_track_length < 1 or max_center_distance_px <= 0:
        raise ValueError("max_gap_ms, min_track_length, and max_center_distance_px are invalid")

    rows = _normalise_detections(detections)
    grouped: dict[int, list[tuple[int, float, float, float, float, float]]] = defaultdict(list)
    for row in rows:
        grouped[row[0]].append(row)
    times = sorted(grouped)
    time_steps = [b - a for a, b in zip(times, times[1:], strict=False) if b > a]
    typical_step_ms = float(np.median(time_steps)) if time_steps else 1.0

    active: list[_Tracklet] = []
    finished: list[_Tracklet] = []
    next_index = 0
    switch_frames: set[int] = set()

    for t_ms in times:
        frame = grouped[t_ms]
        eligible = [tr for tr in active if t_ms - tr.detections[-1][0] <= max_gap_ms]
        finished.extend(tr for tr in active if tr not in eligible)
        active = eligible

        assignments: list[tuple[int, int]] = []
        if active and frame:
            costs = np.full((len(active), len(frame)), _INVALID_COST, dtype=float)
            for i, track in enumerate(active):
                prediction = _predicted_box(
                    track, t_ms, max_prediction_px=max_center_distance_px * 1.5
                )
                for j, detection in enumerate(frame):
                    costs[i, j] = _association_cost(prediction, detection, max_center_distance_px)
            track_indices, detection_indices = linear_sum_assignment(costs)
            for i, j in zip(track_indices, detection_indices, strict=True):
                if costs[i, j] < _INVALID_COST:
                    active[i].detections.append(frame[j])
                    assignments.append((i, j))

            # With boxes alone, only ambiguous two-track matches can be flagged as possible
            # identity switches. Count one event per frame even if multiple pairs are ambiguous.
            for (i, j), (k, ell) in combinations(assignments, 2):
                direct = costs[i, j] + costs[k, ell]
                crossed = costs[i, ell] + costs[k, j]
                close_boxes = _iou(frame[j][1:5], frame[ell][1:5]) >= 0.1
                if close_boxes and abs(direct - crossed) <= _SWITCH_AMBIGUITY_MARGIN:
                    switch_frames.add(t_ms)

        matched_detections = {j for _, j in assignments}
        for j, detection in enumerate(frame):
            if j not in matched_detections:
                active.append(_Tracklet(next_index, [detection]))
                next_index += 1

    finished.extend(active)
    qualified = [tr for tr in finished if len(tr.detections) >= min_track_length]
    dropped_short = len(finished) - len(qualified)
    limit = 2 if format_ == "singles" else 4
    ranked = sorted(
        qualified,
        key=lambda tr: (
            -_observed_duration(tr.detections, typical_step_ms),
            -len(tr.detections),
            tr.detections[0][0],
            tr.index,
        ),
    )
    selected = ranked[:limit]
    dropped_by_limit = len(ranked) - len(selected)
    selected.sort(key=lambda tr: (tr.detections[0][0], tr.index))
    tracks = [
        PlayerTrack(
            track_id=f"t_{i}",
            detections=tuple(tr.detections),
            observed_duration_ms=_observed_duration(tr.detections, typical_step_ms),
        )
        for i, tr in enumerate(selected, start=1)
    ]
    stats = {
        "format": format_,
        "max_tracks": limit,
        "tracks_created": len(finished),
        "kept_tracks": len(tracks),
        "dropped_tracks": dropped_short + dropped_by_limit,
        "dropped_short": dropped_short,
        "dropped_by_limit": dropped_by_limit,
        "identity_switches": len(switch_frames),
        "identity_switch_frames_ms": sorted(switch_frames),
        "identity_switch_basis": "ambiguous_two_track_hungarian_assignment",
        "tracks": [
            {
                "track_id": tr.track_id,
                "first_t_ms": tr.first_t_ms,
                "observations": len(tr.detections),
                "observed_duration_ms": round(tr.observed_duration_ms, 1),
            }
            for tr in tracks
        ],
    }
    return tracks, stats


def build_window_rows(
    tracks: Sequence[PlayerTrack],
    emit_range_ms: tuple[int, int],
    calibration_: calibration.Calibration | None,
) -> tuple[list[list], dict]:
    """Build observed player rows inside one emitted range, projecting feet through calibration."""
    lo, hi = emit_range_ms
    if lo < 0 or hi < lo:
        raise ValueError(f"invalid emitted range {emit_range_ms}")
    emitted = [
        (track, detection)
        for track in tracks
        for detection in track.detections
        if lo <= detection[0] < hi
    ]
    # Contracts 1.8.0 (HW14 item 4): foot metres are nullable. A row is always emitted for an
    # observed box; its feet are null (withheld) without a calibration, on a tier C calibration,
    # above the court's horizon or more than OFF_COURT_MARGIN_M off the court.
    rows, no_cal, tier_c, off_court = [], 0, 0, 0
    for track, detection in emitted:
        t_ms, x1, y1, x2, y2, score = detection
        foot = None
        if calibration_ is None:
            no_cal += 1
        else:
            foot = calibration.player_metres(
                calibration_.h, detection[1:5], calibration_.tier, calibration_.template
            )
            if foot is None:
                if calibration_.tier == "C":
                    tier_c += 1
                else:
                    off_court += 1
        rows.append(
            [
                t_ms,
                track.track_id,
                round(x1, 2),
                round(y1, 2),
                round(x2, 2),
                round(y2, 2),
                None if foot is None else round(foot[0], 4),
                None if foot is None else round(foot[1], 4),
                round(score, 4),
            ]
        )
    rows.sort(key=lambda row: (row[0], int(row[1][2:])))
    return rows, {
        "calibration_available": calibration_ is not None,
        "rows_emitted": len(rows),
        "feet_withheld": no_cal + tier_c + off_court,
        "feet_withheld_no_calibration": no_cal,
        "feet_withheld_tier_c": tier_c,
        "feet_withheld_off_court": off_court,
    }
