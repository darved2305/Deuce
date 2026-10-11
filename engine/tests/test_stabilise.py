"""Stabilisation: path recovery on frames with a known camera path, map_back, gate, fallback.

The synthetic scene is only a texture moved by known transforms; it checks the geometry
(conventions, composition, scale), not how well it does on a real handheld clip (that is
engine/eval/runs/stabilise_*)."""

from __future__ import annotations

import dataclasses

import cv2
import numpy as np
import pytest

from elevate_engine import stabilise as stab
from elevate_engine.sports import STABILISE, StabiliseConfig

H_PX, W_PX = 288, 512
CFG = dataclasses.replace(STABILISE, enabled=True)


def texture(seed: int = 3) -> np.ndarray:
    """A blurred-noise grey scene with blobs and lines: plenty of trackable corners."""
    rng = np.random.default_rng(seed)
    img = cv2.GaussianBlur(rng.integers(0, 255, (H_PX, W_PX), dtype=np.uint8), (0, 0), 2.0)
    img = cv2.normalize(img, None, 40, 215, cv2.NORM_MINMAX)
    for _ in range(40):
        cv2.circle(img, (int(rng.integers(0, W_PX)), int(rng.integers(0, H_PX))),
                   int(rng.integers(4, 14)), int(rng.integers(0, 255)), -1)  # fmt: skip
    return img


def camera(dx: float, dy: float, deg: float) -> np.ndarray:
    """Scene -> frame homography: rotate by deg about the centre, then shift."""
    M = np.eye(3)
    M[:2] = cv2.getRotationMatrix2D((W_PX / 2, H_PX / 2), deg, 1.0)
    T = np.eye(3)
    T[0, 2], T[1, 2] = dx, dy
    return T @ M


def make_clip(n: int = 21):
    base = texture()
    t = np.linspace(0, 2 * np.pi, n)
    cams = [camera(6 * np.sin(a), 4 * np.cos(2 * a) - 4, 1.5 * np.sin(a)) for a in t]
    frames = [
        cv2.warpPerspective(base, G, (W_PX, H_PX), borderMode=cv2.BORDER_REFLECT) for G in cams
    ]
    return frames, cams


def corner_err(A: np.ndarray, B: np.ndarray) -> float:
    pts = np.float64([[0, 0], [W_PX, 0], [W_PX, H_PX], [0, H_PX], [256, 144]]).reshape(-1, 1, 2)
    a = cv2.perspectiveTransform(pts, A).reshape(-1, 2)
    b = cv2.perspectiveTransform(pts, B).reshape(-1, 2)
    return float(np.linalg.norm(a - b, axis=1).max())


def test_estimate_path_recovers_shift_and_rotation():
    frames, cams = make_clip()
    ref = len(frames) // 2
    Hs = stab.estimate_path(frames, None, CFG)
    assert len(Hs) == len(frames)
    for i, H in enumerate(Hs):
        truth = cams[ref] @ np.linalg.inv(cams[i])  # frame i -> reference frame
        assert corner_err(H, truth) < 0.5, f"frame {i}"
    assert corner_err(Hs[ref], np.eye(3)) < 1e-9


def test_estimate_path_ignores_masked_region():
    frames, cams = make_clip()
    mask = np.full((H_PX, W_PX), 255, np.uint8)
    mask[:, 300:] = 0  # an excluded region (players) is not tracked
    for f in frames:  # and moves independently of the camera there
        f[:, 300:] = np.random.default_rng(0).integers(0, 255, f[:, 300:].shape, dtype=np.uint8)
    Hs = stab.estimate_path(frames, mask, CFG)
    ref = len(frames) // 2
    assert all(
        corner_err(H, cams[ref] @ np.linalg.inv(G)) < 1.5 for H, G in zip(Hs, cams, strict=True)
    )


