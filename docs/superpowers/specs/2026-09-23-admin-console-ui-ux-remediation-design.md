# FluxPay Admin Console UI/UX Remediation Design

**Date:** 2026-09-23
**Status:** Conversation-approved; written-spec review pending
**Scope:** Frontend only

## 1. Purpose

Remediate the administrator console so it feels like a deliberate financial-operations product rather than a collection of unstable cards and narrow tables.

The completed console must let an administrator:

1. Scan a stable queue without large empty regions or layout shifts.
2. Open a record by selecting any part of its row.
3. Inspect evidence and complete every record-specific action inside one modal workflow.
4. Read wide provider, route, and policy data without labels or actions wrapping vertically.
5. Move from a compliance case to a familiar cited-chat Copilot experience.

The current FluxPay color theme, authentication flow, session behavior, `ADMIN` role, API contracts, and backend behavior remain unchanged.

## 2. Relationship to the existing console design

This document amends the implemented console described by `docs/superpowers/specs/2026-09-22-admin-operations-console-design.md`.

The following earlier decisions remain authoritative:

- Frontend-only scope.
- Existing Oracle JET routing and Knockout view models.
- Existing administrator routes and grouped sidebar.
- Current backend APIs and response fields.
- No invented governance, audit, authorization, approval, OCR, SLA, policy-publication, or route-winner capabilities.
- No destructive provider, route, policy, or compliance-case controls added by this work.

This document supersedes the earlier three-column review workspace, inline configuration editor, and three-column Copilot presentation. Those layouts are replaced by stable list pages and modal workflows.

## 3. Scope boundaries

Implementation may change frontend source, frontend tests, and project documentation. It must not change:

- Backend Java, tests, configuration, database migrations, or API payloads.
- Login, JWT storage, token expiry handling, `session.restore`, or route guards.
- The existing `ADMIN` role or authorization rules.
- Customer-facing business behavior.
- The meaning of KYC, compliance, ticket, provider, route, or policy states.
- The FluxPay color palette.

No npm dependency or externally hosted font is required. The implementation must remain usable in the local demo when external network access is unavailable.

## 4. Diagnosed defects

### 4.1 Provider editor binding failure

The provider page places its editor under `visible:providerForm`. Knockout's `visible` binding hides the element but still binds its descendants. A descendant evaluates `$root.environment.label` even though the Oracle JET module root is null in this context. That exception aborts the remaining subtree, which exposes incomplete review markup and leaves an unlabeled confirmation button.

The remediation must:

- Render the editor conditionally only while it is open.
- Use the correct view-model binding context.
- Move provider editing and review into one modal state machine.
- Ensure review markup does not exist in the active DOM until review is requested.

### 4.2 Review workspace layout shift

KYC, compliance, and support currently change from a single queue column to a multi-column workspace after selection. The queue shrinks, the selected row moves, and previously stable content appears destroyed.

The remediation keeps the queue geometry unchanged and opens the selected record over it in a modal.

### 4.3 Narrow configuration tables

Provider, route, and policy tables share horizontal space with inline editors. Dense cells allow aggressive word breaking, so short actions such as `Edit` can render vertically.

The remediation makes lists full width, gives wide tables an explicit minimum width, provides horizontal scrolling, and keeps the action column visible.

### 4.4 Copilot is presented as a form

The existing three-column layout emphasizes an empty text area and empty source panel. It does not resemble the chat tools users already understand.

The remediation presents the current cited request and response as a compact chat conversation without claiming persistent chat history.

## 5. Chosen architecture

Use a stable queue plus unified workflow modal across review and configuration pages.

Alternative approaches were rejected:

- Retaining inline detail cards would preserve the layout shift and inconsistent action placement.
- Building a generic virtualized admin-grid framework would add unnecessary infrastructure and integration risk.

The chosen approach uses existing Knockout observables and API services. Shared CSS classes and the existing dialog binding provide consistent structure, focus management, and responsiveness. Individual view models retain domain-specific loading, validation, and mutation behavior.

## 6. Shared list and modal contract

### 6.1 Stable list page

Each page keeps its heading, filters, refresh controls, and list visible while a modal is open. Opening or closing a record must not change the list's dimensions, filter values, page, or scroll position.

Review queues use a semantic list whose record rows are native buttons styled as a compact data grid. This makes the whole row clickable and keyboard accessible without placing click behavior on a non-interactive table row.

Each queue row exposes the minimum information needed to choose a record. A trailing chevron may indicate that the row opens details, but a separate `Open` text action is not used.

When a filtered queue has no records, the empty state is centered horizontally and vertically in a bounded queue surface. It includes a short explanation and, where useful, a reset-filters action.

