"""Cloudflare R2 for the engine: read the original once, write window results.

Budget rules (docs/ops/cloudflare-budget.md): never list objects (G7); read the original once per
analysis and write at most one window_result per window (G8); write nothing unless
R2_WRITES_ENABLED is "true" (G9). Credentials come from the `elevate-r2` Modal Secret and are
never logged.
"""

from __future__ import annotations

import hashlib
import os
import re
from dataclasses import dataclass
from pathlib import Path

ORIG_KEY = re.compile(r"orig/s_[A-Za-z0-9_-]+/source\.mp4")
WINDOW_KEY = re.compile(r"analysis/a_[A-Za-z0-9_-]+/[^/]+/windows/[0-9]+\.json")


class WritesDisabled(RuntimeError):
    """R2_WRITES_ENABLED is not "true": the kill switch is on."""


def window_key(analysis_id: str, engine_version: str, window_seq: int) -> str:
    """contracts §4: analysis/{analysis_id}/{engine_version}/windows/{window_seq}.json"""
    key = f"analysis/{analysis_id}/{engine_version}/windows/{window_seq}.json"
    if not WINDOW_KEY.fullmatch(key):
        raise ValueError(f"not a contract window key: {key}")
    return key


@dataclass
class R2:
    client: object  # a boto3 S3 client (or a test double with the same two methods)
    bucket: str
    writes_enabled: bool

    @classmethod
    def from_env(cls) -> R2:
        import boto3

        account = os.environ["R2_ACCOUNT_ID"]
        client = boto3.client(
            "s3",
            endpoint_url=f"https://{account}.r2.cloudflarestorage.com",
            aws_access_key_id=os.environ["R2_ACCESS_KEY_ID"],
            aws_secret_access_key=os.environ["R2_SECRET_ACCESS_KEY"],
            region_name="auto",
        )
        enabled = os.environ.get("R2_WRITES_ENABLED", "").strip().lower() == "true"
        return cls(client, os.environ["R2_BUCKET"], enabled)

    def download_original(self, key: str, dest: Path) -> int:
        """One GetObject for the original (G8). Returns its size in bytes."""
        if not ORIG_KEY.fullmatch(key):
            raise ValueError(f"not an original key: {key}")
        body = self.client.get_object(Bucket=self.bucket, Key=key)["Body"]
        with dest.open("wb") as f:
            for chunk in iter(lambda: body.read(1 << 20), b""):
                f.write(chunk)
        return dest.stat().st_size

    def put_json(self, key: str, data: bytes) -> dict:
        """One PutObject. Returns {r2_key, bytes, sha256} for the artifact registration."""
        if not self.writes_enabled:
            raise WritesDisabled("R2_WRITES_ENABLED is not true; not writing " + key)
        if not WINDOW_KEY.fullmatch(key):
            raise ValueError(f"engine may only write contract window keys here: {key}")
        self.client.put_object(
            Bucket=self.bucket, Key=key, Body=data, ContentType="application/json"
        )
        return {"r2_key": key, "bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()}
