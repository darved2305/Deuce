import json

import pytest

from elevate_engine.submit import (
    STALE_CLAIM_S,
    InFlight,
    SubmitRequest,
    authorized,
    handle,
    parse,
    submit_once,
)

# The contracts §2 example session body (trimmed to the fields submit checks).
SPEC = {"sport": "badminton", "format": "singles", "ruleset": {"id": "bwf_3x21"}, "mode": "match"}
BODY = {"analysis_id": "a_01", "r2_key": "orig/s_01/source.mp4", "session_spec": SPEC}


class FakeDict:
    """Same put/get/pop semantics as modal.Dict for the calls submit_once makes."""

    def __init__(self):
        self.data = {}

    def put(self, key, value, *, skip_if_exists=False):
        if skip_if_exists and key in self.data:
            return False
        self.data[key] = value
        return True

    def get(self, key, default=None):
        return self.data.get(key, default)

    def pop(self, key, default=None):
        return self.data.pop(key, default)


class Spawner:
    def __init__(self, fail=False):
        self.calls, self.fail = [], fail

    def __call__(self, req):
        self.calls.append(req.analysis_id)
        if self.fail:
            raise ConnectionError("modal unavailable")
        return f"fc-{len(self.calls)}"


def test_auth_needs_exact_bearer_token():
    assert authorized("Bearer s3cret", "s3cret")
    for header in (None, "", "s3cret", "Bearer wrong", "bearer s3cret", "Bearer s3cret "):
        assert not authorized(header, "s3cret")


def test_auth_refuses_to_run_without_a_configured_token():
    with pytest.raises(RuntimeError):
        authorized("Bearer ", "")


def test_parse_accepts_contract_body():
    assert parse(BODY) == SubmitRequest("a_01", "orig/s_01/source.mp4", SPEC)


@pytest.mark.parametrize(
    "patch",
    [
        {"analysis_id": "m_01"},
        {"analysis_id": None},
        {"r2_key": "clips/m_1/c_1.mp4"},
        {"r2_key": "orig/s_01/../x/source.mp4"},
        {"session_spec": {}},
        {"session_spec": "badminton"},
    ],
)
def test_parse_rejects_bad_bodies(patch):
    with pytest.raises(ValueError):
        parse({**BODY, **patch})


def test_parse_rejects_non_object():
    with pytest.raises(ValueError):
        parse(["a_01"])


def test_same_analysis_spawns_once():
    store, spawn, req = FakeDict(), Spawner(), parse(BODY)
    assert submit_once(store, req, spawn, now=100.0) == ("fc-1", True)
    assert submit_once(store, req, spawn, now=101.0) == ("fc-1", False)
    assert spawn.calls == ["a_01"]


def test_different_analyses_get_their_own_jobs():
    store, spawn = FakeDict(), Spawner()
    submit_once(store, parse(BODY), spawn, now=100.0)
    other = parse({**BODY, "analysis_id": "a_02"})
    assert submit_once(store, other, spawn, now=100.0) == ("fc-2", True)


def test_duplicate_during_dispatch_is_told_to_retry():
    store, spawn, req = FakeDict(), Spawner(), parse(BODY)
    store.put("a_01", {"state": "claimed", "at": 100.0})
    with pytest.raises(InFlight):
        submit_once(store, req, spawn, now=100.0 + STALE_CLAIM_S - 1)
    assert spawn.calls == []


def test_stale_claim_is_taken_over():
    store, spawn, req = FakeDict(), Spawner(), parse(BODY)
    store.put("a_01", {"state": "claimed", "at": 100.0})
    assert submit_once(store, req, spawn, now=100.0 + STALE_CLAIM_S + 1) == ("fc-1", True)


def test_failed_spawn_releases_the_claim():
    store, req = FakeDict(), parse(BODY)
    with pytest.raises(ConnectionError):
        submit_once(store, req, Spawner(fail=True), now=100.0)
    assert store.get("a_01") is None
    assert submit_once(store, req, Spawner(), now=101.0) == ("fc-1", True)


TOKEN = "s3cret"
OK = f"Bearer {TOKEN}"
RAW = json.dumps(BODY).encode()


@pytest.mark.parametrize("raw", [b"", b"not json", b'["a"]', b"\xff\xfe", RAW])
def test_no_token_is_401_whatever_the_body(raw):
    status, payload = handle(raw, None, TOKEN, FakeDict(), Spawner(), now=100.0)
    assert status == 401
    assert payload["error"]["code"] == "unauthorized"


@pytest.mark.parametrize("raw", [b"", b"not json", b'["a"]', b"\xff\xfe", b'{"analysis_id":"m_1"}'])
def test_bad_body_is_422_in_contract_shape(raw):
    status, payload = handle(raw, OK, TOKEN, FakeDict(), Spawner(), now=100.0)
    assert status == 422
    assert set(payload["error"]) == {"code", "message"}
    assert payload["error"]["code"] == "invalid_body"


def test_missing_token_config_fails_closed():
    status, payload = handle(RAW, "Bearer ", "", FakeDict(), Spawner(), now=100.0)
    assert (status, payload["error"]["code"]) == (500, "misconfigured")


def test_accepted_request_returns_call_id():
    status, payload = handle(RAW, OK, TOKEN, FakeDict(), Spawner(), now=100.0)
    assert (status, payload) == (200, {"analysis_id": "a_01", "modal_call_id": "fc-1"})


def test_spawn_failure_is_retryable_503():
    status, payload = handle(RAW, OK, TOKEN, FakeDict(), Spawner(fail=True), now=100.0)
    assert (status, payload["error"]["code"]) == (503, "engine_unavailable")


class FlakyAfterSpawn(FakeDict):
    """Claims work, but recording the spawned call ID fails."""

    def put(self, key, value, *, skip_if_exists=False):
        if value.get("state") == "spawned":
            raise ConnectionError("dict unavailable")
        return super().put(key, value, skip_if_exists=skip_if_exists)


def test_running_job_is_reported_even_if_recording_fails():
    spawn = Spawner()
    status, payload = handle(RAW, OK, TOKEN, FlakyAfterSpawn(), spawn, now=100.0)
    assert (status, payload["modal_call_id"]) == (200, "fc-1")
    assert spawn.calls == ["a_01"]
