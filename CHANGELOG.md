# Änderungsprotokoll

Versionsschema: `0.<Update-Nummer>`. Die installierte Version steht in `~/beyondflow/VERSION` und unten im Studio.
Änderungen an Flows, die im Studio gespeichert werden, protokolliert BeyondFlow zusätzlich in `~/beyondflow/history.git`
(`git --git-dir=~/beyondflow/history.git log --stat`).

## 0.21 – 25.11.2026 – Reiter „SAP-Gateway“ · **Gateway-Update nötig**
- Studio: neuer Reiter **SAP-Gateway** mit Ampel für Dienst, Gateway-Prozess (inkl. SAP-Verbindung), Auftrags-Warteschlange im CRM und die beteiligten Flows; aktualisiert sich alle 15 Sekunden.
  - **Anhalten / Fortsetzen:** Das Gateway lehnt Aufträge mit HTTP 503 ab, BeyondFlow wiederholt sie später. Der Zustand überlebt einen Neustart (`~/sapgateway/PAUSED`).
  - **Stoppen / Starten / Neu starten** des Dienstes über einen eingeschränkten SSH-Zugang (Benutzer `bfctl`, erzwungener Befehl, nur von der BeyondFlow-VM, `sudo` nur für diese drei `systemctl`-Aufrufe). Mit Rückfrage; jede Aktion wird protokolliert.
- SAP-Gateway: `GET /admin/status` (Laufzeit, SAP-Ping, Zähler, letzter Erfolg/Fehler), `POST /admin/pause|resume`. `SuccessExitStatus=143`, damit ein Stopp nicht als „failed“ erscheint.
- Studio-API: `GET /api/sapgw/status`, `POST /api/sapgw/pause|resume|service/{start|stop|restart}`.
- `CX_BF_CHECK`: neuer Typ `sapqueue` (Zähler und letzte 8 Einträge der SAP-Outbox).
- Installer: erzeugt den Steuerschlüssel `~/.ssh/bf_sapctl`. Gateway-Installer: richtet `bfctl` ein.
- Log aufgeräumt:
  - `sap-status-to-crm` prüft vorher `/health` und überspringt den Lauf mit einer Warnung, statt bei gestopptem Gateway pro Auftrag Fehler zu schreiben.
  - `sf-opportunities-to-crm` schreibt die Meldung des CRM lesbar ins Log („… kein CRM-Auftrag, neuer Versuch beim nächsten Abgleich“).
  - Assistent: erkennt diese Meldungen; ServiceNow-Wiederholungen nach 5xx zählen nicht als Fehler.

## 0.20 – 25.11.2026 – ServiceNow: nur Änderungen, Wiederholung bei 5xx
- `snow-accounts-to-crm` und `snow-contacts-to-crm` holen nur Datensätze, die in den letzten 15 Minuten geändert wurden.
  - Abfrage: `sys_updated_onRELATIVEGE@minute@ago@15`, ohne Klammern, damit `RAW()` nicht bricht.
  - Der erste Lauf nach jedem Start gleicht weiterhin die letzten 50 Datensätze vollständig ab. Dafür hat der Timer `includeMetadata=true`, sonst gibt es keinen Laufzähler.
  - Bisher wurden bei jedem Lauf alle 50 neu ins CRM geschrieben.
- Automatisches Update: Es merkt sich den zuletzt erfolgreich installierten Stand in `.installed/commit`. Schlägt eine Installation fehl, wird sie beim nächsten Timer-Lauf wiederholt. Bisher blieb sie unbemerkt liegen.
- Konnektor `servicenow-query`: Bei HTTP 5xx wird nach 3 Sekunden einmal wiederholt. Hintergrund: sporadische 500er der Entwickler-Instanz im Test.

