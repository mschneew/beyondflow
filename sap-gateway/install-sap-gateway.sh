#!/bin/bash
# SAP-Gateway fuer BeyondFlow installieren bzw. aktualisieren (idempotent).
# Laeuft auf einer x86-64-VM im selben privaten Netz wie die BeyondFlow-VM (SAP JCo gibt es nicht fuer ARM).
# Voraussetzungen (siehe docs/INSTALL.md, Abschnitt SAP):
#   /opt/java8      JDK 8 (javac + java)
#   /opt/sapjco3    sapjco3.jar + libsapjco3.so  (SAP JCo 3.1, Download nur mit SAP-S-User)
# Aufruf aus dem Repo-Verzeichnis:  bash sap-gateway/install-sap-gateway.sh
set -euo pipefail
SRC=$(cd "$(dirname "$0")" && pwd)
D=$HOME/sapgateway
ENVF=/etc/sap-bridge.env
JAVA=/opt/java8/bin
JCO=/opt/sapjco3/sapjco3.jar

[ -x $JAVA/javac ] || { echo "FEHLT: JDK 8 unter /opt/java8"; exit 1; }
[ -f $JCO ] && [ -f /opt/sapjco3/libsapjco3.so ] || { echo "FEHLT: SAP JCo unter /opt/sapjco3"; exit 1; }

mkdir -p $D/classes
cp "$SRC/SapGateway.java" $D/SapGateway.java
$JAVA/javac -nowarn -cp $JCO -d $D/classes $D/SapGateway.java
echo "kompiliert"

# SAP-Verbindung abfragen (nur fehlende Werte). Das Passwort wird verdeckt eingegeben.
sudo touch $ENVF && sudo chmod 600 $ENVF
ask() { # ask KEY "Frage" [geheim]
  sudo grep -q "^$1=" $ENVF && return
  if [ "${3:-}" = geheim ]; then read -rsp "$2: " v; echo; else read -rp "$2: " v; fi
  printf '%s=%s\n' "$1" "$v" | sudo tee -a $ENVF >/dev/null
}
ask SAP_ASHOST "SAP Application Server (Host/IP)"
ask SAP_SYSNR  "SAP Systemnummer (z.B. 00)"
ask SAP_ROUTER "SAProuter-String (leer, wenn keiner)"
ask SAP_CLIENT "SAP Mandant"
ask SAP_LANG   "Anmeldesprache (z.B. DE)"
ask SAP_USER   "SAP RFC-Benutzer"
ask SAP_PASSWD "Passwort des RFC-Benutzers" geheim
if ! sudo grep -q '^GW_TOKEN=' $ENVF; then
  printf 'GW_TOKEN=%s\nGW_BIND=%s\nGW_PORT=8095\n' "$(openssl rand -hex 24)" "$(hostname -I | awk '{print $1}')" | sudo tee -a $ENVF >/dev/null
fi

sudo tee /etc/systemd/system/sap-gateway.service >/dev/null <<UNIT
[Unit]
Description=SAP-Gateway fuer BeyondFlow (RFC/JCo -> REST)
After=network-online.target
Wants=network-online.target

[Service]
User=$USER
EnvironmentFile=$ENVF
ExecStart=$JAVA/java -Xmx128m -Djava.library.path=/opt/sapjco3 -cp $D/classes:$JCO SapGateway
Restart=on-failure
RestartSec=10

[Install]
WantedBy=multi-user.target
UNIT

if systemctl is-active --quiet firewalld; then
  sudo firewall-cmd --quiet --permanent --add-port=8095/tcp && sudo firewall-cmd --quiet --reload
  echo "Firewall: Port 8095 offen (zusaetzlich OCI Security List, siehe Anleitung)"
fi
sudo systemctl daemon-reload
sudo systemctl enable sap-gateway >/dev/null
sudo systemctl restart sap-gateway
sleep 6
systemctl is-active sap-gateway
BIND=$(sudo grep '^GW_BIND=' $ENVF | cut -d= -f2)
echo "Test: $(curl -s -H "X-BF-Token: $(sudo grep '^GW_TOKEN=' $ENVF | cut -d= -f2)" http://$BIND:8095/health)"
echo
echo "Gateway-Adresse fuer beyondflow.conf (BF_SAP_GATEWAY): http://$BIND:8095"
echo "Token fuer BeyondFlow (BF_SAP_TOKEN) anzeigen:  sudo grep GW_TOKEN $ENVF"
