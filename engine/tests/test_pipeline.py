import json
from pathlib import Path

import pytest

from elevate_engine import pipeline
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


def per_window(start, end):
    data = json.loads((ENGINE / "eval/runs/h13g_31_91/candidates.json").read_text())
    out = []
    for p in pipeline.plan_windows(start, end):
        t = [x for x in data["t_ms"] if p.emit[0] <= x < p.emit[1]]
        out.append({"emit": list(p.emit), "t_ms": t, "cands": candidates_by_frame(t, data["ball"]),
                    "camera_motion_px": 0.05, "blur_index": 0.14})  # fmt: skip
    return out


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
