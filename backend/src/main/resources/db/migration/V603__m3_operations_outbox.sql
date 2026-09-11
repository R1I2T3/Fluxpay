CREATE TABLE m3_payment_operations (
  id RAW(16) PRIMARY KEY, user_id RAW(16) NOT NULL REFERENCES users(id), operation_type VARCHAR2(20) NOT NULL,
  client_key VARCHAR2(64) NOT NULL, normalized_request CLOB NOT NULL, outcome_status NUMBER(3) NOT NULL,
  response_data CLOB NOT NULL, payment_id RAW(16) REFERENCES payments(id), created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  CONSTRAINT uq_m3_operation_key UNIQUE(user_id, operation_type, client_key),
  CONSTRAINT chk_m3_operation_request_json CHECK (normalized_request IS JSON),
  CONSTRAINT chk_m3_operation_response_json CHECK (response_data IS JSON)
);
CREATE TABLE m3_review_decisions (
  id RAW(16) PRIMARY KEY, payment_id RAW(16) NOT NULL REFERENCES payments(id), review_reference VARCHAR2(100) NOT NULL,
  decision VARCHAR2(10) NOT NULL, receipt_expires_at TIMESTAMP WITH TIME ZONE, accepted_at TIMESTAMP WITH TIME ZONE NOT NULL,
  result_snapshot CLOB NOT NULL CHECK (result_snapshot IS JSON),
  CONSTRAINT uq_m3_review_reference UNIQUE(review_reference), CONSTRAINT chk_m3_review_decision CHECK (decision IN ('APPROVE', 'REJECT'))
);
CREATE TABLE m3_outbox_delivery (
  event_id RAW(16) PRIMARY KEY REFERENCES outbox_events(id), payment_id RAW(16) NOT NULL REFERENCES payments(id),
  aggregate_sequence NUMBER(10) NOT NULL, state VARCHAR2(10) NOT NULL, attempt_count NUMBER(10) DEFAULT 0 NOT NULL,
  next_attempt_at TIMESTAMP WITH TIME ZONE NOT NULL, lease_expires_at TIMESTAMP WITH TIME ZONE,
  claim_token VARCHAR2(100), last_error VARCHAR2(1000), sent_at TIMESTAMP WITH TIME ZONE,
  CONSTRAINT uq_m3_delivery_sequence UNIQUE(payment_id, aggregate_sequence),
  CONSTRAINT chk_m3_delivery_state CHECK (state IN ('PENDING', 'SENDING', 'SENT'))
);
CREATE INDEX idx_m3_delivery_pending ON m3_outbox_delivery(state, next_attempt_at);
