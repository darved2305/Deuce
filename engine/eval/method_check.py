"""Method check (D-261010-HW1): reproduce the published TrackNetV3 numbers on `stdata_test`.

Runs the authors' own code, pinned at OFFICIAL_COMMIT with their own library versions, in a
separate Python 3.8 environment (torch 1.10 has no wheels for newer Pythons; the container itself
runs 3.12 for Modal). Weights come from the sha256-verified zip in Volume `elevate-models`.
A separate Modal app, so evaluation never ships with the deployed engine. From engine/:

    modal run eval/method_check.py::fetch_stdata
    modal run eval/method_check.py::prepare
    modal run eval/method_check.py::run_official                    # TrackNetV3 (PR #9)
    modal run eval/method_check.py::run_official --mode tracknet    # tracking module only
    modal run eval/method_check.py::run_port                        # our port vs that run
    modal run eval/method_check.py::run_predict --clip <name>       # published model on a clip
"""

from __future__ import annotations

import json
import os
import subprocess
from pathlib import Path

import modal

OFFICIAL_REPO = "https://github.com/qaz812345/TrackNetV3.git"
OFFICIAL_COMMIT = "6eda442ada1740573f200f836d93edc9a541ee86"
OFFICIAL_DIR = "/opt/TrackNetV3"
PY38 = "/opt/tn38/bin/python"
# requirements.txt at OFFICIAL_COMMIT, plus what it omits: tqdm, matplotlib, pycocotools (imported)
# and setuptools (torch 1.10 imports pkg_resources). Those four pins are ours, for Python 3.8.
OFFICIAL_PINS = [
    "torch==1.10.0",
    "numpy==1.22.4",
    "opencv-python==4.4.0.46",
    "pandas==2.0.0",
    "Pillow==10.0.0",
    "parse==1.19.1",
    "dash==2.5.1",
    "plotly==5.8.2",
    "tqdm==4.66.1",
    "matplotlib==3.7.2",
    "pycocotools==2.0.7",
    "setuptools==69.5.1",
]
STDATA_URL = (
    "https://nycu1-my.sharepoint.com/:u:/g/personal/tik_m365_nycu_edu_tw/"
    "EWisYhAiai9Ju7L-tQp0ykEBZJd9VQkKqsFrjcqqYIDP-g?e=S0AB1Z"
)
STDATA = {  # fixtures/clips.md size; sha256 recorded by Person 1 on first download (PR #7)
    "id": "stdata_test",
    "bytes": 1_731_293_072,
    "sha256": "d4e69cc350b64256b269317da1f7cd0ce6058babb5bde72a4160d7e339b6f0b1",
}
EVAL_PATH = "/eval"
MODELS_PATH = "/models"

image = (
    modal.Image.debian_slim(python_version="3.12")
    .apt_install("git", "curl", "unzip", "libgl1", "libglib2.0-0")
    .pip_install("uv==0.11.15")
    .run_commands(
        f"git clone {OFFICIAL_REPO} {OFFICIAL_DIR}",
        f"git -C {OFFICIAL_DIR} checkout {OFFICIAL_COMMIT}",
        "uv venv --python 3.8 /opt/tn38",
        f"uv pip install --python {PY38} " + " ".join(OFFICIAL_PINS),
    )
    .apt_install("ffmpeg")  # re-encode the overlay video to H.264 so browsers play it
    .add_local_python_source("elevate_engine", ignore=lambda p: p.suffix not in {".py", ".json"})
)
eval_vol = modal.Volume.from_name("elevate-eval", create_if_missing=True)
models = modal.Volume.from_name("elevate-models")
clips = modal.Volume.from_name("elevate-clips")
CLIPS_PATH = "/clips"
app = modal.App("elevate-eval", image=image)
# Our engine's own stack (Python 3.12, torch 2.14) for running the port.
port_image = (
    modal.Image.debian_slim(python_version="3.12")
    .uv_sync(
        str(Path(__file__).resolve().parent.parent),
        groups=["gpu"],
        extra_options="--no-dev",
        uv_version="0.11.15",
    )
    .add_local_python_source("elevate_engine", ignore=lambda p: p.suffix not in {".py", ".json"})
)


