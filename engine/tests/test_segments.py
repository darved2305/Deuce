"""Segment uploads (contracts §2 chunked upload): collect, verify, join, gaps.

The join tests cut the real working clip into keyframe-aligned pieces (set ELEVATE_H13G to
h13g_singles.mp4 to run them), the way the app's recorder or remuxer produces them.
"""

import hashlib
import os
from fractions import Fraction
from pathlib import Path

import av
import pytest

from elevate_engine import segments as seg
from elevate_engine.submit import parse

CLIP = os.environ.get("ELEVATE_H13G")
# Real keyframes of h13g_singles between 30 and 54 s (PyAV demux): pieces of 5-7 s.
CUTS_S = [30.24, 37.24, 42.24, 49.2, 53.84]


def _cut(
    src: str, out_dir: Path, rebase: bool, audio_overrun_s: float = 0.0
) -> tuple[list[Path], list[dict]]:
    """Remux [CUTS_S[i], CUTS_S[i+1]) into pieces; rebase=True starts each piece at t=0.
    audio_overrun_s keeps audio running past each piece's end (as some remuxers cut)."""
    paths, manifests = [], []
    for i, (a, b) in enumerate(zip(CUTS_S, CUTS_S[1:], strict=False)):
        path = out_dir / f"{i}.mp4"
        with av.open(src) as source, av.open(str(path), "w", format="mp4") as target:
            v_in, a_in = source.streams.video[0], source.streams.audio[0]
            v_out = target.add_stream_from_template(v_in)
            a_out = target.add_stream_from_template(a_in)
            source.seek(int(a / v_in.time_base), stream=v_in, backward=True)
            shift = {}
            first_v = None
            for packet in source.demux(v_in, a_in):
                if packet.pts is None or not packet.size:
                    continue
                t = packet.pts * packet.time_base
                end = Fraction(b).limit_denominator(100)
                if packet.stream.type == "audio" and t < end + Fraction(audio_overrun_s):
                    pass
                elif t >= end:
                    if packet.stream.type == "video":
                        break
                    continue
                if t < Fraction(a).limit_denominator(100) - Fraction(1, 1000):
                    continue
                if packet.stream.type == "video" and first_v is None:
                    first_v = t
                tb = packet.time_base
                off = shift.setdefault(
                    packet.stream.type, round(Fraction(a).limit_denominator(100) / tb)
                )
                if rebase:
                    packet.pts -= off
                    if packet.dts is not None:
                        packet.dts -= off
                packet.stream = v_out if packet.stream.type == "video" else a_out
                target.mux(packet)
        data = path.read_bytes()
        start_us = round(
            (Fraction(a).limit_denominator(100) - Fraction(CUTS_S[0]).limit_denominator(100))
            * 1_000_000
        )
        end_us = round(
            (Fraction(b).limit_denominator(100) - Fraction(CUTS_S[0]).limit_denominator(100))
            * 1_000_000
        )
        manifests.append({"seq": i, "pts_start_us": start_us, "pts_end_us": end_us,
                          "starts_on_sync_sample": True, "bytes": len(data),
                          "sha256": hashlib.sha256(data).hexdigest(),
                          "final": i == len(CUTS_S) - 2,
                          "r2_key": f"seg/s_test/{i}.mp4"})  # fmt: skip
        paths.append(path)
    return paths, manifests


def _video_times_ms(path: str, lo_s: float | None = None, hi_s: float | None = None) -> list[int]:
    """Video packet times in ms from the first kept frame (contracts §0)."""
    with av.open(path) as c:
        s = c.streams.video[0]
        ts = sorted(p.pts * s.time_base for p in c.demux(s) if p.pts is not None and p.size)
    if lo_s is not None:
        ts = [
            t
            for t in ts
            if Fraction(lo_s).limit_denominator(100) <= t < Fraction(hi_s).limit_denominator(100)
        ]
    return [round((t - ts[0]) * 1000) for t in ts]


@pytest.mark.skipif(not CLIP, reason="set ELEVATE_H13G to the real h13g_singles.mp4")
@pytest.mark.parametrize("rebase", [False, True], ids=["original-timeline", "pieces-from-zero"])
def test_join_of_real_pieces_keeps_every_frame_time_of_the_original(tmp_path, rebase):
    paths, manifests = _cut(CLIP, tmp_path, rebase)
    for p, m in zip(paths, manifests, strict=True):
        seg.verify(p, m)
    out = seg.join(paths, manifests, tmp_path / "joined.mp4")
    joined = _video_times_ms(str(out))
    original = _video_times_ms(CLIP, CUTS_S[0], CUTS_S[-1])
    assert len(joined) == len(original) > 1000  # ~23.6 s at 50 fps
    assert max(abs(a - b) for a, b in zip(joined, original, strict=True)) <= 1
    assert seg.gaps(manifests) == []
    with av.open(str(out)) as c:  # audio kept for the onset detector
        assert c.streams.audio