## 0.19 – 25.11.2026 – Prüfabfragen ins CRM
- Paket `CX_BF_CHECK` (`db/70_cx_bf_check.sql`): feste, nur lesende Abfragen als JSON.
  - Typen: `auftrag`, `account`, `kontakt`, `extern`.
  - Liefert Auftrag samt Positionen, Externen IDs und SAP-Outbox, Account und Kontakt mit Zuordnung sowie die Auflösung externer IDs.
- Flow `crm-check`: `GET /check/{typ}/{key}`, nur lokal auf 127.0.0.1:8091.
- Studio-API: `GET /api/crm/check/{typ}/{key}`.
- Tests lassen sich damit ohne Datenbank-Anmeldung gegenprüfen. Das Datenbankpasswort bleibt auf der VM.

## 0.18 – 25.11.2026 – Ereignis-Flow korrigiert, Flow-Updates
- `event-sf-opportunity`: Der Filter liest die Phase direkt aus dem Ereignis-Body.
  - Camel liefert den `ChangeEventHeader` nicht im Body. Deshalb brach jedes CDC-Ereignis mit `RuntimeBeanExpressionException` ab.
  - Gefunden im ersten echten Test: Opportunity „Test E2E Ereignis 25.11.“.
- Installer: Flows, die seit dem letzten Einspielen lokal unverändert sind, werden auf den neuen Repo-Stand aktualisiert.
  - Bisher wurden alle abweichenden Flows behalten. Im Studio geänderte Flows bleiben weiterhin erhalten.
  - Der zuletzt eingespielte Stand liegt in `~/beyondflow/.installed/`.

## 0.17 – 25.11.2026 – Datenbank-Skripte vollständig
- Aus der Referenz-DB exportiert:
  - `db/10_beyondflow_user.sql` (Profil, Benutzer)
  - `20_cx_bf_api.sql`
  - `30_cx_sap_s4.sql` (Paket und drei Sichten)
  - `60_grants.sql`
- Alle Pakete und Sichten in `db/` sind per Prüfsumme mit der Referenz-DB abgeglichen.
- Das automatische Update überspringt den Installer, wenn sich nur `docs/`, `db/` oder `*.md` geändert haben. So entsteht keine Unterbrechung der Laufzeiten.

## 0.16 – 25.11.2026 – Automatisches Update
- Timer `beyondflow-update.timer` prüft alle 10 Minuten GitHub (Branch `main`).
- Bei neuen Commits: `git merge --ff-only`, dann `install/install.sh`. Protokoll in `logs/update.log`.
- `install.sh` läuft ohne Terminal nicht-interaktiv:
  - Fehlt ein Pflicht-Geheimnis, bricht er mit einem Hinweis ab, statt zu hängen.
  - Fehlende optionale Geheimnisse überspringt er.
- Die Prüfung des Ereignis-Eingangs erkennt `[::ffff:127.0.0.1]:8091`.
- Abbruch in Schritt 7 behoben, der auftrat, wenn `events.properties` schon existierte.

## 0.15 – 24.11.2026 – Reproduzierbare Installation
- Alles in diesem Repository: Quellen, Flows, Units, Konfigurationsvorlagen, DB-Skripte, Anleitung.
- `install/install.sh`: ein idempotenter Installer für Erstinstallation und Update.
  - Prüft Pakete, installiert JBang und **Camel 4.22.0 (festgeschrieben)**.
  - Holt Kaoto und schreibt den Image-Digest nach `~/beyondflow/KAOTO_DIGEST`.
  - Fragt fehlende Geheimnisse verdeckt ab und deaktiviert Flows nicht angebundener Systeme.
- Kundenwerte stehen nur noch in `~/beyondflow.conf`.
  - Die ServiceNow-Instanz ist jetzt die Eigenschaft `bf.snow.instance`; vorher war sie fest eingetragen.
  - Der Betriebsassistent liest die Instanz von dort.
- SAP-Gateway:
  - Host und SAProuter sind nicht mehr im Code vorbelegt; der Installer fragt sie ab.
  - Der SAProuter ist optional.
  - Neues Installationsskript `sap-gateway/install-sap-gateway.sh`.
