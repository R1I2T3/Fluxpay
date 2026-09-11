import * as ko from 'knockout';
import { paymentService, QuoteResult } from '../services/payment-service';
export class PaymentQuotesViewModel {
  busy = ko.observable(false); error = ko.observable(''); result = ko.observable<QuoteResult | null>(null); selectedQuoteId = ko.observable(''); secondsRemaining = ko.observable(0); private timer?: number;
  constructor() { const id = new URLSearchParams(window.location.hash.split('?')[1] || '').get('paymentId') || sessionStorage.getItem('m3-payment-id'); if (id) void this.load(id); else this.error('Choose a payment before viewing quotes.'); }
  async load(id: string) { this.busy(true); try { const quote = await paymentService.quotes(id); this.result(quote); this.selectedQuoteId(quote.recommendedQuoteId); const drift = Date.parse(quote.expiresAt) - Date.parse(quote.serverTime); this.secondsRemaining(Math.max(0, Math.floor(drift / 1000))); this.timer = window.setInterval(() => this.secondsRemaining(Math.max(0, this.secondsRemaining() - 1)), 1000); } catch (e: any) { this.error(e.message); } finally { this.busy(false); } }
  async confirm() { const result = this.result(); const quoteId = this.selectedQuoteId(); if (!result || !quoteId) return; this.busy(true); this.error(''); const key = sessionStorage.getItem(`m3-confirm-key:${result.paymentId}`) || crypto.randomUUID(); sessionStorage.setItem(`m3-confirm-key:${result.paymentId}`, key); try { const payment = await paymentService.confirm(result.paymentId, quoteId, key); sessionStorage.removeItem(`m3-confirm-key:${result.paymentId}`); window.location.hash = 'payments-list'; return payment; } catch (e: any) { this.error(e.message); } finally { this.busy(false); } }
  dispose() { if (this.timer) window.clearInterval(this.timer); }
}
