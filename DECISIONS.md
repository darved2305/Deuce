# DECISIONS.md

The single record of **what we decided and what it touches**. Any change to the plan, to a
contract, to ownership, or to a rule starts here, before code.

**Rules**
- A PR that changes `docs/plan/`, `docs/plan/contracts.md`, `packages/contracts/`, `AGENTS.md`,
  `CLAUDE.md`, `.claude/lanes.json` or `.github/CODEOWNERS` **must add or update an entry here in
  the same PR** (CI: `scripts/check-pr.sh`). Prefer a small decision-only PR that merges first.
- One decision per entry. **Append new entries at the end.** Never rewrite an accepted entry's
  decision: add a new entry and change only the old one's status line to `superseded by D-…`.
- **IDs never collide:** seeds are D-001…D-015; new IDs are `D-<YYMMDD>-<initials><n>`
  (e.g. `D-261011-NP1` for nikhilpravinpise's first decision that day).
- **Blast radius is mandatory** and decides who must sign off: if it touches another person's
  folder or a contract, every affected person approves the PR before merge.
- The reasoning behind D-001…D-015 lives in `docs/plan/11-debates.md` and `docs/research/`.

Template (copy to the end of the file):

```markdown
## D-<YYMMDD>-<initials><n> · Short imperative title
- **Status:** proposed | accepted | superseded by D-…
- **Date:** YYYY-MM-DD · **Owner:** @handle (Person n) · **Supersedes:** D-… | none
- **In one line:** In the context of <slice>, facing <concern>, we decided <option> and rejected
  <alternatives>, to achieve <benefit>, accepting <cost>.
- **Context:** 2–4 lines on what forced this now.
- **Decision:** 1–3 imperative lines: what every affected person must now do.
- **Alternatives:** (a) … rejected because …; (b) …
- **Blast radius:**
  - Contracts: none | sections/schemas; version bump none | minor | major
  - People and folders: Person n `folder/` must …
  - Data/infra: migrations, env names (`.env.example`), secrets, Modal/Supabase config
  - Containment: flag | additive-only | adapter; who signed off
- **Exit:** how to undo it, and the point of no return.
- **Proof / evidence:** links to real runs, recordings, measurements.
- **PRs:** #…
```

---

## Open (decide, then record as entries)
1. Product name (shortlist: CourtMemory, RallyMirror, MatchEcho); code uses `elevate` until then.
2. Modal spend ceiling: decided in D-261010-VV1 ($30 total).
3. The demo clip (working and eval clips are decided in D-261010-HW1).
4. Default badminton ruleset (proposed `bwf_3x21`).
5. Retention of results and clips beyond 14 days (originals: deleted after 1 day, D-261011-HW2).
6. Referee call-coverage threshold for the evaluation (set before results are seen).

---

## D-001 · Android first, native Kotlin + Compose + Media3, no Android Studio
- **Status:** accepted · **Date:** 2026-10-10 · **Owner:** @darved2305 (Person 2)
- **Context:** Team phones are Android (Galaxy S23 FE, Galaxy A55); one iPhone. The product's core
  is real footage with overlays glued to frames.
- **Decision:** Kotlin 2.4.20, AGP 9.3.3, Gradle 9.5.0, JDK 17, Compose BOM 2026.09.00, Media3
  1.11.1, Room 2.8.5, Glance 1.2.0, WorkManager 2.11.2, supabase-kt 3.8.0. Built with the
  command-line SDK; GitHub Actions as backup builder.
- **Alternatives:** Expo/React Native (bundled Skia video bug on Android, no per-frame callback,
  unpredictable EAS queue); Flutter (still needs Kotlin for Media3).
- **Blast radius:** `android/` · Person 2.
- **Proof / evidence:** `docs/research/debates/D1-app-stack.md`.

## D-002 · Cloud vision engine on Modal; phones run no ML
- **Status:** accepted · **Date:** 2026-10-10 · **Owner:** @VrajVed (Person 1)
- **Context:** Exynos 2200 / 1480 have no verified on-device path for these models.
- **Decision:** Modal (Python 3.12, Modal SDK 1.6.1, PyAV 19.0.1, FFmpeg 9.0.2) on a T4; one job
  per analysis; ordered ~6 s windows with overlap. Person 1 is the Modal operator with an agreed
  spend ceiling.
- **Blast radius:** `engine/` · Person 1 (operator), Person 3 (caller).
- **Proof / evidence:** `docs/research/reports/18-device-capabilities.md`, `16-cloud-streaming.md`.

## D-003 · One API: Hono on Supabase Edge Function `api`; Supabase + R2
- **Status:** accepted · **Date:** 2026-10-10 · **Owner:** @nikhilpravinpise (Person 3)
- **Decision:** Hono 4.13.13 + `@hono/zod-openapi` 1.6.3 on Edge Function `api`
  (`{SUPABASE_URL}/functions/v1/api`, routes `/v1/...`); Supabase Postgres `ap-south-1`, Auth,
  Realtime Broadcast (hint) + REST event cursor (source of truth); private Cloudflare R2 for video
  and artifacts (presigned with `aws4fetch` 1.0.20). No Cloudflare Worker API, no queue, no SSE.
- **Blast radius:** `supabase/`, `sdk/`, `mcp/` · Person 3; Persons 1 and 2 consume.
- **Proof / evidence:** `docs/research/debates/D2-architecture-streaming.md`, reviews R1–R3.

## D-004 · Upload the original with R2 multipart; segment upload later behind a flag
- **Status:** accepted · **Date:** 2026-10-10 · **Owner:** @darved2305 (Person 2)
- **Decision:** 8 MiB parts, 2 concurrent, resumable, idempotent completion. Phone-side remux into
  segments is a Phase 4 upgrade, enabled only after it passes on real S23 FE and A55 gallery files.
- **Blast radius:** `android/`, `supabase/` · Persons 2 and 3.

## D-005 · Sports scope
- **Status:** accepted · **Date:** 2026-10-10 · **Owner:** @HetanshWaghela (Person 4)
- **Decision:** Badminton primary (court, rules, tracker). Table tennis: court + rules, no ball
  tracker. Tennis: court + rules, static-view tracker. Squash, padel, pickleball: listed,
  unsupported. Free play: one-tap ball tracking on informal footage.
- **Blast radius:** contracts §9 · all.

## D-006 · Models
- **Status:** accepted · **Date:** 2026-10-10 · **Owner:** @VrajVed (Person 1)
- **Decision:** `tracknet_v3` (MIT code + weights) is the badminton default; `dfine_s_coco`
  (Apache-2.0, sha256 `48a6c8cc…3e89`) for players; `tapir_causal` (Apache-2.0) for free play.
  WASB-SBDT only as a challenger after its weight licence is checked. No Ultralytics (AGPL).
- **Blast radius:** `engine/`, contracts §6 · Person 1.
- **Proof / evidence:** `docs/research/debates/D3-vision-engine.md`, `docs/plan/04-vision-engine.md` §1.

## D-007 · Honesty rules in the product
- **Status:** accepted · **Date:** 2026-10-10 · **Owner:** @HetanshWaghela (Person 4)
- **Decision:** Referee = MODEL ESTIMATE (IN / OUT / TOO CLOSE TO CALL / UNOBSERVED); the score
  changes only on a player's confirmation. No km/h; flight time instead. Comparison clips are
  "SIMILAR MOMENT" below the evidence gate (≥20 comparable decisions, ≥5 per action, ≥3 matches,
  ≤1 per rally), "WORKED FOR YOU n/N" above it. No win probability. Every number shows its
  denominator; never a fake progress percentage.
- **Blast radius:** `android/`, `services/insights/`, `engine/` · Persons 1, 2, 3.

## D-008 · Real clips and real data only: nothing synthetic, for anyone
- **Status:** accepted · **Date:** 2026-10-10 · **Owner:** @HetanshWaghela (Person 4)
- **Decision:** Every overlay is drawn on real frames; every comparison clip is a real clip with
  provenance; the only synthetic visual is the top-down court animation, labelled SIMULATED. The
  shared fixtures are **real**: a rights-cleared real clip hand-labelled by Person 4 (state
  `user`), replaced by real engine output as soon as it exists. No invented numbers anywhere, in
  code, fixtures, demos or PR proof.
- **Blast radius:** all folders · all four.

## D-009 · Proof of real results is the merge bar; tests are regression only
- **Status:** accepted · **Date:** 2026-10-10 · **Owner:** @HetanshWaghela (Person 4)
- **Decision:** Passing tests never proves the product works. No PR merges without proof on real
  input in its "Proof" section: Person 1 a real Modal run on a real clip (app/run ID, outputs,
  detections against labels); Person 2 a screen recording on the S23 FE or A55 with real footage;
  Person 3 real calls against the deployed API with real IDs; Person 4 real footage, real labels,
  eval on real engine output. If an upstream is not ready, the PR says which part ran on the
  hand-labelled real fixture, and the slice is re-proven on the live path at the phase check.
- **Blast radius:** PR template, CI guard · all four.

## D-010 · Ownership and single writers
- **Status:** accepted · **Date:** 2026-10-10 · **Owner:** @HetanshWaghela (Person 4)
- **Decision:** Each person edits only their folders (`AGENTS.md` §Folders). Person 1's Modal job
  never writes Postgres; it reports through `/internal/analyses/{id}/events|artifacts|finalize`.
  Person 3 alone assigns event `seq`, sends Broadcasts, persists results, runs insights and appends
  `analysis.completed`. Person 4 owns contracts and `@elevate/rules`; Person 2 owns the Kotlin port.
- **Blast radius:** all · all four.

## D-011 · Ship in slices to `main`
- **Status:** accepted · **Date:** 2026-10-10 · **Owner:** @HetanshWaghela (Person 4)
- **Decision:** Small slices; branch `p{n}/{slice}`; lane checks; agent code review; PR with real
  proof; squash-merge when CI is green; `main` always demoable; whoever breaks `main` fixes it
  first. LEDGER.md records every merge.
- **Blast radius:** repo process · all four.

## D-012 · Contracts v1 frozen at the start of Phase 1
- **Status:** accepted · **Date:** 2026-10-10 · **Owner:** @HetanshWaghela (Person 4)
- **Decision:** `docs/plan/contracts.md` is v1. Changes need all four to agree, a DECISIONS entry
  with blast radius, a schema version bump, and a matching update to `packages/contracts`.
- **Blast radius:** contracts · all four.

## D-013 · Team map
- **Status:** accepted · **Date:** 2026-10-10 · **Owner:** @HetanshWaghela (Person 4)
- **Decision:** Person 1 @VrajVed (vision engine, Modal). Person 2 @darved2305 (Android app;
  handle to confirm). Person 3 @nikhilpravinpise (API, data, events, insights, SDK, MCP).
  Person 4 @HetanshWaghela (repo + CI, contracts, rules, footage, evaluation, demo).
- **Blast radius:** `.github/CODEOWNERS`, `AGENTS.md` · all.

## D-014 · Phases, not deadlines
- **Status:** accepted · **Date:** 2026-10-10 · **Owner:** @HetanshWaghela (Person 4)
- **Decision:** 1 Foundations → 2 First real result → 3 Full loop → 4 Depth and extras →
  5 Demo-ready; each ends with one integration check (`docs/plan/10-build-plan.md`).
- **Blast radius:** planning · all.

---

## D-015 · UI concept is a starting point owned by Person 2
- **Status:** accepted · **Date:** 2026-10-10 · **Owner:** @darved2305 (Person 2)
- **Decision:** `docs/ui/` (8 concept screens + tokens) is not final. Person 2 owns the final UI
  design and `docs/ui/`; layouts, motion and styling may change freely. The product rules
  (D-007, D-008) stay fixed. Big design changes get a DECISIONS entry.
- **Blast radius:** `docs/ui/`, `android/` · Person 2.

## D-261010-NP1 · Apply the audited Person-3 spec corrections before implementation
- **Status:** accepted · **Date:** 2026-10-10 · **Owner:** @nikhilpravinpise (Person 3) · **Supersedes:** none
- **In one line:** In the context of Person 3's first slices, facing blocking corrections found by
  the pre-push audit, we decided to amend `person-3-spec.md` per `docs/AUDIT-FINDINGS.md` items
  17-25 and rejected implementing the spec as written, to avoid baking in known bugs, accepting a
  spec rewrite before any code lands.
- **Context:** The independent audit exercised the scripts and SQL in a scratch copy and found
  blockers (a double `/api` path prefix, PostgREST unable to increment `dispatch_attempts`,
  Broadcast failures killing appends, an insights-on-Modal plan that collides with "only Person 1
  deploys Modal", missing read endpoints the app already needs). Fixing them now is cheaper than
  after code exists.
- **Decision:** implement the spec with these corrections, all of which are now written into
  `docs/lanes/person-3-spec.md`:
  1. Mounting: `const api = new OpenAPIHono()` serves `/v1` routes and `api.doc('/v1/openapi.json')`;
      `const root = new Hono().route('/api', api); Deno.serve(root.fetch)`. No `basePath` (it made the
      spec emit `/api/v1/...` so generated SDKs would call `/api/api/v1`).
  2. Internal event posts accept the contract `decode_window_us` / `emit_range_us` arrays, mapped to
      the four `*_us` columns; `engine_version` optional, set on `analysis.started`; the cursor
      returns the same arrays.
  3. `append_event` is the audit's tested version: row lock on the run, `(type, window_seq)` dedupe
      inside the lock (Modal retries return the existing row, no new `seq`), the state-transition
      table, state + `engine_version` + `failure_reason` updated on the run, Broadcast failure becomes
      a warning, never a failed append.
  4. `analysis_runs` gains `finalized_at`, `dispatch_claimed_at` and `players`, `calibration`,
      `capabilities`, `models` jsonb; a partial unique index allows at most one non-terminal run per
      session; an `idempotency` table stores the response body for 24 h.
  5. `ingest_vision_result(p_analysis_id, p_result jsonb)` persists the vision result in one
      transaction, sets `finalized_at` (second call -> `already_finalized`), appends
      `analysis.finalizing`, runs insights in SQL, then appends the single `analysis.completed`.
      `analysis.finalizing` is removed from the Modal-allowed event list; `analysis.partial` stays in
      the enum but is not emitted in V1; the first `window.completed` flips state to `partial`.
  6. Dispatch: `analysis.queued` is appended BEFORE the Modal call; claiming is the atomic
      `claim_dispatch` RPC (`dispatch_claimed_at` lease, stale after 60 s); `create_analysis_run`
      does the idempotent insert; a `POST /v1/analyses` with a fresh `Idempotency-Key` while a live
      run exists returns the live run (the partial unique index, not a second GPU job);
      `session_spec` is the contracts section 2 body assembled by SQL.
  7. Insights run as deterministic SQL inside `ingest_vision_result`, not as a Python job on Modal:
      hero insight = eligible metric (tier not `unsupported`, denominator >= 5) ranked by
      `abs(num/den - 0.5)` desc, `den` desc, key asc, else the longest observed rally clip; finalize
      writes moments, insights only tags them; `compare_shot(...)` is a SQL function implementing
      section 10.3 exactly. `services/insights/` is unused in V1.
  8. RLS written in full: `sessions` insert additionally checks that `match_id` is owned; every
      select-only policy spelled out; explicit `grant select ... to authenticated` and
      `grant all ... to service_role`; deletes go through the admin client after an ownership check.
  9. Child IDs supplied by Person 1 are kept verbatim with primary key `(match_id, id)`. The score is
      the contiguous prefix of rallies with a user-confirmed winner, recomputed through vendored
      `@elevate/rules`.
  10. API surface additions go to `contracts.md` in their own all-four-sign-off PR:
      `GET /v1/capabilities`, `GET /v1/analyses/{id}`, `GET /v1/matches`, `GET /v1/sessions/{id}`,
      `GET /v1/matches/{id}/rallies` + `/{rid}`, `GET /v1/matches/{id}/moments`, `GET /v1/moments`,
      `GET /v1/players/{id}/history`, `GET /v1/export/{id}.rally`, share-grants, embed-tokens,
      developer-keys (moved to Phase 3: MCP needs them) and the `StubAnalysis` definition.
  11. `packages/contracts` zod schemas are vendored into `api/lib/contracts_vendor` the same way as
      rules (`supabase/scripts/sync-*.ts`, generated-header, CI freshness check). Helper scripts live
      in `supabase/scripts/`, not `functions/scripts/`. Person 4 keeps `packages/rules`
      zero-dependency with explicit `.ts` imports so Deno can run it unbundled.
- **Alternatives:** (a) implement the spec as written - rejected, the audit found real bugs;
  (b) keep insights on Modal - rejected, only Person 1 deploys Modal and it adds a second trigger
  path plus new secrets for a deterministic job SQL does inside the same transaction;
  (c) hand-maintain the API's zod schemas - rejected, contracts are law so they are vendored.
- **Blast radius:**
  - Contracts: sections 3, 7, 8 and the StubAnalysis note; version bump minor, additive only; the
    `contracts.md` edits land in their own PR (D-012 sign-off by all four).
  - People and folders: Person 3 `supabase/`, `sdk/`, `mcp/`, `docs/lanes/person-3-spec.md` implements
    per above; Person 1 posts only `analysis.started`, `window.completed`, `window.failed`,
    `analysis.failed` to `/internal` and receives `session_spec` as the section-2 body; Person 4
    keeps `packages/rules` zero-dep with `.ts` imports and lands `capabilities.json`; Person 2 is
    unaffected (additions only).
  - Data/infra: migrations per spec section 5 as amended; env names `INTERNAL_API_TOKEN`,
    `EMBED_SIGNING_KEY`, `API_BASE_URL`, `R2_*`, `MODAL_SUBMIT_*` (`.env.example` update requested
    from Person 4); `services/insights/` stays empty in V1.
  - Containment: additive-only; no app-visible contract removal; contract PR needs all four.
- **Exit:** before the first code merge, revert by superseding this entry; after merges, changes go
  through normal decisions. Point of no return is the contracts amendment PR merging.
- **Proof / evidence:** `docs/AUDIT-FINDINGS.md` items 17-25 (tested in the audit's scratch copy);
  the replacement `append_event` is quoted verbatim from there.
- **PRs:** this PR; contracts amendment PR to follow.

## D-261010-HW1 · First real clips: a static singles clip to work on, the TrackNetV3 test split to check the method
- **Status:** accepted · **Date:** 2026-10-10 · **Owner:** @HetanshWaghela (Person 4) · **Supersedes:** none (closes Open item 3)
- **In one line:** In the context of Person 1's first detection runs, facing no named clip and no
  ground truth to check our TrackNetV3 port against, we decided a static behind-the-baseline amateur
  singles clip as the working clip plus the TrackNetV3 authors' labelled test split as the method
  check, and rejected waiting for team footage or using the EuroGames clip as the main clip.
- **Context:** Person 1 is blocked on a clip and labels for the TrackNetV3 / D-FINE slices. The
  EuroGames clip is filmed from the stands at an angle, doubles, with neighbouring courts in frame,
  at 848×464; TrackNetV3 found candidates in 24% of its frames. Team footage does not exist yet.
- **Decision:**
  1. The clips, their roles and fetch commands are in `fixtures/clips.md`: working clip
     `h13g_singles` 31–91 s (dev), held-out `h13g_singles` 300–360 s (Person 4 labels and scores it;
     nobody tunes on it), method check `stdata_test`, low-resolution check `eurogames_bern_2023`.
  2. Person 1's TrackNetV3 must reproduce the published test-split numbers (official `test.py`
     first, then our module) before any threshold or tracking parameter is tuned.
  3. Licences are not tracked for these clips; source URL, stream IDs and sha256 are.
     `stdata_test` is broadcast footage and is never shown in the app or the demo.
  4. The demo clip is still open (Open item 3).
- **Alternatives:** (a) EuroGames as the main clip, rejected for the reasons above (kept as the
  low-resolution check); (b) wait for team footage, rejected because it blocks Persons 1, 2 and 4;
  (c) Mendeley single-stroke clips, rejected because they are 1–2 s with no rallies; (d) pro
  matches filmed from the stands, rejected because the near court is cut and the players are pros.
- **Blast radius:**
  - People and folders: Person 1 (`engine/eval/clips.json` mirrors `fixtures/clips.md`; method
    check before tuning); Person 2 (overlay spike on the working segment); Person 4 (start fixtures
    from the working segment, held-out labels). Person 3 unaffected.
  - Contracts: none.
  - Containment: swapping a clip later means relabelling only that clip's fixtures.
- **Exit:** supersede with a new entry; no point of no return before fixtures are labelled.
- **Proof / evidence:** `fixtures/clips.md` "First look" (local TrackNetV3 probe: candidates in
  501/600 frames on the working segment, eye-checked on 20 frames; not accuracy).
- **PRs:** this PR.

## D-261010-VV1 · Modal spend ceiling is $30 for the project
- **Status:** accepted
- **Date:** 2026-10-10 · **Owner:** @VrajVed (Person 1) · **Supersedes:** none
- **In one line:** In the context of running the vision engine on Modal (D-002), facing GPU spend
  on a card with no automatic cap, we decided a $30 total ceiling for the project and rejected
  having no ceiling or a higher one, so that a runaway job or retry loop cannot run up a bill,
  accepting that GPU minutes on real clips are now limited.
- **Context:** Resolves Open item 2. Modal Starter gives a $30 monthly credit, but that credit is
  not a spending cap and GPU needs a card (`docs/plan/03-architecture-and-streaming.md` §7,
  `docs/plan/10-build-plan.md`). Person 1 operates the workspace `voidinvariant`.
- **Decision:** Total Modal spend for the project stays at or under $30. Person 1 sets a workspace
  budget limit in the Modal dashboard if Modal offers one (UNVERIFIED), scales to zero outside demo
  windows, and checks usage on each `/engine-health` run. Person 3's spend guard stops GPU
  submissions before the $30 ceiling is reached.
- **Alternatives:** (a) no ceiling, rejected because the credit does not stop charges to the card;
  (b) a higher ceiling, rejected because $30 matches the Starter monthly credit.
- **Blast radius:**
  - Contracts: none; version bump none
  - People and folders: Person 1 `engine/` keeps jobs scaled to zero and watches usage; Person 3
    `supabase/` configures the spend guard threshold below $30 (Phase 4)
  - Data/infra: Modal workspace billing settings; no new env names or secrets
  - Containment: config only; signed off by all four (Person 3 for the spend guard)
- **Exit:** a new entry raising or lowering the ceiling; no point of no return (money already spent
  is spent).
- **Proof / evidence:** Ceiling set by Person 1 on 2026-10-10; all four people agreed the
  same day (confirmed to Person 1 outside GitHub), including Person 3 for the spend guard. Modal Starter credit
  figures as cited in `docs/plan/03-architecture-and-streaming.md` §7 (UNVERIFIED against
  modal.com/pricing today).
- **PRs:** #6

## D-261010-HW2 · `@elevate/contracts` is the single source of truth for contracts v1
- **Status:** accepted · **Date:** 2026-10-10 · **Owner:** @HetanshWaghela (Person 4) · **Supersedes:** none
- **In one line:** In the context of every lane needing the same data shapes, facing drift between
  TypeScript, Deno, Python and Kotlin, we decided zod 4 schemas in `packages/contracts/src` with
  generated JSON Schemas and spec-sync tests, and rejected hand-written schemas per lane.
- **Context:** Person 3 (API, SDK, MCP) and Person 2 (Android) are blocked on the schemas; Person 1
  validates engine output in CI.
- **Decision:**
  1. `packages/contracts/src` (zod 4.6.5) is the source. `schemas/*.schema.json` (draft 2020-12) are
     generated and committed; CI fails if they are stale. Person 3 vendors `src/` into Deno;
     Person 1 validates with Python `jsonschema`; Person 2 generates or validates Kotlin from
     `schemas/`.
  2. Producers validate strictly (unknown fields rejected); readers ignore unknown fields, so
     additive changes never break a deployed reader. Breaking changes ship as `.v2` beside `.v1`.
  3. Tests fail when `contracts.md` and the schemas disagree: every fenced JSON example and every
     enum written in its prose.
  4. Interpretations of v1 (no contract change): the engine posts only `analysis.started`,
     `window.completed`, `window.failed`, `analysis.failed` to `/internal` (§3, D-261010-NP1);
     times are never negative (§0); the badminton court template must equal
     `badminton_<format>` (§2); a free-play floor rectangle carries its four named tapped corners
     (§2); a match event with `contact_xy_m` needs `plane`, and an airborne event may carry
     `image_xy` instead (§0; additive optional field).
  5. Rules JSON Schema cannot express (range order, template vs format, contact needs plane) are
     listed in the package README with a Python check.
- **Alternatives:** (a) hand-written JSON Schema, rejected: a second source drifts; (b) OpenAPI as
  the source, rejected: it does not cover R2 artifacts (`window_result.v1`) or Kotlin models.
- **Blast radius:**
  - People and folders: Person 1 validates `window_result.v1` / `vision_result.v1` from
    `schemas/`; Person 2 builds models from `schemas/`; Person 3 vendors `src/` and uses it in
    `@hono/zod-openapi`; Person 4 owns `packages/contracts`.
  - Contracts: v1 unchanged except the additive optional `image_xy` on match events.
  - Containment: open questions are listed in `packages/contracts/CHANGELOG.md` for the
    contracts amendment PR (D-012 sign-off by all four).
- **Exit:** revert the package PR; nothing else imports it yet.
- **Proof / evidence:** the PR's Proof section (Python, Deno, Hono and CI runs).
- **PRs:** this PR.

## D-261010-HW3 · Contracts enums equal the database CHECK constraints
- **Status:** accepted · **Date:** 2026-10-10 · **Owner:** @HetanshWaghela (Person 4) · **Supersedes:** none
- **In one line:** In the context of the API validating requests with `@elevate/contracts` before
  writing to Postgres, facing enums that differed from Person 3's CHECK constraints, we decided the
  contracts accept exactly what the database accepts (tested), and rejected leaving them looser.
- **Context:** Audit of `main` after PR #10: the contracts allowed 7 sports (database: 4), lacked
  format `none` (database has it for free play), and left `mode` and artifact `kind` as free
  strings (database: fixed lists). A request could pass validation and then fail in Postgres.
- **Decision:** sessions, matches and rules use the 4 built sports (sport packs keep all 7);
  format adds `none`, used exactly when sport is `free_play`; `mode` and artifact `kind` use the
  database's lists. A test reads `supabase/migrations/*.sql` and fails when they differ; CI's
  `ts` job now also runs on migration changes. Package 1.1.0.
- **Alternatives:** (a) widen the database to 7 sports, rejected: squash, padel and pickleball are
  unsupported until built (§9); (b) no test, rejected: the next migration would drift silently.
- **Blast radius:**
  - People and folders: Person 3 (a migration that changes one of these CHECKs now needs the same
    change in `packages/contracts`, or the `ts` job fails); Person 1 posts artifact kinds from the
    list; Person 2 models get the narrower enums.
  - Contracts: v1, tightened to match the database; additive `none` format.
- **Exit:** supersede with a new entry; change both the migration and the package together.
- **Proof / evidence:** the PR's Proof section.
- **PRs:** this PR.

## D-261011-HW1 · Cloudflare stays at $0: free tier only, enforced in code, config and a card limit
- **Status:** accepted · **Date:** 2026-10-11 · **Owner:** @HetanshWaghela (Person 4) · **Supersedes:** none
- **In one line:** In the context of R2 storing every upload and Workers AI being available, facing
  Cloudflare having no hard spending cap, we decided to keep usage under half the free tier with
  code guards, bucket configuration and a low-limit card, and rejected relying on budget alerts.
- **Context:** Cloudflare's budget alerts only email; nothing stops usage. R2 bills beyond 10
  GB-month, 1M Class A and 10M Class B ops; every multipart part is a Class A op.
- **Decision:** follow `docs/ops/cloudflare-budget.md`: budget = half of each free dimension;
  guards G1-G9 in the API and engine; lifecycle rules (originals 3 days, results and clips 30
  days, abandoned multipart 1 day); no public access; Standard storage only; Workers Free plan
  only; scoped tokens; daily 2-minute check; kill switch `R2_WRITES_ENABLED`.
- **Alternatives:** (a) budget alerts only, rejected: they do not stop spend; (b) no R2, rejected:
  the upload flow (D-004) needs object storage.
- **Blast radius:**
  - People and folders: Person 3 implements G1-G7 and G9 in `supabase/` (new 429/413/503 error
    codes and an `r2_usage_monthly` table); Person 1 implements G8 in `engine/`; the Cloudflare
    account owner applies layers 2 and 3 and does the daily check; Person 2 shows the 429/503
    messages in the app.
  - Contracts: none (error codes are free strings in the §1 envelope).
  - Data/infra: R2 lifecycle rules delete originals after 3 days (closes the retention open item
    for originals).
- **Exit:** raise a budget with a new entry; never remove the guards.
- **Proof / evidence:** limits and commands from developers.cloudflare.com (R2 pricing, R2 object
  lifecycles, Workers AI pricing, Wrangler R2 commands, usage-based billing), read 2026-10-11.
- **PRs:** this PR.

## D-261011-HW2 · Cloudflare budget is 20% of the free tier, with an automatic pause at 10%
- **Status:** accepted · **Date:** 2026-10-11 · **Owner:** @HetanshWaghela (Person 4) · **Supersedes:** the numbers in D-261011-HW1
- **In one line:** In the context of D-261011-HW1, facing the team's requirement that the free tier
  is never even approached ("not even $1"), we decided a hard budget of 20% of each free limit with
  an automatic pause at 10%, and rejected the earlier 50% budget.
- **Context:** Our counters are estimates (retries, failed requests and ranged reads also count at
  Cloudflare), so the margin has to be large.
- **Decision:** in `docs/ops/cloudflare-budget.md`: storage 2 GiB (pause at 1 GiB), Class A
  200,000/month (pause at 100,000), Class B 2,000,000 (pause at 1,000,000); one video at most 300
  MiB; 5 sessions per user and 30 uploads per day; upload-part URLs live 10 minutes; 200 download
  URLs per user per day; 40 clips per analysis; new guards G10 (automatic pause) and G11 (the app
  retries a part at most 3 times); originals deleted after 1 day, results and clips after 14 days.
- **Alternatives:** (a) keep 50%, rejected by the team; (b) no R2, rejected: uploads need it.
- **Blast radius:**
  - People and folders: Person 3 implements the tighter G1-G6 and new G10 in `supabase/`; Person 1
    caps clips at 40 (G8); Person 2 implements G11 and shows the pause message; the Cloudflare
    account owner applies the 1-day and 14-day lifecycle rules.
  - Contracts: none.
- **Exit:** a new entry; never above 50% of any free limit.
- **Proof / evidence:** same Cloudflare sources as D-261011-HW1.
- **PRs:** this PR.

## D-261011-HW3 · Chunked real-time analysis is core: analysis starts on the first segment
- **Status:** accepted · **Date:** 2026-10-11 · **Owner:** @HetanshWaghela (Person 4) · **Supersedes:** the "Phase 4, behind a flag" placement of segment upload in `03`, `10` and debate D1
- **In one line:** In the context of the judges asking for a chunking strategy and real-time
  analysis, facing analysis that only starts after the whole video uploads, we decided to make
  segment upload core in Phase 3, and rejected keeping it as a later flag.
- **Context:** Judges' feedback in the mentor round. Today results already stream per 6 s window,
  but only after the full upload; a 5-minute 1080p video can take minutes to upload.
- **Decision:** as `docs/plan/03-architecture-and-streaming.md` §2 now describes: the phone
  remuxes into ~6 s keyframe-aligned segments (no re-encode) and uploads them in order; the API
  registers each segment and starts the analysis on segment 0; the engine processes segments as
  their manifests arrive and posts windows as today; whole-file multipart stays as the fallback.
  Contracts 1.2.0 accept `upload_mode: "segments"`; segment routes and a `final` flag go through
  the contracts amendment (D-012).
- **Alternatives:** (a) keep it in Phase 4, rejected: the judges asked for it; (b) live in-app
  recording with chunked capture (`MediaRecorder.setNextOutputFile`), deferred: new scope, can
  build on the same segment pipeline later.
- **Blast radius:**
  - People and folders: Person 2 segmenter and uploader; Person 3 segment routes and engine
    notification; Person 1 segment-by-segment processing with overlap; Person 4 contracts
    amendment and the Cloudflare rule (`seg/` deleted after 1 day; segments count against G1/G4).
  - Contracts: 1.2.0 (`upload_mode` enum); routes and `final` in the amendment PR.
- **Exit:** set `upload_mode` back to `multipart` in the app; the server keeps both.
- **Proof / evidence:** judges' feedback; latency to be measured on S23 FE, A55 and Modal at the
  Phase 3 check.
- **PRs:** this PR.
