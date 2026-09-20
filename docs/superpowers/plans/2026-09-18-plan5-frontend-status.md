# Plan 5 implementation status

## September 20 UX update — supersedes the original phone-frame / tab layout

The user's latest direction replaces the original centered 480px desktop frame and five navigation tabs:

- Desktop customer pages now use the entire available width. Mobile pages remain responsive, without a fixed-width frame on tablets.
- Per the user's final clarification, retain the main logo header with a profile-name dropdown for account routes and logout. Customer bottom tabs are visible only below 768px. There is no sidebar or hamburger. Dashboard content cards also expose wallets, Send, history, recipients, profile, verification, and support; other customer pages have a contextual Back to home link.
- Dashboard recipient shortcuts launch the Send flow with that person selected. Parameterized `send/{recipient}` URLs preserve the selection through the actual JET URL adapter; `send/new` opens the inline recipient form.
- Send step 1 includes the existing recipient dropdown, searchable people, and an inline Add recipient dialog. Saving adds and selects the recipient without losing wallet/amount input or navigating away.
- Step 2 compares delivery options. Step 3 is a full review with both **Send money** and **Save draft**. Send confirms and submits the payout using the existing APIs, then shows the result and timeline in the same flow. No separate Tracking confirmation is needed.
- A saved draft does not confirm, reserve, or send money. The backend status is not falsified as PROCESSING. Live progress uses plain-language copy. Compliance holds/rejections remain enforced, with approval followed by a Send action on the same page.
- Duplicate clicks, expired quotes, and uncertain payout responses cannot automatically dispatch another payout. Advanced recovery for a failed payout remains available through transfer details.
- Dashboard Recent activity and the complete Activity page now share a payment-plus-ledger feed. They include funding credits, both wallet legs of exchanges, wallet-to-wallet records when supplied by the backend, and recipient payments. Money-in/out direction and source wallet currency are shown. Type/date/status filters remain available, and a failed ledger source produces a warning instead of silently hiding the gap.
- Backend code and API contracts are unchanged. The external dependencies listed below still apply; unavailable features are not simulated in production.

Automated checks for this update cover the profile header/mobile-only tabs, contextual access, unified transaction feed and pagination, preselected recipients, step-3 draft saving, confirm-plus-payout, compliance gating, and duplicate/uncertain-request protection.

Final September 20 verification: TypeScript passed, all 65 tests passed, and the full JET build passed (one generated-theme copy race required a retry). Browser QA at 1440px and 360px confirmed the profile menu, full-width desktop, mobile-only bottom tabs, no horizontal overflow, recipient preselection, inline recipient creation, amount retention, step-3 Send/Save draft, and an inline completion receipt. A synthetic wallet top-up appeared immediately in Activity alongside wallet-to-wallet, exchange, and recipient-payment records. No browser console errors were recorded in these flows. Tests used the isolated in-memory fixture, not real account mutations or live money movement. Temporary QA tabs/server and generated preview HTML were cleaned up.

The sections below describe the original September 18 implementation and verification, before this UX update.

Implemented on 2026-09-18 in the existing FluxPay blue/white theme. No backend files, database migrations, existing API URLs, or Plan 1/4 API anchor blocks were changed.

## Implemented frontend work

- Customer shell: centered 480px maximum width, five bottom tabs (Home, Wallets, Send, Activity, More), safe-area spacing, keyboard focus indicators, and reduced-motion support. Public marketing/auth pages retain their layouts; Administration remains full-width with no customer sidebar or bottom navigation.
- Activity: transfer list and tracking are connected through `?ojr=activity/<payment-id>` (URL-encoded by Oracle JET). Old tracking navigation and matrix-parameter bookmarks are supported. IDs are shortened for display, with the complete reference available in the title/accessibility label and detail view.
- Session expiration: authenticated API 401 clears token and user, notifies session subscribers, and navigates home. A 403 preserves the session. Failed login does not trigger the authenticated-session expiration handler.
- Send: Pay/compare quotes, Save draft without requesting quotes or moving funds, Back to details, unchanged-draft reuse, reactive five-minute countdown capped by server expiry, expiry confirmation guard, and a success screen without automatic navigation.
- Recipients: searchable native bank/country suggestions, free-text bank entry, removed customer status selector/status writes, delete confirmation, keyboard focus trapping/restoration, and failure-safe deletion UI.
- Wallets: Add money entry point from Home and Wallets, clearly labeled existing development-funding API, immediate wallet/ledger refresh, and an on-hold list including PROCESSING and UNDER_REVIEW.
- Activity filters: search, status (including combined On hold), type and inclusive local date range. Combines payment pages with wallet ledger pages. Supports Plan 3 `entry_type` as well as current `entryType` and legacy `wallet:demo:` / `wallet:fx:` references. Pagination is applied after filtering. Fetching is capped at 2,000 records per source with a visible limitation notice.
- KYC: status explanation, rejection note and corrected-document form; pending/verified applications cannot be resubmitted. Initial submission remains available. Existing metadata-only document submission is clearly identified.
- More: recipients, verification, support tickets, Activity and account settings remain reachable.
- Plan 1/4 integration points: `tickets` route with a host that loads the owner-provided `tickets` fragment, plus `#helper-host`. Missing support fragment renders an explicit unavailable screen rather than leaving the page blank. Fragment internals are untouched.
- Build reliability: Windows-only, bounded retries for transient EBUSY errors inside generated Redwood/stable theme output, skipping byte-identical theme files. Source files and unrelated destinations retain normal copy/error behavior; the original copier is restored after the build. This addresses the repeated theme-copy locks observed during verification without editing dependencies.