- Ereignis-Laufzeit:
  - protobuf-java 4.34.2 als Abhängigkeit. Behebt den Absturz in einer Schleife mit `ClassNotFoundException …RuntimeVersion` und 164 % CPU.
  - RestartSec 60.
- Windows-Skript `tools/BeyondFlow-Studio.cmd` für den SSH-Tunnel mit automatischem Wiederaufbau.

## 0.14 – Ereignismodell
- Salesforce Change Data Capture für Opportunities in einer eigenen Ereignis-Laufzeit (`beyondflow-events`).
  - Eigener Prozess, weil ein Salesforce-Anmeldefehler sonst die Hauptlaufzeit stoppt.
- Ereignis-Eingang `127.0.0.1:8091` (nur lokal): ein Ereignis stößt den Abgleich sofort an, die Timer bleiben als Rückfallebene.
- Abgleiche laufen seriell über SEDA (`concurrentConsumers=1`).

## 0.13 – CPU und Speicher je Prozess
- Reiter Laufzeit zeigt CPU und RSS für API, Laufzeit, Ereignis-Laufzeit und nginx.

## 0.12 – Versionsanzeige
- Die installierte Version steht unten im Studio (`/api/version`).

## 0.11 – Betriebsassistent
- Reiter Assistent: Diagnose der Logs nach Regeln.
- Salesforce, SAP und ServiceNow lassen sich prüfen, pausieren und fortsetzen.
- Optional eine KI-Zusammenfassung (Claude API, `BF_AI_KEY`).

## 0.10 – Salesforce-Anbindung
- Accounts in beide Richtungen, Kontakte von Salesforce ins CRM.
- Kette Opportunity „Closed Won“ → CRM-Auftrag → SAP; der Status kommt zurück.
- Paket `CX_BF_SFDC`.
- Fehlerbehebungen:
  - ORA-40573: JSON-Methoden werden nicht mehr in SQL aufgerufen.
  - jq-Split `".[]"` → `"."`: Arrays mit einem Element wurden falsch zerlegt.

## 0.9 – Drittsoftware & Lizenzen
- Lizenzseite im Studio (Apache License 2.0, NOTICE Apache Camel).

## 0.8 – Kaoto-Logo
- Kaoto-Logo im Designer ausgeblendet.

## 0.7 – Schutz der Konnektoren
- Konnektoren, die noch von aktiven Flows benutzt werden, lassen sich nicht deaktivieren (HTTP 409).

## 0.6 – Filter, ServiceNow-Fehler
- Filter nach Flow-ID und Status in Log und Laufzeit.
- ServiceNow-Fehler erscheinen als WARN mit der Antwort statt als Stacktrace.

## 0.5 – Studio-Menü
- Menü: Designer | Mapping | Laufzeit | Log.

## 0.4 – SAP-Anbindung
- SAP-Gateway (JCo → REST) auf einer eigenen x86-VM.
- Flows `crm-orders-to-sap` und `sap-status-to-crm`; Paket `CX_BF_SAP`.

## 0.3 – Deploy per Neustart
- Deploy startet die Laufzeit neu statt Hot-Reload. Beim Hot-Reload gingen die Konnektor-Vorlagen verloren („Cannot find Kamelet“).

## 0.2 – Konnektoren und Mapping
- Konnektoren als Kamelets in der Kaoto-Palette.
- Mapping-Editor mit Profilen für ServiceNow, SAP und Salesforce.

## 0.1 – Betrieb
- Die git-Historie liegt außerhalb des Flow-Ordners.
- Logdatei `logs/runtime.log`; deaktivierte Flows in `disabled/`.

## 0.0 – Grundinstallation
- Kaoto-Designer, Studio, API und Camel-JBang-Laufzeit auf einer OCI-ARM-VM.
- Anbindung ServiceNow → BeyondCRM (Accounts, Kontakte).
