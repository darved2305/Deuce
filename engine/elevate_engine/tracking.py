"""Shuttle tracking layer (engine plan step 4): candidates per frame -> one trail.

- Constant-velocity Kalman filter on [x, y, vx, vy] in source pixels, stepped by real PTS deltas.
- Each live tracklet takes the candidate with the lowest gated cost: squared Mahalanobis
  distance of the innovation (gate: chi-square, 2 dof) minus a bonus for heatmap confidence.
- A candidate that no tracklet takes starts a new tracklet.
- A tracklet survives one missed frame; if it is matched on the next frame, the missed frame
  is filled by the filter's midpoint and marked "inferred" (never more than one frame).
- Tracklets that barely move (wall marks, floor spots: 90% of points within a small radius)
  or are too short are dropped.
- Per frame, the trail takes the point of the best surviving tracklet (longest, then most
  confident). Process noise and gates are sport configuration (elevate_engine.sports).
"""

from __future__ import annotations

from collections.abc import Sequence
from dataclasses import dataclass, field

import numpy as np

from elevate_engine.sports import TrackingConfig


@dataclass
class Tracklet:
    tid: int
    x: np.ndarray  # state [x, y, vx, vy]
    P: np.ndarray  # state covariance
    last_t: int
    points: list = field(default_factory=list)  # [t_ms, x, y, conf, state]
    missed: int = 0  # consecutive frames without a match

    def observed(self) -> list:
        return [p for p in self.points if p[4] == "observed"]


def _transition(dt_s: float) -> np.ndarray:
    F = np.eye(4)
    F[0, 2] = F[1, 3] = dt_s
    return F


def _process_noise(dt_s: float, accel_px_s2: float) -> np.ndarray:
    """Discrete white-acceleration noise for a constant-velocity model."""
    q = accel_px_s2**2
    dt2, dt3, dt4 = dt_s**2, dt_s**3, dt_s**4
    Q = np.zeros((4, 4))
    for i in (0, 1):
        Q[i, i] = dt4 / 4 * q
        Q[i, i + 2] = Q[i + 2, i] = dt3 / 2 * q
        Q[i + 2, i + 2] = dt2 * q
    return Q


H = np.array([[1.0, 0, 0, 0], [0, 1.0, 0, 0]])


def _predict(tr: Tracklet, t_ms: int, cfg: TrackingConfig) -> tuple[np.ndarray, np.ndarray]:
    dt = (t_ms - tr.last_t) / 1000.0
    F = _transition(dt)
    return F @ tr.x, F @ tr.P @ F.T + _process_noise(dt, cfg.accel_px_s2)


def _update(x, P, z, cfg: TrackingConfig):
    R = np.eye(2) * cfg.meas_sigma_px**2
    S = H @ P @ H.T + R
    K = P @ H.T @ np.linalg.inv(S)
    y = z - H @ x
    return x + K @ y, (np.eye(4) - K @ H) @ P


def _gated_cost(x, P, z, conf, cfg: TrackingConfig) -> float | None:
    S = H @ P @ H.T + np.eye(2) * cfg.meas_sigma_px**2
    y = z - H @ x
    d2 = float(y @ np.linalg.solve(S, y))
    if d2 > cfg.gate_chi2:
        return None
    return d2 - cfg.conf_bonus * (conf or 0.0)


def _new(tid: int, t: int, cand, cfg: TrackingConfig) -> Tracklet:
    x = np.array([cand[0], cand[1], 0.0, 0.0])
    P = np.diag([cfg.meas_sigma_px**2] * 2 + [cfg.init_speed_sigma_px_s**2] * 2)
    return Tracklet(tid, x, P, t, [[t, cand[0], cand[1], cand[2], "observed"]])


def is_static(tr: Tracklet, cfg: TrackingConfig) -> bool:
    """True if at least cfg.static_fraction of the observed points stay within
    cfg.static_radius_px of their median: one stray point must not turn a wall mark into a
    moving track."""
    pts = np.array([[p[1], p[2]] for p in tr.observed()])
    if len(pts) == 0:
        return True
    dist = np.linalg.norm(pts - np.median(pts, axis=0), axis=1)
    return float(np.mean(dist <= cfg.static_radius_px)) >= cfg.static_fraction


