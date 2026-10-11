"""Stabilise a handheld window for TrackNetV3, which assumes a fixed camera.

Per window (engine/eval/runs/stabilise_*/ has the measurements):
1. estimate_path: a homography from every frame to the window's middle frame. Sparse KLT between
   consecutive frames (forward/back checked, RANSAC homography) chained outward from the
   reference; every `reanchor_every` frames a direct ORB + RANSAC match to the reference replaces
   the chained value when the two agree, which stops drift. Player regions can be masked out.
   A step that fails records identity for that step (the chain carries on) and is counted; too
   many failures, or any error, leaves the whole window on identity (unstabilised).
2. warp: each frame onto the reference plus a valid-pixel mask. The background is the median of
   the warped frames over valid pixels only; invalid border pixels of a stabilised frame are
   filled with that background so they never look like motion.
3. map_back: TrackNet candidates found in stabilised space go back through the inverse
   homography and the 512x288 -> source scale, so every output stays in original source pixels.
4. Gate: only a window whose measured camera_motion_px (source px) exceeds the threshold is
   touched; a steady clip passes through byte-identical.

Conventions: frames are (3, 288, 512) uint8 RGB like infer.resize_chw_uint8; H[i] maps a point of
frame i to the reference (src -> dst, as cv2.warpPerspective takes it). Masks are uint8 with 255
where a pixel may be used for estimation.
"""

from __future__ import annotations

import warnings
from collections.abc import Sequence
from dataclasses import dataclass, field

import cv2
import numpy as np

from elevate_engine import window as win
from elevate_engine.sports import STABILISE, StabiliseConfig

cv2.setNumThreads(1)  # one window per container core; avoids oversubscription with the decoder

GreyFrame = np.ndarray  # (H, W) uint8


@dataclass
class PathEstimate:
    homographies: list[np.ndarray]
    ref_index: int
    failed_steps: int = 0  # consecutive-frame KLT steps that fell back to identity
    unrecovered: int = 0  # frames where no step, anchor or refinement worked (identity step kept)
    reanchors: int = 0  # ORB anchors accepted
    reanchors_rejected: int = 0  # tried, too few inliers or disagreed with the chain
    identity_fallback: bool = False  # whole window left on identity
    reason: str = ""

    @property
    def report(self) -> dict:
        return {"ref_index": self.ref_index, "failed_steps": self.failed_steps,
                "unrecovered": self.unrecovered,
                "reanchors": self.reanchors, "reanchors_rejected": self.reanchors_rejected,
                "identity_fallback": self.identity_fallback, "reason": self.reason}  # fmt: skip


def to_grey(chw: np.ndarray) -> GreyFrame:
    """(3, H, W) uint8 RGB -> (H, W) uint8 luminance."""
    return cv2.cvtColor(np.ascontiguousarray(np.moveaxis(chw, 0, -1)), cv2.COLOR_RGB2GRAY)


def person_mask(
    boxes: Sequence[Sequence[float]], shape: tuple[int, int], scale: tuple[float, float], pad: int
) -> np.ndarray:
    """Usable-pixel mask (255 usable) with each source-px box [x1, y1, x2, y2] blanked, mapped to
    the (H, W) model space by dividing by scale = (source/model width, source/model height)."""
    h, w = shape
    mask = np.full((h, w), 255, np.uint8)
    for x1, y1, x2, y2 in boxes:
        a, b = max(0, int(x1 / scale[0]) - pad), max(0, int(y1 / scale[1]) - pad)
        c, d = min(w, int(np.ceil(x2 / scale[0])) + pad), min(h, int(np.ceil(y2 / scale[1])) + pad)
        mask[b:d, a:c] = 0
    return mask


