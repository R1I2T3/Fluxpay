// services/route-analysis.ts
import type { RailDescriptor, TransferProvider, TransferRoute } from './flux-api';

export interface EligibilityInput {
  country: string;
  currency: string;
  amount: string;
  destinationType: string;
  sourceCurrency: string;
  sourceCountry?: string | null;
}
export interface EligibilityRow {
  route: TransferRoute;
  provider?: TransferProvider;
  eligible: boolean;
  reasons: string[];
}

const active = (value: { active: boolean; archivedAt: string | null }) =>
  value.active && !value.archivedAt;
const normalized = (value: string | null | undefined) =>
  String(value || '')
    .trim()
    .toUpperCase();

export function routeNeedsAttention(route: TransferRoute, provider?: TransferProvider): boolean {
  const unavailable = !active(route) || !provider || !active(provider);
  const hasOutcomes = route.completedCount + route.failedCount > 0;
  return (
    unavailable ||
    (hasOutcomes && Number(route.effectiveSuccessRate) < Number(route.configuredSuccessRate))
  );
}

export function evaluateRouteEligibility(
  input: EligibilityInput,
  routes: TransferRoute[],
  providers: TransferProvider[],
  rails: RailDescriptor[],
) {
  const amount = Number(input.amount);
  const inputErrors: string[] = [];
  if (!Number.isFinite(amount) || amount <= 0)
    inputErrors.push('Enter an amount greater than zero.');
  if (!/^[A-Z]{2}$/.test(normalized(input.country)))
    inputErrors.push('Enter a two-letter destination country.');
  if (!/^[A-Z]{3}$/.test(normalized(input.currency)))
    inputErrors.push('Enter a three-letter payout currency.');
  if (!/^[A-Z]{3}$/.test(normalized(input.sourceCurrency)))
    inputErrors.push('Enter a three-letter source currency.');
  const sourceCountry = normalized(input.sourceCountry);
  if (sourceCountry && !/^[A-Z]{2}$/.test(sourceCountry))
    inputErrors.push('Enter a two-letter source country.');
  const rows: EligibilityRow[] = routes.map((route) => {
    const provider = providers.find((item) => item.id === route.providerId);
    const rail = provider && rails.find((item) => item.railType === provider.railType);
    const reasons: string[] = [];
    if (!provider) reasons.push('Provider configuration is unavailable.');
    else if (provider.archivedAt) reasons.push('Provider is archived.');
    else if (!provider.active) reasons.push('Provider is inactive.');
    if (route.archivedAt) reasons.push('Route is archived.');
    else if (!route.active) reasons.push('Route is inactive.');
    if (
      route.destinationCountry &&
      normalized(route.destinationCountry) !== normalized(input.country)
    )
      reasons.push('Destination country does not match.');
    if (normalized(route.payoutCurrency) !== normalized(input.currency))
      reasons.push('Payout currency does not match.');
    if (normalized(route.sourceCurrency) !== normalized(input.sourceCurrency))
      reasons.push('Source currency does not match.');
    if (route.sourceCountry && normalized(route.sourceCountry) !== normalized(input.sourceCountry))
      reasons.push('Source country does not match.');
    if (normalized(route.destinationType) !== normalized(input.destinationType))
      reasons.push('Payout method does not match.');
    if (provider && !rail) reasons.push('Rail compatibility is unavailable.');
    else if (rail && !rail.supportedDestinations.includes(input.destinationType))
      reasons.push('Provider rail does not support this payout method.');
    if (Number.isFinite(amount) && amount > 0) {
      if (route.minimumRecipientAmount != null && amount < Number(route.minimumRecipientAmount))
        reasons.push(`Amount is below the configured minimum of ${route.minimumRecipientAmount}.`);
      if (route.maximumRecipientAmount != null && amount > Number(route.maximumRecipientAmount))
        reasons.push(`Amount exceeds the configured maximum of ${route.maximumRecipientAmount}.`);
    }
    return { route, provider, eligible: inputErrors.length === 0 && reasons.length === 0, reasons };
  });
  return { inputErrors, routes: rows };
}

export interface CorridorMatrixCell {
  corridor: string;
  count: number;
}
export interface CorridorMatrixRow {
  source: string;
  cells: CorridorMatrixCell[];
}

const sourceKey = (route: TransferRoute) =>
  `${normalized(route.sourceCountry) || 'GLOBAL'}/${normalized(route.sourceCurrency)}`;
const destinationKey = (route: TransferRoute) =>
  `${normalized(route.destinationCountry) || 'GLOBAL'}/${normalized(route.payoutCurrency)}`;

export function buildCorridorMatrix(routes: TransferRoute[], providers: TransferProvider[]) {
  const eligible = routes.filter((route) => {
    const provider = providers.find((item) => item.id === route.providerId);
    return active(route) && Boolean(provider && active(provider));
  });
  const sources = Array.from(new Set(eligible.map(sourceKey))).sort();
  const corridors = Array.from(new Set(eligible.map(destinationKey))).sort();
  return sources.map((source) => ({
    source,
    cells: corridors.map((corridor) => ({
      corridor,
      count: eligible.filter(
        (route) => sourceKey(route) === source && destinationKey(route) === corridor,
      ).length,
    })),
  }));
}

export function buildProviderComparison(providers: TransferProvider[], routes: TransferRoute[]) {
  return providers.map((provider) => ({
    provider,
    routes: routes.filter((route) => route.providerId === provider.id),
  }));
}
