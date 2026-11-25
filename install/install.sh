#!/bin/bash
# BeyondFlow – Installation und Update (idempotent)
#
# Auf der BeyondFlow-VM (Oracle Linux 9, ARM oder x86) als normaler Benutzer mit sudo-Recht ausfuehren:
#
#   git clone <repo> ~/beyondflow-src && cd ~/beyondflow-src
#   cp config/beyondflow.conf.example ~/beyondflow.conf   # anpassen
#   bash install/install.sh                                # Erstinstallation oder Update
#
# Optionen:
#   --conf DATEI       andere Parameterdatei (Standard: ~/beyondflow.conf)
#   --replace-flows    geaenderte Flows aus dem Repo ueberschreiben (Standard: nur fehlende Flows ergaenzen,
#                      im Studio geaenderte Flows bleiben erhalten; Abweichungen werden angezeigt)
#   --update-kaoto     Kaoto-Oberflaeche neu aus dem Container-Image holen
#   --no-restart       Dienste nicht neu starten
#
# Geheimnisse werden nur abgefragt, wenn sie in ~/beyondflow/config/secrets.env fehlen (verdeckte Eingabe).
set -euo pipefail
trap 'echo; echo "ABBRUCH in install.sh Zeile $LINENO (Befehl: $BASH_COMMAND)"' ERR

SRC=$(cd "$(dirname "$0")/.." && pwd)
VERSION=$(cat "$SRC/VERSION")
CONF=$HOME/beyondflow.conf
REPLACE_FLOWS=0; UPDATE_KAOTO=0; RESTART=1
while [ $# -gt 0 ]; do
  case "$1" in
    --conf) CONF=$2; shift ;;
    --replace-flows) REPLACE_FLOWS=1 ;;
    --update-kaoto) UPDATE_KAOTO=1 ;;
    --no-restart) RESTART=0 ;;
    *) echo "Unbekannte Option: $1"; exit 1 ;;
  esac; shift
done

B=$HOME/beyondflow
USER=${USER:-$(id -un)}
U=$HOME/.config/systemd/user
step() { echo; echo "== $* =="; }

