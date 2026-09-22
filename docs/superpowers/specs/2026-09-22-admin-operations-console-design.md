# FluxPay Admin Operations Console Design

**Date:** 2026-09-22
**Status:** Approved
**Scope:** Frontend only

## 1. Purpose

Redesign the existing FluxPay administrator workspace as a risk-prioritized operations console rather than a generic analytics dashboard. The redesign must preserve the current FluxPay theme, increase operational density, favor labeled dropdowns for selection and filtering, and remain truthful to the backend APIs that already exist.

The console must help an administrator answer three questions quickly:

1. What needs attention now?
2. What is the next valid action for the selected record?
3. What evidence does the current backend provide for that action?

## 2. Scope boundary

Only files under `frontend/fluxpay-ui` may change during implementation, apart from documentation and local development metadata.

The implementation must not change:

- Backend Java code, tests, configuration, or database migrations.
- API routes, request bodies, or response bodies.
- Authentication, JWT handling, session restoration, or the existing `ADMIN` role.
- Backend authorization or ownership rules.
- Routing, policy, compliance, KYC, or support domain behavior.
- The meaning of any backend status or version field.

The final verification must show an empty `git diff -- backend`.

## 3. Explicit non-goals

The frontend must not simulate or claim backend guarantees that do not exist. This redesign therefore does not implement:

- Step-up authentication.
- Capability-based administrator roles.
- Dual approval or maker-checker enforcement.
- Persisted `Draft -> Review -> Active` workflows.
- A global or immutable audit log.
- Server-recorded sensitive-field reveal or copy events.
- Policy version, owner, effective-date, publication-status, or index-job metadata.
- An authoritative route simulation or smart-routing recommendation.
- KYC OCR extraction or automated field-mismatch detection.
- Support SLA deadlines or message-thread data.
- Permanent-delete-to-archive conversion in the backend.

Unsupported features must be omitted rather than represented by fake, session-only, or decorative data. The Audit Log navigation item and Governance group are omitted entirely.

## 4. Approved design principles

- Desktop-first, information-dense operations UI.
- Persistent labeled left sidebar, approximately 240 pixels wide.
- Risk and urgency before aggregate analytics.
- Dense semantic tables for queues and configuration records.
- Dropdowns for filters, saved views, record modes, and decisions wherever practical.
- One visually dominant primary action per screen or decision bar.
- Destructive and uncommon actions do not appear as prominent controls.
- No exposed provider, route, policy, or compliance-case deletion control.
- Red only for critical/error states, amber for warnings, green for confirmed success, and blue for informational or selected states.
- Every status includes text or an icon as well as color.
- No gradients, decorative charts, excessive rounded cards, or large empty areas.
- Existing FluxPay typography, tokens, controls, focus styles, and status conventions remain the visual foundation.

## 5. Information architecture

The administrator sidebar is expanded by default and contains:

- **Overview**
- **Operations**
  - KYC Reviews
  - Compliance Cases
  - Support Tickets
- **Money Movement**
  - Providers
  - Payout Routes
- **Policy & AI**
  - Policy Library
  - Compliance Copilot

The route map is:

| Route | Screen |
| --- | --- |
| `admin` | Overview |
| `admin-kyc` | KYC Reviews |
| `admin-compliance` | Compliance Cases |
| `admin-tickets` | Support Tickets |
| `admin-providers` | Providers |
| `admin-routes` | Payout Routes |
| `admin-policies` | Policy Library |
| `admin-copilot` | Compliance Copilot |

`admin` remains the administrator landing route and account path so existing bookmarks and redirects remain valid. Existing customer routes and bottom navigation remain unchanged.

## 6. Persistent shell

The current root shell owns the administrator sidebar so it remains stable while Oracle JET modules change in the main content region.

The shell contains:

- FluxPay wordmark.
- Permanently visible environment label.
- Grouped administrator navigation with icons and visible text.
- Active-route state and available attention counts where already loaded.
- Administrator profile menu using the existing session.
- A skip link and keyboard-visible focus states.

The shell must not render customer navigation for an administrator. It must not change the customer shell.

### 6.1 Environment label

`window.FLUXPAY_ENVIRONMENT` accepts these case-insensitive values:

- `PRODUCTION`
- `STAGING`
- `SANDBOX`

Display labels are `Production`, `Staging`, and `Sandbox`. An unknown or missing value falls back to hostname inference:

- `localhost` or `127.0.0.1` -> Sandbox.
- A hostname containing `staging`, `stage`, or `uat` -> Staging.
- Any other hostname -> Production.

The environment value controls only the visible context label and confirmation wording. It never selects an API target, changes permissions, or toggles features.

The existing network behavior remains authoritative:

