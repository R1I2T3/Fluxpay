const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const ko = require('knockout');
const { load } = require('./helpers/load-typescript.cjs');

const read = (file) => fs.readFileSync(path.join(__dirname, '../src', file), 'utf8');

test('admin payment operations balances Knockout virtual elements so bindings initialize', () => {
  const html = read('ts/views/admin-payment-operations.html');
  const markers = Array.from(html.matchAll(/<!--\s*(ko\b[\s\S]*?|\/ko)\s*-->/g), (match) =>
    match[1].trim(),
  );
  let depth = 0;

  for (const marker of markers) {
    depth += marker === '/ko' ? -1 : 1;
    assert.ok(depth >= 0, `unexpected Knockout closing marker: ${marker}`);
  }

  assert.equal(depth, 0, 'unclosed Knockout virtual element prevents the page from binding');
});

test('admin payment operations renders accessible read-only lifecycle evidence', () => {
  const html = read('ts/views/admin-payment-operations.html');
  const css = read('css/admin-console.css');

  assert.match(html, /Administrator access required/);
  assert.match(html, /session\.isAdmin\(\)/);
  assert.match(html, /role="alert"/);
  assert.match(html, /role="status"[^>]*aria-live="polite"/);
  assert.match(html, /<details>/);
  assert.match(html, /<summary>/);
  assert.match(
    html,
    /class="event-payload" data-bind="text:\$parent\.json\(payload\)"/,
  );
  assert.match(html, /text:\$parent\.label\(payment\.status\)/);
  assert.match(html, /text:payment\.id/);
  assert.match(html, /text:payment\.eventSequence/);
  assert.match(html, /text:recovery\.decision/);
  assert.match(html, /delivery \? delivery\.state : 'UNKNOWN'/);
  assert.match(html, /delivery \? delivery\.attemptCount : 0/);
  assert.match(html, /delivery && delivery\.nextAttemptAt/);
  assert.match(html, /delivery && delivery\.lastError/);
  assert.doesNotMatch(html, /payment\(\)\.|recovery\(\)\.|delivery\(\)/);
  assert.doesNotMatch(html, /data-bind="[^"]*\bhtml\s*:/);
  assert.doesNotMatch(html, /Simulate|Trigger retry|Trigger refund|Reconcile payout/);
  assert.match(css, /\.operations-flow\s*\{[^}]*display:\s*grid/);
  assert.match(css, /\[data-state='PENDING'\]/);
  assert.match(css, /\[data-state='SENDING'\]/);
  assert.match(css, /\[data-state='SENT'\]/);
  assert.doesNotMatch(css, /linear-gradient|radial-gradient/);
});

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

const timestamp = '2026-09-25T10:00:00.000Z';
const operationsResponse = (overrides = {}) => ({
  payment: {
    id: 'payment-default',
    status: 'COMPLETED',
    selectedQuoteId: null,
    eventSequence: 0,
    createdAt: timestamp,
    updatedAt: timestamp,
  },
  attempts: [],
  outboxEvents: [],
  timelineEvents: [],
  operations: [],
  ledgerEntries: [],
  recovery: { automatedRetryCount: 0, decision: 'NOT_REQUIRED', nextRun: null },
  ...overrides,
});
const outboxEvent = (
  eventId,
  eventType,
  aggregateSequence,
  state = 'SENT',
  createdAt = timestamp,
) => ({
  eventId,
  eventType,
  aggregateSequence,
  createdAt,
  payload: { eventId },
  delivery: {
    state,
    attemptCount: state === 'PENDING' ? 1 : 0,
    nextAttemptAt: timestamp,
    sentAt: state === 'SENT' ? timestamp : null,
    lastError: null,
  },
});
const timelineEvent = (
  eventId,
  eventType,
  occurredAt = timestamp,
  correlationId = `${eventId}-correlation`,
) => ({
  eventId,
  eventType,
  kafkaTopic: eventType,
  correlationId,
  payload: { eventId },
  occurredAt,
});
const deferred = () => {
  let resolve;
  let reject;
  const promise = new Promise((resolvePromise, rejectPromise) => {
    resolve = resolvePromise;
    reject = rejectPromise;
  });
  return { promise, resolve, reject };
};
const flushAsync = () => new Promise((resolve) => setImmediate(resolve));

