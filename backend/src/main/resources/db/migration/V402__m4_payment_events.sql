CREATE TABLE payment_events (
  id RAW(16) DEFAULT SYS_GUID() PRIMARY KEY,
  payment_id RAW(16) NOT NULL REFERENCES payments(id),
  event_type VARCHAR2(100) NOT NULL,
  event_payload CLOB NOT NULL CHECK (event_payload IS JSON),
  kafka_topic VARCHAR2(150) NOT NULL,
  correlation_id VARCHAR2(100) NOT NULL,
  occurred_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_events_payment ON payment_events(payment_id, occurred_at);
