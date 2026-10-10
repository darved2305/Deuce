import io
import json

import pytest

from elevate_engine.r2 import R2, WritesDisabled, window_key


class FakeS3:
    def __init__(self, objects=None):
        self.objects, self.calls = dict(objects or {}), []

    def get_object(self, Bucket, Key):  # noqa: N803 (boto3 names)
        self.calls.append(("get", Key))
        return {"Body": io.BytesIO(self.objects[Key])}

    def put_object(self, Bucket, Key, Body, ContentType):  # noqa: N803
        self.calls.append(("put", Key))
        self.objects[Key] = Body


def test_window_key_follows_contract_section_4():
    assert window_key("a_x1", "0.1.0", 0) == "analysis/a_x1/0.1.0/windows/0.json"
    with pytest.raises(ValueError):
        window_key("m_x1", "0.1.0", 0)


def test_original_is_read_with_one_get(tmp_path):
    s3 = FakeS3({"orig/s_1/source.mp4": b"\x00" * 3_000_000})
    size = R2(s3, "bucket", True).download_original("orig/s_1/source.mp4", tmp_path / "v.mp4")
    assert size == 3_000_000 and s3.calls == [("get", "orig/s_1/source.mp4")]


def test_put_returns_registration_fields():
    s3 = FakeS3()
    data = json.dumps({"schema": "window_result.v1"}).encode()
    out = R2(s3, "bucket", True).put_json("analysis/a_1/0.1.0/windows/0.json", data)
    assert out["bytes"] == len(data) and len(out["sha256"]) == 64
    assert s3.calls == [("put", "analysis/a_1/0.1.0/windows/0.json")]


def test_kill_switch_blocks_writes():
    s3 = FakeS3()
    with pytest.raises(WritesDisabled):
        R2(s3, "bucket", False).put_json("analysis/a_1/0.1.0/windows/0.json", b"{}")
    assert s3.calls == []


def test_engine_cannot_write_outside_window_keys():
    with pytest.raises(ValueError):
        R2(FakeS3(), "bucket", True).put_json("orig/s_1/source.mp4", b"x")
