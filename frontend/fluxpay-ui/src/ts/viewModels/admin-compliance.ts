// viewModels/admin-compliance.ts
import * as ko from 'knockout';
import { focusRecordHeading } from '../services/admin-console';
import '../services/admin-dialog';
import { ComplianceWorkspace } from '../services/compliance-workspace';
import { navigate, session } from '../services/session';

class AdminComplianceViewModel {
  session = session;
  workspace = new ComplianceWorkspace();
  private caseId: string;
  constructor(params: any) {
    const view = String(params?.params?.view || 'OPEN').toUpperCase();
    if (['HIGH_RISK', 'REQUOTE_REQUIRED', 'REVIEW_EXPIRING', 'OPEN', 'COMPLETED'].includes(view))
      this.workspace.savedCaseView(view as any);
    this.caseId = String(params?.params?.caseId || '');
    void this.activate();
  }
  private async activate() {
    if (!session.user()) await session.restore();
    if (!session.isAdmin()) return;
    await this.workspace.loadCases();
    if (this.caseId) await this.workspace.openCase({ id: this.caseId });
  }
  changeView = () => navigate('admin-compliance', { view: this.workspace.savedCaseView() });
  openCase = async (item: any, event: { detail: number }) => {
    await this.workspace.openCase(item);
    focusRecordHeading(event, 'compliance-record-heading');
  };
  copyPaymentId = async () => {
    const paymentId = this.workspace.selectedCase()?.paymentId;
    if (!paymentId) return;
    try {
      if (!navigator.clipboard?.writeText) throw new Error('Clipboard unavailable');
      await navigator.clipboard.writeText(paymentId);
      this.workspace.notice('Payment ID copied.');
    } catch {
      this.workspace.error('Unable to copy the payment ID. Select the visible ID and copy it manually.');
    }
  };
  confirmDecision = async () => {
    await this.workspace.confirm();
    if (!this.workspace.error() && this.workspace.selectedCase()?.status !== 'OPEN') {
      this.workspace.closeCase();
    }
  };
  navigateToCopilot = () => {
    const selected = this.workspace.selectedCase();
    if (selected) navigate('admin-copilot', { caseId: selected.id, paymentId: selected.paymentId });
  };
  disconnected() {
    this.workspace.dispose();
  }
}
export = AdminComplianceViewModel;
