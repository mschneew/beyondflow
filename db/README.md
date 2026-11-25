# Datenbank-Skripte (Schema SIEBEL)

Als `ADMIN` in Database Actions → SQL, in Nummernreihenfolge als Skript (F5) ausführen. Alle Skripte sind wiederholbar.

| Skript | Inhalt |
|---|---|
| `10_beyondflow_user.sql` | Profil SERVICE_ACCOUNT, Benutzer BEYONDFLOW (Passwort als Ersetzungsvariable) |
| `20_cx_bf_api.sql` | Paket CX_BF_API |
| `30_cx_sap_s4.sql` | Paket CX_SAP_S4, Sichten CX_BF_ORDER_V, CX_SAP_ORDER_HDR_V, CX_SAP_ORDER_ITM_V |
| `40_cx_bf_sap.sql` | Paket CX_BF_SAP |
| `50_cx_bf_sfdc.sql` | Paket CX_BF_SFDC, Tabelle CX_BF_SYNC_STATE |
| `60_grants.sql` | Rechte von BEYONDFLOW, Kontrolle |
| `70_cx_bf_check.sql` | Paket CX_BF_CHECK (nur lesende Prüfabfragen) |

Stand: 25.11.2026 aus der Referenz-DB exportiert. Die Pakete und Sichten sind per Prüfsumme mit der Datenbank abgeglichen.

Voraussetzung sind die BeyondCRM-Tabellen S_ORG_EXT, S_CONTACT, S_ORDER, S_ORDER_ITEM, S_PROD_INT, S_EXT_REF, S_INT_OUTBOX und S_LST_OF_VAL.

Nach dem Einspielen eines geänderten Pakets die Laufzeit neu starten. Sonst kommt ORA-04065, weil die Sitzungen im Pool den alten Stand halten.