- `window.FLUXPAY_API_URL`, when set, supplies the API base URL.
- When it is unset, the frontend uses same-origin `/api/...` requests.
- During local `ojet serve`, `before_serve.js` proxies `/api/...` to `API_PROXY`, defaulting to `http://127.0.0.1:8080`.

## 7. Reusable page patterns

### 7.1 Review workspace

Used by KYC, compliance, and support:

- Left: filterable, prioritized record queue.
- Center: selected record and domain details.
- Right: evidence and available history/context.
- Bottom: sticky action or decision bar.

The columns may stack at narrower widths, but the desktop layout must keep the queue, record, and inspector visible together.

### 7.2 Configuration workspace

Used by providers, routes, and policies:

- Searchable dense table.
- Selected-record editor.
- Client-side before/after comparison for edits.
- Explicit confirmation before the existing create or update API is called.
- Advanced details behind a dropdown or secondary view.

The comparison is a frontend confirmation aid. It is not a persisted version or approval workflow.

### 7.3 Copilot workspace

- Optional payment or compliance-case context on the left.
- Question and cited answer in the center.
- Persistent source-policy panel on the right.
- A visible advisory-only boundary.

The workspace uses the cited, non-streaming Copilot response as its normal answer flow because the current streaming response does not include sources.

## 8. Overview

The Overview contains clickable attention metrics and one prioritized table. It has no decorative charts.

It loads existing APIs independently with `Promise.allSettled`:

- `GET /api/admin/kyc/applications?status=PENDING&page=0&size=100`
- `GET /api/compliance/cases?status=OPEN`
- `GET /api/admin/tickets?status=ALL&page=0&size=100`
- `GET /api/admin/providers`
- `GET /api/admin/routes`
- `GET /api/policies`

The resulting attention measures are:

- Open high-risk compliance cases.
- Pending KYC reviews and reviews waiting over 24 hours.
- Archived or inactive routes and active routes performing below their configured reliability when outcome data exists.
- Open or in-progress tickets and tickets waiting over 24 hours.
- Policies that have no non-manual indexed chunks.

Every metric navigates to the corresponding route with filter parameters. If the KYC query returns the full 100-row page, the display is `100+`, not an inaccurate exact total.

One failed data source does not blank the page. Its section shows a compact labeled retry state while the other sources remain usable.

## 9. Queue priority and saved views

Priority uses only fields present in current responses.

### 9.1 Compliance

Sort by:

1. `risk`: HIGH, MEDIUM, LOW.
2. `reviewExpiresAt`, with the nearest non-null deadline first.
3. `createdAt`, oldest first.

Saved views include High risk, Requote required, Review expiring, Open, and Completed.

### 9.2 KYC

The API supplies no risk score or OCR comparison. Sort pending work by:

1. Original documents unavailable.
2. `submittedAt`, oldest first.

Saved views include Pending, Waiting over 24 hours, Documents unavailable, Verified, and Rejected.

### 9.3 Support

The API supplies no SLA deadline or message history. Sort by:

1. OPEN before IN_PROGRESS before RESOLVED before CLOSED.
2. Unassigned before assigned.
3. `createdAt`, oldest first.

Saved views include Open, Unassigned, Assigned to me, Waiting over 24 hours, Resolved, and Closed. The UI uses the term `age`, never `SLA`, for derived time warnings.

## 10. KYC Reviews

The KYC review workspace uses `KycAdminRow` data:

- Application ID and optimistic-lock version.
- Email and full name.
- Document type and document number.
- Status and submission/decision times.
- Rejection reason.
- Uploaded document metadata and availability.

The center compares the submitted fields with a read-only original document preview. Because the backend exposes no OCR extraction, the UI does not assert match or mismatch results. It provides a clear manual-review checklist instead.

Document numbers are masked in queue and summary surfaces. The full submitted value is not given a separate reveal/copy control because the backend cannot audit that action. Existing protected document preview behavior remains available.

The sticky decision bar uses:

- Decision dropdown: approve or reject/request correction.
- Standard frontend reason-code dropdown.
- Optional notes, subject to the existing 500-character backend limit.
- Required document-review consent before approval.
- Existing `expectedVersion` on approval and rejection.

Reason code and notes are combined into the existing `reason` string. No request shape changes.

## 11. Compliance Cases

The compliance workspace displays only current `ComplianceCaseResponse` fields:

- Case and payment IDs.
- Review reference and expiry.
- Requote-required flag.
- Risk, status, risk reasons, and suggested action.
- Decision author, time, and reason when completed.
- Creation time.

The frontend does not call the customer-owned payment-detail API for an administrator and does not invent payment, customer, country, currency, or amount context.

