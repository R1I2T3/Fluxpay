-- READ ONLY. Run in the explicitly designated M5 test-schema connection.
-- These queries inspect data; they do not seed, reset, repair or migrate anything.

SELECT USER AS session_user,
       SYS_CONTEXT('USERENV', 'CURRENT_SCHEMA') AS schema_name,
       SYS_CONTEXT('USERENV', 'CON_NAME') AS container_name
FROM dual;

SELECT product, version_full FROM product_component_version;

SELECT "version", "description", "success"
FROM "flyway_schema_history"
ORDER BY "installed_rank";

-- Confirm the new authoritative case lifecycle, not the retired prototype table.
SELECT RAWTOHEX(id) AS case_id, RAWTOHEX(payment_id) AS payment_id,
       RAWTOHEX(assessment_id) AS assessment_id, assessment_sequence,
       risk, status, screening_verdict, verdict, payment_disposition,
       RAWTOHEX(review_reference) AS review_reference,
       RAWTOHEX(decided_by) AS reviewer_id, decided_at, version
FROM screening_cases
ORDER BY created_at, id;

-- Every head must point to its payment's highest published assessment sequence.
SELECT RAWTOHEX(h.payment_id) AS payment_id,
       RAWTOHEX(h.latest_case_id) AS latest_case_id,
       h.latest_sequence, c.assessment_sequence, c.status
FROM m5_screening_heads h
LEFT JOIN screening_cases c ON c.id = h.latest_case_id
ORDER BY h.payment_id;

-- Expected zero rows: broken head relationship or non-latest publication.
SELECT RAWTOHEX(h.payment_id) AS invalid_head
FROM m5_screening_heads h
WHERE h.latest_case_id IS NOT NULL AND NOT EXISTS (
  SELECT 1 FROM screening_cases c
  WHERE c.id = h.latest_case_id AND c.payment_id = h.payment_id
    AND c.assessment_sequence = h.latest_sequence
    AND NOT EXISTS (SELECT 1 FROM screening_cases newer
      WHERE newer.payment_id = h.payment_id
        AND newer.assessment_sequence > h.latest_sequence)
);

-- The same decision ID persists across retry; ACKNOWLEDGED is not a payout receipt.
SELECT RAWTOHEX(id) AS decision_id, RAWTOHEX(case_id) AS case_id,
       decision, delivery_state, retry_count, next_attempt_at, last_error_code
FROM m5_review_decisions
ORDER BY decided_at, id;

-- Expected zero rows: assessment/sequence/decision duplication.
SELECT RAWTOHEX(assessment_id) AS duplicated_assessment, COUNT(*) AS copies
FROM screening_cases GROUP BY assessment_id HAVING COUNT(*) > 1;

SELECT RAWTOHEX(payment_id) AS payment_id, assessment_sequence, COUNT(*) AS copies
FROM screening_cases GROUP BY payment_id, assessment_sequence HAVING COUNT(*) > 1;

SELECT RAWTOHEX(case_id) AS duplicated_decision_case, COUNT(*) AS copies
FROM m5_review_decisions GROUP BY case_id HAVING COUNT(*) > 1;

-- Native Oracle VECTOR operations: self distance 0, near distance about 0.2,
-- orthogonal distance 1. This is a math/capability check, not semantic evidence.
SELECT VECTOR_DIMENSION_COUNT(TO_VECTOR('[1,0,0]', 3, FLOAT32)) AS dimensions,
       VECTOR_DISTANCE(TO_VECTOR('[1,0,0]', 3, FLOAT32),
                       TO_VECTOR('[1,0,0]', 3, FLOAT32), COSINE) AS self_distance,
       VECTOR_DISTANCE(TO_VECTOR('[1,0,0]', 3, FLOAT32),
                       TO_VECTOR('[0.8,0.6,0]', 3, FLOAT32), COSINE) AS near_distance,
       VECTOR_DISTANCE(TO_VECTOR('[1,0,0]', 3, FLOAT32),
                       TO_VECTOR('[0,1,0]', 3, FLOAT32), COSINE) AS orthogonal_distance
FROM dual;

-- After indexing, all canonical chunks must have 768 dimensions and FLOAT32 format.
SELECT RAWTOHEX(policy_document_id) AS document_id,
       RAWTOHEX(generation_id) AS generation_id, chunk_number,
       VECTOR_DIMENSION_COUNT(embedding) AS dimensions,
       VECTOR_DIMENSION_FORMAT(embedding) AS dimension_format
FROM policy_chunks
ORDER BY policy_document_id, generation_id, chunk_number;

-- Confirm the unrelated pre-existing embedding table was not resized.
SELECT table_name, column_name, data_type
FROM user_tab_columns
WHERE table_name IN ('POLICY_CHUNKS', 'DOCUMENT_EMBEDDINGS')
  AND column_name = 'EMBEDDING';
