// services/admin-console.ts
import type { ComplianceCase, KycAdminRow, TicketResponse } from './flux-api';

export type AdminEnvironmentName = 'PRODUCTION' | 'STAGING' | 'SANDBOX';
export interface AdminEnvironment {
  name: AdminEnvironmentName;
  label: string;
  tone: 'critical' | 'warning' | 'info';
}
export interface FieldChange {
  field: string;
  label: string;
  before: string;
  after: string;
}

const environments: Record<AdminEnvironmentName, AdminEnvironment> = {
  PRODUCTION: { name: 'PRODUCTION', label: 'Production', tone: 'critical' },
  STAGING: { name: 'STAGING', label: 'Staging', tone: 'warning' },
  SANDBOX: { name: 'SANDBOX', label: 'Sandbox', tone: 'info' },
};
const timestamp = (value: unknown, fallback = Number.POSITIVE_INFINITY) => {
  const parsed = Date.parse(String(value ?? ''));
  return Number.isFinite(parsed) ? parsed : fallback;
};
const stable = <T>(items: T[], compare: (left: T, right: T) => number) =>
  items
    .map((value, index) => ({ value, index }))
    .sort((left, right) => compare(left.value, right.value) || left.index - right.index)
    .map((entry) => entry.value);

export function resolveAdminEnvironment(configured: unknown, hostname: string): AdminEnvironment {
  const explicit = String(configured ?? '')
    .trim()
    .toUpperCase() as AdminEnvironmentName;
  if (explicit in environments) return environments[explicit];
  const host = hostname.toLowerCase();
  if (host === 'localhost' || host === '127.0.0.1') return environments.SANDBOX;
  if (/(^|[.-])(staging|stage|uat)([.-]|$)/.test(host)) return environments.STAGING;
  return environments.PRODUCTION;
}
export function isOlderThanHours(value: unknown, hours: number, now = Date.now()): boolean {
  const parsed = timestamp(value, Number.NaN);
  return Number.isFinite(parsed) && now - parsed >= hours * 60 * 60 * 1000;
}
export function prioritizeKycReviews<T extends Pick<KycAdminRow, 'submittedAt' | 'documents'>>(
  items: T[],
): T[] {
  const hasOriginal = (item: T) =>
    item.documents.length > 0 && item.documents.every((document) => document.available);
  return stable(
    [...items],
    (left, right) =>
      Number(hasOriginal(left)) - Number(hasOriginal(right)) ||
      timestamp(left.submittedAt) - timestamp(right.submittedAt),
  );
}
export function prioritizeComplianceCases<
  T extends Pick<ComplianceCase, 'risk' | 'reviewExpiresAt' | 'createdAt'>,
>(items: T[]): T[] {
  const risk: Record<string, number> = { HIGH: 0, MEDIUM: 1, LOW: 2 };
  return stable(
    [...items],
    (left, right) =>
      (risk[left.risk] ?? 3) - (risk[right.risk] ?? 3) ||
      timestamp(left.reviewExpiresAt) - timestamp(right.reviewExpiresAt) ||
      timestamp(left.createdAt) - timestamp(right.createdAt),
  );
}
export function prioritizeSupportTickets<
  T extends Pick<TicketResponse, 'status' | 'assigneeAdminId' | 'createdAt'>,
>(items: T[]): T[] {
  const status: Record<string, number> = { OPEN: 0, IN_PROGRESS: 1, RESOLVED: 2, CLOSED: 3 };
  return stable(
    [...items],
    (left, right) =>
      (status[left.status] ?? 4) - (status[right.status] ?? 4) ||
      Number(Boolean(left.assigneeAdminId)) - Number(Boolean(right.assigneeAdminId)) ||
      timestamp(left.createdAt) - timestamp(right.createdAt),
  );
}
export function composeDecisionReason(code: string, notes: string): string {
  const normalizedCode = code.trim().toUpperCase();
  if (!normalizedCode) throw new Error('Choose a standardized reason code.');
  const normalizedNotes = notes.trim();
  const result = normalizedNotes ? `${normalizedCode} — ${normalizedNotes}` : normalizedCode;
  if (result.length > 500)
    throw new Error('Reason code and notes must be 500 characters or fewer.');
  return result;
}
export function maskIdentifier(value: string): string {
  const normalized = value.trim();
  return !normalized ? '—' : normalized.length <= 4 ? '••••' : `••••${normalized.slice(-4)}`;
}
const display = (value: unknown) =>
  value === true
    ? 'Yes'
    : value === false
      ? 'No'
      : value == null || value === ''
        ? '—'
        : String(value);
export function diffFields(
  before: Record<string, unknown>,
  after: Record<string, unknown>,
  labels: Record<string, string>,
  order: string[],
): FieldChange[] {
  return order
    .filter((field) => display(before[field]) !== display(after[field]))
    .map((field) => ({
      field,
      label: labels[field] || field,
      before: display(before[field]),
      after: display(after[field]),
    }));
}
export function nextTicketAction(status: string): { status: string; label: string } | null {
  return (
    (
      {
        OPEN: { status: 'IN_PROGRESS', label: 'Start work' },
        IN_PROGRESS: { status: 'RESOLVED', label: 'Resolve ticket' },
        RESOLVED: { status: 'CLOSED', label: 'Close ticket' },
        CLOSED: { status: 'OPEN', label: 'Reopen ticket' },
      } as Record<string, { status: string; label: string }>
    )[status] || null
  );
}
export function focusRecordHeading(
  event: { detail: number },
  id: string,
  runtime?: {
    requestAnimationFrame(callback: () => void): unknown;
    document: { getElementById(id: string): { focus(): void } | null };
  },
): void {
  if (event.detail !== 0) return;
  const host = runtime ?? {
    requestAnimationFrame: (callback: () => void) => window.requestAnimationFrame(() => callback()),
    document,
  };
  host.requestAnimationFrame(() => host.document.getElementById(id)?.focus());
}
