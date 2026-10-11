"""The finalize body (contracts §3, D-261011-HW14): the vision part of match.v1, after the last
window.

Only what the engine measured goes in. Rallies, shots, reviews, scores, metrics, moments and clips
stay empty until the engine produces them; players are identity records (see players()). Hits are
match events (`type: "hit"`), each with its own frame as evidence. Capability statuses are
`unknown`: a status comes from the held-out eval, never from one run (01-ps-fundamentals).
"""

from __future__ import annotations

import hashlib
import re
from collections.abc import Sequence

SESSION_FROM_KEY = re.compile(r"^(?:orig|seg)/(s_[A-Za-z0-9_-]+)/")


def session_id(r2_key: str) -> str:
    m = SESSION_FROM_KEY.match(r2_key)
    if not m:
        raise ValueError(f"no session id in r2_key {r2_key!r}")
    return m.group(1)


def players(match_id: str, session_spec: dict) -> list[dict]:
    """match.v1 identity records (HW14 item 4; #65 B4b; #92). Singles with a who_is_me tap: the
    user (`user_confirmed`, from their own tap) and the opponent (`engine`), in that order so the
    API's teams are [[you], [opponent]]. track_ids stay empty until live player tracks link them:
    no track is claimed that the engine has not associated. Otherwise no players."""
    if session_spec.get("format") != "singles" or not session_spec.get("who_is_me"):
        return []
    pid = lambda role: "p_" + hashlib.sha256(f"{match_id}:{role}".encode()).hexdigest()[:16]  # noqa: E731
    return [
        {"player_id": pid("me"), "track_ids": [], "label": "you", "identity": "user_confirmed"},
        {"player_id": pid("opponent"), "track_ids": [], "label": "opponent", "identity": "engine"},
    ]


def match_events(window_events: Sequence[dict]) -> list[dict]:
    """window_result hit events -> match.v1 events. The shuttle-trail track_id is not carried
    over: in match.v1 a hit's track_id means the hitter, which the engine does not know yet."""
    keep = ("event_id", "type", "t_ms", "t_sigma_ms", "image_xy", "cues", "state")
    return [
        {**{k: e[k] for k in keep}, "evidence": [{"t_ms": e["t_ms"]}]}
        for e in sorted(window_events, key=lambda e: (e["t_ms"], e["event_id"]))
    ]


def request(
    *,
    analysis_id: str,
    match_id: str,
    r2_key: str,
    session_spec: dict,
    sid: str | None = None,
    engine_version: str,
    models: Sequence[str],
    artifacts: dict,
    window_events: Sequence[dict],
    calibration: dict | None,
) -> dict:
    """`analysis.finalize.request.v1` body; validate it before sending (an invalid body makes the
    API fail the analysis). session_id comes from the dispatch (1.9.0), else from the r2_key."""
    unknown = {"status": "unknown"}
    vision = {
        "schema": "match.v1",
        "match_id": match_id,
        "session_ids": [sid or session_id(r2_key)],
        "sport": session_spec["sport"],
        "format": session_spec["format"],
        "ruleset": session_spec["ruleset"],
        "players": players(match_id, session_spec),
        "calibration": calibration,
        "analysis": {
            "analysis_id": analysis_id,
            "engine_version": engine_version,
            "state": "finalizing",
            "models": list(models),
            "artifacts": artifacts,
        },
        "capabilities": {
            "ball": {"tier": "core", **unknown},
            "court": {"tier": "core", **unknown},
            "reviews": {"tier": "experimental", **unknown},
            "score": {"tier": "assisted", **unknown},
        },
        "rallies": [],
        "shots": [],
        "events": match_events(window_events),
        "reviews": [],
        "confirmed_points": [],
        "score": {"timeline": [], "games": []},
        "metrics": [],
        "moments": [],
        "clips": [],
        "corrections": [],
    }
    return {"vision_result": vision, "clip_artifact_ids": []}
