-- ============================================================
-- FluxPay - Member 5 (Compliance + Policy + Vector Search)
-- Schema + sample seed data
-- Run order: schema first, then seed data, in this file top to bottom.
-- Target: Oracle Database 23ai
-- ============================================================


-- ============================================================
-- SECTION 1: SCHEMA (Flyway V501 + V502)
-- ============================================================

-- V501__m5_compliance_cases.sql
CREATE TABLE compliance_cases (
  id                RAW(16) DEFAULT SYS_GUID() PRIMARY KEY,
  payment_id        RAW(16) NOT NULL,
  risk              VARCHAR2(10) NOT NULL
                       CHECK (risk IN ('LOW','MEDIUM','HIGH')),
  status            VARCHAR2(10) DEFAULT 'OPEN' NOT NULL
                       CHECK (status IN ('OPEN','APPROVED','REJECTED','CLOSED')),
  risk_reasons      CLOB
                       CONSTRAINT cc_reasons_json CHECK (risk_reasons IS JSON),
  suggested_action  VARCHAR2(400),
  decided_by        VARCHAR2(100),
  decided_at        TIMESTAMP,
  decision_reason   VARCHAR2(500),
  created_at        TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL
);

CREATE INDEX idx_cc_payment ON compliance_cases(payment_id);
CREATE INDEX idx_cc_status  ON compliance_cases(status);


-- V502__m5_policies_vectors.sql
CREATE TABLE policy_documents (
  id              RAW(16) DEFAULT SYS_GUID() PRIMARY KEY,
  title           VARCHAR2(200) NOT NULL,
  category        VARCHAR2(20) NOT NULL
                     CHECK (category IN ('KYC','AML','PAYMENT_REVIEW','COUNTRY_RULE','SUPPORT')),
  content         CLOB NOT NULL,
  document_hash   VARCHAR2(64) UNIQUE NOT NULL,
  created_at      TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL
);

CREATE TABLE policy_chunks (
  id                    RAW(16) DEFAULT SYS_GUID() PRIMARY KEY,
  policy_document_id    RAW(16) NOT NULL REFERENCES policy_documents(id) ON DELETE CASCADE,
  chunk_number          NUMBER NOT NULL,
  content               CLOB NOT NULL,
  embedding             VECTOR(1536, FLOAT32),
  CONSTRAINT uq_chunk UNIQUE (policy_document_id, chunk_number)
)
TABLESPACE USERS;


--SELECT tablespace_name,
--segment_space_management
--from user_tablespaces;

CREATE VECTOR INDEX idx_chunk_vec ON policy_chunks(embedding)
  ORGANIZATION NEIGHBOR PARTITIONS
  DISTANCE COSINE
  WITH TARGET ACCURACY 95
  TABLESPACE USERS;


-- ============================================================
-- NOTE ON IDs / SEQUENCES
-- ============================================================
-- All three tables use RAW(16) DEFAULT SYS_GUID() as the primary key.
-- SYS_GUID() generates a random 16-byte value automatically on INSERT
-- whenever you don't supply an id yourself -- no CREATE SEQUENCE or
-- trigger is required for these PKs.
--
-- The only "auto-incrementing" value in this module is chunk_number
-- in policy_chunks, and it is scoped PER DOCUMENT (1, 2, 3... resets
-- for every new policy). A single Oracle SEQUENCE is a global counter
-- and can't do that cleanly, so chunk_number is assigned in application
-- code (PolicyChunker) rather than the database.


-- ============================================================
-- SECTION 2: SAMPLE SEED DATA
-- ============================================================

-- ---- policy_documents (5 rows, fixed RAW(16) ids so policy_chunks can reference them) ----

INSERT INTO policy_documents (id, title, category, content, document_hash) VALUES
(HEXTORAW('AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA'),
 'Customer Identity Verification Policy',
 'KYC',
 'All senders must complete identity verification with a government-issued document before international withdrawals are permitted. Enhanced due diligence applies to higher-risk profiles.',
 'hash-kyc-verification-policy-0001');