def _run(cmd: list[str] | str, cwd: str | None = None) -> str:
    """Run a command, streaming each output line to the Modal logs as it happens.

    Returns the combined output; raises with the tail on failure.
    """
    from elevate_engine.log import log

    log("eval.command", cmd=cmd, cwd=cwd)
    proc = subprocess.Popen(
        cmd,
        cwd=cwd,
        shell=isinstance(cmd, str),
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        bufsize=1,
        env={**os.environ, "PYTHONUNBUFFERED": "1"},
    )
    lines = []
    for line in proc.stdout:  # universal newlines also splits tqdm's \r updates
        line = line.rstrip()
        if line:
            print(line, flush=True)
            lines.append(line)
    code = proc.wait()
    out = "\n".join(lines)
    if code != 0:
        log("eval.command_failed", cmd=cmd, code=code, tail=out[-3000:])
        raise RuntimeError(f"{cmd} exited {code}")
    return out


@app.function(region="ap", volumes={EVAL_PATH: eval_vol}, timeout=3600)
def fetch_stdata() -> dict:
    """Download the Shuttlecock Trajectory Dataset zip into the volume; keep it only if its size
    and sha256 match STDATA."""
    import zipfile

    from elevate_engine.log import log
    from elevate_engine.weights import WeightsError, sha256_file, verify

    zip_path = Path(EVAL_PATH) / "TrackNetV2.zip"
    try:
        verify(zip_path, STDATA)
    except WeightsError as reason:
        log("stdata.download", url=STDATA_URL, reason=str(reason))
        tmp = zip_path.with_suffix(".zip.part")
        _run(f'curl -sL -c /tmp/ck -b /tmp/ck -o /dev/null "{STDATA_URL}"')
        _run(f'curl -sL -c /tmp/ck -b /tmp/ck -o {tmp} "{STDATA_URL}&download=1"')
        try:
            verify(tmp, STDATA)
        except WeightsError as exc:
            got = {"bytes": tmp.stat().st_size, "sha256": sha256_file(tmp)}
            tmp.unlink()
            log("stdata.failed", error=str(exc), **got)
            raise
        tmp.rename(zip_path)
    with zipfile.ZipFile(zip_path) as z:
        names = z.namelist()
    result = {"bytes": STDATA["bytes"], "sha256": STDATA["sha256"], "verified": True}
    result["entries"] = len(names)
    result["dirs_depth2"] = sorted({"/".join(n.split("/")[:2]) for n in names if "/" in n})
    log("stdata.fetched", **result)
    eval_vol.commit()
    return result


def _test_split_complete(zip_path: Path, test_dir: Path) -> bool:
    """True if every file of TrackNetV2/Test/ in the zip is in test_dir with the same size."""
    import zipfile

    prefix = "TrackNetV2/Test/"
    with zipfile.ZipFile(zip_path) as z:
        members = [i for i in z.infolist() if i.filename.startswith(prefix) and not i.is_dir()]
    return bool(members) and all(
        (test_dir / i.filename[len(prefix) :]).is_file()
        and (test_dir / i.filename[len(prefix) :]).stat().st_size == i.file_size
        for i in members
    )


WORK = f"{EVAL_PATH}/work"  # copy of the official repo; its data_dir 'data' -> /eval/data


