-- Synthetic M5 database-observation fixture for the authorized fresh local schema.
-- Run in a NEW SQL*Plus connection as FLUXPAY at localhost:1521/FREEPDB1,
-- after all migrations have completed. The guard verifies owner and container;
-- the connection's localhost endpoint must be selected by the operator.
-- Example, from the repository root (SQL*Plus prompts for the password):
--   sqlplus -L FLUXPAY@localhost:1521/FREEPDB1 @docs/sql/m5-risk-demo-fixture.sql
-- This script commits one transaction and refuses to overwrite an existing fixture.
--
-- This is a complete M3-format DRAFT row for testing the authoritative M5 reader.
-- It is inserted directly: M3's draft API requires verified KYC and enough funds,
-- so this PENDING-KYC, zero-balance fixture is not an M3 API success scenario.
-- No login is possible with the deliberately unusable password_hash. Use an
-- independently authorized local API test identity; no credential is seeded here.
-- No money, ledger entries, quote, hold, screening case, or risk result is seeded.
-- The synthetic PAN/account references are deliberately non-payable test labels.
--
-- With backend/src/main/resources/m5-backend.properties, assessment is expected to produce HIGH:
--   R1 / HIGH   / KYC_UNVERIFIED
--   R2 / MEDIUM / FIRST_TO_RECIPIENT
--   R4 / MEDIUM / HIGH_VALUE (1500 USD is greater than the 1000 USD threshold)
-- FAMILY_SUPPORT is the actual M3 purpose enum, not a narrative explanation.
-- It is long enough to avoid SHORT_PURPOSE. IN is absent from the example risk
-- country list, and the new sender/recipient have no earlier payment history.
-- Assessment must run through the API to create its own authoritative result.
-- DRAFT is not evidence that a payment has been held or a review cycle opened.

WHENEVER SQLERROR EXIT FAILURE ROLLBACK
WHENEVER OSERROR EXIT FAILURE ROLLBACK
SET AUTOCOMMIT OFF
SET SERVEROUTPUT ON SIZE UNLIMITED
SET DEFINE OFF
SET VERIFY OFF

-- Refuse to join an existing transaction in a reused SQL*Plus session.
SET TRANSACTION READ WRITE NAME 'm5_risk_demo_fixture';

DECLARE
  v_email CONSTANT VARCHAR2(255) := 'risk.sender.01@example.invalid';
  v_user_id RAW(16) := SYS_GUID();
  v_wallet_id RAW(16) := SYS_GUID();
  v_kyc_id RAW(16) := SYS_GUID();
  v_recipient_id RAW(16) := SYS_GUID();
  v_payment_id RAW(16) := SYS_GUID();
  v_now_tz TIMESTAMP WITH TIME ZONE := SYSTIMESTAMP AT TIME ZONE 'UTC';
  -- M5 reads the legacy timezone-less created_at columns using a UTC calendar.
  v_now TIMESTAMP := SYS_EXTRACT_UTC(v_now_tz);
  v_recipient_name CONSTANT VARCHAR2(255) := 'Synthetic M5 Recipient 01';
  v_account_ref CONSTANT VARCHAR2(255) := 'SYNTHETIC-NONPAYABLE-M5-01';
  v_bank_name CONSTANT VARCHAR2(150) := 'Synthetic Test Bank - No Real Account';
  v_recipient_snapshot CLOB;
  v_existing_count NUMBER;

  FUNCTION uuid_text(p_id RAW) RETURN VARCHAR2 IS
    v_hex VARCHAR2(32) := LOWER(RAWTOHEX(p_id));
  BEGIN
    RETURN SUBSTR(v_hex, 1, 8) || '-' || SUBSTR(v_hex, 9, 4) || '-'
        || SUBSTR(v_hex, 13, 4) || '-' || SUBSTR(v_hex, 17, 4) || '-'
        || SUBSTR(v_hex, 21, 12);
  END;
