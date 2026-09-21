-- Member 5 seed data.
-- All rows below are synthetic test data: fake emails, fake account
-- references, a fixed (non-usable) bcrypt hash. Nothing here is a real
-- person, account, or credential.
--
-- Fixture users/wallets/recipients/payments exist ONLY so that
-- compliance_cases.payment_id is a real, valid foreign key into the
-- existing `payments` table (see V301). Without them the compliance_cases
-- inserts below would fail the FK constraint.

-- ---------------------------------------------------------------
-- Fixture identities
-- ---------------------------------------------------------------

INSERT INTO users (id, email, password_hash, role, full_name, created_at, updated_at) VALUES
(HEXTORAW('00000000000000000000000000005A01'), 'priya.sharma@fluxpay.test',
 '$2b$10$09AHQRHC3IVHeT2EBn85..4aKNDIXYZOACEuk3JkKKEiNeeG0ULM.', 'USER', 'Priya Sharma',
 SYSTIMESTAMP, SYSTIMESTAMP);

INSERT INTO users (id, email, password_hash, role, full_name, created_at, updated_at) VALUES
(HEXTORAW('00000000000000000000000000005A02'), 'arjun.mehta@fluxpay.test',
 '$2b$10$09AHQRHC3IVHeT2EBn85..4aKNDIXYZOACEuk3JkKKEiNeeG0ULM.', 'USER', 'Arjun Mehta',
 SYSTIMESTAMP, SYSTIMESTAMP);

INSERT INTO users (id, email, password_hash, role, full_name, created_at, updated_at) VALUES
(HEXTORAW('00000000000000000000000000005A03'), 'meera.iyer@fluxpay.test',
 '$2b$10$09AHQRHC3IVHeT2EBn85..4aKNDIXYZOACEuk3JkKKEiNeeG0ULM.', 'USER', 'Meera Iyer',
 SYSTIMESTAMP, SYSTIMESTAMP);

INSERT INTO wallets (id, user_id, currency, account_role, balance, created_at) VALUES
(HEXTORAW('00000000000000000000000000005B01'), HEXTORAW('00000000000000000000000000005A01'), 'USD', 'CUSTOMER', 500.00, SYSTIMESTAMP);

INSERT INTO wallets (id, user_id, currency, account_role, balance, created_at) VALUES
(HEXTORAW('00000000000000000000000000005B02'), HEXTORAW('00000000000000000000000000005A02'), 'USD', 'CUSTOMER', 1200.00, SYSTIMESTAMP);

INSERT INTO wallets (id, user_id, currency, account_role, balance, created_at) VALUES
(HEXTORAW('00000000000000000000000000005B03'), HEXTORAW('00000000000000000000000000005A03'), 'EUR', 'CUSTOMER', 300.00, SYSTIMESTAMP);

INSERT INTO recipients (id, user_id, name, account_ref, created_at) VALUES
(HEXTORAW('00000000000000000000000000005C01'), HEXTORAW('00000000000000000000000000005A01'), 'Priya India Savings', 'IN-SAVINGS-XXXX4821', SYSTIMESTAMP);

INSERT INTO recipients (id, user_id, name, account_ref, created_at) VALUES
(HEXTORAW('00000000000000000000000000005C02'), HEXTORAW('00000000000000000000000000005A02'), 'Alex Germany Bank', 'DE-IBAN-XXXX9053', SYSTIMESTAMP);

INSERT INTO recipients (id, user_id, name, account_ref, created_at) VALUES
(HEXTORAW('00000000000000000000000000005C03'), HEXTORAW('00000000000000000000000000005A03'), 'Meera Chennai Bank', 'IN-SAVINGS-XXXX7734', SYSTIMESTAMP);

INSERT INTO payments (id, sender_wallet_id, recipient_id, amount, currency, status, created_at) VALUES
(HEXTORAW('00000000000000000000000000005D01'), HEXTORAW('00000000000000000000000000005B01'), HEXTORAW('00000000000000000000000000005C01'), 500.00, 'USD', 'UNDER_REVIEW', SYSTIMESTAMP);

