# BeyondFlow – Installation bei einem Kunden

Diese Anleitung baut das Referenz-Setup bei einem Kunden nach. Voraussetzung: **BeyondCRM läuft bereits** in einer Oracle Autonomous Database (Schema `SIEBEL`).

Arbeitsreihenfolge:
1. Infrastruktur und Netz (Abschnitt 1)
2. Datenbank (2)
3. Die angebundenen Systeme, nur die gewünschten (3–5)
4. Installer (6)
5. Prüfung (7)

Zeitbedarf: etwa einen halben Tag, ohne Wartezeiten beim Kunden (Benutzer, Freischaltungen).

> Konventionen
> - `<…>` sind Kundenwerte.
> - Geheimnisse werden nie in Dateien des Repositorys oder in Tickets und Chats geschrieben. Der Installer fragt sie verdeckt ab.

---

## 0. Checkliste vom Kunden

| Was | Wofür |
|---|---|
| OCI-Tenancy mit Compartment, VCN und Rechten für Compute | BeyondFlow-VM, ggf. SAP-Gateway-VM |
| Admin-Zugang zur Autonomous Database von BeyondCRM | DB-Benutzer und Pakete anlegen, Wallet |
| Salesforce: Systemadministrator in der Org, My-Domain-URL | External Client App, Felder, CDC |
| ServiceNow: Admin auf der Instanz | Integrationsbenutzer |
| SAP: RFC-Benutzer, Application Server, Systemnummer, Mandant, ggf. SAProuter | SAP-Gateway |
| SAP-S-User mit Download-Recht | SAP JCo 3.1 (Linux x86_64) – darf nicht weitergegeben werden |

---

## 1. Infrastruktur (OCI)

### 1.1 BeyondFlow-VM
- **Shape:** VM.Standard.A1.Flex (ARM), 2 OCPU, 12 GB RAM, Image **Oracle Linux 9**.
- **Netz:** privates Subnetz des VCN, mit öffentlicher IP *oder* nur über Bastion erreichbar.
- **Eingehend genügt SSH (22).** Studio, API, Laufzeit und Kafka hören nur auf `127.0.0.1`.
- Nur für ServiceNow-Ereignisse zusätzlich **443 und 80** eingehend (Security List), siehe Abschnitt 4.1.
- Den SSH-Schlüssel beim Anlegen erzeugen und sicher ablegen. Benutzer: `opc`.

### 1.2 SAP-Gateway-VM (nur mit SAP)
SAP JCo gibt es nicht für ARM, deshalb läuft das Gateway auf einer eigenen kleinen x86-VM.
- **Shape:** z. B. VM.Standard.E4.Flex, 1 OCPU, 2 GB, Oracle Linux 9.
- **Netz:** im selben VCN wie die BeyondFlow-VM.
- **Security List** (oder NSG) des Subnetzes: Ingress-Regel
  - Quelle: `<VCN-CIDR, z. B. 10.0.0.0/16>`
  - Protokoll TCP, Zielport **8095**
  - Beschreibung „BeyondFlow → SAP-Gateway“
- Die VM braucht eine Netzverbindung zum SAP-System bzw. zum SAProuter des Kunden.

### 1.3 Zugriff vom Arbeitsplatz
Das Studio wird nur über einen SSH-Tunnel geöffnet.

Unter Windows `tools/BeyondFlow-Studio.cmd` kopieren, `KEY` und `VM` anpassen und doppelklicken. Danach im Browser: `http://localhost:8080/studio.html`.

Manuell:
```
ssh -i <key> -N -L 8080:127.0.0.1:8080 opc@<vm-ip>
```

---

## 2. Datenbank (BeyondCRM)

In der OCI-Konsole: Autonomous Database → **Database Actions → SQL**, angemeldet als `ADMIN`. Die Skripte aus `db/` **in Nummernreihenfolge** ausführen, jeweils als Skript (F5):

| Skript | Inhalt |
|---|---|
| `10_beyondflow_user.sql` | Profil `SERVICE_ACCOUNT` (Passwort läuft nicht ab) und Benutzer `BEYONDFLOW`. Database Actions fragt das Passwort als Ersetzungsvariable ab. |
| `20_cx_bf_api.sql` | Paket `CX_BF_API` (Upsert ServiceNow → CRM) |
| `30_cx_sap_s4.sql` | Paket `CX_SAP_S4`, Sichten `CX_BF_ORDER_V`, `CX_SAP_ORDER_HDR_V`, `CX_SAP_ORDER_ITM_V`. Zeigt am Ende die SAP-Werte (Liste `SAP_S4_CONFIG`). |
| `40_cx_bf_sap.sql` | Paket `CX_BF_SAP` (Auftrag → SAP, Status zurück) |
| `50_cx_bf_sfdc.sql` | Paket `CX_BF_SFDC`, Tabelle `CX_BF_SYNC_STATE` (Salesforce) |
| `60_grants.sql` | Rechte von `BEYONDFLOW` zusammengefasst, mit Kontrolle (5 Zeilen) |
| `70_cx_bf_check.sql` | Paket `CX_BF_CHECK`: nur lesende Prüfabfragen für Tests und Betrieb, im Studio unter `/api/crm/check/<typ>/<schlüssel>` |