@app.function(region="ap", volumes={EVAL_PATH: eval_vol}, timeout=7200, cpu=8)
def prepare() -> dict:
    """Lay out the test split as the README says, then run the official preprocess.py on it.

    Only the test split is extracted: train stays empty, so preprocess.py extracts test frames,
    copies the authors' corrected test labels (corrected_test_label/) and computes the medians.
    """
    import shutil

    from elevate_engine.log import log
    from elevate_engine.weights import verify

    zip_path = Path(EVAL_PATH) / "TrackNetV2.zip"
    verify(zip_path, STDATA)
    data = Path(EVAL_PATH) / "data"
    data.mkdir(exist_ok=True)
    if not _test_split_complete(zip_path, data / "test"):
        # Extract on the volume, then rename: an interrupted run never leaves a half test split.
        staging = Path(EVAL_PATH) / "staging"
        shutil.rmtree(staging, ignore_errors=True)
        _run(["unzip", "-q", str(zip_path), "TrackNetV2/Test/*", "-d", str(staging)])
        shutil.rmtree(data / "test", ignore_errors=True)
        (staging / "TrackNetV2" / "Test").rename(data / "test")  # README: rename Test -> test
        shutil.rmtree(staging)
    # Train stays empty (test-only evaluation); preprocess.py also lists val, so it must exist.
    (data / "train").mkdir(exist_ok=True)
    (data / "val").mkdir(exist_ok=True)
    marker = Path(WORK) / ".official_commit"
    if not marker.is_file() or marker.read_text() != OFFICIAL_COMMIT:
        shutil.rmtree(WORK, ignore_errors=True)
        shutil.copytree(OFFICIAL_DIR, WORK)
        (Path(WORK) / "data").symlink_to(data)
        marker.write_text(OFFICIAL_COMMIT)
    out = _run([PY38, "preprocess.py"], cwd=WORK)
    lines = [ln for ln in out.splitlines() if "total frames" in ln or "Done" in ln]
    log("stdata.prepared", summary=lines)
    eval_vol.commit()
    return {"summary": lines}


@app.function(
    gpu="T4", region="ap", volumes={EVAL_PATH: eval_vol, MODELS_PATH: models}, timeout=7200
)
def run_official(mode: str = "full") -> dict:
    """README 'Evaluation'. mode "full": generate_mask_data.py on test, then test.py with
    InpaintNet (TrackNetV3). mode "tracknet": test.py with the tracking module only, saving
    per-frame predictions (--output_pred) so our port can be compared frame by frame."""
    if mode not in {"full", "tracknet"}:
        raise ValueError(f"mode must be full or tracknet, got {mode}")
    import zipfile

    from elevate_engine.log import log
    from elevate_engine.weights import load_manifest, verify

    marker = Path(WORK) / ".official_commit"
    if not marker.is_file() or marker.read_text() != OFFICIAL_COMMIT:
        raise RuntimeError("work copy is not at OFFICIAL_COMMIT; run prepare first")
    entry = load_manifest()["tracknet_v3"]
    zip_path = Path(MODELS_PATH) / entry["descriptor"]["file"]
    verify(zip_path, entry["descriptor"])  # sha256 before any byte is used
    with zipfile.ZipFile(zip_path) as z:
        z.extractall(WORK)  # -> WORK/ckpts/{TrackNet,InpaintNet}_best.pt
    gpu = _run(["nvidia-smi", "--query-gpu=name,driver_version", "--format=csv,noheader"])
    torch_info = _run(
        [PY38, "-c", "import torch;print(torch.__version__, torch.cuda.is_available())"]
    )
    log(
        "official.start",
        mode=mode,
        commit=OFFICIAL_COMMIT,
        gpu=gpu.strip(),
        torch=torch_info.strip(),
    )

    if mode == "full":
        steps = [
            [PY38, "generate_mask_data.py", "--tracknet_file", "ckpts/TrackNet_best.pt",
             "--split_list", "test"],
            [PY38, "test.py", "--inpaintnet_file", "ckpts/InpaintNet_best.pt", "--save_dir",
             "eval"],
        ]  # fmt: skip
    else:
        steps = [
            [PY38, "test.py", "--tracknet_file", "ckpts/TrackNet_best.pt", "--save_dir",
             "eval_tracknet", "--output_pred"],
        ]  # fmt: skip
    outputs = []
    for cmd in steps:
        out = _run(cmd, cwd=WORK)
        tail = [ln for ln in out.splitlines() if ln.strip()][-25:]
        log("official.step", cmd=" ".join(cmd[1:]), tail=tail)
        outputs.append({"cmd": " ".join(cmd[1:]), "tail": tail})
    eval_vol.commit()
    return {"mode": mode, "commit": OFFICIAL_COMMIT, "gpu": gpu.strip(),
            "torch": torch_info.strip(), "steps": outputs}  # fmt: skip