def track(
    t_ms: Sequence[int], candidates: Sequence[Sequence[tuple]], cfg: TrackingConfig
) -> tuple[list[list], dict]:
    """t_ms: every frame time. candidates[i]: [(x, y, conf), ...] for frame i.

    Returns (ball rows [t_ms, x, y, conf|None, state] with at most one row per frame, stats).
    """
    live: list[Tracklet] = []
    done: list[Tracklet] = []
    next_id = 0
    for t, cands in zip(t_ms, candidates, strict=True):
        free = list(range(len(cands)))
        preds = {tr.tid: _predict(tr, t, cfg) for tr in live}
        pairs = []
        for tr in live:
            xp, Pp = preds[tr.tid]
            for j in free:
                c = cands[j]
                cost = _gated_cost(xp, Pp, np.array(c[:2], float), c[2], cfg)
                if cost is not None:
                    pairs.append((cost, tr.tid, j))
        taken_tr, taken_c = set(), set()
        by_id = {tr.tid: tr for tr in live}
        for _cost, tid, j in sorted(pairs):
            if tid in taken_tr or j in taken_c:
                continue
            taken_tr.add(tid)
            taken_c.add(j)
            tr, (xp, Pp), c = by_id[tid], preds[tid], cands[j]
            if tr.missed == 1:  # one-frame gap: fill it from the filter, marked inferred
                # tr.last_t is the missed frame's real PTS and tr.x the prediction there.
                gap = [tr.last_t, float(tr.x[0]), float(tr.x[1]), None, "inferred"]
                tr.points.append(gap)
            tr.x, tr.P = _update(xp, Pp, np.array(c[:2], float), cfg)
            tr.last_t, tr.missed = t, 0
            tr.points.append([t, float(c[0]), float(c[1]), c[2], "observed"])
        still_live = []
        for tr in live:
            if tr.tid in taken_tr:
                still_live.append(tr)
            elif tr.missed + 1 <= cfg.max_missed:
                tr.x, tr.P = preds[tr.tid]
                tr.last_t, tr.missed = t, tr.missed + 1
                still_live.append(tr)
            else:
                done.append(tr)
        live = still_live
        for j in range(len(cands)):
            if j not in taken_c:
                live.append(_new(next_id, t, cands[j], cfg))
                next_id += 1
    done.extend(live)

    kept, static, short = [], 0, 0
    for tr in done:
        if tr.points and tr.points[-1][4] == "inferred":
            tr.points.pop()
        if len(tr.observed()) < cfg.min_observed:
            short += 1
        elif is_static(tr, cfg):
            static += 1
        else:
            kept.append(tr)
    best: dict[int, tuple] = {}
    for tr in kept:
        obs = tr.observed()
        rank = (len(obs), float(np.mean([p[3] for p in obs])))
        for p in tr.points:
            key = (p[4] == "observed", *rank)  # an observed point beats any inferred one
            if p[0] not in best or key > best[p[0]][0]:
                best[p[0]] = (key, p)
    rows = [best[t][1] for t in sorted(best)]
    stats = {
        "tracklets": len(done),
        "kept": len(kept),
        "dropped_static": static,
        "dropped_short": short,
        "frames_with_ball": len(rows),
        "inferred": sum(1 for r in rows if r[4] == "inferred"),
    }
    return [[int(r[0]), round(r[1], 1), round(r[2], 1), r[3], r[4]] for r in rows], stats


def candidates_by_frame(t_ms: Sequence[int], ball_rows: Sequence[Sequence]) -> list[list[tuple]]:
    """Group window_result ball rows ([t_ms, x, y, conf, state]) per frame time."""
    index = {t: i for i, t in enumerate(t_ms)}
    out: list[list[tuple]] = [[] for _ in t_ms]
    for r in ball_rows:
        if int(r[0]) in index:
            out[index[int(r[0])]].append((float(r[1]), float(r[2]), r[3]))
    return out
