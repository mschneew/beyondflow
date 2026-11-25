#!/bin/bash
# TLS-Zertifikat fuer den oeffentlichen Ereignis-Eingang holen bzw. erneuern (acme.sh, Let's Encrypt, HTTP-01 ueber Port 80 -> 8089).
# Name im Zertifikat: BF_PUBLIC_NAME aus ~/beyondflow.conf, sonst <ip-mit-bindestrichen>.sslip.io (oeffentlicher DNS-Dienst,
# der den Namen auf die IP aufloest). Kein reines IP-Zertifikat: Let's Encrypt stellt es nur ohne Subject (CN) aus, und
# der HTTP-Client von ServiceNow lehnt solche Zertifikate ab ("badly formatted directory string").
set -uo pipefail
E=$HOME/beyondflow/edge
A=$HOME/.acme.sh/acme.sh
conf() { sed -n "s/^$1=//p" $HOME/beyondflow.conf | tail -1 | sed 's/#.*//' | tr -d "\"' "; }
HOST=$(conf BF_PUBLIC_HOST); NAME=$(conf BF_PUBLIC_NAME)
[ -z "$NAME" ] && [ -n "$HOST" ] && NAME="${HOST//./-}.sslip.io"
echo "== $(date -u '+%F %T') UTC  Zertifikat fuer ${NAME:-?}"
[ -n "$NAME" ] || { echo "BF_PUBLIC_HOST fehlt in ~/beyondflow.conf - nichts zu tun"; exit 0; }
[ -x $A ] || { echo "acme.sh fehlt (install.sh ausfuehren)"; exit 1; }
# fruehere Zertifikate fuer andere Namen (z. B. die reine IP) nicht mehr erneuern
for d in $HOME/.acme.sh/*_ecc; do
  [ -d "$d" ] || continue; n=$(basename "$d" _ecc)
  [ "$n" = "$NAME" ] || { $A --remove -d "$n" --ecc --home $HOME/.acme.sh >/dev/null 2>&1; rm -rf "$d"; echo "altes Zertifikat $n entfernt"; }
done
RELOAD="podman exec bf-edge nginx -s reload"
if [ -f "$HOME/.acme.sh/${NAME}_ecc/${NAME}.cer" ]; then
  $A --cron --home $HOME/.acme.sh 2>&1 | tail -5
else
  $A --issue --server letsencrypt -d "$NAME" --webroot $E/acme --keylength ec-256 --home $HOME/.acme.sh 2>&1 | tail -8 || true
  [ -f "$HOME/.acme.sh/${NAME}_ecc/${NAME}.cer" ] || { echo "Zertifikat noch nicht erhalten - naechster Versuch in 1 Stunde"; exit 0; }
  $A --install-cert -d "$NAME" --ecc --home $HOME/.acme.sh \
     --key-file $E/certs/key.pem --fullchain-file $E/certs/fullchain.pem --reloadcmd "$RELOAD" 2>&1 | tail -3
fi
openssl x509 -noout -subject -enddate -in $E/certs/fullchain.pem
