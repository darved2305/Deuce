# AGENTS.md

Elevate 1.0 · PS 02 · racket-sports intelligence from one phone camera. Four people, four lanes,
small slices straight to `main`. `main` is always demoable. Read this file, then the `AGENTS.md`
in the folder you are working in (it adds lane-specific rules).

## The product in five lines
Android app (Kotlin, Compose, Media3): a player picks a match video from the gallery, it plays at
once, a cloud engine on Modal analyses it, and results are drawn on the **real footage** (shuttle
trail, landing marks, court lines). Every number opens the frames that prove it; anything
uncertain is withheld. The same match record (`match.v1`) is served to developers through a REST
API, a TypeScript SDK, a read-only MCP server and an embeddable player. Plan: `docs/plan/`.

## Non-negotiable rules
1. **Nothing synthetic, for anyone (D-008).** No invented numbers, clips, detections or demo
   data, ever. Overlays sit on real frames; comparison clips are real clips with provenance. The
   shared fixtures are a real clip hand-labelled by Person 4 (`state: "user"`). The only synthetic
   visual is the top-down court animation, labelled `SIMULATED`.
2. **Proof of real results is the merge bar (D-009).** Tests catch regressions; they never prove
   the product works. No PR merges without real proof in its `## Proof` section (see "Proof by
   person" below). Never claim something works, is deployed or is tested unless you ran it and saw
   the output in this session.
3. **Honesty in the product (D-007).** Every displayed number shows its denominator or confidence
   and opens its frames. Referee verdicts are `MODEL ESTIMATE` (IN / OUT / TOO CLOSE TO CALL /
   UNOBSERVED); the score changes only when a player confirms. No km/h (flight time instead).
   Comparison clips say `SIMILAR MOMENT` unless the evidence gate is met. No fake progress bars.
4. **Stay in your lane (D-010).** Edit only your folders (table below). Need something from another
   lane? Ask its owner or write a decision. A Claude Code hook blocks out-of-lane edits and CI runs
   `scripts/check-lane.sh`; do not route around either with shell commands.
5. **Contracts are law (D-012).** Data shapes, routes, enums, errors: `docs/plan/contracts.md`
   (and `packages/contracts` once it exists). Read before touching data. Changing a contract is its
   own PR with a `DECISIONS.md` entry and the sign-off of everyone in its blast radius.
6. **Decisions before changes.** Any change to the plan, a contract, ownership, scope, stack or a
   rule in this file gets an entry in `DECISIONS.md` (blast radius mandatory) in the same PR or
   earlier. `LEDGER.md` is written only by CI after each merge; never edit it.
7. **Never guess an API.** Pin exact versions. Before using any library or platform API that is
   not already used in this repo, read current docs for the pinned version (see "Docs for agents").
   If you cannot verify something, write `UNVERIFIED` in the PR.
8. **Secrets never enter git, prompts or logs.** Names live in `.env.example`; values in local
   `.env` (gitignored), Supabase/Modal secret stores, or GitHub Actions secrets. The Supabase
   service-role key never reaches the app.
9. **Big binaries never enter git.** Videos, model weights and APKs live in R2, the Modal image or
   CI artifacts. Git holds code, docs, JSON fixtures and links.
10. **Cloudflare and Modal cost nothing beyond their caps (D-261011-HW1, D-261010-VV1).** R2 stays under
    half its free tier, enforced in code: follow `docs/ops/cloudflare-budget.md` (upload size, storage
    and daily caps, no `ListObjects` loops, short-lived URLs, kill switch `R2_WRITES_ENABLED`). Never
    Infrequent Access, public buckets, Workers Paid or account-wide tokens.

## Team and lanes
| Person | GitHub | Lane | Folders (edit only these) |
|---|---|---|---|
| 1 | @VrajVed | Vision engine on Modal; Modal operator | `engine/`, `docs/lanes/person-1.md` |
| 2 | @darved2305 | Android app + final UI design | `android/`, `docs/ui/`, `docs/lanes/person-2.md` |
| 3 | @nikhilpravinpise | API, data, events, insights, SDK, MCP | `supabase/`, `services/insights/`, `sdk/`, `mcp/`, `docs/lanes/person-3*.md` |
| 4 | @HetanshWaghela | Repo, CI, contracts, rules, fixtures, footage, eval, embed, demo | repo-root files, `.github/`, `.claude/`, `scripts/`, `packages/`, `fixtures/`, `web/`, `demo/`, `docs/` |
| all | — | Decisions | `DECISIONS.md` (append; see its rules) |

Single writers: Person 1's Modal job never writes Postgres (it calls `/internal/...`); Person 3
alone assigns event `seq`, sends Broadcasts, persists results and appends `analysis.completed`;
Person 4 owns contracts and `@elevate/rules` (Person 2 owns the Kotlin port).

Your brief: `docs/lanes/person-<n>.md` (Person 3 also `docs/lanes/person-3-spec.md`).

## Ship in slices (the pipeline)
1. `/sync-main` (or `git fetch && git rebase origin/main`) and read what other lanes changed.
2. Plan the slice (`/slice-plan`): one behaviour, one lane, about 300 changed lines or fewer. Unfinished
   user-facing work hides behind a feature flag.
3. Branch `p<n>/<slice-name>` from fresh `main` (e.g. `p2/review-screen`).
4. Build it. Write the regression test or fixture check, then make it pass.
5. Run your lane's checks (lane `AGENTS.md`), then `./scripts/check-lane.sh`.
6. **Prove it for real** (next section) and capture the evidence.
7. Fresh-context review: Claude Code `/code-review` (and the `slice-reviewer` subagent); Codex
   `codex review --base origin/main`. Fix every blocking finding; re-run checks.
8. `/ship` (or push + `gh pr create`) with the PR template filled: Slice, Decisions, Contracts,
   **Proof**, Checks run. CI: `guard` (lane, proof, decisions, secrets) + your lane job + `ci-gate`.
9. Squash-merge when `ci-gate` is green **and the proof is real**. No waiting for approval unless the
   blast radius touches another person's folder or a contract (then they approve).
10. **If `main` breaks:** its author reverts or fixes it before doing anything else. Revert first,
    fix forward second.

## Proof by person (what goes in `## Proof`)
- **Person 1:** a real Modal run on a real clip: app/run ID, output artifact links, detections drawn
  on frames (contact sheet), metrics against Person 4's hand labels, and what changed vs the last
  good run.
- **Person 2:** a screen recording (or screenshots) from the S23 FE or A55 running the build with
  real footage; for live features, the real backend.
- **Person 3:** the exact commands and responses against the **deployed** API with real IDs (curl,
  SDK script, MCP tool call), plus the rows/events they produced.
- **Person 4:** real footage and labels (source + rights), eval output on real engine results,
  CI run links for pipeline changes.
If an upstream is not ready, say exactly which part ran on the hand-labelled real fixture; that
slice is re-proven on the live path at the phase check.

## Code Review Rules
Used by Codex's GitHub reviewer, `codex review` and Claude's `/code-review`. Report only:
- **Contracts:** any field, enum, unit, route or error shape that differs from `docs/plan/contracts.md`.
- **Scope:** files outside the author's lane, or changes unrelated to the slice.
- **Honesty:** synthetic or invented data anywhere; a displayed number without denominator or
  confidence; an overlay not on real footage; fixture or cached output presented as live; a
  verdict that changes the score without confirmation.
- **Proof:** a PR whose proof is only "tests pass" or does not match the change.
- **Correctness and safety:** bugs, unhandled errors, secrets, the service-role key in client code,
  missing RLS, hand-edited lockfiles or generated files, unpinned versions.
Do not flag formatting, lint or naming preferences that CI already enforces.

## Docs for agents (read before using an API)
- Context7: `npx ctx7@latest library "<name>" "<question>"` then `npx ctx7@latest docs <id> "<question>"`.
- Vendor indexes: https://supabase.com/llms.txt · https://modal.com/llms.txt
- Android: developer.android.com (Compose, Media3, WorkManager, Glance) via Context7 or the site.
- Lane `AGENTS.md` files list the exact docs, MCP servers and gotchas for each stack.

## Phases (no deadlines)
1 Foundations → 2 First real result (one real badminton clip: phone → R2 → Modal → events → trail on
the real video) → 3 Full loop → 4 Depth and extras → 5 Demo-ready. Details:
`docs/plan/10-build-plan.md`. Each phase ends with one integration check.

## Where things are
`docs/plan/` the plan (contracts, architecture, vision, app, coaching, referee, platform, demo,
build plan, real clips) · `docs/lanes/` each person's brief · `docs/research/` evidence ·
`docs/ui/` concept screens (illustrations stand in for footage; numbers illustrative) ·
`DECISIONS.md` decisions · `LEDGER.md` what landed · `docs/AUDIT-FINDINGS.md` open fixes per person (do yours first).
