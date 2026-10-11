"""Landing candidates: where an observed shuttle flight ends on the floor, and where on court.

A flight is a run of observed trail rows with no gap over FLIGHT_GAP_MS. Its end is a landing
candidate when the flight has at least MIN_FLIGHT_POINTS, it is not rising as it ends (a shuttle
leaving the top of the frame), it does not end at the image's top edge, and the shuttle is then
unseen for at least QUIET_MS within the known range (the rally is over or the shuttle lies on
the floor). The time and image point are where the shuttle comes to rest: the first of the
trailing points that move less than REST_PX. Measured on h13g 31-91 s against Darved's labels:
at 41640 ms the labelled shuttle stops at (907, 738) and is still there at 42280 ms before the
next serve; flights that end at y < 10 px left the frame.

Court metres come only from a calibration (homography is valid for surface points, and a
landed shuttle is on the surface). cov_m2 propagates the calibration's H covariance and a
stated 2 px image error through the projection; tier C calibrations give no metres.
"""

from __future__ import annotations

import hashlib
import math
from collections.abc import Sequence

import numpy as np

FLIGHT_GAP_MS = 200
MIN_FLIGHT_POINTS = 6
QUIET_MS = 1000
REST_PX = 4.0
MAX_RISE_PX = 8.0  # over the last 3 steps: rising faster than this means leaving the view
TOP_EDGE_PX = 16.0
IMAGE_SIGMA_PX = 2.0  # assumed shuttle localisation error (same basis as TAP_SIGMA_PX)


def _flights(trail: Sequence[Sequence]) -> list[list]:
    rows = sorted((r for r in trail if len(r) >= 5 and r[4] == "observed"), key=lambda r: r[0])
    flights: list[list] = []
    for r in rows:
        if flights and r[0] - flights[-1][-1][0] <= FLIGHT_GAP_MS:
            flights[-1].append(r)
        else:
            flights.append([r])
    return flights


def detect_landings(trail: Sequence[Sequence], known_until_ms: int) -> list[dict]:
    """[{t_ms, image_xy, t_sigma_ms}] for flights that end on the floor. known_until_ms is the end
    of the range the trail covers: a flight ending less than QUIET_MS before it is undecided."""
    flights = _flights(trail)
    out = []
    for i, f in enumerate(flights):
        if len(f) < MIN_FLIGHT_POINTS:
            continue
        end_t = int(f[-1][0])
        next_t = int(flights[i + 1][0][0]) if i + 1 < len(flights) else known_until_ms
        if next_t - end_t < QUIET_MS:
            continue
        xy = np.array([[float(r[1]), float(r[2])] for r in f])
        if xy[-1, 1] - xy[-4, 1] < -MAX_RISE_PX or xy[-1, 1] < TOP_EDGE_PX:
            continue
        rest = len(f) - 1
        while rest > 0 and float(np.linalg.norm(xy[rest] - xy[rest - 1])) < REST_PX:
            rest -= 1
        if rest < MIN_FLIGHT_POINTS // 2:  # it never really moved: a static blob, not a flight
            continue
        steps = np.diff([int(r[0]) for r in f])
        out.append({
            "t_ms": int(f[rest][0]),
            "image_xy": [float(xy[rest, 0]), float(xy[rest, 1])],
            "t_sigma_ms": max(1, math.ceil(float(np.median(steps)) / 2)),
        })  # fmt: skip
    return out


def window_events(
    landings: Sequence[dict], analysis_id: str, window_seq: int, track_id: str
) -> list[dict]:
    """window_result.v1 events (type "landing", state "candidate") for detected landings."""
    events = []
    for lnd in landings:
        key = f"{analysis_id}:{window_seq}:landing:{lnd['t_ms']}"
        events.append({
            "event_id": "e_" + hashlib.sha256(key.encode()).hexdigest()[:24],
            "type": "landing", "t_ms": lnd["t_ms"], "t_sigma_ms": lnd["t_sigma_ms"],
            "track_id": track_id, "image_xy": lnd["image_xy"],
            "cues": ["trajectory"], "state": "candidate",
        })  # fmt: skip
    return events


def _project(h: np.ndarray, x: float, y: float) -> np.ndarray:
    p = h @ np.array([x, y, 1.0])
    return p[:2] / p[2]


def court_point(calibration: dict | None, image_xy: Sequence[float]) -> dict | None:
    """{contact_xy_m, cov_m2, plane} for an image point on the court surface, or None without a
    usable calibration (none, or tier C)."""
    if not calibration or calibration.get("tier") not in ("A", "B"):
        return None
    h = np.array(calibration["H"], dtype=float).reshape(3, 3)
    x, y = float(image_xy[0]), float(image_xy[1])
    # On or beyond the court plane's horizon (e.g. a shuttle held high): no surface point. The
    # front side is where the court centre (2.5 m, 6.7 m) appears in the image.
    try:
        centre = np.linalg.inv(h) @ np.array([2.5, 6.7, 1.0])
    except np.linalg.LinAlgError:
        return None
    if abs(centre[2]) < 1e-12:
        return None
    front = float(h[2] @ np.array([centre[0] / centre[2], centre[1] / centre[2], 1.0]))
    w = float(h[2] @ np.array([x, y, 1.0]))
    if abs(w) < 1e-9 or np.sign(w) != np.sign(front):
        return None
    xy_m = _project(h, x, y)
    eps = 1e-6
    jac_h = np.zeros((2, 9))
    for k in range(9):
        dh = np.zeros(9)
        dh[k] = eps * max(1.0, abs(h.ravel()[k]))
        jac_h[:, k] = (_project(h + dh.reshape(3, 3), x, y) - xy_m) / dh[k]
    jac_px = np.column_stack([(_project(h, x + 1e-3, y) - xy_m) / 1e-3,
                              (_project(h, x, y + 1e-3) - xy_m) / 1e-3])  # fmt: skip
    cov = jac_px @ jac_px.T * IMAGE_SIGMA_PX**2
    if calibration.get("H_cov") is not None:
        cov = cov + jac_h @ np.array(calibration["H_cov"], dtype=float).reshape(9, 9) @ jac_h.T
    if not (np.all(np.isfinite(xy_m)) and np.all(np.isfinite(cov))):
        return None
    return {
        "contact_xy_m": [round(float(v), 4) for v in xy_m],
        "cov_m2": [[round(float(v), 6) for v in row] for row in cov],
        "plane": "court",
    }