Open cases expose the existing approve and reject actions. The decision bar requires a standardized frontend reason code plus optional notes, combined into the existing `decisionReason` string.

The delete-manual-case action is not exposed. The existing case-to-Copilot handoff remains and passes only the case fields already available plus the payment ID accepted by the Copilot API.

## 12. Support Tickets

The support workspace displays:

- Ticket ID, user ID, and optional payment ID.
- Subject and original body.
- Status and assignee administrator ID.
- Created and updated times.
- Age derived from `createdAt`.
- Next valid status actions supported by the current backend.

The ticket body is labeled `Customer statement`, not `Latest message`, because the admin response has no conversation thread.

The action bar supports assignment to the current administrator and existing status transitions. Closed-ticket restrictions remain enforced by the current service and are represented in the UI.

## 13. Providers and Payout Routes

The provider and route pages reuse `RoutingWorkspace` validation and stale-version recovery.

### 13.1 Providers

The table includes code, name, rail, active state, protection state, archive state, version, and route count. Create and edit use the existing POST and PUT endpoints.

There is no removal control. Administrators may deactivate eligible providers through the existing edit path. Backend errors such as active child routes remain authoritative.

### 13.2 Routes

The table includes provider, route code/name, destination type, country, currency, fees, spread, ETA, configured/effective reliability, outcome counts, limits, active/archive/protection state, and version.

Before an update, the UI compares the loaded DTO with the form payload and shows changed fields plus the directly affected corridor. It must not claim affected-transaction counts.

There is no removal control. Administrators may deactivate eligible routes through the existing edit path.

### 13.3 Corridor matrix and provider comparison

The corridor matrix is derived from the loaded route list, grouped by destination country and payout currency. Each cell includes text for availability and the count of active routes; color is secondary.

Provider comparison presents existing commercial and reliability fields on one common table. It does not recommend a provider.

### 13.4 Eligibility Preview

The frontend-only Eligibility Preview accepts:

- Destination country.
- Payout currency.
- Amount.
- Destination/payout type.

It evaluates only the loaded configuration:

- Route and provider are active and not archived.
- Country, currency, and destination type match.
- Rail descriptor supports the destination type.
- Amount fits configured minimum and maximum recipient limits.

It displays every route with eligible/ineligible text and explicit rejection reasons. It shows base fee, spread, ETA, and effective reliability as separate configuration values. It does not calculate recipient proceeds, create a transaction, call smart routing, or identify a winner.

## 14. Policy Library

The policy page exposes only existing fields and operations:

- Title, category, content, document hash, and creation time.
- Indexed and manual chunks with chunk numbers and creation times.
- Policy guidance linked to completed compliance cases.
- Create, edit, import, index, and advanced chunk/guidance maintenance.

The main table displays `Current document`, not a fabricated version. Index information is limited to the count of non-manual indexed chunks currently returned by the API. It does not display index health, failure history, owner, effective date, or publication state.

Raw chunks and guidance remain in an Advanced view. Policy deletion is not exposed. Existing manual chunk and guidance maintenance may remain in the Advanced view with confirmation because those are subordinate records, not deletion of the policy itself.

## 15. Compliance Copilot

The default Copilot request uses `POST /api/copilot/ask`, which returns an answer plus sources. Every displayed answer keeps its source panel visible.

Each source displays exactly:

- Policy document ID.
- Policy title.
- Chunk number.
- Excerpt.

No policy version is shown because the backend does not return one. Source links open the current policy document.

The workspace visibly states that Copilot is advisory and cannot approve, reject, update, publish, or activate records. The answer renderer continues to treat model and policy content as untrusted text, allowing only the existing safe bold formatting behavior.

## 16. Frontend component structure

The implementation uses focused route view models while retaining existing service behavior:

- `appController.ts`: route registration, grouped administrator navigation, active-route state, and environment label.
- `index.html`: persistent administrator sidebar host and environment indicator.
- `admin.ts` / `admin.html`: Overview entry route.
- New focused view/view-model pairs for KYC, compliance, providers, routes, policies, and Copilot.
- Existing `admin-tickets` route adapted to the shared review layout.
- `RoutingWorkspace`: retained as the provider/route mutation state owner.
- `ComplianceWorkspace`: retained as the compliance/policy/Copilot state owner, with thin page-specific wrappers or modes.
- `flux-api.ts`: existing API contracts retained; only optional pagination arguments may be generalized.
- Small pure helpers for environment detection, queue priority, saved-filter application, DTO diffs, reason composition, and route eligibility preview.
- A new admin-console stylesheet that consumes existing FluxPay tokens and does not replace the global theme.

No new npm dependency is required.

## 17. URL and filter state

