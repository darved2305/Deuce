"""Modal app for the vision engine.

Clip audit (CPU):
    modal volume put elevate-clips <local clip> <name>
    modal run -m elevate_engine.modal_app::main --clip <name>
Weights (once, CPU), then verify + load on a T4:
    modal run -m elevate_engine.modal_app::fetch_weights
    modal run -m elevate_engine.modal_app::check_models
Submit endpoint (contracts §3; needs Modal Secret `elevate-submit` with MODAL_SUBMIT_TOKEN):
    modal serve elevate_engine/modal_app.py    (temporary URL)
    modal deploy -m elevate_engine.modal_app   (from main only)
"""

from __future__ import annotations

import json
from pathlib import Path

import modal
from fastapi import Request

ENGINE_DIR = Path(__file__).resolve().parent.parent
CLIPS_PATH = "/clips"
MODELS_PATH = "/models"
UV_VERSION = "0.11.15"


def _source(image: modal.Image) -> modal.Image:
    # Ship the package's .py files and the models.json manifest.
    return image.add_local_python_source(
        "elevate_engine", ignore=lambda p: p.suffix not in {".py", ".json"}
    )


base = modal.Image.debian_slim(python_version="3.12")
image = _source(base.uv_sync(str(ENGINE_DIR), extra_options="--no-dev", uv_version=UV_VERSION))
gpu_image = _source(
    base.apt_install("git")
    .run_commands(
        # Official D-FINE code (Apache-2.0) at a pinned commit; see elevate_engine/dfine/run.py.
        "git clone https://github.com/Peterande/D-FINE.git /opt/D-FINE",
        "git -C /opt/D-FINE checkout 956d1709314c",
    )
    .uv_sync(str(ENGINE_DIR), groups=["gpu"], extra_options="--no-dev", uv_version=UV_VERSION)
)
clips = modal.Volume.from_name("elevate-clips", create_if_missing=True)
models = modal.Volume.from_name("elevate-models", create_if_missing=True)
# analysis_id -> {"state": "claimed"|"spawned", "call_id"?, "at"}: one job per analysis.
submits = modal.Dict.from_name("elevate-submits", create_if_missing=True)
submit_secret = modal.Secret.from_name("elevate-submit")  # MODAL_SUBMIT_TOKEN
app = modal.App("elevate-engine", image=image)


@app.function(region="ap", timeout=600)
def analyze(analysis_id: str, r2_key: str, session_spec: dict) -> dict:
    """Stub until Phase 2: records that the job ran. CPU only, so no GPU is billed yet."""
    from elevate_engine.log import log

    log("analyze.start", analysis_id=analysis_id, r2_key=r2_key, sport=session_spec.get("sport"))
    log("analyze.done", analysis_id=analysis_id, stub=True)
    return {"analysis_id": analysis_id, "stub": True}


@app.function(region="ap", secrets=[submit_secret], timeout=60)
@modal.fastapi_endpoint(method="POST")
async def submit(request: Request):
    """contracts §3: Person 3 dispatches one analysis. The URL is public: token checked first."""
    import asyncio
    import os
    import time

    from fastapi.responses import JSONResponse

    from elevate_engine.submit import handle

    raw = await request.body()

    def spawn(r) -> str:
        return analyze.spawn(r.analysis_id, r.r2_key, r.session_spec).object_id

    # Modal's sync Dict/spawn calls run in a worker thread, off the event loop.
    status, payload = await asyncio.to_thread(
        handle,
        raw,
        request.headers.get("authorization"),
        os.environ.get("MODAL_SUBMIT_TOKEN", ""),
        submits,
        spawn,
        time.time(),
    )
    return JSONResponse(payload, status_code=status)


# CPU only: the audit reads packet timestamps and never touches a GPU.
@app.function(region="ap", volumes={CLIPS_PATH: clips}, timeout=600)
def audit_clip(name: str) -> dict:
    import time

    import av

    from elevate_engine.audit import probe
    from elevate_engine.log import log

    path = Path(CLIPS_PATH) / name
    versions = {"pyav": av.__version__, "ffmpeg": av.ffmpeg_version_info}
    log("audit.start", clip=name, exists=path.exists(), **versions)
    if not path.exists():
        log("audit.failed", clip=name, error="clip not found in volume elevate-clips")
        raise FileNotFoundError(f"{name} not found in volume elevate-clips")
    log("audit.clip", clip=name, bytes=path.stat().st_size)
    started = time.perf_counter()
    try:
        report = probe(path)
    except Exception as exc:
        log("audit.failed", clip=name, error=f"{type(exc).__name__}: {exc}")
        raise
    report.update(clip=name, **versions)
    elapsed_ms = round((time.perf_counter() - started) * 1000)
    log("audit.done", clip=name, elapsed_ms=elapsed_ms, report=report)
    return report


