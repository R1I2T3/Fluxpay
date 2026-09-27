// services/admin-overview.ts
import type {
  ComplianceCase,
  KycAdminRow,
  PolicyDocument,
  TicketResponse,
  TransferProvider,
  TransferRoute,
} from './flux-api';
import { isOlderThanHours, prioritizeComplianceCases } from './admin-console';
import { routeNeedsAttention } from './route-analysis';

export interface OverviewInput {
  kyc: KycAdminRow[];
  cases: ComplianceCase[];
  tickets: TicketResponse[];
  providers: TransferProvider[];
  routes: TransferRoute[];
  policies: PolicyDocument[];
  now: number;
}
export interface OverviewMetric {
  id: string;
  label: string;
  value: string;
  tone: 'critical' | 'warning' | 'info';
  path: string;
  params: Record<string, string>;
  sourceKeys: string[];
  unavailable?: boolean;
  pending?: boolean;
}
export interface AttentionRow {
  id: string;
  area: string;
  summary: string;
  tone: 'critical' | 'warning' | 'info';
  createdAt: string;
  path: string;
  params: Record<string, string>;
  sourceKeys: string[];
}

export function deriveAdminOverview(input: OverviewInput) {
  const pending = input.kyc.filter((item) => item.status === 'PENDING');
  const agingKyc = pending.filter((item) => isOlderThanHours(item.submittedAt, 24, input.now));
  const highCases = input.cases.filter((item) => item.status === 'OPEN' && item.risk === 'HIGH');
  const providerById = new Map(input.providers.map((item) => [item.id, item]));
  const routeAttention = input.routes.filter((item) =>
    routeNeedsAttention(item, providerById.get(item.providerId)),
  );
  const openTickets = input.tickets.filter((item) => ['OPEN', 'IN_PROGRESS'].includes(item.status));
  const agingTickets = openTickets.filter((item) =>
    isOlderThanHours(item.createdAt, 24, input.now),
  );
  const unindexed = input.policies.filter(
    (item) => !(item.chunks || []).some((chunk) => !chunk.manual),
  );
  const metrics: OverviewMetric[] = [
    {
      id: 'compliance-high',
      label: 'High-risk compliance',
      value: String(highCases.length),
      tone: 'critical',
      path: 'admin-compliance',
      params: { view: 'HIGH_RISK' },
      sourceKeys: ['compliance'],
    },
    {
      id: 'kyc-pending',
      label: 'Pending KYC',
      value: input.kyc.length === 100 ? '100+' : String(pending.length),
      tone: 'warning',
      path: 'admin-kyc',
      params: { view: 'PENDING' },
      sourceKeys: ['kyc'],
    },
    {
      id: 'kyc-aging',
      label: 'KYC over 24 hours',
      value: String(agingKyc.length),
      tone: 'warning',
      path: 'admin-kyc',
      params: { view: 'AGING' },
      sourceKeys: ['kyc'],
    },
    {
      id: 'routes-attention',
      label: 'Routes needing attention',
      value: String(routeAttention.length),
      tone: 'critical',
      path: 'admin-routes',
      params: { view: 'CATALOGUE', status: 'ATTENTION' },
      sourceKeys: ['routes', 'providers'],
    },
    {
      id: 'tickets-open',
      label: 'Open support work',
      value: String(openTickets.length),
      tone: 'info',
      path: 'admin-tickets',
      params: { view: 'OPEN' },
      sourceKeys: ['tickets'],
    },
    {
      id: 'tickets-aging',
      label: 'Tickets over 24 hours',
      value: String(agingTickets.length),
      tone: 'warning',
      path: 'admin-tickets',
      params: { view: 'AGING' },
      sourceKeys: ['tickets'],
    },
    {
      id: 'policies-unindexed',
      label: 'Policies without indexed chunks',
      value: String(unindexed.length),
      tone: 'warning',
      path: 'admin-policies',
      params: { view: 'UNINDEXED' },
      sourceKeys: ['policies'],
    },
  ];
  const rows: AttentionRow[] = [
    ...prioritizeComplianceCases(highCases).map((item) => ({
      id: item.id,
      area: 'Compliance',
      summary: `High-risk case · ${item.id}`,
      tone: 'critical' as const,
      createdAt: item.createdAt,
      path: 'admin-compliance',
      params: { caseId: item.id, view: 'HIGH_RISK' },
      sourceKeys: ['compliance'],
    })),
    ...routeAttention.map((item) => ({
      id: item.id,
      area: 'Routing',
      summary: `Route needs attention · ${item.routeCode}`,
      tone: 'critical' as const,
      createdAt: '',
      path: 'admin-routes',
      params: { routeId: item.id, view: 'CATALOGUE' },
      sourceKeys: ['routes', 'providers'],
    })),
    ...agingKyc.map((item) => ({
      id: item.applicationId,
      area: 'KYC',
      summary: `Aging KYC review · ${item.fullName}`,
      tone: 'warning' as const,
      createdAt: item.submittedAt,
      path: 'admin-kyc',
      params: { applicationId: item.applicationId, view: 'AGING' },
      sourceKeys: ['kyc'],
    })),
    ...agingTickets.map((item) => ({
      id: item.id,
      area: 'Support',
      summary: `Aging support ticket · ${item.subject}`,
      tone: 'warning' as const,
      createdAt: item.createdAt,
      path: 'admin-tickets',
      params: { ticketId: item.id, view: 'AGING' },
      sourceKeys: ['tickets'],
    })),
    ...unindexed.map((item) => ({
      id: item.id,
      area: 'Policy',
      summary: `No indexed chunks · ${item.title}`,
      tone: 'warning' as const,
      createdAt: item.createdAt,
      path: 'admin-policies',
      params: { policyId: item.id },
      sourceKeys: ['policies'],
    })),
  ];
  const toneRank = { critical: 0, warning: 1, info: 2 };
  const rowTime = (row: AttentionRow) => {
    const value = Date.parse(row.createdAt);
    return Number.isFinite(value) ? value : Number.POSITIVE_INFINITY;
  };
  const orderedRows = rows
    .map((row, index) => ({ row, index }))
    .sort(
      (left, right) =>
        toneRank[left.row.tone] - toneRank[right.row.tone] ||
        rowTime(left.row) - rowTime(right.row) ||
        left.index - right.index,
    )
    .map((entry) => entry.row);
  return { metrics, rows: orderedRows };
}
