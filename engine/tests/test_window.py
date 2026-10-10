import json
from pathlib import Path

import numpy as np
import pytest

from elevate_engine import window as win

SAVED = Path(__file__).resolve().parents[1] / "eval" / "runs" / "h13g_w1"


def test_saved_window_1_matches_the_contract_and_its_manifest():
    w = json.loads((SAVED / "window_result.json").read_text())
    win.validate(w)  # packages/contracts window_result.v1
    manifest = json.loads((SAVED / "manifest.json").read_text())
    assert w["window_seq"] == manifest["window_seq"] == 1
    lo, hi = (v // 1000 for v in w["emit_range_us"])
    assert [lo, hi] == manifest["emit_ms"]
    assert all(lo <= row[0] < hi and row[4] == "candidate" for row in w["ball"])


def test_build_keeps_only_rows_inside_the_emitted_range():
    rows = [[999, 1.0, 1.0, 0.9, "candidate"], [1000, 2.0, 2.0, 0.9, "candidate"],
            [1999, 3.0, 3.0, 0.9, "candidate"], [2000, 4.0, 4.0, 0.9, "candidate"]]  # fmt: skip
    q = {"ball_coverage": 0.5, "camera_motion_px": 0.0, "blur_index": 0.1}
    w = win.build("a_test", 0, (1000, 2750), (1000, 2000), rows, q, ["tracknet_v3"])
    assert [r[0] for r in w["ball"]] == [1000, 1999]
    assert w["decode_window_us"] == [1_000_000, 2_750_000]
    win.validate(w)


def test_global_shift_measures_a_known_translation():
    rng = np.random.default_rng(0)
    a = rng.random((288, 512))
    b = np.roll(a, shift=(3, 4), axis=(0, 1))
    assert win.global_shift(a, b) == pytest.approx(5.0)
    assert win.global_shift(a, a) == pytest.approx(0.0, abs=1e-9)


def test_blur_index_rises_when_the_image_is_blurred():
    rng = np.random.default_rng(1)
    sharp = rng.random((288, 512)) * 255
    from scipy import ndimage

    assert win.blur(ndimage.gaussian_filter(sharp, 3)) > win.blur(sharp)


def test_quality_uses_emitted_frames_and_scales_motion_to_source():
    rng = np.random.default_rng(2)
    base = (rng.random((3, 288, 512)) * 255).astype(np.uint8)
    moved = np.roll(base, shift=2, axis=2)  # 2 model px to the right
    q = win.quality([base, moved, moved], frames_with_ball=1, source_scale=3.75)
    assert q["ball_coverage"] == pytest.approx(1 / 3, abs=1e-4)
    # median of shifts [2, 0] is 1 model px -> 3.75 source px
    assert q["camera_motion_px"] == pytest.approx(3.75, abs=0.2)


def test_quality_refuses_to_invent_values_for_an_empty_window():
    with pytest.raises(ValueError):
        win.quality([], frames_with_ball=0, source_scale=3.75)


def test_global_shift_resolves_sub_pixel_motion():
    # Exact half-pixel horizontal shift of a random image, made in the frequency domain.
    rng = np.random.default_rng(3)
    a = rng.random((288, 512))
    kx = np.fft.fftfreq(512)[None, :]
    b = np.fft.ifft2(np.fft.fft2(a) * np.exp(-2j * np.pi * kx * 0.5)).real
    shift = win.global_shift(a, b)
    assert 0.2 < shift < 0.8  # whole-pixel argmax alone would report 0 or 1
