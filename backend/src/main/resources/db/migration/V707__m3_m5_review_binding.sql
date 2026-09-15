-- Additive metadata only. Existing review and payment history remain unchanged.
ALTER TABLE payments ADD (
  review_assessment_id RAW(16),
  review_case_id RAW(16),
  review_payment_fingerprint VARCHAR2(64),
  approval_decision_id RAW(16),
  approval_consumed_at TIMESTAMP WITH TIME ZONE
);
ALTER TABLE m3_review_decisions ADD (
  normalized_command CLOB,
  assessment_id RAW(16),
  case_id RAW(16),
  payment_fingerprint VARCHAR2(64),
  receipt_consumed_at TIMESTAMP WITH TIME ZONE,
  CONSTRAINT chk_m3_review_command_json CHECK (normalized_command IS JSON)
);
ALTER TABLE payments ADD CONSTRAINT fk_m3_approval_decision
  FOREIGN KEY (approval_decision_id) REFERENCES m3_review_decisions(id);
