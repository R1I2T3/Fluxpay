# Admin Statistics design

Date: 2026-09-25
Status: Written specification approved by the user on 2026-09-25.
Deliverable requested: implementation plan. Product implementation is a later task.

## Purpose and agreed scope

Give FluxPay administrators a clear view of business activity and operational
health in a dedicated Statistics page. The user approved:

- Frontend and backend work with database-backed totals.
- A new admin Statistics page.
- One selected currency for monetary statistics.
- Last 30 days by default, with Today, Last 7 days, Last 90 days, and custom dates.
- Payment drill-downs into a paginated list and the existing Payment Operations view.
- Send-money payments for the first version, accompanied by customer, KYC,
  compliance, support, and provider statistics.
- The metric set and refresh behavior described below.

Keep the existing admin colors and compact layout. Preserve authentication,
session restoration, expiry behavior, API-base selection, and the existing ADMIN
role. Add the Statistics navigation entry without redesigning Overview or the
customer dashboard. No commits, branch changes, or deployment are part of this
planning task.

## Approach

A browser-only approach could reuse existing queue APIs, but those APIs return
paged records rather than reliable overall totals. A separate analytics store
would add ingestion and synchronization work beyond this feature's needs.

Use read-only aggregates over the existing Oracle tables and a small reporting
API. Return a single dashboard response plus separate options and paginated
payment-record endpoints. This suits the current application and keeps the
financial reporting separate from payment execution.

## Current code evidence

- `frontend/fluxpay-ui/src/ts/services/admin-overview.ts` derives attention cards
  from loaded records. `viewModels/admin.ts` loads at most 100 KYC applications
  and 100 tickets. These arrays must not supply Statistics totals.
- `frontend/fluxpay-ui/src/ts/appController.ts` defines admin navigation and
  derives routes from its navigation groups.
- `viewModels/admin-payment-operations.ts` accepts `params.params.paymentId`.
- `backend/src/main/java/com/fluxpay/controller/AdminReportController.java`
  already protects reports with `hasRole('ADMIN')`.
- `ReportQueryService.java` still references `payout_routes` and
  `payout_attempts.payout_route_id`. The current schema instead has
  `transfer_providers`, `transfer_routes`, and `payout_attempts.transfer_route_id`.
  Its existing mock-only test does not execute the SQL.
- `Payment.java` and `PaymentStatus.java` define nine payment statuses. Payments
  have source amount/currency, creation time, and current status.
- `PayoutAttempt.java` records individual attempts and their statuses. Its
  `payment_id` is text, whereas `payments.id` is RAW(16).
- Registration assigns `users.role = 'USER'`; the local seed uses ADMIN and
  SYSTEM for noncustomer accounts. Currency codes and scales live in `currencies`.
- KYC uses `kyc_cases`; operational compliance uses `compliance_cases`, not the
  separate `screening_cases` table. Support uses `support_tickets`.

These are source findings, not results from a running database or browser.

## Filters and reporting semantics

### Dates

Use calendar dates with an explicitly displayed reporting timezone of
`Asia/Kolkata` (IST). This approved implementation default is based on the
current user's timezone; it is not a new authentication or account preference.

The API accepts inclusive `from` and `to` dates in YYYY-MM-DD format. The service
converts them to `[start of from, start of day after to)` instants. Last 30 days
means today plus the preceding 29 local calendar days; the other presets follow
the same rule. Use the existing injected Clock to resolve today and generatedAt.

Require from <= to, no future end date, and at most 366 inclusive calendar days.
The default and presets include today's partial data. Cap the effective query
end at generatedAt and return that effective bound in metadata. Fill missing
chart dates with zero values, including an empty current day.

Payment statistics describe payments CREATED in the selected period and their
CURRENT outcomes. They are not a historical snapshot of statuses at period end,
nor a chart of settlement dates. Show this explanation near the payment charts.
Historical figures can change when a payment is completed, retried, or refunded.

### Currency

The selector means source currency (`payments.currency`). Its label is
"Source currency". Populate options from the canonical `currencies` table,
including each currency's configured scale. Prefer INR on first load if it is
available; otherwise choose the first code in alphabetical order. Restore a
valid currency from route parameters when revisiting the page.

