-- BeyondFlow: generischer CRM-Konnektor CX_BF_API (als ADMIN, Skript ausfuehren / F5)
-- Exportiert aus der Referenz-DB am 25.11.2026 (DBMS_METADATA). Wiederholbar.
-- Benutzt die CRM-Tabellen S_ORG_EXT, S_CONTACT und S_EXT_REF (Teil von BeyondCRM).

CREATE OR REPLACE EDITIONABLE PACKAGE "SIEBEL"."CX_BF_API" AS
  -- Generischer CRM-Konnektor fuer BeyondFlow.
  -- p_entity: Account | Contact (erweiterbar), p_system: Quellsystem (z. B. SNOW)
  -- p_json:   flaches JSON-Objekt, Schluessel = CRM-Spalten; Sonderschluessel:
  --           _EXT_ID                    externe ID im Quellsystem (Pflicht)
  --           _LOOKUP:<TABELLE>:<SPALTE> externe ID eines verknuepften Datensatzes -> CRM-ROW_ID in <SPALTE>
  PROCEDURE upsert(p_entity IN VARCHAR2, p_system IN VARCHAR2, p_json IN VARCHAR2,
                   o_row_id OUT VARCHAR2, o_action OUT VARCHAR2);
  FUNCTION table_for(p_entity VARCHAR2) RETURN VARCHAR2;
