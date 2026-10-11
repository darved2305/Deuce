"""Court calibration from the four corner taps (engine plan `04` §2 stage 1, Track S).

The app sends `session_spec.calibration_input.points_px` (contracts §2): the four named corners
of the court template in source pixels after rotation (contracts §0). From them we fit the court
homography `H` with OpenCV and derive everything the record needs:

- `H` maps image pixels -> court metres (x across from the camera's left sideline, y along from
  the near baseline). `Hinv` maps court metres -> image pixels. Both are 3x3, scaled so the
  bottom-right entry is 1.
- Four points fix a homography exactly, so the reprojection residual of the taps is ~0 by
  construction; it only becomes informative if more points are ever sent. The real quality
  signals are therefore the tap geometry (convex, not mirrored), the metre uncertainty from tap
  jitter (Monte Carlo), and how much of the court is inside the frame.
- A homography maps **surface points only** (contracts §0): use it for feet and floor contacts,
  never for an airborne shuttle.
"""

from __future__ import annotations

import hashlib
import json
from dataclasses import dataclass

import cv2
import numpy as np

CORNERS = ("near_left", "near_right", "far_right", "far_left")

# Badminton court (BWF Laws, outer edges of the lines; line width ignored).
LENGTH_M = 13.40
DOUBLES_WIDTH_M = 6.10
SINGLES_INSET_M = 0.46  # singles sideline inside each doubles sideline (6.10 - 5.18) / 2
NET_Y_M = LENGTH_M / 2  # 6.70
SHORT_SERVICE_FROM_NET_M = 1.98
LONG_SERVICE_DOUBLES_FROM_BASELINE_M = 0.76

# Template -> (width_m, length_m) of the rectangle the four taps mark (contracts §0, §2).
TEMPLATES = {
    "badminton_singles": (DOUBLES_WIDTH_M - 2 * SINGLES_INSET_M, LENGTH_M),  # 5.18 x 13.40
    "badminton_doubles": (DOUBLES_WIDTH_M, LENGTH_M),
}

# --- Provisional thresholds (REVIEW: `04` §2 names tiers A/B/C but gives no numbers) ----------
# Tap jitter the uncertainty is computed for. Research report 11 assumes a 2 px point error; it is
# not measured on our phones yet, so it is a stated assumption, not a measurement.
TAP_SIGMA_PX = 2.0
MC_DRAWS = 200
MC_SEED = 0
# sigma_m is the WORST radial std over the court (the far corners), not the average.
# Tier A: good enough for court metrics (coverage needs A, `04` §2 stage 9; `01` T5 asks for 0.5 m
# agreement on coverage maps, so 0.15 m of calibration error leaves room for foot-point error).
TIER_A_RESIDUAL_PX = 1.0
TIER_A_SIGMA_M = 0.15
TIER_A_VISIBLE = 0.90
# Tier B: positions usable with a caveat; anything worse is C and metres are withheld.
TIER_B_RESIDUAL_PX = 3.0
TIER_B_SIGMA_M = 0.40
TIER_B_VISIBLE = 0.50
# Feet further than this outside the tapped rectangle are not on this court (players serve and
# retrieve behind the baseline and in the doubles alley, so the margin is generous).
OFF_COURT_MARGIN_M = 2.0
# Grid step for visible_fraction and the uncertainty sample points.
GRID_STEP_M = 0.25


class CalibrationError(ValueError):
    """The taps cannot define a homography at all (missing keys, unknown template, collinear)."""


def template_corners(template: str) -> np.ndarray:
    """(4, 2) court metres of the tapped corners, in CORNERS order."""
    if template not in TEMPLATES:
        raise CalibrationError(f"unknown court template {template!r}")
    w, length = TEMPLATES[template]
    return np.array([[0.0, 0.0], [w, 0.0], [w, length], [0.0, length]])


def _taps(points_px: dict) -> np.ndarray:
    missing = [k for k in CORNERS if k not in points_px]
    if missing:
        raise CalibrationError(f"missing corner taps: {', '.join(missing)}")
    pts = np.array([points_px[k] for k in CORNERS], dtype=np.float64)
    if pts.shape != (4, 2) or not np.all(np.isfinite(pts)):
        raise CalibrationError("corner taps must be four finite [x, y] pairs")
    return pts


def _normalise(m: np.ndarray) -> np.ndarray:
    if abs(m[2, 2]) < 1e-12:
        raise CalibrationError("homography is degenerate (h33 = 0)")
    return m / m[2, 2]


def _fit(img: np.ndarray, court: np.ndarray) -> np.ndarray | None:
    h, _ = cv2.findHomography(img.astype(np.float64), court.astype(np.float64), 0)
    if h is None or not np.all(np.isfinite(h)) or abs(h[2, 2]) < 1e-12:
        return None
    if abs(np.linalg.det(h)) < 1e-15:
        return None
    return h / h[2, 2]