Ohne SAP oder Salesforce lassen sich die Skripte 30/40 bzw. 50 weglassen. Dann schlagen in `60_grants.sql` die zugehörigen Zeilen fehl; das ist unkritisch.

**SAP-Stammdaten im CRM:**
- Die Werteliste `SAP_S4_CONFIG` braucht die Einträge `VKORG`, `VTWEG`, `SPART` und `AUART` mit den Werten des Kunden.
- Kunden und Produkte brauchen einen Eintrag in `S_EXT_REF` mit `EXT_SYS_CD = 'SAP_S4'`. Als `EXT_ID` steht dort die SAP-Kundennummer bzw. die Materialnummer.

Prüfen: Alle Objekte müssen `VALID` sein.
```sql
select object_name, object_type, status from all_objects
 where owner = 'SIEBEL' and object_name like 'CX\_%' escape '\' order by 1;
```

**Wallet:**
1. Autonomous Database → *Database connection* → *Download wallet* → Typ **Instance Wallet**.
2. Ein Wallet-Passwort vergeben; BeyondFlow braucht es nicht.
3. Die ZIP-Datei auf die BeyondFlow-VM kopieren:
   ```
   scp -i <key> Wallet_<db>.zip opc@<vm-ip>:~/
   ```

Den TNS-Alias (meist `<db>_low`) aus `tnsnames.ora` im ZIP notieren.

---

## 3. Salesforce (optional)

1. **External Client App:** *Setup → External Client App Manager → New External Client App*.
   - Name `BeyondFlow`, Kontakt-E-Mail.
   - *Enable OAuth*, Callback-URL `https://localhost/callback` (wird nicht benutzt).
   - Scope **Manage user data via APIs (api)**.
   - **Enable Client Credentials Flow** anhaken.
2. **Policies** der App bearbeiten:
   - *Enable Client Credentials Flow*.
   - **Run As**: Integrationsbenutzer **per Benutzername** (nicht per E-Mail) eintragen.
   - Der Benutzer braucht Lese- und Schreibrechte auf Account, Contact und Opportunity.
3. **Consumer Key und Secret:** *Settings → OAuth Settings → Consumer Key and Secret*.
   - Eine Bestätigung per E-Mail-Code ist nötig.
   - Die Werte erst beim Installer eingeben.
4. **Felder an der Opportunity** (*Object Manager → Opportunity → Fields & Relationships → New*, Typ Text):

   | Feld | API-Name | Länge |
   |---|---|---|
   | BeyondCRM Auftrag | `BeyondCRM_Auftrag__c` | 30 |
   | SAP Auftrag | `SAP_Auftrag__c` | 20 |
   | ERP Status | `ERP_Status__c` | 40 |

   Für den Integrationsbenutzer sichtbar und bearbeitbar machen und ins **Page Layout** der Opportunity aufnehmen.
5. **Change Data Capture:** *Setup → Change Data Capture* → **Opportunity** zu *Selected Entities* → Speichern.
6. **Produkte:** Die Produkte brauchen einen aktiven Eintrag im **Standard Price Book**, sonst lassen sie sich der Opportunity nicht zuordnen.
   - Der Produktcode muss der SAP-Materialnummer bzw. dem CRM-Produkt entsprechen.
7. Die My-Domain-URL (`https://<my-domain>.my.salesforce.com`) als `BF_SF_URL` notieren.

## 4. ServiceNow (optional)

1. Einen Integrationsbenutzer anlegen: *Web service access only*, ohne Ablauf des Passworts.
2. Rollen: Lesezugriff auf `customer_account` und `customer_contact`, z. B. `snc_platform_rest_api_access` plus eine Rolle mit Leserecht auf diese Tabellen.
3. Den Instanznamen (`<instanz>` aus `<instanz>.service-now.com`) als `BF_SNOW_INSTANCE` notieren.

### 4.1 Ereignisse aus ServiceNow (empfohlen)

