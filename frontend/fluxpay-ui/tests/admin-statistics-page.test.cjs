const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { deferred, summary, makePage } = require('./admin-statistics-fixture.cjs');

test('loads options before one initial summary and parameter-only navigation reloads once', async () => {
  const f = makePage();
  await f.settle();
  assert.deepEqual(
    f.calls.map((call) => call.type),
    ['options', 'summary'],
  );
  assert.deepEqual(JSON.parse(JSON.stringify(f.calls[1].query)), {
    from: '2026-08-27',
    to: '2026-09-25',
    currency: 'INR',
  });
  f.vm.parametersChanged({ from: '2026-08-27', to: '2026-09-25', currency: 'INR' });
  assert.equal(f.calls.filter((call) => call.type === 'summary').length, 1);
  f.vm.applyFilters();
  assert.equal(f.calls.filter((call) => call.type === 'summary').length, 1);
  f.vm.from('2026-09-01');
  f.vm.applyFilters();
  await f.vm.ready;
  assert.equal(f.navigateCalls.at(-1).path, 'admin-statistics');
  assert.equal(f.calls.filter((call) => call.type === 'summary').length, 2);
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
    f.calls.some((call) => call.type === 'summary'),
    false,
  );
  await f.vm.retryOptions();
  assert.equal(f.calls.filter((call) => call.type === 'summary').length, 1);
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
  assert.deepEqual(f.calls.find((call) => call.type === 'summary').query, {
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
    f.calls.some((call) => call.type === 'summary'),
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
  assert.equal(f.calls.filter((call) => call.type === 'options').length, 2);
  assert.equal(f.calls.filter((call) => call.type === 'summary').length, 2);
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
    f.calls.some((call) => call.type === 'payments'),
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
