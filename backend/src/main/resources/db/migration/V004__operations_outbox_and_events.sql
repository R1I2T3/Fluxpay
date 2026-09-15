-- Fresh baseline: idempotent operations, durable outbox and payment events.
-- Payment operations use an explicit IN_PROGRESS/COMPLETED state: pending rows
-- carry no completion fields, completed rows require a final HTTP status and a
-- structured JSON response. The idempotency key is unique per user across payment
-- mutations. Outbox delivery orders per-payment sequences; SENDING marks a claimed
-- row with a live lease, and the covering index serves the relay claim order
-- (state, next_attempt_at, aggregate_sequence).

CREATE TABLE payment_operations (
  id RAW(16) PRIMARY KEY,
  user_id RAW(16) NOT NULL REFERENCES users(id),
  operation_type VARCHAR2(20) NOT NULL,
  client_key VARCHAR2(255) NOT NULL,
  normalized_request CLOB NOT NULL,
  outcome_status NUMBER(3),
  status VARCHAR2(20) NOT NULL,
  response_data CLOB,
  payment_id RAW(16) REFERENCES payments(id),
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  CONSTRAINT uq_payment_operation_key UNIQUE (user_id, client_key),
  CONSTRAINT chk_payment_operation_state CHECK (status IN ('IN_PROGRESS', 'COMPLETED')),
  CONSTRAINT chk_payment_operation_pending CHECK (
    status <> 'IN_PROGRESS' OR (outcome_status IS NULL AND response_data IS NULL)
  ),
  CONSTRAINT chk_payment_operation_completed CHECK (
    status <> 'COMPLETED'
    -- Oracle CHECK passes on UNKNOWN, so NULL must be excluded explicitly;
    -- otherwise a COMPLETED row with absent completion fields would pass.
    OR (outcome_status IS NOT NULL AND outcome_status BETWEEN 200 AND 599 AND response_data IS NOT NULL)
  ),
  CONSTRAINT chk_payment_operation_request_json CHECK (normalized_request IS JSON),
  -- Response data is absent while IN_PROGRESS; Oracle IS JSON is strict, so allow NULL explicitly.
  CONSTRAINT chk_payment_operation_response_json CHECK (response_data IS NULL OR response_data IS JSON)
);

CREATE TABLE review_decisions (
  id RAW(16) PRIMARY KEY,
  payment_id RAW(16) NOT NULL REFERENCES payments(id),
  review_reference VARCHAR2(100) NOT NULL,
  decision VARCHAR2(10) NOT NULL,
  receipt_expires_at TIMESTAMP WITH TIME ZONE,
  accepted_at TIMESTAMP WITH TIME ZONE NOT NULL,
  result_snapshot CLOB NOT NULL,
  CONSTRAINT uq_review_reference UNIQUE (review_reference),
  CONSTRAINT chk_review_decision CHECK (decision IN ('APPROVE', 'REJECT')),
  CONSTRAINT chk_review_result_json CHECK (result_snapshot IS JSON)
);

CREATE TABLE outbox_events (
  id RAW(16) DEFAULT SYS_GUID() PRIMARY KEY,
  topic VARCHAR2(255) NOT NULL,
  payload CLOB NOT NULL,
  created_at TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
  CONSTRAINT chk_outbox_event_payload_json CHECK (payload IS JSON)
);

CREATE TABLE outbox_delivery (
  event_id RAW(16) PRIMARY KEY REFERENCES outbox_events(id),
  payment_id RAW(16) NOT NULL REFERENCES payments(id),
  aggregate_sequence NUMBER(10) NOT NULL,
  state VARCHAR2(10) NOT NULL,
  attempt_count NUMBER(10) DEFAULT 0 NOT NULL,
  next_attempt_at TIMESTAMP WITH TIME ZONE NOT NULL,
  lease_expires_at TIMESTAMP WITH TIME ZONE,
  claim_token VARCHAR2(100),
  last_error VARCHAR2(1000),
  sent_at TIMESTAMP WITH TIME ZONE,
  CONSTRAINT uq_outbox_delivery_sequence UNIQUE (payment_id, aggregate_sequence),
  CONSTRAINT chk_outbox_delivery_state CHECK (state IN ('PENDING', 'SENDING', 'SENT'))
);

CREATE INDEX idx_outbox_delivery_pending ON outbox_delivery (state, next_attempt_at, aggregate_sequence);

CREATE TABLE payment_events (
  id RAW(16) DEFAULT SYS_GUID() PRIMARY KEY,
  payment_id VARCHAR2(50) NOT NULL,
  event_type VARCHAR2(100) NOT NULL,
  event_payload CLOB NOT NULL,
  kafka_topic VARCHAR2(150) NOT NULL,
  correlation_id VARCHAR2(100) NOT NULL,
  occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
  CONSTRAINT chk_payment_event_payload_json CHECK (event_payload IS JSON)
);

CREATE INDEX idx_payment_events_payment ON payment_events (payment_id, occurred_at);
