ALTER TABLE recipients ADD (
  bank_name VARCHAR2(150), country VARCHAR2(2), currency VARCHAR2(3),
  status VARCHAR2(20) DEFAULT 'BLOCKED' NOT NULL,
  profile_complete NUMBER(1) DEFAULT 0 NOT NULL,
  version NUMBER(10) DEFAULT 0 NOT NULL, updated_at TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
  CONSTRAINT chk_m3_recipient_status CHECK (status IN ('ACTIVE', 'BLOCKED')),
  CONSTRAINT chk_m3_recipient_complete CHECK (profile_complete IN (0, 1))
);
ALTER TABLE payments ADD (
  m3_flow_version NUMBER(1) DEFAULT 0 NOT NULL, sender_id RAW(16), payout_currency VARCHAR2(3),
  purpose VARCHAR2(30), preference VARCHAR2(20), recipient_version NUMBER(10), recipient_snapshot CLOB,
  selected_quote_id RAW(16), current_quote_generation NUMBER(10), quote_generation_counter NUMBER(10) DEFAULT 0 NOT NULL,
  event_sequence_counter NUMBER(10) DEFAULT 0 NOT NULL, posting_snapshot CLOB, posted_at TIMESTAMP WITH TIME ZONE,
  version NUMBER(10) DEFAULT 0 NOT NULL, updated_at TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
  review_reference VARCHAR2(100), approval_expires_at TIMESTAMP WITH TIME ZONE,
  CONSTRAINT chk_m3_payment_snapshot_json CHECK (recipient_snapshot IS JSON),
  CONSTRAINT chk_m3_payment_posting_json CHECK (posting_snapshot IS JSON)
);
CREATE INDEX idx_m3_recipients_owner_status ON recipients(user_id, status);
CREATE UNIQUE INDEX uq_m3_recipient_owner_account ON recipients(user_id, account_ref, country);
CREATE INDEX idx_m3_payments_sender_created ON payments(sender_id, created_at DESC, id DESC);
CREATE INDEX idx_m3_payments_status ON payments(status, created_at DESC);
