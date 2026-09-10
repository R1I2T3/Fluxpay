CREATE TABLE kyc_applications (
  id RAW(16) DEFAULT SYS_GUID() PRIMARY KEY,
  user_id RAW(16) NOT NULL,
  status VARCHAR2(20) DEFAULT 'PENDING' NOT NULL,
  doc_type VARCHAR2(30) NOT NULL,
  doc_number VARCHAR2(64) NOT NULL,
  submitted_at TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL,
  decided_by RAW(16),
  decided_at TIMESTAMP,
  reject_reason VARCHAR2(500),
  CONSTRAINT uq_kyc_applications_user UNIQUE(user_id),
  CONSTRAINT fk_kyc_applications_user FOREIGN KEY(user_id) REFERENCES users(id),
  CONSTRAINT fk_kyc_applications_decided_by FOREIGN KEY(decided_by) REFERENCES users(id),
  CONSTRAINT chk_kyc_applications_status CHECK (status IN ('PENDING', 'VERIFIED', 'REJECTED')),
  CONSTRAINT chk_kyc_applications_doc_type CHECK (doc_type IN ('PASSPORT', 'AADHAAR', 'PAN', 'DRIVING_LICENSE'))
);

CREATE INDEX idx_kyc_applications_status ON kyc_applications(status);

CREATE TABLE kyc_documents (
  id RAW(16) DEFAULT SYS_GUID() PRIMARY KEY,
  application_id RAW(16) NOT NULL,
  file_name VARCHAR2(255) NOT NULL,
  file_type VARCHAR2(100),
  file_size NUMBER,
  storage_url VARCHAR2(1000) NOT NULL,
  uploaded_at TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL,
  CONSTRAINT fk_kyc_documents_application
    FOREIGN KEY(application_id) REFERENCES kyc_applications(id) ON DELETE CASCADE,
  CONSTRAINT chk_kyc_documents_file_size CHECK (file_size IS NULL OR file_size >= 0)
);

CREATE INDEX idx_kyc_documents_application_id ON kyc_documents(application_id);