INSERT INTO payments (id, sender_wallet_id, recipient_id, amount, currency, status, created_at) VALUES
(HEXTORAW('00000000000000000000000000005D02'), HEXTORAW('00000000000000000000000000005B02'), HEXTORAW('00000000000000000000000000005C02'), 950.00, 'USD', 'UNDER_REVIEW', SYSTIMESTAMP);

INSERT INTO payments (id, sender_wallet_id, recipient_id, amount, currency, status, created_at) VALUES
(HEXTORAW('00000000000000000000000000005D03'), HEXTORAW('00000000000000000000000000005B03'), HEXTORAW('00000000000000000000000000005C03'), 220.00, 'EUR', 'COMPLETED', SYSTIMESTAMP);

INSERT INTO payments (id, sender_wallet_id, recipient_id, amount, currency, status, created_at) VALUES
(HEXTORAW('00000000000000000000000000005D04'), HEXTORAW('00000000000000000000000000005B01'), HEXTORAW('00000000000000000000000000005C01'), 1800.00, 'USD', 'UNDER_REVIEW', SYSTIMESTAMP);

INSERT INTO payments (id, sender_wallet_id, recipient_id, amount, currency, status, created_at) VALUES
(HEXTORAW('00000000000000000000000000005D05'), HEXTORAW('00000000000000000000000000005B02'), HEXTORAW('00000000000000000000000000005C02'), 75.00, 'USD', 'FAILED', SYSTIMESTAMP);

INSERT INTO payments (id, sender_wallet_id, recipient_id, amount, currency, status, created_at) VALUES
(HEXTORAW('00000000000000000000000000005D06'), HEXTORAW('00000000000000000000000000005B03'), HEXTORAW('00000000000000000000000000005C03'), 640.00, 'EUR', 'COMPLETED', SYSTIMESTAMP);

COMMIT;

-- ---------------------------------------------------------------
-- Policy corpus: the 5 policies named in the product brief.
-- document_hash is a real SHA-256-shaped hex string (64 chars) rather than
-- a slug, matching what PolicyDocumentService actually computes and
-- checks for duplicates. One seed chunk per document; embedding stays
-- NULL until the vector-indexing endpoint exists.
-- ---------------------------------------------------------------

INSERT INTO policy_documents (id, title, category, content, document_hash) VALUES
(HEXTORAW('00000000000000000000000000005E01'),
 'Customer Identity Verification Policy', 'KYC',
 'All senders must complete identity verification with a government-issued document before international withdrawals are permitted. Enhanced due diligence applies to higher-risk profiles and repeated verification failures.',
 '7a1e6f0c2b6a4d9e8c3f5a2b1d0e9c8a7b6f5e4d3c2b1a0f9e8d7c6b5a4f3e2d');

INSERT INTO policy_documents (id, title, category, content, document_hash) VALUES
(HEXTORAW('00000000000000000000000000005E02'),
 'New Recipient Review Policy', 'PAYMENT_REVIEW',
 'The first payment to any new recipient must be flagged for manual review to confirm recipient bank details and reduce the risk of misdirected funds.',
 '4c3b2a1f0e9d8c7b6a5f4e3d2c1b0a9f8e7d6c5b4a3f2e1d0c9b8a7f6e5d4c3b');

INSERT INTO policy_documents (id, title, category, content, document_hash) VALUES
(HEXTORAW('00000000000000000000000000005E03'),
 'High Value Payment Policy', 'PAYMENT_REVIEW',
 'Payments exceeding the configured threshold require additional review, including confirmation of source of funds and purpose of the transfer.',
 '9f8e7d6c5b4a3f2e1d0c9b8a7f6e5d4c3b2a1f0e9d8c7b6a5f4e3d2c1b0a9f8e');

