const { test } = require('node:test');
const assert = require('node:assert/strict');
const { load } = require('./helpers/load-typescript.cjs');

const helpers = load('ts/services/admin-statistics.ts', {}, { Date, Intl, URLSearchParams });

test('statistics wrappers issue authenticated GETs and unwrap API data', async () => {
  const calls = [];
  const events = [];
  const data = { items: [], totalElements: 0 };
  const runtime = load(
    'ts/services/flux-api.ts',
    { './admin-statistics': helpers },
    {
      window: { FLUXPAY_API_URL: '', dispatchEvent: (event) => events.push(event) },
      sessionStorage: { getItem: () => 'test-admin-token', removeItem() {} },
      fetch: async (url, init) => {
        calls.push({ url, init });
        return { ok: true, status: 200, json: async () => ({ data }) };
      },
      URLSearchParams,
      Date,
      Intl,
    },
  );
  assert.equal(typeof runtime.fluxApi.adminStatisticsOptions, 'function');
  assert.equal(typeof runtime.fluxApi.adminStatistics, 'function');
  assert.equal(typeof runtime.fluxApi.adminStatisticsPayments, 'function');
  const optionsResult = await runtime.fluxApi.adminStatisticsOptions();
  const summaryResult = await runtime.fluxApi.adminStatistics({
    from: '2026-09-01',
    to: '2026-09-25',
    currency: 'INR',
  });
  const paymentsResult = await runtime.fluxApi.adminStatisticsPayments({
    from: '2026-09-01',
    to: '2026-09-25',
    currency: 'INR',
    status: 'FAILED',
    page: 0,
    size: 20,
  });
  assert.deepEqual(
    calls.map((call) => new URL(call.url, 'http://localhost').pathname),
    [
      '/api/admin/reports/statistics/options',
      '/api/admin/reports/statistics',
      '/api/admin/reports/statistics/payments',
    ],
  );
  for (const { url, init } of calls) {
    assert.equal(init.method, 'GET');
    assert.equal(init.headers.Authorization, 'Bearer test-admin-token');
    assert.equal(init.headers['Idempotency-Key'], undefined);
    assert.equal(init.body, undefined);
  }
  const paymentUrl = new URL(calls[2].url, 'http://localhost');
  assert.equal(paymentUrl.searchParams.get('status'), 'FAILED');
  assert.equal(paymentUrl.searchParams.get('currency'), 'INR');
  assert.equal(paymentUrl.searchParams.get('page'), '0');
  assert.equal(optionsResult, data);
  assert.equal(summaryResult, data);
  assert.equal(paymentsResult, data);
  assert.equal(events.length, 0);
});
