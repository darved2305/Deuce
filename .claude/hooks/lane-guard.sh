#!/usr/bin/env bash
# PreToolUse guard: block Edit/Write outside the developer's lane (exit 2 = block).
# Lane = $ELEVATE_LANE, else `git config --local elevate.lane`. Folder map: .claude/lanes.json
set -uo pipefail

input=$(cat)
file=$(jq -r '.tool_input.file_path // .tool_input.notebook_path // empty' <<<"$input" | tr -d '\r')
cwd=$(jq -r '.cwd // empty' <<<"$input" | tr -d '\r')
[ -z "$file" ] && exit 0

root=$(git -C "${cwd:-.}" rev-parse --show-toplevel 2>/dev/null) || exit 0
lane="${ELEVATE_LANE:-$(git -C "$root" config --get elevate.lane 2>/dev/null || true)}"
if [ -z "$lane" ]; then
  echo "No lane set. Run: git config --local elevate.lane p1|p2|p3|p4  (or export ELEVATE_LANE)." >&2
  exit 2
fi
[ "$lane" = "*" ] && exit 0

case "$file" in /*) abs="$file" ;; *) abs="${cwd:-$root}/$file" ;; esac
case "$abs" in
  "$root"/*) rel="${abs#"$root"/}" ;;
  *) exit 0 ;; # outside the repo: not a lane question (Claude's own permissions still apply)
esac
case "/$rel/" in
  */../*) echo "Blocked: path traversal in '$file'." >&2; exit 2 ;;
esac

allowed=$(jq -r --arg l "$lane" '((.[$l] // []) + (.shared // []))[]' "$root/.claude/lanes.json" | tr -d '\r')
while IFS= read -r p; do
  [ -z "$p" ] && continue
  case "$p" in
    "./*") [[ "$rel" != */* ]] && exit 0 ;;   # any file directly in the repo root
    */)    [[ "$rel" == "$p"* ]] && exit 0 ;; # folder prefix
    *)     [[ "$rel" == "$p" ]] && exit 0 ;;  # exact file
  esac
done <<<"$allowed"

echo "Blocked: '$rel' is outside lane '$lane' (allowed: $(tr '\n' ' ' <<<"$allowed")). Edit only your lane. If the change is needed, ask the owning lane or record it with /decision. Do not route around this guard with shell commands." >&2
exit 2
