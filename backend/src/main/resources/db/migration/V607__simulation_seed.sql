-- Synthetic manual-QA recipient. Simulation remains disabled unless the
-- development-only compliance property is explicitly enabled.
INSERT INTO recipients (
  id, user_id, name, account_ref, bank_name, country, currency,
  status, profile_complete, version, created_at, updated_at
) VALUES (
  HEXTORAW('00000000000000000000000000005C04'),
  HEXTORAW('00000000000000000000000000005A01'),
  'SANCTIONED_ACME',
  'SIM-SANCTIONS-0001',
  'FluxPay Simulation Bank',
  'US',
  'USD',
  'ACTIVE',
  1,
  0,
  SYSTIMESTAMP,
  SYSTIMESTAMP
);

COMMIT;
