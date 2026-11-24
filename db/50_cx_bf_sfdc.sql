-- BeyondFlow <-> Salesforce: Datenbankteil (als ADMIN im SQL Worksheet mit F5 "Skript ausfuehren")
SET DEFINE OFF

-- Stand der Synchronisation je Datensatz und System (Echo-Schutz in beide Richtungen)
DECLARE
  n NUMBER;
BEGIN
  SELECT COUNT(*) INTO n FROM all_tables WHERE owner = 'SIEBEL' AND table_name = 'CX_BF_SYNC_STATE';
  IF n = 0 THEN
    EXECUTE IMMEDIATE 'CREATE TABLE siebel.cx_bf_sync_state (
      tbl_name    VARCHAR2(30) NOT NULL,
      row_id      VARCHAR2(15) NOT NULL,
      ext_sys_cd  VARCHAR2(30) NOT NULL,
      in_hash     VARCHAR2(64),
      crm_hash    VARCHAR2(64),
      last_in_dt  DATE,
      last_out_dt DATE,
      CONSTRAINT cx_bf_sync_state_p PRIMARY KEY (tbl_name, row_id, ext_sys_cd))';
  END IF;
END;
/

CREATE OR REPLACE PACKAGE siebel.cx_bf_sfdc AS
  c_sys CONSTANT VARCHAR2(30) := 'SFDC';
  -- Fingerabdruck der synchronisierten Account- bzw. Auftragsfelder
  FUNCTION account_hash(p_row_id VARCHAR2) RETURN VARCHAR2;
  FUNCTION order_hash(p_row_id VARCHAR2) RETURN VARCHAR2;
  -- Salesforce -> CRM (Account, Contact): ueberspringt unveraenderte Daten, verknuepft Account-Dubletten ueber Name + Ort
  PROCEDURE sync_in(p_entity IN VARCHAR2, p_json IN VARCHAR2, o_row_id OUT VARCHAR2, o_action OUT VARCHAR2);
  -- CRM -> Salesforce: geaenderte Accounts als JSON-Array (max. 50)
  PROCEDURE accounts_out(o_json OUT VARCHAR2);
  PROCEDURE account_out_done(p_row_id IN VARCHAR2, p_sf_id IN VARCHAR2, p_hash IN VARCHAR2, o_result OUT VARCHAR2);
  -- Gewonnene Opportunity -> CRM-Auftrag mit Positionen, danach Uebergabe an SAP (Outbox)
  PROCEDURE create_order(p_json IN VARCHAR2, o_json OUT VARCHAR2);
  -- CRM-Auftragsstatus (SAP-Nummer, ERP-Status) -> Salesforce
  PROCEDURE orders_out(o_json OUT VARCHAR2);
  PROCEDURE order_out_done(p_row_id IN VARCHAR2, p_hash IN VARCHAR2, o_result OUT VARCHAR2);
END cx_bf_sfdc;
/

