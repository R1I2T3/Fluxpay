const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const read = file => fs.readFileSync(path.join(__dirname, '../src', file), 'utf8');

test('admin payment operations exposes the typed read-only operations request', () => {
  const api = read('ts/services/flux-api.ts');
  assert.match(
    api,
    /adminPaymentOperations: \(id: string\) =>\s*request<PaymentOperationsResponse>\(\s*`\/api\/admin\/payments\/\$\{encodeURIComponent\(id\)\}\/operations`,\s*\),/s,
  );
  const method = api.slice(
    api.indexOf('adminPaymentOperations:'),
    api.indexOf('  routes:', api.indexOf('adminPaymentOperations:')),
  );
  assert.doesNotMatch(method, /['"]POST['"]/);
  assert.doesNotMatch(method, /Idempotency-Key|,\s*(?:undefined|true|false)\s*\)/);
});

test('failed customer payments link to support rather than offering a refund action', () => {
  const tracking = read('ts/views/tracking.html');
  assert.match(tracking, /Contact support ↗/);
  assert.match(tracking, /data-route="tickets"/);
  assert.doesNotMatch(tracking, /askAction\('refund'\)/);
});

test('customer page and API do not retain refund dispatch or payment-action branches', () => {
  const page = read('ts/services/page.ts');
  const api = read('ts/services/flux-api.ts');
  assert.doesNotMatch(page, /action==='refund'/);
  assert.doesNotMatch(api, /refund:\s*\(id:\s*string\)/);
  assert.doesNotMatch(api, /\/api\/payments\/\$\{id\}\/refund/);
});
