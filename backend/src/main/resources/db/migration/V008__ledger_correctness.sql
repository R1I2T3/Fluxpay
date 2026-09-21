-- Central currency metadata and concurrency-safe, journal-level idempotency.
-- This version intentionally remains below V601-V605. Existing deployments apply it once with
-- Flyway out-of-order enabled; fresh databases apply it in normal numeric order.

CREATE TABLE currencies (
  code CHAR(3) PRIMARY KEY,
  scale NUMBER(2) NOT NULL,
  CONSTRAINT chk_currency_scale CHECK (scale BETWEEN 0 AND 9)
);

INSERT INTO currencies (code, scale) VALUES ('USD', 2);
INSERT INTO currencies (code, scale) VALUES ('EUR', 2);
INSERT INTO currencies (code, scale) VALUES ('INR', 2);

CREATE TABLE ledger_journal_locks (
  lock_id NUMBER(3) PRIMARY KEY,
  CONSTRAINT chk_ledger_journal_lock CHECK (lock_id BETWEEN 0 AND 63)
);

INSERT INTO ledger_journal_locks (lock_id)
SELECT LEVEL - 1 FROM dual CONNECT BY LEVEL <= 64;

CREATE TABLE ledger_journals (
  journal_reference VARCHAR2(64) PRIMARY KEY,
  transaction_category VARCHAR2(24) DEFAULT 'LEGACY' NOT NULL,
  payload_hash CHAR(64),
  created_at TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL,
  CONSTRAINT chk_ledger_journal_category CHECK (
    transaction_category IN (
      'LEGACY', 'SELF_TRANSFER', 'WALLET_TO_WALLET', 'WALLET_TOPUP', 'SEND_MONEY'
    )
  ),
  CONSTRAINT chk_ledger_journal_hash CHECK (
    payload_hash IS NULL OR REGEXP_LIKE(payload_hash, '^[0-9a-f]{64}$')
  )
);

INSERT INTO ledger_journals (journal_reference, transaction_category, payload_hash, created_at)
SELECT journal_reference, 'LEGACY', NULL, MIN(created_at)
FROM ledger_entries
WHERE journal_reference IS NOT NULL
GROUP BY journal_reference;

ALTER TABLE ledger_entries ADD (
  rate NUMBER(19,8),
  quote_id VARCHAR2(36),
  CONSTRAINT chk_ledger_fx_metadata CHECK (
    (rate IS NULL AND quote_id IS NULL) OR (rate IS NOT NULL AND rate > 0 AND quote_id IS NOT NULL)
  ),
  CONSTRAINT fk_ledger_journal FOREIGN KEY (journal_reference)
    REFERENCES ledger_journals(journal_reference)
);

ALTER TABLE wallets DROP CONSTRAINT chk_wallet_role;
ALTER TABLE wallets ADD CONSTRAINT chk_wallet_role CHECK (
  account_role IN (
    'CUSTOMER', 'FX_CLEARING', 'FX_GAIN_LOSS', 'DEMO_CLEARING', 'PAYOUT_CLEARING', 'FEE_REVENUE'
  )
);
