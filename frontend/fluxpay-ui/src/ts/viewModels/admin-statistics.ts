import * as ko from 'knockout';
import { fluxApi } from '../services/flux-api';
import { navigate, session } from '../services/session';
import {
  formatReportMoney,
  paymentQuery,
  presetRange,
  reportChartPath,
  resolveRouteState,
  toRouteParams,
} from '../services/admin-statistics';
import type {
  PaymentStatus,
  StatisticsOptions,
  StatisticsPaymentPage,
  StatisticsPaymentQuery,
  StatisticsRouteState,
  StatisticsSummary,
} from '../services/admin-statistics-contracts';

const messageOf = (error: unknown, fallback: string) => {
  const message = (error as { message?: unknown } | null)?.message;
  return typeof message === 'string' && message ? message : fallback;
};

class AdminStatisticsViewModel {
  session = session;
  options = ko.observable<StatisticsOptions | undefined>(undefined);
  state = ko.observable<StatisticsRouteState | undefined>(undefined);
  snapshot = ko.observable<StatisticsSummary | undefined>(undefined);
  pageData = ko.observable<StatisticsPaymentPage | undefined>(undefined);
  from = ko.observable('');
  to = ko.observable('');
  currency = ko.observable('');
  optionsBusy = ko.observable(false);
  busy = ko.observable(false);
  listBusy = ko.observable(false);
  optionsError = ko.observable('');
  error = ko.observable('');
  listError = ko.observable('');
  notice = ko.observable('');
  refreshStatus = ko.observable('');
  lastUpdated = ko.pureComputed(() => this.snapshot()?.meta.generatedAt || '');
  listPage = ko.pureComputed(() => this.pageData()?.page ?? this.state()?.page ?? 0);
  canPreviousPaymentPage = ko.pureComputed(() => !this.listBusy() && this.listPage() > 0);
  canNextPaymentPage = ko.pureComputed(() => {
    const page = this.pageData();
    if (this.listBusy() || !page) return false;
    return page.page + 1 < Math.max(1, page.totalPages);
  });
  beyondLastPage = ko.pureComputed(() => {
    const page = this.pageData();
    return !!page && page.page > 0 && page.items.length === 0;
  });
  listSelection = ko.pureComputed(() => {
    const state = this.state();
    if (!state?.showPayments) return '';
    const scope = state.day
      ? 'Payments created on ' + state.day
      : 'Payments created ' + state.from + ' to ' + state.to;
    return (
      scope +
      ', ' +
      state.currency +
      ' source currency, ' +
      (state.status ? 'status ' + state.status : 'every status')
    );
  });
  listTotals = ko.pureComputed(() => {
    const page = this.pageData();
    if (!page) return '';
    if (!page.items.length)
      return (
        'No rows on page ' +
        (page.page + 1) +
        ' · ' +
        page.totalElements +
        ' payments match this selection'
      );
    const first = page.page * page.size + 1;
    return (
      'Showing ' +
      first +
      '–' +
      (first + page.items.length - 1) +
      ' of ' +
      page.totalElements +
      ' payments · page ' +
      (page.page + 1) +
      ' of ' +
      Math.max(1, page.totalPages)
    );
  });
  listAnnouncement = ko.pureComputed(() => {
    const page = this.pageData();
    if (!page) return '';
    if (!page.items.length)
      return 'No payment records on this page of ' + page.totalElements + ' matching payments.';
    return (
      page.items.length + ' payment records loaded of ' + page.totalElements + ' matching payments.'
    );
  });
  ready: Promise<void>;

  private optionsGeneration = 0;
  private summaryGeneration = 0;
  private listGeneration = 0;
  private summaryReady: Promise<void> = Promise.resolve();
  private listReady: Promise<void> = Promise.resolve();
  private listKey = '';
  private disposed = false;
  private initializing = true;
  private initialized = false;
  private routeParams: Record<string, unknown> = {};
  private identityKey = '';
  private sessionChanged: { dispose(): void };