def _read_labels(csv_path: Path) -> list[tuple[int, float, float]]:
    """(frame, x, y) rows sorted by frame; empty cells are 0 (official: fillna(0))."""
    import csv

    def num(v: str) -> float:
        x = float(v) if v.strip() else 0.0
        return 0.0 if x != x else x  # NaN -> 0, like pandas fillna(0)

    with csv_path.open() as f:
        rows = [(int(num(r["Frame"])), num(r["X"]), num(r["Y"])) for r in csv.DictReader(f)]
    return sorted(rows)


@app.function(
    gpu="T4",
    region="ap",
    image=port_image,
    volumes={EVAL_PATH: eval_vol, MODELS_PATH: models},
    timeout=7200,
    cpu=8,
)
def run_port(max_rallies: int = 0) -> dict:
    """Our engine's TrackNet on stdata_test, scored like the official tracking-module run and
    compared frame by frame with its saved predictions (run_official --mode tracknet).
    max_rallies > 0 runs only that many rallies (smoke test)."""
    from concurrent.futures import ThreadPoolExecutor

    import numpy as np
    import torch
    from PIL import Image

    from elevate_engine.log import log
    from elevate_engine.tracknet import infer
    from elevate_engine.tracknet import run as tracknet_run
    from elevate_engine.tracknet.model import from_checkpoint
    from elevate_engine.weights import load_checkpoints, load_manifest

    ckpt, _ = load_checkpoints(Path(MODELS_PATH), "tracknet_v3", load_manifest())["tracknet"]
    model, seq_len = from_checkpoint(ckpt)
    model = model.cuda()
    log("port.start", torch=torch.__version__, gpu=torch.cuda.get_device_name(0), seq_len=seq_len)

    data = Path(EVAL_PATH) / "data"
    drop = json.loads((data / "drop_frame.json").read_text())
    official_file = Path(WORK) / "eval_tracknet" / "test_eval_analysis_weight.json"
    official = json.loads(official_file.read_text())["pred_dict"] if official_file.is_file() else {}

    def load(path: Path) -> np.ndarray:
        return infer.resize_chw(np.asarray(Image.open(path).convert("RGB"))).astype(np.uint8)

    counts = dict.fromkeys(infer.TYPES, 0)
    agree = {"frames": 0, "same_xyv": 0, "same_type": 0, "frames_with_extra_candidates": 0}
    pool = ThreadPoolExecutor(8)
    done = 0
    for match_dir in sorted((data / "test").glob("match*"), key=lambda p: int(p.name[5:])):
        median_file = match_dir / "median.npz"
        for csv_path in sorted((match_dir / "corrected_csv").glob("*_ball.csv")):
            rally_id = csv_path.name.removesuffix("_ball.csv")
            key = f"{match_dir.name[5:]}_{rally_id}"
            rally_dir = match_dir / "frame" / rally_id
            labels = _read_labels(csv_path)
            if len(labels) < seq_len or (max_rallies and done >= max_rallies):
                continue
            done += 1
            med_path = median_file if median_file.is_file() else rally_dir / "median.npz"
            median = infer.resize_chw(np.load(med_path)["median"])
            frames = list(pool.map(load, [rally_dir / f"{f}.png" for f, _, _ in labels]))
            w, h = Image.open(rally_dir / "0.png").size  # official: always 0.png
            ws, hs = w / infer.WIDTH, h / infer.HEIGHT
            n_win = len(frames) - seq_len + 1

            types, preds = [], []
            windows = tracknet_run.windows(model, median, frames, seq_len)
            for i, heat in enumerate(infer.ensemble_stream(windows, n_win)):
                found = infer.blobs(heat)
                agree["frames_with_extra_candidates"] += len(found) > 1
                pred = found[0] if found else None
                gt = infer.gt_center(labels[i][1], labels[i][2], ws, hs)
                types.append(infer.outcome(pred, gt))
                preds.append((int(pred.cx * ws), int(pred.cy * hs), 1) if pred else (0, 0, 0))
            start, end = drop["start"][key], drop["end"][key]
            for t in types[start:end]:
                counts[t] += 1
            o = official.get(key)
            if official and o is None:
                agree["rallies_without_official"] = agree.get("rallies_without_official", 0) + 1
            if o is not None and len(o["Type"]) != len(preds):
                agree["length_mismatch_rallies"] = agree.get("length_mismatch_rallies", 0) + 1
            elif o is not None:
                for i, (x, y, v) in enumerate(preds):
                    agree["frames"] += 1
                    agree["same_xyv"] += (x, y, v) == (o["X"][i], o["Y"][i], o["Visibility"][i])
                    agree["same_type"] += infer.TYPES.index(types[i]) == o["Type"][i]
            log("port.rally", rally=key, frames=len(frames))
    if not official:
        agree = {"error": "no official predictions; run run_official --mode tracknet first"}
    elif agree["frames"] == 0:
        raise RuntimeError(f"official predictions exist but no rally matched: {agree}")
    result = {"rallies": done, "counts": counts, **infer.metrics(counts), "agreement": agree}
    name = f"tracknet_result_{max_rallies}.json" if max_rallies else "tracknet_result.json"
    out = Path(EVAL_PATH) / "port" / name
    out.parent.mkdir(exist_ok=True)
    out.write_text(json.dumps(result, indent=2))
    log("port.done", **result)
    eval_vol.commit()
    return result


