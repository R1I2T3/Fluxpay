-- Fresh baseline: identity and KYC.
-- Replaces the historical auth/KYC chain with the effective final schema.
-- Users authenticate with email/password; roles stay as plain role strings.
-- KYC cases carry document metadata only; no document binary is stored here.

CREATE TABLE users (
  id RAW(16) DEFAULT SYS_GUID() PRIMARY KEY,
  email VARCHAR2(255) NOT NULL UNIQUE,
  password_hash VARCHAR2(255) NOT NULL,
  role VARCHAR2(50) DEFAULT 'USER' NOT NULL,
  full_name VARCHAR2(255) NOT NULL,
  created_at TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL,
  updated_at TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL
);

CREATE UNIQUE INDEX uq_users_email_canonical ON users (LOWER(TRIM(email)));

CREATE OR REPLACE TRIGGER trg_users_updated_at
BEFORE UPDATE ON users
FOR EACH ROW
BEGIN
  :NEW.updated_at := SYSTIMESTAMP;
END;
/

CREATE TABLE user_roles (
  user_id RAW(16) NOT NULL REFERENCES users(id),
  role VARCHAR2(50) NOT NULL,
  PRIMARY KEY (user_id, role)
);

CREATE TABLE refresh_tokens (
  id RAW(16) DEFAULT SYS_GUID() PRIMARY KEY,
  user_id RAW(16) NOT NULL REFERENCES users(id),
  token_hash VARCHAR2(255) NOT NULL,
  expires_at TIMESTAMP NOT NULL
);

CREATE TABLE kyc_cases (
  id RAW(16) DEFAULT SYS_GUID() PRIMARY KEY,
  user_id RAW(16) NOT NULL REFERENCES users(id),
  status VARCHAR2(20) DEFAULT 'PENDING' NOT NULL,
  doc_type VARCHAR2(30) NOT NULL,
  doc_number VARCHAR2(64) NOT NULL,
  created_at TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL,
  submitted_at TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL,
  decided_by RAW(16) CONSTRAINT fk_kyc_cases_decided_by REFERENCES users(id),
  decided_at TIMESTAMP,
  reject_reason VARCHAR2(500),
  version NUMBER(19) DEFAULT 0 NOT NULL,
  CONSTRAINT uq_kyc_cases_user UNIQUE (user_id),
  CONSTRAINT chk_kyc_cases_status CHECK (status IN ('PENDING', 'VERIFIED', 'REJECTED')),
  CONSTRAINT chk_kyc_cases_doc_type CHECK (doc_type IN ('PASSPORT', 'AADHAAR', 'PAN', 'DRIVING_LICENSE')),
  CONSTRAINT chk_kyc_cases_decision CHECK (
    (status = 'PENDING' AND decided_by IS NULL AND decided_at IS NULL AND reject_reason IS NULL)
    OR (status = 'VERIFIED' AND decided_by IS NOT NULL AND decided_at IS NOT NULL AND reject_reason IS NULL)
    OR (status = 'REJECTED' AND decided_by IS NOT NULL AND decided_at IS NOT NULL AND reject_reason IS NOT NULL)
  )
);

CREATE INDEX idx_kyc_cases_queue ON kyc_cases (status, submitted_at, id);

CREATE TABLE kyc_documents (
  id RAW(16) PRIMARY KEY,
  case_id RAW(16) NOT NULL CONSTRAINT fk_kyc_documents_case REFERENCES kyc_cases(id) ON DELETE CASCADE,
  file_name VARCHAR2(255) NOT NULL,
  file_type VARCHAR2(100) NOT NULL,
  file_size NUMBER(19) NOT NULL,
  storage_url VARCHAR2(1000) NOT NULL,
  uploaded_at TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL,
  CONSTRAINT chk_kyc_documents_type CHECK (file_type IN ('application/pdf', 'image/jpeg', 'image/png')),
  CONSTRAINT chk_kyc_documents_size CHECK (file_size > 0 AND file_size <= 5242880)
);

CREATE INDEX idx_kyc_documents_case ON kyc_documents (case_id);
