# Änderungsprotokoll

Versionsschema: `0.<Update-Nummer>`. Die installierte Version steht in `~/beyondflow/VERSION` und unten im Studio.
Änderungen an Flows, die im Studio gespeichert werden, protokolliert BeyondFlow zusätzlich in `~/beyondflow/history.git`
(`git --git-dir=~/beyondflow/history.git log --stat`).

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