  constructor(params: { params?: Record<string, unknown> }) {
    this.routeParams = { ...(params?.params || {}) };
    this.sessionChanged = session.user.subscribe((user) => this.onSessionChanged(user));
    this.ready = this.initialize().finally(() => (this.initialized = true));
  }

  private currentIdentity(): string {
    const user = session.user();
    return session.isAdmin() && user?.id != null ? String(user.id) + ':ADMIN' : '';
  }

  private isOwner(accountId: unknown, generation: number, kind: 'options' | 'summary' | 'list') {
    const currentGeneration =
      kind === 'options'
        ? this.optionsGeneration
        : kind === 'summary'
          ? this.summaryGeneration
          : this.listGeneration;
    return (
      !this.disposed &&
      generation === currentGeneration &&
      session.isAdmin() &&
      session.user()?.id === accountId
    );
  }

  private invalidateAndClear() {
    this.optionsGeneration++;
    this.summaryGeneration++;
    this.listGeneration++;
    this.summaryReady = Promise.resolve();
    this.listReady = Promise.resolve();
    this.listKey = '';
    this.options(undefined);
    this.state(undefined);
    this.snapshot(undefined);
    this.pageData(undefined);
    this.from('');
    this.to('');
    this.currency('');
    this.optionsBusy(false);
    this.busy(false);
    this.listBusy(false);
    this.optionsError('');
    this.error('');
    this.listError('');
    this.notice('');
    this.refreshStatus('');
  }

  private onSessionChanged(user: any) {
    if (this.initializing || this.disposed) return;
    const nextIdentity = session.isAdmin() && user?.id != null ? String(user.id) + ':ADMIN' : '';
    if (nextIdentity === this.identityKey) return;
    this.identityKey = nextIdentity;
    this.invalidateAndClear();
    if (nextIdentity) this.ready = this.initialize();
  }

  private async initialize(): Promise<void> {
    if (!session.user()) await session.restore();
    this.initializing = false;
    this.identityKey = this.currentIdentity();
    if (this.disposed || !session.isAdmin()) return;
    await this.loadOptions();
    await this.settled();
  }

  private settled(): Promise<void> {
    return Promise.all([this.summaryReady, this.listReady]).then(() => undefined);
  }

  private async loadOptions(): Promise<void> {
    const generation = ++this.optionsGeneration;
    const accountId = session.user()?.id;
    const current = () => this.isOwner(accountId, generation, 'options');
    this.optionsBusy(true);
    this.optionsError('');
    try {
      const response = await fluxApi.adminStatisticsOptions();
      if (!current()) return;
      this.options(response);
      if (!response.currencies.length) {
        this.state(undefined);
        this.snapshot(undefined);
        this.pageData(undefined);
        this.error('');
        this.notice('');
        return;
      }
      this.resolveAndLoad(this.routeParams, true);
    } catch (error: unknown) {
      if (current()) this.optionsError(messageOf(error, 'Statistics options are unavailable.'));
    } finally {
      if (current()) this.optionsBusy(false);
    }
  }

  private resolveAndLoad(params: Record<string, unknown>, initial = false) {
    const currentOptions = this.options();
    if (!currentOptions || !currentOptions.currencies.length) return;
    try {
      const resolved = resolveRouteState(params, currentOptions);
      const previous = this.state();
      const next = resolved.state;
      const retryFailedSummary = !!this.error() && !this.snapshot();
      this.state(next);
      this.from(next.from);
      this.to(next.to);
      this.currency(next.currency);
      this.notice(resolved.notice);
      this.error('');
      const queryChanged =
        !previous ||
        previous.from !== next.from ||
        previous.to !== next.to ||
        previous.currency !== next.currency;
      if (queryChanged || initial || retryFailedSummary) {
        this.snapshot(undefined);
        this.pageData(undefined);
        this.refreshStatus('');
        this.summaryReady = this.loadSummary(
          { from: next.from, to: next.to, currency: next.currency },
          false,
        );
      }
      this.applyListSelection(next);
    } catch (error: unknown) {
      this.snapshot(undefined);
      this.pageData(undefined);
      this.summaryGeneration++;
      this.busy(false);
      this.listGeneration++;
      this.listBusy(false);
      this.listKey = '';
      this.error(messageOf(error, 'Statistics are unavailable.'));
      this.summaryReady = Promise.resolve();
      this.listReady = Promise.resolve();
    }
  }

