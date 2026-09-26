const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('typescript');

const read = (file) => fs.readFileSync(path.join(__dirname, '../src', file), 'utf8');
const compile = (file) =>
  ts.transpileModule(read(file), {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 },
  }).outputText;

const PAYMENT_ID = '11111111-1111-4111-8111-111111111111';

// flux-api.ts imports the statistics helper module; stub it like the other
// suites do so the compiled module evaluates without a bundler.
const statisticsStub = {
  statisticsSearch: (query) => new URLSearchParams(query).toString(),
};
const fluxApiRequire = (name) => {
  if (name === './admin-statistics') return statisticsStub;
  throw new Error(`Missing test dependency: ${name}`);
};

test('payment hold explains why payout is blocked with user-safe copy', () => {
  const context = { exports: {}, require: (name) => require(name) };
  vm.runInNewContext(compile('ts/services/compliance-hold.ts'), context);
  const hold = context.exports;
  assert.deepEqual(
    hold.holdReasonMessages(['AMOUNT_EXCEEDS_REVIEW_THRESHOLD', 'FIRST_TRANSFER_TO_RECIPIENT']),
    [
      'Amount is above the routine review limit and needs a quick compliance check.',
      'This is your first transfer to this recipient, so it needs an extra check.',
    ]
  );
  assert.match(hold.holdReasonMessages(['SOME_UNKNOWN_CODE'])[0], /review|check/i);
  assert.match(hold.holdExpiryLabel(new Date(Date.now() + 5 * 3600 * 1000).toISOString(), Date.now()), /expires in/i);
  assert.match(hold.holdExpiryLabel(new Date(Date.now() - 1000).toISOString(), Date.now()), /expired/i);
});

test('paymentHold endpoint uses authorized GET on /api/payments/{id}/hold', async () => {
  const calls = [];
  const context = {
    exports: {},
    require: fluxApiRequire,
    URLSearchParams,
    window: {},
    sessionStorage: { getItem: () => 'test-token' },
    crypto: { randomUUID: () => PAYMENT_ID },
    fetch: async (url, options) => {
      calls.push([url, options]);
      return { ok: true, status: 200, json: async () => ({ data: { onHold: true } }) };
    },
  };
  vm.runInNewContext(compile('ts/services/flux-api.ts'), context);
  const result = await context.exports.fluxApi.paymentHold(PAYMENT_ID);
  assert.equal(result.onHold, true);
  assert.deepEqual(
    calls.map(([url, options]) => [options.method, url]),
    [['GET', `/api/payments/${PAYMENT_ID}/hold`]]
  );
  assert.equal(calls[0][1].headers.Authorization, 'Bearer test-token');
});

test('confirming into UNDER_REVIEW fires a warning nudge explaining payout is blocked', async () => {
  const ko = require('knockout');
  const notifications = require('./notification-fixture.cjs')();
  const events = [];
  const fakeWindow = {
    setInterval: () => 1,
    clearInterval: () => {},
    addEventListener: () => {},
    removeEventListener: () => {},
    dispatchEvent: (e) => events.push(e),
  };
  const api = {
    confirm: async () => ({ id: PAYMENT_ID, status: 'UNDER_REVIEW' }),
  };
  const helpers = { exports: {}, window: {}, sessionStorage: { getItem: () => null } };
  // Provide flux-api mock via require hook
  const context = {
    exports: {},
    require: (name) => {
      if (name === 'knockout') return ko;
      if (name === './notifications') return notifications;
      if (name === './flux-api') return { fluxApi: api, requireQuoteRoute: () => 'BANK_TRANSFER' };
      if (name === './session') return { session: { user: ko.observable({ role: 'USER' }) }, navigate: () => {} };
      if (name === './activity') return { movementDescription: () => '' };
      if (name === './experience-dialog') return {};
      return require(name);
    },
    window: fakeWindow,
    sessionStorage: { getItem: () => null },
    clearInterval: () => {},
    setInterval: () => 1,
    document: { visibilityState: 'visible' },
  };
  // Expose CustomEvent + window globally for notify()
  const CustomEventShim = class {
    constructor(type, opts) {
      this.type = type;
      this.detail = opts?.detail;
    }
  };
  context.CustomEvent = CustomEventShim;
  vm.runInNewContext(`${compile('ts/services/page.ts')}`, context);
  const page = new context.exports.Page('payments-new');
  page.paymentId(PAYMENT_ID);
  page.payment({ id: PAYMENT_ID, status: 'QUOTED' });
  page.quotes([{ id: 'quote', routeCode: 'BANK_TRANSFER' }]);
  page.selectedQuote({ id: 'quote', routeCode: 'BANK_TRANSFER' });
  page.quoteExpires(new Date(Date.now() + 600000).toISOString());
  page.confirmAction('confirm');
  // Capture both notify() window events and feedbackObservable events
  const allEvents = [...events, ...notifications.events];
  await page.executeAction();
  const combined = [...events, ...notifications.events.slice(allEvents.length)];
  const warning = combined.find((e) => e.type === 'fluxpay:toast' && e.detail?.kind === 'warning');
  assert.ok(warning, 'expected a warning toast on UNDER_REVIEW');
  assert.match(warning.detail.message, /on hold|compliance review/i);
  assert.match(warning.detail.message, /payout|why|receipt/i);
  page.disconnected();
});