### 6.2 Workflow modal

The shared modal has:

- A maximum desktop width appropriate to the record, bounded by the viewport.
- A maximum height of approximately 90 dynamic viewport units.
- A sticky header with record identity, status, and a labeled close icon.
- A scrollable body for details and evidence.
- A sticky footer containing every record-specific mutation or decision action.
- One modal layer at a time; edit, review, and confirmation steps replace modal content rather than stacking dialogs.

Read-only record modals close immediately. Configuration modals retain entered values while validation or network errors are displayed. The modal cannot close while a mutation is actively being submitted.

After a successful mutation, the underlying list refreshes and the modal closes or changes to an explicit success state. After a failed mutation, the modal remains open and preserves all local input.

Deep-linked application, case, ticket, policy, and other supported identifiers open the same modal after the list or detail request completes.

### 6.3 Action hierarchy

Page-level controls may filter, refresh, paginate, or open a `New` modal. All record-specific details, edits, confirmations, and mutations live in the modal.

Secondary, familiar actions may use icon buttons with tooltips and accessible names. Consequential actions retain visible text, including:

- Approve
- Reject
- Apply changes
- Resolve
- Close ticket
- Publish or rebuild an index

Icons must come from the frontend's existing icon vocabulary. No new icon package is added.

## 7. Global visual system

### 7.1 Typography

Replace the current Arial-first stack with:

`"Segoe UI Variable Text", "Segoe UI", "Helvetica Neue", Arial, sans-serif`

The Oracle JET core font variable, document body, form controls, and remaining explicit Arial declarations use the same stack. Monospaced identifiers retain the existing monospace treatment.

Typography changes do not alter the color theme. Headings use restrained weight and size; body copy and table labels prioritize legibility and density.

### 7.2 Cards and spacing

Cards exist only when they group related information. Blank placeholder cards are not rendered. Loading regions show a labeled skeleton or progress state; unavailable regions show a compact error and retry action.

Border radius, shadows, borders, and color tokens continue using the established FluxPay theme. The redesign reduces oversized whitespace and avoids adding decorative gradients or charts.

## 8. Overview

Attention metrics appear in one compact horizontal row.

Each metric tile:

- Is approximately 76 to 84 pixels high.
- Centers the number visually.
- Uses a small, single-line label.
- Uses concise visible wording and a complete accessible name where abbreviation is necessary.
- Always renders its label, including while loading or unavailable.

On narrow screens, the metric row scrolls horizontally rather than wrapping into tall cards. The prioritized-work table remains below the metrics. Source failures render as compact labeled errors and never as anonymous empty cards.

## 9. KYC Reviews

The KYC queue is centered within the available page width and remains full size while a review is open. Rows show applicant identity, submission age, and status.

The KYC modal contains:

- Applicant name, email, application ID, and current status.
- Submitted document type and masked document number.
- Submission and decision timestamps.
- Rejection reason when present.
- Manual review checklist.
- Evidence/document cards and the existing protected preview behavior.
- Decision, reason code, notes, and manual-review confirmation for pending records.

Approve and Reject remain explicit text actions in the modal footer. Opening a document switches the KYC modal body into a focused preview mode rather than stacking a second dialog. Returning to the record restores focus to the document control that opened the preview.

## 10. Compliance Cases

The compliance queue follows the same stable list contract. Rows show payment reference, risk, age or review timing, and status.

The compliance modal contains:

- Case ID and status.
- Risk level, reasons, suggested action, and expiry data available from the API.
- Payment ID with a copy icon and accessible label.
- Review reference and decision history fields exposed by the current response.
- Existing decision reason controls and confirmation flow for open cases.
- A prominent `Ask Copilot` action.

Copy uses the browser clipboard API. Success is announced in the modal; failure leaves the visible identifier selectable and presents a recoverable error.

`Ask Copilot` navigates to `admin-copilot` with the current `caseId` and `paymentId`. It does not mutate the compliance case.

## 11. Support Tickets

The support page must not automatically select the first ticket after loading. It opens a ticket only from an explicit row selection or supported deep link.

Rows show subject, age, assignment state, and ticket status. The support modal contains:

- Subject and complete body.
- User and optional payment reference.
- Created and updated timestamps.
- Current assignee and status.
- Existing assign-to-me and valid next-status actions.

The UI must not invent a message thread, SLA, priority, or conversation history that the API does not provide.

## 12. Providers

The provider list occupies the full configuration surface. New and Edit open the same provider modal.

The modal has two states:

1. **Edit configuration:** provider code, name, rail, activation, validation, and target-environment context.
2. **Review changes:** the existing before/after diff, Back, Apply changes, and Close.

Back returns to the populated edit form. Apply uses the current create/update behavior and stale-version recovery. The target environment remains display-only and never changes API routing.

The provider review state and Apply label must not render before the user requests review. The prior invalid root binding must be covered by a regression test.

## 13. Payout Routes

The route catalogue is full width inside a horizontal scroll container. The request for an "infinitely scrollable" table is interpreted as unrestricted horizontal viewing of all existing columns, not endless API pagination or background fetching.

The catalogue must:

- Use an explicit minimum table width sufficient for readable columns.
- Preserve non-wrapping action labels.
- Keep the action column sticky at the right edge where supported.
- Use an Edit icon with tooltip and accessible name.
- Avoid `overflow-wrap:anywhere` on action and identifier cells.

Editing a route opens a modal with the current fields, system-protected messaging, validation, before/after review, and Apply action. Catalogue, matrix, comparison, and preview behavior remain truthful to the existing frontend analysis and APIs.

## 14. Policy Library

Policy and draft tables use the same horizontal-scroll and sticky-action treatment. View and Edit may use icon buttons with tooltips and accessible names.

Selecting a policy opens one modal workflow containing:

- Policy metadata and content.
- Indexed/manual chunks exposed by the current response.
- Existing guidance and completed-case context.
- Current edit, review, indexing, draft, and guidance actions.

Actions that currently open additional dialogs instead replace the active policy modal's mode. Only one modal layer is active at a time. The design does not add publication metadata, ownership, version history, or destructive controls unsupported by the approved console scope.

## 15. Compliance Copilot

The Copilot becomes a familiar single-conversation chat surface.

### 15.1 Layout

- A compact header identifies Compliance Copilot and retains the advisory-only boundary.
- A context control offers `No case context` plus cases loaded from the existing compliance-case API.
- A deep link from Compliance preselects and loads its case.
- An optional disclosure permits manual payment-ID attachment when the desired payment is not represented by a loaded case.
- The conversation canvas shows an empty welcome state, the submitted user question, and the assistant answer.
- Cited source cards appear beneath the assistant response or in an expandable sources region.
- A compact sticky composer contains the question field and a clearly labeled send action.

### 15.2 Behavior boundaries

Selecting a case populates its payment ID and visible context. Asking a question continues to use the existing cited, non-streaming endpoint so sources remain available.

The frontend displays only the current exchange. It must not imply that the backend stores chat history, remembers prior turns, or streams citations. A new question replaces or clears the current exchange according to explicit UI state.

## 16. State and data flow

### 16.1 Review pages

1. Load the existing queue through the current service.
2. Preserve active filters and pagination in page-level state.
3. On row activation, set the selected identifier and open the modal.
4. Fetch detailed data when the current API requires it, showing a labeled loading state inside the modal.
5. Run decisions through existing validation and mutation methods.
6. On success, refresh the queue and close or update the modal.
7. On failure, retain the selected record and all entered values.

### 16.2 Configuration pages

1. Load the existing provider, route, or policy list.
2. Open a conditionally rendered modal with a snapshot of the selected record.
3. Validate local input.
4. Derive the existing before/after review.
5. Replace the edit step with the review step in the same modal.
6. Submit through the existing API and concurrency behavior.
7. Refresh the list after success; preserve form data after failure or stale conflicts.

### 16.3 Copilot

1. Restore the existing administrator session.
2. Load cases for the context selector without changing case state.
3. Resolve a deep-linked case when supplied.
4. Bind the selected case's payment ID to the existing Copilot request context.
5. Submit the current question to the cited-answer endpoint.
6. Render the returned answer and sources as one exchange.

## 17. Error and loading behavior

- Initial list failures show a labeled page error with Retry; they do not show empty white cards.
- Detail-fetch failures remain inside the open modal and provide Retry or Close.
- Validation errors appear adjacent to the modal form or decision controls.
- Mutation errors preserve local inputs and keep the modal open.
- Provider and route `409` stale-data recovery remains intact and must not overwrite operator edits silently.
- Existing `401` handling, token clearing, expiry event, and signed-out/access-required gates remain unchanged.
- Busy actions disable duplicate submission and expose a readable loading status.
- Overview source requests remain independent so one failure does not blank successful metrics.

## 18. Responsive behavior

At wide desktop sizes, review modal bodies may use two columns for record details and evidence. Below the appropriate breakpoint, they collapse to one column while the header and footer remain usable.