INSERT INTO policy_documents (id, title, category, content, document_hash) VALUES
(HEXTORAW('BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB'),
 'New Recipient Review Policy',
 'PAYMENT_REVIEW',
 'The first payment to any new recipient must be flagged for manual review to confirm recipient bank details and reduce the risk of misdirected funds.',
 'hash-new-recipient-review-policy-0002');

INSERT INTO policy_documents (id, title, category, content, document_hash) VALUES
(HEXTORAW('CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC'),
 'High Value Payment Policy',
 'PAYMENT_REVIEW',
 'Payments exceeding the configured threshold require additional review, including confirmation of source of funds and purpose of the transfer.',
 'hash-high-value-payment-policy-0003');

INSERT INTO policy_documents (id, title, category, content, document_hash) VALUES
(HEXTORAW('DDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDD'),
 'Payment Hold and Recovery Policy',
 'SUPPORT',
 'When a payout attempt fails, the payment must be held and the customer offered a retry on the same route, a switch to an alternate route, or a full refund.',
 'hash-hold-recovery-policy-0004');

INSERT INTO policy_documents (id, title, category, content, document_hash) VALUES
(HEXTORAW('EEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEE'),
 'Country Transfer Rules',
 'COUNTRY_RULE',
 'Transfers to destinations on the high-risk country list require mandatory manual compliance review before funds are released.',
 'hash-country-transfer-rules-0005');

COMMIT;


-- ---- policy_chunks (1 chunk per document for seed purposes) ----
-- embedding is left NULL here on purpose: it is populated by the app when
-- POST /api/policies/{id}/index runs (see PolicyChunkVectorOpsImpl.saveEmbedding).

INSERT INTO policy_chunks (policy_document_id, chunk_number, content) VALUES
(HEXTORAW('AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA'), 1,
 'All senders must complete identity verification with a government-issued document before international withdrawals are permitted.');

INSERT INTO policy_chunks (policy_document_id, chunk_number, content) VALUES
(HEXTORAW('BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB'), 1,
 'The first payment to any new recipient must be flagged for manual review to confirm recipient bank details.');

INSERT INTO policy_chunks (policy_document_id, chunk_number, content) VALUES
(HEXTORAW('CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC'), 1,
 'Payments exceeding the configured threshold require additional review, including confirmation of source of funds.');

INSERT INTO policy_chunks (policy_document_id, chunk_number, content) VALUES
(HEXTORAW('DDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDD'), 1,
 'When a payout attempt fails, the payment must be held and the customer offered a retry, route switch, or refund.');

INSERT INTO policy_chunks (policy_document_id, chunk_number, content) VALUES
(HEXTORAW('EEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEE'), 1,
 'Transfers to destinations on the high-risk country list require mandatory manual compliance review.');

COMMIT;


-- ---- compliance_cases (3 sample rows) ----
-- payment_id values below are placeholder RAW(16) ids for solo/standalone dev.
-- In the integrated app, payment_id must match a real row in M3's `payments` table.

INSERT INTO compliance_cases
  (payment_id, risk, status, risk_reasons, suggested_action) VALUES
(HEXTORAW('F1F1F1F1F1F1F1F1F1F1F1F1F1F1F1F1'),
 'HIGH', 'OPEN',
 '["KYC_UNVERIFIED"]',
 'Hold payment and route to manual review');

INSERT INTO compliance_cases
  (payment_id, risk, status, risk_reasons, suggested_action) VALUES
(HEXTORAW('F2F2F2F2F2F2F2F2F2F2F2F2F2F2F2F2'),
 'MEDIUM', 'OPEN',
 '["FIRST_TO_RECIPIENT","HIGH_VALUE"]',
 'Request additional purpose info / verify recipient');

INSERT INTO compliance_cases
  (payment_id, risk, status, risk_reasons, suggested_action,
   decided_by, decided_at, decision_reason) VALUES
(HEXTORAW('F3F3F3F3F3F3F3F3F3F3F3F3F3F3F3F3'),
 'LOW', 'CLOSED',
 '["RECIPIENT_TODAY"]',
 'Auto-approve eligible',
 'admin@fluxpay.test', SYSTIMESTAMP, 'Cleared after review');

