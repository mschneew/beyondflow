#!/bin/bash
# BeyondFlow – automatisches Update (vom Timer beyondflow-update.timer aufgerufen)
# Holt neue Commits von GitHub (Branch main) und fuehrt dann install/install.sh aus.
# Merkt sich den zuletzt ERFOLGREICH installierten Stand (~/beyondflow/.installed/commit):
# schlaegt eine Installation fehl, wird sie beim naechsten Lauf wiederholt.
# Reine Doku-/DB-Aenderungen (docs/, db/, *.md) werden nur geholt, ohne Installer-Lauf.
# Protokoll: ~/beyondflow/logs/update.log
set -uo pipefail
SRC=$(cd "$(dirname "$0")/.." && pwd)
LOG=$HOME/beyondflow/logs/update.log
DONE=$HOME/beyondflow/.installed/commit
cd "$SRC" || exit 1
mkdir -p "$(dirname "$DONE")"
git fetch -q origin main || { echo "$(date '+%F %T') git fetch fehlgeschlagen" >> "$LOG"; exit 1; }
HEAD_OLD=$(git rev-parse HEAD); NEW=$(git rev-parse origin/main)
[ -f "$DONE" ] || echo "$HEAD_OLD" > "$DONE"   # erster Lauf: bisheriger Stand gilt als installiert
LAST=$(cat "$DONE")
[ "$LAST" = "$NEW" ] && [ "$HEAD_OLD" = "$NEW" ] && exit 0
{
  echo "================ $(date '+%F %T') Update ${LAST:0:7} -> ${NEW:0:7}"
  if [ "$HEAD_OLD" != "$NEW" ]; then
    git log --oneline "$HEAD_OLD..$NEW"
    git merge -q --ff-only origin/main || { echo "Update FEHLER: lokale Aenderungen in $SRC verhindern git merge"; exit 1; }
  fi
  CHANGED=$(git diff --name-only "$LAST" "$NEW" 2>/dev/null || echo "unbekannt")
  if [ -z "$(echo "$CHANGED" | grep -vE '^(docs/|db/|servicenow/)|\.md$')" ]; then
    echo "Nur Dokumentation/DB-Skripte geaendert – kein Installer-Lauf, keine Unterbrechung"
    echo "$NEW" > "$DONE"; exit 0
  fi
  # Der neue Installer kann sich selbst geaendert haben – deshalb erst nach dem Merge starten
  if bash install/install.sh </dev/null; then
    echo "$NEW" > "$DONE"; echo "Update OK: $(cat VERSION)"
  else
    echo "Update FEHLER (Details oben) – naechster Versuch beim naechsten Timer-Lauf"
  fi
} >> "$LOG" 2>&1
