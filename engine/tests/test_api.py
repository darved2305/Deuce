import json

import jsonschema
import pytest

from elevate_engine.api import ApiError, InternalApi


class FakeServer:
    """Records calls; replies from a queue of (status, body)."""

    def __init__(self, *replies):
        self.replies, self.calls = list(replies), []

    def __call__(self, url, token, body):
        self.calls.append((url, token, body))
        reply = self.replies.pop(0)
        if isinstance(reply, Exception):
            raise reply
        return reply


def api(server):
    return InternalApi("https://api.example/functions/v1/api", "s3cret", post=server,
                       sleep=lambda s: None)  # fmt: skip


EVENT_ROW = json.dumps({"seq": 3, "type": "window.completed"})


def test_event_posts_contract_body_to_the_internal_route_with_token():
    server = FakeServer((200, EVENT_ROW))
    body = {"type": "window.completed", "window_seq": 0, "decode_window_us": [0, 6_750_000],
            "emit_range_us": [0, 6_000_000], "artifact_id": "art_1",
            "windows_expected": 81}  # fmt: skip  # contracts 1.8.0: lets the app show n of N
    assert api(server).event("a_1", body)["seq"] == 3
    url, token, sent = server.calls[0]
    assert url.endswith("/internal/analyses/a_1/events") and token == "s3cret" and sent == body


def test_invalid_event_is_refused_before_sending():
    server = FakeServer()
    with pytest.raises(jsonschema.ValidationError):
        api(server).event("a_1", {"type": "window.completed"})  # window_seq required
    assert server.calls == []


def test_retries_network_errors_and_5xx_then_succeeds():
    server = FakeServer(OSError("reset"), (502, "bad gateway"), (200, EVENT_ROW))
    api(server).event("a_1", {"type": "analysis.started", "engine_version": "0.1.0"})
    assert len(server.calls) == 3


def test_4xx_is_final_and_not_retried():
    server = FakeServer((404, '{"error":{"code":"not_found","message":"analysis not found"}}'))
    with pytest.raises(ApiError) as err:
        api(server).event("a_1", {"type": "analysis.started"})
    assert err.value.status == 404 and len(server.calls) == 1


def test_gives_up_after_three_retries():
    server = FakeServer(*[(503, "unavailable")] * 4)
    with pytest.raises(ApiError):
        api(server).event("a_1", {"type": "analysis.started"})
    assert len(server.calls) == 4


def test_artifact_registration_returns_the_api_id():
    server = FakeServer((200, json.dumps({"artifact_id": "art_42"})))
    art = api(server).artifact("a_1", "window_result", "analysis/a_1/0.1.0/windows/0.json",
                               1234, "ab" * 32)  # fmt: skip
    assert art == "art_42"
    assert server.calls[0][2]["kind"] == "window_result"


def test_token_is_not_in_the_repr():
    assert "s3cret" not in repr(api(FakeServer()))