COMMIT;


-- ============================================================
-- SECTION 3: HOW EMBEDDINGS GET FILLED IN (reference only, not run here)
-- ============================================================
-- Embeddings are 1536 floating-point numbers per chunk -- not something
-- to hand-write in SQL. Your Java service builds them via EmbeddingProvider
-- and writes them using TO_VECTOR(), roughly like this:
--
-- UPDATE policy_chunks
-- SET embedding = TO_VECTOR('[0.0123, -0.0456, ... 1536 numbers ...]')
-- WHERE id = HEXTORAW('...');
--
-- To populate embeddings for the rows above, either:
--   1. Call POST /api/policies/{id}/index for each of the 5 policy_document ids, or
--   2. Run scripts/seed_policies.py, which does upload + index automatically.













-- ============================================================
-- FluxPay - Member 5 (Compliance + Policy + Vector Search)
-- Sample seed data: 10 records per table
-- Assumes tables already created (V501 / V502 migrations).
-- Target: Oracle Database 23ai
-- ============================================================


-- ============================================================
-- TABLE 1: policy_documents (10 rows)
-- category must be one of: KYC, AML, PAYMENT_REVIEW, COUNTRY_RULE, SUPPORT
-- ============================================================

INSERT INTO policy_documents (id, title, category, content, document_hash) VALUES
(HEXTORAW('0000000000000000000000000000D001'),
 'Customer Identity Verification Policy', 'KYC',
 'All senders must complete identity verification with a government-issued document before international withdrawals are permitted. Enhanced due diligence applies to higher-risk profiles and repeated verification failures.',
 'hash-policy-doc-0001');

INSERT INTO policy_documents (id, title, category, content, document_hash) VALUES
(HEXTORAW('0000000000000000000000000000D002'),
 'Anti-Money Laundering Monitoring Standard', 'AML',
 'Transaction monitoring evaluates amount, velocity, corridor risk, device changes, and beneficiary patterns before payout release. Alerts are escalated with full case documentation.',
 'hash-policy-doc-0002');

INSERT INTO policy_documents (id, title, category, content, document_hash) VALUES
(HEXTORAW('0000000000000000000000000000D003'),
 'Sanctions Screening Procedure', 'AML',
 'Customers, recipients, and payments are screened against applicable sanctions, PEP, and adverse-media data at onboarding and before payment execution.',
 'hash-policy-doc-0003');

INSERT INTO policy_documents (id, title, category, content, document_hash) VALUES
(HEXTORAW('0000000000000000000000000000D004'),
 'Cross-Border Payment Risk Policy', 'PAYMENT_REVIEW',
 'Cross-border payments above profile thresholds require source-of-funds evidence and may be held for manual review before release to the recipient.',
 'hash-policy-doc-0004');

INSERT INTO policy_documents (id, title, category, content, document_hash) VALUES
(HEXTORAW('0000000000000000000000000000D005'),
 'New Recipient Review Policy', 'PAYMENT_REVIEW',
 'The first payment to any new recipient must be flagged for manual review to confirm recipient bank details and reduce the risk of misdirected funds.',
 'hash-policy-doc-0005');

INSERT INTO policy_documents (id, title, category, content, document_hash) VALUES
(HEXTORAW('0000000000000000000000000000D006'),
 'High Value Payment Policy', 'PAYMENT_REVIEW',
 'Payment****ceeding the configured threshold require additional review, including confirmation of source of funds and purpose of the transfer.',
 'hash-policy-doc-0006');

INSERT INTO policy_documents (id, title, category, content, document_hash) VALUES
(HEXTORAW('0000000000000000000000000000D007'),
 'Payment Hold and Recovery Policy', 'SUPPORT',
 'When a payout attempt fails, the payment must be held and the customer offered a retry on the same route, a switch to an alternate route, or a full refund.',
 'hash-policy-doc-0007');

