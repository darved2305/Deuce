"""Range decoding on the real working clip (set ELEVATE_H13G to h13g_singles.mp4 to run)."""

import json
import os
from pathlib import Path

import pytest

from elevate_engine.video import frames_in_range, source_size

CLIP = os.environ.get("ELEVATE_H13G")
LABELS = json.loads(
    (Path(__file__).resolve().parents[2] / "fixtures" / "fixture_trail.json").read_text()
)


@pytest.mark.skipif(not CLIP, reason="set ELEVATE_H13G to the real h13g_singles.mp4")
def test_range_decode_lands_on_the_labelled_frame_times():
    frames = list(frames_in_range(CLIP, 40000, 44000, transform=lambda rgb: rgb.shape))
    times = [f.t_ms for f in frames]
    assert times == list(range(40000, 44000, 20))  # 50 fps, no gaps (PR #22 audit)
    assert {f["t_ms"] for f in LABELS["frames"]} <= set(times)
    assert frames[0].image == (1080, 1920, 3) and source_size(CLIP) == (1920, 1080)
