# LEDGER.md

Append-only history of everything that landed on `main`: one row per merged PR, plus contract
version bumps and decisions. Written by the `ledger` CI job after each merge (do not hand-edit
rows; if a row is wrong, add a correcting row).

Contract version in force: **contracts v1** (D-012)

| Merged (UTC) | PR | Person | Lane | Slice | Decisions | Contracts | Proof |
|---|---|---|---|---|---|---|---|
| 2026-10-10 | — | @HetanshWaghela | repo | Initial plan, docs, agent rules, CI | D-001…D-015 | v1 | docs/research/ |
| 2026-10-10T15:37Z | #1 | @VrajVed | p1 | p1/engine-skeleton: engine skeleton with clip timing audit on Modal | none (stack versions as pinned in D-002; PyAV 19.0.1 confirmed to bundle FFmpeg 9.0.2) | v1 | Real Modal run on a real clip: https://modal.com/apps/voidinvariant/main/ap-oB2BQbvarg4S33MdPbv9Va |
