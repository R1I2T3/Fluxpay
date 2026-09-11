-- M5 cutover. V001..V604 and their historical rows are deliberately unchanged.
-- Oracle DDL commits implicitly: this entire read-only preflight MUST precede DDL.
-- Stop writers and reconcile unexpected history before applying this migration.
-- V603's known synthetic prototype cases remain in compliance_cases, inactive.
DECLARE
    unexpected NUMBER;
BEGIN
    SELECT COUNT(*) INTO unexpected FROM screening_cases;
    IF unexpected > 0 THEN
        RAISE_APPLICATION_ERROR(-20051,
          'M5 legacy screening history requires explicit assessment/reviewer reconciliation; no DDL applied.');
    END IF;

    SELECT COUNT(*) INTO unexpected FROM (
        SELECT payment_id FROM compliance_cases GROUP BY payment_id HAVING COUNT(*) > 1
    );
    IF unexpected > 0 THEN
        RAISE_APPLICATION_ERROR(-20052,
          'M5 prototype history includes additional cycles; reconcile it before cutover.');
    END IF;

    WITH expected AS (
      SELECT '00000000000000000000000000005D01' payment_hex, 'HIGH' risk, 'OPEN' status,
        '["KYC_UNVERIFIED"]' reasons,
        'Hold payment and route to manual compliance review.' action,
        CAST(NULL AS VARCHAR2(255)) reviewer, CAST(NULL AS VARCHAR2(500)) reason FROM dual
      UNION ALL SELECT '00000000000000000000000000005D02', 'MEDIUM', 'OPEN',
        '["FIRST_TRANSFER_TO_RECIPIENT","RECIPIENT_ADDED_TODAY"]',
        'Confirm recipient bank details before payout.', NULL, NULL FROM dual
      UNION ALL SELECT '00000000000000000000000000005D03', 'LOW', 'CLOSED',
        '["RECIPIENT_KNOWN"]', 'No action required; auto-eligible for release.',
        'admin.reviewer@fluxpay.test',
        'Recipient has 3 prior completed payments, cleared automatically.' FROM dual
      UNION ALL SELECT '00000000000000000000000000005D04', 'HIGH', 'OPEN',
        '["AMOUNT_EXCEEDS_THRESHOLD","FIRST_TRANSFER_TO_RECIPIENT"]',
        'Request source-of-funds confirmation and verify recipient bank details.', NULL, NULL FROM dual
      UNION ALL SELECT '00000000000000000000000000005D05', 'MEDIUM', 'REJECTED',
        '["PAYMENT_PURPOSE_MISSING"]', 'Request additional payment purpose detail from customer.',
        'admin.reviewer@fluxpay.test',
        'Customer did not respond within the review window; payout attempt failed and was not retried.' FROM dual
      UNION ALL SELECT '00000000000000000000000000005D06', 'LOW', 'APPROVED',
        '["RECIPIENT_KNOWN"]', 'No action required; auto-eligible for release.',
        'admin.reviewer@fluxpay.test', 'Reviewed and cleared for payout.' FROM dual
    )
    SELECT COUNT(*) INTO unexpected FROM compliance_cases c
    WHERE NOT EXISTS (
      SELECT 1 FROM expected e
      WHERE c.payment_id = HEXTORAW(e.payment_hex)
        AND c.risk = e.risk AND c.status = e.status
        AND DBMS_LOB.COMPARE(c.risk_reasons, TO_CLOB(e.reasons)) = 0
        AND c.suggested_action = e.action
        AND (c.decided_by = e.reviewer OR (c.decided_by IS NULL AND e.reviewer IS NULL))
        AND (c.decision_reason = e.reason OR (c.decision_reason IS NULL AND e.reason IS NULL))
        AND ((e.reviewer IS NULL AND c.decided_at IS NULL)
          OR (e.reviewer IS NOT NULL AND c.decided_at IS NOT NULL))
    );
    IF unexpected > 0 THEN
        RAISE_APPLICATION_ERROR(-20053,
          'M5 non-seed or changed prototype cases require evidence-based reconciliation; no DDL applied.');
    END IF;
