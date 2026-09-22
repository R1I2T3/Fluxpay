// viewModels/admin-policies.ts
import { resolveAdminEnvironment } from '../services/admin-console';
import { ComplianceWorkspace } from '../services/compliance-workspace';
import { navigate, session } from '../services/session';

class AdminPoliciesViewModel {
  session = session;
  workspace = new ComplianceWorkspace();
  environment = resolveAdminEnvironment(
    (window as any).FLUXPAY_ENVIRONMENT,
    window.location.hostname,
  );
  private policyId: string;
  constructor(params: any) {
    this.policyId = String(params?.params?.policyId || '');
    this.workspace.policySavedView(
      String(params?.params?.view || '').toUpperCase() === 'UNINDEXED' ? 'UNINDEXED' : 'ALL',
    );
    void this.activate();
  }
  private async activate() {
    if (!session.user()) await session.restore();
    if (!session.isAdmin()) return;
    await this.workspace.loadPolicies();
    if (this.policyId && this.workspace.policies().some((item) => item.id === this.policyId))
      await this.workspace.openPolicy({ id: this.policyId });
  }
  changeSavedView = () => navigate('admin-policies', { view: this.workspace.policySavedView() });
  disconnected() {
    this.workspace.dispose();
  }
}
export = AdminPoliciesViewModel;
