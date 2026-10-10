# 10 · Build plan: four people, five phases, nobody waits

Everyone builds through LLM agents, so the hard part is integration and correctness. The plan
keeps everyone unblocked: contracts and small **real** fixtures (a real clip, hand-labelled; D-008)
exist from the start, every person builds against them and a stub of whatever sits upstream, and real pieces are joined at the end of
each phase with one integration check.

## 1. Ownership

| | Person 1 | Person 2 | Person 3 | Person 4 |
|---|---|---|---|---|
| Area | Vision engine (Modal job) | Android app | API, data, streaming, intelligence, SDK, MCP | Contracts, rules, fixtures, footage and rights, evaluation, demo |
| Folders | `engine/` | `android/` (incl. Gradle root, wrapper, version catalog) | `supabase/` (Edge Function `api`, migrations, RLS, SQL), `services/insights/`, `sdk/`, `mcp/` | repo root (pnpm/uv workspace, `AGENTS.md`, CI), `packages/contracts/`, `packages/rules/`, `fixtures/`, `web/`, `demo/` |
| Language | Python | Kotlin | TypeScript (Deno) + SQL + Python | TypeScript |
| Brief | `docs/lanes/person-1.md` | `docs/lanes/person-2.md` | `docs/lanes/person-3.md` | `docs/lanes/person-4.md` |

Single owners for every shared interface:

| Interface | Owner | Others |
|---|---|---|
| `docs/plan/contracts.md`, `packages/contracts` | Person 4 | all consume |
| Routes, auth, errors, `/v1/openapi.json` | Person 3 | Person 2 and SDK consume |
| R2 signing, object keys, artifact rows | Person 3 | Person 2 uploads directly; Person 1 reads/writes with scoped Modal secrets |
| Event `seq`, `analysis_events`, Broadcast | Person 3 (only writer) | Person 1 calls `/internal/…/events` |
| Modal submit endpoint and its secret | Person 1 | Person 3 calls it (idempotent dispatch) |
| Vision result → `match.v1` persistence, insights, `analysis.completed` | Person 1 builds the vision result; Person 3 persists, adds insights, completes | Person 2 reads |
| `@elevate/rules` TypeScript + golden vectors | Person 4 | Person 3 uses it; Person 2 ports to Kotlin |
| Clip cutting | Person 1 | Person 3 stores; Person 2 plays |
| Footage rights, consent, attribution | Person 4 | — |
| Demo seed command `seed_demo_match` | Person 3 | Person 4 supplies media manifest + seed JSON |
| Evaluation labels, script, `RESULTS.md` | Person 4 | Person 1 supplies engine outputs + manifest |
| Integration check scripts | Person 4 | each person brings their part |

## 2. Before Phase 1 (decisions and accounts)

Decide: the core demo path is **badminton replay on one rights-cleared clip** plus a separate
**assisted free-play clip** filmed at the venue; default ruleset `bwf_3x21`; the Modal operator
(Person 1), card and a team spend ceiling; retention default (original deleted from R2 after
analysis unless the user keeps it; derived clips kept with consent); the product name or a
working name.

Ready: GitHub repo + Actions; Supabase project in Mumbai (active); R2 bucket + scoped
credentials; Modal account with GPU access; one Mac with JDK 17, Android command-line SDK
(platform/build tools 36, platform-tools) and both phones visible in `adb devices`; each
person's own agent access (Claude Code / Codex) confirmed.

## 3. Phases