INSERT INTO policy_documents (id, title, category, content, document_hash) VALUES
(HEXTORAW('0000000000000000000000000000D008'),
 'Country Transfer Rules', 'COUNTRY_RULE',
 'Transfers to destinations on the high-risk country list require mandatory manual compliance review before funds are released.',
 'hash-policy-doc-0008');

INSERT INTO policy_documents (id, title, category, content, document_hash) VALUES
(HEXTORAW('0000000000000000000000000000D009'),
 'Customer Complaint Handling Policy', 'SUPPORT',
 'Customer complaints must be acknowledged within one business day and resolved or escalated within the applicable service-level target.',
 'hash-policy-doc-0009');

INSERT INTO policy_documents (id, title, category, content, document_hash) VALUES
(HEXTORAW('0000000000000000000000000000D010'),
 'Access Control and Privileged Account Policy', 'SUPPORT',
 'Privileged access requires named accounts, multi-factor authentication, least-privilege approval, quarterly review, and immediate revocation on exit.',
 'hash-policy-doc-0010');

COMMIT;


-- ============================================================
-- TABLE 2: policy_chunks (10 rows -- one chunk per document above)
-- embedding is left NULL: populated by POST /api/policies/{id}/index
-- ============================================================

INSERT INTO policy_chunks (policy_document_id, chunk_number, content) VALUES
(HEXTORAW('0000000000000000000000000000D001'), 1,
 'All senders must complete identity verification with a government-issued document before international withdrawals are permitted.');

INSERT INTO policy_chunks (policy_document_id, chunk_number, content) VALUES
(HEXTORAW('0000000000000000000000000000D002'), 1,
 'Transaction monitoring evaluates amount, velocity, corridor risk, device changes, and beneficiary patterns before payout release.');

INSERT INTO policy_chunks (policy_document_id, chunk_number, content) VALUES
(HEXTORAW('0000000000000000000000000000D003'), 1,
 'Customers, recipients, and payments are screened against applicable sanctions, PEP, and adverse-media data before execution.');

INSERT INTO policy_chunks (policy_document_id, chunk_number, content) VALUES
(HEXTORAW('0000000000000000000000000000D004'), 1,
 'Cross-border payments above profile thresholds require source-of-funds evidence and may be held for manual review.');

INSERT INTO policy_chunks (policy_document_id, chunk_number, content) VALUES
(HEXTORAW('0000000000000000000000000000D005'), 1,
 'The first payment to any new recipient must be flagged for manual review to confirm recipient bank details.');

INSERT INTO policy_chunks (policy_document_id, chunk_number, content) VALUES
(HEXTORAW('0000000000000000000000000000D006'), 1,
 'Payment****ceeding the configured threshold require additional review, including confirmation of source of funds.');

INSERT INTO policy_chunks (policy_document_id, chunk_number, content) VALUES
(HEXTORAW('0000000000000000000000000000D007'), 1,
 'When a payout attempt fails, the payment must be held and the customer offered a retry, route switch, or refund.');

INSERT INTO policy_chunks (policy_document_id, chunk_number, content) VALUES
(HEXTORAW('0000000000000000000000000000D008'), 1,
 'Transfers to destinations on the high-risk country list require mandatory manual compliance review.');

INSERT INTO policy_chunks (policy_document_id, chunk_number, content) VALUES
(HEXTORAW('0000000000000000000000000000D009'), 1,
 'Customer complaints must be acknowledged within one business day and resolved within the applicable service-level target.');

INSERT INTO policy_chunks (policy_document_id, chunk_number, content) VALUES
(HEXTORAW('0000000000000000000000000000D010'), 1,
 'Privileged access requires named accounts, multi-factor authentication, least-privilege approval, and quarterly review.');

COMMIT;


-- ============================================================
-- TABLE 3: compliance_cases (10 rows)
-- risk must be one of: LOW, MEDIUM, HIGH
-- status must be one of: OPEN, APPROVED, REJECTED, CLOSED
-- risk_reasons must be a valid JSON array (IS JSON constraint)
-- payment_id values are placeholders for solo/standalone dev;
-- in the integrated app they must match real rows in M3's `payments` table.
-- ============================================================

