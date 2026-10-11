"""Collect and losslessly join the recorded pieces of one analysis.

The submit request carries segment 0's key, while the internal segments endpoint returns only
segments with seq > 0. Segment 0 is therefore represented by its key alone; the API does not
provide its expected SHA-256 or declared time span to the engine.
"""

from __future__ import annotations

import hashlib
import re
import time
from collections.abc import Callable, Mapping, Sequence
from dataclasses import dataclass
from fractions import Fraction
from pathlib import Path

import av

SEGMENT_KEY = re.compile(r"seg/(s_[A-Za-z0-9_-]{1,64})/([0-9]+)\.mp4")
DEFAULT_TIMEOUT_S = 10 * 60  # idle: no new completed piece for this long
DEFAULT_TOTAL_S = 60 * 60  # hard cap; analyze's Modal timeout leaves room for the pipeline after
DEFAULT_POLL_INTERVAL_S = 3.0


class SegmentError(RuntimeError):
    """Invalid, missing, or unverifiable segment input."""


class SegmentsTimeout(SegmentError):
    """The analysis did not receive its complete final segment prefix in time."""


class SegmentVerificationError(SegmentError):
    """A downloaded segment does not match its registered manifest."""


@dataclass(frozen=True)
class SegmentRef:
    seq: int
    r2_key: str
    manifest: dict | None


@dataclass(frozen=True)
class SegmentCollection:
    segments: tuple[SegmentRef, ...]
    final_seq: int


def collect(
    api,
    analysis_id: str,
    first_key: str,
    *,
    timeout_s: float = DEFAULT_TIMEOUT_S,
    total_s: float = DEFAULT_TOTAL_S,
    poll_interval_s: float = DEFAULT_POLL_INTERVAL_S,
    sleep: Callable[[float], None] = time.sleep,
    clock: Callable[[], float] = time.monotonic,
) -> SegmentCollection:
    """Poll after seq 0 until the API exposes every completed segment through final_seq.
    timeout_s is an idle timeout (reset whenever a page brings a new piece, so a long live
    recording keeps going); total_s caps the whole collection."""
    match = SEGMENT_KEY.fullmatch(first_key)
    if not match or int(match.group(2)) != 0:
        raise SegmentError("first_key must be the dispatch key for segment 0")
    if timeout_s < 0 or poll_interval_s <= 0:
        raise ValueError("timeout_s must be non-negative and poll_interval_s must be positive")

    session_id = match.group(1)
    refs = [SegmentRef(0, first_key, None)]
    after_seq = 0
    final_seq: int | None = None
    started = clock()
    deadline = started + timeout_s

    first_poll = True
    while True:
        if not first_poll and clock() >= deadline:
            raise SegmentsTimeout(
                f"segments_timeout waiting for seq {after_seq + 1}; final_seq={final_seq}"
            )
        first_poll = False
        page = api.segments(analysis_id, after_seq)
        if not isinstance(page, dict) or not isinstance(page.get("segments"), list):
            raise SegmentError("internal segments page has no segments array")
        page_final = page.get("final_seq")
        if page_final is not None and (
            not isinstance(page_final, int) or isinstance(page_final, bool) or page_final < 0
        ):
            raise SegmentError("internal segments page has an invalid final_seq")
        if final_seq is not None and page_final is not None and page_final != final_seq:
            raise SegmentError("internal segments page changed final_seq")
        if page_final is not None:
            if page_final < after_seq:
                raise SegmentError("final_seq is behind the collected segment prefix")
            final_seq = page_final

        expected = after_seq + 1
        for manifest in page["segments"]:
            if not isinstance(manifest, dict):
                raise SegmentError("internal segments page contains a non-object manifest")
            seq = manifest.get("seq")
            key = manifest.get("r2_key")
            key_match = SEGMENT_KEY.fullmatch(key) if isinstance(key, str) else None
            if seq != expected:
                raise SegmentError(f"segments are out of order or missing seq {expected}")
            if not key_match or key_match.group(1) != session_id or int(key_match.group(2)) != seq:
                raise SegmentError(f"segment {seq} has an invalid or mismatched R2 key")
            if final_seq is not None and seq > final_seq:
                raise SegmentError(f"segment {seq} is beyond final_seq {final_seq}")
            if manifest.get("final") and final_seq != seq:
                raise SegmentError(f"segment {seq} is final but final_seq is {final_seq}")
            if final_seq is not None and seq == final_seq and not manifest.get("final"):
                raise SegmentError(f"final_seq {seq} is not marked final in its manifest")
            refs.append(SegmentRef(seq, key, manifest))
            expected += 1
        if expected > after_seq + 1:
            after_seq = expected - 1
            deadline = min(clock() + timeout_s, started + total_s)  # progress resets idle

        if final_seq == 0:
            return SegmentCollection(tuple(refs), final_seq)
        if final_seq is not None and after_seq == final_seq:
            return SegmentCollection(tuple(refs), final_seq)
        if final_seq is not None and after_seq > final_seq:
            raise SegmentError("collected segment prefix is beyond final_seq")

        remaining = deadline - clock()
        if remaining <= 0:
            waiting_for = after_seq + 1
            raise SegmentsTimeout(
                f"segments_timeout waiting for seq {waiting_for}; final_seq={final_seq}"
            )
        sleep(min(poll_interval_s, remaining))


