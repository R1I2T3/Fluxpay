-- Correct immutable revisions for the historical demo corpus. Original content/hash/time remain evidence.
-- No automatic risk rules are added. These descriptions are not operational policies or current law.
ALTER TABLE policy_documents ADD (
  policy_revision NUMBER(10) DEFAULT 1 NOT NULL,
  superseded_by_id RAW(16),
  CONSTRAINT ck_m5_policy_revision CHECK (policy_revision >= 1),
  CONSTRAINT fk_m5_policy_superseded FOREIGN KEY (superseded_by_id) REFERENCES policy_documents(id),
  CONSTRAINT ck_m5_policy_superseded CHECK (superseded_by_id IS NULL OR superseded_by_id <> id)
);
CREATE INDEX idx_m5_policy_superseded ON policy_documents(superseded_by_id);

INSERT INTO policy_documents (id, title, category, content, document_hash, policy_revision)
SELECT HEXTORAW('00000000000000000000000000006E01'), 'Current Demo Sender Verification Rule', old.category, revision.content,
       LOWER(RAWTOHEX(STANDARD_HASH(revision.content, 'SHA256'))), old.policy_revision + 1
FROM policy_documents old CROSS JOIN
  (SELECT 'The current M5 demo evaluates an authoritative payment observation. R1 KYC_UNVERIFIED has HIGH severity when sender KYC is not verified. A missing KYC observation is a data-unavailable error rather than an invented verification result. Any HIGH reason makes overall risk HIGH, routes the screening verdict to REVIEW and the recommended payment status to UNDER_REVIEW. This screening result does not itself move money. Human review and M3 confirmation remain separate operations. This rule does not establish that document authenticity, expiry, address, sanctions or PEP checks have been performed; additional controls described in historical policies are not implemented by these six rules. This document describes a prototype, not operational compliance guidance or current law.' AS content FROM dual) revision
WHERE old.id = HEXTORAW('00000000000000000000000000005E01');
UPDATE policy_documents SET superseded_by_id = HEXTORAW('00000000000000000000000000006E01')
WHERE id = HEXTORAW('00000000000000000000000000005E01');

INSERT INTO policy_documents (id, title, category, content, document_hash, policy_revision)
SELECT HEXTORAW('00000000000000000000000000006E02'), 'Current Demo First Recipient Rule', old.category, revision.content,
       LOWER(RAWTOHEX(STANDARD_HASH(revision.content, 'SHA256'))), old.policy_revision + 1
FROM policy_documents old CROSS JOIN
  (SELECT 'R2 FIRST_TO_RECIPIENT has MEDIUM severity when the authoritative observation reports no earlier completed payment from this sender to this recipient, excluding the current payment. One prior completed payment is sufficient for this specific reason to stop firing. There is no three-payment exemption and no rule named RECIPIENT_KNOWN in the current engine. R3 RECIPIENT_TODAY is independent of R2 and counts qualifying payments during the configured day, not recipient age. Any HIGH reason produces HIGH risk; without a HIGH reason, two or more MEDIUM reasons produce MEDIUM risk, while zero or one MEDIUM reason produces LOW risk. This demo logic is not fraud detection assurance or current legal policy.' AS content FROM dual) revision
WHERE old.id = HEXTORAW('00000000000000000000000000005E02');
UPDATE policy_documents SET superseded_by_id = HEXTORAW('00000000000000000000000000006E02')
WHERE id = HEXTORAW('00000000000000000000000000005E02');

INSERT INTO policy_documents (id, title, category, content, document_hash, policy_revision)
SELECT HEXTORAW('00000000000000000000000000006E03'), 'Current Demo Source Currency Value Rule', old.category, revision.content,
       LOWER(RAWTOHEX(STANDARD_HASH(revision.content, 'SHA256'))), old.policy_revision + 1
FROM policy_documents old CROSS JOIN
  (SELECT 'R4 HIGH_VALUE has MEDIUM severity only when the source amount is strictly greater than the configured threshold for its source currency. The engine does not convert the amount with fixed USD rates or live foreign exchange. Example demo settings are USD 1000, EUR 920 and INR 83500; deployed configuration is authoritative and these examples are not regulatory thresholds. An amount equal to its threshold does not trigger R4. Supported currency and positive amount with at most four decimal places are validated separately. One MEDIUM reason alone leaves overall risk LOW; two or more MEDIUM reasons produce MEDIUM risk unless a HIGH reason takes precedence. No source-of-funds verification is performed automatically by this value comparison.' AS content FROM dual) revision
