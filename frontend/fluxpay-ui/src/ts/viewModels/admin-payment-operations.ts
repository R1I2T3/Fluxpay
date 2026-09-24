import * as ko from 'knockout';
import { fluxApi } from '../services/flux-api';
import type {
  PaymentOperationsDelivery,
  PaymentOperationsOutboxEvent,
  PaymentOperationsResponse,
  PaymentOperationsTimelineEvent,
} from '../services/flux-api';
import { session } from '../services/session';

type LifecycleStageId = 'confirmation' | 'payout' | 'recovery' | 'refund';
type LifecycleStatus = 'Complete' | 'Active' | 'Warning' | 'Neutral';
type LifecycleTone = 'success' | 'info' | 'warning' | 'neutral';

type PaymentOperationsEventRow = {
  key: string;
  eventId: string;
  eventType: string;
  stage: LifecycleStageId | null;
  aggregateSequence: number;
  createdAt: string;
  occurredAt: string | null;
  outbox: PaymentOperationsOutboxEvent | null;
  delivery: PaymentOperationsDelivery | null;
  timeline: PaymentOperationsTimelineEvent | null;
  timelinePersisted: boolean;
  command: boolean;
  payload: unknown;
  kafkaTopic: string;
  correlationId: string | null;
  consumptionLabel: string;
};

type IntervalScheduler = {
  setInterval: (callback: () => void, delay: number) => number;
  clearInterval: (timer: number) => void;
};

const STAGES = [
  { id: 'confirmation', label: 'Payment confirmation' },
  { id: 'payout', label: 'Payout execution' },
  { id: 'recovery', label: 'Recovery / retry' },
  { id: 'refund', label: 'Refund' },
] as const;

function consumptionLabel(event: PaymentOperationsOutboxEvent, timelinePersisted: boolean): string {
  if (event.eventType === 'payout.retry' || event.eventType === 'payout.refund')
    return 'Operational recovery command; not a customer timeline event';
  if (timelinePersisted) return 'Persisted to customer timeline';
  if (event.delivery.state === 'SENT')
    return 'Published to Kafka; timeline persistence is not yet recorded';
  return 'Committed to the outbox; delivery is not yet published';
}

function stageFor(eventType: string): LifecycleStageId | null {
  if (
    [
      'payment.initiated',
      'payment.route.selected',
      'payment.screening.completed',
      'payment.review.requested',
    ].includes(eventType)
  )
    return 'confirmation';
  if (['payout.submitted', 'payout.completed', 'payout.failed'].includes(eventType))
    return 'payout';
  if (eventType === 'payout.retry') return 'recovery';
  if (['payout.refund', 'payment.refunded'].includes(eventType)) return 'refund';
  return null;
}

function eventTypes(response?: PaymentOperationsResponse): string[] {
  if (!response) return [];
  return [
    ...(response.outboxEvents || []).map((event) => event.eventType),
    ...(response.timelineEvents || []).map((event) => event.eventType),
  ];
}

function hasEvent(response: PaymentOperationsResponse | undefined, eventType: string): boolean {
  return eventTypes(response).includes(eventType);
}

function stageStatus(
  stage: LifecycleStageId,
  response?: PaymentOperationsResponse,
): LifecycleStatus {
  if (!response) return 'Neutral';
  const decision = response.recovery?.decision;
  if (stage === 'confirmation')
    return hasEvent(response, 'payment.initiated') ? 'Complete' : 'Neutral';
  if (stage === 'payout') {
    if (hasEvent(response, 'payout.completed')) return 'Complete';
    if (
      hasEvent(response, 'payout.failed') ||
      response.payment?.status === 'FAILED' ||
      decision === 'RECONCILIATION_REQUIRED'
    )
      return 'Warning';
    if (response.payment?.status === 'PROCESSING' || hasEvent(response, 'payout.submitted'))
      return 'Active';
    return 'Neutral';
  }
  if (stage === 'recovery') {
    if (decision === 'RETRY_SCHEDULED') return 'Active';
    if (decision === 'REFUND_SCHEDULED' || decision === 'REFUNDED') return 'Complete';
    if (decision === 'STALE') return 'Warning';
    return 'Neutral';
  }
  if (decision === 'REFUND_SCHEDULED') return 'Active';
  if (decision === 'REFUNDED' || hasEvent(response, 'payment.refunded')) return 'Complete';
  return 'Neutral';
}

function stageTone(stage: LifecycleStageId, response?: PaymentOperationsResponse): LifecycleTone {
  switch (stageStatus(stage, response)) {
    case 'Complete':
      return 'success';
    case 'Active':
      return 'info';
    case 'Warning':
      return 'warning';
    default:
      return 'neutral';
  }
}

function stageDescription(stage: LifecycleStageId): string {
  switch (stage) {
    case 'confirmation':
      return 'Payment initiation, routing, and review evidence.';
    case 'payout':
      return 'Payout submission and provider outcome evidence.';
    case 'recovery':
      return 'Retry, reconciliation, and operational command evidence.';
    case 'refund':
      return 'Refund decision and ledger evidence.';
  }
}

