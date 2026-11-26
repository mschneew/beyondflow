#!/bin/bash
# BeyondFlow: Geheimnisse einmalig aus ~/beyondflow/config/secrets.env in OCI Vault uebertragen und auf Vault-Betrieb umstellen.
# Voraussetzungen (docs/INSTALL.md, Abschnitt Geheimnisse in OCI Vault): Vault + Software-Schluessel, dynamische Gruppe,
# Richtlinie (fuer diesen Lauf auch "manage secret-family" und "use keys"), BF_VAULT_OCID und BF_VAULT_KEY_OCID in ~/beyondflow.conf.
# Aufruf auf der BeyondFlow-VM:  bash ~/beyondflow-src/install/vault-migrate.sh
# Werte werden nie angezeigt.
set -euo pipefail
SRC=$(cd "$(dirname "$0")/.." && pwd)
B=$HOME/beyondflow; S=$B/config/secrets.env
source "$HOME/beyondflow.conf"
: "${BF_VAULT_OCID:?BF_VAULT_OCID fehlt in ~/beyondflow.conf}" "${BF_VAULT_KEY_OCID:?BF_VAULT_KEY_OCID fehlt in ~/beyondflow.conf}"
V="python3 $SRC/vault/bf-vault.py"
python3 -c 'import oci' 2>/dev/null || { echo "installiere OCI-SDK fuer Python ..."; sudo dnf install -y python39-oci-sdk >/dev/null 2>&1 || { sudo dnf install -y python3-pip >/dev/null 2>&1; python3 -m pip install --user -q oci; }; }

echo "== 1/4 Zugriff auf den Vault pruefen"
$V check --vault "$BF_VAULT_OCID" --prefix BF_

if [ -f "$S" ]; then
  echo "== 2/4 Geheimnisse uebertragen (aus $S)"
  $V migrate --vault "$BF_VAULT_OCID" --key "$BF_VAULT_KEY_OCID" --env "$S"
  echo "== 3/4 Gegenprobe: alles aus dem Vault lesen und mit der Datei vergleichen"
  T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
  ok=0
  for i in 1 2 3 4 5 6; do   # neu angelegte Secrets sind einige Sekunden im Zustand CREATING
    $V fetch --vault "$BF_VAULT_OCID" --prefix BF_ --out "$T/s.env" >/dev/null
    if diff -q <(grep -E '^BF_[A-Za-z0-9_]+=.' "$S" | sort) <(sort "$T/s.env") >/dev/null; then ok=1; break; fi
    sleep 10
  done
  [ $ok = 1 ] || { echo "Abweichung zwischen Datei und Vault - NICHT umgestellt. Nach einer Minute erneut starten."; exit 1; }
  echo "Gegenprobe OK: Vault enthaelt alle $(grep -cE '^BF_[A-Za-z0-9_]+=.' "$S") Geheimnisse"
else
  echo "== 2-3/4 keine lokale secrets.env - nur Umstellung"
fi

echo "== 4/4 auf Vault-Betrieb umstellen"
touch "$B/config/vault-active"
bash "$SRC/install/install.sh" </dev/null
if [ -f "$S" ]; then
  echo
  read -rp "Laeuft alles? Dann jetzt $S endgueltig loeschen (ja/N): " a
  if [ "$a" = ja ]; then shred -u "$S"; echo "geloescht - die Geheimnisse liegen nur noch im OCI Vault"; else echo "behalten - spaeter: shred -u $S"; fi
fi
echo
echo "Zum Schluss in der OCI-Richtlinie die Zeilen mit 'manage secret-family' und 'use keys' entfernen (die VMs brauchen nur noch 'read')."
