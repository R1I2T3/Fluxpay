export interface PaymentHold {
  paymentId: string;
  status: string;
  onHold: boolean;
  canPayout: boolean;
  reviewExpiresAt: string | null;
  risk: string | null;
  reasons: string[];
  reasonMessages: string[];
  whatNext: string;
  decisionReason: string | null;
}

export const HOLD_REASON_MESSAGES: Record<string, string> = {
  AMOUNT_EXCEEDS_REVIEW_THRESHOLD:
    'Amount is above the routine review limit and needs a quick compliance check.',
  FIRST_TRANSFER_TO_RECIPIENT:
    'This is your first transfer to this recipient, so it needs an extra check.',
  RECIPIENT_ADDED_TODAY: 'This recipient was added recently, so the transfer is held for review.',
  INVALID_COMPLIANCE_INPUT: 'Some payment details need an extra check before payout.',
  UNSUPPORTED_SOURCE_CURRENCY: 'This currency needs a manual compliance check before payout.',
};

export function holdReasonMessages(reasons: string[]): string[] {
  return (reasons || []).map((code) => {
    const direct = HOLD_REASON_MESSAGES[code];
    if (direct) return direct;
    const lower = String(code || '')
      .toLowerCase()
      .replace(/_/g, ' ')
      .trim();
    if (!lower) return 'This transfer needs an extra compliance check before payout.';
    const humanized = lower.charAt(0).toUpperCase() + lower.slice(1);
    return `${humanized} — needs an extra compliance check before payout.`;
  });
}

export function holdExpiryLabel(expiresAt: string | null, nowMs = Date.now()): string {
  if (!expiresAt) return 'Review time not supplied. Approval enables payout.';
  const expires = Date.parse(expiresAt);
  if (!Number.isFinite(expires)) return 'Review time not supplied. Approval enables payout.';
  const diff = expires - nowMs;
  if (diff <= 0) return 'Review window expired — a fresh quote may be needed.';
  const hours = Math.floor(diff / 3600000);
  const minutes = Math.ceil((diff % 3600000) / 60000);
  if (hours <= 0) return `Review expires in ${minutes}m. No action needed.`;
  return `Review expires in ${hours}h${minutes ? ` ${minutes}m` : ''}. No action needed.`;
}
