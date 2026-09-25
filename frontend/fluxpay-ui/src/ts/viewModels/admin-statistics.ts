import * as ko from 'knockout';
import { fluxApi } from '../services/flux-api';
import { navigate, session } from '../services/session';
import {
  formatReportMoney,
  presetRange,
  reportChartPath,
  resolveRouteState,
  toRouteParams,
} from '../services/admin-statistics';
import type {
  StatisticsOptions,
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
  pageData = ko.observable<undefined>(undefined);
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
  ready: Promise<void>;

  private optionsGeneration = 0;
  private summaryGeneration = 0;
  private listGeneration = 0;
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
    await this.summaryReady;
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

  private summaryReady: Promise<void> = Promise.resolve();

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
    } catch (error: unknown) {
      this.snapshot(undefined);
      this.pageData(undefined);
      this.summaryGeneration++;
      this.busy(false);
      this.error(messageOf(error, 'Statistics are unavailable.'));
      this.summaryReady = Promise.resolve();
    }
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

  parametersChanged(params: Record<string, unknown>): void {
    if (this.disposed) return;
    this.routeParams = { ...params };
    if (!session.isAdmin() || !this.options()) return;
    this.resolveAndLoad(this.routeParams);
    this.ready = this.summaryReady;
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
    const next: StatisticsRouteState = {
      from: this.from(),
      to: this.to(),
      currency: this.currency(),
      showPayments: false,
      page: 0,
    };
    if (current?.showPayments && current.status) delete next.status;
    navigate('admin-statistics', toRouteParams(next));
  }

  async retryOptions(): Promise<void> {
    if (!session.isAdmin() || this.disposed) return;
    this.invalidateAndClear();
    await this.loadOptions();
    await this.summaryReady;
  }

  async refresh(): Promise<void> {
    const current = this.state();
    if (!session.isAdmin() || !current || this.disposed) return;
    await this.loadSummary(current, true);
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
      600,
      170,
    );
  }

  paymentAmountPath(): string {
    return reportChartPath(
      (this.snapshot()?.paymentTrend || []).map((day) => Number(day.completedAmount)),
      600,
      170,
    );
  }

  customerRegistrationPath(): string {
    return reportChartPath(
      (this.snapshot()?.customerTrend || []).map((day) => day.registrations),
      600,
      170,
    );
  }

  private chartTicks(values: number[], precision = 0): string[] {
    const maximum = Math.max(0, ...values.map((value) => (Number.isFinite(value) ? value : 0)));
    return [maximum, maximum / 2, 0].map((value) =>
      precision ? value.toFixed(precision) : String(Math.round(value)),
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

  selectDay(date: string): void {
    const current = this.state();
    if (!current) return;
    const next: StatisticsRouteState = { ...current, showPayments: true, day: date, page: 0 };
    navigate('admin-statistics', toRouteParams(next));
  }

  disconnected(): void {
    this.disposed = true;
    this.invalidateAndClear();
    this.sessionChanged.dispose();
    this.lastUpdated.dispose();
  }
}

export = AdminStatisticsViewModel;
