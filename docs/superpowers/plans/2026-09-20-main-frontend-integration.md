# Main integration — September 20, 2026

## Preserved local work

Local `main` was fast-forwarded from `1b32d8a` to `031e26b`. All tracked and untracked local work was saved and restored from stash `643dc68b826fe5caa29b03e2703c07557a6989c3` (`codex backup before main integration 2026-09-20`). The stash is retained as a recovery copy. The only merge conflict was the stylesheet list: incoming admin icons and local responsive/history styles were both retained. The user's unrelated `scripts/seed-demo.py` remains untouched.

## Frontend integration

- Bank linking collects only bank name, currency and last four account digits. A bank linked from Add money is selected when the funding dialog resumes.
- Bank top-up, withdrawal and wallet-to-wallet transfer use the actual new APIs with idempotency headers, validation, a review step, duplicate-submit protection, result receipts and balance/ledger refresh. Transfers support email or user ID and SOURCE/TARGET amount modes. Server-owned fees and FX rates are never fabricated.
- Added an approved, read-only `GET /api/bank-accounts` endpoint, backed by the existing owner-scoped repository query. Bank lists now survive navigation/login without browser-local account storage.
- Existing local funding remains available as “Test balance,” with an honest no-bank-charge notice.
- Activity classifies new top-ups, wallet transfers and bank withdrawals from their actual ledger references. Customer receipts retain only useful details, masked account data and recorded timestamps.
- Incoming policy editing/import, manual-chunk maintenance, case-linked guidance and review-hold controls remain integrated in Administration.
- Added optional Copilot streaming with fragmented SSE/UTF-8 handling, cancellation, interrupted-response errors, and session/disposal guards. Cited answers remain the default; streamed responses explicitly offer a separate cited-answer request because the stream API supplies no citations.
- Fixed the incoming streaming endpoint's asynchronous authentication: the JWT filter now authenticates asynchronous dispatches again, without allowing anonymous dispatches or weakening the admin role requirement.

## Local backend upgrade

- A verified logical export of 27 tables / 290 rows was saved in ignored `logs/schema-before-main-20260920-190224.json.gz`. This contains sensitive application data and must not be committed or uploaded. It contains table DDL and data, not an RMAN backup.
- Seven pending migrations (V007, V008, V606–V610) applied successfully using a one-time out-of-order startup. Subsequent startup uses normal ordering. Existing users (10) and ledger entries (64) were preserved. The incoming V607 migration adds its own policy/review fixtures.
- Only the verified previous FluxPay backend process was stopped. Restart initially hit Windows Java's temporary socket-path problem. A process-local `jdk.net.unixdomain.tmpdir` pointing to `logs/java-tmp` allowed normal startup; no security or system-wide network setting was changed.
- Provisioned only the three missing zero-balance `FX_GAIN_LOSS` wallets for the configured system user. Existing balances, identities and roles were not changed. Ledger reconciliation: grouped journals balanced, zero ungrouped entries.
- The running backend exposes the new bank-list and wallet-transfer contracts. Authenticated bank-list and wallet reads were verified using the local configured test identity; no live customer financial operations were performed.

## Verification

- Frontend TypeScript, complete build and 99 tests passed, including wallet contracts, review/confirmation safety, ledger categories, SSE parsing and admin editor completion.
- Backend targeted tests: 30 passed (`BankAccountControllerTest`, `BankAccountListTest`, `WalletTransferTest`, `CopilotStreamSecurityTest`). These include owner scoping/authentication, bank operations and transfer accounting in isolated test storage, plus successful admin stream completion and customer/anonymous denial.
- Live administrator Copilot streaming returned HTTP 200 and the terminal done event both directly (26 delta frames) and through the frontend proxy (61 delta frames). Live anonymous/customer requests were denied with 401/403 respectively. The frontend is available on port 8000 and backend on port 8080.
- Browser fixture verifies actual compiled JET routing/views with in-memory APIs: link bank → selected funding bank → review → top-up; withdrawal review/completion; wallet-transfer review/completion; all three types appearing in history. Mobile review has no page-wide horizontal overflow; browser error log was empty.
- This report supersedes the earlier Plan 5 note that Plan 3 bank/P2P endpoints were absent. Support-ticket and helper fragments, recipient DELETE, and an “Others” payment-purpose enum remain separate older dependencies not supplied by these main commits.

Changes are local; no new commit, push, pull request or GitHub merge was performed for this request.
