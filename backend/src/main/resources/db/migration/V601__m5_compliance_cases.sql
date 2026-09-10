-- Member 5: compliance case tracking (Payment Passport decisions).
-- payment_id references the real `payments` table created in V301, so every
-- case here always points at a payment that actually exists.

CREATE TABLE compliance_cases (
  id                RAW(16) DEFAULT SYS_GUID() PRIMARY KEY,
  payment_id        RAW(16) NOT NULL REFERENCES payments(id),
  risk              VARCHAR2(10) NOT NULL
                       CHECK (risk IN ('LOW','MEDIUM','HIGH')),
  status            VARCHAR2(10) DEFAULT 'OPEN' NOT NULL
                       CHECK (status IN ('OPEN','APPROVED','REJECTED','CLOSED')),
  risk_reasons      CLOB NOT NULL
                       CONSTRAINT cc_reasons_json CHECK (risk_reasons IS JSON),
  suggested_action  VARCHAR2(400) NOT NULL,
  decided_by        VARCHAR2(255),
  decided_at        TIMESTAMP,
  decision_reason   VARCHAR2(500),
  created_at        TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL
);

CREATE INDEX idx_cc_payment ON compliance_cases(payment_id);
CREATE INDEX idx_cc_status  ON compliance_cases(status);
