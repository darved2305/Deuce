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


def test_apex_is_not_a_hit_but_a_horizontal_reversal_is():
    # Rising-then-falling with the same horizontal direction (200 ms) is gravity at the top
    # of a flight; the horizontal reversal at 420 ms is a racket contact.
    turns = detect_direction_changes(trajectory_with_two_turns())

    assert len(turns) == 1
    assert abs(turns[0]["t_ms"] - 420) <= 20
    assert turns[0]["t_sigma_ms"] > 0


def test_a_falling_shuttle_sent_back_up_is_a_hit():
    # Image y grows downward: falling ~15 px/frame until the racket at 200 ms, then sent back
    # up at the same speed (a sharp reversal, as a real contact is).
    rows = [[t, 300 + 4 * t, 500 - 0.75 * abs(t - 200), 0.9, "observed"]
            for t in range(0, 421, 20)]  # fmt: skip
    turns = detect_direction_changes(rows)
    assert len(turns) == 1 and abs(turns[0]["t_ms"] - 200) <= 20


def test_a_far_court_hit_is_not_mistaken_for_an_apex():
    # Seen from behind the baseline, a far-court smash keeps its image x direction: the shuttle
    # rises fast towards the far player (y decreasing ~15 px/frame) and comes back down fast.
    # Fast on both sides means a contact, not the slow reversal at the top of a flight.
    rows = [[t, 600 + 2 * t, 300 + 0.75 * abs(t - 200), 0.9, "observed"]
            for t in range(0, 421, 20)]  # fmt: skip
    turns = detect_direction_changes(rows)
    assert len(turns) == 1 and abs(turns[0]["t_ms"] - 200) <= 20


def test_a_turn_is_not_measured_across_a_tracking_gap():
    # Two straight flights 400 ms apart with nothing observed between them: the secants would
    # join unrelated motion (a lost track or a switch), so no turn is reported.
    first = [[t, 100 + 4 * t, 500 - 2 * t, 0.9, "observed"] for t in range(0, 121, 20)]
    second = [[t, 900 - 4 * (t - 520), 300 + 2 * (t - 520), 0.9, "observed"]
              for t in range(520, 641, 20)]  # fmt: skip
    assert detect_direction_changes(first + second) == []


def test_direction_changes_ignore_inferred_only_motion():
    rows = [[t, 100 + t, 200, 0.9, "observed"] for t in range(0, 241, 20)]
    rows[6] = [120, 100, 400, None, "inferred"]

    assert detect_direction_changes(rows) == []


def test_audio_and_trajectory_fuse_into_deterministic_schema_valid_candidates():
    trail = trajectory_with_two_turns()
    audio = [[425, 0.8], [600, 0.6]]  # one onset at the contact, one with no turn

    events = hit_candidates(trail, audio, "a_test", 3, tolerance_ms=40)
    repeated = hit_candidates(trail, audio, "a_test", 3, tolerance_ms=40)

    assert events == repeated
    # The onset at 600 ms has no trajectory turn: it stays an onset, not a hit event.
    assert len(events) == 1
    assert events[0]["cues"] == ["trajectory", "audio"]
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
