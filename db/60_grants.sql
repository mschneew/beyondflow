-- BeyondFlow: Rechte des Benutzers BEYONDFLOW (als ADMIN, nach den Skripten 10–50)
-- Exportiert aus der Referenz-DB am 25.11.2026. Wiederholbar.
-- BEYONDFLOW sieht nur diese Pakete und die Auftragssicht, keine Tabellen.
GRANT EXECUTE ON siebel.cx_bf_api   TO beyondflow;
GRANT EXECUTE ON siebel.cx_sap_s4   TO beyondflow;
GRANT EXECUTE ON siebel.cx_bf_sap   TO beyondflow;
GRANT EXECUTE ON siebel.cx_bf_sfdc  TO beyondflow;
GRANT SELECT  ON siebel.cx_bf_order_v TO beyondflow;
-- ab 0.19 (nach Skript 70): GRANT EXECUTE ON siebel.cx_bf_check TO beyondflow;

-- Kontrolle: 5 Zeilen
SELECT privilege, table_name FROM dba_tab_privs WHERE grantee = 'BEYONDFLOW' ORDER BY table_name;
