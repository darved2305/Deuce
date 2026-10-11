"""Player association tests use synthetic boxes only as unit-test inputs."""

import json
from dataclasses import replace
from pathlib import Path

import jsonschema

from elevate_engine import calibration, pipeline, window
from elevate_engine.players import build_window_rows, track_players

ENGINE = Path(__file__).resolve().parents[1]
REAL_DETECTIONS = ENGINE / "eval/runs/h13g_w1/persons.json"


def detection(t_ms, centre_x, *, width=18, score=0.9, y1=20, y2=100):
    return [t_ms, centre_x - width / 2, y1, centre_x + width / 2, y2, score]


def synthetic_calibration():
    # A synthetic, well-conditioned badminton singles H used only by geometry unit tests.
    taps = {
        "near_left": [0.0, 268.0],
        "near_right": [103.6, 268.0],
        "far_right": [103.6, 0.0],
        "far_left": [0.0, 0.0],
    }
    return calibration.calibrate(taps, "badminton_singles", 150, 300, tap_sigma_px=0.1)


def test_crossing_tracks_keep_first_appearance_ids_despite_detection_order():
    detections = []
    for index in range(10):
        t_ms = index * 100
        pair = [
            detection(t_ms, 20 + 7 * index),
            detection(t_ms, 100 - 8 * index),
        ]
        detections.extend(pair if index % 2 == 0 else reversed(pair))

    tracks, stats = track_players(detections, min_track_length=5)

    assert [track.track_id for track in tracks] == ["t_1", "t_2"]
    assert tracks[0].detections[0][1] < tracks[0].detections[-1][1]
    assert tracks[1].detections[0][1] > tracks[1].detections[-1][1]
    assert stats["identity_switches"] >= 0
    assert stats["identity_switch_basis"] == "ambiguous_two_track_hungarian_assignment"


def test_short_occlusion_stays_in_track_and_long_gap_starts_a_new_track():
    short_gap = [detection(t, 40) for t in (0, 100, 200, 500, 600, 700)]
    tracks, _ = track_players(short_gap, max_gap_ms=350, min_track_length=3)
    assert len(tracks) == 1
    assert [row[0] for row in tracks[0].detections] == [0, 100, 200, 500, 600, 700]

    long_gap = [detection(t, 40) for t in (0, 100, 200, 700, 800, 900)]
    tracks, stats = track_players(long_gap, max_gap_ms=350, min_track_length=3)
    assert [track.track_id for track in tracks] == ["t_1", "t_2"]
    assert [len(track.detections) for track in tracks] == [3, 3]
    assert stats["tracks_created"] == 2


def test_singles_limit_keeps_longest_tracks_and_reports_dropped_tracks():
    detections = []
    for index in range(8):
        t_ms = index * 100
        detections.extend([detection(t_ms, 20), detection(t_ms, 75)])
        if index < 4:
            detections.append(detection(t_ms, 130))

    tracks, stats = track_players(detections, format_="singles", min_track_length=3)

    assert len(tracks) == 2
    assert [track.track_id for track in tracks] == ["t_1", "t_2"]
    assert stats["max_tracks"] == 2
    assert stats["dropped_by_limit"] == stats["dropped_tracks"] == 1


def test_selected_ids_follow_first_appearance_and_doubles_keep_four_tracks():
    staggered = []
    for index in range(8):
        t_ms = index * 100
        if index < 3:
            staggered.append(detection(t_ms, 20))
        if index >= 1:
            staggered.append(detection(t_ms, 75))
        if index >= 2:
            staggered.append(detection(t_ms, 130))
    tracks, _ = track_players(staggered, format_="singles", min_track_length=3)
    assert [(track.track_id, track.first_t_ms) for track in tracks] == [("t_1", 100), ("t_2", 200)]

    five_players = [
        detection(t_ms, centre) for t_ms in range(0, 600, 100) for centre in (20, 65, 110, 155, 200)
    ]
    doubles, stats = track_players(five_players, format_="doubles", min_track_length=3)
    assert len(doubles) == 4
    assert stats["max_tracks"] == 4 and stats["dropped_by_limit"] == 1


def test_ambiguous_two_player_overlap_is_flagged_in_stats():
    detections = [
        detection(0, 40, width=30),
        detection(0, 60, width=30),
        detection(100, 50, width=30),
        detection(100, 50, width=30),
    ]
    _tracks, stats = track_players(detections, min_track_length=1)
    assert stats["identity_switches"] == 1
    assert stats["identity_switch_frames_ms"] == [100]


