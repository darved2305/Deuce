"""Client for Person 3's internal API (contracts §3): the engine's only way to report.

POST {API_BASE_URL}/internal/analyses/{id}/events and /artifacts with X-Internal-Token (Modal
Secret `elevate-internal`, never logged). Bodies are validated against packages/contracts before
sending. Retries only network errors and 5xx (at most 3 retries, backoff); 4xx are final.
The API de-duplicates events and re-registered artifact keys, so retries are safe.
"""

from __future__ import annotations

import json
import os
import time
from collections.abc import Callable
from dataclasses import dataclass, field
from pathlib import Path

from elevate_engine.log import log

RETRY_DELAYS_S = (0.5, 1.0, 2.0)


class ApiError(RuntimeError):
    def __init__(self, status: int, body: str):
        super().__init__(f"HTTP {status}: {body[:300]}")
        self.status = status


def schemas_dir() -> Path:
    """packages/contracts/schemas in the repo, or ELEVATE_SCHEMAS in the Modal image."""
    env = os.environ.get("ELEVATE_SCHEMAS")
    if env:
        return Path(env)
    return Path(__file__).resolve().parents[2] / "packages" / "contracts" / "schemas"


def validate(name: str, body: dict) -> None:
    import jsonschema

    schema = json.loads((schemas_dir() / f"{name}.schema.json").read_text())
    jsonschema.validate(body, schema)


# 60 s: finalize ingests the vision result and runs insights in one call.
def _post_http(url: str, token: str, body: dict, timeout: float = 60.0) -> tuple[int, str]:
    import urllib.error
    import urllib.request

    req = urllib.request.Request(
        url,
        data=json.dumps(body).encode(),
        headers={"content-type": "application/json", "x-internal-token": token},
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.status, resp.read().decode()
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()


@dataclass
class InternalApi:
    base_url: str
    token: str = field(repr=False)
    post: Callable[[str, str, dict], tuple[int, str]] = _post_http
    sleep: Callable[[float], None] = time.sleep

    @classmethod
    def from_env(cls) -> InternalApi:
        return cls(os.environ["API_BASE_URL"].rstrip("/"), os.environ["INTERNAL_API_TOKEN"])

    def _send(
        self, analysis_id: str, route: str, body: dict, done_marker: str | None = None
    ) -> dict:
        """POST with retries on network errors and 5xx. done_marker: a 409 whose body contains it,
        after an earlier attempt whose outcome is unknown, means that attempt succeeded."""
        url = f"{self.base_url}/internal/analyses/{analysis_id}/{route}"
        uncertain = False
        for attempt in range(len(RETRY_DELAYS_S) + 1):
            try:
                status, text = self.post(url, self.token, body)
            except OSError as exc:  # network error: retry
                status, text = 0, repr(exc)
            if 200 <= status < 300:
                return json.loads(text)
            if status == 409 and uncertain and done_marker and done_marker in text:
                log("api.already_done", route=route, analysis_id=analysis_id, attempt=attempt)
                return {"state": "already_finalized"}
            retryable = status == 0 or status >= 500
            log("api.error", route=route, analysis_id=analysis_id, status=status,
                attempt=attempt, retry=retryable, body=text[:200])  # fmt: skip
            if not retryable or attempt == len(RETRY_DELAYS_S):
                raise ApiError(status, text)
            uncertain = True
            self.sleep(RETRY_DELAYS_S[attempt])
        raise AssertionError("unreachable")

    def event(self, analysis_id: str, body: dict) -> dict:
        validate("internal.event.request.v1", body)
        row = self._send(analysis_id, "events", body)
        log("api.event", analysis_id=analysis_id, type=body["type"], seq=row.get("seq"))
        return row

    def artifact(self, analysis_id: str, kind: str, r2_key: str, size: int, sha256: str) -> str:
        body = {"kind": kind, "r2_key": r2_key, "bytes": size, "sha256": sha256}
        validate("internal.artifact.request.v1", body)
        resp = self._send(analysis_id, "artifacts", body)
        validate("internal.artifact.response.v1", resp)
        log("api.artifact", analysis_id=analysis_id, kind=kind, artifact_id=resp["artifact_id"])
        return resp["artifact_id"]

    def finalize(self, analysis_id: str, body: dict) -> dict:
        """POST the vision result once. Validated first: an invalid body makes the API fail the
        analysis (it appends analysis.failed), so it must never leave the engine."""
        validate("analysis.finalize.request.v1", body)
        resp = self._send(analysis_id, "finalize", body, done_marker="already finalized")
        log("api.finalize", analysis_id=analysis_id, state=resp.get("state"),
            last_seq=resp.get("last_seq"))  # fmt: skip
        return resp
