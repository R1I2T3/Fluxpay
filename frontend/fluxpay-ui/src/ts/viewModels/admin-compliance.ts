// viewModels/admin-compliance.ts
import * as ko from 'knockout';
import { copyAdminIdentifier, focusRecordHeading } from '../services/admin-console';
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
  copyIdentifier = async (value: string | null | undefined, label: string) => {
    this.workspace.error('');
    this.workspace.notice('');
    try {
      this.workspace.notice(await copyAdminIdentifier(value, label, navigator.clipboard));
    } catch (error: any) {
      this.workspace.error(error.message || 'Unable to copy ' + label.toLowerCase() + '.');
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
