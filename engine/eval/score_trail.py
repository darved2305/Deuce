"""Score ball candidates against hand-labelled trail frames (elevate.trail_labels.v1).

For each labelled frame (ball [x, y] or null = looked, not visible), the engine's prediction is
its top candidate at that t_ms (highest confidence). Outcomes use the TrackNet rules at a pixel
tolerance in source pixels: TP / FP1 (too far) / FP2 (predicted, none labelled) / FN / TN.
"any_candidate_hits" counts labelled shuttles that ANY candidate reached: what a tracking layer
could recover by choosing better among candidates.

    uv run python eval/score_trail.py ../fixtures/fixture_trail.json track_result.json
"""

from __future__ import annotations

import json
import math
import sys
from collections import defaultdict
from pathlib import Path


def score(labels: dict, ball_rows: list[list], tolerance_px: float) -> dict:
    by_t: dict[int, list[list]] = defaultdict(list)
    for row in ball_rows:
        by_t[int(row[0])].append(row)
    counts = dict.fromkeys(("TP", "TN", "FP1", "FP2", "FN"), 0)
    any_hits = 0
    for frame in labels["frames"]:
        cands = by_t.get(int(frame["t_ms"]), [])
        top = max(cands, key=lambda r: r[3] if r[3] is not None else -1) if cands else None
        gt = frame["ball"]
        if gt is None:
            counts["FP2" if top else "TN"] += 1
            continue
        if top is None:
            counts["FN"] += 1
        elif math.dist(top[1:3], gt) <= tolerance_px:
            counts["TP"] += 1
        else:
            counts["FP1"] += 1
        any_hits += any(math.dist(c[1:3], gt) <= tolerance_px for c in cands)
    tp, fp = counts["TP"], counts["FP1"] + counts["FP2"]
    visible = sum(1 for f in labels["frames"] if f["ball"] is not None)
    precision = tp / (tp + fp) if tp + fp else 0.0
    recall = tp / visible if visible else 0.0
    return {
        "tolerance_px": tolerance_px,
        "labelled_frames": len(labels["frames"]),
        "labelled_visible": visible,
        "counts": counts,
        "precision": round(precision, 4),
        "recall": round(recall, 4),
        "f1": round(2 * precision * recall / (precision + recall), 4) if precision + recall else 0,
        "any_candidate_hits": any_hits,
    }


def main(labels_path: str, result_path: str) -> None:
    labels = json.loads(Path(labels_path).read_text())
    result = json.loads(Path(result_path).read_text())
    # 15 px at 1920x1080 is the official 4 px tolerance at the 512x288 model input.
    for tol in (15.0, 30.0):
        print(json.dumps(score(labels, result["ball"], tol)))


if __name__ == "__main__":
    main(*sys.argv[1:3])
