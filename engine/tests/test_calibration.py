"""Court homography from corner taps (elevate_engine.calibration).

The cameras here are synthetic test inputs (a pinhole camera behind the near baseline), used only
to check the maths against a known answer; they are never presented as real footage or taps.
"""

import json
from pathlib import Path

import jsonschema
import numpy as np
import pytest

from elevate_engine import calibration as cal

REPO = Path(__file__).resolve().parents[2]
MATCH_SCHEMA = json.loads((REPO / "packages/contracts/schemas/match.v1.schema.json").read_text())
FINALIZE_SCHEMA = json.loads(
    (REPO / "packages/contracts/schemas/analysis.finalize.request.v1.schema.json").read_text()
)
W, H = 1920, 1080


def camera(cam_xyz=(2.59, -6.0, 6.0), target=(2.59, 6.7, 0.0), f=1200.0, size=(W, H)) -> np.ndarray:
    """3x3 court(z=0) -> image homography of a pinhole camera looking at `target`."""
    c = np.array(cam_xyz, float)
    fwd = np.array(target, float) - c
    fwd /= np.linalg.norm(fwd)
    right = np.cross(fwd, [0.0, 0.0, 1.0])
    right /= np.linalg.norm(right)
    down = np.cross(fwd, right)
    r = np.vstack([right, down, fwd])  # world -> camera
    t = -r @ c
    k = np.array([[f, 0, size[0] / 2], [0, f, size[1] / 2], [0, 0, 1]])
    m = k @ np.column_stack([r[:, 0], r[:, 1], t])
    return m / m[2, 2]


def taps_from(hinv: np.ndarray, template: str) -> dict:
    px = cal.to_image(hinv, cal.template_corners(template))
    return {k: [float(x), float(y)] for k, (x, y) in zip(cal.CORNERS, px, strict=True)}


def test_recovers_known_homography_exactly():
    true_hinv = camera()
    taps = taps_from(true_hinv, "badminton_singles")
    h, hinv = cal.homography(taps, "badminton_singles")
    np.testing.assert_allclose(hinv, true_hinv, rtol=1e-5, atol=1e-5)
    prod = h @ hinv  # identity up to the projective scale
    np.testing.assert_allclose(prod / prod[2, 2], np.eye(3), atol=1e-9)
    pts_m = np.array([[1.0, 2.0], [4.0, 11.5], [2.59, 6.7]])
    np.testing.assert_allclose(cal.to_court(h, cal.to_image(hinv, pts_m)), pts_m, atol=1e-6)


def test_residual_is_zero_for_exact_taps_and_good_view_is_tier_a():
    taps = taps_from(camera(), "badminton_singles")
    c = cal.calibrate(taps, "badminton_singles", W, H)
    assert c.residual_px < 1e-3
    assert c.geometry_problem is None
    assert c.visible_fraction == 1.0
    assert c.uncertainty.sigma_m < cal.TIER_A_SIGMA_M
    assert c.tier == "A"


def test_uncertainty_is_reproducible_with_fixed_seed():
    taps = taps_from(camera(), "badminton_singles")
    a = cal.uncertainty(taps, "badminton_singles")
    b = cal.uncertainty(taps, "badminton_singles")
    assert a.sigma_m == b.sigma_m and a.draws == 200 and a.failed_draws == 0
    assert a.h_cov.shape == (9, 9)


def test_tier_degrades_with_more_tap_jitter():
    taps = taps_from(camera(), "badminton_singles")
    tiers = [
        cal.calibrate(taps, "badminton_singles", W, H, tap_sigma_px=s).tier for s in (1, 6, 40)
    ]
    assert tiers == ["A", "B", "C"]


def test_small_court_in_frame_degrades_tier():
    # Same view at a tenth of the focal length: the court spans ~100 px, so 2 px taps cost metres.
    taps = taps_from(camera(f=140.0), "badminton_singles")
    assert cal.calibrate(taps, "badminton_singles", W, H).tier == "C"