@app.function(
    gpu="T4",
    region="ap",
    volumes={EVAL_PATH: eval_vol, MODELS_PATH: models, CLIPS_PATH: clips},
    timeout=3600,
)
def run_predict(clip: str) -> dict:
    """The authors' predict.py (TrackNet + InpaintNet, unchanged) on a clip in `elevate-clips`.

    Writes {clip}_ball.csv and {clip}.mp4 (trajectory drawn on the real frames) to
    /eval/predict/{stem}/. This is the published model as-is, not the engine pipeline: whole
    frame resized, no court crop.
    """
    import csv
    import zipfile

    from elevate_engine.log import log
    from elevate_engine.weights import load_manifest, verify

    marker = Path(WORK) / ".official_commit"
    if not marker.is_file() or marker.read_text() != OFFICIAL_COMMIT:
        raise RuntimeError("work copy is not at OFFICIAL_COMMIT; run prepare first")
    video = Path(CLIPS_PATH) / clip
    if not video.is_file():
        raise FileNotFoundError(f"{clip} not in volume elevate-clips")
    entry = load_manifest()["tracknet_v3"]
    zip_path = Path(MODELS_PATH) / entry["descriptor"]["file"]
    verify(zip_path, entry["descriptor"])
    with zipfile.ZipFile(zip_path) as z:
        z.extractall(WORK)
    out_dir = Path(EVAL_PATH) / "predict" / video.stem
    log("predict.start", clip=clip, bytes=video.stat().st_size, commit=OFFICIAL_COMMIT)
    _run(
        [PY38, "predict.py", "--video_file", str(video), "--tracknet_file",
         "ckpts/TrackNet_best.pt", "--inpaintnet_file", "ckpts/InpaintNet_best.pt",
         "--save_dir", str(out_dir), "--large_video", "--output_video"],
        cwd=WORK,
    )  # fmt: skip
    _run(
        ["ffmpeg", "-y", "-loglevel", "error", "-i", str(out_dir / f"{video.stem}.mp4"),
         "-c:v", "libx264", "-pix_fmt", "yuv420p", "-movflags", "+faststart",
         str(out_dir / f"{video.stem}_h264.mp4")]
    )  # fmt: skip
    with (out_dir / f"{video.stem}_ball.csv").open() as f:
        rows = list(csv.DictReader(f))
    visible = sum(1 for r in rows if r.get("Visibility") == "1")
    result = {"clip": clip, "frames": len(rows), "frames_with_shuttle": visible,
              "out_dir": str(out_dir)}  # fmt: skip
    log("predict.done", **result)
    eval_vol.commit()
    return result


@app.local_entrypoint()
def main() -> None:
    print(json.dumps(fetch_stdata.remote(), indent=2))
