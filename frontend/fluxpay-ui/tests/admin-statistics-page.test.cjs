const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {
  deferred,
  summary,
  paymentRow,
  paymentPage,
  statuses,
  makePage,
} = require('./admin-statistics-fixture.cjs');

const plain = (value) => JSON.parse(JSON.stringify(value));
const readStatisticsView = () =>
  fs.readFileSync(path.join(__dirname, '../src/ts/views/admin-statistics.html'), 'utf8');
const lastCall = (f, kind) => f.calls.filter((call) => call.kind === kind).at(-1);
const countCalls = (f, kind) => f.calls.filter((call) => call.kind === kind).length;

test('loads options before one initial summary and parameter-only navigation reloads once', async () => {
  const f = makePage();
  await f.settle();
  assert.deepEqual(
    f.calls.map((call) => call.kind),
    ['options', 'summary'],
  );
  assert.deepEqual(JSON.parse(JSON.stringify(f.calls[1].query)), {
    from: '2026-08-27',
    to: '2026-09-25',
    currency: 'INR',
  });
  f.vm.parametersChanged({ from: '2026-08-27', to: '2026-09-25', currency: 'INR' });
  assert.equal(f.calls.filter((call) => call.kind === 'summary').length, 1);
  f.vm.applyFilters();
  assert.equal(f.calls.filter((call) => call.kind === 'summary').length, 1);
  f.vm.from('2026-09-01');
  f.vm.applyFilters();
  await f.vm.ready;
  assert.equal(f.navigateCalls.at(-1).path, 'admin-statistics');
  assert.equal(f.calls.filter((call) => call.kind === 'summary').length, 2);
});

test('regular and anonymous sessions never request protected reports', async () => {
  for (const role of ['CUSTOMER', null]) {
    const f = makePage({ role });
    await f.settle();
    assert.equal(f.calls.length, 0);
  }
});

test('options failure can be retried before summary loading', async () => {
  let first = true;
  const f = makePage({
    options: async () => {
      if (first) {
        first = false;
        throw new Error('Options offline');
      }
      return {
        currencies: [
          { code: 'INR', scale: 2 },
          { code: 'USD', scale: 2 },
        ],
        defaultCurrency: 'INR',
        reportingZone: 'Asia/Kolkata',
        today: '2026-09-25',
        maximumRangeDays: 366,
      };
    },
  });
  await f.settle();
  await f.vm.ready;
  assert.equal(f.vm.optionsError(), 'Options offline');
  assert.equal(
    f.calls.some((call) => call.kind === 'summary'),
    false,
  );
  await f.vm.retryOptions();
  assert.equal(f.calls.filter((call) => call.kind === 'summary').length, 1);
});

test('route parameters changed while options load own the initial summary query', async () => {
  const pendingOptions = deferred();
  const f = makePage({ options: () => pendingOptions.promise });
  await f.settle();
  f.vm.parametersChanged({ from: '2026-09-01', to: '2026-09-25', currency: 'USD' });
  pendingOptions.resolve({
    currencies: [
      { code: 'INR', scale: 2 },
      { code: 'USD', scale: 2 },
    ],
    defaultCurrency: 'INR',
    reportingZone: 'Asia/Kolkata',
    today: '2026-09-25',
    maximumRangeDays: 366,
  });
  await f.vm.ready;
  assert.deepEqual(f.calls.find((call) => call.kind === 'summary').query, {
    from: '2026-09-01',
    to: '2026-09-25',
    currency: 'USD',
  });
});

test('empty currency options show setup state and skip summary requests', async () => {
  const f = makePage({
    options: async () => ({
      currencies: [],
      defaultCurrency: null,
      reportingZone: 'Asia/Kolkata',
      today: '2026-09-25',
      maximumRangeDays: 366,
    }),
  });
  await f.settle();
  await f.vm.ready;
  assert.deepEqual(f.vm.options().currencies, []);
  assert.equal(
    f.calls.some((call) => call.kind === 'summary'),
    false,
  );
});

test('successful zero summary is visible and last update comes from server metadata', async () => {
  const f = makePage();
  await f.settle();
  assert.equal(f.vm.snapshot().paymentSummary.paymentCount, 0);
  assert.equal(f.vm.snapshot().paymentStatuses.length, 9);
  assert.equal(f.vm.snapshot().paymentTrend.length, 30);
  assert.equal(f.vm.lastUpdated(), '2026-09-25T12:00:00Z');
});

test('summary failures are reported independently from options and list state', async () => {
  const f = makePage({
    summary: async () => {
      throw new Error('Summary offline');
    },
  });
  await f.settle();
  assert.equal(f.vm.error(), 'Summary offline');
  assert.equal(f.vm.optionsError(), '');
  assert.equal(f.vm.listError(), '');
  assert.equal(f.vm.snapshot(), undefined);
});