def test_window_rows_project_feet_filter_emit_range_and_validate_schema():
    detections = [
        detection(99, 25),
        detection(100, 25),
        detection(150, 27),
        detection(200, 29),
        detection(100, 70),
        detection(150, 72),
        detection(200, 74),
    ]
    tracks, _ = track_players(detections, min_track_length=2)
    calibration_ = synthetic_calibration()
    rows, row_stats = build_window_rows(tracks, (100, 200), calibration_)

    assert len(rows) == 4
    assert all(row[0] == 100 or row[0] == 150 for row in rows)
    assert all(row[1] in {"t_1", "t_2"} for row in rows)
    assert all(isinstance(value, (int, float)) for row in rows for value in row[2:])
    assert row_stats["rows_emitted"] == 4 and row_stats["feet_withheld"] == 0

    q = {"ball_coverage": 0.0, "camera_motion_px": 0.0, "blur_index": 0.1}
    result = window.build("a_test", 0, (90, 250), (100, 200), [], q, ["dfine_s_coco"], players=rows)
    window.validate(result)

    plan = pipeline.plan_windows(100, 200)[0]
    pipeline_result = pipeline.window_result(
        "a_test",
        plan,
        {"emit": [100, 200], "t_ms": [100], "camera_motion_px": 0.0, "blur_index": 0.1},
        [],
        ["dfine_s_coco"],
        players=rows,
    )
    window.validate(pipeline_result)
    assert pipeline_result["players"] == rows


def test_tier_c_off_court_and_missing_calibration_withhold_feet_not_rows():
    # Contracts 1.8.0 (HW14 item 4): an observed box is always a row; unknown feet are null.
    detections = [
        detection(100, 25),
        detection(200, 27),
        detection(300, 29),
        detection(100, 400),
        detection(200, 402),
        detection(300, 404),
    ]
    tracks, _ = track_players(detections, min_track_length=2)
    good = synthetic_calibration()
    schema = json.loads(window.SCHEMA_PATH.read_text())
    row_schema = schema["properties"]["players"]["items"]

    rows, stats = build_window_rows(tracks, (100, 301), replace(good, tier="C"))
    assert len(rows) == 6 and all(r[6] is None and r[7] is None for r in rows)
    assert stats["feet_withheld_tier_c"] == 6

    rows, stats = build_window_rows(tracks, (100, 301), good)
    assert len(rows) == 6 and sum(r[6] is None for r in rows) == 3
    assert stats["feet_withheld_off_court"] == 3 and stats["feet_withheld"] == 3

    rows, stats = build_window_rows(tracks, (100, 301), None)
    assert len(rows) == 6 and all(r[6] is None for r in rows)
    assert stats["calibration_available"] is False
    assert stats["feet_withheld_no_calibration"] == 6
    for row in rows:
        jsonschema.validate(row, row_schema)


def test_real_h13g_window_one_has_two_tracks_covering_most_detection_frames():
    """Real D-FINE boxes test tracking only; h13g_w1 has no real calibration taps."""
    detections = json.loads(REAL_DETECTIONS.read_text())["rows"]
    tracks, stats = track_players(detections, format_="singles")
    times = {int(row[0]) for row in detections}
    by_track = [{row[0] for row in track.detections} for track in tracks]
    both_track_coverage = sum(all(t in observed for observed in by_track) for t in times) / len(
        times
    )

    assert len(tracks) == 2
    assert both_track_coverage >= 0.9
    assert isinstance(stats["identity_switches"], int)
    assert stats["identity_switches"] == len(stats["identity_switch_frames_ms"])


def test_real_h13g_players_stay_on_their_own_half_when_they_cross_in_the_image():
    # Real D-FINE-S person boxes on h13g 37-43 s and the real A55 corner taps of the same clip
    # (issue #61). At 42520 ms the far player stands right behind the near one in the image.
    det = json.loads((ENGINE / "eval/runs/h13g_w1/persons.json").read_text())["rows"]
    spec = {"plane": "court", "method": "tap", "frame_ms": 41260, "template": "badminton_singles",
            "points_px": {"far_left": [624, 664.4], "far_right": [1331.6, 664.4],
                          "near_left": [83.6, 1014.7], "near_right": [1831.1, 1014.7]}}  # fmt: skip
    tracks, _ = track_players(det)
    rows, _ = build_window_rows(
        tracks, (37000, 43000), calibration.from_calibration_input(spec, 1920, 1080)
    )
    halves = {}
    for row in rows:
        halves.setdefault(row[1], set()).add(row[7] > calibration.NET_Y_M)
    assert len(halves) == 2 and all(len(h) == 1 for h in halves.values())
    assert {next(iter(h)) for h in halves.values()} == {True, False}
