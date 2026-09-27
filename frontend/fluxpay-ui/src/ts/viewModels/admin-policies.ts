// viewModels/admin-policies.ts
import { copyAdminIdentifier, resolveAdminEnvironment } from '../services/admin-console';
import '../services/admin-dialog';
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
  copyIdentifier = async (value: string | null | undefined, label: string) => {
    this.workspace.error('');
    this.workspace.notice('');
    try {
      this.workspace.notice(await copyAdminIdentifier(value, label, navigator.clipboard));
    } catch (error: any) {
      this.workspace.error(error.message || 'Unable to copy ' + label.toLowerCase() + '.');
    }
  };
  copyGuidanceCaseId = () =>
    this.copyIdentifier(this.workspace.guidanceCaseViewer()?.id, 'Case ID');
  copyGuidancePaymentId = () =>
    this.copyIdentifier(this.workspace.guidanceCaseViewer()?.paymentId, 'Payment ID');
  disconnected() {
    this.workspace.dispose();
  }
}
export = AdminPoliciesViewModel;