WHERE old.id = HEXTORAW('00000000000000000000000000005E03');
UPDATE policy_documents SET superseded_by_id = HEXTORAW('00000000000000000000000000006E03')
WHERE id = HEXTORAW('00000000000000000000000000005E03');

INSERT INTO policy_documents (id, title, category, content, document_hash, policy_revision)
SELECT HEXTORAW('00000000000000000000000000006E04'), 'Current Demo Review and Payment Separation', old.category, revision.content,
       LOWER(RAWTOHEX(STANDARD_HASH(revision.content, 'SHA256'))), old.policy_revision + 1
FROM policy_documents old CROSS JOIN
  (SELECT 'The M5 screening engine reports risk, reasons, verdict and a recommended payment status. HIGH and MEDIUM risks recommend UNDER_REVIEW; LOW risk recommends PROCESSING. Screening and policy retrieval do not themselves execute payout, debit, refund, retry or route switching. Review decisions and the M3 confirmation workflow enforce their own current contracts and current observations. A historical policy describing a particular wallet hold, automatic release or recovery action is not evidence that M5 performed that action. Administrators should use the current case record and payment state when explaining a payment, keeping those facts separate from retrieved policy text. This is a demo workflow description, not operational or legal assurance.' AS content FROM dual) revision
WHERE old.id = HEXTORAW('00000000000000000000000000005E04');
UPDATE policy_documents SET superseded_by_id = HEXTORAW('00000000000000000000000000006E04')
WHERE id = HEXTORAW('00000000000000000000000000005E04');

INSERT INTO policy_documents (id, title, category, content, document_hash, policy_revision)
SELECT HEXTORAW('00000000000000000000000000006E05'), 'Current Demo Destination Rule', old.category, revision.content,
       LOWER(RAWTOHEX(STANDARD_HASH(revision.content, 'SHA256'))), old.policy_revision + 1
FROM policy_documents old CROSS JOIN
  (SELECT 'R6 HIGH_RISK_DEST has HIGH severity when the authoritative recipient country, compared in uppercase, appears in the configured demo high-risk-country set. The source is persisted recipient data, not a caller-supplied highRiskDestination hint. Example demo configuration lists KP, IR, SY, MM, RU and BY. This static list is synthetic demonstration configuration and is not a current sanctions list, a statement of current law or evidence that a destination is legally prohibited. Any HIGH reason produces overall HIGH risk and REVIEW with recommended status UNDER_REVIEW. The engine does not screen recipient names, bank names, sanctions lists or politically exposed persons. Production destination and sanctions controls require separately implemented and maintained systems.' AS content FROM dual) revision
WHERE old.id = HEXTORAW('00000000000000000000000000005E05');
UPDATE policy_documents SET superseded_by_id = HEXTORAW('00000000000000000000000000006E05')
WHERE id = HEXTORAW('00000000000000000000000000005E05');

INSERT INTO policy_documents (id, title, category, content, document_hash, policy_revision)
SELECT HEXTORAW('00000000000000000000000000006F01'), 'Demo Enhanced Due Diligence Control Limitations', old.category, revision.content,
       LOWER(RAWTOHEX(STANDARD_HASH(revision.content, 'SHA256'))), old.policy_revision + 1
FROM policy_documents old CROSS JOIN
  (SELECT 'The current six-rule M5 engine checks the observed sender KYC verification flag through R1. It does not automatically detect dormant accounts, require renewed identity documents, validate document expiry, trigger enhanced due diligence or verify source-of-wealth evidence. A VERIFIED observation is not a claim that those additional controls ran. Earlier policy text describing those controls is historical aspirational wording and is superseded for current retrieval. Any operational enhanced due diligence process requires a separately designed, implemented and validated workflow. The demo risk result and human review record describe only their actual observations and decisions; they are not certification of legal compliance.' AS content FROM dual) revision
WHERE old.id = HEXTORAW('00000000000000000000000000005F01');
UPDATE policy_documents SET superseded_by_id = HEXTORAW('00000000000000000000000000006F01')
WHERE id = HEXTORAW('00000000000000000000000000005F01');

INSERT INTO policy_documents (id, title, category, content, document_hash, policy_revision)
SELECT HEXTORAW('00000000000000000000000000006F02'), 'Current Demo Six Rule Monitoring Scope', old.category, revision.content,
       LOWER(RAWTOHEX(STANDARD_HASH(revision.content, 'SHA256'))), old.policy_revision + 1