INSERT INTO policy_documents (id, title, category, content, document_hash) VALUES
(HEXTORAW('00000000000000000000000000005E04'),
 'Payment Hold and Recovery Policy', 'SUPPORT',
 'When a payout attempt fails, the payment must be held and the customer offered a retry on the same route, a switch to an alternate route, or a full refund.',
 '2d1c0b9a8f7e6d5c4b3a2f1e0d9c8b7a6f5e4d3c2b1a0f9e8d7c6b5a4f3e2d1c');

INSERT INTO policy_documents (id, title, category, content, document_hash) VALUES
(HEXTORAW('00000000000000000000000000005E05'),
 'Country Transfer Rules', 'COUNTRY_RULE',
 'Transfers to destinations on the high-risk country list require mandatory manual compliance review before funds are released.',
 '6b5a4f3e2d1c0b9a8f7e6d5c4b3a2f1e0d9c8b7a6f5e4d3c2b1a0f9e8d7c6b5a');

COMMIT;

INSERT INTO policy_chunks (policy_document_id, chunk_number, content) VALUES
(HEXTORAW('00000000000000000000000000005E01'), 1,
 'All senders must complete identity verification with a government-issued document before international withdrawals are permitted.');

INSERT INTO policy_chunks (policy_document_id, chunk_number, content) VALUES
(HEXTORAW('00000000000000000000000000005E02'), 1,
 'The first payment to any new recipient must be flagged for manual review to confirm recipient bank details.');

INSERT INTO policy_chunks (policy_document_id, chunk_number, content) VALUES
(HEXTORAW('00000000000000000000000000005E03'), 1,
 'Payments exceeding the configured threshold require additional review, including confirmation of source of funds.');

INSERT INTO policy_chunks (policy_document_id, chunk_number, content) VALUES
(HEXTORAW('00000000000000000000000000005E04'), 1,
 'When a payout attempt fails, the payment must be held and the customer offered a retry, route switch, or refund.');

INSERT INTO policy_chunks (policy_document_id, chunk_number, content) VALUES
(HEXTORAW('00000000000000000000000000005E05'), 1,
 'Transfers to destinations on the high-risk country list require mandatory manual compliance review.');

COMMIT;

-- ---------------------------------------------------------------
-- Compliance cases: one per fixture payment above, deliberately
-- spanning every risk level and every case status.
-- ---------------------------------------------------------------

INSERT INTO compliance_cases (payment_id, risk, status, risk_reasons, suggested_action) VALUES
(HEXTORAW('00000000000000000000000000005D01'), 'HIGH', 'OPEN',
 '["KYC_UNVERIFIED"]',
 'Hold payment and route to manual compliance review.');

INSERT INTO compliance_cases (payment_id, risk, status, risk_reasons, suggested_action) VALUES
(HEXTORAW('00000000000000000000000000005D02'), 'MEDIUM', 'OPEN',
 '["FIRST_TRANSFER_TO_RECIPIENT","RECIPIENT_ADDED_TODAY"]',
 'Confirm recipient bank details before payout.');

INSERT INTO compliance_cases
  (payment_id, risk, status, risk_reasons, suggested_action, decided_by, decided_at, decision_reason) VALUES
(HEXTORAW('00000000000000000000000000005D03'), 'LOW', 'CLOSED',
 '["RECIPIENT_KNOWN"]',
 'No action required; auto-eligible for release.',
 'admin.reviewer@fluxpay.test', SYSTIMESTAMP, 'Recipient has 3 prior completed payments, cleared automatically.');

INSERT INTO compliance_cases (payment_id, risk, status, risk_reasons, suggested_action) VALUES
(HEXTORAW('00000000000000000000000000005D04'), 'HIGH', 'OPEN',
 '["AMOUNT_EXCEEDS_THRESHOLD","FIRST_TRANSFER_TO_RECIPIENT"]',
 'Request source-of-funds confirmation and verify recipient bank details.');

INSERT INTO compliance_cases
  (payment_id, risk, status, risk_reasons, suggested_action, decided_by, decided_at, decision_reason) VALUES
