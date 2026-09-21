import * as ko from 'knockout';
import { ticketApi, fluxApi } from '../services/flux-api';
import { Page } from '../services/page';

type Ticket = {
  id: string;
  subject: string;
  body: string;
  status: string;
  paymentId?: string | null;
  assigneeAdminId?: string | null;
  createdAt: string;
  updatedAt: string;
};

type RelatedPayment = {
  id: string;
  label: string;
};

class TicketsViewModel extends Page {
  tickets = ko.observableArray<Ticket>([]);
  total = ko.observable(0);
  subject = ko.observable('');
  body = ko.observable('');
  paymentId = ko.observable('');
  relatedPayments = ko.observableArray<RelatedPayment>([]);
  private ticketSessionSubscription?: { dispose(): void };

  constructor(params: any) {
    super('tickets', params);
    this.ticketSessionSubscription = this.session.user.subscribe(user => {
      this.paymentId('');
      if (user) {
        void this.loadTicketWorkspace();
      } else {
        this.tickets([]);
        this.total(0);
        this.relatedPayments([]);
      }
    });
    // Page's base constructor performs its generic load before this view model exists. Defer the
    // ticket request until that short generic load has released its busy state.
    window.setTimeout(() => void this.loadTicketWorkspace(), 0);
  }

  private async fetchTickets() {
    const userId = this.session.user()?.id;
    if (!userId) {
      this.tickets([]);
      this.total(0);
      return;
    }
    const result = await ticketApi.list();
    if (this.session.user()?.id !== userId) {
      return;
    }
    this.tickets(result.items || []);
    this.total(Number(result.total || 0));
  }

  private async fetchRelatedPayments() {
    const userId = this.session.user()?.id;
    if (!userId) {
      this.relatedPayments([]);
      this.paymentId('');
      return;
    }
    const [payments, recipients] = await Promise.all([
      this.fetchAllPayments(),
      fluxApi.recipients()
    ]);
    const recipientNames = new Map(recipients.map(recipient => [recipient.id, recipient.name]));
    if (this.session.user()?.id !== userId) {
      return;
    }
    this.relatedPayments(
      payments
        .filter(payment => !['DRAFT', 'QUOTED'].includes(payment.status))
        .map(payment => ({
          id: payment.id,
          label: this.paymentLabel(payment, recipientNames.get(payment.recipientId))
        }))
    );
  }

  private async fetchAllPayments(): Promise<any[]> {
    const payments: any[] = [];
    let page = 0;
    let total = 0;
    do {
      const result = await fluxApi.payments(page, 100);
      const items = result.items || [];
      payments.push(...items);
      total = Number(result.total || 0);
      page += 1;
      if (items.length === 0) {
        break;
      }
    } while (payments.length < total);
    return payments;
  }

  private paymentLabel(payment: any, recipientName?: string) {
    const date = payment.createdAt ? new Date(payment.createdAt).toLocaleDateString() : 'Unknown date';
    const recipient = recipientName || 'Unknown recipient';
    const amount = this.money(payment.sourceAmount, payment.sourceCurrency);
    return `[${date} - ${recipient} - ${amount}]`;
  }

  private async fetchTicketWorkspace() {
    await Promise.all([this.fetchTickets(), this.fetchRelatedPayments()]);
  }

  loadTicketWorkspace = () => this.run(() => this.fetchTicketWorkspace());

  createTicket = () =>
    this.run(async () => {
      const subject = this.subject().trim();
      const body = this.body().trim();
      const paymentId = String(this.paymentId() || '').trim();
      if (!subject || subject.length > 120) {
        throw new Error('Enter a subject between 1 and 120 characters.');
      }
      if (!body || body.length > 4000) {
        throw new Error('Enter a message between 1 and 4000 characters.');
      }
      await ticketApi.create({
        subject,
        body,
        ...(paymentId ? { paymentId } : {})
      });
      this.subject('');
      this.body('');
      this.paymentId('');
      await this.fetchTickets();
    }, 'Your support ticket has been created.');

  refreshTickets = () => {
    void this.loadTicketWorkspace();
  };

  disconnected() {
    this.ticketSessionSubscription?.dispose();
    super.disconnected();
  }
}

export = TicketsViewModel;