CREATE OR REPLACE PACKAGE BODY siebel.cx_bf_sfdc AS

  FUNCTION sha(p_text VARCHAR2) RETURN VARCHAR2 IS
    l VARCHAR2(64);
  BEGIN
    SELECT RAWTOHEX(STANDARD_HASH(NVL(p_text, '-'), 'SHA256')) INTO l FROM dual;
    RETURN l;
  END sha;

  FUNCTION account_hash(p_row_id VARCHAR2) RETURN VARCHAR2 IS
    l VARCHAR2(4000);
  BEGIN
    SELECT name || '|' || loc || '|' || ou_num || '|' || main_ph_num || '|' || url || '|' || indust_cd || '|' ||
           ou_type_cd || '|' || vat_regn_num || '|' || cust_stat_cd
      INTO l FROM siebel.s_org_ext WHERE row_id = p_row_id;
    RETURN sha(l);
  EXCEPTION WHEN NO_DATA_FOUND THEN RETURN NULL;
  END account_hash;

  FUNCTION order_hash(p_row_id VARCHAR2) RETURN VARCHAR2 IS
    l VARCHAR2(400);
  BEGIN
    SELECT order_num || '|' || erp_order_num || '|' || erp_status_cd INTO l FROM siebel.s_order WHERE row_id = p_row_id;
    RETURN sha(l);
  EXCEPTION WHEN NO_DATA_FOUND THEN RETURN NULL;
  END order_hash;

  FUNCTION ext_row(p_tbl VARCHAR2, p_ext VARCHAR2) RETURN VARCHAR2 IS
    l VARCHAR2(15);
  BEGIN
    SELECT record_id INTO l FROM siebel.s_ext_ref WHERE tbl_name = p_tbl AND ext_sys_cd = c_sys AND ext_id = p_ext AND ROWNUM = 1;
    RETURN l;
  EXCEPTION WHEN NO_DATA_FOUND THEN RETURN NULL;
  END ext_row;

  PROCEDURE link(p_tbl VARCHAR2, p_row_id VARCHAR2, p_ext VARCHAR2) IS
  BEGIN
    MERGE INTO siebel.s_ext_ref r
    USING (SELECT p_tbl t, p_row_id id FROM dual) s
       ON (r.tbl_name = s.t AND r.record_id = s.id AND r.ext_sys_cd = c_sys)
     WHEN MATCHED THEN UPDATE SET r.ext_id = p_ext, r.last_sync_dt = SYSDATE
     WHEN NOT MATCHED THEN INSERT (tbl_name, record_id, ext_sys_cd, ext_id, last_sync_dt)
                           VALUES (p_tbl, p_row_id, c_sys, p_ext, SYSDATE);
  END link;

  PROCEDURE set_state(p_tbl VARCHAR2, p_row_id VARCHAR2, p_in VARCHAR2, p_crm VARCHAR2, p_dir VARCHAR2) IS
  BEGIN
    MERGE INTO siebel.cx_bf_sync_state s
    USING (SELECT p_tbl t, p_row_id id FROM dual) x
       ON (s.tbl_name = x.t AND s.row_id = x.id AND s.ext_sys_cd = c_sys)
     WHEN MATCHED THEN UPDATE SET
          s.in_hash = NVL(p_in, s.in_hash), s.crm_hash = NVL(p_crm, s.crm_hash),
          s.last_in_dt = CASE WHEN p_dir = 'IN' THEN SYSDATE ELSE s.last_in_dt END,
          s.last_out_dt = CASE WHEN p_dir = 'OUT' THEN SYSDATE ELSE s.last_out_dt END
     WHEN NOT MATCHED THEN INSERT (tbl_name, row_id, ext_sys_cd, in_hash, crm_hash, last_in_dt, last_out_dt)
          VALUES (p_tbl, p_row_id, c_sys, p_in, p_crm,
                  CASE WHEN p_dir = 'IN' THEN SYSDATE END, CASE WHEN p_dir = 'OUT' THEN SYSDATE END);
  END set_state;

  PROCEDURE sync_in(p_entity IN VARCHAR2, p_json IN VARCHAR2, o_row_id OUT VARCHAR2, o_action OUT VARCHAR2) IS
    l_tbl  VARCHAR2(30) := siebel.cx_bf_api.table_for(p_entity);
    j      json_object_t := json_object_t.parse(p_json);
    l_ext  VARCHAR2(100) := j.get_string('_EXT_ID');
    l_in   VARCHAR2(64) := sha(p_json);
    l_row  VARCHAR2(15);
    l_old  VARCHAR2(64);
    l_name VARCHAR2(200);
    l_loc  VARCHAR2(200);
  BEGIN
    l_row := ext_row(l_tbl, l_ext);
    IF l_row IS NOT NULL THEN
      BEGIN
        SELECT in_hash INTO l_old FROM siebel.cx_bf_sync_state WHERE tbl_name = l_tbl AND row_id = l_row AND ext_sys_cd = c_sys;
      EXCEPTION WHEN NO_DATA_FOUND THEN l_old := NULL;
      END;
      IF l_old = l_in THEN
        o_row_id := l_row; o_action := 'UNVERAENDERT'; RETURN;
      END IF;
    ELSIF l_tbl = 'S_ORG_EXT' THEN
      -- Dublettenabgleich: gleicher Name und Ort, noch nicht mit Salesforce verknuepft -> verknuepfen statt neu anlegen
      l_name := j.get_string('NAME');
      l_loc  := j.get_string('LOC');
      BEGIN
        SELECT o.row_id INTO l_row FROM siebel.s_org_ext o
         WHERE o.name = l_name AND NVL(o.loc, '-') = NVL(l_loc, '-')
           AND NOT EXISTS (SELECT 1 FROM siebel.s_ext_ref r WHERE r.tbl_name = 'S_ORG_EXT' AND r.record_id = o.row_id AND r.ext_sys_cd = c_sys)
           AND ROWNUM = 1;
        link(l_tbl, l_row, l_ext);
      EXCEPTION WHEN NO_DATA_FOUND THEN NULL;
      END;
    END IF;
    siebel.cx_bf_api.upsert(p_entity, c_sys, p_json, o_row_id, o_action);
    set_state(l_tbl, o_row_id, l_in, CASE WHEN l_tbl = 'S_ORG_EXT' THEN account_hash(o_row_id) END, 'IN');
    COMMIT;
  END sync_in;

  PROCEDURE accounts_out(o_json OUT VARCHAR2) IS
    a json_array_t := json_array_t();
    o json_object_t;
  BEGIN
    FOR r IN (
      SELECT * FROM (
        SELECT v.*, s.crm_hash AS old_hash
          FROM (SELECT x.row_id, x.name, x.loc, x.ou_num, x.main_ph_num, x.url, x.indust_cd, x.ou_type_cd,
                       x.vat_regn_num, x.cust_stat_cd, x.last_upd, siebel.cx_bf_sfdc.account_hash(x.row_id) AS new_hash,
                       (SELECT e.ext_id FROM siebel.s_ext_ref e
                         WHERE e.tbl_name = 'S_ORG_EXT' AND e.record_id = x.row_id AND e.ext_sys_cd = 'SFDC' AND ROWNUM = 1) AS sf_id
                  FROM siebel.s_org_ext x
                 WHERE x.active_flg = 'Y') v
          LEFT JOIN siebel.cx_bf_sync_state s
            ON s.tbl_name = 'S_ORG_EXT' AND s.row_id = v.row_id AND s.ext_sys_cd = 'SFDC'
         WHERE s.crm_hash IS NULL OR s.crm_hash <> v.new_hash
         ORDER BY v.last_upd)
      WHERE ROWNUM <= 50) LOOP
      o := json_object_t();
      o.put('rowId', r.row_id);
      o.put('sfId', r.sf_id);
      o.put('hash', r.new_hash);
      o.put('NAME', r.name);
      o.put('LOC', r.loc);
      o.put('OU_NUM', r.ou_num);
      o.put('MAIN_PH_NUM', r.main_ph_num);
      o.put('URL', r.url);
      o.put('INDUST_CD', r.indust_cd);
      o.put('OU_TYPE_CD', r.ou_type_cd);
      o.put('VAT_REGN_NUM', r.vat_regn_num);
      o.put('CUST_STAT_CD', r.cust_stat_cd);
      a.append(o);
    END LOOP;
    o_json := a.to_string;
  END accounts_out;

  PROCEDURE account_out_done(p_row_id IN VARCHAR2, p_sf_id IN VARCHAR2, p_hash IN VARCHAR2, o_result OUT VARCHAR2) IS
  BEGIN
    link('S_ORG_EXT', p_row_id, p_sf_id);
    set_state('S_ORG_EXT', p_row_id, NULL, p_hash, 'OUT');
    COMMIT;
    o_result := 'OK';
  END account_out_done;

  PROCEDURE create_order(p_json IN VARCHAR2, o_json OUT VARCHAR2) IS
    j       json_object_t := json_object_t.parse(p_json);
    r       json_object_t := json_object_t();
    items   json_array_t;
    it      json_object_t;
    l_opp   VARCHAR2(100) := j.get_string('_EXT_ID');
    l_acc   VARCHAR2(15);
    l_bu    VARCHAR2(15);
    l_ord   VARCHAR2(15);
    l_num   VARCHAR2(30);
    l_prod  VARCHAR2(15);
    l_uom   VARCHAR2(30);
    l_qty   NUMBER;
    l_pri   NUMBER;
    l_sum   NUMBER := 0;
    l_dt    DATE;
    l_acc_ext VARCHAR2(100) := j.get_string('ACCOUNT_EXT_ID');
    l_part  VARCHAR2(100);
    l_amt   NUMBER := j.get_number('ORDER_AMT');
  BEGIN
    -- schon angelegt? (Wiederholung, z. B. wenn das Rueckschreiben nach Salesforce fehlgeschlagen ist)
    l_ord := ext_row('S_ORDER', l_opp);
    IF l_ord IS NOT NULL THEN
      SELECT order_num INTO l_num FROM siebel.s_order WHERE row_id = l_ord;
      r.put('ok', TRUE); r.put('existing', TRUE); r.put('orderNum', l_num); r.put('orderId', l_ord);
      o_json := r.to_string; RETURN;
    END IF;
    l_acc := ext_row('S_ORG_EXT', l_acc_ext);
    IF l_acc IS NULL THEN
      r.put('ok', FALSE); r.put('message', 'Account ' || l_acc_ext || ' ist noch nicht im CRM (wird beim naechsten Account-Abgleich uebernommen)');
      o_json := r.to_string; RETURN;
    END IF;
    items := j.get_array('ITEMS');
    IF items IS NULL OR items.get_size = 0 THEN
      r.put('ok', FALSE); r.put('message', 'Opportunity hat keine Produkte'); o_json := r.to_string; RETURN;
    END IF;
    -- Produkte vorab pruefen
    FOR k IN 0 .. items.get_size - 1 LOOP
      it := TREAT(items.get(k) AS json_object_t);
      l_part := it.get_string('PART_NUM');
      BEGIN
        SELECT row_id INTO l_prod FROM siebel.s_prod_int WHERE part_num = l_part AND active_flg = 'Y' AND ROWNUM = 1;
      EXCEPTION WHEN NO_DATA_FOUND THEN
        r.put('ok', FALSE); r.put('message', 'Produktcode ' || NVL(l_part, '(leer)') || ' gibt es im CRM nicht (Artikel-Nr.)');
        o_json := r.to_string; RETURN;
      END;
    END LOOP;

    SELECT bu_id INTO l_bu FROM siebel.s_org_ext WHERE row_id = l_acc;
    BEGIN
      l_dt := TO_DATE(SUBSTR(j.get_string('ORDER_DT'), 1, 10), 'YYYY-MM-DD');
    EXCEPTION WHEN OTHERS THEN l_dt := TRUNC(SYSDATE);
    END;
    INSERT INTO siebel.s_order (bu_id, last_upd_by, order_dt, accnt_id, status_cd, order_amt)
    VALUES (l_bu, '0-1', NVL(l_dt, TRUNC(SYSDATE)), l_acc, 'Bestätigt', l_amt)
    RETURNING row_id, order_num INTO l_ord, l_num;

    FOR k IN 0 .. items.get_size - 1 LOOP
      it := TREAT(items.get(k) AS json_object_t);
      l_part := it.get_string('PART_NUM');
      SELECT row_id, uom_cd INTO l_prod, l_uom FROM siebel.s_prod_int
       WHERE part_num = l_part AND active_flg = 'Y' AND ROWNUM = 1;
      l_qty := NVL(it.get_number('QTY_REQ'), 1);
      l_pri := it.get_number('NET_PRI');
      INSERT INTO siebel.s_order_item (bu_id, last_upd_by, order_id, ln_num, prod_id, qty_req, uom_cd, net_pri, line_total, status_cd)
      VALUES (l_bu, '0-1', l_ord, k + 1, l_prod, l_qty, l_uom, l_pri, ROUND(l_qty * NVL(l_pri, 0), 2), 'Neu');
      l_sum := l_sum + l_qty * NVL(l_pri, 0);
    END LOOP;
    UPDATE siebel.s_order SET order_amt = NVL(order_amt, l_sum) WHERE row_id = l_ord;
    SELECT order_num INTO l_num FROM siebel.s_order WHERE row_id = l_ord;

    link('S_ORDER', l_ord, l_opp);
    siebel.cx_sap_s4.submit_order(l_ord);
    COMMIT;
    r.put('ok', TRUE); r.put('existing', FALSE); r.put('orderNum', l_num); r.put('orderId', l_ord);
    o_json := r.to_string;
  EXCEPTION WHEN OTHERS THEN
    ROLLBACK;
    r := json_object_t();
    r.put('ok', FALSE); r.put('message', 'CRM: ' || SQLERRM);
    o_json := r.to_string;
  END create_order;

  PROCEDURE orders_out(o_json OUT VARCHAR2) IS
    a json_array_t := json_array_t();
    o json_object_t;
  BEGIN
    FOR r IN (
      SELECT * FROM (
        SELECT so.row_id, so.order_num, so.erp_order_num, so.erp_status_cd, e.ext_id AS sf_id,
               siebel.cx_bf_sfdc.order_hash(so.row_id) AS new_hash, s.crm_hash AS old_hash
          FROM siebel.s_order so
          JOIN siebel.s_ext_ref e ON e.tbl_name = 'S_ORDER' AND e.record_id = so.row_id AND e.ext_sys_cd = 'SFDC'
          LEFT JOIN siebel.cx_bf_sync_state s ON s.tbl_name = 'S_ORDER' AND s.row_id = so.row_id AND s.ext_sys_cd = 'SFDC'
         ORDER BY so.last_upd)
      WHERE old_hash IS NULL OR old_hash <> new_hash) LOOP
      o := json_object_t();
      o.put('rowId', r.row_id);
      o.put('sfId', r.sf_id);
      o.put('hash', r.new_hash);
      o.put('ORDER_NUM', r.order_num);
      o.put('ERP_ORDER_NUM', r.erp_order_num);
      o.put('ERP_STATUS_CD', r.erp_status_cd);
      a.append(o);
      EXIT WHEN a.get_size >= 50;
    END LOOP;
    o_json := a.to_string;
  END orders_out;

  PROCEDURE order_out_done(p_row_id IN VARCHAR2, p_hash IN VARCHAR2, o_result OUT VARCHAR2) IS
  BEGIN
    set_state('S_ORDER', p_row_id, NULL, p_hash, 'OUT');
    COMMIT;
    o_result := 'OK';
  END order_out_done;

END cx_bf_sfdc;
/

GRANT EXECUTE ON siebel.cx_bf_sfdc TO beyondflow;

SELECT object_name, object_type, status FROM all_objects
 WHERE owner = 'SIEBEL' AND object_name IN ('CX_BF_SFDC', 'CX_BF_SYNC_STATE') ORDER BY object_type;
