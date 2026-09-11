import { api } from './api-client';

export type DraftInput = { sourceWalletId: string; recipientId: string; sourceAmount: string; sourceCurrency: string; payoutCurrency: string; purpose: string; preference: string };
export type Quote = { id: string; route: string; marketRate: string; offeredRate: string; feeAmount: string; recipientAmount: string; estimatedMinutes: number; recommended: boolean };
export type QuoteResult = { paymentId: string; recommendedQuoteId: string; recommendationReason: string; expiresAt: string; serverTime: string; quotes: Quote[] };
export const idempotencyKey = () => crypto.randomUUID();
export const paymentService = {
  async draft(input: DraftInput, key: string) { return (await api.post('/payments/draft', input, { headers: { 'Idempotency-Key': key } })).data.data; },
  async quotes(paymentId: string): Promise<QuoteResult> { return (await api.post(`/payments/${paymentId}/quotes`)).data.data; },
  async getQuotes(paymentId: string): Promise<QuoteResult> { return (await api.get(`/payments/${paymentId}/quotes`)).data.data; },
  async confirm(paymentId: string, quoteId: string, key: string) { return (await api.post(`/payments/${paymentId}/confirm`, { quoteId }, { headers: { 'Idempotency-Key': key } })).data.data; },
  async list(page = 0, size = 20) { return (await api.get(`/payments?page=${page}&size=${size}`)).data.data; },
  async cancel(id: string) { return (await api.post(`/payments/${id}/cancel`)).data.data; },
};
