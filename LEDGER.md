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
| 2026-10-10T16:26Z | #5 | @VrajVed | p1 | p1/submit-endpoint: submit with bearer auth and one job per analysis | none (route, body, auth and error shape as in contracts §1 and §3; dispatch behaviour as in D-261010-NP1 / `docs/lanes | v1 | Real Modal run (`modal serve`), https://modal.com/apps/voidinvariant/main/ap-ZEVaxGavy1TszgFubXoI1e, URL `https://voidin |
| 2026-10-10T16:39Z | #6 | @VrajVed | p1 | p1/modal-spend-ceiling: decision D-261010-VV1 (Modal spend ceiling $30) | D-261010-VV1 (adds, status `proposed`); Open item 2 now points at it. | v1 | Decision-only PR, no engine change. What it governs and why: |
| 2026-10-10T18:10Z | #7 | @nikhilpravinpise | p3 | db: phase 1 core tables, RLS, tested append_event | D-261010-NP1 (implements the audited spec corrections: #19 append_event, #20 lease columns, #22 RLS). | v1 | **This session (2026-10-10, rebased onto main d33b6f1), local stack:** |
| 2026-10-10T18:15Z | #9 | @VrajVed | p1 | p1/tracknet-method-check: reproduce published TrackNetV3 numbers on stdata_test | none new; implements D-261010-HW1 decision 2 (official `test.py` first). For @HetanshWaghela: please record the `stdata_ | v1 | Separate Modal app `elevate-eval` (never deployed with the engine), Volume `elevate-eval`: |
| 2026-10-10T18:22Z | #10 | @HetanshWaghela | p4 | p4/contracts-package: @elevate/contracts v1 (zod source, JSON Schemas, spec-sync | D-261010-HW2 (adds). Implements D-012 (contracts v1) and the engine event restriction from D-261010-NP1. | 1.0.0 | Real data validated (Person 1's merged `engine/elevate_engine/models.json`): |
| 2026-10-10T18:26Z | #11 | @nikhilpravinpise | p3 | api: edge function skeleton, health, openapi, auth middleware | D-261010-NP1 (item 1: mount fix, no `basePath`). | 1.0.0 | Deployed to the real project from this branch's head `5505bfc` with `supabase functions deploy api --use-api --project-r |
| 2026-10-10T18:30Z | #12 | @HetanshWaghela | p4 | p4/contracts-align-db: contracts 1.1.0 match the database, tested (D-261010-HW3) | D-261010-HW3 (adds). | 1.1.0 | Audit of real code on `main` (`supabase/migrations/20261010160619_phase1_core.sql`, PR #7) against `@elevate/contracts`  |
| 2026-10-10T18:45Z | #13 | @nikhilpravinpise | p3 | contracts: vendor packages/contracts into the Edge Function with a sync script | D-261010-HW2 (Person 3 vendors `src/` into Deno). | 1.1.0 | No runtime behaviour changes in this slice, and nothing is deployed. Evidence it works: |
