"""`submit` logic (contracts §3): bearer auth, body validation, one job per analysis_id.

Kept free of Modal and FastAPI so it can be tested directly; `modal_app.submit` wires it up with a
`modal.Dict` as the store and `analyze.spawn` as the spawner.
"""

from __future__ import annotations

import hmac
import json
import re
from collections.abc import Callable
from dataclasses import dataclass
from typing import Protocol

from elevate_engine.log import log

ANALYSIS_ID = re.compile(r"a_[A-Za-z0-9_-]{1,64}")
MATCH_ID = re.compile(r"m_[A-Za-z0-9_-]{1,64}")
SESSION_ID = re.compile(r"s_[A-Za-z0-9_-]{1,64}")
# contracts §2/§4: an original, or segment 0 of a segment upload (the engine then polls the rest).
R2_KEY = re.compile(r"(?:orig/s_[A-Za-z0-9_-]{1,64}/source\.mp4|seg/s_[A-Za-z0-9_-]{1,64}/0\.mp4)")
# A claim whose spawn never completed (crash between claim and spawn) may be retaken after this.
STALE_CLAIM_S = 60.0


class Store(Protocol):
    def put(self, key: str, value: dict, *, skip_if_exists: bool = False) -> bool | None: ...
    def get(self, key: str, default=None): ...
    def pop(self, key: str, default=None): ...


class InFlight(Exception):
    """Another request for this analysis_id is spawning its job right now; retry shortly."""


@dataclass(frozen=True)
class SubmitRequest:
    analysis_id: str
    r2_key: str
    session_spec: dict
    match_id: str | None = None  # optional in modal.submit.request.v1 since 1.9.0 (HW15)
    session_id: str | None = None


def authorized(header: str | None, token: str) -> bool:
    if not token:
        raise RuntimeError("MODAL_SUBMIT_TOKEN is empty")
    expected = f"Bearer {token}".encode()
    return header is not None and hmac.compare_digest(header.encode(), expected)


def parse(body: object) -> SubmitRequest:
    """Validate the §3 body; raises ValueError with a message safe to return to the caller."""
    if not isinstance(body, dict):
        raise ValueError("body must be a JSON object")
    analysis_id, r2_key, spec = (
        body.get("analysis_id"),
        body.get("r2_key"),
        body.get("session_spec"),
    )
    if not isinstance(analysis_id, str) or not ANALYSIS_ID.fullmatch(analysis_id):
        raise ValueError("analysis_id must look like a_…")
    if not isinstance(r2_key, str) or not R2_KEY.fullmatch(r2_key):
        raise ValueError("r2_key must be orig/{session_id}/source.mp4 or seg/{session_id}/0.mp4")
    if not isinstance(spec, dict) or not spec.get("sport"):
        raise ValueError("session_spec must be the contracts §2 session body")
    match_id = body.get("match_id")
    if match_id is not None and (not isinstance(match_id, str) or not MATCH_ID.fullmatch(match_id)):
        raise ValueError("match_id must look like m_…")
    sid = body.get("session_id")
    if sid is not None and (not isinstance(sid, str) or not SESSION_ID.fullmatch(sid)):
        raise ValueError("session_id must look like s_…")
    if sid is not None and not r2_key.split("/")[1] == sid:
        raise ValueError("session_id does not match the session in r2_key")
    return SubmitRequest(analysis_id, r2_key, spec, match_id, sid)


def submit_once(
    store: Store, req: SubmitRequest, spawn: Callable[[SubmitRequest], str], now: float
) -> tuple[str, bool]:
    """Spawn the job for req.analysis_id once. Returns (call_id, newly_spawned).

    The fresh claim is atomic. Taking over a stale claim is not (modal.Dict has no
    compare-and-swap), so two dispatchers that both find the same stale claim could both spawn;
    Person 3's claim_dispatch serialises dispatch for an analysis, which closes that gap.
    """
    claim = {"state": "claimed", "at": now}
    if not store.put(req.analysis_id, claim, skip_if_exists=True):
        existing = store.get(req.analysis_id)
        if existing and existing.get("state") == "spawned":
            return existing["call_id"], False
        if existing and now - existing.get("at", now) < STALE_CLAIM_S:
            raise InFlight(req.analysis_id)
        # Stale or vanished claim: take it over. Person 3's claim_dispatch also serialises calls.
        store.put(req.analysis_id, claim)
    try:
        call_id = spawn(req)
    except Exception:
        store.pop(req.analysis_id, None)  # let a retry spawn again
        raise
    record = {"state": "spawned", "call_id": call_id, "at": now}
    for attempt in range(3):
        try:
            store.put(req.analysis_id, record)
            break
        except Exception as exc:
            # The job IS running: still return its call ID so Person 3 stores it and does not
            # mark the analysis failed. A later duplicate would then only see a stale claim.
            log(
                "submit.record_failed",
                analysis_id=req.analysis_id,
                attempt=attempt,
                error=repr(exc),
            )
    return call_id, True


def handle(
    raw_body: bytes,
    authorization: str | None,
    token: str,
    store: Store,
    spawn: Callable[[SubmitRequest], str],
    now: float,
) -> tuple[int, dict]:
    """The whole HTTP exchange: token first, then body, then one job. Returns (status, json)."""

    def error(status: int, code: str, message: str) -> tuple[int, dict]:
        log("submit.rejected", status=status, code=code)
        return status, {"error": {"code": code, "message": message}}

    if not token:
        return error(500, "misconfigured", "submit token is not configured")
    if not authorized(authorization, token):
        return error(401, "unauthorized", "missing or wrong bearer token")
    try:
        body = json.loads(raw_body)
    except ValueError:  # JSONDecodeError and non-UTF-8 bodies
        return error(422, "invalid_body", "body must be JSON")
    try:
        req = parse(body)
    except ValueError as exc:
        return error(422, "invalid_body", str(exc))
    try:
        call_id, new = submit_once(store, req, spawn, now)
    except InFlight:
        return error(503, "dispatch_in_flight", "this analysis is being dispatched; retry")
    except Exception as exc:
        log("submit.failed", analysis_id=req.analysis_id, error=repr(exc))
        return error(503, "engine_unavailable", "could not start the analysis; retry")
    log("submit.accepted", analysis_id=req.analysis_id, modal_call_id=call_id, new=new)
    return 200, {"analysis_id": req.analysis_id, "modal_call_id": call_id}
