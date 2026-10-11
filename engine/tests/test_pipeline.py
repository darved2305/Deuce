import json
from pathlib import Path

import pytest

from elevate_engine import calibration, pipeline
from elevate_engine import window as win
from elevate_engine.players import LivePlayers, build_window_rows, track_players
from elevate_engine.sports import BADMINTON
from elevate_engine.tracking import candidates_by_frame, track

ENGINE = Path(__file__).resolve().parents[1]


def test_windows_tile_the_range_in_order_with_clipped_overlap():
    plans = pipeline.plan_windows(0, 14_500)
    assert [p.emit for p in plans] == [(0, 6000), (6000, 12000), (12000, 14500)]
    assert [p.decode for p in plans] == [(0, 6750), (5250, 12750), (11250, 14500)]
    assert [p.seq for p in plans] == [0, 1, 2]
    with pytest.raises(ValueError):
        pipeline.plan_windows(5, 5)


def test_static_filter_drops_a_spot_in_most_frames_and_keeps_a_moving_one():
    wall = (1260.0, 300.0, 0.9)
    cands = [[wall, (300.0 + 10 * i, 700.0, 0.8)] if i % 2 else [(300.0 + 10 * i, 700.0, 0.8)]
             for i in range(20)]  # fmt: skip
    out, dropped = pipeline.static_filter(cands)
    assert dropped == 10 and all(c[0] != 1260.0 for frame in out for c in frame)
    sparse = [[wall] if i < 5 else [] for i in range(20)]  # 25% of frames: kept
    assert pipeline.static_filter(sparse)[1] == 0


def per_window(start, end, window_ms=pipeline.WINDOW_MS):
    """Real h13g TrackNet candidates per planned window, plus the real D-FINE person boxes
    (eval/runs/h13g_w1, 37000-43000 ms) of the frames inside each emitted range."""
    data = json.loads((ENGINE / "eval/runs/h13g_31_91/candidates.json").read_text())
    persons = json.loads(REAL_PERSONS.read_text())["rows"]
    out = []
    for p in pipeline.plan_windows(start, end, window_ms):
        t = [x for x in data["t_ms"] if p.emit[0] <= x < p.emit[1]]
        out.append({"emit": list(p.emit), "t_ms": t, "cands": candidates_by_frame(t, data["ball"]),
                    "camera_motion_px": 0.05, "blur_index": 0.14,
                    "persons": [r for r in persons if p.emit[0] <= r[0] < p.emit[1]]})  # fmt: skip
    return out


REAL_PERSONS = ENGINE / "eval/runs/h13g_w1/persons.json"
MODELS = ("tracknet_v3", "dfine_s_coco")


def real_calibration():
    # Real taps for the h13g file (eval/runs/s3df758_calibration), source 1920x1080.
    spec = json.loads((ENGINE / "eval/runs/s3df758_calibration/calibration_input.json").read_text())
    return calibration.from_calibration_input(spec, 1920, 1080)


def test_player_ids_stay_the_same_across_a_real_window_boundary():
    # The real 37-43 s person boxes split at 40 s into two windows, posted one by one.
    plans = pipeline.plan_windows(37000, 43000, 3000)
    ws = per_window(37000, 43000, 3000)
    assert [len(w["persons"]) for w in ws] == [72, 73]  # all 145 real boxes, split at 40 s
    cal = real_calibration()
    full, _ = pipeline.assemble("a_x", plans, ws, BADMINTON, MODELS, LivePlayers(cal))
    live = LivePlayers(cal)
    posted = [pipeline.window_for_posting("a_x", plans, ws, k, BADMINTON, MODELS, live)
              for k in range(len(ws))]  # fmt: skip
    assert posted == full
    for w in full:
        win.validate(w)
        assert {r[1] for r in w["players"]} == {"t_1", "t_2"}
    # Same rows as associating the whole 6 s at once: the split changed no id and no box.
    tracks, _ = track_players(json.loads(REAL_PERSONS.read_text())["rows"])
    whole, _ = build_window_rows(tracks, (37000, 43000), cal)
    assert [r for w in full for r in w["players"]] == whole
    # Each id stays on its own half (net at 6.70 m) in both windows.
    for tid in ("t_1", "t_2"):
        ys = [r[7] for w in full for r in w["players"] if r[1] == tid and r[7] is not None]
        assert ys and (max(ys) < 6.7 or min(ys) > 6.7)
    assert live.track_ids == ["t_1", "t_2"]