Filter all payment and provider reporting to that source currency. Do not add an
"All currencies" monetary total or perform exchange-rate conversion. Customer
statistics are currency-independent. Current workload is independent of both
the date range and currency, and must be labeled accordingly.

## Metric definitions

All payment metrics share one cohort: payment rows within the effective creation
time bounds and selected source currency. Do not join attempts into a payment
SUM or COUNT in a way that multiplies payment rows.

| Metric | Definition |
| --- | --- |
| Payment count | Count all payments in the cohort, including drafts and quotes; label the card "Payments created" and explain the inclusion. |
| Completed payment amount | Sum `payments.amount` only where current status is COMPLETED. This is source payment amount, not net recipient proceeds or revenue. |
| Payout success rate | `100 * COMPLETED / (COMPLETED + FAILED + REFUNDED)` using distinct payments in the cohort and their current states. Refunds remain unsuccessful payouts. |
| Failed payments | Current status FAILED; refunded payments have their own status and are not counted here. |
| Processing payments | Current status PROCESSING, including uncertain delivery awaiting reconciliation. |
| Daily payment count | Count cohort payments grouped by their creation date in the reporting timezone. |
| Daily completed amount | Completed source amount grouped by PAYMENT CREATION date, not completion date. |
| Status breakdown | One count for each of DRAFT, QUOTED, UNDER_REVIEW, PROCESSING, COMPLETED, FAILED, REFUNDED, REJECTED, CANCELLED. Include zero-valued statuses. |

The payout success denominator excludes drafts, quotes, reviews, processing,
rejections, and cancellations. A zero denominator returns null and displays
"No final outcomes", not 0% or 100%. Percentage values are rounded to two
decimal places on the server. A recovered payment counts once in its current
state; its earlier failed attempts remain visible in provider reporting.

### Provider performance

Group real payout attempts by provider ID/code/name, following
`payout_attempts.transfer_route_id -> transfer_routes.provider_id -> transfer_providers.id`.
Include attempts belonging to the selected payment cohort, initiated before
generatedAt, even when the attempt occurred after the selected creation period.
Label the table "Attempts for payments created in the selected period".

Show total, completed, failed, and in-progress attempts plus observed success
rate: completed / (completed + failed). INITIATED and PROCESSING count as
in-progress and do not enter the rate denominator. Use null for no terminal
attempts. Show providers with at least one matching attempt; include archived
and inactive providers when they have matching history. Sort by total attempts
descending, then provider code. Never present configured route reliability as an
observed success rate.

Match textual attempt payment IDs to canonical payment UUIDs safely. Do not call
HEXTORAW on arbitrary attempt IDs: standalone/non-UUID attempt IDs are allowed
by the existing schema. Compare normalized text with RAWTOHEX(payment.id), or
an equivalent safe canonical representation. Unlinked attempts do not belong to
the send-money payment cohort and are excluded from this page.

Provider cells represent attempts and are not payment-count drill-down buttons.
This avoids presenting a deduplicated payment list as if it matched attempt totals.

### Customers

Count users with role USER only. Exclude ADMIN and SYSTEM.

- Total customers: current customer accounts created before generatedAt.
- New registrations: customer accounts created within the selected date bounds.
- Registration trend: new registrations by local calendar date, with zero-filled days.

Total customers is an all-time count; registrations follow the selected dates.
Neither changes with currency selection. No login-based active-user metric is
claimed because the inspected data does not provide that history.

### Current workload

All figures below are current, across all dates and currencies, and use complete
database aggregates. "Over 24 hours" means timestamp strictly before
generatedAt minus 24 hours, not an invented business-hours SLA.

| Area | Counts |
| --- | --- |
| KYC | PENDING applications; pending applications aged from `submitted_at`. |
| Compliance | OPEN cases; OPEN HIGH-risk cases; OPEN cases aged from `created_at`. |
| Support | OPEN or IN_PROGRESS tickets; those same unresolved tickets aged from `created_at`. |

Workload cards are informational in v1. The approved record drill-down requirement
applies to payments. The existing Overview continues to provide queue navigation.

## Page behavior