def test_warp_aligns_frames_and_flags_invalid_border():
    frames, cams = make_clip()
    ref = len(frames) // 2
    Hs = stab.estimate_path(frames, None, CFG)
    k = 4  # frame 0 happens to sit on the reference; frame 4 is displaced
    chw = np.repeat(frames[k][None], 3, axis=0)
    warped, valid = stab.warp(chw, Hs[k])
    assert warped.shape == chw.shape and valid.shape == (H_PX, W_PX)
    assert 0.8 < valid.mean() < 1.0  # the camera moved, so some border is invalid
    diff = np.abs(warped[0].astype(int) - frames[ref].astype(int))[valid & (frames[ref] > 0)]
    base_diff = np.abs(frames[k].astype(int) - frames[ref].astype(int)).mean()
    assert diff[diff.size // 10 : -diff.size // 10].mean() < base_diff  # closer to the reference


def test_valid_median_background_ignores_invalid_pixels():
    a = np.full((3, 4, 4), 100, np.uint8)
    b = np.full((3, 4, 4), 100, np.uint8)
    c = np.zeros((3, 4, 4), np.uint8)  # black border where invalid
    va, vb = np.ones((4, 4), bool), np.ones((4, 4), bool)
    vc = np.zeros((4, 4), bool)
    vc[:, :2] = True
    bg = stab.valid_median_background([a, b, c], [va, vb, vc], a)
    assert np.all(bg == 100)  # a plain median would have given 100 only where c was dropped
    none = stab.valid_median_background([c], [np.zeros((4, 4), bool)], a + 5)
    assert np.all(none == 105)  # uncovered pixels take the fallback


def test_map_back_round_trip_and_scale():
    H = camera(7.5, -3.0, 2.0) @ np.array([[1, 0, 0], [0, 1, 0], [1e-4, 2e-4, 1]])
    pts = np.array([[10.0, 20.0], [256.0, 144.0], [500.0, 280.0]])
    fwd = cv2.perspectiveTransform(pts.reshape(-1, 1, 2), H).reshape(-1, 2)  # frame -> stabilised
    x, y = stab.map_back(fwd[:, 0], fwd[:, 1], H)
    assert np.allclose(np.c_[x, y], pts, atol=1e-6)
    sx, sy = 1920 / W_PX, 1080 / H_PX
    x, y = stab.map_back(fwd[:, 0], fwd[:, 1], H, (sx, sy))
    assert np.allclose(np.c_[x, y], pts * [sx, sy], atol=1e-5)
    x0, y0 = stab.map_back(100.0, 50.0, np.eye(3), (2.0, 3.0))  # scalars, identity
    assert (float(x0), float(y0)) == (200.0, 150.0)
    with pytest.raises(ValueError):
        stab.map_back(1.0, 1.0, np.zeros((3, 3)))


def test_gate_leaves_steady_window_untouched():
    frames, _ = make_clip()
    chw = [np.repeat(f[None], 3, axis=0) for f in frames]
    steady = stab.stabilise_window(chw, 0.03, CFG)  # h13g measures 0.03 source px
    assert not steady.applied and steady.report["reason"] == "below_threshold"
    assert (
        all(a is b for a, b in zip(steady.frames, chw, strict=True)) and steady.background is None
    )
    assert all(np.array_equal(H, np.eye(3)) for H in steady.homographies)
    off = stab.stabilise_window(chw, 5.0, dataclasses.replace(CFG, enabled=False))
    assert not off.applied and off.report["reason"] == "disabled"
    assert not stab.needs_stabilising(CFG.min_motion_px, CFG)  # the threshold itself is steady


def test_gate_stabilises_shaky_window_and_reduces_motion():
    frames, _ = make_clip()
    chw = [np.repeat(f[None], 3, axis=0) for f in frames]
    out = stab.stabilise_window(chw, 5.0, CFG)
    assert out.applied and out.background is not None and out.background.shape == chw[0].shape
    assert all(f.shape == chw[0].shape and f.dtype == np.uint8 for f in out.frames)
    before = np.median([stab.win.global_shift(a.astype(float), b.astype(float))
                        for a, b in zip(frames, frames[1:], strict=False)])  # fmt: skip
    assert out.report["residual_motion_px"] < 0.25 * before
    assert out.report["residual_to_reference_px"] < 0.25


def test_failure_falls_back_to_identity():
    rng = np.random.default_rng(1)
    blank = [np.full((H_PX, W_PX), 90, np.uint8) for _ in range(9)]  # nothing to track
    est = stab.track_path(blank, None, CFG)
    assert est.identity_fallback and est.unrecovered > 0
    assert all(np.array_equal(H, np.eye(3)) for H in est.homographies)
    assert est.report["identity_fallback"] and est.report["reason"]
    noise = [rng.integers(0, 255, (H_PX, W_PX), dtype=np.uint8) for _ in range(9)]  # unrelated
    assert all(np.isfinite(H).all() for H in stab.estimate_path(noise, None, CFG))
    one = stab.track_path(blank[:1], None, CFG)
    assert one.identity_fallback and len(one.homographies) == 1
    chw = [np.repeat(f[None], 3, axis=0) for f in blank]
    win = stab.stabilise_window(chw, 5.0, CFG)  # gate open, estimate failed: input unchanged
    assert not win.applied and win.report["identity_fallback"]
    assert all(a is b for a, b in zip(win.frames, chw, strict=True))


def test_error_in_estimation_never_escapes():
    frames, _ = make_clip(5)
    bad = [f.astype(np.float32) for f in frames]  # LK rejects float frames: raises inside
    est = stab.track_path(bad, None, CFG)
    assert est.identity_fallback and est.reason


def test_person_mask_blanks_boxes_in_model_space():
    m = stab.person_mask([[384.0, 216.0, 768.0, 432.0]], (H_PX, W_PX), (3.75, 3.75), pad=2)
    assert m[100, 100] == 0 and m[60, 120] == 0 and m[10, 10] == 255
    assert stab.person_mask([], (H_PX, W_PX), (3.75, 3.75), pad=2).all()


def test_config_defaults_are_off_and_frozen():
    assert StabiliseConfig().enabled is False and STABILISE.enabled is False
    with pytest.raises(dataclasses.FrozenInstanceError):
        STABILISE.enabled = True  # type: ignore[misc]
