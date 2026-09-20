import * as ko from 'knockout';
import {
  fluxApi as api,
  RailDescriptor,
  TransferProvider,
  TransferRoute,
  CreateProviderRequest,
} from './flux-api';
import { session } from './session';

export const routingDestinations = ['INTERNAL_WALLET', 'EXTERNAL_ACCOUNT'];
const codePattern = /^[A-Z][A-Z0-9_]{2,49}$/;
const invalid = (message: string): never => {
  throw new Error(message);
};

function normalizeCode(value: string, label: string): string {
  const code = value.trim().toUpperCase();
  if (!codePattern.test(code))
    invalid(label + ' must start with a letter and use 3 to 50 letters, digits or underscores.');
  return code;
}

function nonNegative(value: string, label: string): number {
  if (!value.trim()) invalid(label + ' is required.');
  const parsed = Number(value);
  if (!Number.isFinite(parsed) || parsed < 0) invalid(label + ' must be zero or greater.');
  return parsed;
}

function positiveInt(value: string, label: string): number {
  if (!value.trim()) invalid(label + ' is required.');
  const parsed = Number(value);
  if (!Number.isInteger(parsed) || parsed <= 0)
    invalid(label + ' must be a whole number greater than zero.');
  return parsed;
}

function reliability(value: string): number {
  if (!value.trim()) invalid('Configured reliability is required.');
  const parsed = Number(value);
  if (!Number.isFinite(parsed) || parsed < 0 || parsed > 100)
    invalid('Configured reliability must be between 0 and 100.');
  return parsed;
}

function optionalLimit(value: string, label: string): number | null {
  if (!value.trim()) return null;
  const parsed = Number(value);
  if (!Number.isFinite(parsed) || parsed <= 0)
    invalid(label + ' must be greater than zero when set.');
  return parsed;
}