END cx_bf_api;
/
CREATE OR REPLACE EDITIONABLE PACKAGE BODY "SIEBEL"."CX_BF_API" AS
  TYPE t_vals IS TABLE OF VARCHAR2(4000) INDEX BY VARCHAR2(128);

  FUNCTION table_for(p_entity VARCHAR2) RETURN VARCHAR2 IS
  BEGIN
    RETURN CASE UPPER(p_entity) WHEN 'ACCOUNT' THEN 'S_ORG_EXT' WHEN 'CONTACT' THEN 'S_CONTACT'
      ELSE NULL END;
  END;

  FUNCTION ext_lookup(p_tbl VARCHAR2, p_system VARCHAR2, p_ext VARCHAR2) RETURN VARCHAR2 IS
    l VARCHAR2(15);
  BEGIN
    SELECT MAX(record_id) INTO l FROM siebel.s_ext_ref WHERE tbl_name = p_tbl AND ext_sys_cd = p_system AND ext_id = p_ext;
    RETURN l;
  END;

  FUNCTION is_column(p_tbl VARCHAR2, p_col VARCHAR2) RETURN BOOLEAN IS
    n NUMBER;
  BEGIN
    SELECT COUNT(*) INTO n FROM all_tab_columns WHERE owner = 'SIEBEL' AND table_name = p_tbl AND column_name = p_col
      AND column_name NOT IN ('ROW_ID','CREATED','CREATED_BY','LAST_UPD','LAST_UPD_BY','MODIFICATION_NUM','CONFLICT_ID','DB_LAST_UPD','DB_LAST_UPD_SRC');
    RETURN n > 0;
  END;

  PROCEDURE upsert(p_entity IN VARCHAR2, p_system IN VARCHAR2, p_json IN VARCHAR2,
                   o_row_id OUT VARCHAR2, o_action OUT VARCHAR2) IS
    l_tbl  VARCHAR2(30) := table_for(p_entity);
    l_obj  JSON_OBJECT_T;
    l_keys JSON_KEY_LIST;
    l_vals t_vals;
    l_ext  VARCHAR2(200);
    l_k    VARCHAR2(200);
    l_col  VARCHAR2(128);
    l_sql  VARCHAR2(32767);
    l_lst  VARCHAR2(32767);
    l_c    INTEGER;
    l_n    INTEGER;
    l_i    PLS_INTEGER;
    l_rid  VARCHAR2(15);
  BEGIN
    IF l_tbl IS NULL THEN RAISE_APPLICATION_ERROR(-20200, 'Unbekannte Entitaet: ' || p_entity); END IF;
    l_obj  := JSON_OBJECT_T.parse(p_json);
    l_ext  := l_obj.get_string('_EXT_ID');
    IF l_ext IS NULL THEN RAISE_APPLICATION_ERROR(-20201, '_EXT_ID fehlt'); END IF;
    l_keys := l_obj.get_keys;
    FOR i IN 1 .. l_keys.COUNT LOOP
      l_k := l_keys(i);
      IF l_k LIKE '\_LOOKUP:%' ESCAPE '\' THEN
        l_col := UPPER(REGEXP_SUBSTR(l_k, '[^:]+', 1, 3));
        IF is_column(l_tbl, l_col) AND NOT l_obj.get(l_k).is_null THEN
          l_vals(l_col) := ext_lookup(UPPER(REGEXP_SUBSTR(l_k, '[^:]+', 1, 2)), p_system, l_obj.get_string(l_k));
        END IF;
      ELSIF SUBSTR(l_k, 1, 1) <> '_' AND is_column(l_tbl, UPPER(l_k)) THEN
        IF l_obj.get(l_k).is_null THEN l_vals(UPPER(l_k)) := NULL;
        ELSE l_vals(UPPER(l_k)) := SUBSTR(l_obj.get_string(l_k), 1, 4000); END IF;
      END IF;
    END LOOP;

    o_row_id := ext_lookup(l_tbl, p_system, l_ext);
    l_c := DBMS_SQL.OPEN_CURSOR;
    IF o_row_id IS NULL THEN
      l_col := l_vals.FIRST; l_i := 0;
      WHILE l_col IS NOT NULL LOOP
        l_i := l_i + 1; l_sql := l_sql || CASE WHEN l_i > 1 THEN ',' END || l_col; l_lst := l_lst || CASE WHEN l_i > 1 THEN ',' END || ':b' || l_i;
        l_col := l_vals.NEXT(l_col);
      END LOOP;
      DBMS_SQL.PARSE(l_c, 'BEGIN INSERT INTO siebel.' || l_tbl || ' (' || l_sql || ') VALUES (' || l_lst || ') RETURNING row_id INTO :rid; END;', DBMS_SQL.NATIVE);
      o_action := 'INSERT';
    ELSE
      l_col := l_vals.FIRST; l_i := 0;
      WHILE l_col IS NOT NULL LOOP
        l_i := l_i + 1; l_sql := l_sql || CASE WHEN l_i > 1 THEN ',' END || l_col || '=:b' || l_i;
        l_col := l_vals.NEXT(l_col);
      END LOOP;
      DBMS_SQL.PARSE(l_c, 'BEGIN UPDATE siebel.' || l_tbl || ' SET ' || l_sql || ' WHERE row_id = :rid; END;', DBMS_SQL.NATIVE);
      o_action := 'UPDATE';
    END IF;
    l_col := l_vals.FIRST; l_i := 0;
    WHILE l_col IS NOT NULL LOOP
      l_i := l_i + 1; DBMS_SQL.BIND_VARIABLE(l_c, ':b' || l_i, l_vals(l_col), 4000);
      l_col := l_vals.NEXT(l_col);
    END LOOP;
    DBMS_SQL.BIND_VARIABLE(l_c, ':rid', NVL(o_row_id, RPAD(' ', 15)), 15);
    l_n := DBMS_SQL.EXECUTE(l_c);
    IF o_action = 'INSERT' THEN
      DBMS_SQL.VARIABLE_VALUE(l_c, ':rid', l_rid);
      o_row_id := TRIM(l_rid);
      INSERT INTO siebel.s_ext_ref (tbl_name, record_id, ext_sys_cd, ext_id, last_sync_dt) VALUES (l_tbl, o_row_id, p_system, l_ext, SYSDATE);
    ELSE
      UPDATE siebel.s_ext_ref SET last_sync_dt = SYSDATE WHERE tbl_name = l_tbl AND record_id = o_row_id AND ext_sys_cd = p_system;
    END IF;
    DBMS_SQL.CLOSE_CURSOR(l_c);
    COMMIT;
  EXCEPTION WHEN OTHERS THEN
    IF DBMS_SQL.IS_OPEN(l_c) THEN DBMS_SQL.CLOSE_CURSOR(l_c); END IF;
    ROLLBACK;
    RAISE;
  END;
END cx_bf_api;
/

GRANT EXECUTE ON siebel.cx_bf_api TO beyondflow;
SELECT object_type, status FROM all_objects WHERE owner = 'SIEBEL' AND object_name = 'CX_BF_API';