def sha256_file(path: str | Path) -> str:
    digest = hashlib.sha256()
    with Path(path).open("rb") as source:
        for chunk in iter(lambda: source.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def verify(path: str | Path, manifest: Mapping) -> None:
    """Check downloaded bytes and SHA-256 against a server-returned segment manifest."""
    file_path = Path(path)
    expected_hash = manifest.get("sha256")
    if not isinstance(expected_hash, str) or not re.fullmatch(r"[A-Fa-f0-9]{64}", expected_hash):
        raise SegmentVerificationError("segment manifest has no valid sha256")
    actual_bytes = file_path.stat().st_size
    expected_bytes = manifest.get("bytes")
    if expected_bytes is not None and actual_bytes != expected_bytes:
        raise SegmentVerificationError(
            f"segment {manifest.get('seq')} byte count mismatch: "
            f"expected {expected_bytes}, got {actual_bytes}"
        )
    actual_hash = sha256_file(file_path)
    if actual_hash.lower() != expected_hash.lower():
        raise SegmentVerificationError(
            f"segment {manifest.get('seq')} sha256 mismatch: "
            f"expected {expected_hash}, got {actual_hash}"
        )


def measured_span_us(path: str | Path) -> int:
    """Measure a file's video span from its real packet PTS, including the last frame step."""
    with av.open(str(path)) as container:
        if not container.streams.video:
            raise SegmentError(f"segment has no video stream: {path}")
        stream = container.streams.video[0]
        pts = sorted(
            packet.pts
            for packet in container.demux(stream)
            if packet.size and packet.pts is not None
        )
        if len(pts) < 2:
            raise SegmentError(f"segment needs at least two timestamped video packets: {path}")
        steps = sorted(b - a for a, b in zip(pts, pts[1:], strict=False) if b > a)
        if not steps:
            raise SegmentError(f"segment video packets have no positive PTS steps: {path}")
        middle = len(steps) // 2
        median_step = (
            Fraction(steps[middle])
            if len(steps) % 2
            else Fraction(steps[middle - 1] + steps[middle], 2)
        )
        span = (pts[-1] - pts[0] + median_step) * stream.time_base
        return round(span * 1_000_000)


def segment0_manifest(path: str | Path, next_manifest: Mapping | None) -> tuple[dict, bool]:
    """A manifest for seq 0, which the internal page never returns (it lists seq > after_seq and
    after_seq >= 0). Its span is measured from real packet PTS. Its start is 0, unless seq 1's
    declared start is more than 1 s away from that span (a session timeline that does not start
    at 0): then seq 0 is placed to end where seq 1 starts. Returns (manifest, start_inferred)."""
    span = measured_span_us(path)
    start, inferred = 0, False
    if next_manifest is not None and isinstance(next_manifest.get("pts_start_us"), int):
        # seq 0 ends where seq 1 declares it starts; "inferred" flags a timeline not near 0.
        start = next_manifest["pts_start_us"] - span
        inferred = abs(start) > 1_000_000
    return {"seq": 0, "pts_start_us": start, "pts_end_us": start + span}, inferred


def gaps(manifests: Sequence[Mapping]) -> list[tuple[int, int]]:
    """Report timestamp discontinuities between ordered pieces as (next_seq, delta_us).

    A missing seq remains visible as a timestamp gap here. `collect` rejects a missing seq from
    the API page and `join` refuses to assemble an incomplete sequence.
    """
    if not manifests:
        return []
    seqs = [item.get("seq") for item in manifests]
    if seqs[0] != 0:
        raise SegmentError("segment manifests must start at seq 0")
    if any(not isinstance(seq, int) or isinstance(seq, bool) for seq in seqs):
        raise SegmentError("segment seq values must be integers")
    if any(current <= previous for previous, current in zip(seqs, seqs[1:], strict=False)):
        raise SegmentError("segment manifests are out of order or duplicated")

    found = []
    for previous, current in zip(manifests, manifests[1:], strict=False):
        start = current.get("pts_start_us")
        end = previous.get("pts_end_us")
        if not isinstance(start, int) or not isinstance(end, int):
            raise SegmentError("segment manifests need integer PTS spans to report gaps")
        delta = start - end
        if delta:
            found.append((current["seq"], delta))
    return found


def _first_video_time(path: Path) -> Fraction:
    with av.open(str(path)) as container:
        if not container.streams.video:
            raise SegmentError(f"segment has no video stream: {path}")
        stream = container.streams.video[0]
        try:
            frame = next(container.decode(stream))
        except StopIteration as exc:
            raise SegmentError(f"segment has no decodable video frames: {path}") from exc
        if frame.pts is None:
            raise SegmentError(f"segment's first video frame has no PTS: {path}")
        return Fraction(frame.pts) * Fraction(frame.time_base or stream.time_base)


def _round_ticks(seconds: Fraction, time_base: Fraction) -> int:
    return round(seconds / time_base)


def join(paths: Sequence[str | Path], manifests: Sequence[Mapping], out: str | Path) -> Path:
    """Remux pieces to MP4, moving each first video frame onto its declared session PTS."""
    if not paths or len(paths) != len(manifests):
        raise SegmentError("join needs one manifest for each segment path")
    expected_seqs = list(range(len(paths)))
    seqs = [manifest.get("seq") for manifest in manifests]
    if seqs != expected_seqs:
        raise SegmentError(f"join requires contiguous segment seqs 0..N; got {seqs}")
    if any(not isinstance(manifest.get("pts_start_us"), int) for manifest in manifests):
        raise SegmentError("join needs an integer pts_start_us for every segment")

    output_path = Path(out)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.unlink(missing_ok=True)
    baseline_us = manifests[0]["pts_start_us"]
    templates: dict[tuple[str, int], object] = {}
    last_dts: dict[int, int | None] = {}
    try:
        with av.open(str(output_path), mode="w", format="mp4") as target:
            pieces = enumerate(zip(paths, manifests, strict=True))
            for segment_index, (raw_path, manifest) in pieces:
                source_path = Path(raw_path)
                first_time = _first_video_time(source_path)
                target_time = Fraction(manifest["pts_start_us"] - baseline_us, 1_000_000)
                shift = target_time - first_time

                with av.open(str(source_path)) as source:
                    selected = {}
                    counts: dict[str, int] = {"video": 0, "audio": 0}
                    for stream in source.streams:
                        if stream.type not in counts:
                            continue
                        ordinal = counts[stream.type]
                        counts[stream.type] += 1
                        key = (stream.type, ordinal)
                        if segment_index == 0:
                            templates[key] = target.add_stream_from_template(stream)
                        elif key not in templates:
                            raise SegmentError(
                                f"segment {segment_index} has an unexpected {stream.type} stream"
                            )
                        selected[stream.index] = (stream, templates[key])
                    if not any(stream.type == "video" for stream, _ in selected.values()):
                        raise SegmentError(f"segment {segment_index} has no video stream")

                    for packet in source.demux():
                        if packet.size <= 0 or packet.stream.index not in selected:
                            continue
                        input_stream, output_stream = selected[packet.stream.index]
                        packet_time_base = Fraction(packet.time_base or input_stream.time_base)
                        packet.time_base = packet_time_base
                        shift_ticks = _round_ticks(shift, packet_time_base)
                        if packet.pts is not None:
                            packet.pts += shift_ticks
                        if packet.dts is not None:
                            packet.dts += shift_ticks
                        packet.stream = output_stream
                        stamp = packet.dts if packet.dts is not None else packet.pts
                        prev = last_dts.get(output_stream.index)
                        if stamp is not None and prev is not None and stamp <= prev:
                            if input_stream.type == "audio":
                                continue  # audio overlapping the previous piece: keep the first
                            raise SegmentError(
                                f"segment {segment_index}: video time goes backwards at the join"
                            )
                        if stamp is not None:
                            last_dts[output_stream.index] = stamp
                        target.mux(packet)
        return output_path
    except Exception:
        output_path.unlink(missing_ok=True)
        raise