@app.function(region="ap", volumes={MODELS_PATH: models}, timeout=1200)
def fetch_weights(force: bool = False) -> list[dict]:
    """Download each manifest file from its official source into the volume.

    A download is kept only if its size and sha256 match the manifest; otherwise it is deleted
    and the call fails, so a bad file (e.g. an HTML error page) never lands in the volume.
    """
    import shutil
    import urllib.request
    import zipfile

    from elevate_engine.log import log
    from elevate_engine.weights import WeightsError, load_manifest, sha256_file, verify

    results = []
    for model_id, entry in load_manifest().items():
        d = entry["descriptor"]
        path = Path(MODELS_PATH) / d["file"]
        try:
            if force:
                raise WeightsError("forced re-download")
            verify(path, d)
        except WeightsError as reason:
            log("weights.download", model=model_id, url=entry["download_url"], reason=str(reason))
            tmp = path.with_suffix(path.suffix + ".part")
            with (
                urllib.request.urlopen(entry["download_url"], timeout=300) as r,
                tmp.open("wb") as f,
            ):
                shutil.copyfileobj(r, f, 1 << 20)
            try:
                verify(tmp, d)
            except WeightsError as exc:
                got = {"bytes": tmp.stat().st_size, "sha256": sha256_file(tmp)}
                tmp.unlink()
                log("weights.failed", model=model_id, error=str(exc), **got)
                raise
            tmp.rename(path)
        result = {"model": model_id, "bytes": d["bytes"], "sha256": d["sha256"], "verified": True}
        if entry.get("members"):
            with zipfile.ZipFile(path) as archive:
                result["zip_members"] = {i.filename: i.file_size for i in archive.infolist()}
        log("weights.fetched", **result)
        results.append(result)
    models.commit()
    return results