Overview metrics and sidebar links use the existing `navigate(path, params)` mechanism. Queue routes read parameters as initial filter state. Filters remain shareable through the Oracle JET router rather than living only in component memory.

The UI provides predefined saved views. It does not claim server-persisted or user-created saved filters.

## 18. Mutation and stale-data flow

For direct provider, route, and policy edits:

1. Keep the server DTO as an immutable baseline.
2. Edit a separate observable form model.
3. Normalize and validate using current frontend rules.
4. Compute a client-side changed-field list.
5. Show a confirmation summary.
6. Call the existing API with the current optimistic-lock version where supported.
7. Refresh the affected list and selected record from the server.

If a stale-version conflict occurs, refresh the list but preserve entered form values. The UI explains that another administrator changed or removed the record. The backend remains the authority for all compatibility and lifecycle constraints.

## 19. Error, loading, and empty states

- `401`: retain current token clearing, expiry event, and navigation to the public flow.
- `403`: display the administrator-access gate.
- `409`: display stale-data recovery while retaining form input.
- Network and `5xx`: preserve the current selection and form input, show a retry action, and avoid success language.
- Overview partial failure: show a source-specific unavailable state without hiding successful sources.
- Empty queues: explain which active filters produced the empty result and offer a filter reset.
- Policy/Copilot timeout: retain current longer proxy timeout and avoid claiming a completed index or answer.
- Late responses after logout, navigation, disposal, or a newer request are ignored using the existing epoch/disposal pattern.

## 20. Accessibility and keyboard behavior

- Sidebar uses labeled links with `aria-current`.
- Queues and tables remain semantic; interactive rows are keyboard operable.
- Record selection moves focus to the record heading only when initiated by keyboard navigation.
- Dialogs trap and restore focus through existing dialog bindings.
- Sticky action bars remain reachable in source order.
- Dynamic load, error, and success messages use appropriate `aria-live`, `role=status`, or `role=alert` semantics.
- Status meaning never depends on color alone.
- Focus indicators and contrast meet the existing accessible FluxPay conventions.
- Dense desktop layouts reflow without clipped controls on narrower screens.

## 21. Testing strategy

### 21.1 Pure logic tests

- Environment parsing and hostname fallback.
- Compliance, KYC, and support prioritization.
- Saved-filter predicates.
- Before/after DTO diff generation.
- Reason-code and note composition.
- Route eligibility and rejection reasons.
- Overview derived metrics and `100+` KYC behavior.

### 21.2 Navigation and shell tests

- Administrator grouped sidebar and all deep links.
- `admin` remains the administrator landing route.
- Customer routes, bottom navigation, and account path remain unchanged.
- Audit Log and Governance navigation are absent.
- Environment is permanently visible on administrator routes.

### 21.3 Workspace tests

- KYC document availability, consent, approval, rejection, and stale version.
- Compliance filters, ordering, reason composition, decision handling, and case-to-Copilot handoff.
- Ticket assignment, pagination, status transitions, and closed-state restrictions.
- Provider and route validation, direct update, diff confirmation, and stale-data preservation.
- Corridor matrix and Eligibility Preview.
- Policy creation/editing/import/indexing/chunks/guidance with policy deletion absent.
- Copilot answer citations, safe text rendering, optional payment ID, and provider errors.
- Destructive provider, route, policy, and case actions absent from templates.

### 21.4 Verification commands

From `frontend/fluxpay-ui`:

```powershell
npm.cmd test
npm.cmd run typecheck
npm.cmd run build
```

Also run:

```powershell
git diff -- backend
```

The backend diff must be empty.

### 21.5 Visual verification

Inspect the built administrator routes at desktop widths representative of the approved 240-pixel sidebar layout. Verify:

- No clipped columns, dropdowns, dialogs, or sticky actions.
- The environment label remains visible.
- Risk and warning hierarchy remains readable without color.
- Table density remains usable at common desktop widths.
- Keyboard focus follows the visible interaction order.

## 22. Acceptance criteria

The redesign is complete when:

1. Every approved administrator route is reachable from the persistent grouped sidebar.
2. The Overview contains only clickable, API-derived attention information.
3. KYC, compliance, and support use the shared review-workspace layout without invented backend fields.
4. Providers, routes, and policies use the shared configuration layout with direct existing API mutations and client-side diffs.
5. Corridor matrix, provider comparison, and Eligibility Preview are explicitly configuration-derived and non-authoritative.
6. Copilot answers always show the citations returned by the current cited-answer endpoint.
7. Audit Log, unsupported approval/version workflows, and destructive resource controls are absent.
8. The environment label is permanently visible and does not affect API routing.
9. Existing customer behavior and authentication remain unchanged.
10. Frontend tests, type checking, and the Oracle JET build pass.
11. No backend file is modified.