BEGIN
  IF USER <> 'FLUXPAY'
      OR SYS_CONTEXT('USERENV', 'CURRENT_SCHEMA') <> 'FLUXPAY'
      OR SYS_CONTEXT('USERENV', 'CON_NAME') <> 'FREEPDB1' THEN
    RAISE_APPLICATION_ERROR(-20701,
        'M5 fixture requires USER=CURRENT_SCHEMA=FLUXPAY and CON_NAME=FREEPDB1.');
  END IF;

  -- Perform this check before the first INSERT, including canonical email forms.
  SELECT COUNT(*) INTO v_existing_count
    FROM users
   WHERE LOWER(TRIM(email)) = v_email;
  IF v_existing_count <> 0 THEN
    RAISE_APPLICATION_ERROR(-20702,
        'M5 fixture email already exists; no fixture rows have been inserted.');
  END IF;

  -- Match the frozen JSON fields written by PaymentService.draft().
  SELECT JSON_OBJECT(
      'name' VALUE v_recipient_name,
      'account' VALUE v_account_ref,
      'bankName' VALUE v_bank_name,
      'country' VALUE 'IN',
      'currency' VALUE 'INR'
      RETURNING CLOB)
    INTO v_recipient_snapshot
    FROM dual;

  INSERT INTO users (
      id, email, password_hash, role, full_name, created_at, updated_at)
  VALUES (
      v_user_id, v_email, '!M5_DEMO_LOGIN_DISABLED!', 'USER',
      'Synthetic M5 Sender 01', v_now, v_now);

  INSERT INTO wallets (
      id, user_id, currency, balance, account_role, held_balance, version, created_at)
  VALUES (
      v_wallet_id, v_user_id, 'USD', 0, 'CUSTOMER', 0, 0, v_now);

  INSERT INTO kyc_cases (
      id, user_id, status, doc_type, doc_number, submitted_at, created_at,
      decided_by, decided_at, reject_reason, version)
  VALUES (
      v_kyc_id, v_user_id, 'PENDING', 'PAN', 'SYNTHETIC-PAN-M5-01', v_now, v_now,
      NULL, NULL, NULL, 0);

  INSERT INTO recipients (
      id, user_id, name, account_ref, bank_name, country, currency,
      status, profile_complete, version, created_at, updated_at)
  VALUES (
      v_recipient_id, v_user_id, v_recipient_name, v_account_ref, v_bank_name,
      'IN', 'INR', 'ACTIVE', 1, 0, v_now, v_now_tz);

  INSERT INTO payments (
      id, sender_wallet_id, recipient_id, amount, currency, status, created_at,
      m3_flow_version, sender_id, payout_currency, purpose, preference,
      recipient_version, recipient_snapshot, selected_quote_id,
      current_quote_generation, quote_generation_counter, event_sequence_counter,
      posting_snapshot, posted_at, version, updated_at,
      review_reference, approval_expires_at)
  VALUES (
      v_payment_id, v_wallet_id, v_recipient_id, 1500, 'USD', 'DRAFT', v_now,
      1, v_user_id, 'INR', 'FAMILY_SUPPORT', 'BALANCED',
      0, v_recipient_snapshot, NULL,
      NULL, 0, 0,
      NULL, NULL, 0, v_now_tz,
      NULL, NULL);

  COMMIT;
  DBMS_OUTPUT.PUT_LINE('M5 demo fixture committed.');
  DBMS_OUTPUT.PUT_LINE('senderEmail=' || v_email);
  DBMS_OUTPUT.PUT_LINE('senderId=' || uuid_text(v_user_id));
  DBMS_OUTPUT.PUT_LINE('paymentId=' || uuid_text(v_payment_id));
  DBMS_OUTPUT.PUT_LINE('paymentStatus=DRAFT');
  DBMS_OUTPUT.PUT_LINE('expectedRiskWithExampleConfig=HIGH');
  DBMS_OUTPUT.PUT_LINE('expectedReasonCodes=KYC_UNVERIFIED,FIRST_TO_RECIPIENT,HIGH_VALUE');
EXCEPTION
  WHEN OTHERS THEN
    ROLLBACK;
    RAISE;
END;
/

EXIT SUCCESS
