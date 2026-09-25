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
  f.api.adminStatistics = async (query) => summary(query);
  f.session.user({ id: 'admin-2', role: 'ADMIN' });
  await f.vm.ready;
  assert.equal(f.vm.snapshot().meta.currency, 'INR');
  pending.resolve(summary({ from: '2026-08-27', to: '2026-09-25', currency: 'INR' }));
  await oldReady;
  assert.equal(f.vm.snapshot().meta.currency, 'INR');
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
  assert.match(html, /text:/);
  assert.doesNotMatch(html, /html:/);
  assert.match(html, /No final outcomes/);
  assert.match(html, /No terminal attempts/);
  assert.match(html, /All time/);
  assert.ok(html.includes('Selected dates / all currencies'));
  assert.ok(html.includes('Current / all dates and currencies'));
  assert.equal(
    f.calls.some((call) => call.type === 'payments'),
    false,
  );
});
