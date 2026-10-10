# ggw_elevate · Elevate 1.0 · PS 02

Racket-sports intelligence from one phone camera. Android app + cloud engine + developer
platform. Everything shown is real footage and real results; nothing synthetic.

**Agents and humans: the rules are in [`AGENTS.md`](AGENTS.md).** Claude Code reads it through
`CLAUDE.md`; Codex reads it directly.

> **Open audit findings, assigned by person: [`docs/AUDIT-FINDINGS.md`](docs/AUDIT-FINDINGS.md). Fix yours as your first slices.**

## Who does what
| Person | GitHub | Lane | Start with |
|---|---|---|---|
| 1 | @VrajVed | Vision engine on Modal (Modal operator) | `docs/lanes/person-1.md`, `engine/AGENTS.md` |
| 2 | @darved2305 | Android app (Kotlin, Compose, Media3) + final UI design | `docs/lanes/person-2.md`, `android/AGENTS.md`, `docs/ui/` |
| 3 | @nikhilpravinpise | API, database, events, insights, SDK, MCP | `docs/lanes/person-3-spec.md` (implement exactly), `supabase/AGENTS.md` |
| 4 | @HetanshWaghela | Repo, CI, contracts, rules, fixtures, footage, eval, demo | `docs/lanes/person-4.md` |

## First steps (everyone)
1. Clone, then set your lane once: `git config --local elevate.lane p1` (p1, p2, p3 or p4).
2. Read `AGENTS.md`, your lane's `AGENTS.md`, your brief in `docs/lanes/`, `docs/plan/contracts.md`, and `DECISIONS.md`.
3. Claude Code: open the repo, approve the project hooks and `.mcp.json` servers when asked, run `/memory` once to confirm `AGENTS.md` loaded. Codex: start it inside your lane folder (`codex --cd android`) so your lane's `AGENTS.md` is in context.
4. Install your lane's agent skills (listed in your lane `AGENTS.md`): Modal (`modal skills install --claude`), Android (`android skills add --all --agent claude-code`), Supabase / Hono / Deno / Cloudflare (`npx skills add …`).
5. Copy `.env.example` to `.env` and fill only what your lane needs (never commit it).

## How we ship
Small slices straight to `main`: `/slice-plan` → build → lane checks → **real proof** →
`/code-review` (or `codex review --base origin/main`) → `/ship` → merge when `ci-gate` is green.
Tests catch regressions; they never prove the product works. Every PR's `## Proof` shows real
results (see `AGENTS.md`). Any change to the plan, a contract or a rule starts in `DECISIONS.md`.
`LEDGER.md` records every merge automatically.

## Repo map
```
AGENTS.md · CLAUDE.md        rules for every agent (single source)
DECISIONS.md · LEDGER.md     decisions with blast radius · what landed on main
docs/plan/                   the plan: contracts, architecture, vision, app, coaching, referee, platform, demo, build plan
docs/lanes/                  each person's brief (+ Person 3's full spec)
docs/research/               research, debates and reviews behind the decisions
docs/ui/                     concept screens (illustrations stand in for real footage)
engine/                      Person 1 · android/ Person 2 · supabase/ services/insights/ sdk/ mcp/ Person 3
packages/ fixtures/ web/ demo/ Person 4
.claude/                     shared skills, reviewer agent, hooks, lane map
.github/                     CI (ci-gate + lane jobs), ledger, PR template, CODEOWNERS
scripts/                     check-lane.sh, check-pr.sh
```

## Repo owner (@darved2305): one setting that makes the rules enforceable
On GitHub Free, a private repo cannot require checks, enforce CODEOWNERS or auto-merge. GitHub Pro
is free for verified students (GitHub Education). With Pro: Settings → Branches → protect `main`:
require the `ci-gate` check, require pull requests, enable auto-merge and "Automatically delete head
branches". Until then the rules are honour-based and CI results are advisory.
