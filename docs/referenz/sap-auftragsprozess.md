# Prozess: Auftrag CRM → SAP S/4HANA (CRM Teil 3)

> Historisches Konzept vom 23.11.2026 (vor BeyondFlow). Der hier beschriebene Poller-Dienst `sap-bridge` ist abgelöst:
> Heute steuert BeyondFlow den Ablauf (Flows `crm-orders-to-sap`, `sap-status-to-crm`), die SAP-VM betreibt nur noch das SAP-Gateway.
> Die fachlichen Regeln (Outbox, Retry, Dublettenschutz, Stammdaten über S_EXT_REF) gelten weiter.

## Technik (Stand 23.11.2026)
- OCI-Instanz `sap-bridge` (VM.Standard.E2.1.Micro, Oracle Linux 9, x86_64), öffentliche IP 92.5.26.170, User `opc`
- Java: Temurin JDK 8 unter /opt/java8; SAP JCo 3.0.18 unter /opt/sapjco3
- SAP: S23 (S/4HANA 2023, Release 758), Anwendungsserver 172.21.72.22, Instanz 00, SAProuter /H/161.38.17.212/S/3299/H/
- Testprogramme auf der Instanz: ~/rfcping (RfcPing, BapiRead, MatSearch, OrderTest)
- Dienst: ~/sapbridge/SapBridge.java (+ ojdbc8.jar, oraclepki.jar 23.6), Konfiguration /etc/sap-bridge.env (chmod 600)
- DB-Zugang: Instance Wallet unter /opt/wallet_siebelsb, DB_URL jdbc:oracle:thin:@siebelsb_low?TNS_ADMIN=/opt/wallet_siebelsb (mTLS bleibt aktiv, keine ACL)
- DB-Benutzer SAP_BRIDGE (Profil SERVICE_ACCOUNT, nur SELECT auf CX_SAP_ORDER_HDR_V/ITM_V und EXECUTE auf CX_SAP_S4)
- Erster BAPI-Testauftrag: 1036779 (Kunde 9830023, VkOrg 1020/10/00, Material 301186, Bestellnr. BEYONDCRM-TEST)
- Erster Ende-zu-Ende-Auftrag aus dem CRM: AU-2026-0402 (Kessler) → SAP 1036780 (vorher ein fachlicher Fehler: Material 301209 ohne Positionstyp zu TA)

## Datenbankobjekte (Schema SIEBEL)
- Werteliste SAP_S4_CONFIG: VKORG 1020, VTWEG 10, SPART 00, AUART TA
- ERP_STATUS ergänzt: Übergeben, Fehler
- S_EXT_REF (SAP_S4): Kessler 1-U3 → 0009830023; GM-75 1-V1 → 301186; ET-STD 1-VA → 301186 (vereinfacht)
- Paket CX_SAP_S4: submit_order, claim_next, complete_ok, complete_err
- APEX Seite 17: Button SEND_SAP „An SAP übergeben“ + Prozess

## Entscheidungen
1. Auslöser: Button „An SAP übergeben“ am CRM-Auftrag
2. Vertriebsbereich/Auftragsart: eine globale Konfiguration (1020/10/00, TA)
3. Jeder Auftrag wird zuerst simuliert (TESTRUN), danach angelegt

## Ablauf
1. Button erzeugt Outbox-Eintrag (SAP_S4, S_ORDER, Neu), ERP-Status „Übergeben“
2. Dienst pollt alle 30 s (claim_next mit FOR UPDATE SKIP LOCKED)
3. Mapping Account → KUNNR, Produkt → MATNR über S_EXT_REF; fehlende Zuordnung = fachlicher Fehler
4. BAPI_SALESORDER_CREATEFROMDAT2 mit TESTRUN, dann echt + BAPI_TRANSACTION_COMMIT (gleicher JCoContext); CRM-Auftragsnr. als Kundenbestellnr.
5. Rückschreiben: ERP-Auftragsnr., ERP-Status „Angelegt“, Status „Bestätigt“, S_EXT_REF, Outbox „Verarbeitet“

## Fehler
- Technisch: Retry mit Backoff (2^n Minuten), max. 5
- Fachlich: kein Retry, Meldung in Outbox, ERP-Status „Fehler“, Button erneut nutzbar
- Dubletten: SAP-Nummer vorhanden → kein Neuanlegen; vor Retry Suche per BAPI_SALESORDER_GETLIST nach Kundenbestellnr.

## Stammdaten
- Experiment: manuelle Zuordnung über „Externe IDs“
- Später: Abgleich per GETLIST mit Vorschlägen; keine Kundenanlage in SAP aus dem CRM

## Offen (Stand 23.11.2026)
- systemd-Dienst für Dauerbetrieb (erledigt: `sap-gateway`)
- RFC-Systembenutzer (bisher persönlicher User)
- SAProuter-Freigabe ist offenbar breit – an Basis melden
- Passwörter aus Env-Datei in OCI Vault verlagern
- Idee „CRM Teil 4“: Integrations-Hub nach MuleSoft-Vorbild mit Apache Camel auf Ampere-Instanz (umgesetzt als BeyondFlow)
