-- Linked bank-account metadata. Full account numbers must never be persisted here.

CREATE TABLE bank_accounts (
  id RAW(16) DEFAULT SYS_GUID() PRIMARY KEY,
  user_id RAW(16) NOT NULL REFERENCES users(id),
  bank_name VARCHAR2(80) NOT NULL,
  account_last4 CHAR(4) NOT NULL,
  currency CHAR(3) NOT NULL,
  status VARCHAR2(16) NOT NULL,
  CONSTRAINT chk_bank_account_last4 CHECK (REGEXP_LIKE(account_last4, '^[0-9]{4}$')),
  CONSTRAINT chk_bank_account_currency CHECK (currency IN ('USD', 'EUR', 'INR'))
);

CREATE INDEX idx_bank_accounts_user ON bank_accounts (user_id);