/** Administrator transfer-routing state. Isolated from customer flows like ComplianceWorkspace. */
export class RoutingWorkspace {
  session = session;
  busy = ko.observable(false);
  error = ko.observable('');
  notice = ko.observable('');
  railTypes = ko.observableArray<RailDescriptor>([]);
  providers = ko.observableArray<TransferProvider>([]);
  routes = ko.observableArray<TransferRoute>([]);
  section = ko.observable<'providers' | 'routes'>('providers');
  search = ko.observable('');
  providerFilter = ko.observable('ALL');
  destinationFilter = ko.observable('ALL');
  countryFilter = ko.observable('');
  currencyFilter = ko.observable('');
  statusFilter = ko.observable('ALL');
  destinations = routingDestinations;
  providerForm = ko.observable(false);
  providerEditTarget = ko.observable<TransferProvider>();
  providerCode = ko.observable('');
  providerName = ko.observable('');
  providerRail = ko.observable('');
  providerActive = ko.observable(true);
  providerError = ko.observable('');
  routeForm = ko.observable(false);
  routeEditTarget = ko.observable<TransferRoute>();
  routeProviderId = ko.observable('');
  routeCode = ko.observable('');
  routeName = ko.observable('');
  routeDestination = ko.observable('EXTERNAL_ACCOUNT');
  routeCountry = ko.observable('');
  routeCurrency = ko.observable('');
  routeFee = ko.observable('');
  routeSpread = ko.observable('');
  routeEta = ko.observable('');
  routeReliability = ko.observable('');
  routeMin = ko.observable('');
  routeMax = ko.observable('');
  routeActive = ko.observable(true);
  routeError = ko.observable('');
  pendingProvider = ko.observable<TransferProvider>();
  pendingRoute = ko.observable<TransferRoute>();
  confirmation = ko.observable<'delete-provider' | 'delete-route' | ''>('');
  filteredProviders = ko.pureComputed(() =>
    this.providers().filter((p) =>
      (p.providerCode + ' ' + p.providerName + ' ' + p.railType)
        .toLowerCase()
        .includes(this.search().toLowerCase()),
    ),
  );
  filteredRoutes = ko.pureComputed(() =>
    this.routes().filter(
      (r) =>
        (this.providerFilter() === 'ALL' || r.providerId === this.providerFilter()) &&
        (this.destinationFilter() === 'ALL' || r.destinationType === this.destinationFilter()) &&
        (!this.countryFilter().trim() ||
          (r.destinationCountry || '')
            .toUpperCase()
            .includes(this.countryFilter().trim().toUpperCase())) &&
        (!this.currencyFilter().trim() ||
          (r.payoutCurrency || '')
            .toUpperCase()
            .includes(this.currencyFilter().trim().toUpperCase())) &&
        (this.statusFilter() === 'ALL' ||
          (this.statusFilter() === 'ACTIVE' ? r.active : !r.active)) &&
        (
          r.routeCode +
          ' ' +
          r.name +
          ' ' +
          (r.destinationCountry || '') +
          ' ' +
          (r.payoutCurrency || '')
        )
          .toLowerCase()
          .includes(this.search().toLowerCase()),
    ),
  );
  compatibleProviders = ko.pureComputed(() => {
    const destination = this.routeDestination();
    if (!this.railTypes().length) return this.providers();
    return this.providers().filter((p) => {
      const rail = this.railTypes().find((entry) => entry.railType === p.railType);
      return !rail || rail.supportedDestinations.includes(destination);
    });
  });
  providerDisplayName = (id: string) =>
    this.providers().find((p) => p.id === id)?.providerName || '—';
  providerCount = (id: string) => this.routes().filter((r) => r.providerId === id).length;
  // Protection badges are driven by the server `systemProtected` flag on each record.
  providerProtected = (provider: { systemProtected?: boolean }) =>
    provider.systemProtected === true;
  routeProtected = (route: { systemProtected?: boolean }) =>
    route.systemProtected === true;
  private disposed = false;
  private epoch = 0;
  private sessionChanged = session.user.subscribe(() => {
    this.epoch++;
    this.clear();
  });
  private noticeChanged = this.notice.subscribe((value) => this.dismissToast(this.notice, value));
  private errorChanged = this.error.subscribe((value) => this.dismissToast(this.error, value));
  private clear() {
    this.railTypes([]);
    this.providers([]);
    this.routes([]);
    this.providerForm(false);
    this.providerEditTarget(undefined);
    this.providerError('');
    this.routeForm(false);
    this.routeEditTarget(undefined);
    this.routeError('');
    this.pendingProvider(undefined);
    this.pendingRoute(undefined);
    this.confirmation('');
    this.error('');
    this.notice('');
  }
  dispose() {
    this.disposed = true;
    this.epoch++;
    this.clear();
    this.sessionChanged.dispose();
    this.noticeChanged.dispose();
    this.errorChanged.dispose();
  }
  private dismissToast(target: ko.Observable<string>, value: string) {
    if (!value || typeof window === 'undefined' || !window.setTimeout) return;
    window.setTimeout(() => {
      if (!this.disposed && target() === value) target('');
    }, 5000);
  }
  async run(action: () => Promise<void>) {
    if (this.busy() || this.disposed) return;
    if (!session.isAdmin()) {
      this.error('An administrator account is required.');
      return;
    }
    this.busy(true);
    this.error('');
    this.notice('');
    const epoch = this.epoch;
    try {
      await action();
    } catch (e: any) {
      if (!this.disposed && epoch === this.epoch)
        this.error(e.message || 'The service could not complete this request.');
    } finally {
      if (this.disposed || epoch !== this.epoch) this.clear();
      this.busy(false);
    }
  }
  resetSearch() {
    this.search('');
    this.error('');
    this.notice('');
    this.confirmation('');
    this.providerError('');
    this.routeError('');
  }
  loadAll = () =>
    this.run(async () => {
      const [rails, providers, routes] = await Promise.all([
        api.railTypes(),
        api.providers(),
        api.routesAdmin(),
      ]);
      this.railTypes(rails);
      this.providers(providers);
      this.routes(routes);
    });
  loadProviders = () =>
    this.run(async () => {
      this.providers(await api.providers());
    });
  loadRoutes = () =>
    this.run(async () => {
      this.routes(await api.routesAdmin());
    });
  selectSection = (section: 'providers' | 'routes') => {
    if (!this.busy()) {
      this.section(section);
      this.resetSearch();
    }
  };
  newProvider = () => {
    if (this.busy()) return;
    this.providerEditTarget(undefined);
    this.providerCode('');
    this.providerName('');
    this.providerRail(this.railTypes()[0]?.railType || '');
    this.providerActive(true);
    this.providerError('');
    this.providerForm(true);
  };
  editProvider = (provider: TransferProvider) => {
    if (this.busy()) return;
    this.providerEditTarget(provider);
    this.providerCode(provider.providerCode);
    this.providerName(provider.providerName);
    this.providerRail(provider.railType);
    this.providerActive(provider.active);
    this.providerError('');
    this.providerForm(true);
  };
  closeProviderEditor = () => {
    if (!this.busy()) {
      this.providerForm(false);
      this.providerEditTarget(undefined);
      this.providerError('');
    }
  };
  private providerPayload(): CreateProviderRequest {
    const providerCode = normalizeCode(this.providerCode(), 'Provider code');
    const providerName = this.providerName().trim();
    if (!providerName) invalid('Enter a provider name.');
    const railType = this.providerRail().trim();
    if (!railType) invalid('Choose a rail type.');
    return { providerCode, providerName, railType, active: this.providerActive() };
  }
  saveProvider = () =>
    this.run(async () => {
      let body: CreateProviderRequest;
      try {
        body = this.providerPayload();
      } catch (e: any) {
        this.providerError(e.message || 'Enter valid provider details.');
        return;
      }
      const target = this.providerEditTarget();
      try {
        if (target) {
          const updated = await api.updateProvider(target.id, {
            providerName: body.providerName,
            railType: body.railType,
            active: body.active,
            version: target.version,
          });
          this.providers(this.providers().map((p) => (p.id === updated.id ? updated : p)));
        } else {
          this.providers([...this.providers(), await api.createProvider(body)]);
        }
      } catch (e: any) {
        const message = e.message || 'The provider could not be saved.';
        if (/STALE_PROVIDER/.test(message)) {
          this.providerError(
            'Stale version: another administrator changed this provider. Your entries were kept; the latest list is shown below.',
          );
          this.providers(await api.providers());
          return;
        }
        this.providerError(message);
        return;
      }
      this.providerForm(false);
      this.providerEditTarget(undefined);
      this.providerError('');
      this.notice(target ? 'Provider updated.' : 'Provider created.');
      this.providers(await api.providers());
      this.routes(await api.routesAdmin());
    });
  newRoute = () => {
    if (this.busy()) return;
    this.routeEditTarget(undefined);
    this.routeProviderId(this.compatibleProviders()[0]?.id || '');
    this.routeCode('');
    this.routeName('');
    this.routeDestination('EXTERNAL_ACCOUNT');
    this.routeCountry('');
    this.routeCurrency('');
    this.routeFee('');
    this.routeSpread('');
    this.routeEta('');
    this.routeReliability('');
    this.routeMin('');
    this.routeMax('');
    this.routeActive(true);
    this.routeError('');
    this.routeForm(true);
  };
  editRoute = (route: TransferRoute) => {
    if (this.busy()) return;
    this.routeEditTarget(route);
    this.routeProviderId(route.providerId);
    this.routeCode(route.routeCode);
    this.routeName(route.name);
    this.routeDestination(route.destinationType);
    this.routeCountry(route.destinationCountry || '');
    this.routeCurrency(route.payoutCurrency || '');
    this.routeFee(String(route.baseFee ?? ''));
    this.routeSpread(String(route.fxSpreadPercentage ?? ''));
    this.routeEta(String(route.estimatedMinutes ?? ''));
    this.routeReliability(String(route.configuredSuccessRate ?? ''));
    this.routeMin(route.minimumRecipientAmount == null ? '' : String(route.minimumRecipientAmount));
    this.routeMax(route.maximumRecipientAmount == null ? '' : String(route.maximumRecipientAmount));
    this.routeActive(route.active);
    this.routeError('');
    this.routeForm(true);
  };
  closeRouteEditor = () => {
    if (!this.busy()) {
      this.routeForm(false);
      this.routeEditTarget(undefined);
      this.routeError('');
    }
  };
  private routePayload() {
    const providerId = this.routeProviderId().trim();
    if (!providerId) invalid('Choose a provider for this route.');
    const routeCode = normalizeCode(this.routeCode(), 'Route code');
    const name = this.routeName().trim();
    if (!name) invalid('Enter a route name.');
    const destinationType = this.routeDestination().trim();
    if (destinationType !== 'INTERNAL_WALLET' && destinationType !== 'EXTERNAL_ACCOUNT')
      invalid('Choose a destination type.');
    const country = this.routeCountry().trim().toUpperCase();
    if (destinationType === 'EXTERNAL_ACCOUNT' && !/^[A-Z]{2}$/.test(country))
      invalid('Enter the two-letter ISO country for external routes.');
    if (country && !/^[A-Z]{2}$/.test(country)) invalid('Country uses a two-letter ISO code.');
    const payoutCurrency = this.routeCurrency().trim().toUpperCase();
    if (!/^[A-Z]{3}$/.test(payoutCurrency)) invalid('Enter the three-letter ISO payout currency.');
    const minimumRecipientAmount = optionalLimit(this.routeMin(), 'Minimum amount');
    const maximumRecipientAmount = optionalLimit(this.routeMax(), 'Maximum amount');
    if (
      minimumRecipientAmount !== null &&
      maximumRecipientAmount !== null &&
      maximumRecipientAmount < minimumRecipientAmount
    )
      invalid('The maximum amount must be greater than or equal to the minimum.');
    return {
      providerId,
      routeCode,
      name,
      destinationType,
      destinationCountry: country || null,
      payoutCurrency,
      baseFee: nonNegative(this.routeFee(), 'Base fee'),
      fxSpreadPercentage: nonNegative(this.routeSpread(), 'FX spread'),
      estimatedMinutes: positiveInt(this.routeEta(), 'Estimated minutes'),
      configuredSuccessRate: reliability(this.routeReliability()),
      minimumRecipientAmount,
      maximumRecipientAmount,
      active: this.routeActive(),
    };
  }
  saveRoute = () =>
    this.run(async () => {
      let body: ReturnType<RoutingWorkspace['routePayload']>;
      try {
        body = this.routePayload();
      } catch (e: any) {
        this.routeError(e.message || 'Enter valid route details.');
        return;
      }
      const target = this.routeEditTarget();
      try {
        if (target) {
          const { routeCode: _immutable, ...mutable } = body;
          void _immutable;
          const updated = await api.updateRoute(target.id, { ...mutable, version: target.version });
          this.routes(this.routes().map((r) => (r.id === updated.id ? updated : r)));
        } else {
          this.routes([...this.routes(), await api.createRoute(body)]);
        }
      } catch (e: any) {
        const message = e.message || 'The route could not be saved.';
        if (/STALE_ROUTE/.test(message)) {
          this.routeError(
            'Stale version: another administrator changed this route. Your entries were kept; the latest list is shown below.',
          );
          this.routes(await api.routesAdmin());
          return;
        }
        this.routeError(message);
        return;
      }
      this.routeForm(false);
      this.routeEditTarget(undefined);
      this.routeError('');
      this.notice(target ? 'Route updated.' : 'Route created.');
      this.routes(await api.routesAdmin());
    });
  requestDeleteProvider = (provider: TransferProvider) => {
    if (!this.busy()) {
      this.error('');
      this.pendingRoute(undefined);
      this.pendingProvider(provider);
      this.confirmation('delete-provider');
    }
  };
  requestDeleteRoute = (route: TransferRoute) => {
    if (!this.busy()) {
      this.error('');
      this.pendingProvider(undefined);
      this.pendingRoute(route);
      this.confirmation('delete-route');
    }
  };
  cancelConfirmation = () => {
    if (!this.busy()) {
      this.confirmation('');
      this.pendingProvider(undefined);
      this.pendingRoute(undefined);
    }
  };
  confirm = () =>
    this.run(async () => {
      const action = this.confirmation();
      if (action === 'delete-provider') {
        const target = this.pendingProvider();
        if (!target) throw new Error('Select a provider to delete.');
        const result = await api.deleteProvider(target.id, target.version);
        this.pendingProvider(undefined);
        this.confirmation('');
        this.notice(
          result.disposition === 'ARCHIVED'
            ? 'Provider archived. It stays visible for history but receives no new traffic.'
            : 'Provider deleted.',
        );
        this.providers(await api.providers());
        this.routes(await api.routesAdmin());
      } else if (action === 'delete-route') {
        const target = this.pendingRoute();
        if (!target) throw new Error('Select a route to delete.');
        const result = await api.deleteRoute(target.id, target.version);
        this.pendingRoute(undefined);
        this.confirmation('');
        this.notice(
          result.disposition === 'ARCHIVED'
            ? 'Route archived. It stays visible for history but receives no new traffic.'
            : 'Route deleted.',
        );
        this.routes(await api.routesAdmin());
      }
    });
}
