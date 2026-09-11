import * as ko from 'knockout';
import { paymentService, idempotencyKey } from '../services/payment-service';
export class PaymentsNewViewModel {
  busy = ko.observable(false); error = ko.observable(''); paymentId = ko.observable<string | null>(null);
  sourceWalletId = ko.observable(''); recipientId = ko.observable(''); amount = ko.observable(''); sourceCurrency = ko.observable('USD'); payoutCurrency = ko.observable('INR'); purpose = ko.observable('FAMILY_SUPPORT'); preference = ko.observable('CHEAPEST');
  async continueToQuotes() { if (!this.sourceWalletId() || !this.recipientId() || !this.amount()) { this.error('Choose a wallet, recipient and amount.'); return; } this.busy(true); this.error(''); const key = idempotencyKey(); sessionStorage.setItem('m3-draft-key', key); try { const payment = await paymentService.draft({ sourceWalletId: this.sourceWalletId(), recipientId: this.recipientId(), sourceAmount: this.amount(), sourceCurrency: this.sourceCurrency(), payoutCurrency: this.payoutCurrency(), purpose: this.purpose(), preference: this.preference() }, key); this.paymentId(payment.id); sessionStorage.setItem('m3-payment-id', payment.id); window.location.hash = `payment-quotes?paymentId=${payment.id}`; } catch (e: any) { this.error(e.message); } finally { this.busy(false); } }
}