test('applying unchanged filters retries a failed summary request', async () => {
  let shouldFail = true;
  let attempts = 0;
  const route = { from: '2026-09-01', to: '2026-09-25', currency: 'INR' };
  const f = makePage({
    params: route,
    summary: async (query) => {
      attempts++;
      if (shouldFail) throw new Error('Summary offline');
      return summary(query);
    },
  });
  await f.settle();
  await f.vm.ready;
  assert.equal(f.vm.error(), 'Summary offline');
  f.navigate('admin-statistics', route);
  assert.equal(f.parameterUpdates, 0);
  shouldFail = false;
  const navigationsBeforeRetry = f.navigateCalls.length;
  f.vm.applyFilters();
  await f.vm.ready;
  assert.equal(attempts, 2);
  assert.equal(f.vm.error(), '');
  assert.ok(f.vm.snapshot());
  assert.equal(f.navigateCalls.length, navigationsBeforeRetry);
});

test('options loading finishes independently while the initial summary remains pending', async () => {
  const pendingOptions = deferred();
  const pendingSummary = deferred();
  const f = makePage({
    options: () => pendingOptions.promise,
    summary: () => pendingSummary.promise,
  });
  await f.settle();
  assert.equal(f.vm.optionsBusy(), true);
  assert.equal(f.vm.busy(), false);
  pendingOptions.resolve({
    currencies: [
      { code: 'INR', scale: 2 },
      { code: 'USD', scale: 2 },
    ],
    defaultCurrency: 'INR',
    reportingZone: 'Asia/Kolkata',
    today: '2026-09-25',
    maximumRangeDays: 366,
  });
  await new Promise((resolve) => setImmediate(resolve));
  assert.equal(f.vm.optionsBusy(), false);
  assert.equal(f.vm.busy(), true);
  pendingSummary.resolve(summary({ from: '2026-08-27', to: '2026-09-25', currency: 'INR' }));
  await f.vm.ready;
});

test('a late summary cannot overwrite a newer currency selection', async () => {
  const pending = deferred();
  const newer = deferred();
  const f = makePage({ summary: () => pending.promise });
  await f.settle();
  const oldReady = f.vm.ready;
  f.api.adminStatistics = async () => newer.promise;
  f.vm.parametersChanged({ from: '2026-09-01', to: '2026-09-25', currency: 'USD' });
  const newReady = f.vm.ready;
  pending.resolve(summary({ from: '2026-08-27', to: '2026-09-25', currency: 'INR' }));
  await oldReady;
  assert.equal(f.vm.snapshot(), undefined);
  assert.equal(f.vm.busy(), true);
  newer.resolve(summary({ from: '2026-09-01', to: '2026-09-25', currency: 'USD' }));
  await newReady;
  assert.equal(f.vm.snapshot().meta.currency, 'USD');
});

test('an old administrator account response cannot replace a new account summary', async () => {
  const pending = deferred();
  const f = makePage({ summary: () => pending.promise });
  await f.settle();
  const oldReady = f.vm.ready;
  f.api.adminStatistics = async (query) => {
    const response = summary(query);
    response.paymentSummary.paymentCount = 42;
    return response;
  };
  f.session.user({ id: 'admin-2', role: 'ADMIN' });
  await f.vm.ready;
  assert.equal(f.vm.snapshot().paymentSummary.paymentCount, 42);
  const oldResponse = summary({ from: '2026-08-27', to: '2026-09-25', currency: 'INR' });
  oldResponse.paymentSummary.paymentCount = 999;
  pending.resolve(oldResponse);
  await oldReady;
  assert.equal(f.vm.snapshot().paymentSummary.paymentCount, 42);
});

test('logout and disconnect revoke pending response ownership', async () => {
  for (const revoke of [(f) => f.session.user(null), (f) => f.vm.disconnected()]) {
    const pending = deferred();
    const f = makePage({ summary: () => pending.promise });
    await new Promise((resolve) => setImmediate(resolve));
    const request = f.vm.ready;
    revoke(f);
    pending.resolve(summary({ from: '2026-08-27', to: '2026-09-25', currency: 'INR' }));
    await request;
    assert.equal(f.vm.snapshot(), undefined);
    assert.equal(f.vm.busy(), false);
  }
});

test('admin identity changes and role loss clear prior protected data', async () => {
  const f = makePage();
  await f.settle();
  f.session.user({ id: 'admin-2', role: 'ADMIN' });
  await f.vm.ready;
  assert.equal(f.calls.filter((call) => call.kind === 'options').length, 2);
  assert.equal(f.calls.filter((call) => call.kind === 'summary').length, 2);
  f.session.user({ id: 'admin-2', role: 'CUSTOMER' });
  assert.equal(f.vm.snapshot(), undefined);
  assert.equal(f.vm.options(), undefined);
});