  private applyListSelection(state: StatisticsRouteState) {
    if (!state.showPayments) {
      this.listGeneration++;
      this.listKey = '';
      this.pageData(undefined);
      this.listError('');
      this.listBusy(false);
      this.listReady = Promise.resolve();
      return;
    }
    const query = paymentQuery(state);
    const key = JSON.stringify(query);
    // An unchanged selection triggers no request, so any in-flight page stays in `ready`.
    if (key === this.listKey) return;
    this.listKey = key;
    this.pageData(undefined);
    this.listError('');
    this.listReady = this.loadPage(query);
  }

  private async loadSummary(
    filters: { from: string; to: string; currency: string },
    refreshing: boolean,
  ) {
    const generation = ++this.summaryGeneration;
    const accountId = session.user()?.id;
    const current = () => this.isOwner(accountId, generation, 'summary');
    this.busy(true);
    this.error('');
    this.refreshStatus(refreshing ? 'Refreshing statistics…' : '');
    try {
      const response = await fluxApi.adminStatistics(filters);
      if (!current()) return;
      this.snapshot(response);
      this.refreshStatus('');
    } catch (error: unknown) {
      if (current()) {
        const message = messageOf(error, 'Statistics are unavailable.');
        this.error(message);
        this.refreshStatus(refreshing ? 'Refresh failed: ' + message : '');
      }
    } finally {
      if (current()) this.busy(false);
    }
  }

  private async loadPage(query: StatisticsPaymentQuery) {
    const generation = ++this.listGeneration;
    const accountId = session.user()?.id;
    const current = () => this.isOwner(accountId, generation, 'list');
    this.listBusy(true);
    this.listError('');
    try {
      const response = await fluxApi.adminStatisticsPayments(query);
      if (!current()) return;
      this.pageData(response);
    } catch (error: unknown) {
      if (current()) this.listError(messageOf(error, 'Payment records are unavailable.'));
    } finally {
      if (current()) this.listBusy(false);
    }
  }

  parametersChanged(params: Record<string, unknown>): void {
    if (this.disposed) return;
    this.routeParams = { ...params };
    if (!session.isAdmin() || !this.options()) return;
    this.resolveAndLoad(this.routeParams);
    this.ready = this.settled();
  }

  selectPreset(days: 1 | 7 | 30 | 90): void {
    const options = this.options();
    if (!options) return;
    const range = presetRange(options.today, days);
    this.from(range.from);
    this.to(range.to);
    this.applyFilters();
  }

  applyFilters(): void {
    if (!session.isAdmin() || !this.options()) return;
    const current = this.state();
    const from = this.from();
    const to = this.to();
    if (
      current &&
      from === current.from &&
      to === current.to &&
      this.currency() === current.currency
    ) {
      if (this.error() || (!this.snapshot() && !this.busy())) {
        this.summaryReady = this.loadSummary(
          { from: current.from, to: current.to, currency: current.currency },
          false,
        );
        this.ready = this.settled();
      }
      return;
    }
    const open = !!current?.showPayments;
    const day = current?.day;
    const next: StatisticsRouteState = {
      from,
      to,
      currency: this.currency(),
      showPayments: open,
      ...(open && current?.status ? { status: current.status } : {}),
      ...(open && day && day >= from && day <= to ? { day } : {}),
      page: 0,
    };
    navigate('admin-statistics', toRouteParams(next));
  }

  async retryOptions(): Promise<void> {
    if (!session.isAdmin() || this.disposed) return;
    this.invalidateAndClear();
    await this.loadOptions();
    await this.settled();
  }

  async refresh(): Promise<void> {
    const current = this.state();
    if (!session.isAdmin() || !current || this.disposed) return;
    this.summaryReady = this.loadSummary(current, true);
    if (current.showPayments) this.listReady = this.loadPage(paymentQuery(current));
    await this.settled();
  }

