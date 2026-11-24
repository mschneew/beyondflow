# Datenbank-Skripte (Schema SIEBEL)

Als `ADMIN` in Database Actions → SQL, in Nummernreihenfolge als Skript ausführen. Alle Skripte sind wiederholbar (`create or replace`).

| Skript | Stand |
|---|---|
| `10_beyondflow_user.sql` – Benutzer BEYONDFLOW, Profil, Rechte | folgt (Export aus der Referenz-DB) |
| `20_cx_bf_api.sql` – Paket CX_BF_API | folgt |
| `30_cx_sap_s4.sql` – Paket CX_SAP_S4, Sichten CX_BF_ORDER_V, CX_SAP_ORDER_HDR_V, CX_SAP_ORDER_ITM_V | folgt |
| `40_cx_bf_sap.sql` – Paket CX_BF_SAP | vorhanden |
| `50_cx_bf_sfdc.sql` – Paket CX_BF_SFDC, Tabelle CX_BF_SYNC_STATE | vorhanden |

Nach dem Einspielen eines geänderten Pakets die Laufzeit neu starten (ORA-04065 durch gepoolte Sitzungen).
