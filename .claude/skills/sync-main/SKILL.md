---
name: sync-main
description: Rebase on the latest origin/main and summarise what other lanes changed. Run before each new slice and after long sessions.
disable-model-invocation: true
---
1. `git status --porcelain` must be empty. If not, ask whether to commit or stash.
2. `git fetch origin`, `before=$(git merge-base HEAD origin/main)`, then `git rebase origin/main`. Resolve conflicts only inside your lane; otherwise stop and ask.
3. Summarise what landed since `$before`: `git log --oneline "$before"..origin/main` and `git diff --stat "$before" origin/main -- docs/plan packages/contracts DECISIONS.md fixtures AGENTS.md CLAUDE.md .claude`.
4. Report at most 10 lines: new decisions (IDs), contract changes, rule or instruction changes, fixture changes, and anything that affects your current slice.
5. If any `AGENTS.md`, `CLAUDE.md` or `.claude/` file changed, tell the user to restart this agent session so the new rules load.
6. Re-run your lane's quick check.
