"""Score raw candidates vs the tracking layer against hand labels (elevate.trail_labels.v1).

    cd engine && PYTHONPATH=.:eval uv run python eval/track_eval.py \
        ../fixtures/fixture_trail.json eval/runs/h13g_31_91/candidates.json
TRACK_RESULT is a track_clip output ({"t_ms": [...], "ball": [...]}) from a real Modal run.
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

from score_trail import score

from elevate_engine.sports import BADMINTON
from elevate_engine.tracking import candidates_by_frame, track


def main(labels_path: str, result_path: str) -> None:
    labels = json.loads(Path(labels_path).read_text())
    result = json.loads(Path(result_path).read_text())
    rows, stats = track(result["t_ms"], candidates_by_frame(result["t_ms"], result["ball"]),
                        BADMINTON)  # fmt: skip
    print(json.dumps({"tracker": stats}))
    observed = [r for r in rows if r[4] != "inferred"]  # plan step 4: inferred not in metrics
    for name, ball in (("raw", result["ball"]), ("tracked", observed), ("tracked+inferred", rows)):
        print(json.dumps({name: score(labels, ball, 15.0)}))


if __name__ == "__main__":
    main(*sys.argv[1:3])
