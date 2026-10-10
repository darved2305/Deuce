import json
import sys
from pathlib import Path

from elevate_engine.sports import BADMINTON
from elevate_engine.tracking import candidates_by_frame, track

ENGINE = Path(__file__).resolve().parents[1]
T = list(range(0, 1000, 20))  # 50 fps


def flight(t):
    """A shuttle crossing the frame at constant velocity (1500, -600) px/s."""
    return (300 + 1.5 * t, 700 - 0.6 * t, 0.9)


def test_follows_a_moving_shuttle():
    rows, stats = track(T, [[flight(t)] for t in T], BADMINTON)
    assert [r[0] for r in rows] == T and all(r[4] == "observed" for r in rows)
    assert stats["kept"] == 1


def test_drops_a_stationary_wall_mark_and_keeps_the_shuttle():
    wall = (760.0, 320.0, 0.95)
    rows, stats = track(T, [[flight(t), wall] for t in T], BADMINTON)
    assert all(abs(r[1] - flight(r[0])[0]) < 1 for r in rows)  # every row is the shuttle
    assert stats["dropped_static"] >= 1


def test_one_missed_frame_is_filled_and_marked_inferred():
    cands = [[] if t == 400 else [flight(t)] for t in T]
    rows, _ = track(T, cands, BADMINTON)
    filled = [r for r in rows if r[0] == 400]
    assert len(filled) == 1 and filled[0][4] == "inferred" and filled[0][3] is None
    assert abs(filled[0][1] - flight(400)[0]) < 5


def test_two_missed_frames_are_never_filled():
    cands = [[] if t in (400, 420) else [flight(t)] for t in T]
    rows, _ = track(T, cands, BADMINTON)
    assert {400, 420}.isdisjoint(r[0] for r in rows)
    assert all(r[4] == "observed" for r in rows)


def test_on_real_window_1_tracking_beats_raw_candidates_on_the_labels():
    sys.path.insert(0, str(ENGINE / "eval"))
    from score_trail import score

    labels = json.loads((ENGINE.parent / "fixtures" / "fixture_trail.json").read_text())
    w = json.loads((ENGINE / "eval/runs/h13g_w1/window_result.json").read_text())
    lo, hi = (v // 1000 for v in w["emit_range_us"])
    t_ms = list(range(lo, hi, 20))  # h13g is 50 fps with no gaps (PR #22 audit)
    rows, _ = track(t_ms, candidates_by_frame(t_ms, w["ball"]), BADMINTON)
    inside = {"frames": [f for f in labels["frames"] if lo <= f["t_ms"] < hi]}
    raw, tracked = score(inside, w["ball"], 15.0), score(inside, rows, 15.0)
    assert tracked["precision"] > raw["precision"] + 0.15
    assert tracked["recall"] >= raw["recall"] - 0.05


def test_a_wall_mark_that_absorbs_one_stray_point_is_still_static():
    wall = (760.0, 320.0, 0.95)
    cands = [[wall] for _ in T]
    cands[25] = [(778.0, 320.0, 0.6)]  # one stray candidate 18 px away, inside the gate
    rows, stats = track(T, cands, BADMINTON)
    assert rows == [] and stats["dropped_static"] >= 1


def test_filled_frame_uses_the_real_time_of_the_missed_frame():
    t_vfr = [0, 34, 66, 100, 133, 167, 200, 234]  # variable frame rate, no constant step
    cands = [[] if t == 66 else [(300 + 1.5 * t, 700.0, 0.9)] for t in t_vfr]
    rows, _ = track(t_vfr, cands, BADMINTON)
    assert [r[0] for r in rows if r[4] == "inferred"] == [66]