test('template renders accessible summary sections with safe text bindings and no list request', async () => {
  const f = makePage();
  await f.settle();
  const html = fs.readFileSync(
    path.join(__dirname, '../src/ts/views/admin-statistics.html'),
    'utf8',
  );
  for (const section of [
    'Payment summary',
    'Payment statuses',
    'Provider attempts',
    'Customer registrations',
    'Current operational workload',
  ])
    assert.ok(html.includes(section), `missing ${section}`);
  const bindings = [
    'paymentSummary.paymentCount',
    'paymentSummary.completedCount',
    'paymentSummary.completedAmount',
    'paymentSummary.payoutSuccessRate',
    'paymentSummary.failedCount',
    'paymentSummary.processingCount',
    'paymentStatuses',
    'status',
    'count',
    'paymentTrend',
    'date',
    'paymentCount',
    'completedAmount',
    'providerName',
    'providerCode',
    'totalAttempts',
    'completedAttempts',
    'failedAttempts',
    'inProgressAttempts',
    'successRate',
    'customers.totalCustomers',
    'customers.newRegistrations',
    'customerTrend',
    'registrations',
    'workload.kycPending',
    'workload.kycOver24h',
    'workload.complianceOpen',
    'workload.complianceHighRisk',
    'workload.complianceOver24h',
    'workload.ticketsOpen',
    'workload.ticketsOver24h',
  ];
  const dataBindings = Array.from(html.matchAll(/data-bind="([^"]+)"/g), (match) => match[1]);
  const collections = new Set(['paymentStatuses', 'paymentTrend', 'customerTrend']);
  for (const field of bindings)
    assert.ok(
      dataBindings.some((binding) =>
        collections.has(field)
          ? binding.includes('foreach:' + field)
          : binding.startsWith('text:') && binding.includes(field),
      ),
      `missing ${collections.has(field) ? 'foreach' : 'text'} binding for ${field}`,
    );
  assert.doesNotMatch(html, /html:/);
  assert.match(html, /No final outcomes/);
  assert.match(html, /No terminal attempts/);
  assert.match(html, /All time/);
  assert.ok(html.includes('Selected dates / all currencies'));
  assert.ok(html.includes('Current / all dates and currencies'));
  assert.match(html, /Source currency/);
  assert.match(html, /value:from/);
  assert.match(html, /value:to/);
  assert.match(html, /value:currency/);
  assert.match(html, /payment count includes drafts and quotes/i);
  assert.match(html, /payments created in the selected period/i);
  assert.match(
    html,
    /No provider attempts are associated with payments created in this reporting period/i,
  );
  assert.equal((html.match(/statistics-chart-ticks/g) || []).length, 3);
  assert.match(html, /text:\$root\.paymentCountTicks\(\)\[0\]/);
  assert.match(html, /text:\$root\.paymentAmountTicks\(\)\[0\]/);
  assert.match(html, /text:\$root\.customerRegistrationTicks\(\)\[0\]/);
  assert.match(html, /d="M32 8H568M32 85H568M32 162H568"/);
  assert.equal((html.match(/transform="translate\(24 0\)"/g) || []).length, 3);
  assert.match(html, /text:\$root\.snapshot\(\)\.meta\.from/);
  assert.match(html, /text:\$root\.snapshot\(\)\.meta\.to/);
  assert.doesNotMatch(html, /text:\$root\.from\(\)|text:\$root\.to\(\)/);
  assert.equal(
    f.calls.some((call) => call.kind === 'payments'),
    false,
  );
});

test('chart date ticks bind to the displayed snapshot rather than unsubmitted controls', () => {
  const html = fs.readFileSync(
    path.join(__dirname, '../src/ts/views/admin-statistics.html'),
    'utf8',
  );
  assert.equal((html.match(/text:\$root\.snapshot\(\)\.meta\.from/g) || []).length, 3);
  assert.equal((html.match(/text:\$root\.snapshot\(\)\.meta\.to/g) || []).length, 3);
  assert.doesNotMatch(html, /text:\$root\.from\(\)|text:\$root\.to\(\)/);
});