def homography(points_px: dict, template: str) -> tuple[np.ndarray, np.ndarray]:
    """(H, Hinv): H maps image px -> court metres, Hinv the reverse. Raises CalibrationError."""
    h = _fit(_taps(points_px), template_corners(template))
    if h is None:
        raise CalibrationError("taps are degenerate (three or more collinear)")
    return h, _normalise(np.linalg.inv(h))


def _apply(m: np.ndarray, xy: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
    """Projective map of (N, 2) points -> ((N, 2) points, (N,) homogeneous w)."""
    xy = np.asarray(xy, dtype=np.float64).reshape(-1, 2)
    hom = np.hstack([xy, np.ones((len(xy), 1))]) @ m.T
    w = hom[:, 2]
    with np.errstate(divide="ignore", invalid="ignore"):
        return hom[:, :2] / w[:, None], w


def to_court(h: np.ndarray, xy_px) -> np.ndarray:
    """Image px (N, 2) or (2,) -> court metres, same shape."""
    out, _ = _apply(h, xy_px)
    return out.reshape(np.shape(xy_px))


def to_image(hinv: np.ndarray, xy_m) -> np.ndarray:
    """Court metres (N, 2) or (2,) -> image px, same shape."""
    out, _ = _apply(hinv, xy_m)
    return out.reshape(np.shape(xy_m))


def _court_centre(template: str) -> np.ndarray:
    w, length = TEMPLATES[template]
    return np.array([w / 2, length / 2])


def _front_sign_court(hinv: np.ndarray, template: str) -> float:
    """Sign of Hinv's w on the visible court side (court points with the other sign are behind
    the camera's horizon and have no image)."""
    return float(np.sign(_apply(hinv, _court_centre(template))[1][0]))


def _front_sign_image(h: np.ndarray, template: str) -> float:
    """Sign of H's w for image points below the horizon (on the court side)."""
    c = _court_centre(template)
    p = np.linalg.inv(h) @ np.array([c[0], c[1], 1.0])
    return float(np.sign(1.0 / p[2]))


def reprojection_residual_px(hinv: np.ndarray, points_px: dict, template: str) -> float:
    """RMS distance (px) between the taps and the template corners projected back to the image.
    With exactly four taps this is ~0 by construction (the fit is exact)."""
    proj = to_image(hinv, template_corners(template))
    return float(np.sqrt(np.mean(np.sum((proj - _taps(points_px)) ** 2, axis=1))))


def tap_geometry_problem(points_px: dict) -> str | None:
    """None when the taps form a convex quad in contract order with the camera's view orientation;
    else a short code. Image y points down, so a non-mirrored view of the court (near_left ->
    near_right -> far_right -> far_left) turns clockwise on screen: every cross product < 0."""
    q = _taps(points_px)
    turns = []
    for i in range(4):
        a, b, c = q[i], q[(i + 1) % 4], q[(i + 2) % 4]
        turns.append((b[0] - a[0]) * (c[1] - b[1]) - (b[1] - a[1]) * (c[0] - b[0]))
    turns = np.array(turns)
    if np.all(turns > 0):
        return "mirrored"  # left/right (or near/far) swapped
    if not np.all(turns < 0):
        return "crossed"  # not convex or self-intersecting
    return None


def _grid(template: str) -> np.ndarray:
    w, length = TEMPLATES[template]
    xs = np.arange(GRID_STEP_M / 2, w, GRID_STEP_M)
    ys = np.arange(GRID_STEP_M / 2, length, GRID_STEP_M)
    gx, gy = np.meshgrid(xs, ys)
    return np.column_stack([gx.ravel(), gy.ravel()])


def visible_fraction(hinv: np.ndarray, template: str, width: int, height: int) -> float:
    """Share of the tapped court rectangle (by area, 0.25 m grid) whose image lies in the frame."""
    pts, w = _apply(hinv, _grid(template))
    front = np.sign(w) == _front_sign_court(hinv, template)
    inside = (
        front & (pts[:, 0] >= 0) & (pts[:, 0] < width) & (pts[:, 1] >= 0) & (pts[:, 1] < height)
    )
    return float(np.mean(inside))


@dataclass(frozen=True)
class Uncertainty:
    tap_sigma_px: float
    draws: int
    failed_draws: int
    h_cov: np.ndarray  # (9, 9) covariance of the flattened H (h33 = 1)
    sigma_m: float  # worst radial std (m) over court sample points
    sigma_m_mean: float  # mean radial std (m) over court sample points


def uncertainty(
    points_px: dict,
    template: str,
    tap_sigma_px: float = TAP_SIGMA_PX,
    draws: int = MC_DRAWS,
    seed: int = MC_SEED,
) -> Uncertainty:
    """Monte Carlo over tap jitter: each tap gets independent N(0, tap_sigma_px) noise in x and y,
    H is refitted, and the true image points of a court grid (plus the corners) are mapped to
    metres with the jittered H. Returns H's covariance and the metre std at those points."""
    taps = _taps(points_px)
    court = template_corners(template)
    h, hinv = homography(points_px, template)
    sample_m = np.vstack([court, _grid(template)])
    sample_px = to_image(hinv, sample_m)
    rng = np.random.default_rng(seed)
    hs, maps, failed = [], [], 0
    for _ in range(draws):
        hk = _fit(taps + rng.normal(0.0, tap_sigma_px, taps.shape), court)
        if hk is None:
            failed += 1
            continue
        hs.append(hk.ravel())
        maps.append(to_court(hk, sample_px))
    if len(hs) < 2:
        inf = float("inf")
        return Uncertainty(tap_sigma_px, draws, failed, np.full((9, 9), np.nan), inf, inf)
    maps_arr = np.stack(maps)  # (draws, N, 2)
    radial = np.sqrt(np.var(maps_arr[..., 0], axis=0) + np.var(maps_arr[..., 1], axis=0))
    return Uncertainty(
        tap_sigma_px=tap_sigma_px,
        draws=draws,
        failed_draws=failed,
        h_cov=np.cov(np.stack(hs), rowvar=False),
        sigma_m=float(np.max(radial)),
        sigma_m_mean=float(np.mean(radial)),
    )


def tier(
    residual_px: float,
    sigma_m: float,
    visible: float,
    geometry_problem: str | None = None,
    failed_fraction: float = 0.0,
) -> str:
    """A / B / C from the provisional thresholds above (REVIEW: not numeric in `04` §2)."""
    if geometry_problem is not None or failed_fraction > 0.1 or not np.isfinite(sigma_m):
        return "C"
    if (
        residual_px <= TIER_A_RESIDUAL_PX
        and sigma_m <= TIER_A_SIGMA_M
        and visible >= TIER_A_VISIBLE
    ):
        return "A"
    if (
        residual_px <= TIER_B_RESIDUAL_PX
        and sigma_m <= TIER_B_SIGMA_M
        and visible >= TIER_B_VISIBLE
    ):
        return "B"
    return "C"


def foot_point(box) -> tuple[float, float]:
    """Bottom-centre of a person box [x1, y1, x2, y2] (image px): where the feet meet the floor."""
    x1, _, x2, y2 = (float(v) for v in box[:4])
    return (x1 + x2) / 2, y2


def player_metres(h: np.ndarray, box, tier_: str, template: str) -> tuple[float, float] | None:
    """(foot_x_m, foot_y_m) of a person box, or None when the calibration is tier C, the foot is
    above the court's horizon, or it lands more than OFF_COURT_MARGIN_M outside the tapped
    rectangle."""
    if tier_ == "C":
        return None
    pts, w = _apply(h, np.array([foot_point(box)]))
    if np.sign(w[0]) != _front_sign_image(h, template):
        return None
    x, y = (float(v) for v in pts[0])
    cw, length = TEMPLATES[template]
    m = OFF_COURT_MARGIN_M
    if not (np.isfinite(x) and np.isfinite(y)) or not (-m <= x <= cw + m and -m <= y <= length + m):
        return None
    return x, y


def court_lines_m(template: str) -> list[tuple[str, tuple[float, float], tuple[float, float]]]:
    """Every painted badminton line in this template's court metres (BWF dimensions)."""
    w, _ = TEMPLATES[template]
    if template == "badminton_singles":  # taps mark the singles sidelines: x = 0 and x = 5.18
        xs0, xs1 = 0.0, w
        x0, x1 = -SINGLES_INSET_M, w + SINGLES_INSET_M
    else:  # taps mark the doubles sidelines: x = 0 and x = 6.10
        x0, x1 = 0.0, w
        xs0, xs1 = SINGLES_INSET_M, w - SINGLES_INSET_M
    length = LENGTH_M
    ss_near = NET_Y_M - SHORT_SERVICE_FROM_NET_M  # 4.72
    ss_far = NET_Y_M + SHORT_SERVICE_FROM_NET_M  # 8.68
    ls = LONG_SERVICE_DOUBLES_FROM_BASELINE_M
    xc = (x0 + x1) / 2
    return [
        ("near_baseline", (x0, 0.0), (x1, 0.0)),
        ("far_baseline", (x0, length), (x1, length)),
        ("left_sideline_doubles", (x0, 0.0), (x0, length)),
        ("right_sideline_doubles", (x1, 0.0), (x1, length)),
        ("left_sideline_singles", (xs0, 0.0), (xs0, length)),
        ("right_sideline_singles", (xs1, 0.0), (xs1, length)),
        ("near_long_service_doubles", (x0, ls), (x1, ls)),
        ("far_long_service_doubles", (x0, length - ls), (x1, length - ls)),
        ("near_short_service", (x0, ss_near), (x1, ss_near)),
        ("far_short_service", (x0, ss_far), (x1, ss_far)),
        ("near_centre", (xc, 0.0), (xc, ss_near)),
        ("far_centre", (xc, ss_far), (xc, length)),
    ]


def court_lines_px(hinv: np.ndarray, template: str) -> list[dict]:
    """Every court line projected into image px, NOT clipped to the frame (the app draws the
    off-frame part dashed). A segment crossing the camera's horizon is cut just before it
    (`clipped: true`); a line wholly behind the camera is dropped."""
    front = _front_sign_court(hinv, template)
    out = []
    for line_id, a, b in court_lines_m(template):
        a_m, b_m = np.array(a), np.array(b)
        wa = front * float(_apply(hinv, a_m)[1][0])
        wb = front * float(_apply(hinv, b_m)[1][0])
        if wa <= 0 and wb <= 0:
            continue
        clipped = False
        if wa <= 0 or wb <= 0:
            # w is linear along the segment in court space; stop at 1 % of the in-front w.
            eps = 0.01 * max(wa, wb)
            t = (eps - wa) / (wb - wa)
            cut = a_m + t * (b_m - a_m)
            a_m, b_m = (cut, b_m) if wa <= 0 else (a_m, cut)
            clipped = True
        pa, pb = to_image(hinv, np.vstack([a_m, b_m]))
        out.append(
            {
                "line_id": line_id,
                "a_px": [float(pa[0]), float(pa[1])],
                "b_px": [float(pb[0]), float(pb[1])],
                "clipped": clipped,
            }
        )
    return out


@dataclass(frozen=True)
class Calibration:
    h: np.ndarray
    hinv: np.ndarray
    template: str
    residual_px: float
    visible_fraction: float
    uncertainty: Uncertainty
    geometry_problem: str | None
    tier: str

    def to_match(self, calibration_id: str, method: str = "tap", plane: str = "court") -> dict:
        """The `match.v1.calibration` object (contracts §7). `H` is image px -> court metres,
        row-major; `H_cov` is the 9x9 Monte Carlo covariance of that H, flattened row-major
        (81 numbers), or null when it could not be estimated."""
        cov = self.uncertainty.h_cov
        h_cov = [float(v) for v in cov.ravel()] if np.all(np.isfinite(cov)) else None
        return {
            "calibration_id": calibration_id,
            "plane": plane,
            "template": self.template,
            "method": method,
            "H": [float(v) for v in self.h.ravel()],
            "H_cov": h_cov,
            "residual_px": round(self.residual_px, 3),
            "visible_fraction": round(self.visible_fraction, 4),
            "tier": self.tier,
        }


def calibration_id(points_px: dict, template: str) -> str:
    """Deterministic id from the taps and template: the same taps give the same id."""
    key = json.dumps({"t": template, "p": {k: list(points_px[k]) for k in CORNERS}}, sort_keys=True)
    return "cal_" + hashlib.sha256(key.encode()).hexdigest()[:16]


def calibrate(
    points_px: dict,
    template: str,
    width: int,
    height: int,
    tap_sigma_px: float = TAP_SIGMA_PX,
) -> Calibration:
    """Fit H from the taps and grade it. Raises CalibrationError only when no H exists at all;
    bad-but-fittable taps (mirrored, crossed) come back as tier C."""
    h, hinv = homography(points_px, template)
    problem = tap_geometry_problem(points_px)
    residual = reprojection_residual_px(hinv, points_px, template)
    visible = visible_fraction(hinv, template, width, height)
    unc = uncertainty(points_px, template, tap_sigma_px=tap_sigma_px)
    t = tier(residual, unc.sigma_m, visible, problem, unc.failed_draws / max(unc.draws, 1))
    return Calibration(h, hinv, template, residual, visible, unc, problem, t)


def from_calibration_input(calibration_input: dict, width: int, height: int) -> Calibration:
    """Calibrate from `session_spec.calibration_input` (contracts §2)."""
    if not isinstance(calibration_input, dict):
        raise CalibrationError("no calibration_input")
    if calibration_input.get("plane") != "court":
        raise CalibrationError("only the court plane is supported")
    points, template = calibration_input.get("points_px"), calibration_input.get("template")
    if not isinstance(points, dict) or not isinstance(template, str):
        raise CalibrationError("calibration_input needs points_px and template")
    return calibrate(points, template, width, height)
