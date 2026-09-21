CREATE TABLE policy_guidance (
  id RAW(16) DEFAULT SYS_GUID() PRIMARY KEY,
  policy_document_id RAW(16) NOT NULL REFERENCES policy_documents(id) ON DELETE CASCADE,
  compliance_case_id RAW(16) NOT NULL REFERENCES compliance_cases(id),
  content CLOB NOT NULL,
  created_at TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL,
  updated_at TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL,
  CONSTRAINT uq_m5_policy_guidance_case UNIQUE (policy_document_id, compliance_case_id)
);
CREATE INDEX idx_m5_guidance_policy ON policy_guidance(policy_document_id);
