# LEDGER.md

Append-only history of everything that landed on `main`: one row per merged PR, plus contract
version bumps and decisions. Written by the `ledger` CI job after each merge (do not hand-edit
rows; if a row is wrong, add a correcting row).

Contract version in force: **contracts v1** (D-012)

| Merged (UTC) | PR | Person | Lane | Slice | Decisions | Contracts | Proof |
|---|---|---|---|---|---|---|---|
| 2026-10-10 | — | @HetanshWaghela | repo | Initial plan, docs, agent rules, CI | D-001…D-015 | v1 | docs/research/ |
| 2026-10-10T15:37Z | #1 | @VrajVed | p1 | p1/engine-skeleton: engine skeleton with clip timing audit on Modal | none (stack versions as pinned in D-002; PyAV 19.0.1 confirmed to bundle FFmpeg 9.0.2) | v1 | Real Modal run on a real clip: https://modal.com/apps/voidinvariant/main/ap-oB2BQbvarg4S33MdPbv9Va |
| 2026-10-10T15:59Z | #2 | @VrajVed | p1 | p1/model-weights: verified TrackNetV3 and D-FINE-S weights on a T4 | none (weights in a Volume, sha256 at load, once in `@modal.enter()` as in `engine/AGENTS.md`; torch pinned per D-002) | v1 | Real Modal runs on the final code: |
| 2026-10-10T16:05Z | #3 | @nikhilpravinpise | p3 | decision: audited Person-3 spec corrections (D-261010-NP1) | `D-261010-NP1` - nine spec corrections, none of which change `contracts.md`: | v1 | Docs/decision slice - no runtime proof owed. Environment evidence that informed the corrections (run in this session): |
| 2026-10-10T16:17Z | #4 | @HetanshWaghela | p4 | p4/real-clips: pin the first real clips and the TrackNetV3 method check (D-26101 | D-261010-HW1 (adds). Closes audit #34 for the clip; the spend ceiling is Person 1's D-261010-VV1. | v1 | Real footage, downloaded and probed locally on 2026-10-10: |