FROM policy_documents old CROSS JOIN
  (SELECT 'M5 currently evaluates exactly six deterministic demo rules: R1 KYC_UNVERIFIED is HIGH for unverified sender KYC; R2 FIRST_TO_RECIPIENT is MEDIUM without an earlier completed sender-recipient payment; R3 RECIPIENT_TODAY is MEDIUM when at least one other qualifying payment occurred during the configured day; R4 HIGH_VALUE is MEDIUM for source amount strictly above its configured currency threshold; R5 SHORT_PURPOSE is MEDIUM for missing purpose or fewer than ten Unicode code points after outer stripping; R6 HIGH_RISK_DEST is HIGH for an authoritative recipient country in the configured demo set. Any HIGH reason yields HIGH risk; otherwise two or more MEDIUM reasons yield MEDIUM risk and zero or one yields LOW risk. HIGH and MEDIUM require REVIEW; LOW is APPROVE eligible. The engine does not detect structuring, laundering networks, adverse media, sanctions or PEP status. This scope is a prototype and does not establish operational compliance.' AS content FROM dual) revision
WHERE old.id = HEXTORAW('00000000000000000000000000005F02');
UPDATE policy_documents SET superseded_by_id = HEXTORAW('00000000000000000000000000006F02')
WHERE id = HEXTORAW('00000000000000000000000000005F02');

INSERT INTO policy_documents (id, title, category, content, document_hash, policy_revision)
SELECT HEXTORAW('00000000000000000000000000006F03'), 'Unimplemented Sanctions and PEP Controls', old.category, revision.content,
       LOWER(RAWTOHEX(STANDARD_HASH(revision.content, 'SHA256'))), old.policy_revision + 1
FROM policy_documents old CROSS JOIN
  (SELECT 'Sanctions screening, politically exposed person screening and adverse-media screening are not implemented by the current M5 six-rule engine. No list provider is queried, no name-match score is calculated and no true-positive or false-positive resolution workflow is implied by a policy passage. R6 compares an authoritative country code to a configured static demo set; that is not sanctions or PEP screening and the set is not current law. Historical text describing screening at onboarding and before payout, list-match blocking, watchlist retention or PEP enhanced due diligence is superseded as current implementation evidence. A production service would need separately implemented, maintained and validated controls and appropriate professional guidance.' AS content FROM dual) revision
WHERE old.id = HEXTORAW('00000000000000000000000000005F03');
UPDATE policy_documents SET superseded_by_id = HEXTORAW('00000000000000000000000000006F03')
WHERE id = HEXTORAW('00000000000000000000000000005F03');

INSERT INTO policy_documents (id, title, category, content, document_hash, policy_revision)
SELECT HEXTORAW('00000000000000000000000000006F04'), 'Current Demo Purpose Rule', old.category, revision.content,
       LOWER(RAWTOHEX(STANDARD_HASH(revision.content, 'SHA256'))), old.policy_revision + 1
FROM policy_documents old CROSS JOIN
  (SELECT 'R5 SHORT_PURPOSE has MEDIUM severity when the persisted payment purpose is null or has fewer than ten Unicode code points after leading and trailing whitespace are stripped. The observation must explicitly establish purpose availability; unavailable authoritative data fails closed rather than silently skipping this rule. The engine does not decide whether a ten-character description is plausible and it does not verify the source of funds. No caller-supplied purpose hint substitutes for the authoritative payment observation. One MEDIUM reason alone leaves overall risk LOW, while two or more MEDIUM reasons produce MEDIUM risk unless a HIGH reason exists. This demo threshold is not a statutory requirement or production compliance standard.' AS content FROM dual) revision
WHERE old.id = HEXTORAW('00000000000000000000000000005F04');
UPDATE policy_documents SET superseded_by_id = HEXTORAW('00000000000000000000000000006F04')
WHERE id = HEXTORAW('00000000000000000000000000005F04');

INSERT INTO policy_documents (id, title, category, content, document_hash, policy_revision)
SELECT HEXTORAW('00000000000000000000000000006F05'), 'Current Demo Recipient Today Rule', old.category, revision.content,
       LOWER(RAWTOHEX(STANDARD_HASH(revision.content, 'SHA256'))), old.policy_revision + 1
