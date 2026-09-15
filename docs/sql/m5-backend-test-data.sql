-- USER-RUN ONLY. Synthetic local M3/M5 testing fixture; NOT a Flyway migration.
-- Run once in a NEW SQL*Plus connection after V708, against FLUXPAY/FREEPDB1.
-- sqlplus -L fluxpay@localhost:1521/FREEPDB1 @docs/sql/m5-backend-test-data.sql
-- Refuses existing fixture IDs/email; never updates/deletes an existing record.
-- Passwords deliberately disabled. Use your existing signed JWT or the local test-token script.
-- No cases, risk values, review receipts or payment outcomes are precomputed here.
-- Funding is a balanced synthetic ledger journal, not money received from a real bank.
WHENEVER SQLERROR EXIT FAILURE ROLLBACK
WHENEVER OSERROR EXIT FAILURE ROLLBACK
SET AUTOCOMMIT OFF
SET DEFINE OFF
SET VERIFY OFF
SET SERVEROUTPUT ON
SET TRANSACTION READ WRITE NAME 'm5_backend_fixture';

DECLARE
  v_count NUMBER;
  v_now TIMESTAMP := SYS_EXTRACT_UTC(SYSTIMESTAMP);
  v_admin RAW(16) := HEXTORAW('5F000000000000000000000000000001');
  v_system RAW(16) := HEXTORAW('5F000000000000000000000000000002');
  v_sender RAW(16) := HEXTORAW('5F000000000000000000000000000003');
  v_pending RAW(16) := HEXTORAW('5F000000000000000000000000000004');
  v_customer RAW(16) := HEXTORAW('5F100000000000000000000000000001');
  v_funding RAW(16) := HEXTORAW('5F100000000000000000000000000002');
  v_clearing RAW(16) := HEXTORAW('5F100000000000000000000000000003');
  v_fee RAW(16) := HEXTORAW('5F100000000000000000000000000004');
  v_pending_wallet RAW(16) := HEXTORAW('5F100000000000000000000000000005');
  PROCEDURE add_user(p_id RAW,p_email VARCHAR2,p_role VARCHAR2,p_name VARCHAR2) IS
  BEGIN
    INSERT INTO users(id,email,password_hash,role,full_name,created_at,updated_at)
    VALUES(p_id,p_email,'!SYNTHETIC_M5_LOGIN_DISABLED!',p_role,p_name,v_now,v_now);
  END;
  PROCEDURE add_wallet(p_id RAW,p_owner RAW,p_role VARCHAR2,p_balance NUMBER) IS
  BEGIN
    INSERT INTO wallets(id,user_id,currency,account_role,balance,held_balance,version,created_at)
    VALUES(p_id,p_owner,'USD',p_role,p_balance,0,0,v_now);
  END;
  PROCEDURE add_recipient(p_hex VARCHAR2,p_owner RAW,p_country VARCHAR2,p_label VARCHAR2) IS
  BEGIN
    INSERT INTO recipients(id,user_id,name,account_ref,bank_name,country,currency,status,
      profile_complete,version,created_at,updated_at)
    VALUES(HEXTORAW(p_hex),p_owner,p_label,'SYNTHETIC-NONPAYABLE-'||p_hex,
      'Synthetic M5 Bank - No Real Account',p_country,'INR','ACTIVE',1,0,v_now,SYSTIMESTAMP);
  END;
