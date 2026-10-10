#!/usr/bin/env bash
# PR guard (CI and /ship): real proof present, decisions recorded for plan/contract/rule changes,
# LEDGER.md never hand-edited. Reads the PR body from $PR_BODY (CI) or `gh pr view` (local).
set -euo pipefail

body="${PR_BODY:-}"
if [ -z "$body" ] && command -v gh >/dev/null 2>&1; then
  body=$(gh pr view --json body --jq .body 2>/dev/null || true)
fi
base="${BASE_REF:-origin/main}"
changed=$(git diff --name-only "$base"...HEAD)
fail=0
err() { echo "check-pr: $1" >&2; fail=1; }

section() { # print the text under "## <name>" up to the next "## "
  awk -v h="## $1" 'index($0,h)==1{f=1;next} /^## /{f=0} f' <<<"$body"
}

# 1. Real proof (D-009): a non-empty Proof section that points at something real.
proof=$(section "Proof" | sed '/^[[:space:]]*$/d; /<!--/,/-->/d')
if [ -z "$proof" ]; then
  err "missing '## Proof'. Show real results: recording/screenshot on a real phone, real Modal run ID + outputs, real API calls with real IDs, or real eval output. Tests passing is not proof."
elif ! grep -Eqi 'https?://|user-attachments|modal\.com|run[ _-]?id|ap-[a-z0-9]+|fc-[a-z0-9]+|\$ curl|```' <<<"$proof"; then
  err "'## Proof' has no link, attachment, run ID or command output. Paste real evidence."
fi

# 2. Decisions recorded (D-012): plan, contract, rule or ownership changes need DECISIONS.md.
needs_decision=$(grep -E '^(docs/plan/|packages/contracts/|AGENTS\.md|CLAUDE\.md|\.claude/lanes\.json|\.github/CODEOWNERS)' <<<"$changed" || true)
if [ -n "$needs_decision" ] && ! grep -qx 'DECISIONS.md' <<<"$changed"; then
  err "these files change the plan/contracts/rules but DECISIONS.md is not updated:
$needs_decision
Add a D- entry with the blast radius (or split: decision-only PR first)."
fi
if ! section "Decisions" | grep -Eq 'none|D-[0-9A-Z-]+'; then
  err "'## Decisions' must list decision IDs (e.g. D-261010-HW1) or 'none'."
fi

# 3. LEDGER.md is written only by the ledger workflow.
if grep -qx 'LEDGER.md' <<<"$changed"; then
  err "LEDGER.md is append-only and written by CI after merge. Remove it from this PR."
fi

exit "$fail"
