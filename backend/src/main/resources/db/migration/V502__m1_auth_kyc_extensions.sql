ALTER TABLE users ADD (
  full_name VARCHAR2(255),
  updated_at TIMESTAMP
);

UPDATE users
SET full_name = email,
    updated_at = created_at
WHERE full_name IS NULL OR updated_at IS NULL;

ALTER TABLE users MODIFY (
  full_name VARCHAR2(255) NOT NULL,
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

ALTER TABLE kyc_cases ADD (
  doc_type VARCHAR2(30),
  doc_number VARCHAR2(64),
  submitted_at TIMESTAMP,
  decided_by RAW(16),
  decided_at TIMESTAMP,
  reject_reason VARCHAR2(500),
  version NUMBER(19) DEFAULT 0 NOT NULL
);

UPDATE kyc_cases
SET submitted_at = created_at
WHERE submitted_at IS NULL;

ALTER TABLE kyc_cases MODIFY (
  doc_type VARCHAR2(30) NOT NULL,
  doc_number VARCHAR2(64) NOT NULL,
  submitted_at TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL
);

ALTER TABLE kyc_cases ADD CONSTRAINT uq_kyc_cases_user UNIQUE (user_id);
ALTER TABLE kyc_cases ADD CONSTRAINT fk_kyc_cases_decided_by FOREIGN KEY (decided_by) REFERENCES users(id);
ALTER TABLE kyc_cases ADD CONSTRAINT chk_kyc_cases_status CHECK (status IN ('PENDING', 'VERIFIED', 'REJECTED'));
ALTER TABLE kyc_cases ADD CONSTRAINT chk_kyc_cases_doc_type CHECK (doc_type IN ('PASSPORT', 'AADHAAR', 'PAN', 'DRIVING_LICENSE'));
ALTER TABLE kyc_cases ADD CONSTRAINT chk_kyc_cases_decision CHECK (
  (status = 'PENDING' AND decided_by IS NULL AND decided_at IS NULL AND reject_reason IS NULL)
  OR (status = 'VERIFIED' AND decided_by IS NOT NULL AND decided_at IS NOT NULL AND reject_reason IS NULL)
  OR (status = 'REJECTED' AND decided_by IS NOT NULL AND decided_at IS NOT NULL AND reject_reason IS NOT NULL)
);

CREATE INDEX idx_kyc_cases_queue ON kyc_cases(status, submitted_at, id);

CREATE TABLE kyc_documents (
  id RAW(16) PRIMARY KEY,
  case_id RAW(16) NOT NULL REFERENCES kyc_cases(id) ON DELETE CASCADE,
  file_name VARCHAR2(255) NOT NULL,
  file_type VARCHAR2(100) NOT NULL,
  file_size NUMBER(19) NOT NULL,
  storage_url VARCHAR2(1000) NOT NULL,
  uploaded_at TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL,
  CONSTRAINT chk_kyc_documents_type CHECK (file_type IN ('application/pdf', 'image/jpeg', 'image/png')),
  CONSTRAINT chk_kyc_documents_size CHECK (file_size > 0 AND file_size <= 5242880)
);

CREATE INDEX idx_kyc_documents_case_id ON kyc_documents(case_id);
