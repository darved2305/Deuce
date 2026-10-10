# Person 4 · Contracts, rules, footage, evaluation and demo

Paste this file into your coding agent as the kickoff prompt.

## Mission
Keep the other three unblocked and make the judges trust what they see: the shared contracts and
fixtures everyone builds against, the score engine, the real footage with clear rights, the
held-out evaluation that turns claims into numbers, and the demo itself.

## Read first
`docs/plan/contracts.md`, `docs/plan/01-ps-fundamentals.md`, `docs/plan/12-real-clips.md`, `docs/plan/09-demo.md`,
`docs/plan/07-ai-referee.md` §6, `docs/plan/11-debates.md`, `docs/research/footage-sources.md`.

## You own
Repo root (pnpm/uv workspace, `AGENTS.md`, CI), `packages/contracts/`, `packages/rules/`
(`@elevate/rules`), `fixtures/`, `web/`, `demo/`, footage rights and attribution, evaluation and
the integration check scripts. (Android Gradle files belong to Person 2; migrations to Person 3.)

## How you ship
In slices, straight to `main`, through the pipeline in `docs/plan/10-build-plan.md` §6: small slice → your lane's checks → agent code review → PR → squash-merge on green CI → deployed. Unfinished work hides behind a flag. If you break `main`, fixing it comes first.

## Phase 1 · Foundations
1. Workspace, `AGENTS.md` (shared rules from `10` §7), and the **ship pipeline** (`10` §6): branch
   naming, PR template (what + how verified + screenshot/output), CI that validates fixtures and
   engine outputs against the schemas, runs the rules vectors and each lane's tests, builds the APK
   artifact, and deploys the Edge Function + migrations from `main`; squash-merge on green.
2. `packages/contracts`: zod schemas for every shape in `contracts.md`, exported TS types,
   generated JSON Schema files (Person 1 validates in Python, Person 2 generates Kotlin models,
   Person 3 uses zod-openapi).
3. The four start fixtures (`contracts.md` §11), built from the working segment in `fixtures/clips.md` (D-261010-HW1) by hand
   labelling (shuttle points, landings, winners); nothing invented. Then `fixture_freeplay.json`
   (real corridor clip + slow-motion truth) and `fixture_comparison.json` (two real drill clips).
4. `@elevate/rules`: `RulesState`, `bwf_3x21` with `applyPoint`, `undo`, `server`, `courtSide`,
   game/match end; golden vectors (deuce at 20-all, cap at 30, service court by score parity).
5. Keep `fixtures/clips.md` current (source, stream IDs, sha256); licences are not tracked (D-261010-HW1). Label the held-out segment yourself; rally winners are burned into the video.
6. Audit an A55 slow-motion test file (real frame rate and timestamps) to confirm it can serve as
   the ground-truth reference.

## Phase 2 · First real result
- Footage: team drills and the corridor set filmed on the S23 FE (stand, landscape), with every
  corridor bounce also filmed by the A55 in slow motion beside a measured floor grid (the
  independent truth for review estimates). Consent recorded for everyone on camera.
- Held-out labels on clips nobody tunes on: ball positions, hits, landings/bounces with the true
  side of the line, rallies with winners.
- Evaluation script against the frozen schema (precision/recall with stated pixel tolerance, hit
  F1, contact error in cm on the floor plane, review estimates vs truth with call coverage and
  abstention rate, flight-time error, rally/score accuracy).

## Phase 3 · Full loop
Run the eval on real engine output → `fixtures/eval/RESULTS.md` (pass / unmet / unknown /
could-not-run, with clip names and conditions); set the review call-coverage threshold before
looking at results; reference library: team drills through the real engine, tagged
`team_reference` with consent; seed JSON + media manifest for Person 3's `seed_demo_match`;
the end-of-phase integration script.

## Phase 4 · Depth and extras
`<match-player>` embed component + a plain academy page built only on the TS SDK; capability
slide from `RESULTS.md`; remaining rulesets (`casual_21`, `ittf_11`, `tennis_bo3_tb`, `bwf_3x15`)
with vectors.

## Phase 5 · Demo-ready
Run sheet (`09`), cue card, fallback kit (USB-C → HDMI adapter tested with DeX on the S23 FE,
hotspot plan, fallback clips genuinely analysed and labelled "analysed earlier"), rehearsals, pitch.
