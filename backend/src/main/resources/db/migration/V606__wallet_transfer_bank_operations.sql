-- Retain existing operation identities and allow the Task 3 money movement APIs.
ALTER TABLE wallet_operations DROP CONSTRAINT chk_wallet_operation_type;
ALTER TABLE wallet_operations ADD CONSTRAINT chk_wallet_operation_type
  CHECK (operation_type IN ('RECEIVE_DEMO', 'CONVERT', 'TRANSFER', 'WITHDRAW', 'BANK_LINK', 'BANK_TOPUP'));
