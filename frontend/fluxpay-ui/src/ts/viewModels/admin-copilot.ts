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
  selectedCaseId = ko.observable('');
  manualPaymentContext = ko.observable(false);
  caseOptions = ko.pureComputed(() => [
    {id: '', label: 'No case context'},
    ...this.workspace.cases().map((item) => ({
      id: item.id,
      label: `${item.risk} risk · ${item.paymentId}`,
    })),
  ]);
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
    await this.workspace.loadCases();
    this.workspace.copilotPaymentId(this.paymentId);
    this.manualPaymentContext(!this.caseId && !!this.paymentId);
    if (this.caseId) {
      this.selectedCaseId(this.caseId);
      await this.workspace.run(async () => {
        const item = await fluxApi.complianceCase(this.caseId);
        this.caseContext(item);
        this.manualPaymentContext(false);
        this.workspace.copilotPaymentId(item.paymentId);
        this.workspace.question(copilotQuestionForCase(item));
      });
    }
  }
  selectCaseContext = async () => {
    const id = this.selectedCaseId();
    if (!id) {
      this.caseContext(undefined);
      this.manualPaymentContext(true);
      this.workspace.copilotPaymentId('');
      return;
    }
    await this.workspace.run(async () => {
      const item = await fluxApi.complianceCase(id);
      this.caseContext(item);
      this.manualPaymentContext(false);
      this.workspace.copilotPaymentId(item.paymentId);
      this.workspace.question(copilotQuestionForCase(item));
    });
  };
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
