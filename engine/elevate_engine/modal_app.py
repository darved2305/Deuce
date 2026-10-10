"""Modal app for the vision engine.

Clip audit (CPU):
    modal volume put elevate-clips <local clip> <name>
    modal run -m elevate_engine.modal_app::main --clip <name>
Weights (once, CPU), then verify + load on a T4:
    modal run -m elevate_engine.modal_app::fetch_weights
    modal run -m elevate_engine.modal_app::check_models
"""

from __future__ import annotations

import json
from pathlib import Path

import modal

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
    base.uv_sync(str(ENGINE_DIR), groups=["gpu"], extra_options="--no-dev", uv_version=UV_VERSION)
)
clips = modal.Volume.from_name("elevate-clips", create_if_missing=True)
models = modal.Volume.from_name("elevate-models", create_if_missing=True)
app = modal.App("elevate-engine", image=image)


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


@app.cls(gpu="T4", region="ap", image=gpu_image, volumes={MODELS_PATH: models}, timeout=600)
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


@app.local_entrypoint()
def main(clip: str) -> None:
    print(json.dumps(audit_clip.remote(clip), indent=2))


@app.local_entrypoint()
def check_models() -> None:
    m = Models()
    first = m.describe.remote()
    second = m.describe.remote()
    print(json.dumps({"first": first, "second": second}, indent=2))
