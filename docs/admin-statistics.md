# Admin statistics guide

The Statistics page gives an administrator one read-only view of payment activity and
current operational workload. It answers three questions: how many payments were
**created** in a period, what is their **current** state, and what is waiting for a
human right now.

This guide is for administrators using the page. The wire format for the three
endpoints behind it is in the [Bruno API catalog](api-catalog.md#admin-statistics-reporting-api).

## Who can see it

Sign in with an administrator account. Non-administrators see "Administrator access
required." instead of the report, and signing out clears every figure on the page.
There is no new role, permission, or account setting: the page uses the same
administrator authorization as the rest of the admin area.

## Where it lives

In the admin sidebar, open the **Overview** group and choose **Statistics**, next to
**Overview**. The page is bookmarkable and shareable: the selected dates, source
currency, open drill-down, selected status, selected day, and list page are all stored
in the page URL, so a colleague who opens the same link sees the same report.

## Choose the period

Two date boxes, **Start date** and **End date**, accept calendar dates, and the page
shows the reporting zone (`Asia/Kolkata`, IST) directly beneath them. Both dates are
**inclusive**: selecting 1 September to 25 September covers all 25 days, including
both endpoints.

Four presets fill the boxes for you: **Today**, **7 days**, **30 days**, and
**90 days**. Each preset ends today and counts backwards, so "30 days" means today
plus the preceding 29 days. The page opens on the last 30 days.

Three rules are enforced by the server, not just the browser:

- The start date must not be after the end date.
- The end date cannot be in the future.
- The range cannot exceed **366 inclusive days**.

**Today is always partial.** A report's effective end is capped at the moment the
server answered, so a report for today covers only the part of today that has already
happened, and the numbers will grow. This is expected. Likewise, a past day's figures
are not frozen: they describe each payment's state *now*, not its state at the end of
that day. A payment created on 3 September that completed yesterday is counted as a
completed payment created on 3 September. If that matters to your investigation, note
the **Last updated** timestamp and use the payment drill-down (below) for the live
record.

## Choose the source currency

**Source currency** filters to the currency the customer was charged — the amount on
the payment, not the recipient's currency. It defaults to INR when INR is configured,
otherwise to the first currency alphabetically, and it is restored from the URL when
you return to a bookmarked page.

There is deliberately **no "all currencies" money total and no exchange-rate
conversion** anywhere on this page. If you need a combined figure across currencies,
add the per-currency numbers yourself, or ask for a converted total to be built as a
separate feature. Amounts are shown at each currency's own configured precision, so
JPY-style zero-decimal currencies do not gain fake decimals.

Changing the currency changes the payment and provider figures. It does **not** change
the customer figures or the workload figures — see below.

## Read the payment panels

The page opens with **Payment summary**, then **Payment statuses**, then the two daily
charts.

**Payments created** counts every payment in the cohort, and that deliberately
**includes drafts and quotes**. A payment that was created and abandoned still counts
as a payment created. Use the status table to see how much of that total is real work.

**Completed payments** and the amount beneath it count payments whose state is
currently `COMPLETED`, and the sum of their source amounts. This is the amount
customers were charged, not net recipient proceeds and not revenue. That card is the
authoritative money figure on the page: the server sums the cohort at full precision and
rounds once, at the end. **Completed amount by day** rounds each day separately for
display, so the card and a hand-summed daily column can differ by a small per-day
rounding amount — see "Open the matching payments" below.

**Processing now** counts payments currently `PROCESSING`, including those awaiting
reconciliation after an uncertain provider response.

**Payout success rate** is `completed ÷ (completed + failed + refunded)`, expressed as
a percentage. The denominator therefore contains only payments that reached a final
outcome; drafts, quotes, reviews, processing, rejections, and cancellations are left
out. A refunded payment counts as an unsuccessful payout, because the customer did not
get what they paid for. When no payment in the period has a final outcome, the page
shows **"No final outcomes"** instead of `0%` or `100%` — an empty period is not a
failure.

**Payment statuses** lists all nine payment states with a count each, including the
ones with zero. The counts add up to **Payments created**.

**Payments created by day** and **Completed amount by day** are separate charts on
separate scales, grouped by the day the payment was **created** — not the day it
settled. Days with no matching payments are shown as zero rather than skipped, so a gap
in the line is real information. Each chart has a **View daily …** table underneath
with the exact numbers if the line is hard to read, and every chart is labelled in text
for screen readers.

## Payment success rate versus provider success rate

These two rates measure different things and will not match. Both are on the page, and
mixing them up is the most common misreading.

| | Payout success rate | Provider attempt success rate |
|---|---|---|
| Counts | payments | payout **attempts** |
| Numerator | payments currently `COMPLETED` | attempts that completed |
| Denominator | completed + failed + refunded **payments** | completed + failed **attempts** |
| Still in flight | `PROCESSING` payments excluded | `INITIATED`/`PROCESSING` attempts excluded, reported as **In progress** |
| Empty case | "No final outcomes" | "No terminal attempts" |

**Provider attempts** is grouped by provider and counts *attempts*, following each
attempt's route to its provider. Read the panel as "attempts for payments created in
the selected period": an attempt can have happened **after** the selected period, as
long as it belongs to a payment created inside it. That is intentional — a payment
created on 2 September that retried on 20 September contributes its 20 September
attempt here.

Because a retried payment produces several attempts, attempt totals exceed payment
totals. A provider whose rate is lower than the page's payout success rate may simply
have absorbed more retries.

`INITIATED` and `PROCESSING` attempts are counted as **In progress** and excluded from
the rate denominator, so an in-flight attempt never flatters or damages a provider's
rate. A provider with only in-flight attempts shows **"No terminal attempts"**.

Archived and inactive providers still appear when they have matching history, which is
how you investigate a provider you have since switched off. Provider cells are
**not** drill-down links; open the matching payment list instead.

## Customers and workload do not follow your filters

**Customer registrations** shows two numbers plus a daily chart:

- **All time customers** is an all-time count of customer accounts.
- **Selected dates / all currencies** is the number of customers who registered inside
  your selected dates.

Only accounts with the customer role are counted; administrator and system accounts are
excluded. Neither number changes when you change the source currency — registrations are
not a monetary figure.

**Current operational workload** is a live snapshot across **all dates and all
currencies**. Changing the dates or the currency does not narrow it, which is the
point: you want to know what is waiting regardless of the period you happen to be
investigating.

- **KYC reviews** — pending applications, and how many have been pending over 24
  hours.
- **Compliance cases** — open cases, how many of those are high risk, and how many have
  been open over 24 hours.
- **Support tickets** — open or in-progress tickets, and how many of those are over 24
  hours old.

"Over 24 hours" means the item's timestamp is more than a day before the report was
generated. It is a **queue age count, not an SLA measurement** — there is no
business-hours calendar, no target, and no compliance claim behind these numbers.

These cards are informational. To work an item, use the KYC, Compliance, and Support
pages in the **Operations** group, or the existing Overview page.

## Open the matching payments

Any payment figure on the page is a button that opens a **Matching payments** list
below the dashboard, filtered to exactly that number:

- **Payments created** — every payment in the period.
- **Completed payments**, **Failed payments**, **Processing now** — that status only.
- Any row in **Payment statuses** — that status only.
- A date in **Payments created by day** — every payment created that day.
- A date in **Completed amount by day** — only the `COMPLETED` payments created that day.

The list shows payment ID, created time, source amount, currency, and status, and its
caption states the exact scope it is showing. The list query uses the same cohort and
status rules as the counts, so the row count at the bottom normally matches the number
you clicked. If it does not, a payment changed state between the two reads — see
"What this page does not tell you".

**Counts and amounts do not reconcile the same way.** The count on a card, the count on
a status row, and the row count of the list it opens are the same measure, so they
normally agree. The **completed amount** card is a sum, and each figure in **Completed
amount by day** is rounded on its own to the currency's configured precision. Adding the
daily column by hand can therefore differ from the card by a small per-day rounding
amount — one half-scale unit per day, in either direction — whenever the source amounts
carry more decimal places than the currency's configured precision. The card is the
authoritative cohort sum and never absorbs that drift; quote the card, and treat a
daily-column mismatch as display rounding rather than a missing payment.

Choose a day in a chart and the dashboard keeps its wider range; only the list narrows
to that single day. Close the list with **Close payment list** when you are done.

Paging shows 20 rows at a time. **Previous page** and **Next page** step through the
results in a stable order — newest first, ties broken by ID — so paging back and forth
does not reshuffle rows. If the data changed and you land on an empty page past the
end, that is not an error: the page offers **Return to the first page**, and the
totals shown are still truthful.

## Open a payment's operations

Each row in **Matching payments** has an **Open operations** button. It takes you to
the Payment Operations page for that exact payment, with its attempts, outbox events,
timeline events, and recovery decision. This is where you go to answer "why did this
payment fail?" — the Statistics page deliberately shows you *that* a payment failed,
not the operational history behind it.

To come back, use your browser's **Back** button. The Statistics page is a bookmarkable
route, so Back returns you to the report exactly as you left it — same dates, same
currency, same open drill-down, same status, same day, same list page. The same is true
if you bookmarked or shared the URL: the link reproduces the view.

## Refreshing, and what "now" means

Use the **Refresh** button to re-read the data. **Last updated** shows the moment the
server generated the response you are looking at, which is the cutoff for everything on
the page — including the workload ages and the "created before" cutoffs.

There is **no automatic refresh and no polling timer**. If you leave the page open, the
numbers stay as they were when you arrived. Refresh deliberately, so that a figure you
are about to quote is one you actually looked at.

While a request is in flight the page shows a loading state; previous figures are never
silently relabelled with a new filter selection. If reporting options or the payment
list fail, the message appears in place with a **Retry** button. If the dashboard itself
fails, the message appears in place and pressing **Apply dates** again retries the same
selection. Signing out while a request is pending discards the late response instead of
displaying another administrator's data.

## Keyboard and assistive technology

Every control is reachable and operable by keyboard: date and currency inputs, the four
presets, **Apply dates**, **Refresh**, all payment count and status buttons, chart date
buttons, the list's paging buttons, and **Open operations**. Buttons carry accessible
names, so a screen reader announces "View 12 failed payments created in the selected
period" rather than just the number. Chart lines are decorative; the accessible text
and the **View daily …** tables carry the actual values. Nothing on the page depends on
colour alone. The layout reflows for narrow viewports, and wide tables scroll
horizontally inside their own card rather than breaking the page.

## Worked example

A single payment is created for **INR 25.00**. The first payout attempt fails; the
automatic retry succeeds an hour later. The payment is now `COMPLETED`.

For a period and source currency that include that payment:

- **Payments created** increases by **one** — not two. The retry is an attempt, not a
  payment.
- **Completed payments** increases by **one**, and the completed amount increases by
  **INR 25.00**. The payment's source amount is counted once, whether it took one
  attempt or five.
- **Payout success rate** sees one more completed payment and no more failed or
  refunded ones, so the rate moves according to
  `100 × completed / (completed + failed + refunded)`.
- **Provider attempts** for the provider that handled it shows **two** attempts: one
  completed and one failed. If that provider had no other terminal attempts, its
  success rate is `1 / (1 + 1)` = **50%**.
- **Failed payments** does **not** increase. The payment is not currently `FAILED`. Its
  failed attempt is visible only in the provider panel and on the payment's operations
  page.

Notice the two rates disagree, and both are correct. If this is the only payment in the
period, the payout success rate is `1 / (1 + 0 + 0)` = **100%** — the customer's payout
did succeed. The provider still needed two tries, so its terminal attempt success rate
is **50%**. A low provider rate is not evidence that customers lost money.

## What this page does not tell you

Deliberately out of scope, so you do not go looking for it:

- Wallet top-ups, wallet-to-wallet transfers, currency exchanges, and withdrawals.
- Accounting revenue, margin, or profit. Completed amount is customer-charged value,
  not money earned.
- Exchange-rate conversion or any combined cross-currency money total.
- Forecasts, projections, or trend lines beyond the selected period.
- Historical snapshots of queue or workload state. Workload is always "now".
- SLA compliance, breach counts, or target attainment.
- Exports, scheduled reports, or emailed digests.
- Automatic refresh or background updates.

A figure is a **current-state reading of one request**, not a frozen audit record. If
the system is busy, a later drill-down can legitimately show a different count than
the card you clicked a moment earlier, because a payment changed state in between. For
an auditable record of one payment, use **Open operations**.

## Verification status

The portable checks pass: the full backend test suite, the feature's own portable tests,
the Java formatting check, and the frontend test, typecheck, and build commands. The
statistics repository is registered in the mock lists of the two application-context
wiring tests (`ApplicationBoundaryWiringTest` and `DevelopmentDefaultsTest`), which is
what those contexts need in order to start.

The Oracle integration run and the browser acceptance pass still have **not** been
performed; the next section records exactly what remains.

## Not yet verified

The following checks could **not** be executed in the environment where this page was
documented. They are not passes, and no result should be inferred from static tests.

**Oracle integration and timestamp behavior — not run.** The real-SQL integration test
`AdminStatisticsRepositoryIT` has never executed in this environment, so the following
remain unproven against a live database: Oracle `TIMESTAMP` and `RAW(16)` handling in
the new aggregates, `FROM_TZ(... ) AT TIME ZONE 'Asia/Kolkata'` day bucketing, the
normalized-attempt-ID-to-payment-UUID join, and the >100-row paging path. The
`-Pintegration` profile refuses to start without these environment variables:

- `ORACLE_TEST_JDBC_URL`
- `ORACLE_TEST_PASSWORD`
- `KAFKA_BOOTSTRAP_SERVERS` — required by the profile even though this report sends no
  messages
- `ORACLE_TESTS_ACTIVE=true` and `ORACLE_TEST_USERNAME=FLUXPAY_TEST` for the fixture

Then run, from the repository root (Linux/macOS; use `.\mvnw.cmd` on Windows):

```bash
./mvnw -f backend/pom.xml -Pintegration \
  '-Dtest=AdminStatisticsRulesTest,AdminStatisticsServiceTest,AdminReportControllerMvcTest,ReportQueryServiceTest' \
  -Dit.test=AdminStatisticsRepositoryIT -Duser.timezone=UTC verify
./mvnw -f backend/pom.xml -Pintegration \
  '-Dtest=AdminStatisticsRulesTest,AdminStatisticsServiceTest,AdminReportControllerMvcTest,ReportQueryServiceTest' \
  -Dit.test=AdminStatisticsRepositoryIT -Duser.timezone=Asia/Kolkata verify
```

The two storage timezones matter: they are what would catch a day-bucketing or
timestamp-binding bug that only appears when the JVM and database disagree about where
a day's boundary is. Check that the Failsafe report in `backend/target/failsafe-reports/`
actually lists all 8 `AdminStatisticsRepositoryIT` test methods as **run and passing** —
a skipped or absent class is not a pass, and an empty or missing report directory means
the tests never executed.

**Browser acceptance — not run.** No application was running, so nothing was verified
in a browser against real data. Still outstanding, with a real administrator session
and existing data: desktop and narrow-viewport layout, keyboard-only operation of every
control, all three charts and their text/table alternatives, date and currency changes,
an empty period, each drill-down (payment, status, day), paging past the end, that
**Open operations** lands on the right payment ID, browser **Back** restoring the prior
selection, page refresh, and signing out while a request is pending. Also unverified:
that the existing Overview and customer navigation still work, and how a failed response
renders. Exercise failures with local request interception or the existing test harness
rather than by damaging data.

**Fixture reconciliation — not run.** The controlled-fixture cross-check of every panel
against its drill-down list did not run, because it needs the same database. Still
outstanding: with completed, failed, refunded, processing, and draft payments across
mixed currencies, a later retry, archived providers, `USER`/`ADMIN`/`SYSTEM` accounts,
old/recent/resubmitted KYC, each compliance risk level, and each support state, confirm
that status counts sum to `paymentCount`, that daily counts sum to `paymentCount`, that
each drill-down's total uses the same cohort and status predicates as its card, and that
provider attempt totals are checked separately from payment totals. Do **not** require
the daily completed amounts to add up to `completedAmount`: the card is the
full-precision cohort sum rounded once, and each daily figure is rounded on its own for
display, so the only difference to accept is per-day display rounding.
