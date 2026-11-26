#!/bin/bash
# SAP-Gateway: RFC-Passwort (SAP_PASSWD) und Gateway-Token (GW_TOKEN) einmalig aus /etc/sap-bridge.env in OCI Vault uebertragen
# (Secret-Namen SAPGW_SAP_PASSWD, SAPGW_GW_TOKEN) und den Dienst auf Vault-Betrieb umstellen. Werte werden nie angezeigt.
# Aufruf auf der SAP-VM im Ordner mit den Gateway-Dateien:  bash vault-migrate-sap.sh <Vault-OCID> <Key-OCID>
set -euo pipefail
VAULT=${1:?Vault-OCID fehlt}; KEY=${2:?Key-OCID fehlt}
SRC=$(cd "$(dirname "$0")" && pwd)
ENVF=/etc/sap-bridge.env
python3 -c 'import oci' 2>/dev/null || { echo "installiere OCI-SDK fuer Python ..."; sudo dnf install -y python39-oci-sdk >/dev/null 2>&1 || sudo python3 -m pip install -q oci; }
sudo install -d -m 755 /usr/local/lib/sapgw
sudo install -m 755 "$SRC/bf-vault.py" /usr/local/lib/sapgw/bf-vault.py
V="sudo python3 /usr/local/lib/sapgw/bf-vault.py"

echo "== 1/4 Zugriff pruefen"
$V check --vault "$VAULT" --prefix SAPGW_
if sudo grep -qE '^(SAP_PASSWD|GW_TOKEN)=.' $ENVF; then
  echo "== 2/4 uebertragen"
  $V migrate --vault "$VAULT" --key "$KEY" --env $ENVF --only SAP_PASSWD,GW_TOKEN --name-prefix SAPGW_
  echo "== 3/4 Gegenprobe"
  ok=0
  for i in 1 2 3 4 5 6; do
    $V fetch --vault "$VAULT" --prefix SAPGW_ --strip SAPGW_ --out /run/sap-gateway/check.env >/dev/null
    if sudo diff -q <(sudo grep -E '^(SAP_PASSWD|GW_TOKEN)=' $ENVF | sort) <(sudo sort /run/sap-gateway/check.env) >/dev/null; then ok=1; break; fi
    sleep 10
  done
  sudo rm -f /run/sap-gateway/check.env
  [ $ok = 1 ] || { echo "Abweichung - NICHT umgestellt. Nach einer Minute erneut starten."; exit 1; }
  echo "Gegenprobe OK"
fi

echo "== 4/4 Dienst umstellen"
printf 'VAULT_OCID=%s\n' "$VAULT" | sudo tee /etc/sap-bridge.vault >/dev/null
sudo install -m 644 "$SRC/sap-gateway-secrets.service" /etc/systemd/system/sap-gateway-secrets.service
sudo install -d /etc/systemd/system/sap-gateway.service.d
sudo tee /etc/systemd/system/sap-gateway.service.d/vault.conf >/dev/null <<'DROPIN'
[Unit]
Requires=sap-gateway-secrets.service
After=sap-gateway-secrets.service

[Service]
EnvironmentFile=/run/sap-gateway/secrets.env
DROPIN
sudo systemctl daemon-reload
sudo systemctl enable sap-gateway-secrets >/dev/null
sudo systemctl restart sap-gateway-secrets sap-gateway
sleep 8
TOKEN=$(sudo sed -n 's/^GW_TOKEN=//p' /run/sap-gateway/secrets.env)
BIND=$(sudo sed -n 's/^GW_BIND=//p' $ENVF)
echo "Test: $(curl -s -H "X-BF-Token: $TOKEN" "http://$BIND:8095/health")"
if sudo grep -qE '^(SAP_PASSWD|GW_TOKEN)=' $ENVF; then
  read -rp "Test OK? Dann SAP_PASSWD und GW_TOKEN jetzt aus $ENVF entfernen (ja/N): " a
  if [ "$a" = ja ]; then sudo sed -i '/^SAP_PASSWD=/d;/^GW_TOKEN=/d' $ENVF; echo "entfernt - beide liegen nur noch im OCI Vault"; fi
fi
