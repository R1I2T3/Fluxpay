// viewModels/admin-copilot.ts
import { ComplianceWorkspace } from '../services/compliance-workspace';
import { fluxApi } from '../services/flux-api';
import { copilotQuestionForCase } from '../services/admin-console';
import { navigate, session } from '../services/session';

class AdminCopilotViewModel {
  session = session;
  workspace = new ComplianceWorkspace();
  private caseId: string;
  private paymentId: string;
  ready: Promise<void>;
  constructor(params: any) {
    this.caseId = String(params?.params?.caseId || '');
    this.paymentId = String(params?.params?.paymentId || '');
    this.ready = this.activate();
  }
  private async activate() {
    if (!session.user()) await session.restore();
    if (!session.isAdmin()) return;
    this.workspace.copilotPaymentId(this.paymentId);
    if (this.caseId) {
      await this.workspace.run(async () => {
        const item = await fluxApi.complianceCase(this.caseId);
        this.workspace.copilotPaymentId(item.paymentId);
        this.workspace.question(copilotQuestionForCase(item));
      });
    }
  }
  askCited = async () => {
    this.workspace.liveResponse(false);
    await this.workspace.ask();
    if (this.workspace.answer()) this.workspace.question('');
    return false;
  };
  openSource = (source: { policyDocumentId: string }) =>
    navigate('admin-policies', { policyId: source.policyDocumentId });
  disconnected() {
    this.workspace.dispose();
  }
}
export = AdminCopilotViewModel;