function rowTime(row: PaymentOperationsEventRow): number {
  const value = Date.parse(row.occurredAt || row.createdAt);
  return Number.isFinite(value) ? value : Number.POSITIVE_INFINITY;
}

function rowSequence(row: PaymentOperationsEventRow): number {
  return Number.isFinite(row.aggregateSequence) ? row.aggregateSequence : 0;
}

function compareEventRows(
  left: PaymentOperationsEventRow,
  right: PaymentOperationsEventRow,
): number {
  return (
    rowSequence(left) - rowSequence(right) ||
    rowTime(left) - rowTime(right) ||
    (left.eventId < right.eventId ? -1 : left.eventId > right.eventId ? 1 : 0)
  );
}

function buildEventRows(response?: PaymentOperationsResponse): PaymentOperationsEventRow[] {
  if (!response) return [];
  const outboxEvents = response.outboxEvents || [];
  const timelineEvents = response.timelineEvents || [];
  const timelineById = new Map(timelineEvents.map((event) => [event.eventId, event]));
  const rows: PaymentOperationsEventRow[] = outboxEvents.map((event) => ({
    key: event.eventId,
    eventId: event.eventId,
    eventType: event.eventType,
    stage: stageFor(event.eventType),
    aggregateSequence: event.aggregateSequence,
    createdAt: event.createdAt,
    occurredAt: timelineById.get(event.eventId)?.occurredAt ?? null,
    outbox: event,
    delivery: event.delivery,
    timeline: timelineById.get(event.eventId) ?? null,
    timelinePersisted: timelineById.has(event.eventId),
    command: event.eventType === 'payout.retry' || event.eventType === 'payout.refund',
    payload: event.payload,
    kafkaTopic: event.eventType,
    correlationId: timelineById.get(event.eventId)?.correlationId ?? null,
    consumptionLabel: consumptionLabel(event, timelineById.has(event.eventId)),
  }));
  const outboxIds = new Set(outboxEvents.map((event) => event.eventId));
  rows.push(
    ...timelineEvents
      .filter((event) => !outboxIds.has(event.eventId))
      .map((event) => ({
        key: event.eventId,
        eventId: event.eventId,
        eventType: event.eventType,
        stage: stageFor(event.eventType),
        aggregateSequence: Number.MAX_SAFE_INTEGER,
        createdAt: event.occurredAt,
        occurredAt: event.occurredAt,
        outbox: null,
        delivery: null,
        timeline: event,
        timelinePersisted: true,
        command: event.eventType === 'payout.retry' || event.eventType === 'payout.refund',
        payload: event.payload,
        kafkaTopic: event.kafkaTopic || event.eventType,
        correlationId: event.correlationId,
        consumptionLabel:
          event.eventType === 'payout.retry' || event.eventType === 'payout.refund'
            ? 'Operational recovery command; not a customer timeline event'
            : 'Persisted to customer timeline',
      })),
  );
  return rows.sort(compareEventRows);
}

function messageFor(error: unknown, fallback: string): string {
  if (typeof error === 'string' && error) return error;
  if (error && typeof error === 'object' && 'message' in error) {
    const message = (error as { message?: unknown }).message;
    if (message) return String(message);
  }
  return fallback;
}

function documentIsVisible(): boolean {
  if (typeof document === 'undefined') return true;
  return document.visibilityState !== 'hidden' && !document.hidden;
}

function intervalScheduler(): IntervalScheduler | undefined {
  if (typeof window !== 'undefined') {
    const candidate = window as unknown as Partial<IntervalScheduler>;
    if (
      typeof candidate.setInterval === 'function' &&
      typeof candidate.clearInterval === 'function'
    )
      return candidate as IntervalScheduler;
  }
  const candidate = globalThis as unknown as Partial<IntervalScheduler>;
  if (typeof candidate.setInterval === 'function' && typeof candidate.clearInterval === 'function')
    return candidate as IntervalScheduler;
  return undefined;
}

class AdminPaymentOperationsViewModel {
  session = session;
  paymentId = ko.observable('');
  snapshot = ko.observable<PaymentOperationsResponse | undefined>();
  busy = ko.observable(false);
  error = ko.observable('');
  refreshWarning = ko.observable('');

  eventRows = ko.pureComputed(() => buildEventRows(this.snapshot()));
  unclassifiedEvents = ko.pureComputed(() => this.eventRows().filter((row) => !row.stage));
  lifecycleGroups = ko.pureComputed(() =>
    STAGES.map((stage) => ({
      ...stage,
      status: stageStatus(stage.id, this.snapshot()),
      description: stageDescription(stage.id),
      tone: stageTone(stage.id, this.snapshot()),
      events: this.eventRows().filter((row) => row.stage === stage.id),
    })),
  );
  attempts = ko.pureComputed(() => this.snapshot()?.attempts || []);
  operations = ko.pureComputed(() => this.snapshot()?.operations || []);
  ledgerEntries = ko.pureComputed(() => this.snapshot()?.ledgerEntries || []);
  recovery = ko.pureComputed(() => this.snapshot()?.recovery);

