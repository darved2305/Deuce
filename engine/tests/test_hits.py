from elevate_engine import window
from elevate_engine.hits import detect_direction_changes, hit_candidates


def trajectory_with_two_turns():
    rows = []
    for t_ms in range(0, 621, 20):
        # Shuttle rises to an apex at 200 ms, then descends. Its horizontal direction
        # separately reverses at 420 ms. A single-frame outlier exercises noise resistance.
        y = 200 + 0.006 * (t_ms - 200) ** 2
        x = 300 + (4 * t_ms if t_ms <= 420 else 4 * 420 - 4 * (t_ms - 420))
        if t_ms == 180:
            y += 45
        rows.append([t_ms, x, y, 0.9, "observed"])
    return rows


def test_direction_changes_find_parabola_apex_and_horizontal_reversal():
    turns = detect_direction_changes(trajectory_with_two_turns())

    assert len(turns) == 2
    assert abs(turns[0]["t_ms"] - 200) <= 20
    assert abs(turns[1]["t_ms"] - 420) <= 20
    assert all(turn["t_sigma_ms"] > 0 for turn in turns)


def test_direction_changes_ignore_inferred_only_motion():
    rows = [[t, 100 + t, 200, 0.9, "observed"] for t in range(0, 241, 20)]
    rows[6] = [120, 100, 400, None, "inferred"]

    assert detect_direction_changes(rows) == []


def test_audio_and_trajectory_fuse_into_deterministic_schema_valid_candidates():
    trail = trajectory_with_two_turns()
    audio = [[215, 0.8], [600, 0.6]]

    events = hit_candidates(trail, audio, "a_test", 3, tolerance_ms=40)
    repeated = hit_candidates(trail, audio, "a_test", 3, tolerance_ms=40)

    assert events == repeated
    assert len(events) == 3
    assert events[0]["cues"] == ["trajectory", "audio"]
    assert events[1]["cues"] == ["trajectory"]
    assert events[2]["cues"] == ["audio"]
    assert all(event["type"] == "hit" and event["state"] == "candidate" for event in events)
    assert all(event["track_id"].startswith("t_") for event in events)

    result = window.build(
        "a_test",
        3,
        (0, 700),
        (0, 700),
        trail,
        {"ball_coverage": 1.0, "camera_motion_px": 0.0, "blur_index": 0.1},
        ["tracknet_v3"],
        audio,
        events,
    )
    window.validate(result)