function viewModelHarness({ api, user = { role: 'ADMIN' }, restore, visibility = 'visible' } = {}) {
  const userObservable = ko.observable(user);
  const restoreCalls = [];
  const session = {
    user: userObservable,
    isAdmin: ko.pureComputed(() => userObservable()?.role === 'ADMIN'),
    restore:
      restore ||
      (async () => {
        restoreCalls.push('restore');
      }),
  };
  const timers = new Map();
  let nextTimer = 0;
  const setInterval = (callback, delay) => {
    const timer = ++nextTimer;
    timers.set(timer, { callback, delay });
    return timer;
  };
  const clearInterval = (timer) => timers.delete(timer);
  const windowHost = { setInterval, clearInterval };
  const documentHost = { visibilityState: visibility, hidden: visibility === 'hidden' };
  const ViewModel = load(
    'ts/viewModels/admin-payment-operations.ts',
    {
      knockout: ko,
      '../services/flux-api': {
        fluxApi: api || { adminPaymentOperations: async () => operationsResponse() },
      },
      '../services/session': { session },
    },
    {
      window: windowHost,
      document: documentHost,
      setInterval,
      clearInterval,
    },
  );
  return {
    ViewModel,
    session,
    timers,
    restoreCalls,
    create: (params = {}) => new ViewModel(params),
    setVisibility: (value) => {
      documentHost.visibilityState = value;
      documentHost.hidden = value === 'hidden';
    },
    fire: (timer) => timers.get(timer)?.callback(),
  };
}

test('admin payment operations restores the session before its initial lookup', async () => {
  const calls = [];
  const restoreGate = deferred();
  const harness = viewModelHarness({
    user: null,
    restore: async () => {
      calls.push('restore');
      await restoreGate.promise;
      calls.push('restored');
    },
    api: {
      adminPaymentOperations: async (id) => {
        calls.push(`api:${id}`);
        return operationsResponse();
      },
    },
  });
  const vm = harness.create({ params: { paymentId: ' payment-initial ' } });
  assert.deepEqual(calls, ['restore']);
  restoreGate.resolve();
  await restoreGate.promise;
  await flushAsync();
  assert.deepEqual(calls, ['restore', 'restored', 'api:payment-initial']);
  vm.disconnected();
});

test('admin payment operations correlates timeline evidence by exact event ID', () => {
  const harness = viewModelHarness();
  const vm = harness.create();
  const first = outboxEvent('event-one', 'payout.failed', 1, 'SENT', '2026-09-25T10:01:00.000Z');
  const second = outboxEvent('event-two', 'payout.failed', 2, 'SENT', '2026-09-25T10:02:00.000Z');
  const timeline = timelineEvent(
    'event-two',
    'payout.failed',
    '2026-09-25T10:02:30.000Z',
    'correlation-two',
  );
  vm.snapshot(operationsResponse({ outboxEvents: [first, second], timelineEvents: [timeline] }));
  const rows = Array.from(vm.eventRows());
  assert.deepEqual(
    rows.map((row) => row.eventId),
    ['event-one', 'event-two'],
  );
  assert.equal(rows[0].timeline, null);
  assert.equal(rows[0].timelinePersisted, false);
  assert.equal(
    rows[0].consumptionLabel,
    'Published to Kafka; timeline persistence is not yet recorded',
  );
  assert.equal(rows[1].timeline.eventId, 'event-two');
  assert.equal(rows[1].correlationId, 'correlation-two');
  assert.equal(rows[1].timelinePersisted, true);
  vm.disconnected();
});

test('admin payment operations appends timeline-only evidence without inventing an outbox row', () => {
  const harness = viewModelHarness();
  const vm = harness.create();
  const outbox = outboxEvent('outbox-1', 'payout.completed', 4);
  const timelineOnly = timelineEvent(
    'timeline-only-1',
    'payment.refunded',
    '2026-09-25T10:05:00.000Z',
  );
  vm.snapshot(operationsResponse({ outboxEvents: [outbox], timelineEvents: [timelineOnly] }));
  const rows = Array.from(vm.eventRows());
  assert.deepEqual(
    rows.map((row) => row.eventId),
    ['outbox-1', 'timeline-only-1'],
  );
  assert.equal(rows[1].outbox, null);
  assert.equal(rows[1].delivery, null);
  assert.equal(rows[1].timelinePersisted, true);
  assert.equal(rows[1].consumptionLabel, 'Persisted to customer timeline');
  vm.disconnected();
});

