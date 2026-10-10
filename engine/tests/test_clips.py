"""engine/eval/clips.json must mirror fixtures/clips.md (D-261010-HW1)."""

import json
import re
from pathlib import Path

ENGINE = Path(__file__).resolve().parent.parent
CLIPS = json.loads((ENGINE / "eval" / "clips.json").read_text())["clips"]
CLIPS_MD = (ENGINE.parent / "fixtures" / "clips.md").read_text()


def _hashes(clip):
    if "sha256" in clip:
        yield clip["sha256"]
    for stream in clip.get("streams", {}).values():
        yield stream["sha256"]


def test_one_clip_per_role():
    roles = [c["role"] for c in CLIPS]
    assert sorted(roles) == ["held_out", "low_resolution", "method_check", "working"]


def test_nobody_tunes_on_held_out_or_method_check():
    for clip in CLIPS:
        assert clip["tune"] is (clip["role"] in {"working", "low_resolution"}), clip["role"]


def test_hashes_are_pinned_and_match_clips_md():
    for clip in CLIPS:
        for sha in _hashes(clip):
            assert re.fullmatch(r"[0-9a-f]{64}", sha)
            # stdata_test's zip hash is first recorded by Person 1; clips.md gets it after.
            if clip["id"] != "stdata_test":
                assert sha in CLIPS_MD, f"{clip['id']}: {sha} not in fixtures/clips.md"


def test_held_out_segment_matches_clips_md():
    held_out = next(c for c in CLIPS if c["role"] == "held_out")
    working = next(c for c in CLIPS if c["role"] == "working")
    assert held_out["segment_s"] == [300, 360] and "300–360 s" in CLIPS_MD
    assert working["segment_s"] == [31, 91] and "31–91 s" in CLIPS_MD