  formatMoney(amount: string, currencyCode?: string): string {
    const options = this.options();
    const code = currencyCode || this.snapshot()?.meta.currency || this.currency();
    const scale =
      options?.currencies.find((item) => item.code === code)?.scale ??
      this.snapshot()?.meta.currencyScale ??
      2;
    try {
      return formatReportMoney(amount, code, scale);
    } catch {
      return code + ' ' + amount;
    }
  }

  formatRate(value: number | null, emptyLabel: string): string {
    return value === null ? emptyLabel : value.toFixed(2) + '%';
  }

  paymentCountPath(): string {
    return reportChartPath(
      (this.snapshot()?.paymentTrend || []).map((day) => day.paymentCount),
      552,
      170,
    );
  }

  private chartPoints(
    values: number[],
    dates: string[],
    labels: string[],
  ): Array<{ x: number; y: number; date: string; value: number; label: string }> {
    if (!values.length) return [];
    const width = 552;
    const height = 170;
    const pad = 8;
    const xOffset = 24;
    const finite = values.map((value) => (Number.isFinite(value) ? Math.max(0, value) : 0));
    const maximum = Math.max(1, ...finite);
    return finite.map((value, index) => {
      const x = xOffset + pad + (index * (width - 2 * pad)) / Math.max(1, finite.length - 1);
      const y = height - pad - (value / maximum) * (height - pad * 2);
      return {
        x: Math.round(x * 100) / 100,
        y: Math.round(y * 100) / 100,
        date: dates[index] ?? '',
        value,
        label: labels[index] ?? String(values[index] ?? ''),
      };
    });
  }

  paymentCountPoints(): Array<{
    x: number;
    y: number;
    date: string;
    value: number;
    label: string;
  }> {
    const trend = this.snapshot()?.paymentTrend || [];
    return this.chartPoints(
      trend.map((day) => day.paymentCount),
      trend.map((day) => day.date),
      trend.map((day) => day.date + ': ' + day.paymentCount + ' payments'),
    );
  }

  paymentAmountPoints(): Array<{
    x: number;
    y: number;
    date: string;
    value: number;
    label: string;
  }> {
    const snapshot = this.snapshot();
    const trend = snapshot?.paymentTrend || [];
    const currency = snapshot?.meta.currency || this.currency();
    return this.chartPoints(
      trend.map((day) => Number(day.completedAmount)),
      trend.map((day) => day.date),
      trend.map((day) => day.date + ': ' + this.formatMoney(day.completedAmount, currency)),
    );
  }

  customerRegistrationPoints(): Array<{
    x: number;
    y: number;
    date: string;
    value: number;
    label: string;
  }> {
    const trend = this.snapshot()?.customerTrend || [];
    return this.chartPoints(
      trend.map((day) => day.registrations),
      trend.map((day) => day.date),
      trend.map((day) => day.date + ': ' + day.registrations + ' registrations'),
    );
  }

  statusShare(count: number): number {
    const total = this.snapshot()?.paymentSummary.paymentCount ?? 0;
    if (!Number.isFinite(count) || total <= 0 || count <= 0) return 0;
    return Math.min(100, Math.max(0, (count / total) * 100));
  }

  rateWidth(rate: number | null): string {
    if (rate === null || !Number.isFinite(rate)) return '0%';
    return Math.min(100, Math.max(0, rate)) + '%';
  }

  isPresetActive(days: 1 | 7 | 30 | 90): boolean {
    const currentOptions = this.options();
    if (!currentOptions) return false;
    try {
      const range = presetRange(currentOptions.today, days);
      return this.from() === range.from && this.to() === range.to;
    } catch {
      return false;
    }
  }

  openCountDay(day: { date: string }): void {
    if (!day?.date) return;
    this.openPayments(undefined, day.date);
  }

  openAmountDay(day: { date: string }): void {
    if (!day?.date) return;
    this.openPayments('COMPLETED', day.date);
  }

  paymentAmountPath(): string {
    return reportChartPath(
      (this.snapshot()?.paymentTrend || []).map((day) => Number(day.completedAmount)),
      552,
      170,
    );
  }

