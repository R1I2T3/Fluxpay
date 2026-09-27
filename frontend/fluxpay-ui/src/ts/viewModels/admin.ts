// viewModels/admin.ts
import * as ko from 'knockout';
import { deriveAdminOverview } from '../services/admin-overview';
import { fluxApi, ticketApi } from '../services/flux-api';
import { navigate, session } from '../services/session';

type SourceStatus<T> = { status: 'loading' | 'ready' | 'error'; data: T; error: string };
type SourceKey = 'kyc' | 'compliance' | 'tickets' | 'providers' | 'routes' | 'policies';
const source = <T>(empty: T) =>
  ko.observable<SourceStatus<T>>({ status: 'loading', data: empty, error: '' });

class AdminOverviewViewModel {
  session = session;
  private epoch = 0;
  private disposed = false;
  private sourceEpoch: Record<SourceKey, number> = {
    kyc: 0,
    compliance: 0,
    tickets: 0,
    providers: 0,
    routes: 0,
    policies: 0,
  };
  sources = {
    kyc: source<any[]>([]),
    compliance: source<any[]>([]),
    tickets: source<any[]>([]),
    providers: source<any[]>([]),
    routes: source<any[]>([]),
    policies: source<any[]>([]),
  };
  overview = ko.pureComputed(() =>
    deriveAdminOverview({
      kyc: this.sources.kyc().data,
      cases: this.sources.compliance().data,
      tickets: this.sources.tickets().data,
      providers: this.sources.providers().data,
      routes: this.sources.routes().data,
      policies: this.sources.policies().data,
      now: Date.now(),
    }),
  );
  metrics = ko.pureComputed(() =>
    this.overview().metrics.map((metric) => {
      const states = metric.sourceKeys.map((key) => (this.sources as any)[key]().status);
      const readyMetric = { ...metric, unavailable: false, pending: false };
      return states.includes('error')
        ? { ...readyMetric, value: 'Unavailable', unavailable: true }
        : states.includes('loading')
          ? { ...readyMetric, value: 'Loading…', pending: true }
          : readyMetric;
    }),
  );
  rows = ko.pureComputed(() =>
    this.overview().rows.filter((row) =>
      row.sourceKeys.every((key) => (this.sources as any)[key]().status === 'ready'),
    ),
  );
  sourceEntries = ko.pureComputed(() => Object.entries(this.sources));
  loadAnnouncement = ko.pureComputed(() => {
    const states = this.sourceEntries().map((entry) => entry[1]().status);
    return states.includes('loading')
      ? 'Loading administrator attention data.'
      : states.includes('error')
        ? 'Overview loaded with unavailable sources.'
        : 'Overview data loaded.';
  });
  private sessionChanged = session.user.subscribe((user) => {
    if (!user) {
      this.epoch++;
      (Object.values(this.sources) as Array<ko.Observable<SourceStatus<any[]>>>).forEach((target) =>
        target({ status: 'loading', data: [], error: '' }),
      );
    }
  });
  constructor() {
    void this.loadOverview();
  }
  async loadOverview() {
    if (!session.user()) await session.restore();
    if (!session.isAdmin()) return;
    const epoch = ++this.epoch;
    (Object.values(this.sources) as Array<ko.Observable<SourceStatus<any[]>>>).forEach((target) =>
      target({ ...target(), status: 'loading', error: '' }),
    );
    const requests: Array<Promise<any[]>> = [
      fluxApi.adminKyc('PENDING', 0, 100),
      fluxApi.complianceCases('OPEN'),
      ticketApi.listForAdmin('ALL', 0, 100).then((result) => result.items || []),
      fluxApi.providers(),
      fluxApi.routesAdmin(),
      fluxApi.policies(),
    ];
    const keys = ['kyc', 'compliance', 'tickets', 'providers', 'routes', 'policies'] as const;
    const results = await Promise.allSettled(requests);
    if (this.disposed || epoch !== this.epoch || !session.isAdmin()) return;
    results.forEach((result, index) => {
      const target = this.sources[keys[index]] as ko.Observable<SourceStatus<any[]>>;
      target(
        result.status === 'fulfilled'
          ? { status: 'ready' as const, data: result.value, error: '' }
          : {
              status: 'error' as const,
              data: [],
              error: (result.reason as any)?.message || 'This source is unavailable.',
            },
      );
    });
  }
  async retrySource(key: SourceKey) {
    if (!session.isAdmin()) return;
    const epoch = this.epoch,
      sourceEpoch = ++this.sourceEpoch[key],
      target = this.sources[key];
    target({ ...target(), status: 'loading', error: '' });
    try {
      const data =
        key === 'kyc'
          ? await fluxApi.adminKyc('PENDING', 0, 100)
          : key === 'compliance'
            ? await fluxApi.complianceCases('OPEN')
            : key === 'tickets'
              ? (await ticketApi.listForAdmin('ALL', 0, 100)).items || []
              : key === 'providers'
                ? await fluxApi.providers()
                : key === 'routes'
                  ? await fluxApi.routesAdmin()
                  : await fluxApi.policies();
      if (
        !this.disposed &&
        epoch === this.epoch &&
        sourceEpoch === this.sourceEpoch[key] &&
        session.isAdmin()
      )
        target({ status: 'ready', data, error: '' });
    } catch (error: any) {
      if (
        !this.disposed &&
        epoch === this.epoch &&
        sourceEpoch === this.sourceEpoch[key] &&
        session.isAdmin()
      )
        target({
          status: 'error',
          data: [],
          error: error.message || 'This source is unavailable.',
        });
    }
  }
  open = (item: {
    path: string;
    params: Record<string, string>;
    unavailable?: boolean;
    pending?: boolean;
  }) => {
    if (!item.unavailable && !item.pending) navigate(item.path, item.params);
  };
  disconnected() {
    this.disposed = true;
    this.epoch++;
    this.sessionChanged.dispose();
  }
}
export = AdminOverviewViewModel;
