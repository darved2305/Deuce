#!/usr/bin/env bash
# PostToolUse: format the edited file, then lint it. Lint errors go to stderr with exit 2,
# which Claude sees and fixes. Missing tools are skipped silently. Per-file only (fast);
# full checks run in /ship. EDIT the commands below if your lane uses different tools.
set -uo pipefail

input=$(cat)
f=$(jq -r '.tool_input.file_path // empty' <<<"$input")
cwd=$(jq -r '.cwd // empty' <<<"$input")
[ -f "$f" ] || exit 0
root=$(git -C "${cwd:-.}" rev-parse --show-toplevel 2>/dev/null) || exit 0
cd "$root" || exit 0
rel="${f#"$root"/}"

fail() { printf '%s\n' "$1" >&2; exit 2; }
have() { command -v "$1" >/dev/null 2>&1; }

case "$rel" in
  engine/*.py | services/*.py)            # Python: ruff (assumed in the uv dev deps)
    have uv && uv run --quiet ruff --version >/dev/null 2>&1 || exit 0
    uv run --quiet ruff format "$f" >/dev/null 2>&1
    out=$(uv run --quiet ruff check --fix "$f" 2>&1) || fail "$out"
    ;;
  supabase/functions/*.ts)                # Edge Functions: Deno
    have deno || exit 0
    deno fmt --quiet "$f" >/dev/null 2>&1
    out=$(deno lint "$f" 2>&1) || fail "$out"
    ;;
  packages/*.ts | packages/*.tsx | mcp/*.ts | web/*.ts | web/*.tsx | demo/*.ts | demo/*.tsx)
    have pnpm || exit 0                   # TypeScript workspace: Prettier assumed; swap for Biome if used
    pnpm exec prettier --write "$f" >/dev/null 2>&1
    ;;
  android/*.kt | android/*.kts)           # Kotlin: ktlint if installed; Gradle lint runs in /ship
    have ktlint || exit 0
    out=$(ktlint -F "$f" 2>&1) || fail "$out"
    ;;
esac
exit 0