FROM policy_documents old CROSS JOIN
  (SELECT 'R3 RECIPIENT_TODAY has MEDIUM severity when at least one other qualifying payment for the sender-recipient pair occurred during the configured day. The current payment is excluded. The day begins at local midnight in compliance.day-zone and ends at the authoritative observation cutoff; daylight-saving transitions therefore follow the configured zone. compliance.recipient-today-mode selects ALL_ATTEMPTS or COMPLETED_ONLY. This rule is not based on when the recipient was created and is not a rolling twenty-four-hour recipient-age check. R2 independently considers an earlier completed payment. No payment-velocity model, behavioural baseline, cooling-off period or three-payment exemption is implemented by these two demo rules.' AS content FROM dual) revision
WHERE old.id = HEXTORAW('00000000000000000000000000005F05');
UPDATE policy_documents SET superseded_by_id = HEXTORAW('00000000000000000000000000006F05')
WHERE id = HEXTORAW('00000000000000000000000000005F05');

INSERT INTO policy_documents (id, title, category, content, document_hash, policy_revision)
SELECT HEXTORAW('00000000000000000000000000006F06'), 'Demo Recovery Information Boundary', old.category, revision.content,
       LOWER(RAWTOHEX(STANDARD_HASH(revision.content, 'SHA256'))), old.policy_revision + 1
FROM policy_documents old CROSS JOIN
  (SELECT 'M5 policy search supplies supporting passages and stored case context; it does not process refunds, retry payouts, switch routes or mutate payment and ledger state. Available recovery actions and idempotency guarantees must be established from the current payment module contract and the actual payment record. Do not infer a successful refund, a released hold or a particular number of recovery options from this document. Earlier detailed descriptions of recovery mechanics are retained as historical text but are superseded for current policy retrieval. The demo copilot should present stored case facts separately from policy excerpts and return no grounded answer when the current corpus does not support the requested claim.' AS content FROM dual) revision
WHERE old.id = HEXTORAW('00000000000000000000000000005F06');
UPDATE policy_documents SET superseded_by_id = HEXTORAW('00000000000000000000000000006F06')
WHERE id = HEXTORAW('00000000000000000000000000005F06');

INSERT INTO policy_documents (id, title, category, content, document_hash, policy_revision)
SELECT HEXTORAW('00000000000000000000000000006F07'), 'Demo Support and Evidence Boundaries', old.category, revision.content,
       LOWER(RAWTOHEX(STANDARD_HASH(revision.content, 'SHA256'))), old.policy_revision + 1
FROM policy_documents old CROSS JOIN
  (SELECT 'The M5 copilot retrieves current policy chunks using the configured embedding space and a cosine-distance cutoff. The distance is a retrieval measure, not confidence or proof of compliance. Answers are extractive passages with document citations; stored payment risk and reasons are separate facts when payment context is authorized and available. Policy search does not fetch live FX quotes, validate wallet balances, change recipient status or confirm that a payout completed. Questions about those operations require their current module interfaces and records. Demo policy wording is not operational or current legal advice. Superseded corpus text is retained for history and excluded from current semantic retrieval.' AS content FROM dual) revision
WHERE old.id = HEXTORAW('00000000000000000000000000005F07');
UPDATE policy_documents SET superseded_by_id = HEXTORAW('00000000000000000000000000006F07')
WHERE id = HEXTORAW('00000000000000000000000000005F07');

INSERT INTO policy_documents (id, title, category, content, document_hash, policy_revision)
SELECT HEXTORAW('00000000000000000000000000006F08'), 'Demo Identity Document Control Boundary', old.category, revision.content,
       LOWER(RAWTOHEX(STANDARD_HASH(revision.content, 'SHA256'))), old.policy_revision + 1
FROM policy_documents old CROSS JOIN
  (SELECT 'R1 KYC_UNVERIFIED consumes the authoritative sender KYC flag. The M5 rule does not itself inspect identity images, choose accepted document types, check document numbers or expiry dates, compare proof of address, or implement a re-verification schedule. A KYC flag must not be described as proof that every control mentioned in historical identity-document policies was performed. Specific document requirements belong to the implemented identity module and any separately established operational process. Earlier text prescribing country-specific document combinations and automatic expiry handling is retained as history but superseded for current retrieval. These demo policies do not state current legal identity-verification requirements.' AS content FROM dual) revision
WHERE old.id = HEXTORAW('00000000000000000000000000005F08');
UPDATE policy_documents SET superseded_by_id = HEXTORAW('00000000000000000000000000006F08')
WHERE id = HEXTORAW('00000000000000000000000000005F08');

-- canonical_hash intentionally remains NULL until explicit Java reconciliation scans ALL rows.

