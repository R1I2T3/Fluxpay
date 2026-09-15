-- USER-RUN ONLY. Creates ONE new non-payable recipient for a fresh folder-05 run.
-- Not a migration. Never deletes or changes existing recipients/payments/cases.
-- Run in a NEW SQL*Plus connection to the LOCAL synthetic testing database:
-- sqlplus -L fluxpay@localhost:1521/FREEPDB1 @docs/sql/m5-folder05-fresh-recipient.sql
-- Each successful invocation creates a different recipient. Use it for one run.
WHENEVER SQLERROR EXIT FAILURE ROLLBACK
WHENEVER OSERROR EXIT FAILURE ROLLBACK
SET AUTOCOMMIT OFF
SET DEFINE OFF
SET VERIFY OFF
SET SERVEROUTPUT ON
SET TRANSACTION READ WRITE NAME 'm5_folder05_recipient';

DECLARE
  v_sender RAW(16) := HEXTORAW('5F000000000000000000000000000003');
  v_wallet RAW(16) := HEXTORAW('5F100000000000000000000000000001');
  v_recipient RAW(16) := SYS_GUID();
  v_hex VARCHAR2(32) := LOWER(RAWTOHEX(v_recipient));
  v_count NUMBER;
BEGIN
  IF USER <> 'FLUXPAY' OR SYS_CONTEXT('USERENV','CURRENT_SCHEMA') <> 'FLUXPAY'
      OR SYS_CONTEXT('USERENV','CON_NAME') <> 'FREEPDB1' THEN
    RAISE_APPLICATION_ERROR(-20731,'Requires local FLUXPAY/FREEPDB1 synthetic testing schema.');
  END IF;
  SELECT COUNT(*) INTO v_count FROM users
    WHERE id=v_sender AND LOWER(TRIM(email))='m5.sender@example.invalid'
      AND password_hash='!SYNTHETIC_M5_LOGIN_DISABLED!' AND role='USER';
  IF v_count<>1 THEN
    RAISE_APPLICATION_ERROR(-20732,'Expected disabled-login synthetic M5 sender was not found; nothing changed.');
  END IF;
  SELECT COUNT(*) INTO v_count FROM wallets
    WHERE id=v_wallet AND user_id=v_sender AND currency='USD' AND account_role='CUSTOMER';
  IF v_count<>1 THEN
    RAISE_APPLICATION_ERROR(-20733,'Expected synthetic sender USD wallet was not found; nothing changed.');
  END IF;
  SELECT COUNT(*) INTO v_count FROM kyc_cases WHERE user_id=v_sender AND status='VERIFIED';
  IF v_count=0 THEN
    RAISE_APPLICATION_ERROR(-20734,'Synthetic sender needs verified KYC; nothing changed.');
  END IF;

  INSERT INTO recipients(id,user_id,name,account_ref,bank_name,country,currency,status,
    profile_complete,version,created_at,updated_at)
  VALUES(v_recipient,v_sender,'Synthetic Folder 05 India Recipient',
    'SYNTHETIC-NONPAYABLE-'||v_hex,'Synthetic M5 Bank - No Real Account',
    'IN','INR','ACTIVE',1,0,SYS_EXTRACT_UTC(SYSTIMESTAMP),SYSTIMESTAMP);
  COMMIT;
  DBMS_OUTPUT.PUT_LINE('Created one fresh synthetic recipient. Existing records were preserved.');
  DBMS_OUTPUT.PUT_LINE('safeRecipientId='||SUBSTR(v_hex,1,8)||'-'||SUBSTR(v_hex,9,4)||'-'||
    SUBSTR(v_hex,13,4)||'-'||SUBSTR(v_hex,17,4)||'-'||SUBSTR(v_hex,21,12));
  DBMS_OUTPUT.PUT_LINE('Set safeRecipientId in the active Postman environment. Clear folder-05 generated variables once, then run all eight requests in order.');
EXCEPTION WHEN OTHERS THEN
  ROLLBACK;
  RAISE;
END;
/
EXIT SUCCESS