END;
/

ALTER TABLE screening_cases ADD (
    assessment_id RAW(16), assessment_sequence NUMBER(19),
    request_fingerprint VARCHAR2(64), payment_fingerprint VARCHAR2(64),
    risk VARCHAR2(10), status VARCHAR2(20), screening_verdict VARCHAR2(20),
    risk_reasons CLOB, suggested_action VARCHAR2(400),
    decided_by RAW(16), decided_at TIMESTAMP, decision_reason VARCHAR2(500),
    version NUMBER(10) DEFAULT 0 NOT NULL,
    assessment_snapshot CLOB, assessment_response CLOB,
    rule_version VARCHAR2(100), rule_config_hash VARCHAR2(64),
    review_reference RAW(16), payment_disposition VARCHAR2(20)
);

-- Preflight established that no original screening history needs invented metadata.
ALTER TABLE screening_cases MODIFY (
    assessment_id NOT NULL, assessment_sequence NOT NULL,
    request_fingerprint NOT NULL, payment_fingerprint NOT NULL,
    risk NOT NULL, status NOT NULL, screening_verdict NOT NULL,
    risk_reasons NOT NULL, suggested_action NOT NULL,
    assessment_snapshot NOT NULL, assessment_response NOT NULL,
    rule_version NOT NULL, rule_config_hash NOT NULL
);
ALTER TABLE screening_cases ADD (
    CONSTRAINT m5_sc_assessment_uq UNIQUE (assessment_id),
    CONSTRAINT m5_sc_cycle_uq UNIQUE (payment_id, assessment_sequence),
    CONSTRAINT m5_sc_case_payment_uq UNIQUE (id, payment_id),
    CONSTRAINT m5_sc_head_target_uq UNIQUE (id, payment_id, assessment_sequence),
    CONSTRAINT m5_sc_command_uq UNIQUE (id, assessment_id, payment_id, review_reference),
    CONSTRAINT m5_sc_reference_uq UNIQUE (review_reference),
    CONSTRAINT m5_sc_sequence_ck CHECK (assessment_sequence > 0),
    CONSTRAINT m5_sc_version_ck CHECK (version >= 0),
    CONSTRAINT m5_sc_risk_ck CHECK (risk IN ('LOW','MEDIUM','HIGH')),
    CONSTRAINT m5_sc_status_ck CHECK (status IN ('UNDER_REVIEW','PROCESSING','APPROVED','REJECTED')),
    CONSTRAINT m5_sc_verdict_ck CHECK (verdict IN ('APPROVE','REVIEW','BLOCK')),
    CONSTRAINT m5_sc_screen_verdict_ck CHECK (screening_verdict IN ('APPROVE','REVIEW','BLOCK')),
    CONSTRAINT m5_sc_reasons_json CHECK (risk_reasons IS JSON),
    CONSTRAINT m5_sc_snapshot_json CHECK (assessment_snapshot IS JSON),
    CONSTRAINT m5_sc_response_json CHECK (assessment_response IS JSON),
    CONSTRAINT m5_sc_reviewer_fk FOREIGN KEY (decided_by) REFERENCES users(id),
    CONSTRAINT m5_sc_disposition_ck CHECK (payment_disposition IN ('REVIEW_REQUIRED','PROCEED','BLOCKED')),
    CONSTRAINT m5_sc_reference_ck CHECK (
      (payment_disposition IS NOT NULL AND payment_disposition = 'REVIEW_REQUIRED'
          AND review_reference IS NOT NULL)
      OR ((payment_disposition IS NULL OR payment_disposition IN ('PROCEED','BLOCKED'))
          AND review_reference IS NULL)),
    CONSTRAINT m5_sc_decision_ck CHECK (
      (status IN ('UNDER_REVIEW','PROCESSING') AND decided_by IS NULL
        AND decided_at IS NULL AND decision_reason IS NULL)
      OR (status IN ('APPROVED','REJECTED') AND decided_by IS NOT NULL
        AND decided_at IS NOT NULL)),
    CONSTRAINT m5_sc_reject_reason_ck CHECK (
      status <> 'REJECTED' OR TRIM(decision_reason) IS NOT NULL),
    CONSTRAINT m5_sc_status_verdict_ck CHECK (
      (status = 'UNDER_REVIEW' AND verdict = 'REVIEW')
      OR (status IN ('PROCESSING','APPROVED') AND verdict = 'APPROVE')
      OR (status = 'REJECTED' AND verdict = 'BLOCK'))
);