### Phase 1 · Foundations
| Person 1 | Person 2 | Person 3 | Person 4 |
|---|---|---|---|
| Modal app with pinned image (Python 3.12, PyAV 19.0.1, FFmpeg 9.0.2, PyTorch CUDA) and baked weights; private `submit` endpoint with bearer auth; CLI runs TrackNetV3 + D-FINE-S on the selected badminton clip; model manifest | Gradle project builds and installs on both phones; Photo Picker + ExoPlayer; **overlay spike**: a Media3 frame effect draws `fixture_trail.json` keyed to presentation time at 1×, 0.25× and while seeking | Edge Function `api` deployed (`/v1/health`, `/v1/openapi.json`); **R2 multipart probe** (create, signed part PUTs, complete, HEAD) with a real 60 MB clip; minimal tables (`matches, sessions, uploads, analysis_runs, analysis_events, artifacts`) + RLS; `StubAnalysis` | Workspace, `AGENTS.md`, CI; `packages/contracts` (zod + JSON Schema); the four start fixtures; `@elevate/rules` `bwf_3x21` + vectors; rights manifest for the demo clip; A55 slow-motion test file audited |

**End-of-phase check:** fixtures validate in CI; the APK shows the fixture trail glued to the
video on both phones; the curl multipart probe passes; Modal `submit` answers.

### Phase 2 · First real result
| Person 1 | Person 2 | Person 3 | Person 4 |
|---|---|---|---|
| Thin path first: read original from R2, one window through TrackNetV3, post `window.completed` via `/internal`; then calibration from taps, players + association, tracking layer, audio onsets, hit and landing candidates | Add-match flow (corner handles with loupe, who-is-me, free-play tap, clip support card); upload with R2 multipart + resume; event loop (Broadcast hint → cursor); overview and rally screens on fixtures, then live | Sessions, multipart parts/complete, idempotent `POST /v1/analyses` → Modal; `/internal` events/artifacts/finalize; cursor; Broadcast `analysis_seq` with RLS; `GET /v1/matches/{id}`; artifact signed URLs | Footage: team drills and corridor set (S23 FE + A55 slow-motion reference + measured grid); held-out labels for the selected clip; eval script against the frozen schema |

**End-of-phase check:** the selected badminton clip goes phone → R2 → Modal → events → app;
the real trail draws on the real video; a missed hint is recovered by the cursor.

### Phase 3 · Full loop
| Person 1 | Person 2 | Person 3 | Person 4 |
|---|---|---|---|
| Finalizer (stitching, rallies, score proposal via vectors-checked rules, metrics, flight times); review estimates (`07` §3); clip cutting + posters; free-play TAPIR | Review screen (MODEL ESTIMATE, confirm/correct); compare screen with two real clips synced at contact; Room cache + offline history; Kotlin rules port passing the vectors | Persist vision result; insights job (shot records, hero insight, similar-moment search, comparison endpoint with real clip choice); confirm endpoint + `point.confirmed`; history endpoints; TS SDK; local MCP (tested against the actual Claude host or shown via the MCP inspector) | Eval results into `RESULTS.md`; reference library (team drills through the real engine, consent recorded); seed JSON for `seed_demo_match` |

**End-of-phase check:** a fresh phone upload completes end to end, and the first trail appears
while later segments are still uploading (chunked real-time analysis, D-261011-HW3); a landing is reviewed and the
confirmed point updates the score; a comparison opens a real clip with provenance; airplane mode
still shows the cached record; the TS SDK and MCP return the same `match_id`. "History across
two matches" needs two distinct sources (two rights-cleared clips or two team sessions).

### Phase 4 · Depth and extras
| Person 1 | Person 2 | Person 3 | Person 4 |
|---|---|---|---|
| Throughput and warm-image tuning; quality gates; capability numbers with Person 4; WASB as challenger once its weight terms are checked | Matches history + search chips, Today, Glance widget, notifications, highlight export (Media3 Transformer), simulation screens, motion polish | Simulation and opponent-scout endpoints; developer keys, embed tokens, share grants, delete cascade; optional one-line LLM captions with fallback; quotas and spend guard | Embed component + academy page on the SDK; capability slide; remaining rulesets (`casual_21`, `ittf_11`, `tennis_bo3_tb`, `bwf_3x15`) |

**End-of-phase check (freeze):** full demo run on the real setup; nothing new after this.