test('failed payment drilldown preserves source currency and opens operations', async () => {
  const f = makePage();
  await f.vm.ready;
  f.vm.openPayments('FAILED');
  await f.vm.ready;
  const query = f.calls.filter((call) => call.kind === 'payments').at(-1).query;
  assert.equal(query.status, 'FAILED');
  assert.equal(query.currency, 'INR');
  assert.equal(query.page, 0);
  assert.equal(query.size, 20);
  f.vm.openOperations({
    paymentId: '00000000-0000-0000-0000-000000000001',
    createdAt: '2026-09-24T12:00:00Z',
    sourceAmount: '25.00',
    sourceCurrency: 'INR',
    status: 'FAILED',
  });
  const navigation = f.calls.filter((call) => call.kind === 'navigate').at(-1);
  assert.equal(navigation.path, 'admin-payment-operations');
  assert.equal(navigation.params.paymentId, '00000000-0000-0000-0000-000000000001');
});

test('every drilldown entry point maps to exactly one list filter', async () => {
  const f = makePage();
  await f.vm.ready;
  const summaryCalls = countCalls(f, 'summary');

  f.vm.openPayments();
  await f.vm.ready;
  assert.deepEqual(plain(lastCall(f, 'payments').query), {
    from: '2026-08-27',
    to: '2026-09-25',
    currency: 'INR',
    page: 0,
    size: 20,
  });

  for (const status of statuses) {
    f.vm.openPayments(status);
    await f.vm.ready;
    const query = lastCall(f, 'payments').query;
    assert.equal(query.status, status);
    assert.equal(query.from, '2026-08-27');
    assert.equal(query.to, '2026-09-25');
  }

  f.vm.openPayments('COMPLETED', '2026-09-18');
  await f.vm.ready;
  assert.deepEqual(plain(lastCall(f, 'payments').query), {
    from: '2026-09-18',
    to: '2026-09-18',
    currency: 'INR',
    status: 'COMPLETED',
    page: 0,
    size: 20,
  });
  assert.equal(plain(f.vm.state()).day, '2026-09-18');
  assert.equal(plain(f.vm.state()).from, '2026-08-27');
  assert.equal(countCalls(f, 'summary'), summaryCalls);
  assert.equal(countCalls(f, 'payments'), statuses.length + 2);
});

test('page changes reload only the list and keep the dashboard summary', async () => {
  const f = makePage({
    payments: (query) => Promise.resolve(paymentPage(query, { totalElements: 45, totalPages: 3 })),
  });
  await f.vm.ready;
  f.vm.openPayments('COMPLETED');
  await f.vm.ready;
  assert.equal(f.vm.listPage(), 0);
  assert.equal(f.vm.canPreviousPaymentPage(), false);
  assert.equal(f.vm.canNextPaymentPage(), true);

  f.vm.goToPage(2);
  await f.vm.ready;
  assert.equal(lastCall(f, 'payments').query.page, 2);
  assert.equal(plain(f.vm.state()).page, 2);
  assert.equal(countCalls(f, 'summary'), 1);
  assert.ok(f.vm.snapshot());
  assert.equal(f.vm.listError(), '');

  f.vm.goToPage(1);
  await f.vm.ready;
  assert.equal(lastCall(f, 'payments').query.page, 1);
  assert.equal(countCalls(f, 'summary'), 1);

  f.vm.goToPage(-1);
  f.vm.goToPage(1.5);
  await f.settle();
  assert.equal(countCalls(f, 'payments'), 3);
  assert.equal(f.vm.listPage(), 1);
  assert.equal(f.vm.canNextPaymentPage(), true);
  assert.equal(f.vm.canPreviousPaymentPage(), true);
});

test('repeating the same list selection keeps the loaded page and issues no request', async () => {
  const slow = deferred();
  let attempts = 0;
  const f = makePage({
    payments: (query) => {
      attempts++;
      return attempts === 1
        ? slow.promise
        : Promise.resolve(paymentPage(query, { items: [paymentRow()] }));
    },
  });
  await f.vm.ready;
  f.vm.openPayments('FAILED');
  assert.equal(countCalls(f, 'payments'), 1);
  assert.equal(f.vm.listBusy(), true);

  // The same selection again must not orphan the request that `ready` is waiting on.
  f.vm.parametersChanged({ ...f.navigateCalls.at(-1).params });
  let readySettled = false;
  void f.vm.ready.then(() => (readySettled = true));
  await f.settle();
  assert.equal(countCalls(f, 'payments'), 1);
  assert.equal(readySettled, false);

  slow.resolve(
    paymentPage(
      {
        from: '2026-08-27',
        to: '2026-09-25',
        currency: 'INR',
        status: 'FAILED',
        page: 0,
        size: 20,
      },
      { items: [paymentRow()] },
    ),
  );
  await f.vm.ready;
  assert.equal(readySettled, true);
  assert.equal(f.vm.pageData().items.length, 1);
  assert.equal(f.vm.listBusy(), false);
  assert.equal(f.vm.listError(), '');
  assert.equal(countCalls(f, 'summary'), 1);
  assert.equal(plain(f.vm.state()).status, 'FAILED');
});