@pytest.mark.skipif(not CLIP, reason="set ELEVATE_H13G to the real h13g_singles.mp4")
def test_a_dropped_piece_is_reported_as_a_gap_and_seq0_start_is_inferred(tmp_path):
    paths, manifests = _cut(CLIP, tmp_path, rebase=True)
    kept = [manifests[0], manifests[2]]
    assert seg.gaps([kept[0], {**kept[1], "seq": 1}]) == [(1, 5_000_000)]
    m0, inferred = seg.segment0_manifest(paths[0], manifests[1])
    assert not inferred and abs(m0["pts_end_us"] - manifests[0]["pts_end_us"]) <= 20_000
    shifted = {**manifests[1], "pts_start_us": manifests[1]["pts_start_us"] + 600_000_000}
    m0, inferred = seg.segment0_manifest(paths[0], shifted)
    assert inferred and m0["pts_end_us"] == shifted["pts_start_us"]


def test_verify_rejects_a_changed_piece(tmp_path):
    p = tmp_path / "1.mp4"
    p.write_bytes(b"real bytes stand-in for a downloaded object")
    good = {
        "seq": 1,
        "bytes": p.stat().st_size,
        "sha256": hashlib.sha256(p.read_bytes()).hexdigest(),
    }
    seg.verify(p, good)
    with pytest.raises(seg.SegmentVerificationError):
        seg.verify(p, {**good, "sha256": "0" * 64})


class Pages:
    """Answers the internal segments route like supabase/functions/api/routes/internal.ts."""

    def __init__(self, completed_by_poll: list[list[int]], final_seq: int | None):
        self.polls, self.final_seq, self.calls = completed_by_poll, final_seq, []

    def segments(self, analysis_id, after_seq):
        self.calls.append(after_seq)
        done = self.polls[min(len(self.calls) - 1, len(self.polls) - 1)]
        page = [s for s in done if s > after_seq]
        return {"segments": [{"seq": s, "pts_start_us": s * 6_000_000,
                              "pts_end_us": (s + 1) * 6_000_000, "starts_on_sync_sample": True,
                              "bytes": 1, "sha256": "a" * 64, "final": s == self.final_seq,
                              "r2_key": f"seg/s_x/{s}.mp4"} for s in page],
                "final_seq": self.final_seq}  # fmt: skip


def test_collect_polls_until_the_final_piece_and_stops():
    api = Pages([[], [1], [1, 2]], final_seq=2)
    got = seg.collect(api, "a_1", "seg/s_x/0.mp4", sleep=lambda s: None, clock=lambda: 0.0)
    assert [r.seq for r in got.segments] == [0, 1, 2] and got.final_seq == 2
    assert api.calls == [0, 0, 1]


def test_collect_single_piece_and_timeout():
    one = Pages([[]], final_seq=0)
    assert [
        r.seq for r in seg.collect(one, "a_1", "seg/s_x/0.mp4", sleep=lambda s: None).segments
    ] == [0]
    t = iter(range(0, 10_000, 10))
    with pytest.raises(seg.SegmentsTimeout, match="segments_timeout"):
        seg.collect(Pages([[]], final_seq=None), "a_1", "seg/s_x/0.mp4", timeout_s=30,
                    sleep=lambda s: None, clock=lambda: float(next(t)))  # fmt: skip


def test_submit_accepts_segment_keys():
    spec = {"sport": "badminton"}
    assert parse({"analysis_id": "a_1", "r2_key": "seg/s_1/0.mp4", "session_spec": spec}).r2_key
    assert parse({"analysis_id": "a_1", "r2_key": "seg/s_1/0.mp4", "session_spec": spec,
                  "session_id": "s_1"}).session_id == "s_1"  # fmt: skip
    for bad in ("seg/s_1/x.mp4", "seg/s_1/0.mov", "segs/s_1/0.mp4", "seg/s_1/3.mp4"):
        with pytest.raises(ValueError):
            parse({"analysis_id": "a_1", "r2_key": bad, "session_spec": spec})


@pytest.mark.skipif(not CLIP, reason="set ELEVATE_H13G to the real h13g_singles.mp4")
def test_audio_overlapping_the_next_piece_does_not_break_the_join(tmp_path):
    paths, manifests = _cut(CLIP, tmp_path, rebase=True, audio_overrun_s=0.06)
    out = seg.join(paths, manifests, tmp_path / "joined.mp4")
    joined = _video_times_ms(str(out))
    original = _video_times_ms(CLIP, CUTS_S[0], CUTS_S[-1])
    assert joined == original
    with av.open(str(out)) as c:
        a = c.streams.audio[0]
        dts = [p.dts for p in c.demux(a) if p.size and p.dts is not None]
    assert dts == sorted(dts) and len(set(dts)) == len(dts)


def test_idle_timeout_resets_while_pieces_keep_arriving():
    # One new piece per poll, 300 s apart: 12 pieces over 3300 s, far past a 600 s idle limit.
    now = [0.0]
    polls = [list(range(1, n + 1)) for n in range(0, 12)]
    got = seg.collect(Pages(polls, final_seq=11), "a_1", "seg/s_x/0.mp4", timeout_s=600,
                      total_s=7200, poll_interval_s=300,
                      sleep=lambda s: now.__setitem__(0, now[0] + s),
                      clock=lambda: now[0])  # fmt: skip
    assert got.final_seq == 11 and len(got.segments) == 12 and now[0] >= 3000
    stuck = [0.0]
    with pytest.raises(seg.SegmentsTimeout):  # no new piece for longer than the idle limit
        seg.collect(Pages([[1]], final_seq=5), "a_1", "seg/s_x/0.mp4", timeout_s=600,
                    poll_interval_s=300, sleep=lambda s: stuck.__setitem__(0, stuck[0] + s),
                    clock=lambda: stuck[0])  # fmt: skip
