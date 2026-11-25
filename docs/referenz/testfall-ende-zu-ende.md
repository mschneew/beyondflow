# BeyondFlow – Testfall Ende-zu-Ende

Nov 24, 2026 · @Markus

## Ziel und Umfang

Der Test weist nach, dass eine in Salesforce gewonnene Opportunity ohne manuellen Eingriff zum CRM-Auftrag und zum SAP-Kundenauftrag wird und der SAP-Status nach Salesforce zurückläuft. Dauer des Hauptablaufs: etwa 5 Minuten.

Getestet werden vier Systeme und BeyondFlow als Integrationsschicht:

- **Salesforce** (Developer-Org orgfarm-7813050473): Opportunity, Accounts, Kontakte
- **BeyondCRM** (Oracle ADB siebelsb): Auftrag, Positionen, Outbox, Externe IDs
- **SAP S/4HANA 2023** (S23, über SAP-Gateway auf sap-bridge): Kundenauftrag per BAPI
- **ServiceNow** (dev484521): Accounts und Kontakte ins CRM
- **BeyondFlow Studio** 0.12: Laufzeit, Log, Mapping und Betriebsassistent

Nicht Teil des Tests: Lastverhalten, Ausfallsicherheit der VMs und die Lieferung bzw. Faktura in SAP.

## Voraussetzungen und Testdaten

Alle Punkte müssen vor dem Start erfüllt sein; jeder lässt sich im Studio oder im jeweiligen System prüfen.

- [x] Studio zeigt unten in der Seitenleiste **BeyondFlow 0.12**
- [x] Reiter **Assistent**: keine Karte mit „Störung“; „Salesforce prüfen“ und „SAP S/4HANA prüfen“ melden ✓
- [x] Reiter **Laufzeit**: 9 Routen im Status Started
- [ ] Account **Kessler Maschinenbau GmbH** existiert in Salesforce (von BeyondFlow angelegt) und im CRM mit Externer ID SAP\_S4 = 0009830023
- [x] Produkt **GM-75** existiert im CRM (Artikel-Nr. GM-75, Externe ID SAP\_S4 = Material 301186)
- [x] Produkt mit **Produktcode GM-75** existiert in Salesforce, aktiv, mit Preis im Standard-Preisbuch
- [ ] Felder `BeyondCRM_Auftrag__c`, `SAP_Auftrag__c`, `ERP_Status__c` an der Opportunity in Salesforce angelegt

| Testdatum | Salesforce | BeyondCRM | SAP |
| --- | --- | --- | --- |
| Kunde | Kessler Maschinenbau GmbH | Account mit Externer ID SFDC und SAP\_S4 | Kunde 0009830023 |
| Produkt | Produktcode GM-75, 980 EUR | Artikel GM-75 | Material 301186 |
| Vertriebsbereich | – | Werteliste SAP\_S4\_CONFIG | VkOrg 1020 / 10 / 00, Auftragsart TA |
| Opportunity | „Test E2E GM-75“, Menge 1 | wird Auftrag AU-2026-04xx | wird Kundenauftrag 00010368xx |

## Ablauf

&#91;embedded content: Testablauf · 3 Systeme, 4 Flows\]

BeyondFlow verbindet die Schritte im Takt der Flows; im ungünstigsten Fall summieren sich die Wartezeiten auf etwa 5 Minuten.

## Testfall 1 – Hauptablauf Opportunity bis SAP und zurück

Erwartet: nach höchstens 5 Minuten stehen CRM-Auftragsnummer, SAP-Nummer und ERP-Status „Angelegt“ an der Opportunity.

