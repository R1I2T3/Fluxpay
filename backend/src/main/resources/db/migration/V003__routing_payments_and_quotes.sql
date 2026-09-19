-- Fresh baseline: transfer routing, payments and quotes.
-- Dependency order: transfer providers/routes first, then recipients, then payments without
-- the selected-quote FK, then payment_quotes, then the selected-quote FK, then
-- payout_attempts. No legacy route/attempt tables and no seed routes are created
-- here; the route catalog is provisioned explicitly by local seeding.
-- Quotes persist the top-ranked transfer routes for one payment generation: the route
-- identity plus frozen snapshots of the route code, provider, effective reliability, ranking
-- score, and ranking position. At most three rows exist per generation. Money uses
-- NUMBER(19,4); rates use NUMBER(19,6); ranking scores use NUMBER(19,12).

CREATE TABLE transfer_providers (
  id RAW(16) DEFAULT SYS_GUID() PRIMARY KEY,
  provider_code VARCHAR2(50) NOT NULL UNIQUE,
  provider_name VARCHAR2(100) NOT NULL,
  rail_type VARCHAR2(30) NOT NULL,
  active NUMBER(1) NOT NULL,
  system_protected NUMBER(1) NOT NULL,
  version NUMBER(10) DEFAULT 0 NOT NULL,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
  archived_at TIMESTAMP WITH TIME ZONE,
  CONSTRAINT chk_transfer_provider_code CHECK (REGEXP_LIKE(provider_code, '^[A-Z][A-Z0-9_]{2,49}$')),
  CONSTRAINT chk_transfer_provider_rail CHECK (rail_type IN ('INTERNAL_LEDGER', 'BANK_NETWORK', 'REAL_TIME_NETWORK', 'PARTNER_NETWORK')),
  CONSTRAINT chk_transfer_provider_active CHECK (active IN (0, 1)),
  CONSTRAINT chk_transfer_provider_protected CHECK (system_protected IN (0, 1))
);

CREATE TABLE transfer_routes (
  id RAW(16) DEFAULT SYS_GUID() PRIMARY KEY,
  provider_id RAW(16) NOT NULL REFERENCES transfer_providers(id),
  route_code VARCHAR2(50) NOT NULL UNIQUE,
  route_name VARCHAR2(100) NOT NULL,
  destination_type VARCHAR2(30) NOT NULL,
  destination_country VARCHAR2(2),
  payout_currency VARCHAR2(3) NOT NULL,
  base_fee NUMBER(19,4) NOT NULL,
  fx_spread_percentage NUMBER(9,6) NOT NULL,
  estimated_minutes NUMBER(10) NOT NULL,
  configured_success_rate NUMBER(5,2) NOT NULL,
  minimum_recipient_amount NUMBER(19,4),
  maximum_recipient_amount NUMBER(19,4),
  active NUMBER(1) NOT NULL,
  system_protected NUMBER(1) NOT NULL,
  version NUMBER(10) DEFAULT 0 NOT NULL,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
  archived_at TIMESTAMP WITH TIME ZONE,
  CONSTRAINT chk_transfer_route_code CHECK (REGEXP_LIKE(route_code, '^[A-Z][A-Z0-9_]{2,49}$')),
  CONSTRAINT chk_transfer_route_destination CHECK (destination_type IN ('INTERNAL_WALLET', 'EXTERNAL_ACCOUNT')),
  CONSTRAINT chk_transfer_route_country CHECK (destination_type = 'INTERNAL_WALLET' OR destination_country IS NOT NULL),
  CONSTRAINT chk_transfer_route_fee CHECK (base_fee >= 0),
  CONSTRAINT chk_transfer_route_spread CHECK (fx_spread_percentage >= 0),
  CONSTRAINT chk_transfer_route_eta CHECK (estimated_minutes > 0),
  CONSTRAINT chk_transfer_route_success CHECK (configured_success_rate BETWEEN 0 AND 100),
  CONSTRAINT chk_transfer_route_limits CHECK ((minimum_recipient_amount IS NULL OR minimum_recipient_amount > 0) AND (maximum_recipient_amount IS NULL OR maximum_recipient_amount > 0) AND (minimum_recipient_amount IS NULL OR maximum_recipient_amount IS NULL OR maximum_recipient_amount >= minimum_recipient_amount)),
  CONSTRAINT chk_transfer_route_active CHECK (active IN (0, 1)),
  CONSTRAINT chk_transfer_route_protected CHECK (system_protected IN (0, 1))
);

CREATE TABLE transfer_route_outcomes (
  id RAW(16) DEFAULT SYS_GUID() PRIMARY KEY,
  transfer_route_id RAW(16) NOT NULL REFERENCES transfer_routes(id),
  execution_reference VARCHAR2(100) NOT NULL UNIQUE,
  outcome VARCHAR2(20) NOT NULL,
  occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
  CONSTRAINT chk_transfer_route_outcome CHECK (outcome IN ('COMPLETED', 'FAILED'))
);

CREATE TABLE recipients (
  id RAW(16) DEFAULT SYS_GUID() PRIMARY KEY,
  user_id RAW(16) NOT NULL REFERENCES users(id),
  name VARCHAR2(255) NOT NULL,
  account_ref VARCHAR2(255) NOT NULL,
  bank_name VARCHAR2(150),
  country VARCHAR2(2),
  currency VARCHAR2(3),
  status VARCHAR2(20) DEFAULT 'BLOCKED' NOT NULL,
  profile_complete NUMBER(1) DEFAULT 0 NOT NULL,
  version NUMBER(10) DEFAULT 0 NOT NULL,
  created_at TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL,
  updated_at TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
  CONSTRAINT chk_recipient_status CHECK (status IN ('ACTIVE', 'BLOCKED')),
  CONSTRAINT chk_recipient_profile CHECK (profile_complete IN (0, 1))
);