@app.cls(
    gpu="T4",
    region="ap",
    image=gpu_image,
    volumes={MODELS_PATH: models, CLIPS_PATH: clips},
    timeout=1800,
    cpu=4,
)
class Models:
    @modal.enter()
    def load(self) -> None:
        import time

        import torch

        from elevate_engine.log import log
        from elevate_engine.weights import count_parameters, load_checkpoints, load_manifest

        started = time.perf_counter()
        self.manifest = load_manifest()
        self.device = torch.device("cuda")
        log(
            "models.start",
            torch=torch.__version__,
            cuda=torch.version.cuda,
            gpu=torch.cuda.get_device_name(0),
            capability=torch.cuda.get_device_capability(0),
            arch_list=torch.cuda.get_arch_list(),
        )
        # Fails here, before any input is served, if a file is missing or its sha256 differs.
        self.checkpoints = {}
        self.summary = {}
        for model_id in self.manifest:
            for name, (ckpt, weights_only) in load_checkpoints(
                Path(MODELS_PATH), model_id, self.manifest
            ).items():
                self.checkpoints[name] = ckpt
                keys = list(ckpt) if isinstance(ckpt, dict) else []
                self.summary[name] = {
                    "model": model_id,
                    "verified_sha256": self.manifest[model_id]["descriptor"]["sha256"],
                    "weights_only": weights_only,
                    "top_level_keys": keys[:10],
                    "elements_per_key": {k: count_parameters(ckpt[k]) for k in keys[:10]},
                }
        # A real CUDA op proves this torch build runs on this GPU.
        probe = (torch.ones(1024, device=self.device) * 2).sum().item()
        self.load_ms = round((time.perf_counter() - started) * 1000)
        self.calls = 0
        log("models.loaded", load_ms=self.load_ms, cuda_probe=probe, checkpoints=self.summary)

    @modal.method()
    def describe(self) -> dict:
        from elevate_engine.log import log
        from elevate_engine.weights import descriptors

        self.calls += 1
        log("models.describe", call=self.calls, load_ms=self.load_ms)
        return {
            "call_on_this_container": self.calls,
            "load_ms": self.load_ms,
            "checkpoints": self.summary,
            "models": descriptors(self.manifest),
        }

    @modal.method()
    def track(self, clip: str, start_ms: int, end_ms: int) -> dict:
        """TrackNet port on [start_ms, end_ms) of a clip in elevate-clips: every candidate as
        window_result.v1 ball rows at source resolution."""
        import time

        from elevate_engine.log import log
        from elevate_engine.tracknet import infer
        from elevate_engine.tracknet import run as tracknet_run
        from elevate_engine.tracknet.model import from_checkpoint
        from elevate_engine.video import frames_in_range, source_size

        if not hasattr(self, "tracknet"):
            model, self.seq_len = from_checkpoint(self.checkpoints["tracknet"])
            self.tracknet = model.cuda()
        path = Path(CLIPS_PATH) / clip
        started = time.perf_counter()
        decoded = list(frames_in_range(path, start_ms, end_ms, infer.resize_chw_uint8))
        t_ms = [f.t_ms for f in decoded]
        decode_s = time.perf_counter() - started
        blobs = list(tracknet_run.track(self.tracknet, self.seq_len, [f.image for f in decoded]))
        src_w, src_h = source_size(path)
        rows = tracknet_run.ball_rows(t_ms, blobs, src_w, src_h)
        result = {
            "clip": clip,
            "range_ms": [start_ms, end_ms],
            "frames": len(t_ms),
            "frames_with_candidate": sum(1 for b in blobs if b),
            "candidates": len(rows),
            "source_size": [src_w, src_h],
            "decode_s": round(decode_s, 1),
            "total_s": round(time.perf_counter() - started, 1),
        }
        log("track.done", **result)
        return {**result, "t_ms": t_ms, "ball": rows}

    @modal.method()
    def detect_players(self, clip: str, start_ms: int, end_ms: int, fps: float = 12.0) -> dict:
        """D-FINE-S person boxes on [start_ms, end_ms) of a clip, sampled at about `fps`.

        Rows are [t_ms, x1, y1, x2, y2, score] in source pixels. They are detections, not
        window_result.v1 player rows yet: track ids and foot positions in metres need player
        association and court calibration (Phase 2).
        """
        import time

        from elevate_engine.dfine import run as dfine
        from elevate_engine.log import log
        from elevate_engine.video import frames_in_range

        if not hasattr(self, "dfine"):
            state = self.checkpoints["dfine_s_coco"]["model"]
            model, post = dfine.build(state)
            self.dfine = (model.cuda(), post.cuda())
        keep = dfine.sampler(fps)  # raises on fps <= 0 before any decoding
        started = time.perf_counter()
        # Sample while decoding: only the ~fps frames are held, not every full-size frame.
        picked = [
            f for f in frames_in_range(Path(CLIPS_PATH) / clip, start_ms, end_ms) if keep(f.t_ms)
        ]
        boxes = dfine.detect(*self.dfine, [f.image for f in picked])
        rows = [
            [f.t_ms, *b] for f, frame_boxes in zip(picked, boxes, strict=True) for b in frame_boxes
        ]
        per_frame = [len(b) for b in boxes]
        result = {
            "clip": clip,
            "range_ms": [start_ms, end_ms],
            "frames_sampled": len(picked),
            "fps": fps,
            "persons": len(rows),
            "persons_per_frame": {str(n): per_frame.count(n) for n in sorted(set(per_frame))},
            "total_s": round(time.perf_counter() - started, 1),
            "raw_top_first_frame": dfine.raw_top(*self.dfine, picked[0].image) if picked else [],
        }
        log("detect.done", **result)
        return {**result, "rows": rows}

    @modal.method()
    def window(
        self, clip: str, analysis_id: str, window_seq: int, emit_ms: list, overlap_ms: int = 750
    ) -> dict:
        """One window_result.v1 for [emit_ms) of a clip: TrackNet candidates and measured
        quality (decoded with overlap_ms of context after the emitted range), plus the raw
        D-FINE person detections in the emitted range as a separate, non-contract output."""
        import time

        from elevate_engine import window as win
        from elevate_engine.dfine import run as dfine
        from elevate_engine.tracknet import infer
        from elevate_engine.tracknet import run as tracknet_run
        from elevate_engine.tracknet.model import from_checkpoint
        from elevate_engine.video import frames_in_range, source_size

        if not hasattr(self, "tracknet"):
            model, self.seq_len = from_checkpoint(self.checkpoints["tracknet"])
            self.tracknet = model.cuda()
        if not hasattr(self, "dfine"):
            model, post = dfine.build(self.checkpoints["dfine_s_coco"]["model"])
            self.dfine = (model.cuda(), post.cuda())
        started = time.perf_counter()
        path = Path(CLIPS_PATH) / clip
        lo, hi = int(emit_ms[0]), int(emit_ms[1])
        decode = (lo, hi + overlap_ms)
        decoded = list(frames_in_range(path, *decode, infer.resize_chw_uint8))
        t_ms = [f.t_ms for f in decoded]
        frames = [f.image for f in decoded]
        blobs = list(tracknet_run.track(self.tracknet, self.seq_len, frames))
        src_w, src_h = source_size(path)
        rows = tracknet_run.ball_rows(t_ms, blobs, src_w, src_h)
        emitted = [i for i, t in enumerate(t_ms) if lo <= t < hi]
        quality = win.quality(
            [frames[i] for i in emitted],
            sum(1 for i in emitted if blobs[i]),
            src_w / infer.WIDTH,
        )
        window = win.build(
            analysis_id, window_seq, decode, (lo, hi), rows, quality, ["tracknet_v3"]
        )
        keep = dfine.sampler(12.0)
        sampled = [f for f in frames_in_range(path, lo, hi) if keep(f.t_ms)]
        boxes = dfine.detect(*self.dfine, [f.image for f in sampled])
        persons = [[f.t_ms, *b] for f, fb in zip(sampled, boxes, strict=True) for b in fb]
        summary = {
            "frames_decoded": len(t_ms),
            "frames_emitted": len(emitted),
            "ball_rows": len(window["ball"]),
            "persons": len(persons),
            "quality": quality,
            "total_s": round(time.perf_counter() - started, 1),
        }
        log_event = {"window_seq": window_seq, "emit_ms": [lo, hi], **summary}
        from elevate_engine.log import log

        log("window.built", **log_event)
        return {"window": window, "persons": persons, "summary": summary}


