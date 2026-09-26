import {
  PaymentStatus,
  ResolvedStatisticsRoute,
  StatisticsFilters,
  StatisticsOptions,
  StatisticsPaymentQuery,
  StatisticsRouteState,
} from './admin-statistics-contracts';

const PAYMENT_STATUSES: PaymentStatus[] = [
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
const DAY_MS = 24 * 60 * 60 * 1000;

function validDate(value: unknown): value is string {
  if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(value)) return false;
  const date = new Date(value + 'T00:00:00.000Z');
  return !Number.isNaN(date.getTime()) && date.toISOString().slice(0, 10) === value;
}

function requiredDate(value: unknown, label: string): string {
  if (!validDate(value)) throw new Error(`Use a valid ${label} calendar date.`);
  return value;
}

export function presetRange(today: string, days: 1 | 7 | 30 | 90): { from: string; to: string } {
  if (!validDate(today)) throw new Error('Use a valid calendar date.');
  const end = new Date(today + 'T00:00:00.000Z');
  const start = new Date(end.getTime());
  start.setUTCDate(start.getUTCDate() - days + 1);
  return { from: start.toISOString().slice(0, 10), to: today };
}

function normalizeStatus(value: unknown): PaymentStatus | undefined {
  if (value === undefined) return undefined;
  if (typeof value !== 'string') throw new Error('Choose a valid payment status.');
  const normalized = value.toUpperCase();
  if (!(PAYMENT_STATUSES as string[]).includes(normalized))
    throw new Error('Choose a valid payment status.');
  return normalized as PaymentStatus;
}

export function resolveRouteState(
  params: Record<string, unknown>,
  options: StatisticsOptions,
): ResolvedStatisticsRoute {
  const today = requiredDate(options.today, 'server reporting');
  if (!options.currencies.length) throw new Error('No reporting currency is configured.');
  const currencies = options.currencies.map(({ code }) => code.toUpperCase());
  const configuredDefault = options.defaultCurrency?.toUpperCase();
  const defaultCurrency =
    configuredDefault && currencies.includes(configuredDefault) ? configuredDefault : currencies[0];

  const hasFrom = params.from !== undefined;
  const hasTo = params.to !== undefined;
  if (hasFrom !== hasTo) throw new Error('Choose both a start and end date.');
  const range = hasFrom
    ? { from: requiredDate(params.from, 'start'), to: requiredDate(params.to, 'end') }
    : presetRange(today, 30);
  if (range.from > range.to) throw new Error('The start date must not follow the end date.');
  if (range.to > today) throw new Error('The reporting range cannot include a future date.');
  const maximumDays = Math.min(366, options.maximumRangeDays);
  const rangeDays =
    (Date.parse(range.to + 'T00:00:00.000Z') - Date.parse(range.from + 'T00:00:00.000Z')) / DAY_MS +
    1;
  if (rangeDays > maximumDays)
    throw new Error(`Choose a range of no more than ${maximumDays} days.`);

  const requestedCurrency =
    params.currency === undefined
      ? defaultCurrency
      : typeof params.currency === 'string'
        ? params.currency.toUpperCase()
        : '';
  if (!requestedCurrency) throw new Error('Choose a reporting currency.');
  const removedCurrency = !currencies.includes(requestedCurrency);
  const currency = removedCurrency ? defaultCurrency : requestedCurrency;
  if (removedCurrency) {
    return {
      state: { ...range, currency, showPayments: false, page: 0 },
      notice: 'The saved reporting currency is no longer available. Showing the default currency.',
    };
  }

  const requestedStatus = normalizeStatus(params.status);
  const requestedDay = params.day === undefined ? undefined : requiredDate(params.day, 'drilldown');
  let showPayments: boolean;
  if (
    params.showPayments === undefined ||
    params.showPayments === false ||
    params.showPayments === '0'
  )
    showPayments = false;
  else if (params.showPayments === true || params.showPayments === '1') showPayments = true;
  else throw new Error('Choose a valid payment list setting.');
  const rawPage = params.page;
  if (rawPage !== undefined && (typeof rawPage !== 'string' || !/^\d+$/.test(rawPage)))
    throw new Error('Choose a valid payment page.');
  const parsedPage = rawPage === undefined ? 0 : Number(rawPage);
  if (!Number.isInteger(parsedPage) || parsedPage < 0 || parsedPage > 2147483647)
    throw new Error('Choose a valid payment page.');
  if (requestedDay && (requestedDay < range.from || requestedDay > range.to))
    throw new Error('The selected day must be inside the reporting range.');

  return {
    state: {
      ...range,
      currency,
      showPayments,
      ...(showPayments && requestedStatus ? { status: requestedStatus } : {}),
      ...(showPayments && requestedDay ? { day: requestedDay } : {}),
      page: showPayments ? parsedPage : 0,
    },
    notice: '',
  };
}

export function toRouteParams(state: StatisticsRouteState): Record<string, string> {
  return {
    from: state.from,
    to: state.to,
    currency: state.currency,
    ...(state.showPayments ? { showPayments: '1' } : {}),
    ...(state.showPayments && state.status ? { status: state.status } : {}),
    ...(state.showPayments && state.day ? { day: state.day } : {}),
    ...(state.showPayments ? { page: String(state.page) } : {}),
  };
}

export function paymentQuery(state: StatisticsRouteState): StatisticsPaymentQuery {
  return {
    from: state.day || state.from,
    to: state.day || state.to,
    currency: state.currency,
    ...(state.status ? { status: state.status } : {}),
    page: state.page,
    size: 20,
  };
}

export function statisticsSearch(query: StatisticsFilters | StatisticsPaymentQuery): string {
  const params = new URLSearchParams();
  params.set('from', query.from);
  params.set('to', query.to);
  params.set('currency', query.currency);
  if ('status' in query && query.status) params.set('status', query.status);
  if ('page' in query) params.set('page', String(query.page));
  if ('size' in query) params.set('size', String(query.size));
  return params.toString();
}

export function formatReportMoney(amount: string, currency: string, scale: number): string {
  const match = /^(\d+)(?:\.(\d+))?$/.exec(amount);
  if (!match || !Number.isInteger(scale) || scale < 0 || scale > 4)
    throw new Error('Invalid report amount.');
  const fraction = match[2] || '';
  if (fraction.length > scale && /[1-9]/.test(fraction.slice(scale)))
    throw new Error('Report amount does not match its currency scale.');
  const whole = match[1].replace(/^0+(?=\d)/, '').replace(/\B(?=(\d{3})+(?!\d))/g, ',');
  const decimals = scale ? '.' + fraction.padEnd(scale, '0').slice(0, scale) : '';
  return currency + ' ' + whole + decimals;
}

export function reportChartPath(values: number[], width = 600, height = 150): string {
  if (!values.length) return '';
  const pad = 8;
  const finiteValues = values.map((value) => (Number.isFinite(value) ? Math.max(0, value) : 0));
  const maximum = Math.max(1, ...finiteValues);
  return finiteValues
    .map((value, index) => {
      const x = pad + (index * (width - 2 * pad)) / Math.max(1, finiteValues.length - 1);
      const y = height - pad - (value / maximum) * (height - pad * 2);
      return `${index ? 'L' : 'M'}${x.toFixed(2)},${y.toFixed(2)}`;
    })
    .join(' ');
}