def test_two_player_ids_hold_for_the_whole_real_minute_each_on_its_own_half():
    # Real D-FINE boxes of all ten h13g windows (31-91 s, Modal run ap-igQSaM44yIqvQB2dY7hcDv).
    saved = json.loads((ENGINE / "eval/runs/h13g_31_91_persons/persons.json").read_text())
    plans = pipeline.plan_windows(31000, 91000)
    ws = per_window(31000, 91000)
    for w, s in zip(ws, saved["windows"], strict=True):
        assert w["emit"] == s["emit"]
        w["persons"] = s["rows"]
    live = LivePlayers(real_calibration())
    posted = [pipeline.window_for_posting("a_x", plans, ws, k, BADMINTON, MODELS, live)
              for k in range(len(ws))]  # fmt: skip
    assert live.track_ids == ["t_1", "t_2"]
    for w in posted:
        win.validate(w)
        assert {r[1] for r in w["players"]} == {"t_1", "t_2"}
    rows = [r for w in posted for r in w["players"]]
    near = [r[7] for r in rows if r[1] == "t_1" and r[7] is not None]
    far = [r[7] for r in rows if r[1] == "t_2" and r[7] is not None]
    assert len(near) == 720 and max(near) < 6.7  # one box per sampled frame, all near half
    assert len(far) >= 600 and min(far) > 6.7


def test_live_players_refuse_detections_out_of_time_order():
    live = LivePlayers(None)
    live.feed_windows([{"persons": [[1000, 0, 0, 10, 10, 0.9]]}])
    with pytest.raises(ValueError):
        live.feed_windows([{}, {"persons": [[900, 0, 0, 10, 10, 0.9]]}])


def test_windowed_tracking_equals_tracking_the_whole_range_at_once():
    windows = per_window(31000, 91000)
    rows_w, _ = pipeline.track_windows(windows, BADMINTON)
    t_all = [t for w in windows for t in w["t_ms"]]
    c_all = [pipeline.static_filter(w["cands"])[0] for w in windows]
    rows_1, _ = track(t_all, [[tuple(c) for c in f] for w in c_all for f in w], BADMINTON)
    assert rows_w == rows_1


def test_assembled_real_windows_validate_and_cover_the_range_in_order():
    windows, _ = pipeline.assemble("a_offline_h13g", pipeline.plan_windows(31000, 91000),
                                   per_window(31000, 91000), BADMINTON)  # fmt: skip
    from elevate_engine import window as win

    for w in windows:
        win.validate(w)
    spans = [tuple(v // 1000 for v in w["emit_range_us"]) for w in windows]
    assert spans[0][0] == 31000 and spans[-1][1] == 91000
    assert all(a[1] == b[0] for a, b in zip(spans, spans[1:], strict=False))


def test_posting_each_window_with_its_neighbours_equals_the_full_assembly():
    plans = pipeline.plan_windows(31000, 91000)
    ws = per_window(31000, 91000)
    full, _ = pipeline.assemble("a_x", plans, ws, BADMINTON)
    for k in range(len(ws)):
        assert pipeline.window_for_posting("a_x", plans, ws, k, BADMINTON) == full[k]
    # With player rows (real boxes in window 1, 37-43 s) posting still equals the assembly.
    full, _ = pipeline.assemble(
        "a_x", plans, ws, BADMINTON, MODELS, LivePlayers(real_calibration())
    )
    live = LivePlayers(real_calibration())
    for k in range(len(ws)):
        assert pipeline.window_for_posting("a_x", plans, ws, k, BADMINTON, MODELS, live) == full[k]
    assert [bool(w["players"]) for w in full[:3]] == [False, True, False]


def test_window_result_forwards_optional_onsets_and_hit_events():
    plan = pipeline.plan_windows(0, 100)[0]
    result = {
        "emit": list(plan.emit),
        "t_ms": [0, 50],
        "camera_motion_px": 0.0,
        "blur_index": 0.1,
    }
    event = {
        "event_id": "e_test",
        "type": "hit",
        "t_ms": 50,
        "t_sigma_ms": 10,
        "track_id": "t_test",
        "image_xy": [12.0, 24.0],
        "cues": ["trajectory", "audio"],
        "state": "candidate",
    }

    window = pipeline.window_result(
        "a_test",
        plan,
        result,
        [[50, 12.0, 24.0, 0.9, "observed"]],
        ["tracknet_v3"],
        [[25, 0.8], [100, 0.5]],
        [event],
    )

    assert window["onsets"] == [[25, 0.8]]
    assert window["events"] == [event]
    from elevate_engine import window as win

    win.validate(window)


def test_real_windows_carry_their_onsets_and_hits_inside_their_own_range():
    plans = pipeline.plan_windows(31000, 91000)
    ws = per_window(31000, 91000)
    # Onsets as Models.candidates measures them: per emitted range, so no duplicates.
    ws[1]["onsets"] = [[ws[1]["t_ms"][10], 0.7]]
    full, _ = pipeline.assemble("a_x", plans, ws, BADMINTON)
    from elevate_engine import window as win

    assert sum(len(w["events"]) for w in full) > 0
    for w in full:
        win.validate(w)
        lo, hi = (v // 1000 for v in w["emit_range_us"])
        assert all(lo <= e["t_ms"] < hi and e["cues"][0] == "trajectory" for e in w["events"])
    assert full[1]["onsets"] == ws[1]["onsets"] and full[0]["onsets"] == []
    for k in range(len(ws)):
        assert pipeline.window_for_posting("a_x", plans, ws, k, BADMINTON) == full[k]
