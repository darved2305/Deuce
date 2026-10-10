"""Modal app for the vision engine. Phase 1 skeleton: audit a real clip.

Upload a clip, then run:
    modal volume put elevate-clips <local clip> <name>
    modal run -m elevate_engine.modal_app --clip <name>
"""

from __future__ import annotations

import json
from pathlib import Path

import modal

ENGINE_DIR = Path(__file__).resolve().parent.parent
CLIPS_PATH = "/clips"

image = (
    modal.Image.debian_slim(python_version="3.12")
    .uv_sync(uv_project_dir=str(ENGINE_DIR), extra_options="--no-dev", uv_version="0.11.15")
    .add_local_python_source("elevate_engine")
)
clips = modal.Volume.from_name("elevate-clips", create_if_missing=True)
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


@app.local_entrypoint()
def main(clip: str) -> None:
    print(json.dumps(audit_clip.remote(clip), indent=2))
