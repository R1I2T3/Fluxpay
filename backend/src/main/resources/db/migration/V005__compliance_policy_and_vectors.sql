-- Fresh baseline: retained compliance policy and vector structures.
-- These integrations are unfinished and kept explicitly as structures only;
-- application code must not treat their presence as an implemented integration.

CREATE TABLE screening_cases (
  id RAW(16) DEFAULT SYS_GUID() PRIMARY KEY,
  payment_id RAW(16) NOT NULL REFERENCES payments(id),
  verdict VARCHAR2(20) DEFAULT 'REVIEW' NOT NULL,
  created_at TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL
);

CREATE TABLE policies (
  code VARCHAR2(50) PRIMARY KEY,
  effect VARCHAR2(20) NOT NULL,
  created_at TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL
);

INSERT INTO policies (code, effect) VALUES ('SANCTIONS_BLOCK', 'BLOCK');
INSERT INTO policies (code, effect) VALUES ('HIGH_VALUE_REVIEW', 'REVIEW');
INSERT INTO policies (code, effect) VALUES ('VELOCITY_CAP', 'REVIEW');
INSERT INTO policies (code, effect) VALUES ('GEO_ALLOWLIST', 'ALLOW');
INSERT INTO policies (code, effect) VALUES ('MANUAL_OVERRIDE', 'ALLOW');

CREATE TABLE policy_decisions (
  id RAW(16) DEFAULT SYS_GUID() PRIMARY KEY,
  screening_case_id RAW(16) NOT NULL REFERENCES screening_cases(id),
  policy_code VARCHAR2(50) NOT NULL REFERENCES policies(code),
  created_at TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL
);

CREATE TABLE document_embeddings (
  id RAW(16) DEFAULT SYS_GUID() PRIMARY KEY,
  doc_ref VARCHAR2(255) NOT NULL,
  embedding VECTOR(1536, FLOAT32),
  created_at TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL
);
