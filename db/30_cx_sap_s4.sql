-- BeyondFlow: SAP-Ausgangsqueue CX_SAP_S4 und Sichten fuer Auftraege (als ADMIN, Skript ausfuehren / F5)
-- Exportiert aus der Referenz-DB am 25.11.2026 (DBMS_METADATA). Wiederholbar.
-- Benutzt die CRM-Tabellen S_ORDER, S_ORDER_ITEM, S_ORG_EXT, S_PROD_INT, S_EXT_REF, S_INT_OUTBOX, S_LST_OF_VAL.

-- ---------------------------------------------------------------- Sichten
CREATE OR REPLACE FORCE EDITIONABLE VIEW "SIEBEL"."CX_BF_ORDER_V" ("ROW_ID", "ORDER_NUM", "ORDER_DT", "ACCOUNT_NAME", "STATUS_CD", "ERP_STATUS_CD", "ERP_ORDER_NUM", "ORDER_AMT", "ITEM_CNT", "LAST_UPD") AS
  SELECT o.row_id, o.order_num, o.order_dt, a.name account_name, o.status_cd, o.erp_status_cd, o.erp_order_num, o.order_amt,
       (SELECT COUNT(*) FROM siebel.s_order_item i WHERE i.order_id = o.row_id) item_cnt, o.last_upd
FROM siebel.s_order o LEFT JOIN siebel.s_org_ext a ON a.row_id = o.accnt_id;

-- Auftragskopf fuer SAP: Kundennummer aus S_EXT_REF (System SAP_S4), Vertriebsbereich aus LOV SAP_S4_CONFIG
CREATE OR REPLACE FORCE EDITIONABLE VIEW "SIEBEL"."CX_SAP_ORDER_HDR_V" ("OUTBOX_ID", "RETRY_CNT", "ORDER_ID", "ORDER_NUM", "ERP_ORDER_NUM", "ACCOUNT_NAME", "KUNNR", "VKORG", "VTWEG", "SPART", "AUART") AS
  SELECT ob.row_id outbox_id, ob.retry_cnt, o.row_id order_id, o.order_num, o.erp_order_num, a.name account_name,
  (SELECT ext_id FROM siebel.s_ext_ref e WHERE e.tbl_name='S_ORG_EXT' AND e.record_id=o.accnt_id AND e.ext_sys_cd='SAP_S4') kunnr,
  (SELECT val FROM siebel.s_lst_of_val WHERE type='SAP_S4_CONFIG' AND name='VKORG') vkorg,
  (SELECT val FROM siebel.s_lst_of_val WHERE type='SAP_S4_CONFIG' AND name='VTWEG') vtweg,
  (SELECT val FROM siebel.s_lst_of_val WHERE type='SAP_S4_CONFIG' AND name='SPART') spart,
  (SELECT val FROM siebel.s_lst_of_val WHERE type='SAP_S4_CONFIG' AND name='AUART') auart
FROM siebel.s_int_outbox ob JOIN siebel.s_order o ON o.row_id=ob.record_id LEFT JOIN siebel.s_org_ext a ON a.row_id=o.accnt_id
WHERE ob.ext_sys_cd='SAP_S4' AND ob.tbl_name='S_ORDER';

-- Auftragspositionen fuer SAP: Materialnummer aus S_EXT_REF (Produkt, System SAP_S4)
CREATE OR REPLACE FORCE EDITIONABLE VIEW "SIEBEL"."CX_SAP_ORDER_ITM_V" ("ORDER_ID", "LN_NUM", "PROD_NAME", "QTY_REQ", "MATNR") AS
  SELECT i.order_id, i.ln_num, p.name prod_name, i.qty_req,
  (SELECT ext_id FROM siebel.s_ext_ref e WHERE e.tbl_name='S_PROD_INT' AND e.record_id=i.prod_id AND e.ext_sys_cd='SAP_S4') matnr
FROM siebel.s_order_item i LEFT JOIN siebel.s_prod_int p ON p.row_id=i.prod_id;

-- ---------------------------------------------------------------- Paket
CREATE OR REPLACE EDITIONABLE PACKAGE "SIEBEL"."CX_SAP_S4" AS
  PROCEDURE submit_order(p_order_id VARCHAR2);
  FUNCTION claim_next RETURN VARCHAR2;
  PROCEDURE complete_ok(p_outbox_id VARCHAR2, p_vbeln VARCHAR2, p_msg VARCHAR2);
  PROCEDURE complete_err(p_outbox_id VARCHAR2, p_msg VARCHAR2, p_technical VARCHAR2 DEFAULT 'N');