BEGIN
  IF USER <> 'FLUXPAY' OR SYS_CONTEXT('USERENV','CURRENT_SCHEMA') <> 'FLUXPAY'
      OR SYS_CONTEXT('USERENV','CON_NAME') <> 'FREEPDB1' THEN
    RAISE_APPLICATION_ERROR(-20721,'Requires FLUXPAY schema in FREEPDB1. Select localhost endpoint explicitly.');
  END IF;
  SELECT COUNT(*) INTO v_count FROM user_tab_columns
    WHERE table_name='PAYMENTS' AND column_name='M3_FLOW_VERSION';
  IF v_count<>1 THEN RAISE_APPLICATION_ERROR(-20722,'Apply migrations first.'); END IF;
  SELECT COUNT(*) INTO v_count FROM users WHERE id IN(v_admin,v_system,v_sender,v_pending)
    OR LOWER(TRIM(email)) IN('m5.admin@example.invalid','m5.system@example.invalid',
      'm5.sender@example.invalid','m5.pending@example.invalid');
  IF v_count<>0 THEN RAISE_APPLICATION_ERROR(-20723,'Fixture already exists or IDs/emails collide; nothing changed.'); END IF;

  add_user(v_admin,'m5.admin@example.invalid','ADMIN','Synthetic M5 Reviewer');
  add_user(v_system,'m5.system@example.invalid','USER','Synthetic M5 System Accounts');
  add_user(v_sender,'m5.sender@example.invalid','USER','Synthetic M5 Verified Sender');
  add_user(v_pending,'m5.pending@example.invalid','USER','Synthetic M5 Pending Sender');
  add_wallet(v_customer,v_sender,'CUSTOMER',10000);
  add_wallet(v_funding,v_system,'DEMO_CLEARING',-10000);
  add_wallet(v_clearing,v_system,'PAYOUT_CLEARING',0);
  add_wallet(v_fee,v_system,'FEE_REVENUE',0);
  add_wallet(v_pending_wallet,v_pending,'CUSTOMER',0);

  INSERT INTO ledger_entries(id,wallet_id,entry_type,amount,currency,idempotency_key,
      journal_reference,narration,created_at)
  VALUES(SYS_GUID(),v_funding,'DEBIT',10000,'USD','m5-test:funding:system',
      'm5-test:funding','Synthetic test funding only',v_now);
  INSERT INTO ledger_entries(id,wallet_id,entry_type,amount,currency,idempotency_key,
      journal_reference,narration,created_at)
  VALUES(SYS_GUID(),v_customer,'CREDIT',10000,'USD','m5-test:funding:customer',
      'm5-test:funding','Synthetic test funding only',v_now);

  INSERT INTO kyc_cases(id,user_id,status,doc_type,doc_number,submitted_at,created_at,
      decided_by,decided_at,reject_reason,version)
  VALUES(SYS_GUID(),v_sender,'VERIFIED','PAN','SYNTHETIC-M5-VERIFIED',v_now,v_now,v_admin,v_now,NULL,0);
  INSERT INTO kyc_cases(id,user_id,status,doc_type,doc_number,submitted_at,created_at,
      decided_by,decided_at,reject_reason,version)
  VALUES(SYS_GUID(),v_pending,'PENDING','PAN','SYNTHETIC-M5-PENDING',v_now,v_now,NULL,NULL,NULL,0);

  add_recipient('5F200000000000000000000000000001',v_sender,'IN','Synthetic India Recipient');
  add_recipient('5F200000000000000000000000000002',v_sender,'RU','Synthetic Demo Risk Recipient');
  add_recipient('5F200000000000000000000000000003',v_pending,'IN','Synthetic Pending Sender Recipient');

  COMMIT;
  DBMS_OUTPUT.PUT_LINE('Synthetic M5 fixture committed: 4 users, 5 wallets, 2 ledger entries, 2 KYC cases, 3 recipients.');
  DBMS_OUTPUT.PUT_LINE('system-user-id=5f000000-0000-0000-0000-000000000002');
  DBMS_OUTPUT.PUT_LINE('senderId=5f000000-0000-0000-0000-000000000003');
  DBMS_OUTPUT.PUT_LINE('sourceWalletId=5f100000-0000-0000-0000-000000000001');
  DBMS_OUTPUT.PUT_LINE('Create payments using the API; screening cases and risk reasons must be generated by M5.');
EXCEPTION WHEN OTHERS THEN ROLLBACK; RAISE;
END;
/

SELECT journal_reference,currency,
  SUM(CASE WHEN entry_type='DEBIT' THEN amount ELSE -amount END) AS imbalance
FROM ledger_entries WHERE journal_reference='m5-test:funding'
GROUP BY journal_reference,currency;
EXIT SUCCESS
