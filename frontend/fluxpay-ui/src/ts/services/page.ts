import * as ko from 'knockout';
import { fluxApi as api } from './flux-api';
import { session, navigate } from './session';
import { movementDescription } from './activity';
import './experience-dialog';

export class Page {
  session = session;
  busy = ko.observable(false);
  error = ko.observable('');
  notice = ko.observable('');
  search = ko.observable('');
  wallets = ko.observableArray<any>([]);
  recipients = ko.observableArray<any>([]);
  payments = ko.observableArray<any>([]);
  routes = ko.observableArray<any>([]);
  ledger = ko.observableArray<any>([]);
  cases = ko.observableArray<any>([]);
  timeline = ko.observableArray<any>([]);
  quotes = ko.observableArray<any>([]);
  page = ko.observable(0);
  total = ko.observable(0);
  ledgerPage = ko.observable(0);
  ledgerTotal = ko.observable(0);
  ledgerWallet = ko.observable<any>();
  email = ko.observable('');
  password = ko.observable('');
  fullName = ko.observable('');
  kyc = ko.observable<any>();
  docType = ko.observable('PAN');
  docNumber = ko.observable('');
  documents = ko.observableArray<any>([]);
  documentPreview = ko.observable<any>();
  previewLoading = ko.observable(false);
  previewError = ko.observable('');
  private previewAbort?: AbortController;
  private previewVersion = 0;
  fileSize = (size: number) =>
    size >= 1024 * 1024
      ? (size / (1024 * 1024)).toFixed(1) + ' MB'
      : Math.max(1, Math.round(size / 1024)) + ' KB';
  closeDocument = () => {
    this.previewVersion++;
    this.previewAbort?.abort();
    const current = this.documentPreview();
    if (current?.url) URL.revokeObjectURL(current.url);
    this.documentPreview(undefined);
    this.previewLoading(false);
    this.previewError('');
  };
  openDocument = async (file: any) => {
    this.closeDocument();
    this.documentPreview({ ...file, url: '' });
    this.previewLoading(true);
    const version = this.previewVersion;
    this.previewAbort = new AbortController();
    try {
      if (!file.available || !file.id)
        throw new Error(
          'The original file was not stored with this older submission. Please contact support.',
        );
      const blob = await api.kycDocument(file.id, this.previewAbort.signal);
      if (version !== this.previewVersion || this.disposed) return;
      this.documentPreview({ ...file, url: URL.createObjectURL(blob), fileType: blob.type });
    } catch (e: any) {
      if (version === this.previewVersion && e.name !== 'AbortError')
        this.previewError(e.message || 'Unable to open this document.');
    } finally {
      if (version === this.previewVersion) this.previewLoading(false);
    }
  };
  currency = ko.observable('USD');
  amount = ko.observable('100');
  fundAmount = ko.observable('500');
  from = ko.observable('USD');
  to = ko.observable('INR');
  rate = ko.observable<any>();
  conversion = ko.observable<any>();
  walletId = ko.observable('');
  recipientId = ko.observable('');
  purpose = ko.observable('FAMILY_SUPPORT');
  preference = ko.observable('BALANCED');
  recipientEditing = ko.observable<any>();
  recipientFormOpen = ko.observable(false);
  recipientName = ko.observable('');
  bankName = ko.observable('');
  account = ko.observable('');
  country = ko.observable('IN');
  recipientCurrency = ko.observable('INR');
  recipientStatus = ko.observable('ACTIVE');
  payment = ko.observable<any>();
  paymentId = ko.observable('');
  selectedQuote = ko.observable<any>();
  quoteExpires = ko.observable('');
  now = ko.observable(Date.now());
  step = ko.observable(1);
  outcome = ko.observable<any>();
  recommendation = ko.observable<any>();
  confirmAction = ko.observable('');
  adminStatus = ko.observable('PENDING');
  adminPage = ko.observable(0);
  review = ko.observable<any>();
  reviewReason = ko.observable('');
  reviewConsent = ko.observable(false);
  reviewDecision = ko.observable('');
  reviewDocumentsAvailable = ko.pureComputed(
    () =>
      !!this.review()?.documents?.length && this.review().documents.every((d: any) => d.available),
  );
  filter = ko.observable('ALL');
  typeFilter = ko.observable('');
  fromDate = ko.observable('');
  toDate = ko.observable('');
  overviewCurrency = ko.observable('USD');
  overviewWallet = ko.pureComputed(
    () => this.wallets().find((w) => w.currency === this.overviewCurrency()) || this.wallets()[0],
  );
  completedCount = ko.pureComputed(
    () => this.payments().filter((p) => p.status === 'COMPLETED').length,
  );
  pendingCount = ko.pureComputed(
    () => this.payments().filter((p) => ['PROCESSING', 'UNDER_REVIEW'].includes(p.status)).length,
  );
  onHold = ko.pureComputed(() =>
    this.payments().filter((p) => ['PROCESSING', 'QUOTED'].includes(p.status)),
  );
  processingForWallet = (wallet: any) =>
    this.onHold().filter(
      (p) => p.sourceWalletId === wallet.walletId && p.sourceCurrency === wallet.currency,
    );
  processingAmount = (wallet: any) =>
    this.processingForWallet(wallet).reduce(
      (sum, p) => sum + Math.round(Number(p.sourceAmount || 0) * 10000),
      0,
    ) / 10000;
  quotedForWallet = (wallet: any) =>
    this.processingForWallet(wallet).filter((p) => p.status === 'QUOTED');
  holdCaption = (wallet: any) =>
    this.quotedForWallet(wallet).length ? 'On hold · processing / quoted' : 'On hold · processing';
  movementDescription = movementDescription;
  private timer?: number;
  private polling?: number;
  private disposed = false;
  private sessionListener = () => {
    this.closeDocument();
    this.documents([]);
    this.kyc(undefined);
    this.review(undefined);
    void this.load();
  };
  isAuthenticated = ko.pureComputed(() => !!session.user());
  filteredRecipients = ko.pureComputed(() =>
    this.recipients().filter((r) =>
      (r.name + ' ' + r.bankName).toLowerCase().includes(this.search().toLowerCase()),
    ),
  );
  filteredPayments = ko.pureComputed(() => this.payments().filter((p) => this.matchesActivity(p)));
  matchesActivity = (p: any) => {
    const type = p.type || p.entry_type || p.entryType || 'SEND_MONEY';
    const created = Date.parse(p.createdAt);
    const after = this.fromDate() ? new Date(this.fromDate() + 'T00:00:00').getTime() : -Infinity;
    const end = this.toDate() ? new Date(this.toDate() + 'T00:00:00') : null;
    if (end) end.setDate(end.getDate() + 1);
    return (
      (this.filter() === 'ALL' ||
        (this.filter() === 'ON_HOLD'
          ? ['PROCESSING', 'UNDER_REVIEW'].includes(p.status)
          : p.status === this.filter())) &&
      (!this.typeFilter() || type === this.typeFilter()) &&
      (!this.fromDate() || created >= after) &&
      (!end || created < end.getTime()) &&
      (
        p.id +
        ' ' +
        (p.narration || '') +
        ' ' +
        type +
        ' ' +
        (p.sourceCurrency || '') +
        ' ' +
        (p.journalReference || '') +
        ' ' +
        this.recipientLabel(p.recipientId)
      )
        .toLowerCase()
        .includes(this.search().trim().toLowerCase())
    );
  };
  activeRecipients = ko.pureComputed(() => this.recipients().filter((r) => r.status === 'ACTIVE'));
  remaining = ko.pureComputed(
    () => Math.max(0, Math.ceil((Date.parse(this.quoteExpires()) - this.now()) / 1000)) || 0,
  );
  quoteValid = ko.pureComputed(() => this.quotes().length > 0 && this.remaining() > 0);
  currentWallet = ko.pureComputed(() => this.wallets().find((w) => w.walletId === this.walletId()));
  currentRecipient = ko.pureComputed(() =>
    this.recipients().find((r) => r.id === this.recipientId()),
  );
  pageCount = ko.pureComputed(() => Math.max(1, Math.ceil(this.total() / 20)));
  constructor(
    public screen: string,
    params: any = {},
  ) {
    this.paymentId(params.params?.id || params.params?.payment || '');
    this.timer = window.setInterval(() => this.now(Date.now()), 1000);
    window.addEventListener('fluxpay:session', this.sessionListener);
    void this.load();
  }
  disconnected() {
    this.disposed = true;
    this.closeDocument();
    clearInterval(this.timer);
    clearInterval(this.polling);
    window.removeEventListener('fluxpay:session', this.sessionListener);
  }
  money = (amount: any, currency = 'USD') =>
    new Intl.NumberFormat('en-US', {
      style: 'currency',
      currency,
      maximumFractionDigits: 2,
    }).format(Number(amount || 0));
  date = (value: any) =>
    value
      ? new Date(value).toLocaleString(undefined, {
          month: 'short',
          day: 'numeric',
          year: 'numeric',
          hour: '2-digit',
          minute: '2-digit',
        })
      : '—';
  label = (value: any) =>
    String(value || '')
      .replace(/_/g, ' ')
      .toLowerCase()
      .replace(/^./, (s) => s.toUpperCase());
  initials = (name: string) =>
    name
      ?.split(' ')
      .map((n) => n[0])
      .slice(0, 2)
      .join('')
      .toUpperCase() || 'FP';
  shortId = (id: string) => (id ? id.slice(0, 8) + '…' : '');
  recipientLabel = (id: string) => this.recipients().find((r) => r.id === id)?.name || 'Recipient';
  walletLabel = (w: any) =>
    w.currency + ' · ' + this.money(w.availableBalance, w.currency) + ' available';
  recipientOption = (r: any) => r.name + ' · ' + r.currency;
  go = (path: string) => navigate(path);
  async run(action: () => Promise<void>, success = '') {
    if (this.busy()) return;
    this.busy(true);
    this.error('');
    this.notice('');
    try {
      await action();
      if (success) this.notice(success);
    } catch (e: any) {
      this.error(e.message || 'Something went wrong. Please try again.');
    } finally {
      this.busy(false);
    }
  }
  async load() {
    if (this.screen === 'home' || ['login', 'register'].includes(this.screen)) return;
    if (!sessionStorage.getItem('fluxpay.token')) return;
    await this.run(async () => {
      switch (this.screen) {
        case 'dashboard': {
          const [w, p, r] = await Promise.all([api.wallets(), api.payments(), api.recipients()]);
          this.wallets(w);
          this.payments(p.items);
          this.total(p.total);
          this.recipients(r);
          break;
        }
        case 'account': {
          const user = await api.me();
          session.user(user);
          this.email(user.email);
          this.fullName(user.fullName);
          break;
        }
        case 'kyc':
          this.kyc(await api.kyc());
          break;
        case 'wallets':
          this.wallets(await api.wallets());
          break;
        case 'recipients':
          this.recipients(await api.recipients());
          break;
        case 'payments-new': {
          const [w, r] = await Promise.all([api.wallets(), api.recipients()]);
          this.wallets(w);
          this.recipients(r);
          this.walletId(w[0]?.walletId || '');
          this.recipientId(r.find((x) => x.status === 'ACTIVE')?.id || '');
          break;
        }
        case 'payments-list': {
          const [p, r] = await Promise.all([api.payments(this.page()), api.recipients()]);
          this.payments(p.items);
          this.total(p.total);
          this.recipients(r);
          break;
        }
        case 'tracking': {
          const [p, r, routes] = await Promise.all([
            api.payments(),
            api.recipients(),
            api.routes(),
          ]);
          this.payments(p.items);
          this.recipients(r);
          this.routes(routes.routes);
          if (this.paymentId()) await this.inspectData();
          break;
        }
        case 'admin':
          await session.restore();
          if (!session.isAdmin())
            throw new Error('An administrator account is required to access these controls.');
          this.cases(await api.adminKyc(this.adminStatus(), this.adminPage()));
          break;
      }
    });
  }
  refresh = () => {
    void this.load();
  };
  signIn = (form?: HTMLFormElement) =>
    this.run(async () => {
      // Read native form values as well: password managers may not emit input/change.
      if (form instanceof HTMLFormElement) {
        const fields = new FormData(form);
        this.email(String(fields.get('email') || this.email()));
        this.password(String(fields.get('password') || this.password()));
      }
      if (!this.email().trim() || !this.password())
        throw new Error('Enter your email and password.');
      session.set(await api.login({ email: this.email().trim(), password: this.password() }));
      navigate('dashboard');
    });
  signUp = () =>
    this.run(async () => {
      if (!this.fullName().trim() || !this.email().trim() || !this.password())
        throw new Error('Complete all fields.');
      session.set(
        await api.register({
          fullName: this.fullName().trim(),
          email: this.email().trim(),
          password: this.password(),
        }),
      );
      navigate('kyc');
    });
  saveProfile = () =>
    this.run(async () => {
      session.user(await api.updateMe({ fullName: this.fullName().trim() }));
    }, 'Your profile has been updated.');
  chooseFiles = (_: any, event: Event) => {
    const files = Array.from((event.target as HTMLInputElement).files || []);
    if (
      files.length > 4 ||
      files.some(
        (f) =>
          !['application/pdf', 'image/jpeg', 'image/png'].includes(f.type) ||
          f.size === 0 ||
          f.size > 5 * 1024 * 1024,
      )
    ) {
      this.error('Choose one to four non-empty PDF, JPG or PNG files up to 5 MB each.');
      this.documents([]);
      (event.target as HTMLInputElement).value = '';
      return;
    }
    this.documents(
      files.map((f) => ({ fileName: f.name, fileType: f.type, fileSize: f.size, file: f })),
    );
    this.error('');
  };
  submitKyc = () =>
    this.run(async () => {
      if (!this.docNumber().trim() || !this.documents().length)
        throw new Error('Enter the document number and choose at least one document.');
      this.kyc(
        await api.submitKyc({
          docType: this.docType(),
          docNumber: this.docNumber().trim(),
          documents: this.documents(),
        }),
      );
      await session.restore();
    }, 'Your verification has been submitted for review.');
  validAmount(value: string) {
    if (!/^\d+(\.\d{1,4})?$/.test(value) || Number(value) <= 0)
      throw new Error('Enter an amount greater than zero, with up to four decimal places.');
    return value;
  }
  fund = () =>
    this.run(async () => {
      await api.fund({ currency: this.currency(), amount: this.validAmount(this.fundAmount()) });
      this.wallets(await api.wallets());
    }, 'Money added to your wallet.');
  getRate = () =>
    this.run(async () => {
      this.rate(undefined);
      if (this.from() === this.to()) throw new Error('Choose two different currencies.');
      this.rate(await api.rate(this.from(), this.to()));
    });
  resetRate = () => {
    this.rate(undefined);
    this.conversion(undefined);
  };
  convert = () =>
    this.run(async () => {
      if (this.from() === this.to()) throw new Error('Choose two different currencies.');
      this.conversion(
        await api.convert({
          from: this.from(),
          to: this.to(),
          amount: this.validAmount(this.amount()),
        }),
      );
      this.wallets(await api.wallets());
    }, 'Currency exchanged successfully.');
  showLedger = (w: any) =>
    this.run(async () => {
      this.ledgerWallet(w);
      this.ledgerPage(0);
      await this.loadLedger();
    });
  async loadLedger() {
    const data = await api.ledger(this.ledgerWallet().walletId, this.ledgerPage());
    this.ledger(data.entries);
    this.ledgerTotal(data.totalElements);
  }
  ledgerPrevious = () =>
    this.run(async () => {
      this.ledgerPage(Math.max(0, this.ledgerPage() - 1));
      await this.loadLedger();
    });
  ledgerNext = () =>
    this.run(async () => {
      this.ledgerPage(this.ledgerPage() + 1);
      await this.loadLedger();
    });
  addRecipient = () => {
    this.recipientEditing(undefined);
    this.recipientName('');
    this.account('');
    this.bankName('');
    this.country('IN');
    this.recipientCurrency('INR');
    this.recipientStatus('ACTIVE');
    this.recipientFormOpen(true);
  };
  editRecipient = (r: any) => {
    this.recipientEditing(r);
    this.recipientName(r.name);
    this.account(r.account);
    this.bankName(r.bankName);
    this.country(r.country);
    this.recipientCurrency(r.currency);
    this.recipientStatus(r.status);
    this.recipientFormOpen(true);
  };
  closeRecipient = () => this.recipientFormOpen(false);
  saveRecipient = () =>
    this.run(async () => {
      const b = {
        name: this.recipientName().trim(),
        account: this.account().trim(),
        bankName: this.bankName().trim(),
        country: this.country().toUpperCase(),
        currency: this.recipientCurrency(),
        status: this.recipientStatus(),
        expectedVersion: this.recipientEditing()?.version,
      };
      if (!b.name || !b.account || !b.bankName || !/^[A-Z]{2}$/.test(b.country))
        throw new Error('Complete the recipient details and use a two-letter country code.');
      if (this.recipientEditing()) await api.updateRecipient(this.recipientEditing().id, b);
      else await api.recipient(b);
      this.recipients(await api.recipients());
      this.recipientFormOpen(false);
    }, 'Recipient saved.');
  async loadQuotes(generate = false) {
    const data = generate
      ? await api.quotes(this.paymentId())
      : await api.getQuotes(this.paymentId());
    this.quotes(data.quotes || []);
    this.quoteExpires(data.expiresAt);
    this.selectedQuote(undefined);
  }
  createDraft = () =>
    this.run(async () => {
      const wallet = this.currentWallet(),
        recipient = this.currentRecipient();
      if (!wallet || !recipient) throw new Error('Choose a wallet and an active recipient.');
      const p = await api.draft({
        sourceWalletId: wallet.walletId,
        recipientId: recipient.id,
        sourceAmount: this.validAmount(this.amount()),
        sourceCurrency: wallet.currency,
        payoutCurrency: recipient.currency,
        purpose: this.purpose(),
        preference: this.preference(),
      });
      this.payment(p);
      this.paymentId(p.id);
      this.step(2);
      await this.loadQuotes(true);
    });
  refreshQuotes = () =>
    this.run(async () => {
      await this.loadQuotes(true);
    });
  chooseQuote = (quote: any) => {
    this.selectedQuote(quote);
    this.confirmAction('confirm');
  };
  dismissAction = () => this.confirmAction('');
  confirmQuote = () =>
    this.run(async () => {
      if (!this.selectedQuote() || !this.quoteValid())
        throw new Error('This quote has expired. Refresh your quotes.');
      await this.acceptQuote();
      if (this.screen === 'tracking') await this.inspectData();
    });
  protected async acceptQuote() {
    try {
      this.payment(await api.confirm(this.paymentId(), this.selectedQuote().id));
    } catch (error) {
      // A compliance block is returned as an HTTP error after persisting REJECTED.
      const latest = await api.payment(this.paymentId()).catch(() => undefined);
      if (latest) this.payment(latest);
      if (latest?.status !== 'REJECTED') throw error;
    }
    this.confirmAction('');
    this.step(3);
    this.notice(
      this.payment()?.status === 'UNDER_REVIEW'
        ? 'Your transfer is awaiting compliance review.'
        : this.payment()?.status === 'REJECTED'
          ? 'This transfer was rejected by compliance.'
          : 'Your payment has been confirmed.',
    );
  }
  trackPayment = () => navigate('tracking', { payment: this.paymentId() });
  openPayment = (p: any) => navigate('tracking', { payment: p.id });
  paymentOption = (p: any) =>
    this.money(p.sourceAmount, p.sourceCurrency) +
    ' · ' +
    this.label(p.status) +
    ' · ' +
    p.id.slice(0, 8);
  nextPage = () => {
    this.page(this.page() + 1);
    void this.load();
  };
  previousPage = () => {
    this.page(Math.max(0, this.page() - 1));
    void this.load();
  };
  inspect = () => this.run(() => this.inspectData());
  async inspectData() {
    if (!this.paymentId()) throw new Error('Select or enter a payment ID.');
    const p = await api.payment(this.paymentId());
    this.payment(p);
    this.timeline(await api.timeline(this.paymentId()));
    this.quotes([]);
    this.selectedQuote(undefined);
    if (['QUOTED', 'PROCESSING', 'FAILED', 'UNDER_REVIEW', 'COMPLETED'].includes(p.status))
      await this.loadQuotes();
    clearInterval(this.polling);
    if (['PROCESSING', 'UNDER_REVIEW'].includes(p.status))
      this.polling = window.setInterval(() => {
        if (!this.disposed && !this.busy() && document.visibilityState === 'visible') {
          Promise.all([api.payment(this.paymentId()), api.timeline(this.paymentId())])
            .then(([p, t]) => {
              this.payment(p);
              this.timeline(t);
              if (!['PROCESSING', 'UNDER_REVIEW'].includes(p.status)) clearInterval(this.polling);
            })
            .catch(() => clearInterval(this.polling));
        }
      }, 10000);
  }
  recommend = () =>
    this.run(async () => {
      this.recommendation(await api.recommend(this.paymentId(), this.preference()));
    });
  askAction = (action: string) => {
    this.confirmAction(action);
  };
  executeAction = () =>
    this.run(async () => {
      const id = this.paymentId(),
        action = this.confirmAction();
      if (action === 'confirm') {
        if (!this.selectedQuote() || !this.quoteValid()) throw new Error('Choose a current quote.');
        await this.acceptQuote();
      } else if (action === 'cancel') await api.cancel(id);
      else if (action === 'refund') await api.refund(id);
      else {
        const q =
          this.selectedQuote() ||
          this.quotes().find((q) => q.id === this.payment()?.selectedQuoteId);
        if (!q) throw new Error('Select a quote before continuing.');
        if (action === 'payout') this.outcome(await api.payout(id, q.routeCode));
        if (action === 'retry') this.outcome(await api.retry(id, q.id));
        if (action === 'switch') this.outcome(await api.switchRoute(id, q.routeCode, q.id));
      }
      this.confirmAction('');
      if (this.screen === 'tracking') await this.inspectData();
    }, 'Payment updated. The latest status is shown below.');
  selectRecoveryQuote = (q: any) => this.selectedQuote(q);
  openReview = (c: any) => {
    this.review(c);
    this.reviewReason('');
    this.reviewConsent(false);
    this.reviewDecision('');
    this.error('');
  };
  closeReview = () => {
    if (this.busy()) return;
    this.closeDocument();
    this.review(undefined);
    this.reviewDecision('');
  };
  prepareReview = (approve: boolean) => {
    this.error('');
    if (approve && (!this.reviewConsent() || !this.reviewDocumentsAvailable())) {
      this.error('Open the documents and confirm you have checked the identity details.');
      return;
    }
    if (!approve && !this.reviewReason().trim()) {
      this.error('Explain what the customer needs to correct.');
      return;
    }
    this.reviewDecision(approve ? 'approve' : 'reject');
  };
  decide = (approve: boolean) =>
    this.run(async () => {
      if (!this.review() || this.review().status !== 'PENDING')
        throw new Error('This application has already been reviewed. Refresh the list.');
      if (approve && (!this.reviewConsent() || !this.reviewDocumentsAvailable()))
        throw new Error('Confirm you have checked the uploaded documents.');
      if (!approve && !this.reviewReason().trim())
        throw new Error('Add a standardized reason and notes before saving the decision.');
      const b = { expectedVersion: this.review().version, reason: this.reviewReason().trim() };
      if (approve) await api.approve(this.review().applicationId, b);
      else await api.reject(this.review().applicationId, b);
      this.review(undefined);
      this.reviewDecision('');
      this.cases(await api.adminKyc(this.adminStatus(), this.adminPage()));
    }, 'Verification decision saved.');
  adminNext = () => {
    this.adminPage(this.adminPage() + 1);
    void this.load();
  };
  adminPrevious = () => {
    this.adminPage(Math.max(0, this.adminPage() - 1));
    void this.load();
  };
  adminFilter = () => {
    this.adminPage(0);
    void this.load();
  };
}
