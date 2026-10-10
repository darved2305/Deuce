"""Draw engine candidates on real frames: a contact sheet for PR proof.

Red ring: top candidate (highest confidence). Yellow ring: other candidates. Green cross: hand
label (when a trail_labels.v1 file is given). Frames are decoded from the real video.

    uv run python eval/contact_sheet.py CLIP RESULT.json OUT.jpg --times 40120,41000 \
        [--labels ../fixtures/fixture_trail.json]
"""

from __future__ import annotations

import argparse
import json
from collections import defaultdict
from pathlib import Path

from PIL import Image, ImageDraw

from elevate_engine.video import frames_in_range

TILE_W, TILE_H, COLS = 640, 360, 3


def sheet(clip: str, rows: list[list], times: list[int], labels: dict | None) -> Image.Image:
    by_t = defaultdict(list)
    for r in rows:
        by_t[int(r[0])].append(r)
    gt = {int(f["t_ms"]): f["ball"] for f in (labels or {}).get("frames", [])}
    tiles = []
    for t in times:
        frame = next(frames_in_range(clip, t, t + 1))
        img = Image.fromarray(frame.image)
        sx, sy = TILE_W / img.width, TILE_H / img.height
        img = img.resize((TILE_W, TILE_H))
        d = ImageDraw.Draw(img)
        cands = sorted(by_t.get(t, []), key=lambda r: -(r[3] or 0))
        for i, (_, x, y, _conf, _) in enumerate(cands):
            color, r = ((255, 40, 40), 11) if i == 0 else ((255, 220, 0), 8)
            d.ellipse([x * sx - r, y * sy - r, x * sx + r, y * sy + r], outline=color, width=3)
        if gt.get(t):
            x, y = gt[t][0] * sx, gt[t][1] * sy
            d.line([x - 7, y, x + 7, y], fill=(40, 255, 80), width=3)
            d.line([x, y - 7, x, y + 7], fill=(40, 255, 80), width=3)
        label = "label: none" if t in gt and gt[t] is None else ""
        d.rectangle([0, 0, TILE_W, 22], fill=(0, 0, 0))
        d.text((6, 5), f"t={t} ms  candidates={len(cands)}  {label}", fill=(255, 255, 255))
        tiles.append(img)
    rows_n = (len(tiles) + COLS - 1) // COLS
    out = Image.new("RGB", (COLS * TILE_W, rows_n * TILE_H), (20, 20, 20))
    for i, tile in enumerate(tiles):
        out.paste(tile, ((i % COLS) * TILE_W, (i // COLS) * TILE_H))
    return out


def main() -> None:
    p = argparse.ArgumentParser()
    p.add_argument("clip")
    p.add_argument("result")
    p.add_argument("out")
    p.add_argument("--times", required=True, help="comma-separated t_ms values")
    p.add_argument("--labels")
    a = p.parse_args()
    labels = json.loads(Path(a.labels).read_text()) if a.labels else None
    rows = json.loads(Path(a.result).read_text())["ball"]
    times = [int(t) for t in a.times.split(",")]
    sheet(a.clip, rows, times, labels).save(a.out, quality=88)


if __name__ == "__main__":
    main()
