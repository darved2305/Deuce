#!/usr/bin/env bash
# Tiny adb UI driver for device proof runs: tap by visible text / content-desc, never blind coords.
# Usage: ui.sh tap "<regex>" | ui.sh find "<regex>" | ui.sh dump
set -euo pipefail
export MSYS_NO_PATHCONV=1
A="${ADB:-$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe}"
dump() { "$A" shell uiautomator dump /sdcard/elevate_ui.xml >/dev/null 2>&1; "$A" shell cat /sdcard/elevate_ui.xml; }
find_node() { dump | python -c '
import sys, re
pat = re.compile(sys.argv[1])
for m in re.finditer(r"<node [^>]*>", sys.stdin.read()):
    n = m.group(0)
    t = re.search(r"text=\"([^\"]*)\"", n).group(1); d = re.search(r"content-desc=\"([^\"]*)\"", n).group(1)
    b = [int(v) for v in re.findall(r"\d+", re.search(r"bounds=\"([^\"]*)\"", n).group(1))]
    if pat.search(t) or pat.search(d):
        print((b[0]+b[2])//2, (b[1]+b[3])//2, (t or d)[:80]); break
else:
    sys.exit(1)
' "$1"; }
case "$1" in
  dump) dump | python -c 'import sys,re; [print(a or b, c) for a,b,c in re.findall(r"text=\"([^\"]*)\"[^>]*content-desc=\"([^\"]*)\"[^>]*bounds=\"([^\"]*)\"", sys.stdin.read()) if a or b]' ;;
  find) for i in 1 2 3 4 5; do find_node "$2" && exit 0; sleep 1; done; echo "not found: $2" >&2; exit 1 ;;
  tap)
    for i in 1 2 3 4 5; do
      if read -r x y label < <(find_node "$2"); then "$A" shell input tap "$x" "$y"; echo "tapped: $label ($x,$y)"; exit 0; fi
      sleep 1
    done
    echo "not found: $2" >&2; exit 1 ;;
esac