(HEXTORAW('00000000000000000000000000005D05'), 'MEDIUM', 'REJECTED',
 '["PAYMENT_PURPOSE_MISSING"]',
 'Request additional payment purpose detail from customer.',
 'admin.reviewer@fluxpay.test', SYSTIMESTAMP, 'Customer did not respond within the review window; payout attempt failed and was not retried.');

INSERT INTO compliance_cases
  (payment_id, risk, status, risk_reasons, suggested_action, decided_by, decided_at, decision_reason) VALUES
(HEXTORAW('00000000000000000000000000005D06'), 'LOW', 'APPROVED',
 '["RECIPIENT_KNOWN"]',
 'No action required; auto-eligible for release.',
 'admin.reviewer@fluxpay.test', SYSTIMESTAMP, 'Reviewed and cleared for payout.');

COMMIT;

-- ---------------------------------------------------------------
-- Transfer catalogue: one system-protected internal provider plus
-- representative demonstration providers for every shipped rail.
-- Merges are insert-only so re-running the seed never overwrites an
-- administrator's providers or routes. UUIDs are deterministic:
-- uuid5(uuid.NAMESPACE_URL, 'fluxpay:provider:CODE') for providers and
-- uuid5(uuid.NAMESPACE_URL, 'fluxpay:route:CODE') for routes, matching
-- scripts/seed-local.py. External demonstrations stay inactive until an
-- administrator enables simulated payouts and activates them, because
-- their rails are installed only when
-- fluxpay.development.simulated-payouts-enabled=true.
-- ---------------------------------------------------------------

MERGE INTO transfer_providers target
USING (SELECT 'FLUXPAY' AS provider_code FROM dual) source
ON (target.provider_code = source.provider_code)
WHEN NOT MATCHED THEN INSERT
  (id, provider_code, provider_name, rail_type, active, system_protected, version, created_at, updated_at)
VALUES
  (HEXTORAW('38306FCF068655B19C26932512E8E4CF'), source.provider_code, 'FluxPay',
   'INTERNAL_LEDGER', 1, 1, 0, SYSTIMESTAMP, SYSTIMESTAMP);

MERGE INTO transfer_providers target
USING (SELECT 'DEMO_BANK_ALPHA' AS provider_code FROM dual) source
ON (target.provider_code = source.provider_code)
WHEN NOT MATCHED THEN INSERT
  (id, provider_code, provider_name, rail_type, active, system_protected, version, created_at, updated_at)
VALUES
  (HEXTORAW('9E239B061663536484510E9042102C28'), source.provider_code, 'Demo Bank Alpha',
   'BANK_NETWORK', 0, 0, 0, SYSTIMESTAMP, SYSTIMESTAMP);

MERGE INTO transfer_providers target
USING (SELECT 'DEMO_REAL_TIME' AS provider_code FROM dual) source
ON (target.provider_code = source.provider_code)
WHEN NOT MATCHED THEN INSERT
  (id, provider_code, provider_name, rail_type, active, system_protected, version, created_at, updated_at)
VALUES
  (HEXTORAW('D6727E6342385AF3B8211413B083499A'), source.provider_code, 'Demo Real-Time Network',
   'REAL_TIME_NETWORK', 0, 0, 0, SYSTIMESTAMP, SYSTIMESTAMP);

MERGE INTO transfer_providers target
USING (SELECT 'DEMO_PARTNER' AS provider_code FROM dual) source
ON (target.provider_code = source.provider_code)
WHEN NOT MATCHED THEN INSERT
  (id, provider_code, provider_name, rail_type, active, system_protected, version, created_at, updated_at)
VALUES
  (HEXTORAW('82883A0F2DAB5E169C321F4D08F7E89F'), source.provider_code, 'Demo Partner Network',
   'PARTNER_NETWORK', 0, 0, 0, SYSTIMESTAMP, SYSTIMESTAMP);

MERGE INTO transfer_routes target
USING (SELECT 'FLUXPAY_INTERNAL' AS route_code FROM dual) source
ON (target.route_code = source.route_code)
WHEN NOT MATCHED THEN INSERT
  (id, provider_id, route_code, route_name, destination_type, destination_country,
   payout_currency, base_fee, fx_spread_percentage, estimated_minutes,
   configured_success_rate, active, system_protected, version, created_at, updated_at)
