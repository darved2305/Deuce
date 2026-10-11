import json
from pathlib import Path

from elevate_engine import pipeline
from elevate_engine.hits import estimate_av_offset_ms
from elevate_engine.sports import BADMINTON
from tests.test_pipeline import per_window

ENGINE = Path(__file__).resolve().parents[1]


def real_windows_with_onsets():
    # Real candidates (h13g 31-91 s) and the real onsets measured by the same Modal run.
    pw = per_window(31000, 91000)
    data = json.loads((ENGINE / "eval/runs/h13g_31_91_hits/onsets_by_window.json").read_text())
    for p, onsets in zip(pw, data["onsets"], strict=True):
        p["onsets"] = onsets
    return pw


def test_real_audio_lag_is_estimated_and_most_hits_gain_the_audio_cue():
    pw = real_windows_with_onsets()
    plans = pipeline.plan_windows(31000, 91000)
    full, _ = pipeline.assemble("a_offline_h13g", plans, pw, BADMINTON)
    hits = [e for w in full for e in w["events"] if e["type"] == "hit"]
    assert sum("audio" in e["cues"] for e in hits) >= 20  # 9 of 32 before the lag was corrected
    # The turn at 79000 ms sits on the boundary of windows 7 and 8, whose offsets differ (120 vs
    # 0). The window holding the turn owns the hit: it is emitted exactly once.
    assert sum(abs(e["t_ms"] - 79000) <= 40 for e in hits) == 1
    assert len({e["event_id"] for e in hits}) == len(hits)
    for k in range(len(pw)):
        assert pipeline.window_for_posting("a_offline_h13g", plans, pw, k, BADMINTON) == full[k]


def test_offset_is_zero_when_not_well_determined():
    turns = [{"t_ms": t} for t in (1000, 2000, 3000)]
    assert estimate_av_offset_ms(turns, [[1130, 1.0], [2130, 1.0], [3130, 1.0]]) == 0  # 3 pairs
    many = [{"t_ms": 1000 * i} for i in range(1, 9)]
    scattered = [[1000 * i + (i * 37) % 240 - 120, 1.0] for i in range(1, 9)]
    assert estimate_av_offset_ms(many, scattered) == 0  # spread too wide
    steady = [[1000 * i + 130, 1.0] for i in range(1, 9)]
    assert estimate_av_offset_ms(many, steady) == 130
