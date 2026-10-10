---
name: slice-plan
description: Plan one shippable slice (one behaviour, one lane) before writing code. Use at the start of every slice.
argument-hint: "<what the slice should do>"
---
Plan a slice. Do not edit files in this step. The slice request is the user's text after the command name.

1. `git fetch origin`, then `git log --oneline HEAD..origin/main`. Note anything touching `docs/plan/contracts.md`, `packages/contracts/`, `DECISIONS.md`, `.claude/` or any `AGENTS.md`.
2. Read the parts of `docs/plan/contracts.md` the slice touches, your brief in `docs/lanes/`, and the `AGENTS.md` of the folders involved.
3. Write the plan in chat, at most 15 lines:
   - Slice: one sentence, one behaviour, inside your lane (`git config --get elevate.lane`).
   - Files: paths to create or change. If any is outside your lane, stop: that is another person's slice or a decision.
   - Contracts used: schemas, routes, fixtures, by name.
   - Real proof you will produce (AGENTS.md "Proof by person"): the exact clip, device, run or API call. No synthetic data.
   - Regression check: the test or fixture check you will add.
   - Checks: the exact commands from your lane AGENTS.md.
   - Decisions: does this change the plan, a contract or a rule? If yes, a DECISIONS.md entry comes first.
   - Out of scope: 2–3 bullets.
   - Unverified: any API you must look up first (AGENTS.md "Docs for agents").
4. If it needs more than about 300 changed lines or touches two lanes, split it and plan only the first piece.
5. Stop and wait for approval.