CREATE INDEX m5_sc_status_risk_date_idx ON screening_cases(status, risk, created_at, id);
CREATE INDEX m5_sc_created_idx ON screening_cases(created_at, id);

CREATE TABLE m5_screening_heads (
    payment_id RAW(16) CONSTRAINT m5_sh_pk PRIMARY KEY,
    latest_case_id RAW(16),
    latest_sequence NUMBER(19) DEFAULT 0 NOT NULL,
    version NUMBER(10) DEFAULT 0 NOT NULL,
    CONSTRAINT m5_sh_payment_fk FOREIGN KEY (payment_id) REFERENCES payments(id),
    CONSTRAINT m5_sh_case_payment_fk FOREIGN KEY (latest_case_id, payment_id, latest_sequence)
      REFERENCES screening_cases(id, payment_id, assessment_sequence),
    CONSTRAINT m5_sh_sequence_ck CHECK (
      (latest_case_id IS NULL AND latest_sequence = 0)
      OR (latest_case_id IS NOT NULL AND latest_sequence > 0)),
    CONSTRAINT m5_sh_version_ck CHECK (version >= 0)
);

CREATE TABLE m5_review_decisions (
    id RAW(16) CONSTRAINT m5_rd_pk PRIMARY KEY,
    case_id RAW(16) NOT NULL,
    review_reference RAW(16) NOT NULL,
    payment_id RAW(16) NOT NULL,
    assessment_id RAW(16) NOT NULL,
    payment_fingerprint VARCHAR2(64) NOT NULL,
    decision VARCHAR2(10) NOT NULL,
    reason VARCHAR2(500),
    reviewer_id RAW(16) NOT NULL,
    decided_at TIMESTAMP NOT NULL,
    delivery_state VARCHAR2(20) DEFAULT 'PENDING' NOT NULL,
    retry_count NUMBER(10) DEFAULT 0 NOT NULL,
    next_attempt_at TIMESTAMP NOT NULL,
    last_error_code VARCHAR2(100),
    CONSTRAINT m5_rd_case_uq UNIQUE (case_id),
    CONSTRAINT m5_rd_reference_uq UNIQUE (review_reference),
    CONSTRAINT m5_rd_command_fk FOREIGN KEY (case_id, assessment_id, payment_id, review_reference)
      REFERENCES screening_cases(id, assessment_id, payment_id, review_reference),
    CONSTRAINT m5_rd_reviewer_fk FOREIGN KEY (reviewer_id) REFERENCES users(id),
    CONSTRAINT m5_rd_decision_ck CHECK (decision IN ('APPROVE','REJECT')),
    CONSTRAINT m5_rd_reject_reason_ck CHECK (decision <> 'REJECT' OR TRIM(reason) IS NOT NULL),
    CONSTRAINT m5_rd_state_ck CHECK (delivery_state IN ('PENDING','ACKNOWLEDGED','CONFLICT')),
    CONSTRAINT m5_rd_retry_ck CHECK (retry_count >= 0)
);
CREATE INDEX m5_rd_delivery_idx ON m5_review_decisions(delivery_state, next_attempt_at, id);

COMMENT ON TABLE compliance_cases IS 'Inactive V601 prototype history; authoritative M5 cases are screening_cases. Do not infer assessment metadata from these rows.';
COMMENT ON TABLE m5_review_decisions IS 'Durable review commands. Acknowledgment does not prove money movement.';
