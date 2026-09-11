CREATE TABLE payment_quotes (
  id RAW(16) PRIMARY KEY, payment_id RAW(16) NOT NULL REFERENCES payments(id),
  generation NUMBER(10) NOT NULL, route VARCHAR2(20) NOT NULL, market_rate NUMBER(19,6) NOT NULL,
  spread_bps NUMBER(10) NOT NULL, offered_rate NUMBER(19,6) NOT NULL, fee_amount NUMBER(19,4) NOT NULL,
  recipient_amount NUMBER(19,4) NOT NULL, estimated_minutes NUMBER(10) NOT NULL,
  recommended NUMBER(1) NOT NULL, policy_version VARCHAR2(30) NOT NULL,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL, expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
  CONSTRAINT chk_m3_quote_route CHECK (route IN ('CHEAPEST', 'BALANCED', 'FASTEST')),
  CONSTRAINT chk_m3_quote_positive CHECK (market_rate > 0 AND offered_rate > 0 AND fee_amount >= 0 AND recipient_amount > 0),
  CONSTRAINT chk_m3_quote_recommended CHECK (recommended IN (0, 1)),
  CONSTRAINT uq_m3_quote_generation UNIQUE(payment_id, generation, route)
);
ALTER TABLE payments ADD CONSTRAINT fk_m3_payment_selected_quote FOREIGN KEY(selected_quote_id) REFERENCES payment_quotes(id);
CREATE INDEX idx_m3_quotes_payment_generation ON payment_quotes(payment_id, generation);
