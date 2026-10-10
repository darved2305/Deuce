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


SCHEMAS_LOCAL = ENGINE_DIR.parent / "packages" / "contracts" / "schemas"
SCHEMAS_REMOTE = "/contracts/schemas"


def _source(image: modal.Image) -> modal.Image:
    # Ship the package's .py files, the models.json manifest and the contract JSON Schemas.
    return (
        image.env({"ELEVATE_SCHEMAS": SCHEMAS_REMOTE})
        .add_local_dir(str(SCHEMAS_LOCAL), SCHEMAS_REMOTE)
        .add_local_python_source(
            "elevate_engine", ignore=lambda p: p.suffix not in {".py", ".json"}
        )
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


internal_secret = modal.Secret.from_name("elevate-internal")  # INTERNAL_API_TOKEN, API_BASE_URL
r2_secret = modal.Secret.from_name("elevate-r2")  # R2_* incl. R2_WRITES_ENABLED
ENGINE_VERSION = "0.1.0"
WINDOW_MS = 6000


UPLOADS = "uploads"  # per-analysis copy of the original in elevate-clips, deleted after


@app.function(
    region="ap",
    image=gpu_image,
    secrets=[internal_secret, r2_secret],
    volumes={MODELS_PATH: models, CLIPS_PATH: clips},
    timeout=3600,
)
def analyze(analysis_id: str, r2_key: str, session_spec: dict) -> dict:
    """Whole analysis on CPU, with the windows on parallel T4s (Models.candidates).

    analysis.started -> read the original from R2 once (copy into the clips volume so the window
    containers can decode their own ranges) -> one shared background -> ~6 s windows with 0.75 s
    overlap, in parallel -> A3 static filter + tracking layer in time order -> per window, in
    order: validate, PUT analysis/{id}/{version}/windows/{n}.json, register the artifact, post
    window.completed. A window is posted once the next one's candidates are in, so tracks that
    cross the boundary are complete. Any failure posts analysis.failed with a reason.
    """
    import shutil
    import time

    from elevate_engine import pipeline
    from elevate_engine import window as win
    from elevate_engine.api import InternalApi
    from elevate_engine.log import log
    from elevate_engine.r2 import R2, WritesDisabled, window_key
    from elevate_engine.sports import BADMINTON
    from elevate_engine.tracknet.run import sample_background
    from elevate_engine.video import duration_ms

    started = time.perf_counter()
    api = InternalApi.from_env()
    log("analyze.start", analysis_id=analysis_id, r2_key=r2_key, sport=session_spec.get("sport"))
    api.event(analysis_id, {"type": "analysis.started", "engine_version": ENGINE_VERSION})
    rel = f"{UPLOADS}/{analysis_id}/source.mp4"
    local = Path(CLIPS_PATH) / rel
    posted = []
    try:
        r2 = R2.from_env()
        if not r2.writes_enabled:  # kill switch (G9): fail before any GPU work
            raise WritesDisabled("R2_WRITES_ENABLED is not true")
        local.parent.mkdir(parents=True, exist_ok=True)
        size = r2.download_original(r2_key, local)
        clips.commit()  # make the copy visible to the window containers
        end = duration_ms(local)
        plans = pipeline.plan_windows(0, end)
        log("analyze.original", analysis_id=analysis_id, bytes=size, duration_ms=end,
            windows=len(plans))  # fmt: skip
        models_ = Models()
        bg = sample_background(local, 0, end)  # on this CPU container, not a T4
        args = [(rel, list(p.decode), list(p.emit), bg) for p in plans]
        results: list[dict] = []

        def post(k: int) -> None:
            w = pipeline.window_for_posting(analysis_id, plans, results, k, BADMINTON)
            win.validate(w)
            stored = r2.put_json(
                window_key(analysis_id, ENGINE_VERSION, k), (json.dumps(w) + "\n").encode()
            )
            art = api.artifact(analysis_id, "window_result", stored["r2_key"], stored["bytes"],
                               stored["sha256"])  # fmt: skip
            api.event(analysis_id, {
                "type": "window.completed", "window_seq": k,
                "decode_window_us": w["decode_window_us"], "emit_range_us": w["emit_range_us"],
                "artifact_id": art,
            })  # fmt: skip
            posted.append({"window_seq": k, "artifact_id": art, "ball_rows": len(w["ball"])})

        for res in models_.candidates.starmap(args):  # results arrive in window order
            results.append(res)
            if len(results) >= 2:  # window k-1 is final once window k is known
                post(len(results) - 2)
        post(len(results) - 1)
        _, stats = pipeline.track_windows(results, BADMINTON)
    except Exception as exc:
        reason = f"{type(exc).__name__}: {exc}"[:200]
        log("analyze.failed", analysis_id=analysis_id, reason=reason, windows_posted=len(posted))
        try:
            api.event(analysis_id, {"type": "analysis.failed", "reason": reason})
        except Exception as report_exc:  # keep the original error as the one that surfaces
            log("analyze.report_failed", analysis_id=analysis_id, error=repr(report_exc)[:200])
        raise
    finally:
        shutil.rmtree(local.parent, ignore_errors=True)
        clips.commit()
    result = {"analysis_id": analysis_id, "duration_ms": end, "windows": posted,
              "tracker": stats, "total_s": round(time.perf_counter() - started, 1)}  # fmt: skip
    log("analyze.done", **{k: v for k, v in result.items() if k != "windows"},
        windows_posted=len(posted))  # fmt: skip
    return result


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
    max_containers=10,  # caps parallel windows (cost stays predictable under the $30 limit)
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
        """One window_result.v1 for [emit_ms) of a clip in elevate-clips (see window.from_file),
        plus the raw D-FINE person detections in the emitted range as a separate output."""
        import time

        from elevate_engine import window as win
        from elevate_engine.dfine import run as dfine
        from elevate_engine.tracknet.model import from_checkpoint
        from elevate_engine.video import frames_in_range

        if not hasattr(self, "tracknet"):
            model, self.seq_len = from_checkpoint(self.checkpoints["tracknet"])
            self.tracknet = model.cuda()
        if not hasattr(self, "dfine"):
            model, post = dfine.build(self.checkpoints["dfine_s_coco"]["model"])
            self.dfine = (model.cuda(), post.cuda())
        started = time.perf_counter()
        path = Path(CLIPS_PATH) / clip
        lo, hi = int(emit_ms[0]), int(emit_ms[1])
        window, summary = win.from_file(
            self.tracknet, self.seq_len, path, analysis_id, window_seq, (lo, hi), overlap_ms
        )
        keep = dfine.sampler(12.0)
        sampled = [f for f in frames_in_range(path, lo, hi) if keep(f.t_ms)]
        boxes = dfine.detect(*self.dfine, [f.image for f in sampled])
        persons = [[f.t_ms, *b] for f, fb in zip(sampled, boxes, strict=True) for b in fb]
        summary = {**summary, "persons": len(persons),
                   "total_s": round(time.perf_counter() - started, 1)}  # fmt: skip
        log_event = {"window_seq": window_seq, "emit_ms": [lo, hi], **summary}
        from elevate_engine.log import log

        log("window.built", **log_event)
        return {"window": window, "persons": persons, "summary": summary}

    def _tracknet(self):
        from elevate_engine.tracknet.model import from_checkpoint

        if not hasattr(self, "tracknet"):
            model, self.seq_len = from_checkpoint(self.checkpoints["tracknet"])
            self.tracknet = model.cuda()
        return self.tracknet, self.seq_len

    @modal.method()
    def background(self, clip: str, start_ms: int, end_ms: int):
        """Shared background for a whole range (pipeline step 2), float32 (3, 288, 512)."""
        from elevate_engine.tracknet import run as tracknet_run

        clips.reload()  # see originals that analyze copied in after this container started
        return tracknet_run.sample_background(Path(CLIPS_PATH) / clip, start_ms, end_ms)

    @modal.method()
    def candidates(self, clip: str, decode_ms: list, emit_ms: list, background) -> dict:
        """One window of the whole-video pipeline: TrackNet candidates (source px) and measured
        camera motion / blur for the emitted frames, using the shared background."""
        from elevate_engine import window as win
        from elevate_engine.log import log
        from elevate_engine.tracknet import infer
        from elevate_engine.tracknet import run as tracknet_run
        from elevate_engine.video import frames_in_range, source_size

        model, seq_len = self._tracknet()
        clips.reload()
        path = Path(CLIPS_PATH) / clip
        decoded = list(frames_in_range(path, *decode_ms, infer.resize_chw_uint8))
        frames = [f.image for f in decoded]
        blobs = list(tracknet_run.track(model, seq_len, frames, background))
        src_w, src_h = source_size(path)
        sx, sy = src_w / infer.WIDTH, src_h / infer.HEIGHT
        lo, hi = emit_ms
        emitted = [i for i, f in enumerate(decoded) if lo <= f.t_ms < hi]
        q = win.quality([frames[i] for i in emitted], 0, sx)
        out = {
            "emit": [lo, hi],
            "t_ms": [decoded[i].t_ms for i in emitted],
            "cands": [
                [[round(b.cx * sx, 1), round(b.cy * sy, 1), round(b.peak, 4)] for b in blobs[i]]
                for i in emitted
            ],
            "camera_motion_px": q["camera_motion_px"],
            "blur_index": q["blur_index"],
        }
        log("window.candidates", emit=[lo, hi], frames=len(emitted),
            candidates=sum(len(c) for c in out["cands"]))  # fmt: skip
        return out


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


@app.local_entrypoint()
def run_clip(
    clip: str, start_s: float, end_s: float, out_dir: str, analysis_id: str = "a_offline"
) -> None:
    """Whole-video pipeline on [start_s, end_s) of a clip: parallel windows on T4s, one shared
    background, tracking across windows; every window validated and saved in order."""
    import time

    from elevate_engine import pipeline
    from elevate_engine import window as win
    from elevate_engine.sports import BADMINTON

    t0 = time.perf_counter()
    lo, hi = int(start_s * 1000), int(end_s * 1000)
    plans = pipeline.plan_windows(lo, hi)
    models_ = Models()
    bg = models_.background.remote(clip, lo, hi)
    t_bg = time.perf_counter() - t0
    args = [(clip, list(p.decode), list(p.emit), bg) for p in plans]
    per_window = list(models_.candidates.starmap(args))
    t_win = time.perf_counter() - t0 - t_bg
    windows, stats = pipeline.assemble(analysis_id, plans, per_window, BADMINTON)
    out = Path(out_dir) / "windows"
    out.mkdir(parents=True, exist_ok=True)
    for w in windows:
        win.validate(w)
        (out / f"{w['window_seq']}.json").write_text(json.dumps(w) + "\n")
    summary = {
        "clip": clip, "range_ms": [lo, hi], "windows": len(windows),
        "frames": sum(len(r["t_ms"]) for r in per_window),
        "ball_rows": sum(len(w["ball"]) for w in windows), "tracker": stats,
        "background_s": round(t_bg, 1), "windows_s": round(t_win, 1),
        "total_s": round(time.perf_counter() - t0, 1),
        "realtime_factor": round((time.perf_counter() - t0) / ((hi - lo) / 1000), 2),
    }  # fmt: skip
    (Path(out_dir) / "summary.json").write_text(json.dumps(summary, indent=2) + "\n")
    print(json.dumps(summary, indent=2))


@app.local_entrypoint()
def analyze_once(analysis_id: str, r2_key: str, sport: str = "badminton") -> None:
    """Run analyze for one real, already-dispatched analysis (used for the live proof before
    this code is deployed; after deploy, Person 3's dispatch spawns analyze through submit)."""
    result = analyze.remote(analysis_id, r2_key, {"sport": sport})
    print(json.dumps({k: v for k, v in result.items() if k != "windows"}, indent=2))
    print(json.dumps(result["windows"][:3] + result["windows"][-2:]))