def _corner_shift(H: np.ndarray, shape: tuple[int, int]) -> float:
    """Largest displacement H gives the four frame corners (pixels); inf if degenerate."""
    h, w = shape
    pts = np.float64([[0, 0], [w, 0], [w, h], [0, h]]).reshape(-1, 1, 2)
    with np.errstate(all="ignore"):
        out = cv2.perspectiveTransform(pts, H).reshape(-1, 2)
    if not np.all(np.isfinite(out)):
        return float("inf")
    return float(np.linalg.norm(out - pts.reshape(-1, 2), axis=1).max())


def _valid_homography(H: np.ndarray | None, shape: tuple[int, int], max_shift: float) -> bool:
    return (
        H is not None
        and np.all(np.isfinite(H))
        and abs(np.linalg.det(H[:2, :2])) > 0.25
        and abs(H[2, 2]) > 1e-9
        and _corner_shift(H, shape) <= max_shift
    )


def _klt_match(a: GreyFrame, b: GreyFrame, pts: np.ndarray, mask_b, cfg: StabiliseConfig):
    """Track pts (n, 1, 2) of frame a into frame b; keep those that survive the forward/back
    check and land on usable pixels. Returns (points in a, points in b)."""
    lk = {"winSize": (cfg.lk_win, cfg.lk_win), "maxLevel": cfg.lk_levels,
          "criteria": (cv2.TERM_CRITERIA_EPS | cv2.TERM_CRITERIA_COUNT, 20, 0.03)}  # fmt: skip
    nxt, st, _ = cv2.calcOpticalFlowPyrLK(a, b, pts, None, **lk)
    back, st2, _ = cv2.calcOpticalFlowPyrLK(b, a, nxt, None, **lk)
    fb = np.linalg.norm((back - pts).reshape(-1, 2), axis=1)
    ok = (st.ravel() == 1) & (st2.ravel() == 1) & (fb < cfg.fb_tol_px)
    if mask_b is not None:
        h, w = b.shape
        xy = np.rint(nxt.reshape(-1, 2)).astype(int)
        inside = (xy[:, 0] >= 0) & (xy[:, 0] < w) & (xy[:, 1] >= 0) & (xy[:, 1] < h)
        ok &= inside
        ok[inside] &= mask_b[xy[inside, 1], xy[inside, 0]] > 0
    return pts[ok], nxt[ok]


def _klt_step(
    a: GreyFrame, b: GreyFrame, mask_a, mask_b, cfg: StabiliseConfig, pts=None
) -> np.ndarray | None:
    """Homography taking points of frame a to frame b from tracked features, or None.
    pts: features of a to track (default: good features of a under mask_a)."""
    if pts is None:
        pts = cv2.goodFeaturesToTrack(a, cfg.max_corners, 0.01, 7, mask=mask_a)
    if pts is None or len(pts) < cfg.min_inliers:
        return None
    src, dst = _klt_match(a, b, pts, mask_b, cfg)
    if len(src) < cfg.min_inliers:
        return None
    H, inl = cv2.findHomography(src, dst, cv2.RANSAC, cfg.ransac_px)
    if H is None or inl is None or int(inl.sum()) < cfg.min_inliers:
        return None
    return H


def _orb_features(g: GreyFrame, mask, cfg: StabiliseConfig):
    orb = cv2.ORB_create(cfg.orb_features)
    return orb.detectAndCompute(g, mask)


def _orb_to_ref(g: GreyFrame, mask, ref_feats, cfg: StabiliseConfig) -> np.ndarray | None:
    """Direct homography frame -> reference by ORB + ratio test + RANSAC, or None."""
    kp_r, des_r = ref_feats
    kp, des = _orb_features(g, mask, cfg)
    if des is None or des_r is None or len(kp) < cfg.orb_min_inliers:
        return None
    pairs = cv2.BFMatcher(cv2.NORM_HAMMING).knnMatch(des, des_r, k=2)
    good = [p[0] for p in pairs if len(p) == 2 and p[0].distance < 0.75 * p[1].distance]
    if len(good) < cfg.orb_min_inliers:
        return None
    src = np.float32([kp[m.queryIdx].pt for m in good])
    dst = np.float32([kp_r[m.trainIdx].pt for m in good])
    H, inl = cv2.findHomography(src, dst, cv2.RANSAC, cfg.orb_ransac_px)
    if H is None or inl is None or int(inl.sum()) < cfg.orb_min_inliers:
        return None
    return H


