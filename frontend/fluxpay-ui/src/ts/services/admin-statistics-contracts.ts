export type PaymentStatus =
  | 'DRAFT'
  | 'QUOTED'
  | 'UNDER_REVIEW'
  | 'PROCESSING'
  | 'COMPLETED'
  | 'FAILED'
  | 'REFUNDED'
  | 'REJECTED'
  | 'CANCELLED';

export interface StatisticsCurrency {
  code: string;
  scale: number;
}

export interface StatisticsOptions {
  currencies: StatisticsCurrency[];
  defaultCurrency: string | null;
  reportingZone: string;
  today: string;
  maximumRangeDays: number;
}

export interface StatisticsMetadata {
  from: string;
  to: string;
  currency: string;
  currencyScale: number;
  reportingZone: string;
  fromInclusive: string;
  toExclusive: string;
  generatedAt: string;
  periodBasis: string;
}

export interface StatisticsPaymentSummary {
  paymentCount: number;
  completedCount: number;
  completedAmount: string;
  payoutSuccessRate: number | null;
  failedCount: number;
  processingCount: number;
}

export interface StatisticsPaymentDay {
  date: string;
  paymentCount: number;
  completedAmount: string;
}

export interface StatisticsStatusCount {
  status: PaymentStatus;
  count: number;
}

export interface StatisticsProviderStats {
  providerId: string;
  providerCode: string;
  providerName: string;
  totalAttempts: number;
  completedAttempts: number;
  failedAttempts: number;
  inProgressAttempts: number;
  successRate: number | null;
}

export interface StatisticsCustomerSummary {
  totalCustomers: number;
  newRegistrations: number;
}

export interface StatisticsCustomerDay {
  date: string;
  registrations: number;
}

export interface StatisticsWorkload {
  kycPending: number;
  kycOver24h: number;
  complianceOpen: number;
  complianceHighRisk: number;
  complianceOver24h: number;
  ticketsOpen: number;
  ticketsOver24h: number;
}

export interface StatisticsSummary {
  meta: StatisticsMetadata;
  paymentSummary: StatisticsPaymentSummary;
  paymentTrend: StatisticsPaymentDay[];
  paymentStatuses: StatisticsStatusCount[];
  providers: StatisticsProviderStats[];
  customers: StatisticsCustomerSummary;
  customerTrend: StatisticsCustomerDay[];
  workload: StatisticsWorkload;
}

export interface StatisticsPaymentRow {
  paymentId: string;
  createdAt: string;
  sourceAmount: string;
  sourceCurrency: string;
  status: PaymentStatus;
}

export interface StatisticsPaymentPage {
  meta: StatisticsMetadata;
  status: PaymentStatus | null;
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  items: StatisticsPaymentRow[];
}

export interface StatisticsFilters {
  from: string;
  to: string;
  currency: string;
}

export interface StatisticsRouteState extends StatisticsFilters {
  showPayments: boolean;
  status?: PaymentStatus;
  day?: string;
  page: number;
}

export interface StatisticsPaymentQuery extends StatisticsFilters {
  status?: PaymentStatus;
  page: number;
  size: number;
}

export interface ResolvedStatisticsRoute {
  state: StatisticsRouteState;
  notice: string;
}
