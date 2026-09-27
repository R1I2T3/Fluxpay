import * as ko from 'knockout';
import { ticketApi, TicketResponse } from '../services/flux-api';
import {
  copyAdminIdentifier,
  focusRecordHeading,
  isOlderThanHours,
  nextTicketAction,
  prioritizeSupportTickets,
} from '../services/admin-console';
import { navigate } from '../services/session';
import { Page } from '../services/page';

type TicketView = 'OPEN' | 'UNASSIGNED' | 'ASSIGNED_TO_ME' | 'AGING' | 'RESOLVED' | 'CLOSED';

const PAGE_SIZE = 20;

class AdminTicketsViewModel extends Page {
  tickets = ko.observableArray<TicketResponse>([]);
  total = ko.observable(0);
  status = ko.observable('ALL');
  ticketPage = ko.observable(0);
  currentAdminId = ko.pureComputed(() => this.session.user()?.id || '');
  savedView = ko.observable<TicketView>('OPEN');
  selectedTicket = ko.observable<TicketResponse>();
  private ticketEpoch = 0;
  private preferredTicketId = '';
  private ticketSessionSubscription?: { dispose(): void };

  visibleTickets = ko.pureComputed(() =>
    prioritizeSupportTickets(this.tickets()).filter((ticket) => {
      const query = this.search().trim().toLowerCase();
      const text = (
        ticket.subject +
        ' ' +
        ticket.body +
        ' ' +
        ticket.userId +
        ' ' +
        (ticket.paymentId || '')
      ).toLowerCase();
      const view = this.savedView();
      const matchesView =
        view === 'UNASSIGNED'
          ? !ticket.assigneeAdminId && ticket.status !== 'CLOSED'
          : view === 'ASSIGNED_TO_ME'
            ? ticket.assigneeAdminId === this.currentAdminId() && ticket.status !== 'CLOSED'
            : view === 'AGING'
              ? ['OPEN', 'IN_PROGRESS'].includes(ticket.status) &&
                isOlderThanHours(ticket.createdAt, 24)
              : view === 'OPEN'
                ? ['OPEN', 'IN_PROGRESS'].includes(ticket.status)
                : ticket.status === view;
      return matchesView && (!query || text.includes(query));
    }),
  );
  selectedNextAction = ko.pureComputed(() => nextTicketAction(this.selectedTicket()?.status || ''));

  constructor(params: any) {
    super('admin-tickets', params);
    const view = String(params?.params?.view || 'OPEN').toUpperCase() as TicketView;
    if (['OPEN', 'UNASSIGNED', 'ASSIGNED_TO_ME', 'AGING', 'RESOLVED', 'CLOSED'].includes(view))
      this.savedView(view);
    this.status(['RESOLVED', 'CLOSED'].includes(this.savedView()) ? this.savedView() : 'ALL');
    this.preferredTicketId = String(params?.params?.ticketId || '');
    this.ticketSessionSubscription = this.session.user.subscribe(() => {
      this.ticketEpoch++;
      this.tickets([]);
      this.total(0);
      this.selectedTicket(undefined);
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
    const epoch = ++this.ticketEpoch;
    const result = await ticketApi.listForAdmin(this.status(), this.ticketPage(), PAGE_SIZE);
    if (epoch !== this.ticketEpoch || !this.session.isAdmin()) {
      return;
    }
    const items = result.items || [];
    this.tickets(items);
    this.total(Number(result.total || 0));
    const preferred =
      this.preferredTicketId && items.find((item) => item.id === this.preferredTicketId);
    if (preferred) {
      this.selectedTicket(preferred);
      this.preferredTicketId = '';
      return;
    }
    const currentId = this.selectedTicket()?.id;
    const retained = currentId && items.find((item) => item.id === currentId);
    this.selectedTicket(retained || undefined);
  }

  loadTickets = () => this.run(() => this.fetchTickets());

  changeView = () => {
    this.status(['RESOLVED', 'CLOSED'].includes(this.savedView()) ? this.savedView() : 'ALL');
    this.ticketPage(0);
    void this.loadTickets();
    navigate('admin-tickets', { view: this.savedView() });
  };

  selectTicket = (ticket: TicketResponse, event?: { detail: number }) => {
    this.selectedTicket(ticket);
    if (event) focusRecordHeading(event, 'support-record-heading');
  };

  closeTicket = () => {
    if (!this.busy()) this.selectedTicket(undefined);
  };

  copyIdentifier = async (value: string | null | undefined, label: string) => {
    this.error('');
    this.notice('');
    try {
      this.notice(await copyAdminIdentifier(value, label, navigator.clipboard));
    } catch (error: any) {
      this.error(error.message || 'Unable to copy ' + label.toLowerCase() + '.');
    }
  };

  ticketAge = (ticket: TicketResponse) => {
    const created = Date.parse(ticket.createdAt);
    if (!Number.isFinite(created)) return 'Age unavailable';
    const hours = Math.max(0, Math.floor((Date.now() - created) / (60 * 60 * 1000)));
    const days = Math.floor(hours / 24);
    return hours >= 24
      ? `Waiting ${days} day${days === 1 ? '' : 's'}`
      : `Waiting ${hours} hour${hours === 1 ? '' : 's'}`;
  };

  runNextAction = () => {
    const ticket = this.selectedTicket();
    const action = this.selectedNextAction();
    if (ticket && action) this.moveTo(ticket, action.status);
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

  assignToMe = (ticket: TicketResponse) => {
    if (ticket.status === 'CLOSED') {
      return;
    }
    void this.update(ticket, ticket.status, this.currentAdminId(), 'Ticket assigned to you.');
  };

  moveTo = (ticket: TicketResponse, status: string) => {
    void this.update(ticket, status, undefined, `Ticket marked ${this.label(status)}.`);
  };

  private async update(
    ticket: TicketResponse,
    status: string,
    assigneeAdminId: string | undefined,
    success: string,
  ) {
    await this.run(async () => {
      if (!this.session.isAdmin()) {
        throw new Error('An administrator account is required to manage tickets.');
      }
      await ticketApi.updateForAdmin(ticket.id, {
        status,
        ...(assigneeAdminId ? { assigneeAdminId } : {}),
      });
      await this.fetchTickets();
    }, success);
  }

  disconnected() {
    this.ticketEpoch++;
    this.tickets([]);
    this.total(0);
    this.selectedTicket(undefined);
    this.ticketSessionSubscription?.dispose();
    super.disconnected();
  }
}

export = AdminTicketsViewModel;
