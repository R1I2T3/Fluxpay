import * as ko from 'knockout';
import { ticketApi } from '../services/flux-api';
import { Page } from '../services/page';

type Ticket = {
  id: string;
  userId: string;
  paymentId?: string | null;
  subject: string;
  body: string;
  status: string;
  assigneeAdminId?: string | null;
  createdAt: string;
  updatedAt: string;
};

const PAGE_SIZE = 20;

class AdminTicketsViewModel extends Page {
  tickets = ko.observableArray<Ticket>([]);
  total = ko.observable(0);
  status = ko.observable('ALL');
  ticketPage = ko.observable(0);
  currentAdminId = ko.pureComputed(() => this.session.user()?.id || '');
  private ticketSessionSubscription?: { dispose(): void };

  constructor(params: any) {
    super('admin-tickets', params);
    this.ticketSessionSubscription = this.session.user.subscribe(() => {
      void this.loadTickets();
    });
    // The base Page load finishes first; then fetch the ticket review queue.
    window.setTimeout(() => void this.loadTickets(), 0);
  }

  private async fetchTickets() {
    if (!this.session.user()) {
      await this.session.restore();
    }
    if (!this.session.isAdmin()) {
      this.tickets([]);
      this.total(0);
      return;
    }
    const result = await ticketApi.listForAdmin(this.status(), this.ticketPage(), PAGE_SIZE);
    this.tickets(result.items || []);
    this.total(Number(result.total || 0));
  }

  loadTickets = () => this.run(() => this.fetchTickets());

  changeFilter = () => {
    this.ticketPage(0);
    void this.loadTickets();
  };

  previousPage = () => {
    this.ticketPage(Math.max(0, this.ticketPage() - 1));
    void this.loadTickets();
  };

  nextPage = () => {
    if ((this.ticketPage() + 1) * PAGE_SIZE >= this.total()) {
      return;
    }
    this.ticketPage(this.ticketPage() + 1);
    void this.loadTickets();
  };

  assignToMe = (ticket: Ticket) => {
    if (ticket.status === 'CLOSED') {
      return;
    }
    void this.update(ticket, ticket.status, this.currentAdminId(), 'Ticket assigned to you.');
  };

  moveTo = (ticket: Ticket, status: string) => {
    void this.update(ticket, status, undefined, `Ticket marked ${this.label(status)}.`);
  };

  private async update(
    ticket: Ticket, status: string, assigneeAdminId: string | undefined, success: string) {
    await this.run(async () => {
      if (!this.session.isAdmin()) {
        throw new Error('An administrator account is required to manage tickets.');
      }
      await ticketApi.updateForAdmin(ticket.id, {
        status,
        ...(assigneeAdminId ? { assigneeAdminId } : {})
      });
      await this.fetchTickets();
    }, success);
  }

  disconnected() {
    this.ticketSessionSubscription?.dispose();
    super.disconnected();
  }
}

export = AdminTicketsViewModel;
