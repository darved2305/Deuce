---
name: ship
description: Run lane checks, prove the slice on real input, review the diff in a fresh context, push, open the PR and merge when CI is green. Human-invoked only.
disable-model-invocation: true
---
Ship the current slice. Stop at the first failure and report it. Never skip a step.

1. Branch must not be `main`. Name it `p<n>/<slice>` (n from `git config --get elevate.lane`); if it differs, `git branch -m` it.
2. Sync: `git fetch origin && git rebase origin/main`. Resolve conflicts only inside your lane; for anything else stop and ask.
3. `./scripts/check-lane.sh`. A failure means the diff leaves your lane: split the slice or record a decision.
4. Run your lane's checks from its AGENTS.md. Fix causes; never weaken, skip or delete a test. Keep the pass/fail lines for the PR.
5. Real proof: confirm the evidence for AGENTS.md "Proof by person" exists from a real run in this session (recording, Modal run ID + outputs, deployed-API responses, eval output). If it does not exist, stop and tell the user what to run. Tests passing is not proof.
6. If the diff touches `docs/plan/`, `packages/contracts/`, `AGENTS.md`, `CLAUDE.md`, `.claude/lanes.json` or CODEOWNERS, make sure `DECISIONS.md` has the entry (use `/decision`).
7. Review in a fresh context: run the `slice-reviewer` subagent on the diff against the slice plan, then `/code-review`. Fix only blocking findings (correctness, contracts, lane, honesty, proof). If code changed, repeat step 4.
8. Commit with an imperative message under 72 characters. `git push -u origin HEAD`.
9. Write the PR body to a temp file with exactly these sections (as in `.github/pull_request_template.md`): `## Slice`, `## Decisions`, `## Contracts`, `## Proof`, `## Checks run`. Check it with `PR_BODY="$(cat <file>)" ./scripts/check-pr.sh`, then `gh pr create --title "<slice>" --body-file <file>`.
10. `gh pr checks --watch`. When `ci-gate` is green and the proof is real: `gh pr merge --squash --delete-branch` (unless the blast radius needs another person's approval). If CI fails, fix on this branch and push again. Print the PR URL.
