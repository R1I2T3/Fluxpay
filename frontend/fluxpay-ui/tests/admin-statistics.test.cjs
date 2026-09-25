const { test } = require('node:test');
const assert = require('node:assert/strict');
const { load } = require('./helpers/load-typescript.cjs');

const helpers = load('ts/services/admin-statistics.ts', {}, { Date, Intl, URLSearchParams });
const options = {
  currencies: [
    { code: 'INR', scale: 2 },
    { code: 'USD', scale: 2 },
  ],
  defaultCurrency: 'INR',
  reportingZone: 'Asia/Kolkata',
  today: '2026-09-25',
  maximumRangeDays: 366,
};

test('presets use calendar dates relative to the server supplied today', () => {
  assert.deepEqual(JSON.parse(JSON.stringify(helpers.presetRange('2026-09-25', 30))), {
    from: '2026-08-27',
    to: '2026-09-25',
  });
  assert.deepEqual(JSON.parse(JSON.stringify(helpers.presetRange('2024-03-01', 1))), {
    from: '2024-03-01',
    to: '2024-03-01',
  });
  assert.deepEqual(JSON.parse(JSON.stringify(helpers.presetRange('2024-03-01', 7))), {
    from: '2024-02-24',
    to: '2024-03-01',
  });
  assert.deepEqual(JSON.parse(JSON.stringify(helpers.presetRange('2025-01-03', 7))), {
    from: '2024-12-28',
    to: '2025-01-03',
  });
  assert.throws(() => helpers.presetRange('2025-02-29', 7), /valid calendar date/i);
});

test('route state supplies defaults and serializes only active filters', () => {
  const result = helpers.resolveRouteState({}, options);
  assert.equal(result.state.from, '2026-08-27');
  assert.equal(result.state.to, '2026-09-25');
  assert.equal(result.state.currency, 'INR');
  assert.equal(result.state.showPayments, false);
  assert.equal(result.state.page, 0);
  assert.equal(result.notice, '');
  assert.deepEqual(JSON.parse(JSON.stringify(helpers.toRouteParams(result.state))), {
    from: '2026-08-27',
    to: '2026-09-25',
    currency: 'INR',
  });
});

test('route resolution rejects partial, invalid, future, oversized and reversed ranges', () => {
  const invalidRoutes = [
    [{ from: '2026-09-01' }, /both a start and end date/i],
    [{ from: '2026-02-30', to: '2026-03-01' }, /valid start calendar date/i],
    [{ from: '2026-09-26', to: '2026-09-26' }, /future date/i],
    [{ from: '2026-09-10', to: '2026-09-01' }, /must not follow/i],
    [{ from: '2025-09-24', to: '2026-09-25' }, /no more than 366 days/i],
    [{ from: '2026-09-01', to: '2026-09-25', status: 'UNKNOWN' }, /valid payment status/i],
    [{ from: '2026-09-01', to: '2026-09-25', page: '-1' }, /valid payment page/i],
    [{ from: '2026-09-01', to: '2026-09-25', page: '1.5' }, /valid payment page/i],
    [{ from: '2026-09-01', to: '2026-09-25', page: '2147483648' }, /valid payment page/i],
    [{ from: '2026-09-01', to: '2026-09-25', day: '2026-08-31' }, /inside the reporting range/i],
  ];
  for (const [params, message] of invalidRoutes)
    assert.throws(() => helpers.resolveRouteState(params, options), message);
});

test('currency and status normalize and a removed bookmark clears drilldown safely', () => {
  const normalized = helpers.resolveRouteState(
    {
      from: '2026-09-01',
      to: '2026-09-25',
      currency: 'usd',
      showPayments: '1',
      status: 'failed',
      day: '2026-09-18',
      page: '2',
    },
    options,
  );
  assert.equal(normalized.state.currency, 'USD');
  assert.equal(normalized.state.status, 'FAILED');
  assert.equal(normalized.state.day, '2026-09-18');
  const removed = helpers.resolveRouteState(
    {
      from: '2026-09-01',
      to: '2026-09-25',
      currency: 'EUR',
      showPayments: '1',
      status: 'FAILED',
      day: '2026-09-18',
      page: '2',
    },
    options,
  );
  assert.equal(removed.state.currency, 'INR');
  assert.equal(removed.state.showPayments, false);
  assert.equal(removed.state.status, undefined);
  assert.equal(removed.state.day, undefined);
  assert.equal(removed.state.page, 0);
  assert.match(removed.notice, /currency/i);
  assert.throws(
    () => helpers.resolveRouteState({}, { ...options, currencies: [], defaultCurrency: null }),
    /currency/i,
  );
});

test('day drilldown narrows only the payment query', () => {
  const state = {
    from: '2026-09-01',
    to: '2026-09-25',
    currency: 'INR',
    showPayments: true,
    status: 'FAILED',
    day: '2026-09-18',
    page: 2,
  };
  assert.deepEqual(JSON.parse(JSON.stringify(helpers.paymentQuery(state))), {
    from: '2026-09-18',
    to: '2026-09-18',
    currency: 'INR',
    status: 'FAILED',
    page: 2,
    size: 20,
  });
  assert.equal(state.from, '2026-09-01');
  assert.equal(
    helpers.statisticsSearch({ from: '2026-01-01', to: '2026-01-02', currency: 'A&B / INR' }),
    'from=2026-01-01&to=2026-01-02&currency=A%26B+%2F+INR',
  );
});

test('large monetary strings retain exact minor units without Number coercion', () => {
  assert.equal(
    helpers.formatReportMoney('900719925474099.12', 'INR', 2),
    'INR 900,719,925,474,099.12',
  );
  assert.equal(helpers.formatReportMoney('0001234.5', 'USD', 2), 'USD 1,234.50');
  assert.equal(helpers.formatReportMoney('8', 'JPY', 0), 'JPY 8');
  assert.throws(() => helpers.formatReportMoney('1.234', 'INR', 2), /scale/i);
  assert.throws(() => helpers.formatReportMoney('1e3', 'INR', 2), /amount/i);
});

test('chart paths handle empty, single-point and zero-only data safely', () => {
  assert.equal(helpers.reportChartPath([], 100, 50), '');
  assert.match(helpers.reportChartPath([0], 100, 50), /^M/);
  assert.match(helpers.reportChartPath([0, 0]), /^M/);
  assert.doesNotMatch(helpers.reportChartPath([0, 0]), /NaN|Infinity/);
});