test('a page beyond the last page is empty without changing the server totals', async () => {
  const f = makePage({
    payments: (query) => Promise.resolve(paymentPage(query, { totalElements: 45, totalPages: 3 })),
  });
  await f.vm.ready;
  f.vm.openPayments();
  await f.vm.ready;
  f.vm.goToPage(999);
  await f.vm.ready;
  assert.equal(f.vm.listError(), '');
  assert.equal(f.vm.pageData().totalElements, 45);
  assert.equal(f.vm.pageData().totalPages, 3);
  assert.equal(f.vm.pageData().items.length, 0);
  assert.equal(f.vm.listPage(), 999);
  assert.equal(f.vm.canNextPaymentPage(), false);
  assert.equal(f.vm.canPreviousPaymentPage(), true);
  assert.equal(countCalls(f, 'summary'), 1);

  f.vm.goToPage(0);
  await f.vm.ready;
  assert.equal(lastCall(f, 'payments').query.page, 0);
});

test('a new filter keeps the open list, resets the page, and drops a day outside the range', async () => {
  const f = makePage({ params: { from: '2026-09-01', to: '2026-09-25', currency: 'INR' } });
  await f.vm.ready;
  f.vm.openPayments('COMPLETED', '2026-09-10');
  await f.vm.ready;
  f.vm.goToPage(2);
  await f.vm.ready;
  assert.equal(f.vm.listPage(), 2);

  f.vm.currency('USD');
  f.vm.applyFilters();
  await f.vm.ready;
  const state = plain(f.vm.state());
  assert.equal(state.showPayments, true);
  assert.equal(state.status, 'COMPLETED');
  assert.equal(state.day, '2026-09-10');
  assert.equal(state.page, 0);
  assert.equal(state.currency, 'USD');
  assert.deepEqual(plain(lastCall(f, 'payments').query), {
    from: '2026-09-10',
    to: '2026-09-10',
    currency: 'USD',
    status: 'COMPLETED',
    page: 0,
    size: 20,
  });
  assert.equal(countCalls(f, 'summary'), 2);

  f.vm.to('2026-09-05');
  f.vm.applyFilters();
  await f.vm.ready;
  const narrowed = plain(f.vm.state());
  assert.equal(narrowed.showPayments, true);
  assert.equal(narrowed.status, 'COMPLETED');
  assert.equal(narrowed.day, undefined);
  assert.equal(narrowed.page, 0);
  assert.deepEqual(plain(lastCall(f, 'payments').query), {
    from: '2026-09-01',
    to: '2026-09-05',
    currency: 'USD',
    status: 'COMPLETED',
    page: 0,
    size: 20,
  });
  assert.equal(f.vm.error(), '');
  assert.ok(f.vm.snapshot());
});

test('closing the list invalidates a pending list response and stops later requests', async () => {
  const slow = deferred();
  const f = makePage({ payments: () => slow.promise });
  await f.vm.ready;
  f.vm.openPayments();
  await f.settle();
  assert.equal(f.vm.listBusy(), true);
  f.vm.closePayments();
  assert.equal(plain(f.vm.state()).showPayments, false);
  assert.equal(f.vm.pageData(), undefined);
  assert.equal(f.vm.listBusy(), false);
  slow.resolve(
    paymentPage({ from: '2026-08-27', to: '2026-09-25', currency: 'INR', page: 0, size: 20 }),
  );
  await f.settle();
  assert.equal(f.vm.pageData(), undefined);
  assert.equal(f.vm.listBusy(), false);
  assert.equal(countCalls(f, 'payments'), 1);
  assert.ok(f.vm.snapshot());
  assert.equal(lastCall(f, 'navigate').params.showPayments, undefined);
});

test('every route field is restored from a raw parameter map', async () => {
  const f = makePage();
  await f.vm.ready;
  f.vm.parametersChanged({
    from: '2026-09-01',
    to: '2026-09-25',
    currency: 'USD',
    showPayments: '1',
    status: 'REFUNDED',
    day: '2026-09-12',
    page: '2',
  });
  await f.vm.ready;
  assert.deepEqual(plain(f.vm.state()), {
    from: '2026-09-01',
    to: '2026-09-25',
    currency: 'USD',
    showPayments: true,
    status: 'REFUNDED',
    day: '2026-09-12',
    page: 2,
  });
  assert.deepEqual(plain(lastCall(f, 'payments').query), {
    from: '2026-09-12',
    to: '2026-09-12',
    currency: 'USD',
    status: 'REFUNDED',
    page: 2,
    size: 20,
  });
  assert.equal(f.vm.listPage(), 2);
  assert.equal(countCalls(f, 'summary'), 2);

  f.vm.parametersChanged({
    from: '2026-09-01',
    to: '2026-09-25',
    currency: 'USD',
    showPayments: '0',
    page: '2',
  });
  await f.vm.ready;
  assert.equal(plain(f.vm.state()).showPayments, false);
  assert.equal(plain(f.vm.state()).page, 0);
  assert.equal(f.vm.pageData(), undefined);
  assert.equal(countCalls(f, 'summary'), 2);
  assert.equal(countCalls(f, 'payments'), 1);
});