Ohne Ereignisse gleicht BeyondFlow ServiceNow stündlich ab. Mit Ereignissen kommt jede Änderung an Accounts und Kontakten nach wenigen Sekunden im CRM an.

Ablauf: ServiceNow (Business Rule, async) → HTTPS `https://<Name>/ingest/servicenow` → Prüfung der Signatur → Kafka-Topic `snow.events` → Flow `snow-events-to-crm` → CRM. Fehler landen im Topic `snow.events.dlq`.

1. **OCI:** In der Security List des Subnetzes eingehend TCP **443** und **80** von `0.0.0.0/0` erlauben.
   - 443: Ereignisse. Von außen ist nur dieser eine Pfad erreichbar, jede Meldung muss signiert sein.
   - 80: nur die Zertifikatsprüfung von Let's Encrypt (`/.well-known/acme-challenge/`).
2. In `~/beyondflow.conf`: `BF_PUBLIC_HOST=<öffentliche IP der VM>`, dann `install/install.sh` (oder auf das automatische Update warten). Der Installer
   - richtet die Firewall-Weiterleitung 443 → 8443 und 80 → 8089 ein,
   - startet den Eingang (`beyondflow-edge`, nginx),
   - holt ein Let's-Encrypt-Zertifikat (90 Tage, automatische Erneuerung über `beyondflow-cert.timer`, Protokoll `logs/cert.log`). Name im Zertifikat: `BF_PUBLIC_NAME`, falls der Kunde einen DNS-Namen für die IP hat; sonst `<ip-mit-bindestrichen>.sslip.io` (öffentlicher DNS-Dienst, löst auf die IP auf).
     Kein reines IP-Zertifikat: Let's Encrypt stellt es nur ohne Subject aus, und ServiceNow lehnt solche Zertifikate ab („badly formatted directory string“).
   - erzeugt den Signaturschlüssel `BF_SNOW_HMAC` in `secrets.env`.
3. In ServiceNow die Systemeigenschaften, das Script Include und die Business Rules einrichten: siehe [`servicenow/README.md`](../servicenow/README.md). Den Schlüssel auf der VM anzeigen mit `grep BF_SNOW_HMAC ~/beyondflow/config/secrets.env` und selbst in die Eigenschaft `beyondflow.events.hmac_key` eintragen.
4. Prüfen im Studio, Reiter **Ereignisse**: Eingang „bereit“ mit Let's-Encrypt-Zertifikat; nach einer Änderung in ServiceNow steigt „Angenommen“, das Topic `snow.events` hat keinen Rückstand.

## 5. SAP-Gateway (optional)

Auf der **SAP-Gateway-VM** (Abschnitt 1.2) als `opc`:

1. **JDK 8** nach `/opt/java8` entpacken (z. B. Eclipse Temurin 8, Linux x64). Ergebnis: `/opt/java8/bin/javac`.
2. **SAP JCo 3.1** (Linux x86_64) im SAP Support Portal herunterladen.
   - `sapjco3.jar` und `libsapjco3.so` nach `/opt/sapjco3` legen.
   - Der Download erfordert einen SAP-S-User; die Dateien nicht ins Repository legen.
3. Das Repository auf die VM holen und den Installer starten:
   ```bash
   git clone https://github.com/mschneew/beyondflow.git ~/beyondflow-src
   bash ~/beyondflow-src/sap-gateway/install-sap-gateway.sh
   ```
   - Der Installer fragt Application Server, Systemnummer, optional den SAProuter, Mandant, Sprache, RFC-Benutzer und Passwort ab. Alles landet in `/etc/sap-bridge.env` (Rechte 600).
   - Er erzeugt ein Token und startet den Dienst `sap-gateway`.
4. Am Ende muss stehen: `Test: {"ok":true,…}`. Die ausgegebene **Gateway-Adresse** als `BF_SAP_GATEWAY` notieren.

### 5.1 Steuerzugang für den Reiter „SAP-Gateway“ (empfohlen)

Im Studio zeigt der Reiter **SAP-Gateway** den Zustand des Gateways, der SAP-Verbindung und der Auftrags-Warteschlange.
- **Anhalten / Fortsetzen** wirkt im Gateway selbst und braucht nichts weiter.
- **Stoppen / Starten / Neu starten** des Dienstes läuft über einen eingeschränkten SSH-Zugang:
  - Benutzer `bfctl` auf der SAP-Gateway-VM, ohne Passwort.
  - Der Schlüssel erlaubt nur den erzwungenen Befehl `/usr/local/bin/sap-gateway-ctl` mit `status|start|stop|restart`, nur von der IP der BeyondFlow-VM, ohne Terminal und ohne Weiterleitungen.
  - `sudo` ist nur für `systemctl start|stop|restart sap-gateway` erlaubt (`/etc/sudoers.d/bfctl-sap-gateway`).
  - Jede Aktion steht im Studio unter „Letzte Aktionen“ (`~/beyondflow/logs/sapgw-actions.log`) und im Journal der SAP-VM (`journalctl -t sap-gateway-ctl`).

