import hashlib
import re

import pytest

from elevate_engine.weights import (
    DESCRIPTOR_FIELDS,
    WeightsError,
    descriptors,
    load_checkpoints,
    load_manifest,
    verify,
)

MANIFEST = load_manifest()


def test_manifest_matches_contract_descriptor():
    # contracts §6: exact field set, canonical IDs, pinned sha256 and size.
    assert set(MANIFEST) == {"tracknet_v3", "dfine_s_coco"}
    for model_id, entry in MANIFEST.items():
        d = entry["descriptor"]
        assert tuple(d) == DESCRIPTOR_FIELDS
        assert d["id"] == model_id
        assert re.fullmatch(r"[0-9a-f]{64}", d["sha256"])
        assert d["bytes"] > 0
        assert entry["download_url"].startswith("https://")
    assert descriptors(MANIFEST)[1]["sha256"] == (
        "48a6c8cc43eb57186843f752e2e8461ddd3326e0d3c575e71e6e960844683e89"
    )


def _descriptor_for(path, **overrides):
    data = path.read_bytes()
    d = {"id": "x", "bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()}
    return {**d, **overrides}


def test_verify_accepts_matching_file(tmp_path):
    f = tmp_path / "w.pt"
    f.write_bytes(b"checkpoint bytes")
    verify(f, _descriptor_for(f))


def test_verify_rejects_wrong_sha256(tmp_path):
    f = tmp_path / "w.pt"
    f.write_bytes(b"checkpoint bytes")
    with pytest.raises(WeightsError, match="sha256"):
        verify(f, _descriptor_for(f, sha256="0" * 64))


def test_verify_rejects_wrong_size(tmp_path):
    f = tmp_path / "w.pt"
    f.write_bytes(b"checkpoint bytes")
    with pytest.raises(WeightsError, match="bytes"):
        verify(f, _descriptor_for(f, bytes=1))


def test_missing_file_fails_before_loading(tmp_path):
    with pytest.raises(WeightsError, match="missing"):
        load_checkpoints(tmp_path, "dfine_s_coco", MANIFEST)


def test_unknown_model_is_refused(tmp_path):
    with pytest.raises(WeightsError, match="not in the manifest"):
        load_checkpoints(tmp_path, "wasb_hrnet_badminton", MANIFEST)