### Phase 5 · Demo-ready
| Person 1 | Person 2 | Person 3 | Person 4 |
|---|---|---|---|
| Fallback clips genuinely analysed and labelled "analysed earlier" | Release APK on both phones; airplane-mode checks; DeX/HDMI path | Budget and concurrency limits; demo records seeded | Rehearsals, cue card, pitch |

## 4. Definition of done

Runs on a clip nobody tuned on; validates against the contract; shows evidence, provenance and
abstain states; offline behaviour holds where promised; the matching `01` acceptance test is
recorded as pass / unmet / unknown / could-not-run.

## 5. Free-tier limits to respect

Supabase Free: 500 MB database, 1 GB storage (videos never go there), 5 GB egress, 500k Edge
Function invocations, 2M Realtime messages, 200 peak connections, Edge Function CPU limit (no
heavy work), projects pause after a week idle. R2: 10 GB-month, 1M Class A and 10M Class B
operations, free egress. Modal Starter: $30 monthly credit, 3 seats, card required for GPU; the
credit is guidance, not a spending cap, so stop GPU submissions before it runs out. GitHub
Actions: the free quota depends on public vs private repo. Agent plans: each person's own.

## 6. Ship in slices: code → review → merge → deployed

Everyone ships small, working slices straight to `main`, all the time. `main` is always
demoable. Nobody holds a big branch.

**A slice** is one behaviour that works end to end inside your lane and validates against the
contracts: a screen running on the real hand-labelled fixture, an endpoint proven against the deployed API, an engine stage producing
schema-valid output, a ruleset passing its vectors. Unfinished user-facing work hides behind a
feature flag instead of waiting in a branch.

**The pipeline, for every slice:**

1. **Branch** `p{n}/{slice-name}` from fresh `main` (e.g. `p2/review-screen`).
2. **Build it with your agent**, then run your lane's checks locally:
   Person 1 `uv run pytest` + schema validation of outputs ·
   Person 2 `./gradlew assembleDebug lint test` ·
   Person 3 `deno test`, `deno check`, migration lint ·
   Person 4 `pnpm test` (contracts, rules vectors, web).
3. **Agent code review** of the diff (Claude Code `/code-review` or `codex review`); fix every
   blocking finding; re-run the checks.
4. **Open a PR** with what the slice does and its **real proof** (D-009): Person 1 a real Modal run
   on a real clip (run ID, outputs, detections against labels); Person 2 a screen recording on the
   S23 FE or A55 with real footage; Person 3 real calls against the deployed API with real IDs;
   Person 4 real footage, real labels, eval on real engine output. Tests passing is not proof; tests
   only guard against regressions. CI runs the guard (proof section present, DECISIONS entry when
   contracts/rules/scope change, no secrets) and the lane's own checks.
5. **Squash-merge when CI is green and the proof is real.** No waiting for a human approval;
   teammates glance when they can. If `main` breaks, the slice's author fixes or reverts it first, before anything else.
6. **Deployed from `main`:** CI deploys the Edge Function `api` and applies migrations, attaches
   the debug APK as a build artifact (install with `adb install -r`), publishes the regenerated
   OpenAPI file and TS SDK. Person 1 deploys the Modal app from `main` with the operator's own
   credentials (never shared through CI).
7. **Pull `main` often** and rebase before each new slice, so integration happens in small pieces
   every slice, not at the end of a phase.

**Contract changes** are their own slice: a PR that touches only `docs/plan/contracts.md` and
`packages/contracts`, acknowledged by all four before merge.

GO: small slice, checks, review, merge, deployed. Repeat.

## 7. Shared agent rules (`AGENTS.md`)

Read `docs/plan/contracts.md` before touching data. Edit only your folders. Ship in slices through the
pipeline above; never let `main` stay broken. Every number shown has a denominator or confidence
and links to frames. Every overlay sits on real footage. Pin exact versions. Mark estimates as
estimates. Never present fixtures or cached results as fresh output.