INSERT INTO compliance_cases
  (payment_id, risk, status, risk_reasons, suggested_action) VALUES
(HEXTORAW('0000000000000000000000000000F001'),
 'HIGH', 'OPEN',
 '["KYC_UNVERIFIED"]',
 'Hold payment and route to manual review');

INSERT INTO compliance_cases
  (payment_id, risk, status, risk_reasons, suggested_action) VALUES
(HEXTORAW('0000000000000000000000000000F002'),
 'MEDIUM', 'OPEN',
 '["FIRST_TO_RECIPIENT","HIGH_VALUE"]',
 'Request additional purpose info / verify recipient');

INSERT INTO compliance_cases
  (payment_id, risk, status, risk_reasons, suggested_action,
   decided_by, decided_at, decision_reason) VALUES
(HEXTORAW('0000000000000000000000000000F003'),
 'LOW', 'CLOSED',
 '["RECIPIENT_TODAY"]',
 'Auto-approve eligible',
 'admin@fluxpay.test', SYSTIMESTAMP, 'Cleared after review');

INSERT INTO compliance_cases
  (payment_id, risk, status, risk_reasons, suggested_action) VALUES
(HEXTORAW('0000000000000000000000000000F004'),
 'HIGH', 'OPEN',
 '["HIGH_RISK_DEST"]',
 'Hold payment and route to manual review');

INSERT INTO compliance_cases
  (payment_id, risk, status, risk_reasons, suggested_action,
   decided_by, decided_at, decision_reason) VALUES
(HEXTORAW('0000000000000000000000000000F005'),
 'MEDIUM', 'APPROVED',
 '["SHORT_PURPOSE","HIGH_VALUE"]',
 'Request additional purpose info / verify recipient',
 'admin@fluxpay.test', SYSTIMESTAMP, 'Purpose confirmed via follow-up call');

INSERT INTO compliance_cases
  (payment_id, risk, status, risk_reasons, suggested_action) VALUES
(HEXTORAW('0000000000000000000000000000F006'),
 'LOW', 'OPEN',
 '["RECIPIENT_TODAY"]',
 'Auto-approve eligible');

INSERT INTO compliance_cases
  (payment_id, risk, status, risk_reasons, suggested_action,
   decided_by, decided_at, decision_reason) VALUES
(HEXTORAW('0000000000000000000000000000F007'),
 'HIGH', 'REJECTED',
 '["KYC_UNVERIFIED","HIGH_RISK_DEST"]',
 'Hold payment and route to manual review',
 'admin@fluxpay.test', SYSTIMESTAMP, 'Recipient country on restricted list, payment declined');

INSERT INTO compliance_cases
  (payment_id, risk, status, risk_reasons, suggested_action) VALUES
(HEXTORAW('0000000000000000000000000000F008'),
 'MEDIUM', 'OPEN',
 '["FIRST_TO_RECIPIENT","RECIPIENT_TODAY"]',
 'Request additional purpose info / verify recipient');

INSERT INTO compliance_cases
  (payment_id, risk, status, risk_reasons, suggested_action,
   decided_by, decided_at, decision_reason) VALUES
(HEXTORAW('0000000000000000000000000000F009'),
 'LOW', 'CLOSED',
 '["SHORT_PURPOSE"]',
 'Auto-approve eligible',
 'admin@fluxpay.test', SYSTIMESTAMP, 'Purpose field updated by customer, cleared');

INSERT INTO compliance_cases
  (payment_id, risk, status, risk_reasons, suggested_action) VALUES
(HEXTORAW('0000000000000000000000000000F010'),
 'MEDIUM', 'OPEN',
 '["HIGH_VALUE","SHORT_PURPOSE"]',
 'Request additional purpose info / verify recipient');

COMMIT;


-- ============================================================
-- VERIFY ROW COUNTS (run after the inserts above)
-- ============================================================
 SELECT * FROM policy_documents;
 SELECT * FROM policy_chunks;
 SELECT * FROM compliance_cases;
 
 drop table compliance_cases;
 drop table POLICY_CHUNKS  
 drop table POLICY_DOCUMENTS  