  customerRegistrationPath(): string {
    return reportChartPath(
      (this.snapshot()?.customerTrend || []).map((day) => day.registrations),
      552,
      170,
    );
  }

  private chartArea(path: string): string {
    if (!path) return '';
    const xOffset = 24;
    const shifted = path.replace(/(M|L)(\d+\.\d+),/g, (_, prefix: string, x: string) => {
      const shiftedX = (parseFloat(x) + xOffset).toFixed(2);
      return prefix + shiftedX + ',';
    });
    const first = 8 + xOffset;
    const last = 544 + xOffset;
    const base = 162;
    return (
      shifted +
      ' L' +
      last.toFixed(2) +
      ',' +
      base.toFixed(2) +
      ' L' +
      first.toFixed(2) +
      ',' +
      base.toFixed(2) +
      ' Z'
    );
  }

  paymentCountArea(): string {
    return this.chartArea(this.paymentCountPath());
  }

  paymentAmountArea(): string {
    return this.chartArea(this.paymentAmountPath());
  }

  customerRegistrationArea(): string {
    return this.chartArea(this.customerRegistrationPath());
  }

  private chartTicks(values: number[], precision = 0): string[] {
    const maximum = Math.max(
      1,
      ...values.map((value) => (Number.isFinite(value) ? Math.max(0, value) : 0)),
    );
    return [maximum, maximum / 2, 0].map((value) =>
      precision
        ? value.toFixed(precision)
        : Number.isInteger(value)
          ? String(value)
          : value.toFixed(1),
    );
  }

  paymentCountTicks(): string[] {
    return this.chartTicks((this.snapshot()?.paymentTrend || []).map((day) => day.paymentCount));
  }

  paymentAmountTicks(): string[] {
    return this.chartTicks(
      (this.snapshot()?.paymentTrend || []).map((day) => Number(day.completedAmount)),
      2,
    );
  }

  customerRegistrationTicks(): string[] {
    return this.chartTicks((this.snapshot()?.customerTrend || []).map((day) => day.registrations));
  }

  openPayments(status?: PaymentStatus, day?: string): void {
    const current = this.state();
    if (!current) return;
    navigate(
      'admin-statistics',
      toRouteParams({ ...current, showPayments: true, status, day, page: 0 }),
    );
  }

  closePayments(): void {
    const current = this.state();
    if (!current?.showPayments) return;
    navigate('admin-statistics', toRouteParams({ ...current, showPayments: false, page: 0 }));
  }

  goToPage(page: number): void {
    const current = this.state();
    if (!current?.showPayments || !Number.isInteger(page) || page < 0) return;
    navigate('admin-statistics', toRouteParams({ ...current, page }));
  }

  openOperations(row: StatisticsPaymentPage['items'][number]): void {
    navigate('admin-payment-operations', { paymentId: row.paymentId });
  }

  async retryPayments(): Promise<void> {
    const current = this.state();
    if (!current?.showPayments || !session.isAdmin() || this.disposed) return;
    this.listKey = '';
    this.applyListSelection(current);
    await this.settled();
  }

  formatTime(value: string | null | undefined): string {
    const date = new Date(value || '');
    if (Number.isNaN(date.getTime())) return value || '—';
    const zone = this.pageData()?.meta.reportingZone || this.options()?.reportingZone;
    try {
      return new Intl.DateTimeFormat('en-GB', {
        day: '2-digit',
        month: 'short',
        year: 'numeric',
        hour: '2-digit',
        minute: '2-digit',
        hour12: false,
        timeZone: zone,
        timeZoneName: 'short',
      }).format(date);
    } catch {
      return date.toISOString();
    }
  }

  disconnected(): void {
    this.disposed = true;
    this.invalidateAndClear();
    this.sessionChanged.dispose();
    this.lastUpdated.dispose();
    this.listPage.dispose();
    this.canPreviousPaymentPage.dispose();
    this.canNextPaymentPage.dispose();
    this.beyondLastPage.dispose();
    this.listSelection.dispose();
    this.listTotals.dispose();
    this.listAnnouncement.dispose();
  }
}

export = AdminStatisticsViewModel;
