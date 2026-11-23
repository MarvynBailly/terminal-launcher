#!/usr/bin/env bash
# Draws one look off the phone and tiles the pictures into sheets.
#
#   bash tools/shots.sh <look> [groups]
#
# groups: any of rest wake granted off launch lines rest-moving, with commas. Empty is all of them.
# The sheets land in shots/. With SHOTS_SLOTS set to a folder, at most two of these run at a time
# across every checkout that names the same folder: the rest wait their turn.
set -u
here="$(cd "$(dirname "$0")/.." && pwd)"
cd "$here" || exit 1
look="${1:?which look?}"
groups="${2:-}"

got=""
if [ -n "${SHOTS_SLOTS:-}" ]; then
  mkdir -p "$SHOTS_SLOTS"
  while [ -z "$got" ]; do
    for i in 1 2; do
      if mkdir "$SHOTS_SLOTS/slot$i" 2>/dev/null; then got=$i; break; fi
      # a slot held for a quarter of an hour belongs to a run that died
      if [ -n "$(find "$SHOTS_SLOTS/slot$i" -maxdepth 0 -mmin +15 2>/dev/null)" ]; then rmdir "$SHOTS_SLOTS/slot$i" 2>/dev/null; fi
    done
    [ -z "$got" ] && sleep 5
  done
  trap 'rmdir "$SHOTS_SLOTS/slot$got" 2>/dev/null' EXIT
fi

./gradlew.bat --console=plain testDebugUnitTest "-Pshots=$look" "-PshotsGroups=$groups" --tests "*LookShots*" 2>&1 \
  | grep -E "^e: |FAILED|BUILD|Exception|Caused by|error:" | sed "s#file:///.*/com/terminallauncher/##" | head -40
python tools/look-sheets.py app/build/shots "$look" "$here/shots"