test('invalid route filters send neither a summary nor a list query', async () => {
  const f = makePage();
  await f.vm.ready;
  f.vm.parametersChanged({
    from: '2026-09-01',
    to: '2026-09-25',
    currency: 'INR',
    showPayments: '1',
    day: '2026-08-01',
    page: '0',
  });
  await f.vm.ready;
  assert.equal(f.vm.error(), 'The selected day must be inside the reporting range.');
  assert.equal(f.vm.snapshot(), undefined);
  assert.equal(f.vm.pageData(), undefined);
  assert.equal(f.vm.listBusy(), false);
  assert.equal(countCalls(f, 'summary'), 1);
  assert.equal(countCalls(f, 'payments'), 0);
});

test('a stale list response cannot replace a newer list selection', async () => {
  const slow = deferred();
  let attempts = 0;
  const f = makePage({
    payments: (query) => {
      attempts++;
      if (attempts === 1) return slow.promise;
      return Promise.resolve(paymentPage(query, { items: [paymentRow({ paymentId: 'newer' })] }));
    },
  });
  await f.vm.ready;
  f.vm.openPayments();
  await f.settle();
  f.vm.openPayments('FAILED');
  await f.vm.ready;
  assert.equal(f.vm.pageData().items[0].paymentId, 'newer');
  slow.resolve(
    paymentPage(
      { from: '2026-08-27', to: '2026-09-25', currency: 'INR', page: 0, size: 20 },
      { items: [paymentRow({ paymentId: 'stale' })] },
    ),
  );
  await f.settle();
  assert.equal(f.vm.pageData().items[0].paymentId, 'newer');
  assert.equal(f.vm.listBusy(), false);
  assert.ok(f.vm.snapshot());
});

test('a failed list request preserves the summary and retries the same list state', async () => {
  let shouldFail = true;
  const f = makePage({
    payments: (query) => {
      if (shouldFail) return Promise.reject(new Error('Payments offline'));
      return Promise.resolve(paymentPage(query, { items: [paymentRow()] }));
    },
  });
  await f.vm.ready;
  const summaryCalls = countCalls(f, 'summary');
  f.vm.openPayments('FAILED');
  await f.vm.ready;
  assert.equal(f.vm.listError(), 'Payments offline');
  assert.equal(f.vm.error(), '');
  assert.ok(f.vm.snapshot());
  assert.equal(f.vm.pageData(), undefined);
  assert.equal(f.vm.listBusy(), false);
  assert.equal(countCalls(f, 'summary'), summaryCalls);

  shouldFail = false;
  await f.vm.retryPayments();
  assert.equal(f.vm.listError(), '');
  assert.equal(f.vm.pageData().items.length, 1);
  assert.deepEqual(plain(lastCall(f, 'payments').query), {
    from: '2026-08-27',
    to: '2026-09-25',
    currency: 'INR',
    status: 'FAILED',
    page: 0,
    size: 20,
  });
  assert.equal(countCalls(f, 'summary'), summaryCalls);
});

test('logout and disconnect clear the loaded payment page', async () => {
  for (const revoke of [(f) => f.session.user(null), (f) => f.vm.disconnected()]) {
    const f = makePage({
      payments: (query) => Promise.resolve(paymentPage(query, { items: [paymentRow()] })),
    });
    await f.vm.ready;
    f.vm.openPayments();
    await f.vm.ready;
    assert.equal(f.vm.pageData().items.length, 1);
    revoke(f);
    assert.equal(f.vm.pageData(), undefined);
    assert.equal(f.vm.listBusy(), false);
    assert.equal(f.vm.snapshot(), undefined);
  }
});

test('refresh reloads the summary and the open list', async () => {
  const f = makePage({
    payments: (query) => Promise.resolve(paymentPage(query, { items: [paymentRow()] })),
  });
  await f.vm.ready;
  f.vm.openPayments('FAILED');
  await f.vm.ready;
  await f.vm.refresh();
  assert.equal(countCalls(f, 'summary'), 2);
  assert.equal(countCalls(f, 'payments'), 2);
  assert.equal(f.vm.refreshStatus(), '');
  assert.equal(f.vm.pageData().items.length, 1);
  f.vm.closePayments();
  await f.vm.ready;
  await f.vm.refresh();
  assert.equal(countCalls(f, 'summary'), 3);
  assert.equal(countCalls(f, 'payments'), 2);
});