Add `admin-statistics` under the Overview navigation group, beside Overview.
Use a compact toolbar with date preset/custom dates, source currency, Refresh,
and the time of the last successful response. Load on page entry and filter
changes. There is no polling timer or background refresh in this version.

Display payment cards first, then daily count/amount charts and the status
breakdown, provider performance, customer statistics, and current workload.
Use a small number of charts; provider performance and workload are compact
tables/cards. Keep count and amount scales in separate charts.

Use the existing Oracle JET/Knockout stack and existing styling tokens. SVG/CSS
charts are sufficient; a new chart dependency is not required. Provide readable
axis labels, keyboard-accessible controls, and text/table equivalents for chart
values. Meaning must not depend only on color.

### Payment drill-downs

Clicking Payments created opens all cohort payments. Clicking a status count
opens that status. Clicking completed amount opens COMPLETED payments. Daily
chart controls additionally restrict the list to the selected creation day.
The success-rate card has a formula explanation instead of an ambiguous drill-down.

Show an inline, paginated payment table on the Statistics page with payment ID,
created time, source amount/currency, status, and "Open operations". Do not fetch
customer documents, account references, or entire operation histories for a list.
Each operation link navigates to `admin-payment-operations` with `paymentId`.

Use 20 rows by default, server maximum 100, zero-based pages, and deterministic
`created_at DESC, id DESC` ordering. Store dates, currency, selected status/day,
whether the list is open, and page in route parameters. Browser Back restores
that state. Filter changes reset the list page. List and count queries use the
same cohort/status predicates; never load every payment into the browser.

## Backend contracts and boundaries

Extend the existing AdminReportController with these ADMIN-protected GET routes,
using the existing ApiResponse envelope and correlation ID conventions:

| Route | Inputs | Output |
| --- | --- | --- |
| `/api/admin/reports/statistics/options` | None | Supported currencies/scales, default currency, reportingZone, today, maximumRangeDays. |
| `/api/admin/reports/statistics` | Required from, to, currency | Metadata; payment summary, daily series, status counts; provider rows; customer counts/trend; current workload. |
| `/api/admin/reports/statistics/payments` | Required from, to, currency; optional status; page=0, size=20 | Effective filter metadata, generatedAt, items, totalElements, page, size, totalPages. |

A day drill-down sends that single day as from/to for the list endpoint while
retaining the dashboard's broader dates in page state. All status filters use
the existing PaymentStatus enum. A missing status means every status.

Use dedicated typed DTOs in `dto/AdminStatisticsResponse.java`,
`dto/AdminStatisticsOptionsResponse.java`, and
`dto/AdminStatisticsPaymentPageResponse.java`, with nested records where useful.
Use `service/AdminStatisticsService.java` for validation, date boundaries,
zero-filled series, and rates, and `repository/AdminStatisticsRepository.java`
for parameterized JdbcTemplate aggregates/projections. Keep pure UI filter/chart
transformations in `services/admin-statistics.ts` and orchestration in
`viewModels/admin-statistics.ts`. Add typed API wrappers through `flux-api.ts`
without changing its authentication or shared request behavior.

Amounts travel as decimal strings and are aggregated with Oracle NUMBER and
Java BigDecimal. Counts are integers. Format amounts using the selected currency
scale; do not calculate financial totals in floating-point browser code. Chart
coordinates may be numeric approximations, but labels/tooltips retain the
authoritative decimal amounts.

Return HTTP 400 with stable errors for invalid dates/range, unsupported currency,
invalid status, and invalid pagination. Suggested codes are INVALID_REPORT_RANGE,
INVALID_REPORT_CURRENCY, INVALID_REPORT_STATUS, and INVALID_REPORT_PAGE. Verify
these BusinessExceptions flow through the current advice/envelope convention.

All operations are read-only. Do not acquire payment-execution locks. Do not
introduce caches, materialized views, Kafka consumers, new financial events, or
new tables. Existing indexes are the baseline; a new migration is justified only
by evidence from query validation. Never edit an applied migration.

The summary represents current data read during one request, not an immutable
historical snapshot. Its queries and later drill-downs can observe transitions
between reads. generatedAt is the request's reporting cutoff, not a guarantee
that the entire database was frozen at that instant. Refresh updates outcomes.

