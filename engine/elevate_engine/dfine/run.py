"""D-FINE-S (COCO) person detection, built with the official D-FINE code (Apache-2.0).

The repo is cloned at DFINE_COMMIT into the GPU image; the model is built from its config with
`HGNetv2.pretrained = False` (as the official torch_inf.py does, so no download), loaded from
our sha256-verified `dfine_s_coco.pth`, and run in deploy mode on 640x640 inputs.
"""

from __future__ import annotations

import sys
from collections.abc import Sequence

import numpy as np

DFINE_REPO = "https://github.com/Peterande/D-FINE.git"
DFINE_COMMIT = "956d1709314c"
DFINE_DIR = "/opt/D-FINE"
CONFIG = "configs/dfine/dfine_hgnetv2_s_coco.yml"
# "person" is 0 in D-FINE's contiguous 80-class labels and 1 as a COCO category id. The official
# postprocessor (src/zoo/dfine/postprocessor.py at DFINE_COMMIT) returns before its COCO remap when
# deploy_mode is on, so deployed labels are always contiguous (confirmed on a T4: players = 0).
PERSON_CONTIGUOUS, PERSON_COCO_ID = 0, 1
MIN_SCORE = 0.4  # the official torch_inf.py draw threshold
SIZE = 640


def _stub_calflops() -> None:
    """D-FINE imports calflops (a FLOP profiler that pulls in transformers) at module level, only
    for training-time stats. Inference never calls it, so register a stub that fails loudly if it
    ever is, instead of shipping transformers in the GPU image."""
    import types

    if "calflops" in sys.modules:
        return
    stub = types.ModuleType("calflops")

    def calculate_flops(*_args, **_kwargs):
        raise RuntimeError("calflops is stubbed in the engine; FLOP profiling is not available")

    stub.calculate_flops = calculate_flops
    sys.modules["calflops"] = stub


def build(state: dict, repo_dir: str = DFINE_DIR):
    """(model, postprocessor) in deploy mode, weights from a verified checkpoint's state dict."""
    import os

    if repo_dir not in sys.path:
        sys.path.insert(0, repo_dir)
    _stub_calflops()
    from src.core import YAMLConfig  # the official config system

    cwd = os.getcwd()
    os.chdir(repo_dir)  # config __include__ paths are relative to the repo
    try:
        cfg = YAMLConfig(CONFIG)
        cfg.yaml_cfg["HGNetv2"]["pretrained"] = False
        cfg.model.load_state_dict(state)
        return cfg.model.deploy().eval(), cfg.postprocessor.deploy().eval()
    finally:
        os.chdir(cwd)


def preprocess(rgb: np.ndarray):
    """Official torch_inf.py transform: PIL image -> Resize((640, 640)) -> ToTensor."""
    import torchvision.transforms as T
    from PIL import Image

    return T.Compose([T.Resize((SIZE, SIZE)), T.ToTensor()])(Image.fromarray(rgb))


def person_label(postprocessor) -> int:
    """Mirror the postprocessor: the remap only applies when not in deploy mode."""
    deploy = bool(getattr(postprocessor, "deploy_mode", False))
    remapped = bool(getattr(postprocessor, "remap_mscoco_category", False))
    return PERSON_COCO_ID if remapped and not deploy else PERSON_CONTIGUOUS


def persons(
    labels, boxes, scores, person: int = PERSON_CONTIGUOUS, min_score: float = MIN_SCORE
) -> list[list[float]]:
    """Keep person detections above min_score: [[x1, y1, x2, y2, score], ...], best first."""
    keep = [
        [*map(lambda v: round(float(v), 1), box), round(float(score), 4)]
        for label, box, score in zip(labels, boxes, scores, strict=True)
        if int(label) == person and float(score) >= min_score
    ]
    return sorted(keep, key=lambda r: -r[4])


def sample_times(t_ms: Sequence[int], fps: float) -> list[int]:
    """Indices of frames sampling the stream at about `fps` (first frame at/after each tick)."""
    keep = sampler(fps)
    return [i for i, t in enumerate(t_ms) if keep(t)]


def sampler(fps: float):
    """Stateful filter for frames arriving in time order: True for the first frame at/after each
    1/fps tick. Lets the caller sample while decoding instead of holding every frame."""
    if fps <= 0:
        raise ValueError(f"fps must be positive, got {fps}")
    step = 1000.0 / fps
    state = {"next": None}

    def keep(t: int) -> bool:
        if state["next"] is None:
            state["next"] = float(t)
        if t < state["next"]:
            return False
        state["next"] += step * (1 + (t - state["next"]) // step)
        return True

    return keep


def raw_top(model, postprocessor, frame: np.ndarray, k: int = 8) -> list:
    """Diagnostics: the k highest-scoring raw detections (any class) for one frame."""
    import torch

    x = preprocess(frame).unsqueeze(0).cuda()
    size = torch.tensor([[frame.shape[1], frame.shape[0]]]).cuda()
    with torch.no_grad():
        res = postprocessor(model(x), size)
    if isinstance(res, tuple):
        labels, boxes, scores = (t[0].cpu() for t in res)
    else:
        labels, boxes, scores = (res[0][key].cpu() for key in ("labels", "boxes", "scores"))
    order = scores.argsort(descending=True)[:k]
    return [[int(labels[i]), round(float(scores[i]), 3), [round(float(v)) for v in boxes[i]]]
            for i in order]  # fmt: skip


def detect(model, postprocessor, frames: Sequence[np.ndarray], batch: int = 8):
    """Person boxes per RGB frame, in source pixels."""
    import torch

    person = person_label(postprocessor)
    out = []
    for start in range(0, len(frames), batch):
        chunk = frames[start : start + batch]
        x = torch.stack([preprocess(f) for f in chunk]).cuda()
        sizes = torch.tensor([[f.shape[1], f.shape[0]] for f in chunk]).cuda()
        with torch.no_grad():
            results = postprocessor(model(x), sizes)
        labels, boxes, scores = results if isinstance(results, tuple) else (None, None, None)
        if labels is None:  # list of dicts form
            for r in results:
                out.append(persons(r["labels"].cpu(), r["boxes"].cpu(), r["scores"].cpu(), person))
        else:
            for i in range(len(chunk)):
                out.append(persons(labels[i].cpu(), boxes[i].cpu(), scores[i].cpu(), person))
    return out
