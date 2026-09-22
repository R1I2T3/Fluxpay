// viewModels/admin-providers.ts
import { resolveAdminEnvironment } from '../services/admin-console';
import { RoutingWorkspace } from '../services/routing-workspace';
import { session } from '../services/session';

class AdminProvidersViewModel {
  session = session;
  environment = resolveAdminEnvironment(
    (window as any).FLUXPAY_ENVIRONMENT,
    window.location.hostname,
  );
  workspace = new RoutingWorkspace();
  constructor() {
    void this.activate();
  }
  private async activate() {
    if (!session.user()) await session.restore();
    if (session.isAdmin()) await this.workspace.loadAll();
  }
  disconnected() {
    this.workspace.dispose();
  }
}
export = AdminProvidersViewModel;
