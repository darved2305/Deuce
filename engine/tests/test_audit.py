"""Clip audit on real timestamps from the EuroGames Bern 2023 clip (see tests/data provenance)."""

import json
import os
from fractions import Fraction
from pathlib import Path

import pytest

from elevate_engine.audit import audit_pts, probe

DATA = json.loads((Path(__file__).parent / "data" / "eurogames_pts.json").read_text())
TIME_BASE = Fraction(DATA["time_base"])


def test_real_clip_timing():
    report = audit_pts(DATA["pts"], TIME_BASE)
    assert report["frames"] == 767
    assert report["duration_ms"] == 25567
    assert report["nominal_fps"] == pytest.approx(30.0, abs=0.05)
    assert report["duplicate_pts"] == 0
    assert report["slow_segments"] == []


def test_millisecond_rounding_is_not_vfr():
    # Steps alternate 33/34 ms only because the timebase is 1/1000.
    report = audit_pts(DATA["pts"], TIME_BASE)
    assert (report["step_p05_ms"], report["step_p95_ms"]) == (33.0, 34.0)
    assert report["vfr"] is False


def test_finds_the_one_dropped_frame():
    report = audit_pts(DATA["pts"], TIME_BASE)
    assert report["gaps"] == [{"after_ms": 833, "step_ms": 67.0}]


def test_order_does_not_matter():
    shuffled = DATA["pts"][::2] + DATA["pts"][1::2]
    assert audit_pts(shuffled, TIME_BASE) == audit_pts(DATA["pts"], TIME_BASE)


@pytest.mark.skipif(
    not os.environ.get("ELEVATE_TEST_CLIP"), reason="set ELEVATE_TEST_CLIP to the real clip"
)
def test_probe_real_clip():
    report = probe(os.environ["ELEVATE_TEST_CLIP"])
    assert (report["codec"], report["width"], report["height"]) == ("vp8", 848, 464)
    assert report["rotation_deg"] == 0
    assert report["timing_reliable"] is True
    assert report["gaps"] == audit_pts(DATA["pts"], TIME_BASE)["gaps"]


@pytest.mark.parametrize("drop_at_ms", [15000, 5000])  # 5000: the drop covers most steps
def test_frame_rate_drop_is_vfr_not_gaps(drop_at_ms):
    # The real timestamps with every other frame removed after the drop: 30 fps becomes 15 fps.
    pts = [p for i, p in enumerate(DATA["pts"]) if p < drop_at_ms or i % 2 == 0]
    report = audit_pts(pts, TIME_BASE)
    assert report["vfr"] is True
    assert report["gaps"] == [{"after_ms": 833, "step_ms": 67.0}]
    [segment] = report["slow_segments"]
    assert segment["median_step_ms"] == pytest.approx(66.7, abs=0.5)
    assert segment["steps"] > 100
    assert report["nominal_fps"] == pytest.approx(30.0, abs=0.05)


def test_rejects_too_few_frames():
    with pytest.raises(ValueError):
        audit_pts(DATA["pts"][:1], TIME_BASE)
