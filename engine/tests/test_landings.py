import json
from pathlib import Path

import numpy as np

from elevate_engine import landings, pipeline, window
from elevate_engine.api import validate
from elevate_engine.sports import BADMINTON
from tests.test_pipeline import per_window

ENGINE = Path(__file__).resolve().parents[1]
CAL = json.loads((ENGINE / "eval/runs/s3df758_calibration/calibration.json").read_text())


def real_windows():
    plans = pipeline.plan_windows(31000, 91000)
    return pipeline.assemble("a_offline_h13g", plans, per_window(31000, 91000), BADMINTON)[0]


def test_real_h13g_floor_landings_are_found_and_frame_exits_are_not():
    # Saved real candidates of h13g 31-91 s. Checked on real frames: the shuttle lies on the floor
    # at 49960, 73760 and 83840 ms; flights ending at y < 10 px (33080, 38700, 58420, 62780,
    # 79400 ms) left the top of the frame. Known false positive: a shuttle held still at hand
    # height between rallies (41620, 89300 ms) looks the same to a trajectory-only rule.
    ws = real_windows()
    found = [e["t_ms"] for w in ws for e in w["events"] if e["type"] == "landing"]
    for t in (49960, 73760, 83840):
        assert any(abs(f - t) <= 40 for f in found), (t, found)
    for t in (33080, 38700, 58420, 62780, 79400):
        assert all(abs(f - t) > 300 for f in found), (t, found)
    for w in ws:
        window.validate(w)


def test_landings_get_court_metres_only_from_a_usable_calibration():
    p = landings.court_point(CAL, [206.2, 885.0])
    assert p["plane"] == "court" and len(p["cov_m2"]) == 2
    assert -1.0 < p["contact_xy_m"][0] < 0.0  # just outside the left singles line, as on the frame
    assert landings.court_point({**CAL, "tier": "C"}, [206.2, 885.0]) is None
    assert landings.court_point(None, [206.2, 885.0]) is None


def test_finalize_carries_landings_with_metres_and_validates():
    from elevate_engine import finalize

    events = [e for w in real_windows() for e in w["events"]]
    spec = {
        "sport": "badminton",
        "format": "singles",
        "ruleset": {"id": "bwf_3x21", "version": "1"},
    }
    body = finalize.request(analysis_id="a_offline_h13g", match_id="m_1",
                            r2_key="orig/s_3df758/source.mp4", session_spec=spec,
                            engine_version="0.1.0", models=["tracknet_v3"],
                            artifacts={"ball_track": None, "player_tracks": None},
                            window_events=events, calibration=CAL)  # fmt: skip
    validate("analysis.finalize.request.v1", body)
    lands = [e for e in body["vision_result"]["events"] if e["type"] == "landing"]
    assert lands and all("contact_xy_m" in e and e["plane"] == "court" for e in lands)


def test_points_beyond_the_court_horizon_or_static_flights_give_nothing():
    h = np.array(CAL["H"]).reshape(3, 3)
    # The horizon row of this real calibration: w = h[2] . (x, y, 1) = 0 at y = -h20 x - h22 / h21.
    y_horizon = -(h[2, 0] * 960 + h[2, 2]) / h[2, 1]
    assert landings.court_point(CAL, [960.0, y_horizon]) is None
    assert landings.court_point(CAL, [960.0, y_horizon - 50]) is None  # beyond it: not the floor
    assert landings.court_point(CAL, [960.0, 900.0]) is not None
    still = [[t, 500.0, 600.0, 0.9, "observed"] for t in range(0, 400, 20)]
    assert landings.detect_landings(still, known_until_ms=5000) == []