@app.local_entrypoint()
def main(clip: str) -> None:
    print(json.dumps(audit_clip.remote(clip), indent=2))


@app.local_entrypoint()
def check_models() -> None:
    m = Models()
    first = m.describe.remote()
    second = m.describe.remote()
    print(json.dumps({"first": first, "second": second}, indent=2))


@app.local_entrypoint()
def track_clip(clip: str, start_s: float, end_s: float, out: str = "") -> None:
    result = Models().track.remote(clip, int(start_s * 1000), int(end_s * 1000))
    summary = {k: v for k, v in result.items() if k not in {"t_ms", "ball"}}
    print(json.dumps(summary, indent=2))
    if out:
        Path(out).write_text(json.dumps(result))


@app.local_entrypoint()
def detect_clip(clip: str, start_s: float, end_s: float, fps: float = 12.0, out: str = "") -> None:
    result = Models().detect_players.remote(clip, int(start_s * 1000), int(end_s * 1000), fps)
    print(json.dumps({k: v for k, v in result.items() if k != "rows"}, indent=2))
    if out:
        Path(out).write_text(json.dumps(result))


@app.local_entrypoint()
def window_clip(
    clip: str, window_seq: int, start_s: float, end_s: float, out_dir: str,
    analysis_id: str = "a_offline", overlap_ms: int = 750,
) -> None:  # fmt: skip
    """Build one window on Modal, validate it against the contract locally, save it."""
    import hashlib
    import subprocess

    from elevate_engine import window as win
    from elevate_engine.weights import load_manifest

    def git(*args: str) -> str:
        return subprocess.run(["git", *args], capture_output=True, text=True, cwd=ENGINE_DIR).stdout

    # Provenance must point at code that exists: refuse to run from uncommitted engine code.
    dirty = [ln for ln in git("status", "--porcelain", "--", ".").splitlines()
             if out_dir not in ln and "eval/runs/" not in ln]  # fmt: skip
    if dirty:
        raise SystemExit(f"commit the engine code first; uncommitted: {dirty}")
    commit = git("rev-parse", "HEAD").strip()
    emit = [int(start_s * 1000), int(end_s * 1000)]
    result = Models().window.remote(clip, analysis_id, window_seq, emit, overlap_ms)
    win.validate(result["window"])  # packages/contracts window_result.v1
    out = Path(out_dir)
    out.mkdir(parents=True, exist_ok=True)
    (out / "window_result.json").write_text(json.dumps(result["window"]) + "\n")
    (out / "persons.json").write_text(json.dumps({
        "note": "Raw D-FINE-S person detections [t_ms, x1, y1, x2, y2, score] in source pixels, "
                "about 12 fps. Not window_result.v1 player rows (no track id or foot metres yet).",
        "rows": result["persons"],
    }) + "\n")  # fmt: skip

    manifest = {
        "what": "Engine output for one real window, saved for Person 4's evaluation.",
        "analysis_id_note": "Offline run: no API analysis behind this id.",
        "clip": clip,
        "window_seq": window_seq,
        "emit_ms": emit,
        "decode_ms": [emit[0], emit[1] + overlap_ms],
        "engine_version": "0.1.0",
        "engine_commit": commit,
        "models": [m["descriptor"] for m in load_manifest().values()],
        "summary": result["summary"],
        "quality_definitions": win.__doc__,
        "sha256": {
            name: hashlib.sha256((out / name).read_bytes()).hexdigest()
            for name in ("window_result.json", "persons.json")
        },
    }
    (out / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    print(json.dumps({"saved": str(out), **result["summary"]}, indent=2))
