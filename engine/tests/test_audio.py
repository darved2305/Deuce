from fractions import Fraction

import av
import numpy as np

from elevate_engine.audio import detect_onsets, onsets, relative_time_ms


def test_detects_a_synthetic_click_train_at_its_source_times():
    sample_rate = 16_000
    click_times_ms = [180, 510, 920, 1_370]
    signal = np.zeros(sample_rate * 2, dtype=np.float32)
    burst = np.hanning(65).astype(np.float32)
    for time_ms in click_times_ms:
        start = round(time_ms * sample_rate / 1000)
        signal[start : start + len(burst)] += burst

    result = detect_onsets(signal, sample_rate)

    assert len(result) == len(click_times_ms)
    assert [row[0] for row in result] == sorted(row[0] for row in result)
    assert all(
        abs(found[0] - expected) <= 20
        for found, expected in zip(result, click_times_ms, strict=True)
    )
    assert all(0 <= strength <= 1 for _, strength in result)
    assert max(strength for _, strength in result) == 1.0


def test_audio_pts_are_aligned_to_the_first_presented_video_frame():
    # The audio stream starts 100 ms before the first video frame. The hit at PTS 9600
    # therefore lands at 100 ms on the contract time base, not at 200 ms from file PTS zero.
    assert relative_time_ms(9_600, 1 / 48_000, 100, 1 / 1_000) == 100
    assert relative_time_ms(4_800, 1 / 48_000, 100, 1 / 1_000) == 0


def test_pyav_decode_uses_video_origin_and_audio_pts_offset(tmp_path):
    sample_rate = 8_000
    audio = np.zeros(sample_rate, dtype=np.int16)
    for time_ms in (350, 650):
        start = round(time_ms * sample_rate / 1000)
        audio[start : start + 65] += (np.hanning(65) * 28_000).astype(np.int16)

    path = tmp_path / "offset.mkv"
    with av.open(str(path), "w", format="matroska") as output:
        video_stream = output.add_stream("ffv1", rate=25)
        video_stream.width = 32
        video_stream.height = 24
        video_stream.pix_fmt = "yuv420p"
        audio_stream = output.add_stream("pcm_s16le", rate=sample_rate)
        audio_stream.layout = "mono"

        packets = []
        for index in range(20):
            frame = av.VideoFrame.from_ndarray(np.zeros((24, 32, 3), dtype=np.uint8), "rgb24")
            frame.pts = index + 5  # First video frame is at 200 ms; audio starts at zero.
            frame.time_base = Fraction(1, 25)
            packets.extend(video_stream.encode(frame))
        for start in range(0, sample_rate, 400):
            frame = av.AudioFrame.from_ndarray(
                audio[start : start + 400].reshape(1, -1), format="s16", layout="mono"
            )
            frame.sample_rate = sample_rate
            frame.pts = start
            frame.time_base = Fraction(1, sample_rate)
            packets.extend(audio_stream.encode(frame))
        packets.extend(video_stream.encode())
        packets.extend(audio_stream.encode())
        for packet in sorted(packets, key=lambda value: value.pts * float(value.time_base)):
            output.mux(packet)

    result = onsets(path, 0, 600, sample_rate=sample_rate)

    assert len(result) == 2
    assert abs(result[0][0] - 150) <= 20
    assert abs(result[1][0] - 450) <= 20