test('the list presents rows, totals, announcements, and paging controls', async () => {
  const f = makePage({
    payments: (query) =>
      Promise.resolve(
        paymentPage(query, {
          items: [
            paymentRow({ paymentId: 'row-1' }),
            paymentRow({ paymentId: 'row-2', sourceAmount: '1234.50', sourceCurrency: 'USD' }),
          ],
          totalElements: 45,
          totalPages: 3,
        }),
      ),
  });
  await f.vm.ready;
  f.vm.openPayments('FAILED');
  await f.vm.ready;
  const page = f.vm.pageData();
  assert.equal(page.items.length, 2);
  assert.equal(page.totalElements, 45);
  assert.equal(page.size, 20);
  assert.equal(
    f.vm.listSelection(),
    'Payments created 2026-08-27 to 2026-09-25, INR source currency, status FAILED',
  );
  assert.equal(f.vm.listTotals(), 'Showing 1–2 of 45 payments · page 1 of 3');
  assert.equal(f.vm.listAnnouncement(), '2 payment records loaded of 45 matching payments.');
  assert.equal(f.vm.canPreviousPaymentPage(), false);
  assert.equal(f.vm.canNextPaymentPage(), true);
  assert.equal(f.vm.beyondLastPage(), false);
  assert.equal(
    f.vm.formatMoney(page.items[1].sourceAmount, page.items[1].sourceCurrency),
    'USD 1,234.50',
  );
  assert.equal(f.vm.formatMoney('25.00', 'INR'), 'INR 25.00');
  assert.equal(f.vm.formatRate(50, 'No final outcomes'), '50.00%');
  assert.equal(f.vm.beyondLastPage(), false);
  assert.equal(
    f.vm.formatTime('2026-09-24T12:00:00Z'),
    new Intl.DateTimeFormat('en-GB', {
      day: '2-digit',
      month: 'short',
      year: 'numeric',
      hour: '2-digit',
      minute: '2-digit',
      hour12: false,
      timeZone: page.meta.reportingZone,
      timeZoneName: 'short',
    }).format(new Date('2026-09-24T12:00:00Z')),
  );
  assert.equal(f.vm.formatTime('not-a-timestamp'), 'not-a-timestamp');
});

test('the empty list explains the selection and offers the first page back', async () => {
  const f = makePage({
    payments: (query) => Promise.resolve(paymentPage(query, { totalElements: 45, totalPages: 3 })),
  });
  await f.vm.ready;
  f.vm.openPayments();
  await f.vm.ready;
  f.vm.goToPage(999);
  await f.vm.ready;
  assert.equal(f.vm.beyondLastPage(), true);
  assert.equal(f.vm.canPreviousPaymentPage(), true);
  assert.equal(f.vm.canNextPaymentPage(), false);
  assert.equal(f.vm.listTotals(), 'No rows on page 1000 · 45 payments match this selection');
  assert.equal(f.vm.listAnnouncement(), 'No payment records on this page of 45 matching payments.');
});

test('the template exposes only unambiguous payment drilldown actions', () => {
  const html = readStatisticsView();
  const listCalls = Array.from(html.matchAll(/openPayments\(([^)]*)\)/g), (match) =>
    match[1].trim(),
  );
  assert.deepEqual(listCalls.sort(), [
    '',
    "'COMPLETED'",
    "'COMPLETED', date",
    "'FAILED'",
    "'PROCESSING'",
    'status',
    'undefined, date',
  ]);
  const successRateCard = html.slice(
    html.indexOf('Payout success rate'),
    html.indexOf('Failed payments'),
  );
  assert.ok(successRateCard.length > 0);
  assert.doesNotMatch(successRateCard, /click:/);
  const providerBlock = html.slice(
    html.indexOf('id="statistics-providers-title"'),
    html.indexOf('statistics-customer-card'),
  );
  assert.ok(providerBlock.length > 0);
  assert.doesNotMatch(providerBlock, /click:/);
  assert.doesNotMatch(html, /selectDay/);
  assert.doesNotMatch(html, /html:/);
  for (const binding of [
    'click:closePayments',
    'click:retryPayments',
    'click:$root.openOperations',
    'disable:listBusy',
    'disable:!canPreviousPaymentPage',
    'disable:!canNextPaymentPage',
    'foreach:pageData().items',
    'text:listSelection',
    'text:listTotals',
    'text:listAnnouncement',
    'text:$root.formatTime(createdAt)',
    'text:$root.formatMoney(sourceAmount,sourceCurrency)',
    'text:paymentId',
    'text:sourceCurrency',
  ])
    assert.ok(html.includes(binding), `missing ${binding}`);
  assert.match(html, /aria-live="polite"/);
  assert.match(html, /Return to the first page/);
  assert.equal(countCalls(makePage(), 'payments'), 0);
});