test('admin payment operations exposes four ordered lifecycle groups and classification gaps', () => {
  const harness = viewModelHarness();
  const vm = harness.create();
  vm.snapshot(
    operationsResponse({
      payment: { ...operationsResponse().payment, status: 'PROCESSING' },
      outboxEvents: [
        outboxEvent('confirmation-1', 'payment.initiated', 1),
        outboxEvent('confirmation-2', 'payment.route.selected', 2),
        outboxEvent('payout-1', 'payout.submitted', 3),
        outboxEvent('payout-2', 'payout.failed', 4),
        outboxEvent('recovery-1', 'payout.retry', 5),
        outboxEvent('refund-1', 'payout.refund', 6),
        outboxEvent('other-1', 'internal.audit.note', 7),
      ],
      recovery: { automatedRetryCount: 1, decision: 'RETRY_SCHEDULED', nextRun: timestamp },
    }),
  );
  const groups = Array.from(vm.lifecycleGroups());
  assert.deepEqual(
    groups.map((group) => [group.id, group.label]),
    [
      ['confirmation', 'Payment confirmation'],
      ['payout', 'Payout execution'],
      ['recovery', 'Recovery / retry'],
      ['refund', 'Refund'],
    ],
  );
  assert.deepEqual(
    groups.map((group) => group.events.map((event) => event.eventId)),
    [['confirmation-1', 'confirmation-2'], ['payout-1', 'payout-2'], ['recovery-1'], ['refund-1']],
  );
  assert.deepEqual(
    groups.map((group) => group.status),
    ['Complete', 'Warning', 'Active', 'Neutral'],
  );
  assert.deepEqual(
    Array.from(vm.unclassifiedEvents()).map((row) => row.eventId),
    ['other-1'],
  );
  assert.ok(
    groups.every((group) => typeof group.description === 'string' && group.description.length > 0),
  );
  vm.disconnected();
});

test('admin payment operations formats evidence labels and exposes snapshot collections', () => {
  const harness = viewModelHarness();
  const vm = harness.create();
  const response = operationsResponse({
    attempts: [{ id: 'attempt-1' }],
    operations: [{ id: 'operation-1' }],
    ledgerEntries: [{ id: 'ledger-1' }],
    recovery: { automatedRetryCount: 2, decision: 'STALE', nextRun: null },
  });
  vm.snapshot(response);
  assert.equal(vm.label('UNDER_REVIEW'), 'Under review');
  assert.equal(vm.dateTime(null), '—');
  assert.equal(vm.dateTime('not-a-date'), 'not-a-date');
  assert.equal(vm.json({ answer: 42 }), '{\n  "answer": 42\n}');
  assert.equal(vm.deliveryLabel('PENDING'), 'Committed — pending publication');
  assert.equal(vm.deliveryLabel('SENDING'), 'Publishing to Kafka');
  assert.equal(vm.deliveryLabel('SENT'), 'Published to Kafka');
  assert.equal(vm.deliveryLabel(), 'Delivery state unavailable');
  assert.equal(Array.from(vm.attempts())[0].id, 'attempt-1');
  assert.equal(Array.from(vm.operations())[0].id, 'operation-1');
  assert.equal(Array.from(vm.ledgerEntries())[0].id, 'ledger-1');
  assert.equal(vm.recovery().decision, 'STALE');
  vm.disconnected();
});

test('admin payment operations keeps the last snapshot and exposes a transient refresh warning', async () => {
  let reads = 0;
  const harness = viewModelHarness({
    api: {
      adminPaymentOperations: async () => {
        reads += 1;
        if (reads === 1)
          return operationsResponse({
            payment: { ...operationsResponse().payment, id: 'payment-stable' },
          });
        throw new Error('temporary read failure');
      },
    },
  });
  const vm = harness.create();
  vm.paymentId('payment-stable');
  await vm.lookup();
  const successfulSnapshot = vm.snapshot();
  await vm.refresh();
  assert.equal(vm.snapshot(), successfulSnapshot);
  assert.equal(vm.snapshot().payment.id, 'payment-stable');
  assert.ok(vm.refreshWarning().length > 0);
  vm.disconnected();
});

test('admin payment operations ignores a late response after a payment ID change', async () => {
  const first = deferred();
  const second = deferred();
  const calls = [];
  const harness = viewModelHarness({
    api: {
      adminPaymentOperations: (id) => {
        calls.push(id);
        return id === 'payment-one' ? first.promise : second.promise;
      },
    },
  });
  const vm = harness.create();
  vm.paymentId('payment-one');
  const firstLookup = vm.lookup();
  vm.paymentId('payment-two');
  const secondLookup = vm.lookup();
  second.resolve(
    operationsResponse({ payment: { ...operationsResponse().payment, id: 'payment-two' } }),
  );
  await secondLookup;
  first.resolve(
    operationsResponse({ payment: { ...operationsResponse().payment, id: 'payment-one' } }),
  );
  await firstLookup;
  assert.deepEqual(calls, ['payment-one', 'payment-two']);
  assert.equal(vm.snapshot().payment.id, 'payment-two');
  vm.disconnected();
});

test('admin payment operations refreshes active payments only when the admin requests it', async () => {
  let reads = 0;
  const harness = viewModelHarness({
    api: {
      adminPaymentOperations: async () => {
        reads += 1;
        return operationsResponse({
          payment: { ...operationsResponse().payment, status: 'PROCESSING' },
        });
      },
    },
  });
  const vm = harness.create();
  vm.paymentId('payment-poll');
  await vm.lookup();
  assert.equal(reads, 1);
  assert.equal(harness.timers.size, 0);
  await vm.refresh();
  assert.equal(reads, 2);
  vm.disconnected();
  assert.equal(harness.timers.size, 0);
});
