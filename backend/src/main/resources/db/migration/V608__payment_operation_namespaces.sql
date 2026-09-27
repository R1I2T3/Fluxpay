-- Caller keys retain their exact values and their public replay identities.
ALTER TABLE payment_operations ADD (identity_namespace VARCHAR2(10) DEFAULT 'PUBLIC' NOT NULL);

-- Operation types are assigned by trusted server paths, never by caller key prefixes.
-- Preserve automatic duplicate delivery across an upgrade, including pending retries.
UPDATE payment_operations SET identity_namespace = 'INTERNAL'
WHERE operation_type IN ('AUTO_SCHEDULE', 'AUTO_RETRY', 'AUTO_REFUND');

ALTER TABLE payment_operations ADD CONSTRAINT chk_payment_operation_namespace
  CHECK (identity_namespace IN ('PUBLIC', 'INTERNAL'));
ALTER TABLE payment_operations DROP CONSTRAINT uq_payment_operation_key;
ALTER TABLE payment_operations ADD CONSTRAINT uq_payment_operation_key
  UNIQUE (user_id, identity_namespace, client_key);
