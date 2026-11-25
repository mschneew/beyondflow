# BeyondFlow

BeyondFlow ist eine schlanke Integrationsplattform („Mini-MuleSoft“). Sie verbindet BeyondCRM mit Salesforce, ServiceNow und SAP S/4HANA.

- **Designer:** Kaoto im Browser, Flows als Apache-Camel-YAML.
- **Laufzeit:** Apache Camel 4.22 über JBang, als systemd-Benutzerdienst.
- **Studio:** Menü Designer | Mapping | Laufzeit | Log | Assistent, dazu eine kleine API, die Flows als Dateien und mit git-Historie verwaltet.
- **SAP:** angebunden über ein eigenes SAP-Gateway (RFC/JCo → REST) auf einer x86-VM.

```
Arbeitsplatz ──SSH-Tunnel──► BeyondFlow-VM (OCI, ARM)
                              ├─ beyondflow-studio   nginx (Podman)   127.0.0.1:8080
                              ├─ beyondflow-api      Java             127.0.0.1:8090
                              ├─ beyondflow-runtime  Camel            127.0.0.1:8091 (Ereignis-Eingang)
                              └─ beyondflow-events   Camel            Salesforce CDC
                                     │
          ┌──────────────────────────┼──────────────────────┬──────────────────┐
   Oracle ADB (BeyondCRM)      Salesforce (HTTPS)    ServiceNow (HTTPS)   SAP-Gateway-VM (x86, privat :8095)
                                                                                └─ RFC ─► SAP S/4HANA
```

## Inhalt

| Ordner | Inhalt |
|---|---|
| `install/` | `install.sh` – Installation und Update (idempotent) |
| `config/` | `beyondflow.conf.example` (Kundenparameter), Vorlagen für Properties und Geheimnisse |
| `systemd/` | Benutzer-Units der vier Dienste |
| `runtime/flows/` | Flows und Konnektoren der Hauptlaufzeit |
| `runtime/events/` | Flows der Ereignis-Laufzeit (Salesforce CDC) |
| `runtime/reference/` | Referenz-Flows (frühere Wege, Beispiele) – immer deaktiviert installiert |
| `api/`, `studio/`, `nginx/`, `kaoto/` | Studio-Backend, Oberfläche, Webserver-Konfiguration, Kaoto-Paletten-Patch |
| `sap-gateway/` | SAP-Gateway (Quelle und Installer) |
| `db/` | SQL-Skripte für das CRM-Schema, in Nummernreihenfolge |
| `tools/` | Windows-Skript für den Studio-Tunnel |
| `docs/INSTALL.md` | **Schritt-für-Schritt-Anleitung für eine Kundeninstallation** |
| `docs/referenz/` | Projektdokumente der Referenzinstallation: Übersicht, technische Benutzer, Testprotokoll, SAP-Konzept |
| `CHANGELOG.md` | Was sich wann geändert hat |

## Updates

Ein Commit auf `main` wird automatisch eingespielt: Die VM prüft alle 10 Minuten (Timer `beyondflow-update.timer`, Protokoll `logs/update.log`). Im Studio geänderte Flows bleiben erhalten.

Neuinstallation beim Kunden: [docs/INSTALL.md](docs/INSTALL.md).

## Regeln für dieses Repository

- **Keine Geheimnisse einchecken.** Passwörter, Keys, Tokens und das Wallet liegen nur auf der VM:
  - `~/beyondflow/config/secrets.env`, `~/beyondflow/wallet/`
  - auf der SAP-VM: `/etc/sap-bridge.env`
- **Jede Änderung an der Installation läuft über dieses Repository:** commit, dann `install.sh` auf der VM. Eintrag in `CHANGELOG.md`, Version in `VERSION` erhöhen.
- **Im Studio geänderte Flows zurück ins Repository holen:** `scp opc@<vm>:beyondflow/flows/<id>.camel.yaml runtime/flows/` und committen.