| Nr. | Aktion | Wo | Erwartetes Ergebnis | Nachweis | OK |
| --- | --- | --- | --- | --- | --- |
| 1.1 | Opportunity „Test E2E GM-75“ am Account Kessler anlegen, Produkt GM-75 Menge 1, Phase „Closed Won“ | Salesforce | Opportunity gespeichert, Betrag 980 EUR | Opportunity-Detail |  |
| 1.2 | Warten (Takt sf-opportunities-to-crm: 2 Min.) | Studio → Log, Filter „Test E2E“ | Auftrag im CRM angelegt | Log: „Opportunity Test E2E GM-75 -> CRM-Auftrag AU-2026-04xx (an SAP uebergeben)“ |  |
| 1.3 | Auftrag im CRM öffnen | BeyondCRM | Account Kessler, 1 Position GM-75, Status „Bestätigt“, ERP-Status „Übergeben“ | Auftragsmaske |  |
| 1.4 | Warten (Takt crm-orders-to-sap: 30 s) | Studio → Log | SAP-Auftrag angelegt | Log: „Auftrag AU-2026-04xx -> SAP 00010368xx (SAP-Auftrag … angelegt)“ |  |
| 1.5 | Auftrag im CRM neu laden | BeyondCRM | ERP-Auftragsnr. gefüllt, ERP-Status „Angelegt“ | Auftragsmaske |  |
| 1.6 | Kundenauftrag in SAP anzeigen (VA03) | SAP | Auftraggeber 9830023, Material 301186, Menge 1, Bestellnummer = CRM-Auftragsnr. | VA03 |  |
| 1.7 | Warten (Takt crm-orders-to-sf: 2 Min.) | Studio → Log | Opportunity aktualisiert | Log: „Auftrag AU-2026-04xx -> Salesforce-Opportunity aktualisiert“ |  |
| 1.8 | Opportunity neu laden | Salesforce | BeyondCRM Auftrag, SAP Auftrag und ERP Status „Angelegt“ gefüllt | Opportunity-Detail |  |
| 1.9 | Reiter Assistent öffnen | Studio | Salesforce, SAP, CRM „OK“, keine Störung | Karten |  |

Der nächste Lauf von sf-opportunities-to-crm erzeugt keinen zweiten Auftrag: die Opportunity ist über `BeyondCRM_Auftrag__c` als verarbeitet markiert.

## Testfälle 2–6 – Fehlerfälle und Nebenwege

Jeder Fehlerfall muss im Log und im Assistenten erkennbar sein, ohne dass andere Flows stehen bleiben.

| Nr. | Auslöser | Erwartetes Ergebnis | Nachweis | OK |
| --- | --- | --- | --- | --- |
| 2 Fehlende Materialnummer | Opportunity mit einem Produkt, das im CRM keine SAP-Zuordnung hat (z. B. Produktcode SA-400), auf Closed Won | CRM-Auftrag entsteht, SAP lehnt fachlich ab, ERP-Status „Fehler“, an der Opportunity ERP Status „Fehler“ | Log WARN „hat keine SAP-Materialnummer“; Assistent: Karte „Datenqualität SAP“ |  |
| 3 Unbekannter Produktcode | Opportunity mit einem Salesforce-Produkt, dessen Code es im CRM nicht gibt | Kein CRM-Auftrag, Opportunity bleibt offen und wird alle 2 Min. erneut versucht | Log WARN „Produktcode … gibt es im CRM nicht“; Assistent: „Datenqualität Salesforce“ |  |
| 4 SAP-Gateway aus | Auf sap-bridge `sudo systemctl stop sap-gateway`, dann einen CRM-Auftrag „An SAP übergeben“ | Outbox mit technischem Fehler, Wiederholung mit Wartezeit; nach `sudo systemctl start sap-gateway` wird der Auftrag ohne Dublette angelegt | Assistent: SAP „Störung – SAP-Gateway antwortet nicht“, „SAP prüfen“ ✗, nach Start ✓ |  |
| 5 Pausieren und Fortsetzen | Im Assistenten „Salesforce-Flows pausieren“, danach „Salesforce-Flows fortsetzen“ | 5 Salesforce-Flows erscheinen grau in der Flow-Liste, ServiceNow und SAP laufen weiter; nach Fortsetzen wieder grün | Flow-Liste, Reiter Laufzeit |  |
| 6 ServiceNow → CRM | In ServiceNow bei einem customer\_account die Telefonnummer ändern | Nach höchstens 5 Min. im CRM geändert, danach auch in Salesforce (crm-accounts-to-sf) | Log „Account … -> CRM UPDATE“, dann „CRM-Account … -> Salesforce aktualisiert“ |  |

Testfall 6 zeigt die Drei-System-Kette ServiceNow → CRM → Salesforce ohne eigenen Flow dafür.

## Testlauf 25.11.2026 – Ereignismodell und ServiceNow (durchgeführt von Claude)

Stand BeyondFlow 0.18. Durchgeführt im eingebauten Browser (Salesforce, ServiceNow, Studio, Database Actions); Markus nur für Anmeldungen und Passwörter.

