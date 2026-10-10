"""Method check (D-261010-HW1): reproduce the published TrackNetV3 numbers on `stdata_test`.

Runs the authors' own code, pinned at OFFICIAL_COMMIT with their own library versions, in a
separate Python 3.8 environment (torch 1.10 has no wheels for newer Pythons; the container itself
runs 3.12 for Modal). Weights come from the sha256-verified zip in Volume `elevate-models`.
A separate Modal app, so evaluation never ships with the deployed engine. From engine/:

    modal run eval/method_check.py::fetch_stdata
    modal run eval/method_check.py::prepare
    modal run eval/method_check.py::run_official
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
    .add_local_python_source("elevate_engine", ignore=lambda p: p.suffix not in {".py", ".json"})
)
eval_vol = modal.Volume.from_name("elevate-eval", create_if_missing=True)
models = modal.Volume.from_name("elevate-models")
app = modal.App("elevate-eval", image=image)


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
def run_official() -> dict:
    """README 'Evaluation': generate_mask_data.py on test, then test.py with InpaintNet."""
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
    log("official.start", commit=OFFICIAL_COMMIT, gpu=gpu.strip(), torch=torch_info.strip())

    steps = [
        [PY38, "generate_mask_data.py", "--tracknet_file", "ckpts/TrackNet_best.pt",
         "--split_list", "test"],
        [PY38, "test.py", "--inpaintnet_file", "ckpts/InpaintNet_best.pt", "--save_dir", "eval"],
    ]  # fmt: skip
    outputs = []
    for cmd in steps:
        out = _run(cmd, cwd=WORK)
        tail = [ln for ln in out.splitlines() if ln.strip()][-25:]
        log("official.step", cmd=" ".join(cmd[1:]), tail=tail)
        outputs.append({"cmd": " ".join(cmd[1:]), "tail": tail})
    eval_vol.commit()
    return {"commit": OFFICIAL_COMMIT, "gpu": gpu.strip(), "torch": torch_info.strip(),
            "steps": outputs}  # fmt: skip


@app.local_entrypoint()
def main() -> None:
    print(json.dumps(fetch_stdata.remote(), indent=2))
