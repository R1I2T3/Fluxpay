-- Preflight runs before DDL: financial history is never guessed, deleted or rewritten.
-- For nonempty legacy schemas, an operator must first add nullable ACCOUNT_ROLE and
-- explicitly classify each wallet by UUID after review (see the M2 documentation).
DECLARE
  wallet_count NUMBER;
  role_column NUMBER;
  held_column NUMBER;
  version_column NUMBER;
  invalid_count NUMBER;
  held_expression VARCHAR2(30) := '0';
BEGIN
  SELECT COUNT(*) INTO wallet_count FROM wallets;
  SELECT COUNT(*) INTO role_column FROM user_tab_columns
    WHERE table_name = 'WALLETS' AND column_name = 'ACCOUNT_ROLE';
  SELECT COUNT(*) INTO held_column FROM user_tab_columns
    WHERE table_name = 'WALLETS' AND column_name = 'HELD_BALANCE';
  SELECT COUNT(*) INTO version_column FROM user_tab_columns
    WHERE table_name = 'WALLETS' AND column_name = 'VERSION';

  IF wallet_count > 0 AND role_column = 0 THEN
    RAISE_APPLICATION_ERROR(-20201, 'M2 V202: ' || wallet_count ||
      ' legacy wallets require reviewed classification. Add nullable ACCOUNT_ROLE, '
      || 'classify each wallet by UUID, then retry. Do not assume all are CUSTOMER.');
  END IF;
  IF held_column > 0 THEN
    held_expression := 'held_balance';
  END IF;
  IF role_column > 0 THEN
    EXECUTE IMMEDIATE 'SELECT COUNT(*) FROM wallets WHERE account_role IS NULL OR '
      || 'account_role NOT IN (''CUSTOMER'',''FX_CLEARING'',''DEMO_CLEARING'', '
      || '''PAYOUT_CLEARING'',''FEE_REVENUE'')' INTO invalid_count;
    IF invalid_count > 0 THEN
      RAISE_APPLICATION_ERROR(-20202, 'M2 V202: classify ' || invalid_count ||
        ' wallets with missing/unknown ACCOUNT_ROLE before retrying.');
    END IF;
    EXECUTE IMMEDIATE 'SELECT COUNT(*) FROM wallets WHERE ('
      || held_expression || ' IS NULL) OR (account_role = ''CUSTOMER'' AND ('
      || held_expression || ' < 0 OR balance < ' || held_expression || ')) '
      || 'OR (account_role <> ''CUSTOMER'' AND ' || held_expression || ' <> 0)'
      INTO invalid_count;
    IF invalid_count > 0 THEN
      RAISE_APPLICATION_ERROR(-20203, 'M2 V202: ' || invalid_count ||
        ' wallets violate customer funds/system hold rules. Review balances; do not reset them.');
    END IF;
    EXECUTE IMMEDIATE 'SELECT COUNT(*) FROM (SELECT user_id, currency, account_role '
      || 'FROM wallets GROUP BY user_id, currency, account_role HAVING COUNT(*) > 1)'
      INTO invalid_count;
    IF invalid_count > 0 THEN
      RAISE_APPLICATION_ERROR(-20204, 'M2 V202: duplicate owner/currency/role groups: '
        || invalid_count || '. Resolve ownership with the team before retrying.');
    END IF;
  END IF;
  SELECT COUNT(*) INTO invalid_count FROM wallets WHERE currency NOT IN ('USD','EUR','INR');
  IF invalid_count > 0 THEN
    RAISE_APPLICATION_ERROR(-20205, 'M2 V202: unsupported currencies in ' || invalid_count || ' wallets.');
  END IF;
  IF version_column > 0 THEN
    EXECUTE IMMEDIATE 'SELECT COUNT(*) FROM wallets WHERE version IS NULL OR version < 0'
      INTO invalid_count;
    IF invalid_count > 0 THEN
      RAISE_APPLICATION_ERROR(-20206, 'M2 V202: invalid wallet versions require review.');
    END IF;
  END IF;
  SELECT COUNT(*) INTO invalid_count FROM ledger_entries
    WHERE entry_type NOT IN ('DEBIT','CREDIT') OR amount <= 0 OR currency NOT IN ('USD','EUR','INR');
  IF invalid_count > 0 THEN
    RAISE_APPLICATION_ERROR(-20207, 'M2 V202: ' || invalid_count ||
      ' legacy ledger entries violate type/amount/currency rules. Review history before retrying.');
  END IF;
END;
/

-- Conditional column creation supports an operator's explicit legacy classification step.
DECLARE
  n NUMBER;
BEGIN
  SELECT COUNT(*) INTO n FROM user_tab_columns WHERE table_name='WALLETS' AND column_name='ACCOUNT_ROLE';
  IF n=0 THEN EXECUTE IMMEDIATE 'ALTER TABLE wallets ADD (account_role VARCHAR2(20))'; END IF;
  SELECT COUNT(*) INTO n FROM user_tab_columns WHERE table_name='WALLETS' AND column_name='HELD_BALANCE';
  IF n=0 THEN EXECUTE IMMEDIATE 'ALTER TABLE wallets ADD (held_balance NUMBER(19,4) DEFAULT 0 NOT NULL)'; END IF;
  SELECT COUNT(*) INTO n FROM user_tab_columns WHERE table_name='WALLETS' AND column_name='VERSION';
  IF n=0 THEN EXECUTE IMMEDIATE 'ALTER TABLE wallets ADD (version NUMBER(10) DEFAULT 0 NOT NULL)'; END IF;
  SELECT COUNT(*) INTO n FROM user_tab_columns WHERE table_name='WALLETS' AND column_name='HELD_BALANCE' AND nullable='Y';
  IF n>0 THEN EXECUTE IMMEDIATE 'ALTER TABLE wallets MODIFY (held_balance NOT NULL)'; END IF;
  SELECT COUNT(*) INTO n FROM user_tab_columns WHERE table_name='WALLETS' AND column_name='VERSION' AND nullable='Y';
  IF n>0 THEN EXECUTE IMMEDIATE 'ALTER TABLE wallets MODIFY (version NOT NULL)'; END IF;
END;
/

ALTER TABLE wallets MODIFY (account_role NOT NULL);
ALTER TABLE wallets ADD CONSTRAINT ck_m2_wallet_ccy CHECK (currency IN ('USD','EUR','INR'));
ALTER TABLE wallets ADD CONSTRAINT ck_m2_wallet_role CHECK
  (account_role IN ('CUSTOMER','FX_CLEARING','DEMO_CLEARING','PAYOUT_CLEARING','FEE_REVENUE'));
ALTER TABLE wallets ADD CONSTRAINT ck_m2_wallet_funds CHECK
  ((account_role='CUSTOMER' AND balance >= held_balance AND held_balance >= 0)
   OR (account_role<>'CUSTOMER' AND held_balance=0));
ALTER TABLE wallets ADD CONSTRAINT ck_m2_wallet_version CHECK (version >= 0);
ALTER TABLE wallets ADD CONSTRAINT uq_m2_wallet_owner_ccy_role UNIQUE (user_id,currency,account_role);

ALTER TABLE ledger_entries ADD (journal_reference VARCHAR2(64), narration VARCHAR2(255));
ALTER TABLE ledger_entries ADD CONSTRAINT ck_m2_ledger_type CHECK (entry_type IN ('DEBIT','CREDIT'));
ALTER TABLE ledger_entries ADD CONSTRAINT ck_m2_ledger_amount CHECK (amount > 0);
ALTER TABLE ledger_entries ADD CONSTRAINT ck_m2_ledger_ccy CHECK (currency IN ('USD','EUR','INR'));
CREATE INDEX idx_m2_ledger_journal_ccy ON ledger_entries(journal_reference,currency);
CREATE INDEX idx_m2_ledger_wallet_date ON ledger_entries(wallet_id,created_at DESC,id DESC);

CREATE TABLE wallet_operations (
  id RAW(16) DEFAULT SYS_GUID() PRIMARY KEY,
  user_id RAW(16) NOT NULL REFERENCES users(id),
  operation_type VARCHAR2(20) NOT NULL,
  client_key VARCHAR2(255) NOT NULL,
  normalized_request CLOB NOT NULL,
  journal_reference VARCHAR2(64) NOT NULL,
  status VARCHAR2(20) NOT NULL,
  response_snapshot CLOB,
  created_at TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL,
  CONSTRAINT uq_m2_operation_key UNIQUE (user_id,operation_type,client_key),
  CONSTRAINT ck_m2_operation_type CHECK (operation_type IN ('RECEIVE_DEMO','CONVERT')),
  CONSTRAINT ck_m2_operation_state CHECK
    ((status='IN_PROGRESS' AND response_snapshot IS NULL)
     OR (status='COMPLETED' AND response_snapshot IS NOT NULL))
);
