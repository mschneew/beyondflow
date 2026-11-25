# BeyondFlow – Technische Benutzer und Zugänge

Stand: 25.11.2026 (Referenzinstallation). Alle Benutzer und Schlüssel, die BeyondFlow für die angebundenen Systeme braucht.

> **In dieses Repository nie Passwörter, Keys oder Tokens eintragen.** Das Repository wird auf die VMs geklont.
> - Die Spalte „Ablage des Passworts“ nennt nur den Ort, z. B. „Passwortmanager, Eintrag BeyondFlow/ServiceNow“.
> - Auf der BeyondFlow-VM stehen die Werte in `~/beyondflow/config/secrets.env` (Rechte 600), auf der SAP-VM in `/etc/sap-bridge.env`.

## Neu für BeyondFlow angelegt

| Zielsystem | Benutzer / Kennung | Funktion | Rechte | Ablage auf der VM | Ablage des Passworts |
|---|---|---|---|---|---|
| **BeyondCRM-Datenbank** (Oracle ADB `siebelsb`, Frankfurt) | `BEYONDFLOW` | Laufzeit-Benutzer: Die Flows lesen und schreiben das CRM ausschließlich über die Pakete. Kein direkter Tabellenzugriff. | `CREATE SESSION`; EXECUTE auf `CX_BF_API`, `CX_SAP_S4`, `CX_BF_SAP`, `CX_BF_SFDC`; SELECT auf `CX_BF_ORDER_V`. Profil `SERVICE_ACCOUNT` (Passwort läuft nicht ab, Sperre nach 10 Fehlversuchen). | `BF_CRM_DB_PASSWORD` | |
| **ServiceNow** (`dev484521`) | `beyondflow.integration` (Name: BeyondFlow Integration) | Liest Accounts (`customer_account`) und Kontakte (`customer_contact`) über die Table API für den Abgleich ServiceNow → CRM. Angelegt am 25.11.2026, ersetzt den bisherigen Zugang über einen persönlichen Benutzer. | Nur Web-Service-Zugriff (keine UI-Anmeldung). Rollen `snc_platform_rest_api_access`, `sn_customerservice_agent`. Beim Kunden besser eine eigene Nur-Lese-Rolle. | `BF_SNOW_USER`, `BF_SNOW_PASSWORD` | |
| **Salesforce** (Org `orgfarm-7813050473-dev-ed`) | External Client App **BeyondFlow** (Consumer Key + Secret) | Anmeldung von BeyondFlow per OAuth Client Credentials. Abgleich von Accounts und Kontakten in beide Richtungen, Opportunity → CRM-Auftrag, Status zurück. Außerdem das Ereignis-Abo (Change Data Capture). | API-Scope `api`. Ausgeführt im Namen des Run-As-Benutzers (nächste Zeile). | `BF_SF_CLIENT_ID`, `BF_SF_CLIENT_SECRET` | |
| **Salesforce** | Run-As-Benutzer der App: *Benutzername hier eintragen* | In dessen Namen schreibt BeyondFlow in Salesforce. Er erscheint als „Zuletzt geändert von“. | Lesen und Schreiben auf Account, Contact, Opportunity; Felder `BeyondCRM_Auftrag__c`, `SAP_Auftrag__c`, `ERP_Status__c`. | — (nur in Salesforce hinterlegt) | |
| **SAP S/4HANA** (über SAP-Gateway) | RFC-Benutzer: *Benutzername hier eintragen* (steht in `/etc/sap-bridge.env`, `SAP_USER`) | Das SAP-Gateway legt damit Kundenaufträge an und fragt deren Status ab. | S_RFC und Vertrieb: `RFC_PING`, `BAPI_SALESORDER_CREATEFROMDAT2`, `BAPI_SALESORDER_GETLIST`, `BAPISDORDER_GETDETAILEDLIST`, `BAPI_TRANSACTION_COMMIT`/`_ROLLBACK` | SAP-VM: `SAP_USER`, `SAP_PASSWD` | |
| **SAP-Gateway** (VM `sap-bridge`, Port 8095, nur privates Netz) | Token (Header `X-BF-Token`) | Schützt das Gateway: Nur BeyondFlow darf Aufträge an SAP schicken. | Zugriff auf alle Gateway-Funktionen | SAP-VM: `GW_TOKEN`; BeyondFlow-VM: `BF_SAP_TOKEN` | |
| **GitHub** (`mschneew/beyondflow`, privat) | Deploy Key `beyondflow-vm` | Die BeyondFlow-VM holt damit alle 10 Minuten Updates aus dem Repository (automatisches Update). | Nur lesend | `~/.ssh/beyondflow_deploy` (privater Schlüssel) | — (Schlüssel, kein Passwort) |
| **Anthropic Claude API** (optional) | API-Key | KI-Zusammenfassung im Betriebsassistenten des Studios | Abrechnung über das Anthropic-Konto | `BF_AI_KEY` | |

## Bereits vorhanden, von BeyondFlow mitbenutzt

| Zielsystem | Benutzer | Funktion | Hinweis | Ablage des Passworts |
|---|---|---|---|---|
| BeyondCRM-Datenbank | `ADMIN` | Einspielen der DB-Skripte `db/10`–`60` über Database Actions | Nicht für den Betrieb; BeyondFlow selbst nutzt ihn nicht | |
| BeyondFlow-VM (`instance-20261123-2027`) | `opc` (SSH-Schlüssel `beyondflow.key`) | Installation, Updates von Hand, SSH-Tunnel zum Studio | Ohne Passwort, nur Schlüssel | — |
| SAP-Gateway-VM (`sap-bridge`) | `opc` (SSH) | Installation und Betrieb des SAP-Gateways | | — |
| ServiceNow `dev484521` | `admin` | Administration der Entwickler-Instanz | BeyondFlow nutzt ihn seit 25.11.2026 nicht mehr | |

## Beim Kunden anzulegen (Checkliste)
1. DB-Benutzer `BEYONDFLOW` (`db/10_beyondflow_user.sql`)
2. ServiceNow-Integrationsbenutzer (nur Web-Service-Zugriff, Nur-Lese-Rolle auf Accounts und Kontakte)
3. Salesforce External Client App und Run-As-Integrationsbenutzer
4. SAP-RFC-Benutzer mit den oben genannten Berechtigungen
5. Deploy Key für das Repository (nur lesend)
