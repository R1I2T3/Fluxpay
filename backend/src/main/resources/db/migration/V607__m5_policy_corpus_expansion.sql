-- Member 5: policy corpus expansion for the Compliance Copilot.
--
-- V603 seeded exactly the 5 policies named in the product brief, each with a
-- single one-line chunk. That is enough to prove the vector-search plumbing
-- works, but not enough for the Copilot to give a good answer to most of the
-- questions an admin actually asks ("why was this flagged for a sanctions
-- hit", "what do we do about a dormant account", "when do we escalate repeat
-- payout failures", ...). This migration adds 10 more policy documents,
-- spread across all 5 categories, each split into 2-3 real content chunks
-- (not a repeat of the whole document), plus a matching set of fixture
-- payments/compliance_cases so every new risk-reason code shows up in at
-- least one real case an admin can open.
--
-- document_hash is computed the same way PolicyDocumentService computes it
-- (sha256(title + "|" + content), lower-case hex) so these rows are
-- indistinguishable from ones the app would have created itself, and a real
-- future upload of the same title/content would correctly collide with it.
--
-- generation_id is left NULL, same as V603: these are plain seed chunks, not
-- run through PolicyIndexingService/TextChunker, so the V604 managed-chunk
-- constraints (chunk_count 1..16, chunk_number 1..16) do not apply to them.
-- embedding stays NULL until these are indexed for real.

-- ---------------------------------------------------------------
-- Fixture payments: reuses the existing V603 users/wallets/recipients so
-- these compliance_cases have a real, valid payment_id FK. One payment per
-- new risk scenario below.
-- ---------------------------------------------------------------

INSERT INTO payments (id, sender_wallet_id, recipient_id, amount, currency, status, created_at) VALUES
(HEXTORAW('00000000000000000000000000005D07'), HEXTORAW('00000000000000000000000000005B01'), HEXTORAW('00000000000000000000000000005C01'), 300.00, 'USD', 'UNDER_REVIEW', SYSTIMESTAMP);

INSERT INTO payments (id, sender_wallet_id, recipient_id, amount, currency, status, created_at) VALUES
(HEXTORAW('00000000000000000000000000005D08'), HEXTORAW('00000000000000000000000000005B02'), HEXTORAW('00000000000000000000000000005C02'), 2200.00, 'USD', 'UNDER_REVIEW', SYSTIMESTAMP);

INSERT INTO payments (id, sender_wallet_id, recipient_id, amount, currency, status, created_at) VALUES
(HEXTORAW('00000000000000000000000000005D09'), HEXTORAW('00000000000000000000000000005B03'), HEXTORAW('00000000000000000000000000005C03'), 150.00, 'EUR', 'UNDER_REVIEW', SYSTIMESTAMP);

INSERT INTO payments (id, sender_wallet_id, recipient_id, amount, currency, status, created_at) VALUES
(HEXTORAW('00000000000000000000000000005D10'), HEXTORAW('00000000000000000000000000005B01'), HEXTORAW('00000000000000000000000000005C01'), 900.00, 'USD', 'UNDER_REVIEW', SYSTIMESTAMP);

INSERT INTO payments (id, sender_wallet_id, recipient_id, amount, currency, status, created_at) VALUES
(HEXTORAW('00000000000000000000000000005D11'), HEXTORAW('00000000000000000000000000005B02'), HEXTORAW('00000000000000000000000000005C02'), 500.00, 'USD', 'FAILED', SYSTIMESTAMP);

INSERT INTO payments (id, sender_wallet_id, recipient_id, amount, currency, status, created_at) VALUES
(HEXTORAW('00000000000000000000000000005D12'), HEXTORAW('00000000000000000000000000005B03'), HEXTORAW('00000000000000000000000000005C03'), 1300.00, 'EUR', 'UNDER_REVIEW', SYSTIMESTAMP);

INSERT INTO payments (id, sender_wallet_id, recipient_id, amount, currency, status, created_at) VALUES
(HEXTORAW('00000000000000000000000000005D13'), HEXTORAW('00000000000000000000000000005B01'), HEXTORAW('00000000000000000000000000005C01'), 4200.00, 'USD', 'UNDER_REVIEW', SYSTIMESTAMP);

INSERT INTO payments (id, sender_wallet_id, recipient_id, amount, currency, status, created_at) VALUES
(HEXTORAW('00000000000000000000000000005D14'), HEXTORAW('00000000000000000000000000005B02'), HEXTORAW('00000000000000000000000000005C02'), 680.00, 'USD', 'UNDER_REVIEW', SYSTIMESTAMP);

COMMIT;

-- ---------------------------------------------------------------
-- New policy documents: 10 documents, 2 per category on average,
-- covering the risk reasons used in the compliance_cases fixtures below.
-- ---------------------------------------------------------------

-- KYC ---------------------------------------------------------------------

INSERT INTO policy_documents (id, title, category, content, document_hash) VALUES
(HEXTORAW('00000000000000000000000000005E06'),
 'Identity Document Expiry and Re-Verification Policy', 'KYC',
 'A verified KYC status is only valid for as long as the underlying identity document remains valid. If a customer''s passport, national ID, or driving licence has an expiry date on file that has passed, or expires within 30 days of the payment date, the customer''s KYC status must be treated as not currently verified for the purpose of international withdrawals, even if a prior review previously marked the application VERIFIED. Automated systems must never silently continue to treat an expired document as valid. ' ||
 'When a payment is flagged for this reason, the admin should open the customer''s Profile and KYC screen, confirm the document expiry date, and request a fresh document upload and re-verification before releasing the payment. Do not approve a compliance case on this reason alone without either a renewed document or a documented exception with a named approver and a time-boxed extension of no more than 14 days. ' ||
 'Re-verification follows the same flow as first-time KYC: the customer resubmits document type, document number, and expiry date, and an admin reviews and approves or rejects it. A rejected re-verification blocks new international withdrawals until resolved, but does not reverse or cancel any payment that already completed while the document was valid.',
 LOWER(RAWTOHEX(STANDARD_HASH('Identity Document Expiry and Re-Verification Policy|A verified KYC status is only valid for as long as the underlying identity document remains valid. If a customer''s passport, national ID, or driving licence has an expiry date on file that has passed, or expires within 30 days of the payment date, the customer''s KYC status must be treated as not currently verified for the purpose of international withdrawals, even if a prior review previously marked the application VERIFIED. Automated systems must never silently continue to treat an expired document as valid. When a payment is flagged for this reason, the admin should open the customer''s Profile and KYC screen, confirm the document expiry date, and request a fresh document upload and re-verification before releasing the payment. Do not approve a compliance case on this reason alone without either a renewed document or a documented exception with a named approver and a time-boxed extension of no more than 14 days. Re-verification follows the same flow as first-time KYC: the customer resubmits document type, document number, and expiry date, and an admin reviews and approves or rejects it. A rejected re-verification blocks new international withdrawals until resolved, but does not reverse or cancel any payment that already completed while the document was valid.', 'SHA256'))));

INSERT INTO policy_documents (id, title, category, content, document_hash) VALUES
(HEXTORAW('00000000000000000000000000005E07'),
 'Enhanced Due Diligence and Dormant Account Reactivation Policy', 'KYC',
 'Standard KYC verification is sufficient for most customers, but certain profiles require Enhanced Due Diligence (EDD) before an international withdrawal is released. EDD applies when a customer is flagged as a Politically Exposed Person (PEP), when a wallet that has been inactive for 180 days or more suddenly initiates a large withdrawal, or when a customer''s stated occupation is in a cash-intensive or high-risk sector. ' ||
 'A dormant-account reactivation is not, by itself, evidence of fraud, but it is a recognized account-takeover and money-mule indicator, so the first withdrawal after a long dormancy period must be reviewed even if the customer''s KYC is otherwise VERIFIED and the amount is below the normal review threshold. The admin should confirm the login and device history around the reactivation, verify the recipient is one the customer has used before or is willing to confirm by phone or message, and document the outcome in the case decision. ' ||
 'For PEP exposure, the admin should record the specific PEP category (domestic, foreign, or international-organization official, or an immediate family member/close associate of one), require a source-of-wealth statement in addition to source-of-funds for the specific transfer, and escalate to a second reviewer before approval. PEP status alone is not a reason to reject a payment, but it always requires documented review.',
 LOWER(RAWTOHEX(STANDARD_HASH('Enhanced Due Diligence and Dormant Account Reactivation Policy|Standard KYC verification is sufficient for most customers, but certain profiles require Enhanced Due Diligence (EDD) before an international withdrawal is released. EDD applies when a customer is flagged as a Politically Exposed Person (PEP), when a wallet that has been inactive for 180 days or more suddenly initiates a large withdrawal, or when a customer''s stated occupation is in a cash-intensive or high-risk sector. A dormant-account reactivation is not, by itself, evidence of fraud, but it is a recognized account-takeover and money-mule indicator, so the first withdrawal after a long dormancy period must be reviewed even if the customer''s KYC is otherwise VERIFIED and the amount is below the normal review threshold. The admin should confirm the login and device history around the reactivation, verify the recipient is one the customer has used before or is willing to confirm by phone or message, and document the outcome in the case decision. For PEP exposure, the admin should record the specific PEP category (domestic, foreign, or international-organization official, or an immediate family member/close associate of one), require a source-of-wealth statement in addition to source-of-funds for the specific transfer, and escalate to a second reviewer before approval. PEP status alone is not a reason to reject a payment, but it always requires documented review.', 'SHA256'))));

-- AML -----------------------------------------------------------------------

INSERT INTO policy_documents (id, title, category, content, document_hash) VALUES
(HEXTORAW('00000000000000000000000000005E08'),
 'Sanctions and Politically Exposed Persons Screening Policy', 'AML',
 'Every recipient and every sender must be screened against the configured sanctions and watchlist reference data before a payment is allowed to proceed to payout. A potential match is any case where the screening step returns a fuzzy or exact name/date-of-birth/country match above the configured confidence threshold; it is not a confirmed hit, and the payment must never be auto-rejected purely on a potential match. ' ||
 'When a payment carries a sanctions potential-match reason, the admin must independently verify the match using at least two identifying attributes (full name plus date of birth, or full name plus nationality) before making any decision. If the match cannot be ruled out with confidence, the case must be escalated to a designated compliance officer rather than decided by a single reviewer, and the payment must remain held, not silently cancelled, while escalation is pending. ' ||
 'A confirmed sanctions match must result in rejection of the payment and, per this prototype''s scope, a logged decision reason only; it must never trigger a real regulatory filing, real account freeze, or any action against a real financial institution, since FluxPay never touches real money or real identities.',
 LOWER(RAWTOHEX(STANDARD_HASH('Sanctions and Politically Exposed Persons Screening Policy|Every recipient and every sender must be screened against the configured sanctions and watchlist reference data before a payment is allowed to proceed to payout. A potential match is any case where the screening step returns a fuzzy or exact name/date-of-birth/country match above the configured confidence threshold; it is not a confirmed hit, and the payment must never be auto-rejected purely on a potential match. When a payment carries a sanctions potential-match reason, the admin must independently verify the match using at least two identifying attributes (full name plus date of birth, or full name plus nationality) before making any decision. If the match cannot be ruled out with confidence, the case must be escalated to a designated compliance officer rather than decided by a single reviewer, and the payment must remain held, not silently cancelled, while escalation is pending. A confirmed sanctions match must result in rejection of the payment and, per this prototype''s scope, a logged decision reason only; it must never trigger a real regulatory filing, real account freeze, or any action against a real financial institution, since FluxPay never touches real money or real identities.', 'SHA256'))));

INSERT INTO policy_documents (id, title, category, content, document_hash) VALUES
(HEXTORAW('00000000000000000000000000005E09'),
 'Transaction Velocity and Structuring Detection Policy', 'AML',
 'A sudden increase in the number or total value of a customer''s outbound transfers within a short window is a recognized indicator of account compromise or money-mule activity and must be flagged for review, even when each individual transfer is small and unremarkable on its own. As a working guideline, more than 3 outbound withdrawal attempts within a rolling 24-hour period, or a combined outbound value exceeding twice the customer''s normal monthly pattern, should be treated as high velocity. ' ||
 'Structuring is the practice of deliberately splitting a transfer that would otherwise exceed a review threshold into several smaller transfers that each fall just under it, in order to avoid triggering review. A pattern of multiple transfers to the same recipient (or closely related recipients) within a short period, each just below the configured review threshold, should be flagged as a possible structuring pattern regardless of whether any single transfer individually looks risky. ' ||
 'For both reasons, the admin should review the customer''s recent transfer history side by side with the flagged payment, confirm whether the pattern has a legitimate explanation the customer can describe (for example, a single large purchase split by agreement with the recipient''s bank), and document that explanation in the decision reason whichever way the case is decided.',
 LOWER(RAWTOHEX(STANDARD_HASH('Transaction Velocity and Structuring Detection Policy|A sudden increase in the number or total value of a customer''s outbound transfers within a short window is a recognized indicator of account compromise or money-mule activity and must be flagged for review, even when each individual transfer is small and unremarkable on its own. As a working guideline, more than 3 outbound withdrawal attempts within a rolling 24-hour period, or a combined outbound value exceeding twice the customer''s normal monthly pattern, should be treated as high velocity. Structuring is the practice of deliberately splitting a transfer that would otherwise exceed a review threshold into several smaller transfers that each fall just under it, in order to avoid triggering review. A pattern of multiple transfers to the same recipient (or closely related recipients) within a short period, each just below the configured review threshold, should be flagged as a possible structuring pattern regardless of whether any single transfer individually looks risky. For both reasons, the admin should review the customer''s recent transfer history side by side with the flagged payment, confirm whether the pattern has a legitimate explanation the customer can describe (for example, a single large purchase split by agreement with the recipient''s bank), and document that explanation in the decision reason whichever way the case is decided.', 'SHA256'))));

INSERT INTO policy_documents (id, title, category, content, document_hash) VALUES
(HEXTORAW('00000000000000000000000000005E10'),
 'Source of Funds and Wealth Verification Policy', 'AML',
 'Any inbound credit or outbound withdrawal that is unusually large relative to the customer''s established transaction history requires a source-of-funds explanation before the related withdrawal is released. This applies whether the funds arrived as a single large simulated inbound credit or accumulated gradually and are now being withdrawn in one large transfer. ' ||
 'Source of funds is a plain-language explanation of where the specific money came from (for example, salary, sale of property, inheritance, or a gift) and, where relevant, supporting detail the customer can provide, such as an employer name or a description of the sale. It is a lower bar than source of wealth, which concerns the customer''s overall net worth and is only required for Enhanced Due Diligence cases such as PEP exposure. ' ||
 'Until a satisfactory source-of-funds explanation is recorded against the case, the payment must remain UNDER_REVIEW. The admin should never approve a source-of-funds case purely because the customer''s KYC is VERIFIED; identity verification confirms who the customer is, not where a specific transfer''s money came from.',
 LOWER(RAWTOHEX(STANDARD_HASH('Source of Funds and Wealth Verification Policy|Any inbound credit or outbound withdrawal that is unusually large relative to the customer''s established transaction history requires a source-of-funds explanation before the related withdrawal is released. This applies whether the funds arrived as a single large simulated inbound credit or accumulated gradually and are now being withdrawn in one large transfer. Source of funds is a plain-language explanation of where the specific money came from (for example, salary, sale of property, inheritance, or a gift) and, where relevant, supporting detail the customer can provide, such as an employer name or a description of the sale. It is a lower bar than source of wealth, which concerns the customer''s overall net worth and is only required for Enhanced Due Diligence cases such as PEP exposure. Until a satisfactory source-of-funds explanation is recorded against the case, the payment must remain UNDER_REVIEW. The admin should never approve a source-of-funds case purely because the customer''s KYC is VERIFIED; identity verification confirms who the customer is, not where a specific transfer''s money came from.', 'SHA256'))));

-- PAYMENT_REVIEW --------------------------------------------------------------

INSERT INTO policy_documents (id, title, category, content, document_hash) VALUES
(HEXTORAW('00000000000000000000000000005E11'),
 'Payment Purpose Documentation Policy', 'PAYMENT_REVIEW',
 'Every international withdrawal must state a payment purpose that is specific enough to be meaningful to a reviewer: for example "family support - monthly remittance" or "tuition fee payment" are acceptable, while "other", "personal", or a blank field are not. A missing or overly generic purpose must be flagged for review and must not, by itself, be a reason to reject the payment outright. ' ||
 'When a payment is flagged for this reason, the admin should request that the customer restate the purpose with enough specificity to categorize the transfer (family support, goods or services, education, medical, property, or investment), and should hold the payment until a specific purpose is on file. Repeated vague purposes from the same customer across multiple payments should be treated as a pattern worth escalating, not re-reviewed identically each time.',
 LOWER(RAWTOHEX(STANDARD_HASH('Payment Purpose Documentation Policy|Every international withdrawal must state a payment purpose that is specific enough to be meaningful to a reviewer: for example "family support - monthly remittance" or "tuition fee payment" are acceptable, while "other", "personal", or a blank field are not. A missing or overly generic purpose must be flagged for review and must not, by itself, be a reason to reject the payment outright. When a payment is flagged for this reason, the admin should request that the customer restate the purpose with enough specificity to categorize the transfer (family support, goods or services, education, medical, property, or investment), and should hold the payment until a specific purpose is on file. Repeated vague purposes from the same customer across multiple payments should be treated as a pattern worth escalating, not re-reviewed identically each time.', 'SHA256'))));

INSERT INTO policy_documents (id, title, category, content, document_hash) VALUES
(HEXTORAW('00000000000000000000000000005E12'),
 'Recipient Detail Change and Reactivation Review Policy', 'PAYMENT_REVIEW',
 'A change to a saved recipient''s bank account number, routing/IFSC/SWIFT reference, or country within the 7 days before a withdrawal is initiated must be flagged for review, even for a recipient the customer has paid before. Bank-detail changes immediately before a transfer are a well-known pattern in both social-engineering fraud (a scammer convinces the customer to "update" a recipient''s account) and account-takeover fraud, and the fact that the recipient is not brand-new does not remove that risk. ' ||
 'A payment to a recipient that was previously set to BLOCKED status and has since been reactivated must also be flagged, regardless of why the block was originally applied; the reviewer should look up the reason the recipient was blocked before deciding, since that context is often more informative than the current payment amount. ' ||
 'The suggested action in both cases is the same: confirm the current bank details directly with the customer through a channel other than the one used to request the change, before releasing the payout.',
 LOWER(RAWTOHEX(STANDARD_HASH('Recipient Detail Change and Reactivation Review Policy|A change to a saved recipient''s bank account number, routing/IFSC/SWIFT reference, or country within the 7 days before a withdrawal is initiated must be flagged for review, even for a recipient the customer has paid before. Bank-detail changes immediately before a transfer are a well-known pattern in both social-engineering fraud (a scammer convinces the customer to "update" a recipient''s account) and account-takeover fraud, and the fact that the recipient is not brand-new does not remove that risk. A payment to a recipient that was previously set to BLOCKED status and has since been reactivated must also be flagged, regardless of why the block was originally applied; the reviewer should look up the reason the recipient was blocked before deciding, since that context is often more informative than the current payment amount. The suggested action in both cases is the same: confirm the current bank details directly with the customer through a channel other than the one used to request the change, before releasing the payout.', 'SHA256'))));

-- COUNTRY_RULE ----------------------------------------------------------------

INSERT INTO policy_documents (id, title, category, content, document_hash) VALUES
(HEXTORAW('00000000000000000000000000005E13'),
 'High-Risk Corridor Additional Controls Policy', 'COUNTRY_RULE',
 'The base Country Transfer Rules require manual review for any transfer to a destination on the high-risk country list. This policy adds further controls for a smaller set of "high-risk corridors": a specific sender-country-to-destination-country pairing that is disproportionately associated with fraud or layering activity even when the destination country alone would not otherwise be flagged. ' ||
 'For a payment on a configured high-risk corridor, the admin must apply every control that would apply to a plain high-risk-country payment, plus: confirm the recipient bank is a licensed institution recognized in the destination country, cap the release at the routing engine''s configured corridor limit regardless of the customer''s wallet balance, and record the corridor name explicitly in the decision note so that corridor volume can be reviewed in aggregate later. ' ||
 'Corridor designation is reviewed periodically and can change; an admin who believes a corridor is misclassified should flag it through the Payout Route Configuration screen rather than overriding the review on a single payment.',
 LOWER(RAWTOHEX(STANDARD_HASH('High-Risk Corridor Additional Controls Policy|The base Country Transfer Rules require manual review for any transfer to a destination on the high-risk country list. This policy adds further controls for a smaller set of "high-risk corridors": a specific sender-country-to-destination-country pairing that is disproportionately associated with fraud or layering activity even when the destination country alone would not otherwise be flagged. For a payment on a configured high-risk corridor, the admin must apply every control that would apply to a plain high-risk-country payment, plus: confirm the recipient bank is a licensed institution recognized in the destination country, cap the release at the routing engine''s configured corridor limit regardless of the customer''s wallet balance, and record the corridor name explicitly in the decision note so that corridor volume can be reviewed in aggregate later. Corridor designation is reviewed periodically and can change; an admin who believes a corridor is misclassified should flag it through the Payout Route Configuration screen rather than overriding the review on a single payment.', 'SHA256'))));

-- SUPPORT -----------------------------------------------------------------------

INSERT INTO policy_documents (id, title, category, content, document_hash) VALUES
(HEXTORAW('00000000000000000000000000005E14'),
 'Repeated Payout Failure Escalation Policy', 'SUPPORT',
 'The standard Payment Hold and Recovery Policy allows a customer to retry the same route, switch to an alternate route, or accept a refund after a single payout failure. This policy governs what happens when the same payment fails more than once: after a second consecutive payout failure, regardless of route, the payment must stop being offered a same-route retry and must instead be routed to manual compliance review before any further payout attempt. ' ||
 'Repeated failures across different routes for the same payment can indicate a genuinely unreachable or invalid recipient account, but can also indicate a fraud-prevention control at the receiving institution silently rejecting the payout, which is a signal worth a human look rather than another automatic attempt. ' ||
 'When reviewing a case flagged for this reason, the admin should read the failure_reason on each prior payout_attempts row, confirm with the customer whether the recipient''s account details are current, and default to a refund if the recipient cannot be confirmed reachable within a reasonable time rather than leaving the customer''s funds held indefinitely.',
 LOWER(RAWTOHEX(STANDARD_HASH('Repeated Payout Failure Escalation Policy|The standard Payment Hold and Recovery Policy allows a customer to retry the same route, switch to an alternate route, or accept a refund after a single payout failure. This policy governs what happens when the same payment fails more than once: after a second consecutive payout failure, regardless of route, the payment must stop being offered a same-route retry and must instead be routed to manual compliance review before any further payout attempt. Repeated failures across different routes for the same payment can indicate a genuinely unreachable or invalid recipient account, but can also indicate a fraud-prevention control at the receiving institution silently rejecting the payout, which is a signal worth a human look rather than another automatic attempt. When reviewing a case flagged for this reason, the admin should read the failure_reason on each prior payout_attempts row, confirm with the customer whether the recipient''s account details are current, and default to a refund if the recipient cannot be confirmed reachable within a reasonable time rather than leaving the customer''s funds held indefinitely.', 'SHA256'))));

INSERT INTO policy_documents (id, title, category, content, document_hash) VALUES
(HEXTORAW('00000000000000000000000000005E15'),
 'Customer Communication and Case Escalation Policy', 'SUPPORT',
 'A payment placed UNDER_REVIEW must never be left with no customer-visible explanation; the Payment Detail screen''s Payment Passport must always show a risk level, the specific reasons for review, and a suggested action written in plain, specific language rather than a generic "under review" message. ' ||
 'Admins should aim to decide a compliance case within 1 business day of it entering the queue. If a case cannot be decided within that window because it depends on information from the customer, the case should stay OPEN with a decision_reason recorded describing exactly what is being waited for, so that the next reviewer who opens the case does not have to start from scratch. ' ||
 'Cases requiring input from more than one policy area (for example, a sanctions potential-match on a payment that is also a first transfer to a new recipient) should be decided by whichever reviewer is qualified for the more severe reason, and the decision note should reference every applicable policy, not just the first one found.',
 LOWER(RAWTOHEX(STANDARD_HASH('Customer Communication and Case Escalation Policy|A payment placed UNDER_REVIEW must never be left with no customer-visible explanation; the Payment Detail screen''s Payment Passport must always show a risk level, the specific reasons for review, and a suggested action written in plain, specific language rather than a generic "under review" message. Admins should aim to decide a compliance case within 1 business day of it entering the queue. If a case cannot be decided within that window because it depends on information from the customer, the case should stay OPEN with a decision_reason recorded describing exactly what is being waited for, so that the next reviewer who opens the case does not have to start from scratch. Cases requiring input from more than one policy area (for example, a sanctions potential-match on a payment that is also a first transfer to a new recipient) should be decided by whichever reviewer is qualified for the more severe reason, and the decision note should reference every applicable policy, not just the first one found.', 'SHA256'))));

COMMIT;

-- ---------------------------------------------------------------
-- Chunks for the 10 new documents above: 2-3 real passages per document,
-- matching the paragraph breaks in the content, not a single repeated blob.
-- ---------------------------------------------------------------

-- 5E06 Identity Document Expiry and Re-Verification Policy
INSERT INTO policy_chunks (policy_document_id, chunk_number, content) VALUES
(HEXTORAW('00000000000000000000000000005E06'), 1,
 'A verified KYC status is only valid for as long as the underlying identity document remains valid. If a document has an expiry date on file that has passed, or expires within 30 days of the payment date, the customer''s KYC status must be treated as not currently verified for international withdrawals, even if a prior review previously marked the application VERIFIED.');
INSERT INTO policy_chunks (policy_document_id, chunk_number, content) VALUES
(HEXTORAW('00000000000000000000000000005E06'), 2,
 'When a payment is flagged for this reason, the admin should confirm the document expiry date and request a fresh document upload and re-verification before releasing the payment, rather than approving on this reason alone without a renewed document or a documented, time-boxed exception.');
INSERT INTO policy_chunks (policy_document_id, chunk_number, content) VALUES
(HEXTORAW('00000000000000000000000000005E06'), 3,
 'Re-verification follows the same flow as first-time KYC. A rejected re-verification blocks new international withdrawals until resolved, but does not reverse or cancel any payment that already completed while the document was valid.');

-- 5E07 Enhanced Due Diligence and Dormant Account Reactivation Policy
INSERT INTO policy_chunks (policy_document_id, chunk_number, content) VALUES
(HEXTORAW('00000000000000000000000000005E07'), 1,
 'Enhanced Due Diligence (EDD) applies when a customer is flagged as a Politically Exposed Person (PEP), when a wallet inactive for 180 days or more suddenly initiates a large withdrawal, or when a customer''s stated occupation is in a cash-intensive or high-risk sector.');
INSERT INTO policy_chunks (policy_document_id, chunk_number, content) VALUES
(HEXTORAW('00000000000000000000000000005E07'), 2,
 'A dormant-account reactivation is a recognized account-takeover and money-mule indicator, so the first withdrawal after a long dormancy must be reviewed even if KYC is VERIFIED and the amount is below the normal threshold; confirm login/device history and that the recipient is one the customer recognizes.');
INSERT INTO policy_chunks (policy_document_id, chunk_number, content) VALUES
(HEXTORAW('00000000000000000000000000005E07'), 3,
 'For PEP exposure, record the specific PEP category, require a source-of-wealth statement in addition to source-of-funds, and escalate to a second reviewer before approval. PEP status alone is not a reason to reject a payment, but it always requires documented review.');

-- 5E08 Sanctions and Politically Exposed Persons Screening Policy
INSERT INTO policy_chunks (policy_document_id, chunk_number, content) VALUES
(HEXTORAW('00000000000000000000000000005E08'), 1,
 'Every recipient and sender must be screened against sanctions and watchlist reference data. A potential match is any fuzzy or exact name/date-of-birth/country match above the configured confidence threshold; it is not a confirmed hit, and a payment must never be auto-rejected purely on a potential match.');
INSERT INTO policy_chunks (policy_document_id, chunk_number, content) VALUES
(HEXTORAW('00000000000000000000000000005E08'), 2,
 'The admin must independently verify a potential match using at least two identifying attributes before deciding. If the match cannot be ruled out with confidence, escalate to a designated compliance officer and keep the payment held rather than deciding it alone or cancelling it silently.');
INSERT INTO policy_chunks (policy_document_id, chunk_number, content) VALUES
(HEXTORAW('00000000000000000000000000005E08'), 3,
 'A confirmed sanctions match results in rejection of the payment and a logged decision reason only; per this prototype''s scope it never triggers a real regulatory filing, real account freeze, or action against a real institution.');

-- 5E09 Transaction Velocity and Structuring Detection Policy
INSERT INTO policy_chunks (policy_document_id, chunk_number, content) VALUES
(HEXTORAW('00000000000000000000000000005E09'), 1,
 'A sudden increase in the number or total value of outbound transfers within a short window is a recognized indicator of account compromise or money-mule activity. More than 3 outbound attempts within a rolling 24 hours, or combined value exceeding twice the customer''s normal monthly pattern, should be treated as high velocity.');
INSERT INTO policy_chunks (policy_document_id, chunk_number, content) VALUES
(HEXTORAW('00000000000000000000000000005E09'), 2,
 'Structuring is deliberately splitting a transfer that would exceed a review threshold into several smaller transfers each just under it. Multiple transfers to the same or related recipients, each just below the threshold, should be flagged as a possible structuring pattern regardless of how any single transfer looks alone.');
INSERT INTO policy_chunks (policy_document_id, chunk_number, content) VALUES
(HEXTORAW('00000000000000000000000000005E09'), 3,
 'For both reasons, review the recent transfer history side by side with the flagged payment, confirm whether the pattern has a legitimate explanation the customer can describe, and document that explanation in the decision reason whichever way the case is decided.');

-- 5E10 Source of Funds and Wealth Verification Policy
INSERT INTO policy_chunks (policy_document_id, chunk_number, content) VALUES
(HEXTORAW('00000000000000000000000000005E10'), 1,
 'Any inbound credit or outbound withdrawal unusually large relative to the customer''s established history requires a source-of-funds explanation before the related withdrawal is released, whether the funds arrived as one large credit or accumulated gradually.');
INSERT INTO policy_chunks (policy_document_id, chunk_number, content) VALUES
(HEXTORAW('00000000000000000000000000005E10'), 2,
 'Source of funds is a plain-language explanation of where the specific money came from (salary, sale of property, inheritance, gift). It is a lower bar than source of wealth, which concerns overall net worth and is only required for Enhanced Due Diligence cases such as PEP exposure.');
INSERT INTO policy_chunks (policy_document_id, chunk_number, content) VALUES
(HEXTORAW('00000000000000000000000000005E10'), 3,
 'Until a satisfactory source-of-funds explanation is recorded, the payment must remain UNDER_REVIEW. Never approve this reason purely because KYC is VERIFIED; identity verification confirms who the customer is, not where a specific transfer''s money came from.');

-- 5E11 Payment Purpose Documentation Policy
INSERT INTO policy_chunks (policy_document_id, chunk_number, content) VALUES
(HEXTORAW('00000000000000000000000000005E11'), 1,
 'Every international withdrawal must state a payment purpose specific enough to be meaningful to a reviewer, such as "family support - monthly remittance" or "tuition fee payment"; "other", "personal", or a blank field are not acceptable and must be flagged for review, though not rejected outright on this reason alone.');
INSERT INTO policy_chunks (policy_document_id, chunk_number, content) VALUES
(HEXTORAW('00000000000000000000000000005E11'), 2,
 'Request that the customer restate the purpose with enough specificity to categorize the transfer, and hold the payment until a specific purpose is on file. Repeated vague purposes from the same customer across payments should be treated as a pattern worth escalating.');

-- 5E12 Recipient Detail Change and Reactivation Review Policy
INSERT INTO policy_chunks (policy_document_id, chunk_number, content) VALUES
(HEXTORAW('00000000000000000000000000005E12'), 1,
 'A change to a saved recipient''s bank account number, IFSC/SWIFT reference, or country within the 7 days before a withdrawal must be flagged for review, even for a recipient the customer has paid before, because bank-detail changes right before a transfer are a known social-engineering and account-takeover pattern.');
INSERT INTO policy_chunks (policy_document_id, chunk_number, content) VALUES
(HEXTORAW('00000000000000000000000000005E12'), 2,
 'A payment to a recipient that was previously BLOCKED and has since been reactivated must also be flagged regardless of the original block reason; the reviewer should look up why the recipient was blocked before deciding, since that context is often more informative than the payment amount.');
INSERT INTO policy_chunks (policy_document_id, chunk_number, content) VALUES
(HEXTORAW('00000000000000000000000000005E12'), 3,
 'The suggested action in both cases is the same: confirm the current bank details directly with the customer through a channel other than the one used to request the change, before releasing the payout.');

-- 5E13 High-Risk Corridor Additional Controls Policy
INSERT INTO policy_chunks (policy_document_id, chunk_number, content) VALUES
(HEXTORAW('00000000000000000000000000005E13'), 1,
 'Beyond the base Country Transfer Rules, a "high-risk corridor" is a specific sender-to-destination country pairing disproportionately associated with fraud or layering activity, even when the destination country alone would not otherwise be flagged.');
INSERT INTO policy_chunks (policy_document_id, chunk_number, content) VALUES
(HEXTORAW('00000000000000000000000000005E13'), 2,
 'For a payment on a high-risk corridor, apply every control that would apply to a plain high-risk-country payment, plus confirm the recipient bank is a licensed institution recognized in the destination country, cap release at the corridor limit, and record the corridor name in the decision note.');
INSERT INTO policy_chunks (policy_document_id, chunk_number, content) VALUES
(HEXTORAW('00000000000000000000000000005E13'), 3,
 'Corridor designation is reviewed periodically and can change; an admin who believes a corridor is misclassified should flag it through the Payout Route Configuration screen rather than overriding the review on a single payment.');

-- 5E14 Repeated Payout Failure Escalation Policy
INSERT INTO policy_chunks (policy_document_id, chunk_number, content) VALUES
(HEXTORAW('00000000000000000000000000005E14'), 1,
 'After a second consecutive payout failure for the same payment, regardless of route, the payment must stop being offered a same-route retry and must instead be routed to manual compliance review before any further payout attempt.');
INSERT INTO policy_chunks (policy_document_id, chunk_number, content) VALUES
(HEXTORAW('00000000000000000000000000005E14'), 2,
 'Repeated failures across routes can mean a genuinely unreachable or invalid recipient account, or a fraud-prevention control at the receiving institution silently rejecting the payout, which is a signal worth a human look rather than another automatic attempt.');
INSERT INTO policy_chunks (policy_document_id, chunk_number, content) VALUES
(HEXTORAW('00000000000000000000000000005E14'), 3,
 'Read the failure_reason on each prior payout_attempts row, confirm with the customer whether the recipient''s details are current, and default to a refund if the recipient cannot be confirmed reachable within a reasonable time.');

-- 5E15 Customer Communication and Case Escalation Policy
INSERT INTO policy_chunks (policy_document_id, chunk_number, content) VALUES
(HEXTORAW('00000000000000000000000000005E15'), 1,
 'A payment placed UNDER_REVIEW must never be left with no customer-visible explanation; the Payment Passport must always show a risk level, the specific reasons for review, and a suggested action written in plain, specific language rather than a generic "under review" message.');
INSERT INTO policy_chunks (policy_document_id, chunk_number, content) VALUES
(HEXTORAW('00000000000000000000000000005E15'), 2,
 'Admins should aim to decide a case within 1 business day. If it cannot be decided in that window because it depends on the customer, keep it OPEN with a decision_reason describing exactly what is being waited for, so the next reviewer does not start from scratch.');
INSERT INTO policy_chunks (policy_document_id, chunk_number, content) VALUES
(HEXTORAW('00000000000000000000000000005E15'), 3,
 'Cases touching more than one policy area should be decided by whichever reviewer is qualified for the more severe reason, and the decision note should reference every applicable policy, not just the first one found.');

COMMIT;

-- ---------------------------------------------------------------
-- Compliance cases: one per new fixture payment above, each exercising a
-- new, more diverse risk-reason code than the original V603 set, spanning
-- all three risk levels and OPEN/APPROVED/REJECTED statuses.
-- ---------------------------------------------------------------

INSERT INTO compliance_cases (payment_id, risk, status, risk_reasons, suggested_action) VALUES
(HEXTORAW('00000000000000000000000000005D07'), 'HIGH', 'OPEN',
 '["IDENTITY_DOCUMENT_EXPIRED"]',
 'Request a fresh identity document upload and re-verify KYC before releasing the payment.');

INSERT INTO compliance_cases (payment_id, risk, status, risk_reasons, suggested_action) VALUES
(HEXTORAW('00000000000000000000000000005D08'), 'HIGH', 'OPEN',
 '["HIGH_VELOCITY_TRANSFERS_DETECTED","POSSIBLE_STRUCTURING_PATTERN"]',
 'Review the customer''s last 24 hours of outbound transfers for a structuring pattern before deciding.');

INSERT INTO compliance_cases (payment_id, risk, status, risk_reasons, suggested_action) VALUES
(HEXTORAW('00000000000000000000000000005D09'), 'HIGH', 'OPEN',
 '["SANCTIONS_LIST_POTENTIAL_MATCH"]',
 'Independently verify the sanctions screening match using name plus date of birth before deciding; escalate if not ruled out.');

INSERT INTO compliance_cases (payment_id, risk, status, risk_reasons, suggested_action) VALUES
(HEXTORAW('00000000000000000000000000005D10'), 'MEDIUM', 'OPEN',
 '["RECIPIENT_DETAILS_RECENTLY_CHANGED","RECIPIENT_PREVIOUSLY_BLOCKED"]',
 'Confirm the recipient''s current bank details directly with the customer through a separate channel before payout.');

INSERT INTO compliance_cases
  (payment_id, risk, status, risk_reasons, suggested_action, decided_by, decided_at, decision_reason) VALUES
(HEXTORAW('00000000000000000000000000005D11'), 'MEDIUM', 'APPROVED',
 '["MULTIPLE_FAILED_PAYOUT_ATTEMPTS"]',
 'Confirm recipient account details are current before allowing a further payout attempt.',
 'admin.reviewer@fluxpay.test', SYSTIMESTAMP, 'Customer confirmed recipient details by phone after two prior route failures; cleared for a fresh payout attempt on an alternate route.');

INSERT INTO compliance_cases (payment_id, risk, status, risk_reasons, suggested_action) VALUES
(HEXTORAW('00000000000000000000000000005D12'), 'HIGH', 'OPEN',
 '["SOURCE_OF_FUNDS_UNCONFIRMED","DESTINATION_HIGH_RISK_COUNTRY"]',
 'Request a source-of-funds explanation for this transfer and apply the destination''s mandatory country-rule review before release.');

INSERT INTO compliance_cases
  (payment_id, risk, status, risk_reasons, suggested_action, decided_by, decided_at, decision_reason) VALUES
(HEXTORAW('00000000000000000000000000005D13'), 'HIGH', 'REJECTED',
 '["PEP_EXPOSURE_FLAGGED","DORMANT_ACCOUNT_REACTIVATED"]',
 'Require a source-of-wealth statement and second-reviewer escalation before any release from a reactivated PEP-linked account.',
 'admin.reviewer@fluxpay.test', SYSTIMESTAMP, 'Customer declined to provide a source-of-wealth statement within the review window; payment rejected pending fresh EDD review.');

INSERT INTO compliance_cases (payment_id, risk, status, risk_reasons, suggested_action) VALUES
(HEXTORAW('00000000000000000000000000005D14'), 'MEDIUM', 'OPEN',
 '["HIGH_RISK_CORRIDOR_ADDITIONAL_CONTROLS"]',
 'Confirm the recipient bank is a licensed institution recognized in the destination country and cap release at the corridor limit.');

COMMIT;
