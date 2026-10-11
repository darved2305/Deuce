import json
from pathlib import Path

import jsonschema
import pytest

from elevate_engine import finalize, r2, submit
from elevate_engine.api import validate

ENGINE = Path(__file__).resolve().parents[1]
SPEC = {"sport": "badminton", "format": "singles", "ruleset": {"id": "bwf_3x21", "version": "1"},
        "mode": "match", "source": {}, "calibration_input": None, "who_is_me": None,
        "free_play_seed": None, "rally_candidates": [], "upload_mode": "multipart"}  # fmt: skip


def real_hit_events():
    return json.loads((ENGINE / "eval/runs/h13g_31_91_hits/hits.json").read_text())["events"]


def body(**over):
    args = dict(analysis_id="a_offline_h13g", match_id="m_1", r2_key="orig/s_3df758/source.mp4",
                session_spec=SPEC, engine_version="0.1.0", models=["tracknet_v3"],
                artifacts={"ball_track": "art_b", "player_tracks": "art_p"},
                window_events=real_hit_events(), calibration=None)  # fmt: skip
    return finalize.request(**{**args, **over})


def test_finalize_body_from_real_hits_validates_against_the_contract():
    b = body()
    validate("analysis.finalize.request.v1", b)
    v = b["vision_result"]
    assert v["session_ids"] == ["s_3df758"] and v["match_id"] == "m_1"
    assert len(v["events"]) == 32 and all(e["type"] == "hit" for e in v["events"])
    # A window hit's track_id is the shuttle trail; in match.v1 it would mean the hitter.
    assert all("track_id" not in e and e["evidence"] == [{"t_ms": e["t_ms"]}] for e in v["events"])
    assert all(c["status"] == "unknown" for c in v["capabilities"].values())
    assert v["players"] == [] and v["calibration"] is None and b["clip_artifact_ids"] == []


def test_a_wrong_match_id_never_validates():
    with pytest.raises(jsonschema.ValidationError):
        validate("analysis.finalize.request.v1", body(match_id="not-a-match"))


def test_track_keys_are_the_two_g8_files_only():
    key = r2.track_key("a_1", "0.1.0", "ball_track")
    assert key == "analysis/a_1/0.1.0/tracks/ball_track.json"
    with pytest.raises(ValueError):
        r2.track_key("a_1", "0.1.0", "ball")


def test_submit_passes_an_optional_match_id_through():
    base = {"analysis_id": "a_1", "r2_key": "orig/s_1/source.mp4", "session_spec": SPEC}
    assert submit.parse(base).match_id is None
    assert submit.parse({**base, "match_id": "m_9"}).match_id == "m_9"
    with pytest.raises(ValueError):
        submit.parse({**base, "match_id": "x"})
    assert submit.parse({**base, "session_id": "s_1"}).session_id == "s_1"
    with pytest.raises(ValueError):  # names a session other than the footage analysed
        submit.parse({**base, "session_id": "s_7"})
    assert body(sid="s_7")["vision_result"]["session_ids"] == ["s_7"]


def test_null_player_tracks_validate_since_contracts_1_9():
    validate("analysis.finalize.request.v1",
             body(artifacts={"ball_track": "art_b", "player_tracks": None}))  # fmt: skip