Einrichtung:
1. BeyondFlow installieren (Abschnitt 6). Der Installer erzeugt dabei den Schlüssel `~/.ssh/bf_sapctl`, der die VM nie verlässt.
2. Auf der BeyondFlow-VM den **öffentlichen** Teil anzeigen: `cat ~/.ssh/bf_sapctl.pub`.
3. Auf der SAP-Gateway-VM den Gateway-Installer (erneut) starten und die Zeile einfügen, wenn er danach fragt – oder die Datei `bf_sapctl.pub` vorher neben das Skript legen, dann liest er sie selbst. Die erlaubte Absender-IP liest er aus dem Kommentar `beyondflow@<IP>`.
4. Port 22 von der BeyondFlow-VM zur SAP-Gateway-VM muss im privaten Netz offen sein (OCI-Standard).

Ohne Steuerzugang zeigt der Reiter „nicht eingerichtet“; Status und Anhalten/Fortsetzen funktionieren trotzdem.

Benötigte Berechtigungen des RFC-Benutzers (S_RFC und Vertrieb):
- `RFC_PING`
- `BAPI_SALESORDER_CREATEFROMDAT2`
- `BAPI_SALESORDER_GETLIST`
- `BAPISDORDER_GETDETAILEDLIST`
- `BAPI_TRANSACTION_COMMIT` / `_ROLLBACK`

---

## 6. BeyondFlow installieren

Auf der **BeyondFlow-VM** als `opc`:

```bash
sudo dnf install -y git
git clone https://github.com/mschneew/beyondflow.git ~/beyondflow-src
cd ~/beyondflow-src
cp config/beyondflow.conf.example ~/beyondflow.conf
vi ~/beyondflow.conf          # Werte aus den Abschnitten 2–5 eintragen; nicht angebundene Systeme leer lassen
bash install/install.sh
```

Der Installer arbeitet neun Schritte ab und zeigt jeden an:
1. Pakete (Java 21, git, Podman, Python).
2. JBang und Camel **4.22.0**.
3. Programmdateien.
4. Flows: Flows nicht angebundener Systeme landen unter `disabled/`.
5. Kaoto: Die statischen Dateien kommen aus dem Container-Image. Der Image-Digest steht danach in `~/beyondflow/KAOTO_DIGEST`.
6. Wallet entpacken.
7. Konfiguration und Geheimnisse:
   - DB-Passwort `BEYONDFLOW`
   - Salesforce Consumer Key/Secret
   - ServiceNow-Benutzer/Passwort
   - SAP-Token: `sudo grep GW_TOKEN /etc/sap-bridge.env` auf der SAP-VM
   - optional ein Anthropic-API-Key für die KI-Zusammenfassung im Assistenten
8. systemd-Dienste.
9. Start und Prüfung.

Am Ende müssen alle Prüfungen `OK` zeigen.

**Für eine reproduzierbare Installation:** nach der ersten Installation den gemeldeten Kaoto-Digest in `~/beyondflow.conf` festschreiben:
```
BF_KAOTO_IMAGE=quay.io/kaotoio/kaoto-app@sha256:<digest>
```

### Dateien auf der VM

| Pfad | Inhalt |
|---|---|
| `~/beyondflow.conf` | Kundenparameter (keine Geheimnisse) |
| `~/beyondflow/config/secrets.env` | Geheimnisse, Rechte 600 |
| `~/beyondflow/config/application.properties` | erzeugt; bestehende Werte werden bei Updates nicht überschrieben |
| `~/beyondflow/flows`, `events`, `disabled` | aktive, Ereignis- und deaktivierte Flows |
| `~/beyondflow/history.git` | Änderungsprotokoll aller Flows (Studio und Installer) |
| `~/beyondflow/logs/runtime.log` | Log beider Laufzeiten |
| `~/beyondflow/logs/update.log` | Protokoll der automatischen Updates |
| `~/beyondflow/VERSION`, `KAOTO_DIGEST` | installierte Version, Kaoto-Stand |
| Podman-Volume `bf-kafka-data` | Kafka-Daten (Topics, 7 Tage Aufbewahrung) |
| `~/beyondflow/edge/` | Eingang: nginx-Konfiguration, Zertifikat (`certs/`), Zertifikatsskript |
| `~/beyondflow/logs/ingest.log`, `cert.log` | angenommene und abgelehnte Ereignisse, Zertifikatserneuerung |