test('the statistics view closes every virtual element it opens', () => {
  const balance = (html) => {
    let depth = 0;
    let lowest = 0;
    for (const line of html.split('\n')) {
      depth +=
        (line.match(/<!--\s*ko\b/g) || []).length - (line.match(/<!--\s*\/ko\s*-->/g) || []).length;
      lowest = Math.min(lowest, depth);
    }
    return { depth, lowest };
  };
  const { depth, lowest } = balance(readStatisticsView());
  assert.equal(lowest, 0, 'a virtual element is closed before the one it opens');
  assert.equal(depth, 0, 'every opened virtual element is closed');
});

test('the statistics view only uses style hooks the stylesheet already defines', () => {
  const html = readStatisticsView();
  const css = fs.readFileSync(path.join(__dirname, '../src/css/admin-statistics.css'), 'utf8');
  const defined = new Set(
    Array.from(css.matchAll(/\.statistics-page \.([a-z-]+)/g), (match) => match[1]),
  );
  for (const hook of Array.from(html.matchAll(/class="(statistics-[\w-]+)/g), (match) => match[1]))
    assert.ok(
      defined.has(hook),
      `unstyled statistics hook: ${hook} (add it under .statistics-page in admin-statistics.css)`,
    );
});

test('rates retain the backend two-decimal precision', () => {
  const f = makePage();
  assert.equal(f.vm.formatRate(12.34, 'No outcomes'), '12.34%');
});

test('chart tick values use the same minimum scale as their paths', async () => {
  const f = makePage();
  await f.settle();
  const snapshot = f.vm.snapshot();
  snapshot.paymentTrend = [{ date: '2026-09-25', paymentCount: 1, completedAmount: '0.50' }];
  f.vm.snapshot(snapshot);
  assert.deepEqual(Array.from(f.vm.paymentCountTicks()), ['1', '0.5', '0']);
  assert.deepEqual(Array.from(f.vm.paymentAmountTicks()), ['1.00', '0.50', '0.00']);
  assert.match(f.vm.paymentAmountPath(), /^M8\.00,85\.00$/);
  assert.equal(new Set(f.vm.paymentCountTicks()).size, 3);
});

test('the amount axis is disclosed as approximate and the daily table stays exact', async () => {
  const html = readStatisticsView();
  const flat = (text) => text.replace(/\s+/g, ' ');
  const amountChart = html.slice(
    html.indexOf('<h2>Completed amount by day</h2>'),
    html.indexOf('id="statistics-providers-title"'),
  );
  assert.ok(amountChart.length > 0);
  const amountChartText = flat(amountChart);
  // The axis tick labels come from a Number() conversion of the trend, so nothing on screen
  // may present them as an exact amount: the rotated axis label, the accessible <desc>, and
  // a visible caption under the chart all say approximate.
  assert.match(amountChartText, /Completed amount \(approx\.\)/);
  assert.match(amountChartText, /The amount axis marks an approximate scale, not exact amounts\./);
  assert.match(
    amountChartText,
    /<p class="statistics-caption"> The amount axis is an approximate scale[\s\S]*?table below\./,
  );
  // The exact amounts stay the untouched server strings in the table below the chart.
  assert.match(amountChart, /text:\$root\.formatMoney\(completedAmount,\$parent\.meta\.currency\)/);
  const countChart = html.slice(
    html.indexOf('<h2>Payments created by day</h2>'),
    html.indexOf('<h2>Completed amount by day</h2>'),
  );
  assert.doesNotMatch(countChart, /approx/i);
  const f = makePage();
  await f.settle();
  // A large amount shows why the disclosure matters: the tick is rendered from a Number()
  // conversion, so it can differ from the server string, while the table keeps the server
  // string exactly.
  const amount = '90071992547409.93';
  const snapshot = f.vm.snapshot();
  snapshot.paymentTrend = [{ date: '2026-09-24', paymentCount: 1, completedAmount: amount }];
  f.vm.snapshot(snapshot);
  assert.equal(f.vm.formatMoney(amount, 'INR'), 'INR 90,071,992,547,409.93');
  const [topTick] = Array.from(f.vm.paymentAmountTicks());
  assert.equal(topTick, Number(amount).toFixed(2));
  assert.notEqual(topTick, amount);
});