def _mask_for(mask, i: int):
    if mask is None:
        return None
    if isinstance(mask, np.ndarray) and mask.ndim == 2:
        return mask
    return mask[i]


def track_path(
    frames_grey: Sequence[GreyFrame], mask=None, cfg: StabiliseConfig = STABILISE
) -> PathEstimate:
    """estimate_path plus a record of what happened (failed steps, re-anchors, fallback)."""
    n = len(frames_grey)
    ref = n // 2
    identity = PathEstimate([np.eye(3) for _ in range(n)], ref)
    if n < 2:
        identity.identity_fallback, identity.reason = True, "fewer than 2 frames"
        return identity
    try:
        return _track_path(frames_grey, mask, cfg, ref)
    except Exception as exc:  # noqa: BLE001 - a stabiliser fault must never fail the window
        identity.identity_fallback = True
        identity.reason = f"{type(exc).__name__}: {exc}"[:200]
        return identity


def _track_path(frames, mask, cfg: StabiliseConfig, ref: int) -> PathEstimate:
    """Chain consecutive-frame KLT outward from the reference, then pin every frame to the
    reference: ORB re-anchor every `reanchor_every` frames, and a KLT refinement of the warped
    frame against the reference's own features (so chain drift cannot accumulate)."""
    n, shape = len(frames), frames[0].shape
    h, w = shape
    Hs: list[np.ndarray | None] = [None] * n
    Hs[ref] = np.eye(3)
    est = PathEstimate([], ref)
    ref_feats = _orb_features(frames[ref], _mask_for(mask, ref), cfg)
    ref_pts = cv2.goodFeaturesToTrack(frames[ref], cfg.max_corners, 0.01, 7,
                                      mask=_mask_for(mask, ref))  # fmt: skip
    for direction in (-1, 1):  # chain outward from the reference
        i = ref + direction
        while 0 <= i < n:
            prev = i - direction  # already has a path to the reference
            step = _klt_step(
                frames[i], frames[prev], _mask_for(mask, i), _mask_for(mask, prev), cfg
            )
            chained = _valid_homography(step, shape, cfg.max_step_px)
            if not chained:
                step = np.eye(3)
                est.failed_steps += 1
            H = Hs[prev] @ step
            anchored = False
            if abs(i - ref) % cfg.reanchor_every == 0 or not chained:
                anchor = _orb_to_ref(frames[i], _mask_for(mask, i), ref_feats, cfg)
                if _valid_homography(anchor, shape, np.inf) and (
                    _corner_shift(anchor @ np.linalg.inv(H), shape) <= cfg.reanchor_max_jump_px
                ):
                    H, anchored, est.reanchors = anchor, True, est.reanchors + 1
                else:
                    est.reanchors_rejected += 1
            # Residual alignment of frame i, warped by H, against the reference.
            warped = cv2.warpPerspective(frames[i], H, (w, h), flags=cv2.INTER_LINEAR)
            resid = _klt_step(frames[ref], warped, None, None, cfg, pts=ref_pts)
            if resid is not None and _valid_homography(resid, shape, cfg.refine_max_px):
                H = np.linalg.inv(resid) @ H  # resid maps reference -> warped frame
            elif not (chained or anchored):
                est.unrecovered += 1
            Hs[i] = H / H[2, 2]
            i += direction
    est.homographies = Hs  # type: ignore[assignment]
    if est.unrecovered > cfg.max_failed_fraction * (n - 1):
        est.homographies = [np.eye(3) for _ in range(n)]
        est.identity_fallback = True
        est.reason = f"{est.unrecovered} of {n - 1} frames had no estimate"
    return est