def test_mirrored_and_crossed_taps_are_tier_c():
    taps = taps_from(camera(), "badminton_singles")
    mirrored = {
        "near_left": taps["near_right"], "near_right": taps["near_left"],
        "far_right": taps["far_left"], "far_left": taps["far_right"],
    }  # fmt: skip
    crossed = dict(taps, far_right=taps["far_left"], far_left=taps["far_right"])
    assert cal.tap_geometry_problem(mirrored) == "mirrored"
    assert cal.tap_geometry_problem(crossed) == "crossed"
    assert cal.calibrate(mirrored, "badminton_singles", W, H).tier == "C"
    assert cal.calibrate(crossed, "badminton_singles", W, H).tier == "C"


def test_near_baseline_off_frame_lowers_visible_fraction():
    # Camera low and close: the near part of the court falls below the frame.
    hinv = camera(cam_xyz=(2.59, -1.0, 2.0), target=(2.59, 9.0, 0.0), f=1400.0)
    taps = taps_from(hinv, "badminton_singles")
    assert taps["near_left"][1] > H  # the tap itself is off-frame
    c = cal.calibrate(taps, "badminton_singles", W, H)
    assert 0.0 < c.visible_fraction < cal.TIER_A_VISIBLE
    assert c.tier in ("B", "C")


def test_missing_or_collinear_taps_raise():
    taps = taps_from(camera(), "badminton_singles")
    with pytest.raises(cal.CalibrationError):
        cal.homography({k: v for k, v in taps.items() if k != "far_left"}, "badminton_singles")
    with pytest.raises(cal.CalibrationError):
        cal.homography(
            {k: [float(i), float(i)] for i, k in enumerate(cal.CORNERS)}, "badminton_singles"
        )
    with pytest.raises(cal.CalibrationError):
        cal.homography(taps, "tennis")


def test_foot_point_is_bottom_centre():
    assert cal.foot_point([100, 200, 140, 380]) == (120.0, 380.0)
    assert cal.foot_point([100, 200, 140, 380, 0.9]) == (120.0, 380.0)


def test_player_metres_maps_feet_and_withholds_off_court_and_tier_c():
    hinv = camera()
    h = np.linalg.inv(hinv)
    h /= h[2, 2]
    tmpl = "badminton_singles"
    foot = cal.to_image(hinv, np.array([1.5, 3.0]))
    box = [foot[0] - 30, foot[1] - 180, foot[0] + 30, foot[1]]
    xy = cal.player_metres(h, box, "A", tmpl)
    assert xy is not None
    np.testing.assert_allclose(xy, (1.5, 3.0), atol=1e-6)
    assert cal.player_metres(h, box, "C", tmpl) is None
    # Just behind the baseline (inside the margin) is kept; a spectator 5 m out is not.
    behind = cal.to_image(hinv, np.array([2.0, -1.5]))
    assert cal.player_metres(h, [behind[0] - 5, 0, behind[0] + 5, behind[1]], "B", tmpl) is not None
    far = cal.to_image(hinv, np.array([-5.0, 6.0]))
    assert cal.player_metres(h, [far[0] - 5, 0, far[0] + 5, far[1]], "A", tmpl) is None
    # A "foot" above the horizon (top of the frame here) has no floor point.
    assert cal.player_metres(h, [900, 0, 1000, 1], "A", tmpl) is None


def test_court_lines_singles_template_geometry():
    lines = {lid: (a, b) for lid, a, b in cal.court_lines_m("badminton_singles")}
    assert len(lines) == 12
    assert lines["left_sideline_singles"] == ((0.0, 0.0), (0.0, 13.40))
    assert lines["right_sideline_singles"][0] == pytest.approx((5.18, 0.0))
    assert lines["left_sideline_doubles"][0] == pytest.approx((-0.46, 0.0))
    assert lines["near_short_service"][0][1] == pytest.approx(4.72)
    assert lines["far_short_service"][0][1] == pytest.approx(8.68)
    assert lines["far_long_service_doubles"][0][1] == pytest.approx(12.64)
    assert lines["far_centre"][0] == pytest.approx((2.59, 8.68))
    dbl = {lid: (a, b) for lid, a, b in cal.court_lines_m("badminton_doubles")}
    assert dbl["left_sideline_doubles"][0] == (0.0, 0.0)
    assert dbl["right_sideline_singles"][0] == pytest.approx((5.64, 0.0))


