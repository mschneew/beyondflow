# BeyondFlow – Integrationsplattform nach MuleSoft-Vorbild

## Ziel
Grafisches Entwicklungswerkzeug plus Laufzeit-Backend, um BeyondCRM, Salesforce, ServiceNow und SAP S/4HANA zu integrieren. Wird als LinkedIn-Serie (nach „CRM Teil 3“) dokumentiert.

## Repository und Nachvollziehbarkeit (ab 0.15)
- Privates GitHub-Repo **mschneew/beyondflow** – Quelle der Wahrheit für jede Installation und Änderung
- `install/install.sh` (idempotent: Erstinstallation und Update), Kundenparameter in `~/beyondflow.conf`, Geheimnisse nur in `~/beyondflow/config/secrets.env`
- **Automatisches Update (0.16):** Timer `beyondflow-update.timer` auf der VM prüft alle 10 Min. `main`, holt neue Commits per Deploy-Key (nur lesend) und startet den Installer; reine Doku-/DB-Änderungen ohne Neustart. Protokoll `~/beyondflow/logs/update.log`
- Kundenanleitung `docs/INSTALL.md`, Änderungsprotokoll `CHANGELOG.md`, Version in `VERSION`
- DB-Skripte `db/10`–`60` vollständig, per Prüfsumme mit der Referenz-DB abgeglichen (25.11.2026)
- Flow-Änderungen aus dem Studio zusätzlich in `~/beyondflow/history.git`

## Entscheidungen
- Name: **BeyondFlow** (MuleSoft nur als Vergleich nennen, nicht im Titel)
- Designer im **Browser**: Kaoto (Web-App) auf Basis Apache Camel
- Logo: Design-Canvas „BeyondFlow Logo“ (https://claude.ai/artifact/2VitxetRzwkVVtqh28V7Qi)
- Deploy per Laufzeit-Neustart statt Hot-Reload
- SAP: JCo auf der x86-VM sap-bridge als **SAP-Gateway** (REST, nur privates Netz, Token); BeyondFlow steuert den Ablauf
- Salesforce CDC in eigener Ereignis-Laufzeit (`beyondflow-events`), weil ein Anmeldefehler sonst die Hauptlaufzeit stoppt

## Architektur
| Baustein | MuleSoft-Entsprechung | Umsetzung |
|---|---|---|
| Designer | Anypoint Studio / Design Center | Kaoto Web + BeyondFlow Studio (Designer, Mapping, Laufzeit, Log, Assistent) |
| Laufzeit | Mule Runtime | Apache Camel 4.22 via Camel JBang; Ereignis-Laufzeit für Salesforce CDC |
| Konnektoren | Anypoint Connectors | Kamelets für BeyondCRM, ServiceNow, SAP, Salesforce |
| SAP-Zugang | SAP Connector | SapGateway.java auf sap-bridge (Java 8, JCo), Port 8095, Header X-BF-Token |
| Mapping | DataWeave | jq-Schritt „Mapping …“, grafisch im Studio |
| Monitoring | Runtime Manager | Studio: Laufzeit (Routen, CPU/RAM je Prozess), Log, Betriebsassistent mit optionaler KI-Zusammenfassung |
| Secrets | Secure Properties | ~/beyondflow/config/secrets.env (600); /etc/sap-bridge.env auf der SAP-VM |

## Referenzinstallation
- Instanz `instance-20261123-2027`: VM.Standard.A1.Flex, 2 OCPU / 12 GB, Oracle Linux 9 aarch64, VCN 10.0.0.0/16
- Datenbank: Autonomous DB `siebelsb` (Frankfurt, Always Free), Database Actions per ADMIN
- systemd-User-Dienste: `beyondflow-studio` (8080), `beyondflow-api` (8090), `beyondflow-runtime` (Ereignis-Eingang 127.0.0.1:8091), `beyondflow-events`, `beyondflow-update.timer`
- Stand 25.11.2026: 0.16 installiert, 0.17 kommt per Auto-Update

## Fahrplan
1. ✅ Hub aufsetzen
2. ✅ CRM ↔ ServiceNow
3. ✅ SAP: Gateway, Auftragsanlage, Statusrückmeldung
4. ✅ Salesforce inkl. Ereignismodell CDC
5. ✅ Reproduzierbare Installation (Repo, Installer, Anleitung, DB-Skripte, Auto-Update)
6. Geschützter Zugang (TLS + Login) statt SSH-Tunnel

## Offene Kleinigkeiten
- CDC in Salesforce für Opportunity aktivieren und echten Ereignistest machen; Opportunity-Felder ins Page Layout
- Im Studio geänderte Flows („abweichend“) ins Repo zurückholen
- SAP-Statusfelder nach erster Lieferung/Faktura prüfen
- Studio-Werkzeugleiste bricht in schmalen Fenstern unschön um
