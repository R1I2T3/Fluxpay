// viewModels/admin-copilot.ts
import * as ko from 'knockout';
import { ComplianceWorkspace } from '../services/compliance-workspace';
import { fluxApi } from '../services/flux-api';
import type { ComplianceCase } from '../services/flux-api';
import { copilotQuestionForCase } from '../services/admin-console';
import { navigate, session } from '../services/session';

class AdminCopilotViewModel {
  session = session;
  workspace = new ComplianceWorkspace();
  caseContext = ko.observable<ComplianceCase>();
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
    if (this.caseId)
      await this.workspace.run(async () => {
        const item = await fluxApi.complianceCase(this.caseId);
        this.caseContext(item);
        this.workspace.copilotPaymentId(item.paymentId);
        this.workspace.question(copilotQuestionForCase(item));
      });
  }
  askCited = () => {
    this.workspace.liveResponse(false);
    void this.workspace.ask();
    return false;
  };
  openSource = (source: { policyDocumentId: string }) =>
    navigate('admin-policies', { policyId: source.policyDocumentId });
  disconnected() {
    this.workspace.dispose();
  }
}
export = AdminCopilotViewModel;