### Existing provider report correction

Correct ReportQueryService's obsolete provider-summary SQL to use current
provider/route/attempt tables. Preserve its existing route, ISO-instant from/to
contract, ProviderRow fields, and grouping by provider name. Preserve zero-attempt
provider rows using left joins with attempt time predicates in the join.

The existing endpoint has different semantics from the new page: it counts
attempts INITIATED within its instant range, without a currency/payment-cohort
filter. Keep those contracts explicit; do not silently change old behavior to
the new dashboard's payment-creation cohort. Share only helpers that preserve
both definitions; no broad report-service refactor is required.

## Loading, errors, and session lifecycle

The options request establishes valid controls before loading the dashboard.
If it fails, show a retryable unavailable state. If no configured currencies
exist, show an explicit setup/empty state and disable monetary requests.

The dashboard is one response: on failure, show a retryable dashboard error,
not fabricated zeros. Zero is reserved for a successful empty aggregate. A
payment-list error is local to the list and does not erase the dashboard.

Ignore responses superseded by newer filters, navigation/disposal, or logout.
Clear protected data when the session ends and reuse existing session recovery
and expiry handling. During refresh, retain previous values only if visibly
marked as refreshing/stale; never associate old values with new filter labels.
Update "Last updated" only after a successful response. Announce loading,
errors, and result changes accessibly.

## Acceptance and verification

1. Aggregate counts exceed 100 correctly and agree with the full matching
   database cohort, including zero data and every payment status.
2. Retrying a payment does not multiply amount/count; a failed-then-completed
   payment contributes one completed payment and both provider attempts.
3. Refunded outcomes affect the success denominator as defined; in-flight and
   rejected/cancelled payments do not. Zero denominator yields no rate.
4. Source currency filters are consistent across cards, charts, provider rows,
   and payment lists. Customer/workload independence is visibly explained.
5. India-midnight boundaries, partial today, inclusive custom end dates, empty
   days, maximum range, reversed ranges, and invalid parameters are covered.
6. Oracle verification executes the real queries against the current schema,
   including RAW/text UUID joins, non-UUID attempts, archived providers,
   NUMBER precision, and TIMESTAMP versus TIMESTAMP WITH TIME ZONE handling.
   Confirm ORM-written timestamps bucket correctly; avoid reliance on the
   database/JVM default timezone or changing global timestamp configuration.
7. Customer counts exclude ADMIN/SYSTEM. All backlog and age predicates match
   the definitions, including KYC resubmission time and exact 24-hour boundaries.
8. Drill-down totals/rows match their filters, pagination is stable, and each
   selected ID loads the existing operations view. Browser Back restores filters.
9. ADMIN access works; regular users and anonymous callers cannot retrieve
   report data. Existing authentication and customer/admin navigation still work.
10. Empty, failed, refresh, rapid-filter-change, logout, disposal, and keyboard/
    narrow-screen states are checked in the frontend and browser.

Use meaningful service/controller tests plus a dedicated-schema Oracle query
integration test. Mock-only SQL tests and portable build success do not prove
Oracle behavior. The existing integration profile requires the FLUXPAY_TEST
schema and its environment prerequisites; never run destructive integration
fixtures against the user's working/demo schema.

Frontend verification uses `npm.cmd test`, `npm.cmd run typecheck`, and
`npm.cmd run build` from `frontend/fluxpay-ui`. Backend verification uses the
repository Maven wrapper with `-f backend/pom.xml`, formatting checks, portable
tests, and the dedicated integration profile when configured. Run browser smoke
checks against real data when the application is available. Record unavailable
environment checks honestly rather than reporting them as passed.

## Explicit exclusions

Wallet top-ups, direct wallet transfers, exchanges and withdrawals; accounting
revenue/profit; exchange-rate conversion; forecasts; historical queue snapshots;
SLA compliance claims; exports; automatic refresh; new role/auth behavior;
analytics infrastructure; fake production statistics; and routine demo reseeding.

## Review state

The functional scope and written specification were approved in chat. Timezone,
currency default, range limit, exact formulas, API split, and error details are
the accepted design defaults. No product code has been changed. The companion
implementation plan is `../plans/2026-09-25-admin-statistics.md`.
