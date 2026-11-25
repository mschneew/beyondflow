#!/bin/bash
# BeyondFlow – automatisches Update (vom Timer beyondflow-update.timer aufgerufen)
# Holt neue Commits von GitHub (Branch main) und fuehrt dann install/install.sh aus.
# Ohne neue Commits passiert nichts. Protokoll: ~/beyondflow/logs/update.log
set -uo pipefail
SRC=$(cd "$(dirname "$0")/.." && pwd)
LOG=$HOME/beyondflow/logs/update.log
cd "$SRC" || exit 1
git fetch -q origin main || { echo "$(date '+%F %T') git fetch fehlgeschlagen" >> "$LOG"; exit 1; }
OLD=$(git rev-parse HEAD); NEW=$(git rev-parse origin/main)
[ "$OLD" = "$NEW" ] && exit 0
{
  echo "================ $(date '+%F %T') Update ${OLD:0:7} -> ${NEW:0:7}"
  git log --oneline "$OLD..$NEW"
  if git merge -q --ff-only origin/main; then
    # Der neue Installer kann sich selbst geaendert haben – deshalb erst nach dem Merge starten
    bash install/install.sh </dev/null && echo "Update OK: $(cat VERSION)" || echo "Update FEHLER (Details oben)"
  else
    echo "Update FEHLER: lokale Aenderungen in $SRC verhindern git merge"
  fi
} >> "$LOG" 2>&1