VALUES
  (HEXTORAW('69BF56EC453C519681E768F3D7F6DE0C'),
   HEXTORAW('38306FCF068655B19C26932512E8E4CF'), source.route_code, 'FluxPay wallet',
   'INTERNAL_WALLET', NULL, 'INR', 0.0000, 0.000000, 1, 100.00, 1, 1, 0,
   SYSTIMESTAMP, SYSTIMESTAMP);

MERGE INTO transfer_routes target
USING (SELECT 'DEMO_BANK_STANDARD' AS route_code FROM dual) source
ON (target.route_code = source.route_code)
WHEN NOT MATCHED THEN INSERT
  (id, provider_id, route_code, route_name, destination_type, destination_country,
   payout_currency, base_fee, fx_spread_percentage, estimated_minutes,
   configured_success_rate, active, system_protected, version, created_at, updated_at)
VALUES
  (HEXTORAW('00170BB8071C50A8AED959063231BC7A'),
   HEXTORAW('9E239B061663536484510E9042102C28'), source.route_code, 'Demo bank standard',
   'EXTERNAL_ACCOUNT', 'IN', 'INR', 5.0000, 0.500000, 240, 99.00, 0, 0, 0,
   SYSTIMESTAMP, SYSTIMESTAMP);

MERGE INTO transfer_routes target
USING (SELECT 'DEMO_BANK_EXPRESS' AS route_code FROM dual) source
ON (target.route_code = source.route_code)
WHEN NOT MATCHED THEN INSERT
  (id, provider_id, route_code, route_name, destination_type, destination_country,
   payout_currency, base_fee, fx_spread_percentage, estimated_minutes,
   configured_success_rate, active, system_protected, version, created_at, updated_at)
VALUES
  (HEXTORAW('CBD3B26F1ADB5D5D8139B9A69CE0E803'),
   HEXTORAW('9E239B061663536484510E9042102C28'), source.route_code, 'Demo bank express',
   'EXTERNAL_ACCOUNT', 'IN', 'INR', 11.0000, 0.750000, 30, 98.00, 0, 0, 0,
   SYSTIMESTAMP, SYSTIMESTAMP);

MERGE INTO transfer_routes target
USING (SELECT 'DEMO_REALTIME_INR' AS route_code FROM dual) source
ON (target.route_code = source.route_code)
WHEN NOT MATCHED THEN INSERT
  (id, provider_id, route_code, route_name, destination_type, destination_country,
   payout_currency, base_fee, fx_spread_percentage, estimated_minutes,
   configured_success_rate, active, system_protected, version, created_at, updated_at)
VALUES
  (HEXTORAW('609E178B629652908A7AAE3DB619623E'),
   HEXTORAW('D6727E6342385AF3B8211413B083499A'), source.route_code, 'Demo real-time INR',
   'EXTERNAL_ACCOUNT', 'IN', 'INR', 8.0000, 0.400000, 5, 97.50, 0, 0, 0,
   SYSTIMESTAMP, SYSTIMESTAMP);

MERGE INTO transfer_routes target
USING (SELECT 'DEMO_PARTNER_INR' AS route_code FROM dual) source
ON (target.route_code = source.route_code)
WHEN NOT MATCHED THEN INSERT
  (id, provider_id, route_code, route_name, destination_type, destination_country,
   payout_currency, base_fee, fx_spread_percentage, estimated_minutes,
   configured_success_rate, active, system_protected, version, created_at, updated_at)
VALUES
  (HEXTORAW('F5C1758635CC5347ADAAD3A7E15D0926'),
   HEXTORAW('82883A0F2DAB5E169C321F4D08F7E89F'), source.route_code, 'Demo partner INR',
   'EXTERNAL_ACCOUNT', 'IN', 'INR', 2.0000, 0.250000, 60, 97.50, 0, 0, 0,
   SYSTIMESTAMP, SYSTIMESTAMP);

COMMIT;