test('activity receipt shows a hold card with why, expiry and payout-blocked note', () => {
  const receipt = read('ts/views/payments-list.html');
  assert.match(receipt, /detailHoldVisible/);
  assert.match(receipt, /foreach:detailHoldMessages/);
  assert.match(receipt, /detailHoldExpiry/);
  assert.match(receipt, /detailHold\(\)\?\.whatNext/);
  assert.match(receipt, /Payout stays disabled until approved/);
  assert.ok(!/data-bind="[^"]*\bhtml\s*:/.test(receipt), 'hold card must use text bindings, never html');
  const vmSource = read('ts/viewModels/payments-list.ts');
  assert.match(vmSource, /detailHold/);
  assert.match(vmSource, /paymentHold/);
  assert.match(vmSource, /holdExpiryLabel/);
});

test('review screen warns before send that payout may be held and why', () => {
  const html = read('ts/views/payments-new.html');
  assert.match(html, /reviewHoldHints|Payout may be held/);
  assert.match(html, /foreach:reviewHoldHints/);
  assert.match(html, /approval enables payout|rejection stops/i);
  assert.ok(!/data-bind="[^"]*\bhtml\s*:/.test(html.match(/reviewHoldHints[\s\S]{0,400}/)?.[0] || ''), 'review nudge must use text bindings');
  const vmSource = read('ts/viewModels/payments-new.ts');
  assert.match(vmSource, /reviewHoldHints/);
});

test('review nudge is dynamic: preview endpoint drives the hint list', async () => {
  const calls = [];
  const context = {
    exports: {},
    require: fluxApiRequire,
    URLSearchParams,
    window: {},
    sessionStorage: { getItem: () => 'test-token' },
    crypto: { randomUUID: () => PAYMENT_ID },
    fetch: async (url, options) => {
      calls.push([url, options]);
      return {
        ok: true,
        status: 200,
        json: async () => ({
          data: {
            likely: true,
            risk: 'MEDIUM',
            reasons: ['FIRST_TRANSFER_TO_RECIPIENT'],
            reasonMessages: ['This is your first transfer to this recipient, so it needs an extra check.'],
          },
        }),
      };
    },
  };
  vm.runInNewContext(compile('ts/services/flux-api.ts'), context);
  const preview = await context.exports.fluxApi.holdPreview({
    recipientId: PAYMENT_ID,
    sourceAmount: '10.00',
    sourceCurrency: 'USD',
    paymentId: PAYMENT_ID,
  });
  assert.equal(preview.likely, true);
  assert.deepEqual(
    calls.map(([url, options]) => [options.method, url]),
    [['POST', '/api/payments/hold-preview']]
  );
  assert.deepEqual(JSON.parse(calls[0][1].body), {
    recipientId: PAYMENT_ID,
    sourceAmount: '10.00',
    sourceCurrency: 'USD',
    paymentId: PAYMENT_ID,
  });
  const vmSource = read('ts/viewModels/payments-new.ts');
  assert.match(vmSource, /reviewHoldPreview/);
  assert.match(vmSource, /holdPreview/);
  assert.match(vmSource, /reviewHoldLoading/);
});

test('hold preview never logs the user out: 401 rejects without clearing session', async () => {
  let removed = null;
  const dispatched = [];
  const context = {
    exports: {},
    require: fluxApiRequire,
    URLSearchParams,
    window: { dispatchEvent: (e) => dispatched.push(e) },
    sessionStorage: {
      getItem: () => 'test-token',
      setItem: () => {},
      removeItem: (key) => {
        removed = key;
      },
    },
    crypto: { randomUUID: () => PAYMENT_ID },
    fetch: async () => ({
      ok: false,
      status: 401,
      json: async () => ({ code: 'AUTH_REQUIRED', message: 'authentication is required' }),
    }),
    Event: class {
      constructor(type) {
        this.type = type;
      }
    },
  };
  vm.runInNewContext(compile('ts/services/flux-api.ts'), context);
  await assert.rejects(() =>
    context.exports.fluxApi.holdPreview({
      recipientId: PAYMENT_ID,
      sourceAmount: '10.00',
      sourceCurrency: 'USD',
      paymentId: PAYMENT_ID,
    })
  );
  assert.equal(removed, null, 'preview 401 must not clear the stored token');
  assert.ok(
    !dispatched.some((e) => e.type === 'fluxpay:expired'),
    'preview 401 must not fire session expiry'
  );
});