def estimate_path(
    frames_grey: Sequence[GreyFrame], mask=None, cfg: StabiliseConfig = STABILISE
) -> list[np.ndarray]:
    """Per-frame homographies (frame -> middle frame) for 512x288 grey frames.

    mask: None, one (H, W) uint8 mask for all frames, or one mask (or None) per frame; 255 marks
    pixels usable for tracking (see person_mask). Failures fall back to identity; use track_path
    to see how often."""
    return track_path(frames_grey, mask, cfg).homographies


def warp(frame: np.ndarray, H: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
    """Warp a (3, h, w) uint8 frame onto the reference with H (frame -> reference).

    Returns (warped, valid) where valid is a bool (h, w) mask of pixels that came from inside
    the source frame (one pixel eroded so interpolated edges are not trusted)."""
    _, h, w = frame.shape
    hwc = np.ascontiguousarray(np.moveaxis(frame, 0, -1))
    out = cv2.warpPerspective(hwc, H, (w, h), flags=cv2.INTER_LINEAR)
    inside = cv2.warpPerspective(np.full((h, w), 255, np.uint8), H, (w, h), flags=cv2.INTER_NEAREST)
    valid = cv2.erode(inside, np.ones((3, 3), np.uint8)) > 0
    return np.moveaxis(out, -1, 0).copy(), valid


def valid_median_background(
    warped: Sequence[np.ndarray],
    valid: Sequence[np.ndarray],
    fallback: np.ndarray,
    max_samples: int = 60,
) -> np.ndarray:
    """Median over valid pixels only of warped (3, h, w) frames, float32 (3, h, w). Pixels no
    sampled frame covers take `fallback` (the reference frame)."""
    step = max(1, len(warped) // max_samples)
    stack = np.stack([w.astype(np.float32) for w in warped[::step]])
    ok = np.stack(list(valid[::step]))[:, None]  # (n, 1, h, w)
    stack = np.where(ok, stack, np.nan)
    covered = ok.any(axis=0)
    with warnings.catch_warnings():  # all-NaN pixels take the fallback below
        warnings.simplefilter("ignore", RuntimeWarning)
        med = np.nanmedian(stack, axis=0)
    return np.where(covered, med, fallback.astype(np.float32)).astype(np.float32)


def map_back(x, y, H: np.ndarray, scale: tuple[float, float] = (1.0, 1.0)):
    """Stabilised-space point(s) (model pixels) -> original frame, scaled to source pixels.

    H is the frame -> reference homography used to stabilise that frame; the point goes through
    its inverse, then is multiplied by scale = (source width / 512, source height / 288).
    x, y may be scalars or arrays; returns (x, y) of the same shape."""
    try:
        inv = np.linalg.inv(H)
    except np.linalg.LinAlgError as exc:
        raise ValueError("homography is singular") from exc
    xa, ya = np.asarray(x, dtype=np.float64), np.asarray(y, dtype=np.float64)
    w = inv[2, 0] * xa + inv[2, 1] * ya + inv[2, 2]
    ox = (inv[0, 0] * xa + inv[0, 1] * ya + inv[0, 2]) / w
    oy = (inv[1, 0] * xa + inv[1, 1] * ya + inv[1, 2]) / w
    return ox * scale[0], oy * scale[1]


def needs_stabilising(camera_motion_px: float, cfg: StabiliseConfig = STABILISE) -> bool:
    """The gate: measured window camera motion (source px) past the threshold."""
    return bool(cfg.enabled and camera_motion_px > cfg.min_motion_px)


def residual_motion_px(
    greys: Sequence[GreyFrame],
    homographies: Sequence[np.ndarray],
    valid: Sequence[np.ndarray],
    ref_index: int | None = None,
    samples: int = 0,
) -> tuple[float, float]:
    """Residual camera motion of the warped frames (512x288 px) over the area every frame
    covers (borders would fake motion): (median frame-to-frame phase-correlation shift, as
    window.global_shift measures it; median shift of each frame from the reference frame, which
    is what a per-window background sees). samples > 0 measures that many evenly spaced pairs and
    frames instead of all of them (the median is stable; the FFTs are the cost)."""
    common = np.logical_and.reduce(list(valid))
    ys, xs = np.where(common)
    if len(ys) < 0.25 * common.size:
        raise ValueError("stabilised frames share too little valid area")
    sl = (slice(ys.min(), ys.max() + 1), slice(xs.min(), xs.max() + 1))
    h, w = greys[0].shape
    crops = [
        cv2.warpPerspective(g, H, (w, h), flags=cv2.INTER_LINEAR)[sl].astype(np.float64)
        for g, H in zip(greys, homographies, strict=True)
    ]
    ref = crops[len(crops) // 2 if ref_index is None else ref_index]
    pairs, idx = list(range(len(crops) - 1)), list(range(len(crops)))
    if samples > 0:
        pairs = pairs[:: max(1, len(pairs) // samples)]
        idx = idx[:: max(1, len(idx) // samples)]
    consecutive = [win.global_shift(crops[k], crops[k + 1]) for k in pairs]
    to_ref = [win.global_shift(ref, crops[k]) for k in idx]
    return float(np.median(consecutive)), float(np.median(to_ref))


@dataclass
class StabilisedWindow:
    applied: bool
    frames: list[np.ndarray]  # stabilised (or the input frames when not applied)
    background: np.ndarray | None  # per-window valid-pixel median; None when not applied
    homographies: list[np.ndarray]
    report: dict = field(default_factory=dict)


def stabilise_window(
    frames: Sequence[np.ndarray],
    camera_motion_px: float,
    cfg: StabiliseConfig = STABILISE,
    mask=None,
    measure_residual: bool = True,
) -> StabilisedWindow:
    """Gate, estimate, warp and build the per-window background for (3, 288, 512) uint8 frames.

    Not applied (input returned as is, report says why) when the gate is closed, the path
    estimate fell back to identity, or the warp leaves too little shared valid area."""
    n = len(frames)
    ident = [np.eye(3) for _ in range(n)]
    if not needs_stabilising(camera_motion_px, cfg):
        why = "disabled" if not cfg.enabled else "below_threshold"
        return StabilisedWindow(False, list(frames), None, ident,
                                {"stabilised": False, "reason": why,
                                 "camera_motion_px": camera_motion_px})  # fmt: skip
    greys = [to_grey(f) for f in frames]
    est = track_path(greys, mask, cfg)
    report = {"stabilised": False, "camera_motion_px": camera_motion_px, **est.report}
    if est.identity_fallback:
        return StabilisedWindow(False, list(frames), None, ident, report)
    warped, valid = zip(
        *[warp(f, H) for f, H in zip(frames, est.homographies, strict=True)], strict=True
    )
    bg = valid_median_background(warped, valid, np.asarray(frames[est.ref_index]),
                                 cfg.background_samples)  # fmt: skip
    out = [np.where(v[None], w, bg).astype(np.uint8) for w, v in zip(warped, valid, strict=True)]
    report["stabilised"] = True
    report["valid_fraction"] = round(float(np.mean([v.mean() for v in valid])), 4)
    if measure_residual:
        try:
            step, to_ref = residual_motion_px(
                greys, est.homographies, valid, est.ref_index, cfg.residual_samples
            )
            report["residual_motion_px"] = round(step, 4)
            report["residual_to_reference_px"] = round(to_ref, 4)
        except ValueError as exc:
            report["residual_motion_px"] = None
            report["residual_note"] = str(exc)
    return StabilisedWindow(True, out, bg, est.homographies, report)