## External dependencies still blocking full end-to-end completion

| Requirement | Evidence in this checkout | Current behavior / next step |
| --- | --- | --- |
| Others payment purpose | Backend `PaymentPurpose` contains only FAMILY_SUPPORT, EDUCATION, BUSINESS and SAVINGS. | Others is visible but disabled. Agree on/add the backend enum value, then enable the matching option. No alternate purpose is silently sent. |
| Recipient deletion | `RecipientController` exposes POST/GET/PUT but no DELETE. | UI is wired to the plan's DELETE contract; a 405 explains that deletion is unavailable and leaves the recipient intact. Backend owner must add the endpoint. |
| Bank top-up, P2P, withdrawal | Plan 3 endpoints and request DTOs are absent. Link/top-up/withdraw payload schemas are not fully documented in Plan 3. | Existing demo funding works. Do not invent bank payloads or mislabel demo funding as a real bank connection. Pull the Plan 3 implementation/DTOs, then integrate and verify. |
| Support tickets | Plan 1 `viewModels/tickets.ts` and `views/tickets.html` are absent. | Route host is ready; pull/rebuild the owner-provided fragment and backend. |
| User helper | Plan 4 helper drawer files and helper API are absent. | Host element is ready; drawer owner must supply/mount its component. |
| Referenced detailed specs / execution skill | Both specs linked by Plan 5 and the Superpowers execution skill are unavailable. | Work follows the supplied Plan 5 document and current source contracts. |

These dependencies are not completed or mocked in production. There is no claim of full live backend verification. The backend was not running during this implementation turn.

## Verification

From `frontend/fluxpay-ui`:

```text
npm run typecheck
npm test
npm run build
```

The tests cover session 401/403 behavior, all five tabs, admin isolation, draft saving/reuse, countdown expiry, duplicate-submit prevention, status-free recipient requests, failed deletion, KYC gates, multi-page Activity type/date/status filters, wallet funding/ledger refresh, and initial authenticated Activity loading. Existing compliance, auth, navigation and homepage tests remain included.

Final verification: TypeScript passed, 53 tests passed on Windows, and the complete `npm run build` passed. The theme-copy guard is tested for identical-file scope, changed-file handling, transient retry and restoration of the original copier.

Browser QA uses actual compiled JET router, shell, page controllers, templates and API client with an isolated in-memory HTTP fixture. It does not authenticate against or mutate the real backend:

```text
node tests/build-experience-preview.cjs
python -m http.server 8015 --bind 127.0.0.1 --directory web-dev
```

Open `/experience-preview.html?ojr=dashboard`. `qa_kyc=REJECTED|PENDING|VERIFIED` and `qa_role=admin` select synthetic QA states. The older `build-design-preview.cjs` delegates to this fixture. Generated preview files are ignored build output and are not part of the production source.

Verified at 360px and 1280px: no page-wide horizontal overflow; desktop customer frame is 480px. Browser checks covered draft-save, Activity detail navigation, top-up filter, wallet Add money and ledger refresh, recipient confirmation/Escape/focus restoration, rejected upload and verified upload suppression, and quote confirmation staying on its success page.

Also verified the support-unavailable screen, full-width Administration with no customer navigation, and migration of old `tracking;payment=...` bookmarks into Activity detail. The temporary QA server and browser tabs were closed; generated fixture HTML was removed after testing.

Changes are local and have not been committed, pushed, or merged as part of this implementation request. The unrelated untracked `scripts/seed-demo.py` is unchanged.

## September 20 — transaction receipts and wallet presentation

- Activity now uses date-grouped, clickable transaction cards for payments, funding, exchanges and wallet transfers. Dashboard activity opens the same receipt.
- Customer receipts contain the amount, status, transaction reference, wallet currency, masked recipient account, selected transfer fees/rate/recipient amount, and chronologically ordered event timestamps (including seconds and local time zone). Raw API JSON, event metadata, internal wallet/quote IDs and full account numbers are not displayed.
- Timelines use actual returned events. Wallet receipts show recorded ledger movements and linked exchange entries; unavailable processing steps are not invented. Receipt refreshes handle partial failures, stale responses, Escape, focus restoration and reduced motion.
- Wallet cards show “On hold · processing” only when that wallet has PROCESSING payments, summing their source amounts in the same currency. UNDER_REVIEW, drafts and terminal statuses are excluded. This is a presentation total, not a replacement for backend accounting or the balance API. Payment pages are loaded beyond the first 20 (up to 2,000, with a warning at the limit); active wallet holds refresh every 10 seconds while visible.
- Add-money labels, success messages and funding ledger descriptions no longer use the word “demo.” The existing funding endpoint is unchanged, and a short test-environment notice makes clear that no real bank account is charged.
- Latest verification: TypeScript passed, all 72 tests passed, and `npm run build` passed. Isolated browser QA verified customer receipts at 1440px and 360px, no horizontal overflow, masked accounts, no raw records, money-added filtering/receipts, per-wallet hold visibility/amount, and funding confirmation/ledger refresh. The fixture makes no real financial requests; live backend settlement was not exercised.