def test_court_lines_px_project_through_hinv_including_off_frame():
    hinv = camera(cam_xyz=(2.59, -1.0, 2.0), target=(2.59, 9.0, 0.0))
    taps = taps_from(hinv, "badminton_singles")
    _, fit_hinv = cal.homography(taps, "badminton_singles")
    lines = {d["line_id"]: d for d in cal.court_lines_px(fit_hinv, "badminton_singles")}
    assert len(lines) == 12
    sl = lines["left_sideline_singles"]
    np.testing.assert_allclose(sl["a_px"], taps["near_left"], atol=1e-6)
    np.testing.assert_allclose(sl["b_px"], taps["far_left"], atol=1e-6)
    assert sl["a_px"][1] > H  # not clipped to the frame
    assert not any(d["clipped"] for d in lines.values())


def test_court_lines_px_cut_at_horizon():
    # Camera standing on the court 3 m in, facing the far end: the near baseline is behind it, so it
    # is dropped, and the sidelines are cut before the camera, never wrapped through infinity.
    hinv = camera(cam_xyz=(1.0, 3.0, 1.5), target=(1.0, 13.0, 0.0), f=800.0)
    lines = {d["line_id"]: d for d in cal.court_lines_px(hinv, "badminton_singles")}
    assert "near_baseline" not in lines
    assert lines["left_sideline_singles"]["clipped"]
    assert not lines["far_baseline"]["clipped"]
    lines = lines.values()
    for d in lines:
        assert all(np.isfinite(d["a_px"])) and all(np.isfinite(d["b_px"]))


def test_calibration_dict_validates_against_match_and_finalize_schemas():
    taps = taps_from(camera(), "badminton_singles")
    c = cal.calibrate(taps, "badminton_singles", W, H)
    d = c.to_match(cal.calibration_id(taps, "badminton_singles"))
    assert d["tier"] == "A" and len(d["H"]) == 9 and len(d["H_cov"]) == 81
    assert d["calibration_id"] == cal.calibration_id(taps, "badminton_singles")
    jsonschema.validate(d, MATCH_SCHEMA["properties"]["calibration"])
    vr = FINALIZE_SCHEMA["properties"]["vision_result"]["properties"]["calibration"]
    jsonschema.validate(d, vr)
    json.dumps(d, allow_nan=False)


def test_from_calibration_input_uses_session_spec_shape():
    taps = taps_from(camera(), "badminton_doubles")
    spec = {"method": "tap", "plane": "court", "template": "badminton_doubles",
            "points_px": taps, "frame_ms": 1200}  # fmt: skip
    c = cal.from_calibration_input(spec, W, H)
    assert c.template == "badminton_doubles" and c.tier == "A"


def test_real_a55_taps_give_a_valid_tier_b_calibration():
    # Real corner taps of session s_3df758f2 (issue #61), tapped on h13g_singles at 41260 ms.
    run = Path(__file__).resolve().parents[1] / "eval/runs/s3df758_calibration"
    spec = json.loads((run / "calibration_input.json").read_text())
    c = cal.from_calibration_input(spec, 1920, 1080)
    d = c.to_match(cal.calibration_id(spec["points_px"], spec["template"]))
    jsonschema.validate(d, MATCH_SCHEMA["properties"]["calibration"])
    assert c.geometry_problem is None and c.visible_fraction == 1.0 and c.residual_px < 1e-3
    assert d["tier"] == "B" and 0.15 < c.uncertainty.sigma_m < 0.40
    # Every tap lands on the template corner it names: the H direction is image px -> court m.
    for name, xy in zip(cal.CORNERS, cal.template_corners(spec["template"]), strict=True):
        assert np.allclose(cal.to_court(c.h, [spec["points_px"][name]])[0], xy, atol=1e-4)


@pytest.mark.parametrize("spec", [None, {"plane": "court"}, {"plane": "court", "template": "x"}])
def test_incomplete_calibration_input_raises_calibration_error(spec):
    with pytest.raises(cal.CalibrationError):
        cal.from_calibration_input(spec, W, H)
