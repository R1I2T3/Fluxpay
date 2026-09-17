-- Links a compliance case to the precise payment-review cycle it resolves.
ALTER TABLE compliance_cases ADD (review_reference VARCHAR2(36));
CREATE UNIQUE INDEX ux_cc_review_reference ON compliance_cases(review_reference);
