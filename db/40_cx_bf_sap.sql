-- BeyondFlow <-> SAP: Datenbankteil (als ADMIN im SQL Worksheet ausfuehren, "Skript ausfuehren" / F5)
SET DEFINE OFF

CREATE OR REPLACE PACKAGE siebel.cx_bf_sap AS
  -- Holt den naechsten Outbox-Eintrag (System SAP_S4) und liefert ihn als JSON.
  -- NULL = nichts zu tun. Enthaelt das JSON "error", ist der Auftrag fachlich unvollstaendig.
  PROCEDURE next_order(o_json OUT VARCHAR2);
  -- Schreibt den ERP-Status (aus SAP) an den CRM-Auftrag mit dieser SAP-Nummer.
  PROCEDURE set_erp_status(p_erp_order_num IN VARCHAR2, p_status IN VARCHAR2, o_result OUT VARCHAR2);
END cx_bf_sap;
/

CREATE OR REPLACE PACKAGE BODY siebel.cx_bf_sap AS

  PROCEDURE next_order(o_json OUT VARCHAR2) IS
    l_outbox   VARCHAR2(15);
    l_order_id VARCHAR2(15);
    l_num      VARCHAR2(30);
    l_kunnr    VARCHAR2(50);
    l_vkorg    VARCHAR2(30);
    l_vtweg    VARCHAR2(30);
    l_spart    VARCHAR2(30);
    l_auart    VARCHAR2(30);
    l_erp      VARCHAR2(50);
    l_account  VARCHAR2(200);
    l_retry    NUMBER;
    l_err      VARCHAR2(400);
    j  json_object_t := json_object_t();
    a  json_array_t  := json_array_t();
    it json_object_t;
  BEGIN
    o_json := NULL;
    l_outbox := siebel.cx_sap_s4.claim_next;
    IF l_outbox IS NULL THEN RETURN; END IF;
    j.put('outboxId', l_outbox);
    BEGIN
      SELECT order_id, order_num, kunnr, vkorg, vtweg, spart, auart, erp_order_num, account_name, NVL(retry_cnt, 0)
        INTO l_order_id, l_num, l_kunnr, l_vkorg, l_vtweg, l_spart, l_auart, l_erp, l_account, l_retry
        FROM siebel.cx_sap_order_hdr_v WHERE outbox_id = l_outbox;
    EXCEPTION WHEN NO_DATA_FOUND THEN
      j.put('error', 'Auftrag zur Outbox-ID ' || l_outbox || ' nicht gefunden');
      o_json := j.to_string; RETURN;
    END;
    j.put('orderNum', l_num);
    j.put('accountName', l_account);
    j.put('kunnr', l_kunnr);
    j.put('vkorg', l_vkorg);
    j.put('vtweg', l_vtweg);
    j.put('spart', l_spart);
    j.put('auart', l_auart);
    j.put('erpOrderNum', l_erp);
    j.put('retry', l_retry);
    FOR r IN (SELECT ln_num, prod_name, matnr, qty_req FROM siebel.cx_sap_order_itm_v WHERE order_id = l_order_id ORDER BY ln_num) LOOP
      it := json_object_t();
      it.put('ln', r.ln_num);
      it.put('product', r.prod_name);
      it.put('matnr', r.matnr);
      it.put('qty', NVL(r.qty_req, 1));
      a.append(it);
      IF r.matnr IS NULL AND l_err IS NULL THEN
        l_err := 'Produkt ''' || r.prod_name || ''' hat keine SAP-Materialnummer (Externe IDs, System SAP_S4)';
      END IF;
    END LOOP;
    j.put('items', a);
    IF l_erp IS NULL THEN
      IF l_kunnr IS NULL THEN
        l_err := 'Account ''' || l_account || ''' hat keine SAP-Kundennummer (Externe IDs, System SAP_S4)';
      ELSIF l_vkorg IS NULL OR l_vtweg IS NULL OR l_spart IS NULL OR l_auart IS NULL THEN
        l_err := 'SAP-Konfiguration unvollstaendig (Werteliste SAP_S4_CONFIG)';
      ELSIF a.get_size = 0 THEN
        l_err := 'Auftrag hat keine Positionen';
      END IF;
      IF l_err IS NOT NULL THEN j.put('error', l_err); END IF;
    END IF;
    o_json := j.to_string;
  END next_order;

  PROCEDURE set_erp_status(p_erp_order_num IN VARCHAR2, p_status IN VARCHAR2, o_result OUT VARCHAR2) IS
  BEGIN
    IF p_status NOT IN ('Angelegt', 'Bestätigt', 'Geliefert', 'Fakturiert', 'Storniert') THEN
      o_result := 'UNGUELTIG'; RETURN;
    END IF;
    UPDATE siebel.s_order
       SET erp_status_cd = p_status
     WHERE erp_order_num = p_erp_order_num
       AND NVL(erp_status_cd, '-') <> p_status;
    o_result := CASE WHEN SQL%ROWCOUNT > 0 THEN 'UPDATE' ELSE 'UNVERAENDERT' END;
    COMMIT;
  END set_erp_status;

END cx_bf_sap;
/

GRANT EXECUTE ON siebel.cx_bf_sap TO beyondflow;
GRANT EXECUTE ON siebel.cx_sap_s4 TO beyondflow;

SELECT object_name, object_type, status FROM all_objects WHERE owner = 'SIEBEL' AND object_name = 'CX_BF_SAP';
