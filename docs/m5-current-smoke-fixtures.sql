-- CURRENT PROTOTYPE ONLY: sample data for the accompanying Postman collection.
-- NOT the revised specification's signed-token / snapshot / cycle fixtures.
-- Reviewed against local V001..V604; NOT executed against your database by Codex.
-- Run with SQL Developer F5 in an explicitly chosen DISPOSABLE TEST schema.
-- The same schema must be configured in the backend's test environment.
-- This script inserts synthetic rows and COMMITS. It creates no Oracle users,
-- deletes nothing, and does not alter migrations or existing records.
-- Each intentional rerun creates a NEW fixture batch. Keep the printed IDs.
-- Stop if other schema changes added constraints not represented here.

DEFINE M5_TEST_SCHEMA = ENTER_YOUR_ISOLATED_TEST_SCHEMA
SET SERVEROUTPUT ON
SET VERIFY OFF
WHENEVER SQLERROR EXIT SQL.SQLCODE ROLLBACK

DECLARE
  v_run VARCHAR2(32) := LOWER(RAWTOHEX(SYS_GUID()));
  v_admin RAW(16) := SYS_GUID();
  v_verified RAW(16) := SYS_GUID();
  v_unverified RAW(16) := SYS_GUID();
  v_wallet_verified RAW(16) := SYS_GUID();
  v_wallet_unverified RAW(16) := SYS_GUID();
  v_recipient_low RAW(16) := SYS_GUID();
  v_recipient_medium RAW(16) := SYS_GUID();
  v_recipient_high RAW(16) := SYS_GUID();
  v_recipient_manual RAW(16) := SYS_GUID();
  v_low RAW(16) := SYS_GUID();
  v_medium RAW(16) := SYS_GUID();
  v_high RAW(16) := SYS_GUID();
  v_manual RAW(16) := SYS_GUID();
  v_now TIMESTAMP := LOCALTIMESTAMP;

  FUNCTION uuid_text(p_id RAW) RETURN VARCHAR2 IS
    v_hex VARCHAR2(32) := LOWER(RAWTOHEX(p_id));
  BEGIN
    RETURN SUBSTR(v_hex,1,8)||'-'||SUBSTR(v_hex,9,4)||'-'||
           SUBSTR(v_hex,13,4)||'-'||SUBSTR(v_hex,17,4)||'-'||SUBSTR(v_hex,21,12);
  END;

  PROCEDURE add_user(p_id RAW, p_label VARCHAR2, p_role VARCHAR2) IS
  BEGIN
    INSERT INTO users(id,email,password_hash,role,full_name,created_at,updated_at)
    VALUES(p_id,'m5-'||p_label||'-'||v_run||'@example.invalid',
           '!M5_SMOKE_LOGIN_DISABLED!',p_role,'Synthetic M5 '||p_label,v_now,v_now);
    -- No login password or token is provided: current M5 routes use No Auth.
    -- These intentionally login-disabled rows are not revised-spec auth fixtures.
  END;

  PROCEDURE add_payment(p_id RAW,p_wallet RAW,p_recipient RAW,
                        p_status VARCHAR2,p_created TIMESTAMP) IS
  BEGIN
    INSERT INTO payments(id,sender_wallet_id,recipient_id,amount,currency,status,created_at)
    VALUES(p_id,p_wallet,p_recipient,100,'USD',p_status,p_created);
  END;
BEGIN
  IF UPPER('&M5_TEST_SCHEMA') = 'ENTER_YOUR_ISOLATED_TEST_SCHEMA'
     OR UPPER('&M5_TEST_SCHEMA') <> SYS_CONTEXT('USERENV','CURRENT_SCHEMA')
     OR SYS_CONTEXT('USERENV','SESSION_USER') <> SYS_CONTEXT('USERENV','CURRENT_SCHEMA') THEN
    RAISE_APPLICATION_ERROR(-20001,
      'Set M5_TEST_SCHEMA to your explicitly designated disposable schema and connect as its owner.');
  END IF;

  add_user(v_admin,'reviewer','ADMIN');
  add_user(v_verified,'verified','USER');
  add_user(v_unverified,'unverified','USER');

  INSERT INTO kyc_cases(id,user_id,status,created_at,doc_type,doc_number,
                       submitted_at,decided_by,decided_at,reject_reason)
  VALUES(SYS_GUID(),v_verified,'VERIFIED',v_now,'PASSPORT','SYNTHETIC-'||v_run,
         v_now,v_admin,v_now,NULL);

  INSERT INTO wallets(id,user_id,currency,balance,created_at)
  VALUES(v_wallet_verified,v_verified,'USD',0,v_now);
  INSERT INTO wallets(id,user_id,currency,balance,created_at)
  VALUES(v_wallet_unverified,v_unverified,'USD',0,v_now);

  INSERT INTO recipients(id,user_id,name,account_ref,created_at)
  VALUES(v_recipient_low,v_verified,'Synthetic LOW','M5-LOW-'||v_run,
         v_now - INTERVAL '3' DAY);
  INSERT INTO recipients(id,user_id,name,account_ref,created_at)
  VALUES(v_recipient_medium,v_verified,'Synthetic MEDIUM','M5-MED-'||v_run,
         v_now - INTERVAL '1' HOUR);
  INSERT INTO recipients(id,user_id,name,account_ref,created_at)
  VALUES(v_recipient_high,v_unverified,'Synthetic HIGH','M5-HIGH-'||v_run,
         v_now - INTERVAL '3' DAY);
  INSERT INTO recipients(id,user_id,name,account_ref,created_at)
  VALUES(v_recipient_manual,v_verified,'Synthetic MANUAL','M5-MANUAL-'||v_run,
         v_now - INTERVAL '3' DAY);

  add_payment(SYS_GUID(),v_wallet_verified,v_recipient_low,'COMPLETED',
              v_now - INTERVAL '2' DAY);
  add_payment(SYS_GUID(),v_wallet_unverified,v_recipient_high,'COMPLETED',
              v_now - INTERVAL '2' DAY);
  add_payment(v_low,v_wallet_verified,v_recipient_low,'SCREENING',v_now);
  add_payment(v_medium,v_wallet_verified,v_recipient_medium,'SCREENING',v_now);
  add_payment(v_high,v_wallet_unverified,v_recipient_high,'SCREENING',v_now);
  add_payment(v_manual,v_wallet_verified,v_recipient_manual,'SCREENING',v_now);

  COMMIT;
  DBMS_OUTPUT.PUT_LINE('Fixture batch='||v_run);
  DBMS_OUTPUT.PUT_LINE('manualPaymentId='||uuid_text(v_manual));
  DBMS_OUTPUT.PUT_LINE('lowPaymentId='||uuid_text(v_low));
  DBMS_OUTPUT.PUT_LINE('mediumPaymentId='||uuid_text(v_medium));
  DBMS_OUTPUT.PUT_LINE('highPaymentId='||uuid_text(v_high));
  DBMS_OUTPUT.PUT_LINE('These are CURRENT PROTOTYPE expectations, not revised-spec acceptance.');
EXCEPTION
  WHEN OTHERS THEN
    ROLLBACK;
    RAISE;
END;
/
