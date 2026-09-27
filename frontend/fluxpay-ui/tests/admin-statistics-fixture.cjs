const ko = require('knockout');
const { load } = require('./helpers/load-typescript.cjs');
const helpers = load('ts/services/admin-statistics.ts', {}, { Date, Intl, URLSearchParams });

const statuses = [
  'DRAFT',
  'QUOTED',
  'UNDER_REVIEW',
  'PROCESSING',
  'COMPLETED',
  'FAILED',
  'REFUNDED',
  'REJECTED',
  'CANCELLED',
];

function deferred() {
  let resolve, reject;
  const promise = new Promise((ok, fail) => {
    resolve = ok;
    reject = fail;
  });
  return { promise, resolve, reject };
}

function eachDate(from, to, make) {
  const rows = [];
  const date = new Date(from + 'T00:00:00.000Z');
  const last = new Date(to + 'T00:00:00.000Z');
  while (date <= last) {
    rows.push(make(date.toISOString().slice(0, 10)));
    date.setUTCDate(date.getUTCDate() + 1);
  }
  return rows;
}

function reportMeta(query) {
  const endExclusive = new Date(query.to + 'T00:00:00.000Z');
  endExclusive.setUTCDate(endExclusive.getUTCDate() + 1);
  return {
    from: query.from,
    to: query.to,
    currency: query.currency,
    currencyScale: 2,
    reportingZone: 'Asia/Kolkata',
    fromInclusive: query.from + 'T00:00:00+05:30',
    toExclusive: endExclusive.toISOString().slice(0, 10) + 'T00:00:00+05:30',
    generatedAt: '2026-09-25T12:00:00Z',
    periodBasis: 'PAYMENT_CREATED_AT',
  };
}

function summary(query) {
  return {
    meta: reportMeta(query),
    paymentSummary: {
      paymentCount: 0,
      completedCount: 0,
      completedAmount: '0.00',
      payoutSuccessRate: null,
      failedCount: 0,
      processingCount: 0,
    },
    paymentTrend: eachDate(query.from, query.to, (date) => ({
      date,
      paymentCount: 0,
      completedAmount: '0.00',
    })),
    paymentStatuses: statuses.map((status) => ({ status, count: 0 })),
    providers: [],
    customers: { totalCustomers: 0, newRegistrations: 0 },
    customerTrend: eachDate(query.from, query.to, (date) => ({ date, registrations: 0 })),
    workload: {
      kycPending: 0,
      kycOver24h: 0,
      complianceOpen: 0,
      complianceHighRisk: 0,
      complianceOver24h: 0,
      ticketsOpen: 0,
      ticketsOver24h: 0,
    },
  };
}

function paymentRow(overrides = {}) {
  return {
    paymentId: '00000000-0000-0000-0000-000000000001',
    createdAt: '2026-09-24T12:00:00Z',
    sourceAmount: '25.00',
    sourceCurrency: 'INR',
    status: 'FAILED',
    ...overrides,
  };
}

function paymentPage(query, overrides = {}) {
  const items = overrides.items || [];
  return {
    meta: reportMeta(query),
    status: query.status === undefined ? null : query.status,
    page: query.page,
    size: query.size,
    totalElements: overrides.totalElements === undefined ? items.length : overrides.totalElements,
    totalPages: overrides.totalPages === undefined ? 0 : overrides.totalPages,
    items,
  };
}

function makePage({
  summary: summaryOverride,
  options: optionsOverride,
  payments: paymentsOverride,
  role = 'ADMIN',
  params: initialParams = {},
} = {}) {
  const user = ko.observable(role ? { id: role.toLowerCase() + '-1', role } : null);
  const session = {
    user,
    isAdmin: ko.pureComputed(() => user()?.role === 'ADMIN'),
    restore: async () => {},
  };
  const calls = [];
  const api = {
    adminStatisticsOptions: async () => {
      calls.push({ kind: 'options' });
      return optionsOverride
        ? optionsOverride()
        : {
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
    adminStatistics: async (query) => {
      calls.push({ kind: 'summary', query: { ...query } });
      return summaryOverride ? summaryOverride(query) : summary(query);
    },
    adminStatisticsPayments: async (query) => {
      calls.push({ kind: 'payments', query: { ...query } });
      return paymentsOverride ? paymentsOverride(query) : paymentPage(query);
    },
  };
  let vm;
  let parameterUpdates = 0;
  let currentRouteParams = { ...initialParams };
  const normalizedParams = (params) =>
    JSON.stringify(
      Object.fromEntries(
        Object.keys(params)
          .sort()
          .map((key) => [key, params[key]]),
      ),
    );
  const navigate = (path, params = {}) => {
    calls.push({ kind: 'navigate', path, params: { ...params } });
    if (
      path === 'admin-statistics' &&
      normalizedParams(params) !== normalizedParams(currentRouteParams)
    )
      vm.parametersChanged(params);
  };
  const Page = load(
    'ts/viewModels/admin-statistics.ts',
    {
      knockout: ko,
      '../services/flux-api': { fluxApi: api },
      '../services/session': { session, navigate },
      '../services/admin-statistics': helpers,
    },
    { Date, Intl, URLSearchParams },
  );
  vm = new Page({ params: initialParams });
  const parametersChanged = vm.parametersChanged.bind(vm);
  vm.parametersChanged = (params) => {
    parameterUpdates++;
    currentRouteParams = { ...params };
    parametersChanged(params);
  };
  return {
    vm,
    api,
    session,
    calls,
    get navigateCalls() {
      return calls.filter((call) => call.kind === 'navigate');
    },
    navigate,
    get parameterUpdates() {
      return parameterUpdates;
    },
    settle: async () => new Promise((resolve) => setImmediate(resolve)),
  };
}

module.exports = { deferred, summary, paymentRow, paymentPage, statuses, makePage };