[ -f "$CONF" ] || { echo "Parameterdatei fehlt: $CONF (Vorlage: config/beyondflow.conf.example)"; exit 1; }
# shellcheck disable=SC1090
source "$CONF"
: "${BF_DB_TNS:?BF_DB_TNS fehlt in $CONF}" "${BF_DB_USER:?BF_DB_USER fehlt in $CONF}"
BF_SF_URL=${BF_SF_URL:-}; BF_SNOW_INSTANCE=${BF_SNOW_INSTANCE:-}; BF_SAP_GATEWAY=${BF_SAP_GATEWAY:-}
BF_CAMEL_VERSION=${BF_CAMEL_VERSION:-4.22.0}
BF_KAOTO_IMAGE=${BF_KAOTO_IMAGE:-quay.io/kaotoio/kaoto-app:main}
BF_WALLET_ZIP=${BF_WALLET_ZIP:-}
BF_WALLET_ZIP=${BF_WALLET_ZIP/#\~/$HOME}

echo "BeyondFlow $VERSION – Installation nach $B"
[ -f $B/VERSION ] && echo "bisher installiert: $(cat $B/VERSION)"

# ---------------------------------------------------------------- 1. Betriebssystem-Pakete
step "1/9 Pakete"
PKGS=""
command -v java >/dev/null && java -version 2>&1 | grep -qE 'version "(2[1-9]|[3-9][0-9])' || PKGS="$PKGS java-21-openjdk-devel"
for p in git podman python3 unzip curl; do command -v $p >/dev/null || PKGS="$PKGS $p"; done
if [ -n "$PKGS" ]; then sudo dnf install -y $PKGS; else echo "vorhanden"; fi
sudo loginctl enable-linger "$USER"   # Benutzerdienste laufen auch ohne Anmeldung

# ---------------------------------------------------------------- 2. JBang + Camel (Version festgeschrieben)
step "2/9 JBang und Apache Camel $BF_CAMEL_VERSION"
export PATH=$HOME/.jbang/bin:$PATH
if ! command -v jbang >/dev/null; then
  curl -Ls https://sh.jbang.dev | bash -s - app setup
fi
if ! camel --version 2>/dev/null | grep -q "$BF_CAMEL_VERSION"; then
  jbang app install --force -Dcamel.jbang.version="$BF_CAMEL_VERSION" camel@apache/camel
fi
camel --version | tail -1

# ---------------------------------------------------------------- 3. Programmdateien
step "3/9 Programmdateien"
mkdir -p $B/{flows,disabled,events,logs,config,api,studio,nginx,kaoto,wallet,.installed} $U
cp "$SRC/api/BeyondFlowApi.java" $B/api/
cp -r "$SRC/studio/." $B/studio/
cp "$SRC/nginx/default.conf" $B/nginx/
cp "$SRC/kaoto/patch_kamelets.py" $B/kaoto/
chmod -R a+rX $B/studio $B/nginx
echo "API, Studio, nginx, Kaoto-Patch kopiert"

# ---------------------------------------------------------------- 4. Flows
step "4/9 Flows"
system_of() { # zu welchem System gehoert ein Flow? (leer = immer aktiv)
  case "$1" in
    connector-salesforce|sf-*|*-to-sf|event-sf-*) echo SF ;;
    connector-servicenow|snow-*) echo SNOW ;;
    connector-sap|*-to-sap|sap-*) echo SAP ;;
  esac
}
configured() { case "$1" in SF) [ -n "$BF_SF_URL" ] ;; SNOW) [ -n "$BF_SNOW_INSTANCE" ] ;; SAP) [ -n "$BF_SAP_GATEWAY" ] ;; *) true ;; esac; }
install_flow() { # install_flow QUELLE ZIELORDNER
  local f=$1 dir=$2 id; id=$(basename "$f" .camel.yaml)
  local sys; sys=$(system_of "$id")
  local cur=""
  [ -f "$dir/$id.camel.yaml" ] && cur="$dir/$id.camel.yaml"
  [ -z "$cur" ] && [ -f "$B/disabled/$id.camel.yaml" ] && cur="$B/disabled/$id.camel.yaml"
  local base="$B/.installed/$id.camel.yaml"   # Stand, den der Installer zuletzt eingespielt hat
  if [ -z "$cur" ]; then
    if configured "$sys"; then cp "$f" "$dir/"; echo "  neu:          $id"
    else cp "$f" "$B/disabled/"; echo "  deaktiviert:  $id (System $sys nicht konfiguriert)"; fi
  elif ! cmp -s "$f" "$cur"; then
    if [ $REPLACE_FLOWS = 1 ]; then cp "$f" "$cur"; echo "  ersetzt:      $id"
    elif [ -f "$base" ] && cmp -s "$base" "$cur"; then
      cp "$f" "$cur"; echo "  aktualisiert: $id"          # lokal unveraendert -> neuen Repo-Stand uebernehmen
    else echo "  abweichend:   $id (im Studio geaendert – behalten; --replace-flows zum Ueberschreiben)"; fi
  fi
  # Grundlage fuer das naechste Update merken, aber nur wenn der installierte Stand dem Repo entspricht
  if [ -n "$cur" ] && cmp -s "$f" "$cur"; then cp "$f" "$base"; fi
  if [ -z "$cur" ]; then cp "$f" "$base"; fi
}
for f in "$SRC"/runtime/flows/*.camel.yaml; do install_flow "$f" $B/flows; done
for f in "$SRC"/runtime/events/*.camel.yaml; do install_flow "$f" $B/events; done
for f in "$SRC"/runtime/examples/*.camel.yaml; do
  [ -f "$B/flows/$(basename "$f")" ] || [ -f "$B/disabled/$(basename "$f")" ] || cp "$f" $B/disabled/
done
echo "aktiv: $(ls $B/flows | wc -l), Ereignisse: $(ls $B/events | wc -l), deaktiviert: $(ls $B/disabled | wc -l)"

# ---------------------------------------------------------------- 5. Kaoto-Oberflaeche
step "5/9 Kaoto"
if [ ! -f $HOME/kaoto-web/index.html ] || [ $UPDATE_KAOTO = 1 ]; then
  # Das Kaoto-Image gibt es nur fuer x86; es wird nicht ausgefuehrt, nur die statischen Dateien werden kopiert.
  podman pull --arch amd64 "$BF_KAOTO_IMAGE"
  podman rm -f bf-kaoto-extract >/dev/null 2>&1 || true
  podman create --arch amd64 --name bf-kaoto-extract "$BF_KAOTO_IMAGE" >/dev/null
  rm -rf $HOME/kaoto-web.new && podman cp bf-kaoto-extract:/opt/app-root/src $HOME/kaoto-web.new
  podman rm bf-kaoto-extract >/dev/null
  rm -rf $HOME/kaoto-web && mv $HOME/kaoto-web.new $HOME/kaoto-web
  podman image inspect "$BF_KAOTO_IMAGE" --format '{{.Digest}}' > $B/KAOTO_DIGEST
  echo "Kaoto aus $BF_KAOTO_IMAGE ($(cat $B/KAOTO_DIGEST))"
else
  echo "vorhanden ($(cat $B/KAOTO_DIGEST 2>/dev/null || echo 'Digest unbekannt'))"
fi
python3 $B/kaoto/patch_kamelets.py
chmod -R a+rX $HOME/kaoto-web

# ---------------------------------------------------------------- 6. Datenbank-Wallet
step "6/9 Wallet"
if [ -f $B/wallet/tnsnames.ora ]; then echo "vorhanden"
elif [ -n "$BF_WALLET_ZIP" ] && [ -f "$BF_WALLET_ZIP" ]; then
  unzip -oq "$BF_WALLET_ZIP" -d $B/wallet && chmod 700 $B/wallet && chmod 600 $B/wallet/*
  echo "entpackt aus $BF_WALLET_ZIP"
else echo "FEHLT: Wallet-ZIP (BF_WALLET_ZIP in $CONF)"; exit 1; fi
grep -qi "^$BF_DB_TNS\b" $B/wallet/tnsnames.ora || { echo "TNS-Alias $BF_DB_TNS nicht in tnsnames.ora"; exit 1; }

# ---------------------------------------------------------------- 7. Konfiguration und Geheimnisse
step "7/9 Konfiguration"
render() { sed -e "s|@BF_HOME@|$B|g" -e "s|@BF_DB_TNS@|$BF_DB_TNS|g" -e "s|@BF_DB_USER@|$BF_DB_USER|g" \
  -e "s|@BF_SAP_GATEWAY@|$BF_SAP_GATEWAY|g" -e "s|@BF_SF_URL@|$BF_SF_URL|g" -e "s|@BF_SNOW_INSTANCE@|$BF_SNOW_INSTANCE|g" "$1"; }
merge_props() { # neue Datei anlegen; bestehende nur um fehlende bf.*-Schluessel ergaenzen (manuelle Anpassungen bleiben)
  local tpl=$1 dst=$2
  if [ ! -f "$dst" ]; then render "$tpl" > "$dst"; echo "  angelegt: $(basename "$dst")"; return; fi
  render "$tpl" | { grep -E '^bf\.' || true; } | while IFS= read -r line; do
    grep -q "^${line%%=*}=" "$dst" || { echo "$line" >> "$dst"; echo "  ergaenzt in $(basename "$dst"): ${line%%=*}"; }
  done
}
merge_props "$SRC/config/application.properties.template" $B/config/application.properties
[ -n "$BF_SF_URL" ] && merge_props "$SRC/config/events.properties.template" $B/config/events.properties

S=$B/config/secrets.env
touch $S && chmod 600 $S
secret() { # secret NAME "Frage" [optional]
  grep -q "^$1=." $S && return
  if [ ! -t 0 ]; then [ -n "${3:-}" ] && return; echo "Geheimnis $1 fehlt – install.sh einmal von Hand ausfuehren"; exit 1; fi
  read -rsp "$2${3:+ (Enter = ueberspringen)}: " v; echo
  [ -z "$v" ] && { [ -n "${3:-}" ] && return; echo "Pflichtwert"; exit 1; }
  sed -i "/^$1=/d" $S; printf '%s=%s\n' "$1" "$v" >> $S
}
secret BF_CRM_DB_PASSWORD "Passwort des DB-Benutzers $BF_DB_USER"
if [ -n "$BF_SNOW_INSTANCE" ]; then
  grep -q '^BF_SNOW_USER=.' $S || [ ! -t 0 ] || { read -rp "ServiceNow-Integrationsbenutzer: " v; sed -i '/^BF_SNOW_USER=/d' $S; echo "BF_SNOW_USER=$v" >> $S; }
  secret BF_SNOW_PASSWORD "ServiceNow-Passwort"
fi
if [ -n "$BF_SF_URL" ]; then
  secret BF_SF_CLIENT_ID "Salesforce Consumer Key"
  secret BF_SF_CLIENT_SECRET "Salesforce Consumer Secret"
fi
[ -n "$BF_SAP_GATEWAY" ] && secret BF_SAP_TOKEN "Token des SAP-Gateways (sudo grep GW_TOKEN /etc/sap-bridge.env auf der SAP-VM)"
# Schluessel fuer den eingeschraenkten Steuerzugang zur SAP-VM (Reiter "SAP-Gateway": Starten/Stoppen).
# Bleibt auf dieser VM; auf der SAP-VM darf er nur status|start|stop|restart des Gateway-Dienstes ausloesen.
if [ -n "$BF_SAP_GATEWAY" ] && [ ! -f ~/.ssh/bf_sapctl ]; then
  mkdir -p ~/.ssh && chmod 700 ~/.ssh
  ssh-keygen -q -t ed25519 -N "" -C "beyondflow@$(hostname -I | awk '{print $1}')" -f ~/.ssh/bf_sapctl
  echo "Steuerschluessel fuer das SAP-Gateway erzeugt: ~/.ssh/bf_sapctl.pub (beim Gateway-Installer einfuegen)"
fi
secret BF_AI_KEY "Anthropic API-Key fuer den KI-Assistenten" optional
echo "secrets.env vollstaendig (Werte werden nicht angezeigt)"

# ---------------------------------------------------------------- 8. Dienste
step "8/9 Dienste"
for u in beyondflow-studio beyondflow-api beyondflow-runtime; do cp "$SRC/systemd/$u.service" $U/; done
if [ -n "$BF_SF_URL" ]; then cp "$SRC/systemd/beyondflow-events.service" $U/; EVENTS=1; else EVENTS=0; fi
systemctl --user daemon-reload
systemctl --user enable beyondflow-studio beyondflow-api beyondflow-runtime >/dev/null 2>&1
[ $EVENTS = 1 ] && systemctl --user enable beyondflow-events >/dev/null 2>&1
# Automatisches Update nur, wenn das Repo unter ~/beyondflow-src liegt (Standard laut Anleitung)
if [ "$SRC" = "$HOME/beyondflow-src" ] && [ "${BF_AUTO_UPDATE:-yes}" = yes ]; then
  cp "$SRC/systemd/beyondflow-update.service" "$SRC/systemd/beyondflow-update.timer" $U/
  systemctl --user daemon-reload
  systemctl --user enable --now beyondflow-update.timer >/dev/null 2>&1
  echo "automatisches Update: alle 10 Minuten (Protokoll logs/update.log)"
fi
echo "$VERSION" > $B/VERSION

# Aenderungsprotokoll der Flows (gleiche Historie wie beim Speichern im Studio)
G="git --git-dir=$B/history.git --work-tree=$B"
[ -d $B/history.git ] || $G init -q
$G add -A flows disabled events
$G -c user.name=BeyondFlow -c user.email=beyondflow@localhost commit -q -m "Installation $VERSION" || true

# ---------------------------------------------------------------- 9. Start und Pruefung
step "9/9 Start und Pruefung"
if [ $RESTART = 1 ]; then
  systemctl --user restart beyondflow-studio beyondflow-api beyondflow-runtime
  [ $EVENTS = 1 ] && systemctl --user restart beyondflow-events
  echo "warte auf die Laufzeit ..."; sleep 45
fi
ok=1
chk() { if eval "$2" >/dev/null 2>&1; then echo "  OK    $1"; else echo "  FEHLER $1"; ok=0; fi; }
chk "Studio (127.0.0.1:8080)"     "curl -sf http://127.0.0.1:8080/studio.html"
chk "API (127.0.0.1:8090)"        "curl -sf http://127.0.0.1:8090/api/version"
chk "Laufzeit aktiv"              "systemctl --user is-active beyondflow-runtime"
chk "Ereignis-Eingang 8091"       "ss -ltn | grep -qE '127\.0\.0\.1\]?:8091'"
[ $EVENTS = 1 ] && chk "Ereignis-Laufzeit aktiv" "systemctl --user is-active beyondflow-events"
grep -E "Routes startup|Error starting" $B/logs/runtime.log 2>/dev/null | tail -2 || true
echo
echo "Installiert: BeyondFlow $(curl -s http://127.0.0.1:8090/api/version 2>/dev/null || echo "$VERSION")"
echo "Studio vom Arbeitsplatz: SSH-Tunnel (tools/BeyondFlow-Studio.cmd) und http://localhost:8080/studio.html"
[ $ok = 1 ] || { echo "Es gab Fehler – Log: tail -100 $B/logs/runtime.log"; exit 1; }
