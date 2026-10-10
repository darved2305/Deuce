#!/usr/bin/env bash
# Backstop for agents that bypass edit hooks (shell writes, Codex): fail if the branch diff
# touches paths outside its lane. Lane = $ELEVATE_LANE, else branch prefix p{n}/, else git config.
# Cross-lane PRs (contract slices): run with ALLOW_CROSS_LANE=1 (CI sets it from a PR label).
set -euo pipefail
root=$(git rev-parse --show-toplevel)
branch="${GITHUB_HEAD_REF:-$(git rev-parse --abbrev-ref HEAD)}"
lane="${ELEVATE_LANE:-}"
if [ -z "$lane" ] && [[ "$branch" =~ ^(p[1-4])/ ]]; then lane="${BASH_REMATCH[1]}"; fi
if [ -z "$lane" ]; then lane=$(git config --get elevate.lane || true); fi
if [ -z "$lane" ]; then echo "check-lane: no lane (name the branch p{n}/slice or set elevate.lane)" >&2; exit 1; fi
[ "${ALLOW_CROSS_LANE:-0}" = "1" ] && { echo "check-lane: cross-lane allowed"; exit 0; }

allowed=$(jq -r --arg l "$lane" '((.[$l] // []) + (.shared // []))[]' "$root/.claude/lanes.json")
bad=0
while IFS= read -r rel; do
  ok=0
  while IFS= read -r p; do
    case "$p" in
      "./*") [[ "$rel" != */* ]] && ok=1 ;;
      */)    [[ "$rel" == "$p"* ]] && ok=1 ;;
      *)     [[ "$rel" == "$p" ]] && ok=1 ;;
    esac
  done <<<"$allowed"
  if [ "$ok" = 0 ]; then echo "outside lane $lane: $rel" >&2; bad=1; fi
done < <(git diff --name-only origin/main...HEAD)
exit "$bad"
