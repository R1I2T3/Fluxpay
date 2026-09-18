import * as ko from 'knockout';
import { Page } from '../services/page';
import { ticketApi } from '../services/flux-api';

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

class TicketsViewModel extends Page {
  tickets = ko.observableArray<Ticket>([]);
  total = ko.observable(0);
  subject = ko.observable('');
  body = ko.observable('');
  paymentId = ko.observable('');
  private ticketSessionSubscription?: { dispose(): void };

  constructor(params: any) {
    super('tickets', params);
    this.ticketSessionSubscription = this.session.user.subscribe(user => {
      if (user) {
        void this.loadTickets();
      } else {
        this.tickets([]);
        this.total(0);
      }
    });
    // Page's base constructor performs its generic load before this view model exists. Defer the
    // ticket request until that short generic load has released its busy state.
    window.setTimeout(() => void this.loadTickets(), 0);
  }

  private async fetchTickets() {
    if (!this.session.user()) {
      this.tickets([]);
      this.total(0);
      return;
    }
    const result = await ticketApi.list();
    this.tickets(result.items || []);
    this.total(Number(result.total || 0));
  }

  loadTickets = () => this.run(() => this.fetchTickets());

  createTicket = () =>
    this.run(async () => {
      const subject = this.subject().trim();
      const body = this.body().trim();
      const paymentId = this.paymentId().trim();
      if (!subject || subject.length > 120) {
        throw new Error('Enter a subject between 1 and 120 characters.');
      }
      if (!body || body.length > 4000) {
        throw new Error('Enter a message between 1 and 4000 characters.');
      }
      if (paymentId && !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(paymentId)) {
        throw new Error('Enter a valid payment UUID, or leave it blank.');
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
    void this.loadTickets();
  };

  disconnected() {
    this.ticketSessionSubscription?.dispose();
    super.disconnected();
  }
}

export = TicketsViewModel;