Wide data tables scroll horizontally within their own surfaces. They do not shrink every column until words render vertically. Sticky action cells use an opaque theme-matching background so scrolled data does not show through.

At mobile widths:

- The existing responsive administrator navigation remains usable.
- Modals occupy most of the viewport with safe outer spacing.
- Modal actions stack only when necessary and retain visible labels.
- Queue rows reflow into labeled record summaries without horizontal page overflow.
- The Copilot composer stays reachable without covering the response.

## 19. Accessibility

- Opening a modal moves focus to its heading or first meaningful control.
- Focus remains within the modal while it is open.
- Escape and the close control dismiss non-busy modal states.
- Closing returns focus to the row or button that opened the modal.
- Native buttons provide row keyboard activation with Enter and Space.
- Icon buttons have `aria-label` text and a visible tooltip on hover/focus.
- Loading and successful copy/save updates use polite live regions.
- Errors use alert semantics.
- Status always has readable text and is never communicated by color alone.
- Dialog headings are associated through `aria-labelledby`.
- Table/list headers and compact row labels remain understandable to assistive technology.

## 20. Likely frontend impact

Implementation is expected to touch:

- Global typography in `src/css/app.css` and any remaining explicit Arial declaration.
- Admin layout and modal/table styles in `src/css/admin-console.css` and `src/css/workspace.css`.
- Overview, KYC, compliance, tickets, providers, routes, policies, and Copilot templates.
- The corresponding view models and existing routing/compliance workspaces where modal state or explicit selection behavior belongs.
- Existing dialog/focus helpers only where needed to guarantee focus restoration.
- Frontend tests covering templates, view-model state, binding context, navigation, and accessibility.

The implementation plan will identify exact edits after the written spec is approved. Unrelated frontend code and all backend code remain out of scope.

## 21. Verification strategy

Implementation follows test-driven development. Regression tests must cover:

- The provider template does not evaluate the invalid module-root binding.
- Provider review content exists only in the requested modal step.
- KYC, compliance, and support rows are fully activatable.
- Selecting a record does not alter queue geometry or remove the queue.
- Record details and mutations occur in dialogs, not inline editor cards.
- Support does not auto-select the first ticket.
- Payment ID copy status and Compliance-to-Copilot navigation.
- Copilot case selection, deep-link context, current-exchange rendering, and cited sources.
- Route and policy scroll containers, minimum-width tables, sticky actions, and non-wrapping controls.
- Icon actions have accessible names.
- Loading, empty, error, and success states remain labeled.

Required automated verification from `frontend/fluxpay-ui`:

- `npm.cmd test`
- `npm.cmd run typecheck`
- `npm.cmd run build`

Required repository checks:

- `git diff --check`
- `git diff -- backend` produces no output.
- No unresolved merge markers exist in changed source files.

After allowing the development server 10 to 30 seconds to rebuild, smoke-test all administrator routes at desktop and narrow viewport widths. Exercise empty, populated, selected, loading, error, edit, review, and successful modal states where local data permits. If browser automation is unavailable, report that limitation explicitly and do not describe the UI as visually verified.

## 22. Acceptance criteria

The redesign is complete when:

1. The application uses the approved deliberate system-font stack without altering FluxPay colors.
2. Overview metrics form a compact horizontal row with centered numbers and one-line labels.
3. Overview never shows anonymous blank cards for loading or failed sources.
4. KYC, compliance, and support empty states are centered.
5. Any point on a review record row opens its modal with keyboard parity.
6. Opening a record does not resize, replace, or destroy the queue.
7. Every record-specific detail, decision, edit, review, and mutation action is inside its modal workflow.
8. Support does not open a ticket without explicit selection or a deep link.
9. Provider New/Edit and review use one modal and no hidden binding throws an exception.
10. Provider review text and Apply controls never leak into the page before review is requested.
11. Route and policy tables scroll horizontally while their action controls remain readable and non-vertical.
12. Compliance exposes payment-ID copy and a direct Copilot action inside its modal.
13. Copilot resembles a current-exchange cited chat interface and does not imply stored conversation history.
14. All icon-only controls have tooltips and accessible names; consequential actions retain visible text.
15. Frontend tests, type checking, production build, diff validation, and backend-boundary checks pass.

## 23. Explicit non-goals

This remediation does not add:

- A new backend endpoint or database change.
- New authentication, permissions, roles, or session behavior.
- Persistent Copilot conversation history.
- A synthetic support conversation thread or SLA.
- KYC OCR or automated document comparison.
- Infinite server-side route or policy pagination.
- New destructive controls.
- A new UI framework, data-grid package, icon package, or web-font dependency.
- A new color theme or brand redesign.
