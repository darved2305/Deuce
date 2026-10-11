"""Calibrate from real corner taps and draw the projected court on the tapped frame (PR proof).

Yellow: the court lines projected from the fitted homography. Red crosses: the four taps.
The frame is decoded from the real video at calibration_input.frame_ms.

    PYTHONPATH=. uv run python eval/calibration_check.py CLIP CALIBRATION_INPUT.json OUT.jpg
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path

import jsonschema
from PIL import Image, ImageDraw

from elevate_engine import calibration as cal
from elevate_engine.api import schemas_dir
from elevate_engine.video import frames_in_range, source_size


def main() -> None:
    p = argparse.ArgumentParser()
    p.add_argument("clip")
    p.add_argument("calibration_input")
    p.add_argument("out")
    a = p.parse_args()
    ci = json.loads(Path(a.calibration_input).read_text())
    width, height = source_size(a.clip)
    c = cal.from_calibration_input(ci, width, height)
    record = c.to_match(cal.calibration_id(ci["points_px"], ci["template"]), method=ci["method"])
    match_schema = json.loads((schemas_dir() / "match.v1.schema.json").read_text())
    jsonschema.validate(record, match_schema["properties"]["calibration"])

    frame = next(frames_in_range(a.clip, ci["frame_ms"], ci["frame_ms"] + 40))
    img = Image.fromarray(frame.image)
    d = ImageDraw.Draw(img)
    for line in cal.court_lines_px(c.hinv, ci["template"]):
        d.line([tuple(line["a_px"]), tuple(line["b_px"])], fill=(255, 230, 0), width=3)
    for x, y in ci["points_px"].values():
        d.line([x - 12, y, x + 12, y], fill=(255, 40, 40), width=4)
        d.line([x, y - 12, x, y + 12], fill=(255, 40, 40), width=4)
    d.rectangle([0, 0, width, 34], fill=(0, 0, 0))
    d.text((8, 10), f"t={frame.t_ms} ms  tier {c.tier}  residual {c.residual_px:.2f} px  "
           f"visible {c.visible_fraction:.2f}  sigma {c.uncertainty.sigma_m:.3f} m",
           fill=(255, 255, 255))  # fmt: skip
    img.save(a.out, quality=88)
    summary = {k: v for k, v in record.items() if k != "H_cov"}
    print(json.dumps({**summary, "H_cov": "81 numbers" if record["H_cov"] else None,
                      "sigma_m": c.uncertainty.sigma_m, "geometry_problem": c.geometry_problem,
                      "frame_t_ms": frame.t_ms, "size": [width, height]}, indent=2))  # fmt: skip


if __name__ == "__main__":
    main()
