"""Ball rows and trail scoring against the real fixture (fixtures/fixture_trail.json)."""

import json
import sys
from pathlib import Path

import jsonschema
import numpy as np

from elevate_engine.tracknet.infer import Blob
from elevate_engine.tracknet.run import ball_rows

REPO = Path(__file__).resolve().parents[2]
LABELS = json.loads((REPO / "fixtures" / "fixture_trail.json").read_text())
VISIBLE = sum(1 for f in LABELS["frames"] if f["ball"])  # the fixture grows (PR #21: 153)
SCHEMA = json.loads((REPO / "packages/contracts/schemas/window_result.v1.schema.json").read_text())
sys.path.insert(0, str(REPO / "engine" / "eval"))
from score_trail import score  # noqa: E402


def _window(ball: list) -> dict:
    return {
        "schema": "window_result.v1", "analysis_id": "a_test", "window_seq": 0,
        "decode_window_us": [0, 6_000_000], "emit_range_us": [0, 6_000_000],
        "models": ["tracknet_v3"], "ball": ball, "players": [], "onsets": [], "events": [],
        "quality": {"ball_coverage": 0.0, "camera_motion_px": 0.0, "blur_index": 0.0},
    }  # fmt: skip


def test_ball_rows_scale_to_source_and_validate_against_contract():
    blobs = [[Blob(256, 144, 9, 0.93), Blob(10, 10, 1, 0.6)], []]
    rows = ball_rows([40000, 40020], blobs, 1920, 1080)
    assert rows == [[40000, 960.0, 540.0, 0.93, "candidate"], [40000, 37.5, 37.5, 0.6, "candidate"]]
    jsonschema.validate(_window(rows), SCHEMA)


def test_perfect_prediction_scores_every_visible_label():
    rows = [[f["t_ms"], *f["ball"], 0.9, "candidate"] for f in LABELS["frames"] if f["ball"]]
    s = score(LABELS, rows, 15.0)
    assert VISIBLE > 0 and s["labelled_visible"] == VISIBLE and s["counts"]["TP"] == VISIBLE
    assert s["counts"]["TN"] == len(LABELS["frames"]) - VISIBLE
    assert s["precision"] == s["recall"] == 1.0


def test_no_prediction_misses_every_visible_label():
    s = score(LABELS, [], 15.0)
    assert s["counts"]["FN"] == VISIBLE and s["recall"] == 0.0


def test_top_candidate_is_highest_confidence():
    f = next(f for f in LABELS["frames"] if f["ball"])
    far = (np.array(f["ball"]) + 200).tolist()
    rows = [[f["t_ms"], *far, 0.4, "candidate"], [f["t_ms"], *f["ball"], 0.9, "candidate"]]
    one = {"frames": [f]}
    assert score(one, rows, 15.0)["counts"]["TP"] == 1


def test_fewer_frames_than_a_window_give_empty_candidate_lists():
    from elevate_engine.tracknet.run import track

    frames = [np.zeros((3, 288, 512), dtype=np.uint8)] * 3
    out = list(track(model=None, seq_len=8, frames=frames))
    assert out == [[], [], []]
    assert ball_rows([0, 20, 40], out, 1920, 1080) == []
