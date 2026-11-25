#!/bin/bash
# TLS-Zertifikat fuer den oeffentlichen Ereignis-Eingang holen bzw. erneuern.
# Let's Encrypt stellt Zertifikate fuer IP-Adressen nur als Kurzzeit-Zertifikat aus (Profil "shortlived", 160 Stunden);
# erneuert wird nach 3 Tagen. Pruefung per HTTP-01 ueber Port 80 (-> 8089, nur /.well-known/acme-challenge/).
set -uo pipefail
E=$HOME/beyondflow/edge
A=$HOME/.acme.sh/acme.sh
HOST=$(sed -n 's/^BF_PUBLIC_HOST=//p' $HOME/beyondflow.conf | tail -1 | tr -d '"'"'"' ')
echo "== $(date -u '+%F %T') UTC  Zertifikat fuer ${HOST:-?}"
[ -n "$HOST" ] || { echo "BF_PUBLIC_HOST fehlt in ~/beyondflow.conf - nichts zu tun"; exit 0; }
[ -x $A ] || { echo "acme.sh fehlt (install.sh ausfuehren)"; exit 1; }
RELOAD="podman exec bf-edge nginx -s reload"
if [ -f "$HOME/.acme.sh/${HOST}_ecc/${HOST}.cer" ]; then
  $A --cron --home $HOME/.acme.sh 2>&1 | tail -5
else
  $A --issue --server letsencrypt -d "$HOST" --webroot $E/acme --certificate-profile shortlived --days 3 \
     --keylength ec-256 --home $HOME/.acme.sh 2>&1 | tail -8 || true
  [ -f "$HOME/.acme.sh/${HOST}_ecc/${HOST}.cer" ] || { echo "Zertifikat noch nicht erhalten - naechster Versuch in 1 Stunde"; exit 0; }
  $A --install-cert -d "$HOST" --ecc --home $HOME/.acme.sh \
     --key-file $E/certs/key.pem --fullchain-file $E/certs/fullchain.pem --reloadcmd "$RELOAD" 2>&1 | tail -3
fi
openssl x509 -noout -enddate -in $E/certs/fullchain.pem
