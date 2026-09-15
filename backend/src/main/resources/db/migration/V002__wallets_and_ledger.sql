-- Fresh baseline: wallets and append-only ledger.
-- Money uses NUMBER(19,4); supported currencies are USD, EUR and INR.
-- Ledger rows are append-only: corrections create new entries, never updates.
-- Wallet operations track receive/convert request identity and committed responses:
-- IN_PROGRESS rows carry no response snapshot; COMPLETED rows require one.

CREATE TABLE wallets (
  id RAW(16) DEFAULT SYS_GUID() PRIMARY KEY,
  user_id RAW(16) NOT NULL REFERENCES users(id),
  currency VARCHAR2(3) NOT NULL,
  account_role VARCHAR2(20) NOT NULL,
  balance NUMBER(19,4) DEFAULT 0 NOT NULL,
  held_balance NUMBER(19,4) DEFAULT 0 NOT NULL,
  version NUMBER(10) DEFAULT 0 NOT NULL,
  created_at TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL,
  CONSTRAINT chk_wallet_currency CHECK (currency IN ('USD', 'EUR', 'INR')),
  CONSTRAINT chk_wallet_role CHECK (account_role IN ('CUSTOMER', 'FX_CLEARING', 'DEMO_CLEARING', 'PAYOUT_CLEARING', 'FEE_REVENUE')),
  CONSTRAINT chk_wallet_funds CHECK (
    (account_role = 'CUSTOMER' AND balance >= held_balance AND held_balance >= 0)
    OR (account_role <> 'CUSTOMER' AND held_balance = 0)
  ),
  CONSTRAINT chk_wallet_version CHECK (version >= 0),
  CONSTRAINT uq_wallet_owner_currency_role UNIQUE (user_id, currency, account_role)
);

CREATE TABLE ledger_entries (
  id RAW(16) DEFAULT SYS_GUID() PRIMARY KEY,
  wallet_id RAW(16) NOT NULL REFERENCES wallets(id),
  entry_type VARCHAR2(10) NOT NULL,
  amount NUMBER(19,4) NOT NULL,
  currency VARCHAR2(3) NOT NULL,
  idempotency_key VARCHAR2(255) NOT NULL UNIQUE,
  journal_reference VARCHAR2(64),
  narration VARCHAR2(255),
  created_at TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL,
  CONSTRAINT chk_ledger_entry_type CHECK (entry_type IN ('DEBIT', 'CREDIT')),
  CONSTRAINT chk_ledger_entry_amount CHECK (amount > 0),
  CONSTRAINT chk_ledger_entry_currency CHECK (currency IN ('USD', 'EUR', 'INR'))
);

CREATE INDEX idx_ledger_journal_currency ON ledger_entries (journal_reference, currency);
CREATE INDEX idx_ledger_wallet_created ON ledger_entries (wallet_id, created_at DESC, id DESC);

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
  CONSTRAINT uq_wallet_operation_key UNIQUE (user_id, operation_type, client_key),
  CONSTRAINT chk_wallet_operation_type CHECK (operation_type IN ('RECEIVE_DEMO', 'CONVERT')),
  CONSTRAINT chk_wallet_operation_request_json CHECK (normalized_request IS JSON),
  -- Response is absent while IN_PROGRESS; Oracle IS JSON is strict, so allow NULL explicitly.
  CONSTRAINT chk_wallet_operation_response_json CHECK (response_snapshot IS NULL OR response_snapshot IS JSON),
  CONSTRAINT chk_wallet_operation_state CHECK (
    (status = 'IN_PROGRESS' AND response_snapshot IS NULL)
    OR (status = 'COMPLETED' AND response_snapshot IS NOT NULL)
  )
);
