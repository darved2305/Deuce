"""One JSON line per engine event on stdout, so Modal's logs show what every call did.

Never log secrets, tokens or signed URLs.
"""

from __future__ import annotations

import json
import sys
import time


def log(event: str, **fields) -> None:
    record = {"ts": round(time.time(), 3), "event": event, **fields}
    print(json.dumps(record, default=str), file=sys.stdout, flush=True)
