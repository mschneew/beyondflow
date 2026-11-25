-- BeyondFlow: Pruefabfragen fuer Tests und Betrieb (als ADMIN, Skript ausfuehren / F5). Wiederholbar.
-- Nur lesend, feste Abfragen (kein frei eingebbares SQL). Ergebnis ist JSON.
-- Aufruf durch den Flow crm-check, im Studio unter /api/crm/check/<typ>/<schluessel>:
--   auftrag/<Auftragsnummer>     z. B. auftrag/AU-2026-0408   Kopf, Positionen, Externe IDs, SAP-Outbox
--   account/<ROW_ID>             z. B. account/1-30K          Stammdaten, Externe IDs, Anzahl Kontakte
--   kontakt/<ROW_ID>             z. B. kontakt/1-30G          Stammdaten, zugeordneter Account, Externe IDs
--   extern/<System>:<Externe ID> z. B. extern/SNOW:71a2...    Welcher CRM-Datensatz gehoert zu einer externen ID?

CREATE OR REPLACE PACKAGE siebel.cx_bf_check AS
  PROCEDURE get(p_type IN VARCHAR2, p_key IN VARCHAR2, o_json OUT VARCHAR2);
END cx_bf_check;
/
CREATE OR REPLACE PACKAGE BODY siebel.cx_bf_check AS

  FUNCTION refs(p_tbl VARCHAR2, p_row VARCHAR2) RETURN VARCHAR2 IS
    l VARCHAR2(4000);
  BEGIN
    SELECT JSON_ARRAYAGG(JSON_OBJECT('system' VALUE ext_sys_cd, 'id' VALUE ext_id,
                                     'sync' VALUE TO_CHAR(last_sync_dt, 'YYYY-MM-DD"T"HH24:MI:SS'))
                         ORDER BY ext_sys_cd RETURNING VARCHAR2(4000))
      INTO l FROM siebel.s_ext_ref WHERE tbl_name = p_tbl AND record_id = p_row;
    RETURN NVL(l, '[]');
  END;

  PROCEDURE get(p_type IN VARCHAR2, p_key IN VARCHAR2, o_json OUT VARCHAR2) IS
    l_row VARCHAR2(15);
    l_sys VARCHAR2(30);
    l_ext VARCHAR2(200);
  BEGIN
    CASE LOWER(p_type)
    WHEN 'auftrag' THEN
      SELECT MAX(row_id) INTO l_row FROM siebel.s_order WHERE order_num = p_key;
      IF l_row IS NULL THEN o_json := JSON_OBJECT('found' VALUE 'false' FORMAT JSON, 'typ' VALUE 'auftrag', 'key' VALUE p_key); RETURN; END IF;
      SELECT JSON_OBJECT(
               'found' VALUE 'true' FORMAT JSON, 'typ' VALUE 'auftrag', 'rowId' VALUE o.row_id,
               'auftrag' VALUE o.order_num, 'datum' VALUE TO_CHAR(o.order_dt, 'YYYY-MM-DD'),
               'status' VALUE o.status_cd, 'erpStatus' VALUE o.erp_status_cd, 'erpAuftrag' VALUE o.erp_order_num,
               'betrag' VALUE o.order_amt, 'accountId' VALUE o.accnt_id, 'account' VALUE a.name,
               'geaendert' VALUE TO_CHAR(o.last_upd, 'YYYY-MM-DD"T"HH24:MI:SS'),
               'positionen' VALUE (SELECT JSON_ARRAYAGG(JSON_OBJECT('pos' VALUE i.ln_num, 'produkt' VALUE p.name,
                                                                    'artikel' VALUE p.part_num, 'menge' VALUE i.qty_req)
                                                        ORDER BY i.ln_num RETURNING VARCHAR2(8000))
                                     FROM siebel.s_order_item i LEFT JOIN siebel.s_prod_int p ON p.row_id = i.prod_id
                                    WHERE i.order_id = o.row_id) FORMAT JSON,
               'externeIds' VALUE refs('S_ORDER', o.row_id) FORMAT JSON,
               'sapOutbox' VALUE (SELECT JSON_ARRAYAGG(JSON_OBJECT('status' VALUE b.status_cd, 'versuche' VALUE b.retry_cnt,
                                                                   'meldung' VALUE SUBSTR(b.err_msg, 1, 500),
                                                                   'angelegt' VALUE TO_CHAR(b.created, 'YYYY-MM-DD"T"HH24:MI:SS'))
                                                       ORDER BY b.created RETURNING VARCHAR2(8000))
                                    FROM siebel.s_int_outbox b
                                   WHERE b.tbl_name = 'S_ORDER' AND b.record_id = o.row_id) FORMAT JSON
               RETURNING VARCHAR2(32767))
        INTO o_json
        FROM siebel.s_order o LEFT JOIN siebel.s_org_ext a ON a.row_id = o.accnt_id
       WHERE o.row_id = l_row;

    WHEN 'account' THEN
      SELECT MAX(row_id) INTO l_row FROM siebel.s_org_ext WHERE row_id = p_key;
      IF l_row IS NULL THEN o_json := JSON_OBJECT('found' VALUE 'false' FORMAT JSON, 'typ' VALUE 'account', 'key' VALUE p_key); RETURN; END IF;
      SELECT JSON_OBJECT(
               'found' VALUE 'true' FORMAT JSON, 'typ' VALUE 'account', 'rowId' VALUE a.row_id,
               'name' VALUE a.name, 'ort' VALUE a.loc, 'nummer' VALUE a.ou_num, 'telefon' VALUE a.main_ph_num,
               'web' VALUE a.url, 'geaendert' VALUE TO_CHAR(a.last_upd, 'YYYY-MM-DD"T"HH24:MI:SS'),
               'kontakte' VALUE (SELECT COUNT(*) FROM siebel.s_contact c WHERE c.pr_dept_ou_id = a.row_id),
               'externeIds' VALUE refs('S_ORG_EXT', a.row_id) FORMAT JSON
               RETURNING VARCHAR2(32767))
        INTO o_json FROM siebel.s_org_ext a WHERE a.row_id = l_row;

    WHEN 'kontakt' THEN
      SELECT MAX(row_id) INTO l_row FROM siebel.s_contact WHERE row_id = p_key;
      IF l_row IS NULL THEN o_json := JSON_OBJECT('found' VALUE 'false' FORMAT JSON, 'typ' VALUE 'kontakt', 'key' VALUE p_key); RETURN; END IF;
      SELECT JSON_OBJECT(
               'found' VALUE 'true' FORMAT JSON, 'typ' VALUE 'kontakt', 'rowId' VALUE c.row_id,
               'vorname' VALUE c.fst_name, 'nachname' VALUE c.last_name, 'email' VALUE c.email_addr,
               'accountId' VALUE c.pr_dept_ou_id, 'account' VALUE a.name,
               'geaendert' VALUE TO_CHAR(c.last_upd, 'YYYY-MM-DD"T"HH24:MI:SS'),
               'externeIds' VALUE refs('S_CONTACT', c.row_id) FORMAT JSON
               RETURNING VARCHAR2(32767))
        INTO o_json
        FROM siebel.s_contact c LEFT JOIN siebel.s_org_ext a ON a.row_id = c.pr_dept_ou_id
       WHERE c.row_id = l_row;

    WHEN 'extern' THEN
      l_sys := UPPER(SUBSTR(p_key, 1, INSTR(p_key, ':') - 1));
      l_ext := SUBSTR(p_key, INSTR(p_key, ':') + 1);
      SELECT JSON_OBJECT('found' VALUE CASE WHEN COUNT(*) > 0 THEN 'true' ELSE 'false' END FORMAT JSON,
                         'typ' VALUE 'extern', 'system' VALUE l_sys, 'id' VALUE l_ext,
                         'treffer' VALUE JSON_ARRAYAGG(JSON_OBJECT('tabelle' VALUE tbl_name, 'rowId' VALUE record_id,
                                                                   'sync' VALUE TO_CHAR(last_sync_dt, 'YYYY-MM-DD"T"HH24:MI:SS'))
                                                       RETURNING VARCHAR2(4000)) FORMAT JSON
                         RETURNING VARCHAR2(32767))
        INTO o_json FROM siebel.s_ext_ref WHERE ext_sys_cd = l_sys AND ext_id = l_ext;

    ELSE
      o_json := JSON_OBJECT('found' VALUE 'false' FORMAT JSON, 'fehler' VALUE 'Unbekannter Typ (erlaubt: auftrag, account, kontakt, extern)');
    END CASE;
  END;
END cx_bf_check;
/

GRANT EXECUTE ON siebel.cx_bf_check TO beyondflow;

-- Kontrolle
SELECT object_type, status FROM all_objects WHERE owner = 'SIEBEL' AND object_name = 'CX_BF_CHECK';
