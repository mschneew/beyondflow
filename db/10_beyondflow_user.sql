-- BeyondFlow: Datenbank-Benutzer BEYONDFLOW (als ADMIN ausfuehren)
-- Exportiert aus der Referenz-DB am 25.11.2026. Wiederholbar.
-- Das Passwort wird beim Ausfuehren abgefragt (Database Actions: Ersetzungsvariable) und
-- spaeter beim BeyondFlow-Installer als BF_CRM_DB_PASSWORD eingegeben. Nie in dieses Skript schreiben.
--
-- Die Rechte auf die Pakete und Sichten vergibt Skript 60_grants.sql, nachdem 20–50 eingespielt sind.

-- Profil fuer technische Benutzer: Passwort laeuft nicht ab, Sperre nach 10 Fehlversuchen
DECLARE
  n NUMBER;
BEGIN
  SELECT COUNT(*) INTO n FROM dba_profiles WHERE profile = 'SERVICE_ACCOUNT';
  IF n = 0 THEN
    EXECUTE IMMEDIATE 'CREATE PROFILE service_account LIMIT FAILED_LOGIN_ATTEMPTS 10 '
                   || 'PASSWORD_LIFE_TIME UNLIMITED PASSWORD_REUSE_TIME UNLIMITED PASSWORD_REUSE_MAX UNLIMITED';
  END IF;
END;
/

-- Benutzer anlegen (nur wenn noch nicht vorhanden)
DECLARE
  n NUMBER;
BEGIN
  SELECT COUNT(*) INTO n FROM dba_users WHERE username = 'BEYONDFLOW';
  IF n = 0 THEN
    EXECUTE IMMEDIATE 'CREATE USER beyondflow IDENTIFIED BY "&beyondflow_password" PROFILE service_account';
  END IF;
END;
/
GRANT CREATE SESSION TO beyondflow;

-- Kontrolle
SELECT username, profile, account_status FROM dba_users WHERE username = 'BEYONDFLOW';