CREATE INDEX idx_recipients_owner_status ON recipients (user_id, status);
CREATE UNIQUE INDEX uq_recipient_owner_account ON recipients (user_id, account_ref, country);

CREATE TABLE payments (
  id RAW(16) DEFAULT SYS_GUID() PRIMARY KEY,
  sender_wallet_id RAW(16) NOT NULL REFERENCES wallets(id),
  recipient_id RAW(16) NOT NULL REFERENCES recipients(id),
  amount NUMBER(19,4) NOT NULL,
  currency VARCHAR2(3) NOT NULL,
  status VARCHAR2(20) DEFAULT 'DRAFT' NOT NULL,
  sender_id RAW(16) REFERENCES users(id),
  payout_currency VARCHAR2(3),
  purpose VARCHAR2(30),
  preference VARCHAR2(20),
  recipient_version NUMBER(10),
  recipient_snapshot CLOB,
  selected_quote_id RAW(16),
  current_quote_generation NUMBER(10),
  quote_generation_counter NUMBER(10) DEFAULT 0 NOT NULL,
  event_sequence_counter NUMBER(10) DEFAULT 0 NOT NULL,
  posting_snapshot CLOB,
  posted_at TIMESTAMP WITH TIME ZONE,
  version NUMBER(10) DEFAULT 0 NOT NULL,
  created_at TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL,
  updated_at TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
  review_reference VARCHAR2(100),
  approval_expires_at TIMESTAMP WITH TIME ZONE,
  CONSTRAINT chk_payment_status CHECK (status IN ('DRAFT', 'QUOTED', 'UNDER_REVIEW', 'PROCESSING', 'COMPLETED', 'FAILED', 'REFUNDED', 'REJECTED', 'CANCELLED')),
  -- Snapshots are absent until the payment reaches the stage that produces them.
  CONSTRAINT chk_payment_snapshot_json CHECK (recipient_snapshot IS NULL OR recipient_snapshot IS JSON),
  CONSTRAINT chk_payment_posting_json CHECK (posting_snapshot IS NULL OR posting_snapshot IS JSON)
);

CREATE INDEX idx_payments_sender_created ON payments (sender_id, created_at DESC, id DESC);
CREATE INDEX idx_payments_status_created ON payments (status, created_at DESC);

CREATE TABLE payment_quotes (
  id RAW(16) PRIMARY KEY,
  payment_id RAW(16) NOT NULL REFERENCES payments(id),
  generation NUMBER(10) NOT NULL,
  route_id RAW(16) NOT NULL REFERENCES transfer_routes(id),
  route_code VARCHAR2(50) NOT NULL,
  provider_id RAW(16) NOT NULL REFERENCES transfer_providers(id),
  market_rate NUMBER(19,6) NOT NULL,
  spread_percent NUMBER(9,6) NOT NULL,
  offered_rate NUMBER(19,6) NOT NULL,
  fee_amount NUMBER(19,4) NOT NULL,
  recipient_amount NUMBER(19,4) NOT NULL,
  estimated_minutes NUMBER(10) NOT NULL,
  effective_reliability NUMBER(9,6) NOT NULL,
  ranking_score NUMBER(19,12) NOT NULL,
  ranking_position NUMBER(2) NOT NULL,
  recommended NUMBER(1) NOT NULL,
  policy_version VARCHAR2(30) NOT NULL,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
  CONSTRAINT chk_payment_quote_positive CHECK (market_rate > 0 AND offered_rate > 0 AND fee_amount >= 0 AND recipient_amount > 0),
  CONSTRAINT chk_payment_quote_recommended CHECK (recommended IN (0, 1)),
  CONSTRAINT chk_payment_quote_position CHECK (ranking_position BETWEEN 1 AND 3),
  CONSTRAINT chk_payment_quote_reliability CHECK (effective_reliability >= 0),
  CONSTRAINT uq_payment_quote_generation UNIQUE (payment_id, generation, route_id)
);

ALTER TABLE payments ADD CONSTRAINT fk_payment_selected_quote FOREIGN KEY (selected_quote_id) REFERENCES payment_quotes (id);

CREATE INDEX idx_payment_quotes_generation ON payment_quotes (payment_id, generation);

CREATE TABLE payout_attempts (
  id RAW(16) DEFAULT SYS_GUID() PRIMARY KEY,
  payment_id VARCHAR2(50) NOT NULL,
  transfer_route_id RAW(16) NOT NULL REFERENCES transfer_routes(id),
  attempt_number NUMBER(10) NOT NULL,
  status VARCHAR2(30) NOT NULL,
  failure_reason VARCHAR2(1000),
  provider_reference VARCHAR2(100) UNIQUE,
  initiated_at TIMESTAMP WITH TIME ZONE NOT NULL,
  completed_at TIMESTAMP WITH TIME ZONE,
  CONSTRAINT chk_payout_attempt_number CHECK (attempt_number > 0),
  CONSTRAINT chk_payout_attempt_status CHECK (status IN ('INITIATED', 'PROCESSING', 'COMPLETED', 'FAILED')),
  CONSTRAINT uq_payout_attempt UNIQUE (payment_id, attempt_number)
);
