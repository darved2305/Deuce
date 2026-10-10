"""Model manifest (contracts §6) and sha256-verified weight loading.

Weights live in the Modal Volume `elevate-models`. Nothing is read from a weight file until its
sha256 and size match the manifest.
"""

from __future__ import annotations

import hashlib
import io
import json
import zipfile
from pathlib import Path

MANIFEST_PATH = Path(__file__).with_name("models.json")
DESCRIPTOR_FIELDS = (
    "id",
    "name",
    "source_url",
    "file",
    "sha256",
    "bytes",
    "code_license",
    "weights_license",
    "trained_on",
    "used_for",
)


class WeightsError(RuntimeError):
    pass


def load_manifest(path: Path = MANIFEST_PATH) -> dict:
    return json.loads(path.read_text())


def descriptors(manifest: dict) -> list[dict]:
    """The §6 model descriptors, as `match.v1` and `/v1/capabilities` expect them."""
    return [entry["descriptor"] for entry in manifest.values()]


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def verify(path: Path, descriptor: dict) -> None:
    """Raise WeightsError unless the file exists and matches the descriptor's size and sha256."""
    if not path.is_file():
        raise WeightsError(f"{descriptor['id']}: {path} is missing; run fetch_weights")
    size = path.stat().st_size
    if size != descriptor["bytes"]:
        raise WeightsError(f"{descriptor['id']}: {size} bytes, expected {descriptor['bytes']}")
    actual = sha256_file(path)
    if actual != descriptor["sha256"]:
        raise WeightsError(f"{descriptor['id']}: sha256 {actual}, expected {descriptor['sha256']}")


def _torch_load(data: bytes) -> tuple[object, bool]:
    """Load a checkpoint; returns (object, weights_only). Call only on verified bytes."""
    import pickle

    import torch

    try:
        return torch.load(io.BytesIO(data), map_location="cpu", weights_only=True), True
    except pickle.UnpicklingError:
        # Older checkpoints pickle non-tensor objects. Safe here: the sha256 is already pinned.
        return torch.load(io.BytesIO(data), map_location="cpu", weights_only=False), False


def load_checkpoints(root: Path, model_id: str, manifest: dict) -> dict[str, tuple[object, bool]]:
    """Verify one model's file under `root`, then load every checkpoint it holds."""
    if model_id not in manifest:
        raise WeightsError(f"{model_id} is not in the manifest")
    entry = manifest[model_id]
    descriptor = entry["descriptor"]
    path = root / descriptor["file"]
    verify(path, descriptor)
    members = entry.get("members")
    if not members:
        return {model_id: _torch_load(path.read_bytes())}
    with zipfile.ZipFile(path) as archive:
        return {name: _torch_load(archive.read(member)) for name, member in members.items()}


def count_parameters(obj: object) -> int:
    """Total elements over every tensor nested in a checkpoint."""
    import torch

    if isinstance(obj, torch.Tensor):
        return obj.numel()
    if isinstance(obj, dict):
        return sum(count_parameters(v) for v in obj.values())
    if isinstance(obj, (list, tuple)):
        return sum(count_parameters(v) for v in obj)
    return 0
