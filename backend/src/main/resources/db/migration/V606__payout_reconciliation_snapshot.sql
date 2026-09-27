-- Runs after V605 for existing databases as well as the fresh baseline.
-- Exact command and accepted economics, captured with the pending payout operation.
-- Existing rows remain readable; missing snapshots require manual investigation.
ALTER TABLE payment_operations ADD (payout_reservation CLOB);
ALTER TABLE payment_operations ADD CONSTRAINT chk_payout_reservation_json
  CHECK (payout_reservation IS NULL OR payout_reservation IS JSON);
