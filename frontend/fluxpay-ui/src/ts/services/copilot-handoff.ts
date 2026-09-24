import type { ComplianceCase } from './flux-api';

export type CopilotCaseContext = Pick<ComplianceCase, 'id'|'paymentId'|'risk'|'riskReasons'|'suggestedAction'>;

let pendingCaseContext: CopilotCaseContext | undefined;

export function stageCopilotCaseContext(value: CopilotCaseContext): void {
  pendingCaseContext = value;
}

export function consumeCopilotCaseContext(): CopilotCaseContext | undefined {
  const value = pendingCaseContext;
  pendingCaseContext = undefined;
  return value;
}
