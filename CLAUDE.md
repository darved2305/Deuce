@AGENTS.md

## Claude Code specifics
- **Set your lane once per clone:** `git config --local elevate.lane p1` (p1, p2, p3 or p4). The
  `lane-guard` hook blocks edits outside it; `format-lint` formats and lints each edited file. If a
  hook blocks an edit, do not route around it with shell commands (`sed -i`, redirects): ask.
- Shared skills: `/slice-plan`, `/ship`, `/decision`, `/sync-main` (Person 1 also `/engine-health`).
  Reviewer subagent: `slice-reviewer`.
- Use plan mode (Shift+Tab) when a slice touches more than one file; for a one-line fix, just do it.
- Use subagents for read-heavy investigation and for review. `/clear` between unrelated slices.
- After two failed corrections on the same problem, `/clear` and restate the task with what you learned.
- When compacting, keep: the slice plan, the modified-file list, this lane's checks, and the proof
  still owed.
- Personal preferences go in `CLAUDE.local.md` (gitignored), not here.
- After `/sync-main` reports changes to any `AGENTS.md`, `CLAUDE.md` or `.claude/` file, restart the
  session so the new rules load.
