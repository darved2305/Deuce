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
    import av

    from elevate_engine.audit import probe

    report = probe(Path(CLIPS_PATH) / name)
    report["clip"] = name
    report["pyav"] = av.__version__
    report["ffmpeg"] = av.ffmpeg_version_info
    return report


@app.local_entrypoint()
def main(clip: str) -> None:
    print(json.dumps(audit_clip.remote(clip), indent=2))
