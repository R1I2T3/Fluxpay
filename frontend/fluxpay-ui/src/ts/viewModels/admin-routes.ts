// viewModels/admin-routes.ts
import * as ko from 'knockout';
import { resolveAdminEnvironment } from '../services/admin-console';
import '../services/admin-dialog';
import { RoutingWorkspace } from '../services/routing-workspace';
import {
  buildCorridorMatrix,
  buildProviderComparison,
  evaluateRouteEligibility,
  routeNeedsAttention,
} from '../services/route-analysis';
import { navigate, session } from '../services/session';

class AdminRoutesViewModel {
  session = session;
  workspace = new RoutingWorkspace();
  environment = resolveAdminEnvironment(
    (window as any).FLUXPAY_ENVIRONMENT,
    window.location.hostname,
  );
  mode = ko.observable<'CATALOGUE' | 'MATRIX' | 'COMPARE' | 'PREVIEW'>('CATALOGUE');
  attentionOnly = ko.observable(false);
  previewCountry = ko.observable('');
  previewCurrency = ko.observable('');
  previewAmount = ko.observable('');
  previewDestination = ko.observable('EXTERNAL_ACCOUNT');
  previewSubmitted = ko.observable(false);
  catalogueRoutes = ko.pureComputed(() =>
    this.workspace.filteredRoutes().filter(
      (route) =>
        !this.attentionOnly() ||
        routeNeedsAttention(
          route,
          this.workspace.providers().find((provider) => provider.id === route.providerId),
        ),
    ),
  );
  matrix = ko.pureComputed(() =>
    buildCorridorMatrix(this.workspace.routes(), this.workspace.providers()),
  );
  comparison = ko.pureComputed(() =>
    buildProviderComparison(this.workspace.providers(), this.workspace.routes()),
  );
  preview = ko.pureComputed(() =>
    evaluateRouteEligibility(
      {
        country: this.previewCountry(),
        currency: this.previewCurrency(),
        amount: this.previewAmount(),
        destinationType: this.previewDestination(),
      },
      this.workspace.routes(),
      this.workspace.providers(),
      this.workspace.railTypes(),
    ),
  );
  private routeId: string;
  constructor(params: any) {
    const mode = String(params?.params?.view || 'CATALOGUE').toUpperCase();
    if (['CATALOGUE', 'MATRIX', 'COMPARE', 'PREVIEW'].includes(mode)) this.mode(mode as any);
    this.attentionOnly(String(params?.params?.status || '').toUpperCase() === 'ATTENTION');
    this.routeId = String(params?.params?.routeId || '');
    void this.activate();
  }
  private async activate() {
    if (!session.user()) await session.restore();
    if (!session.isAdmin()) return;
    await this.workspace.loadAll();
    const route = this.workspace.routes().find((item) => item.id === this.routeId);
    if (route) this.workspace.editRoute(route);
  }
  changeMode = () =>
    navigate('admin-routes', {
      view: this.mode(),
      ...(this.attentionOnly() ? { status: 'ATTENTION' } : {}),
    });
  runPreview = () => {
    this.previewSubmitted(true);
    return false;
  };
  disconnected() {
    this.workspace.dispose();
  }
}
export = AdminRoutesViewModel;