END cx_sap_s4;
/
CREATE OR REPLACE EDITIONABLE PACKAGE BODY "SIEBEL"."CX_SAP_S4" AS
  c_max_retry CONSTANT PLS_INTEGER := 5;
  PROCEDURE submit_order(p_order_id VARCHAR2) IS
    l_erp VARCHAR2(100); l_open NUMBER;
  BEGIN
    SELECT erp_order_num INTO l_erp FROM siebel.s_order WHERE row_id = p_order_id;
    IF l_erp IS NOT NULL THEN RAISE_APPLICATION_ERROR(-20100, 'Auftrag ist bereits in SAP angelegt: ' || l_erp); END IF;
    SELECT COUNT(*) INTO l_open FROM siebel.s_int_outbox WHERE ext_sys_cd='SAP_S4' AND tbl_name='S_ORDER' AND record_id=p_order_id AND status_cd IN ('Neu','In Arbeit');
    IF l_open > 0 THEN RAISE_APPLICATION_ERROR(-20101, 'Auftrag wird bereits an SAP übergeben.'); END IF;
    INSERT INTO siebel.s_int_outbox (ext_sys_cd, tbl_name, record_id, operation_cd, status_cd, retry_cnt)
    VALUES ('SAP_S4', 'S_ORDER', p_order_id, 'Anlegen', 'Neu', 0);
    UPDATE siebel.s_order SET status_cd = 'An ERP übergeben', erp_status_cd = 'Übergeben' WHERE row_id = p_order_id;
  END;
  FUNCTION claim_next RETURN VARCHAR2 IS
    l_id VARCHAR2(15);
    CURSOR c IS SELECT row_id FROM siebel.s_int_outbox
      WHERE ext_sys_cd='SAP_S4' AND tbl_name='S_ORDER'
        AND (status_cd='Neu' OR (status_cd='In Arbeit' AND last_upd < SYSDATE - 10/1440))
        AND (NVL(retry_cnt,0)=0 OR last_upd < SYSDATE - POWER(2, retry_cnt)/1440)
      ORDER BY created FOR UPDATE SKIP LOCKED;
  BEGIN
    OPEN c; FETCH c INTO l_id;
    IF c%FOUND THEN
      UPDATE siebel.s_int_outbox SET status_cd='In Arbeit' WHERE CURRENT OF c;
    END IF;
    CLOSE c;
    COMMIT;
    RETURN l_id;
  END;
  PROCEDURE complete_ok(p_outbox_id VARCHAR2, p_vbeln VARCHAR2, p_msg VARCHAR2) IS
    l_order VARCHAR2(15);
  BEGIN
    SELECT record_id INTO l_order FROM siebel.s_int_outbox WHERE row_id = p_outbox_id;
    UPDATE siebel.s_order SET erp_order_num = p_vbeln, erp_status_cd = 'Angelegt', status_cd = 'Bestätigt' WHERE row_id = l_order;
    MERGE INTO siebel.s_ext_ref e USING (SELECT 'S_ORDER' t, l_order r FROM dual) x
      ON (e.tbl_name = x.t AND e.record_id = x.r AND e.ext_sys_cd = 'SAP_S4')
      WHEN MATCHED THEN UPDATE SET e.ext_id = p_vbeln, e.last_sync_dt = SYSDATE
      WHEN NOT MATCHED THEN INSERT (tbl_name, record_id, ext_sys_cd, ext_id, last_sync_dt) VALUES ('S_ORDER', l_order, 'SAP_S4', p_vbeln, SYSDATE);
    UPDATE siebel.s_int_outbox SET status_cd='Verarbeitet', processed_dt=SYSDATE, err_msg=SUBSTR(p_msg,1,2000),
      payload = 'SAP-Auftrag ' || p_vbeln WHERE row_id = p_outbox_id;
    COMMIT;
  END;
  PROCEDURE complete_err(p_outbox_id VARCHAR2, p_msg VARCHAR2, p_technical VARCHAR2 DEFAULT 'N') IS
    l_order VARCHAR2(15); l_retry NUMBER;
  BEGIN
    SELECT record_id, NVL(retry_cnt,0) INTO l_order, l_retry FROM siebel.s_int_outbox WHERE row_id = p_outbox_id;
    IF p_technical = 'Y' AND l_retry + 1 < c_max_retry THEN
      UPDATE siebel.s_int_outbox SET status_cd='Neu', retry_cnt=l_retry+1, err_msg=SUBSTR('Technisch (Versuch '||(l_retry+1)||'): '||p_msg,1,2000) WHERE row_id = p_outbox_id;
    ELSE
      UPDATE siebel.s_int_outbox SET status_cd='Fehler', retry_cnt=l_retry+1, processed_dt=SYSDATE, err_msg=SUBSTR(p_msg,1,2000) WHERE row_id = p_outbox_id;
      UPDATE siebel.s_order SET erp_status_cd='Fehler', status_cd='Neu' WHERE row_id = l_order;
    END IF;
    COMMIT;
  END;
END cx_sap_s4;
/

GRANT EXECUTE ON siebel.cx_sap_s4 TO beyondflow;
GRANT SELECT  ON siebel.cx_bf_order_v TO beyondflow;

-- ---------------------------------------------------------------- Kundeneinstellungen SAP (Vertriebsbereich, Auftragsart)
-- Werte des Kunden in der CRM-Werteliste SAP_S4_CONFIG pflegen (Referenz: VKORG=1020, VTWEG=10, SPART=00, AUART=TA).
-- Pruefen, ob alle vier Eintraege vorhanden sind:
SELECT name, val FROM siebel.s_lst_of_val WHERE type = 'SAP_S4_CONFIG' ORDER BY name;
-- Ausserdem braucht jeder Kunde (S_ORG_EXT) und jedes Produkt (S_PROD_INT) einen Eintrag in S_EXT_REF
-- mit EXT_SYS_CD = 'SAP_S4' und der SAP-Kundennummer bzw. Materialnummer als EXT_ID.

SELECT object_name, object_type, status FROM all_objects
 WHERE owner = 'SIEBEL' AND object_name IN ('CX_SAP_S4','CX_BF_ORDER_V','CX_SAP_ORDER_HDR_V','CX_SAP_ORDER_ITM_V') ORDER BY 1, 2;
