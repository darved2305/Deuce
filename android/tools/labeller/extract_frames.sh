#!/usr/bin/env bash
# Extract real frames + their decoder PTS for the labeller. No re-encode of the source, no
# frame-rate conversion: one JPEG per decoded frame, PTS taken from ffmpeg's showinfo and
# cross-checked against an independent ffprobe frame listing.
# Usage: extract_frames.sh <video> <start_s> <end_s> <out_dir> <clip_id> <sha256> [<sha256>...]
set -euo pipefail
video=$1 start=$2 end=$3 out=$4 clip=$5; shift 5
mkdir -p "$out/frames"
ffmpeg -v info -y -copyts -ss "$start" -to "$end" -i "$video" -vf showinfo -fps_mode passthrough \
  -q:v 2 -an "$out/frames/f_%04d.jpg" 2> "$out/showinfo.log"
ffprobe -v error -select_streams v:0 -show_entries stream=time_base,width,height,start_pts:stream_side_data=rotation -of json "$video" > "$out/stream.json"
ffprobe -v error -select_streams v:0 -read_intervals "$(python -c "print(max(0,$start-1))")%$(python -c "print($end+1)")" \
  -show_entries frame=pts -of csv=p=0 "$video" > "$out/ffprobe_pts.csv"
python - "$out" "$clip" "$(basename "$video")" "$start" "$end" "$@" <<'PY'
import json, re, sys
from fractions import Fraction
out, clip, name, start, end, *shas = sys.argv[1:]
st = json.load(open(f"{out}/stream.json"))["streams"][0]
tb = Fraction(st["time_base"])
# Labels are in contract space (contracts §0): time from the first presented frame, pixels after
# rotation. This tool only handles the simple case and refuses the rest instead of mislabelling.
rot = next((d.get("rotation", 0) for d in st.get("side_data_list", [])), 0)
assert rot == 0, f"rotation {rot}: not supported yet (frames would be auto-rotated, coded size swapped)"
assert int(st.get("start_pts", 0)) == 0, f"first PTS {st.get('start_pts')} != 0: subtract it before labelling"
si = [int(m.group(1)) for m in re.finditer(r"n:\s*\d+\s+pts:\s*(\d+)\s+pts_time", open(f"{out}/showinfo.log", errors="ignore").read())]
lo, hi = si[0], si[-1]
fp = sorted(int(l.strip().rstrip(",")) for l in open(f"{out}/ffprobe_pts.csv") if l.strip())
fp = [p for p in fp if lo <= p <= hi]
assert si == fp, f"showinfo/ffprobe PTS disagree ({len(si)} vs {len(fp)})"
frames = []
for i, p in enumerate(si):
    us = p * tb * 1_000_000
    assert us.denominator == 1, f"PTS {p} is not a whole microsecond"
    frames.append({"file": f"frames/f_{i+1:04d}.jpg", "pts": p, "t_us": int(us)})
m = {"clip_id": clip, "file": name, "accepted_file_sha256": shas, "time_base": st["time_base"],
     "width": st["width"], "height": st["height"], "rotation": 0, "frames": frames}
open(f"{out}/manifest.js", "w").write("window.MANIFEST=" + json.dumps(m) + ";")
print(f"{len(frames)} frames, PTS verified; open {out}/labeller.html")
PY
cp "$(dirname "$0")/labeller.html" "$out/"
