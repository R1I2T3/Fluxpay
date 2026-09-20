import { Page } from '../services/page';
import * as ko from 'knockout';
import { ComplianceWorkspace } from '../services/compliance-workspace';
class ViewModel extends Page {
  workspace = new ComplianceWorkspace();
  adminTab = ko.observable('operations');
  tabs = [
    { id: 'operations', label: 'Verification & routes' },
    { id: 'compliance', label: 'Compliance cases' },
    { id: 'policies', label: 'Policy library' },
    { id: 'copilot', label: 'Compliance Copilot' },
  ];
  constructor(params: any) {
    super('admin', params);
  }
  refreshAdmin = () => {
    if (this.workspace.busy()) return;
    if (this.adminTab() === 'policies') void this.workspace.loadPolicies();
    else if (this.adminTab() === 'compliance') void this.workspace.loadCases();
    else if (this.adminTab() === 'operations') this.refresh();
    else this.workspace.notice('Ask a new question to refresh the policy response.');
  };
  selectTab = (tab: { id: string }) => {
    if (this.workspace.busy() || !this.session.isAdmin()) return;
    this.adminTab(tab.id);
    this.workspace.resetSearch();
    if (tab.id === 'policies') void this.workspace.loadPolicies();
    if (tab.id === 'compliance') void this.workspace.loadCases();
  };
  askAboutCase = () => {
    const c = this.workspace.selectedCase();
    if (!c) return;
    const reasons = c.riskReasons.filter(Boolean).join('; ') || 'No risk reasons recorded.';
    const suggestedAction = c.suggestedAction.trim() || 'No suggested action recorded.';
    const question = [
      'Which policies are relevant to this payment review?',
      '',
      'Case context:',
      `- Risk level: ${c.risk}`,
      `- Risk reasons: ${reasons}`,
      `- Suggested action: ${suggestedAction}`,
      '',
      'Use this case context to identify the relevant policy checks before deciding.',
    ].join('\n');
    this.workspace.copilotPaymentId(c.paymentId);
    this.workspace.question(question);
    this.workspace.closeCase();
    this.selectTab({ id: 'copilot' });
  };
  openSource = async (source: { policyDocumentId: string }) => {
    if (this.workspace.busy()) return;
    this.adminTab('policies');
    this.workspace.resetSearch();
    await this.workspace.loadPolicies();
    await this.workspace.openPolicy({ id: source.policyDocumentId });
  };
  disconnected() {
    super.disconnected();
    this.workspace.dispose();
  }
}
export = ViewModel;
