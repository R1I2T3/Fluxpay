# Custom payment reasons and optional onboarding verification

- Selecting Others reveals a required text area, maximum 250 characters.
- Continue and Save for later both reject missing/whitespace-only reasons.
- The trimmed reason is saved on the payment, returned by detail/list APIs, and shown during review and in customer transaction details. Changing a reason changes the draft fingerprint; preset reasons do not retain stale custom text.
- Backend validation independently enforces the requirement. Existing preset request fingerprints remain compatible.
- V611 adds a nullable column only; applied successfully to the local schema without resetting data.
- Initial identity verification offers Skip for now, opening the dashboard without changing verification state.
- The customer workspace reminder uses the server-provided KYC status. It disappears after submission, not after approval; existing transfer verification gates remain intact.

Verification: 107 frontend tests passed, TypeScript check and Oracle JET build passed. 21 targeted backend tests passed (PaymentPurposeFlowTest, PaymentResponseMapperTest, PaymentOperationServiceTest, KycDocumentFlowTest). Browser QA used isolated synthetic data to confirm required reason validation, retained custom text, and Skip opening the dashboard with its reminder. No real customer payments or review decisions were changed.