---

## 7. Prüfung (Abnahme)

1. Tunnel öffnen, `http://localhost:8080/studio.html` aufrufen. Unten steht die Version, z. B. `BeyondFlow 0.15`.
2. **Assistent:** „Salesforce prüfen“, „SAP prüfen“ und „ServiceNow prüfen“ müssen grün sein.
3. **Laufzeit:** Alle aktiven Flows haben den Status *Started*. CPU und Speicher sind plausibel; die Ereignis-Laufzeit liegt im Leerlauf unter 5 % CPU.
4. **Ende-zu-Ende** (wenn Salesforce und SAP angebunden sind):
   1. In Salesforce eine Opportunity mit Produkt auf *Closed Won* setzen.
   2. Innerhalb von Sekunden (Ereignis) bzw. 15 Minuten (Timer) muss im CRM ein Auftrag `AU-…` entstehen.
   3. Danach entsteht der SAP-Auftrag. Die Felder `BeyondCRM_Auftrag__c`, `SAP_Auftrag__c` und `ERP_Status__c` an der Opportunity sind gefüllt.
5. **Log:** keine roten Einträge nach dem Start.
6. **Ereignisse:** Im Reiter „Ereignisse“ läuft der Broker; alle Topics ohne Rückstand. Topics: `sf.opportunity`, `crm.order`, `sap.order.status`, `snow.events` und je ein Fehler-Topic `*.dlq` (mit „Erneut einspielen“).
7. **SAP-Gateway** (wenn angebunden): Im Reiter „SAP-Gateway“ sind Dienst, Gateway-Prozess und Warteschlange grün.
8. **Gegenprobe im CRM ohne Datenbank-Anmeldung** (bei offenem Tunnel), Beispiele:
   - `http://localhost:8080/api/crm/check/auftrag/AU-2026-0408`
   - `…/account/<ROW_ID>`
   - `…/kontakt/<ROW_ID>`
   - `…/extern/SNOW:<sys_id>`
   - `…/sapqueue/alle` (Warteschlange zu SAP)

## 8. Updates

Updates laufen **automatisch**:
- Der Timer `beyondflow-update.timer` prüft alle 10 Minuten, ob auf GitHub neue Commits liegen.
- Wenn ja, holt er sie und führt `install/install.sh` aus.
- Dabei starten die Laufzeiten kurz neu; Timer-Flows holen Versäumtes beim nächsten Lauf nach.
- Protokoll: `~/beyondflow/logs/update.log`
- Abschalten: `systemctl --user disable --now beyondflow-update.timer`, oder dauerhaft `BF_AUTO_UPDATE=no` in `~/beyondflow.conf`.

Das **SAP-Gateway** wird nicht automatisch aktualisiert, weil es auf einer eigenen VM läuft und ein Neustart dort laufende SAP-Aufrufe trifft. Wenn das Änderungsprotokoll ein Gateway-Update nennt, auf der SAP-Gateway-VM:
```bash
cd ~/beyondflow-src && git pull && bash sap-gateway/install-sap-gateway.sh
```

Von Hand, etwa um neue Geheimnisse einzugeben:
```bash
cd ~/beyondflow-src && git pull && bash install/install.sh
```
- Im Studio geänderte Flows bleiben erhalten; der Installer meldet sie als *abweichend*.
- Mit `--replace-flows` setzt der Installer sie auf den Repository-Stand zurück. Der alte Stand bleibt in `history.git`.
- `--update-kaoto` holt die Kaoto-Oberfläche neu.

## 9. Fehlerbilder

| Meldung | Ursache / Abhilfe |
|---|---|
| `invalid_client_id` (Salesforce) | Consumer Key falsch übernommen → Zeile `BF_SF_CLIENT_ID` in `secrets.env` löschen, `install.sh` erneut |
| `no client credentials user enabled` | Run-As-Benutzer in den App-Policies fehlt |
| `ORA-04065` nach Änderung eines Pakets | Laufzeit neu starten: `systemctl --user restart beyondflow-runtime` |
| `Cannot find Kamelet` | Ein Konnektor-Flow ist deaktiviert, wird aber benutzt → Konnektor aktivieren |
| Ereignis-Laufzeit startet ständig neu | Salesforce-Anmeldung prüfen; die Laufzeit wartet 60 s zwischen den Versuchen, die Hauptlaufzeit ist nicht betroffen |
| SAP: `Gateway nicht erreichbar` | Security List Port 8095, `systemctl status sap-gateway` auf der SAP-VM |