| Nr. | Schritt | Ergebnis | Zeit (MEZ) | OK |
| --- | --- | --- | --- | --- |
| E.1 | Change Data Capture für Opportunity in Salesforce eingeschaltet, Ereignis-Laufzeit neu gestartet | Log: „Subscribed to channel /data/OpportunityChangeEvent“ | 11:05 | ✅ |
| E.2 | Opportunity „Test E2E Ereignis 25.11.“ (Kessler Maschinenbau GmbH, Produkt GM-75, Menge 22\*) auf Closed Won | Ereignis kam an, Flow brach ab: `ChangeEventHeader` nicht im Body | 11:10 | ❌ → behoben in 0.18 |
| E.3 | Phase Negotiation/Review, dann wieder Closed Won | Ereignis +2 s, CRM-Auftrag **AU-2026-0408** +3 s, SAP-Auftrag **0001036803** +10 s, Opportunity aktualisiert +11 s | 11:12 | ✅ |
| E.4 | Gegenprobe CRM-Datenbank | Auftrag „Bestätigt“, ERP-Status „Angelegt“, Outbox „Verarbeitet“ ohne Wiederholung, Externe IDs SFDC + SAP\_S4 | 11:13 | ✅ |
| E.5 | Gegenprobe Salesforce | BeyondCRM Auftrag AU-2026-0408, SAP Auftrag 0001036803, ERP Status „Angelegt“; keine doppelte Opportunity | 11:14 | ✅ |
| S.1 | Neuer ServiceNow-Integrationsbenutzer `beyondflow.integration` (nur Web-Service) | Anmeldung über Studio-Assistent „ServiceNow prüfen“ ✓ | 13:24 | ✅ |
| S.2 | In ServiceNow Account „BF Test Account 25.11.“ und Kontakt „Tina Testkontakt“ angelegt | Kontakt → CRM 1-30G (INSERT), Account → CRM 1-30K | 13:25 / 13:45 | ✅ |
| S.3 | Stabilität ServiceNow-Abruf | Einzelne HTTP 500 der Entwickler-Instanz (13:25, 13:30, 13:45); der nächste Lauf holt alles nach |  | ⚠️ |

\* Menge versehentlich 22 statt 2 eingegeben; für den Test ohne Bedeutung.

**Befunde und Folgearbeiten**

- Behoben (0.18): Filter im Ereignis-Flow; Installer übernimmt neue Flow-Stände, wenn der Flow lokal unverändert ist.
- Offen: Der ServiceNow-Abruf verarbeitet bei jedem Lauf alle letzten 50 Datensätze erneut (UPDATE ohne Änderung). Besser: nur seit dem letzten Lauf geänderte Datensätze holen.
- Offen: Bei HTTP 5xx von ServiceNow sofort einmal wiederholen statt bis zum nächsten Takt zu warten.
- Offen: Zuordnung Kontakt → Account (1-30G zu 1-30K) in der CRM-Datenbank prüfen, sobald Database Actions wieder angemeldet ist.
- Offen: Testfälle 2–5 (Fehlerfälle) noch nicht mit 0.18 wiederholt.

## Abnahme und Aufräumen

Der Test gilt als bestanden, wenn Testfall 1 vollständig und die Testfälle 2 bis 6 mit dem erwarteten Ergebnis durchlaufen.

- [ ] Testfall 1: alle 9 Schritte OK, Durchlaufzeit Opportunity → SAP-Nummer an der Opportunity notiert
- [ ] Testfälle 2–6: Fehler im Assistenten erkannt, kein anderer Flow betroffen
- [ ] Keine Dubletten: je Opportunity genau ein CRM-Auftrag und ein SAP-Auftrag
- [ ] SAP-Gateway nach Testfall 4 wieder gestartet, Salesforce-Flows nach Testfall 5 fortgesetzt

Aufräumen nach dem Test:

1. Testaufträge in SAP stornieren oder mit Absagegrund versehen (VA02); der Statusflow sollte sie danach melden als „Storniert“.
2. Test-Opportunities in Salesforce auf eine eigene Phase setzen oder löschen.
3. CRM-Testaufträge bleiben als Nachweis stehen oder werden im CRM gelöscht.
4. Geänderte Telefonnummer aus Testfall 6 in ServiceNow zurücksetzen.