  label = (value: unknown): string =>
    String(value ?? '')
      .toLowerCase()
      .replace(/_/g, ' ')
      .replace(/^./, (character) => character.toUpperCase());
  dateTime = (value: string | null | undefined): string => {
    if (!value) return '—';
    const date = new Date(value);
    return Number.isNaN(date.getTime()) ? value : date.toLocaleString();
  };
  json = (value: unknown): string => JSON.stringify(value, null, 2);
  deliveryLabel = (value?: string): string =>
    value === 'PENDING'
      ? 'Committed — pending publication'
      : value === 'SENDING'
        ? 'Publishing to Kafka'
        : value === 'SENT'
          ? 'Published to Kafka'
          : 'Delivery state unavailable';

  private generation = 0;
  private stopped = false;
  private timer?: number;
  private timerClear?: (timer: number) => void;
  private lastPaymentId = '';
  private paymentIdSubscription: ko.Subscription;

  constructor(params: any) {
    const initialId = String(params?.params?.paymentId || '').trim();
    if (initialId) this.paymentId(initialId);
    this.lastPaymentId = initialId;
    this.paymentIdSubscription = this.paymentId.subscribe((value) => {
      const nextId = String(value || '').trim();
      if (nextId === this.lastPaymentId) return;
      this.lastPaymentId = nextId;
      this.generation += 1;
      this.clearPolling();
      this.snapshot(undefined);
      this.refreshWarning('');
      this.error('');
      this.busy(false);
    });
    void this.restoreSession();
  }

  private async restoreSession() {
    try {
      if (!session.user()) await session.restore();
      if (this.paymentId()) void this.lookup();
    } catch (error: unknown) {
      if (!this.stopped)
        this.error(messageFor(error, 'Unable to restore the administrator session.'));
    }
  }

  private current(generation: number, id: string): boolean {
    return (
      !this.stopped &&
      generation === this.generation &&
      String(this.paymentId() || '').trim() === id
    );
  }

  private clearPolling() {
    if (this.timer !== undefined) {
      if (this.timerClear) this.timerClear(this.timer);
      else clearInterval(this.timer);
    }
    this.timer = undefined;
    this.timerClear = undefined;
  }

  private schedulePolling(response?: PaymentOperationsResponse) {
    this.clearPolling();
    const delay = this.pollDelay(response);
    if (delay === undefined || this.stopped || !this.paymentId()) return;
    const scheduler = intervalScheduler();
    if (!scheduler) return;
    const generation = this.generation;
    this.timer = scheduler.setInterval(() => {
      if (this.stopped || generation !== this.generation || this.busy() || !documentIsVisible())
        return;
      void this.refresh();
    }, delay);
    this.timerClear = (timer) => scheduler.clearInterval(timer);
  }

  pollDelay(response?: PaymentOperationsResponse): number | undefined {
    if (!response) return undefined;
    const active =
      response.payment?.status === 'PROCESSING' ||
      (response.outboxEvents || []).some((event) =>
        ['PENDING', 'SENDING'].includes(event.delivery.state),
      ) ||
      (response.operations || []).some((operation) => operation.status === 'IN_PROGRESS');
    if (active) return 1000;
    if (response.payment?.status === 'UNDER_REVIEW') return 5000;
    return undefined;
  }

  lookup = async (): Promise<void> => {
    if (this.stopped) return;
    const id = String(this.paymentId() || '').trim();
    if (id !== this.paymentId()) this.paymentId(id);
    if (!id) {
      this.generation += 1;
      this.clearPolling();
      this.snapshot(undefined);
      this.refreshWarning('');
      this.error('Enter a payment ID.');
      this.busy(false);
      return;
    }
    await this.read(id, true);
  };

  refresh = async (): Promise<void> => {
    if (this.stopped) return;
    const id = String(this.paymentId() || '').trim();
    if (!id) {
      this.error('Enter a payment ID.');
      return;
    }
    await this.read(id, false);
  };

  private async read(id: string, replaceSnapshot: boolean) {
    if (this.stopped) return;
    const generation = ++this.generation;
    this.clearPolling();
    this.busy(true);
    this.error('');
    this.refreshWarning('');
    if (replaceSnapshot) this.snapshot(undefined);
    try {
      const response = await fluxApi.adminPaymentOperations(id);
      if (!this.current(generation, id)) return;
      this.snapshot(response);
      this.error('');
      this.refreshWarning('');
      this.schedulePolling(response);
    } catch (error: unknown) {
      if (!this.current(generation, id)) return;
      const message = messageFor(error, 'The payment operations snapshot could not be refreshed.');
      if (this.snapshot()) this.refreshWarning(message);
      else this.error(message);
      this.schedulePolling(this.snapshot());
    } finally {
      if (this.current(generation, id)) this.busy(false);
    }
  }

  disconnected() {
    if (this.stopped) return;
    this.stopped = true;
    this.generation += 1;
    this.clearPolling();
    this.paymentIdSubscription.dispose();
    this.busy(false);
  }
}

export = AdminPaymentOperationsViewModel;
