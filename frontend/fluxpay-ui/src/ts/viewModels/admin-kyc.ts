// viewModels/admin-kyc.ts
import * as ko from 'knockout';
import { Page } from '../services/page';
import {
  composeDecisionReason,
  focusRecordHeading,
  isOlderThanHours,
  maskIdentifier,
  prioritizeKycReviews,
} from '../services/admin-console';
import { navigate } from '../services/session';

type KycView = 'PENDING' | 'AGING' | 'DOCUMENTS_UNAVAILABLE' | 'VERIFIED' | 'REJECTED';

class AdminKycViewModel extends Page {
  savedView = ko.observable<KycView>('PENDING');
  decision = ko.observable<'approve' | 'reject'>('approve');
  reasonCode = ko.observable('VERIFIED_DOCUMENTS');
  notes = ko.observable('');
  readonly reasonCodes = {
    approve: [{ value: 'VERIFIED_DOCUMENTS', label: 'Documents verified' }],
    reject: [
      { value: 'DOCUMENT_UNREADABLE', label: 'Document unreadable' },
      { value: 'DETAILS_DO_NOT_MATCH', label: 'Submitted details do not match' },
      { value: 'DOCUMENT_EXPIRED', label: 'Document expired' },
      { value: 'OTHER', label: 'Other' },
    ],
  };
  visibleReviews = ko.pureComputed(() =>
    prioritizeKycReviews(this.cases()).filter((row) => {
      const query = this.search().trim().toLowerCase();
      const matchesText =
        !query ||
        (row.fullName + ' ' + row.email + ' ' + row.applicationId).toLowerCase().includes(query);
      const matchesView =
        this.savedView() === 'AGING'
          ? row.status === 'PENDING' && isOlderThanHours(row.submittedAt, 24)
          : this.savedView() === 'DOCUMENTS_UNAVAILABLE'
            ? row.status === 'PENDING' &&
              (!row.documents.length || row.documents.some((item: any) => !item.available))
            : true;
      return matchesText && matchesView;
    }),
  );
  maskDocument = maskIdentifier;

  constructor(params: any) {
    super('admin', params);
    const view = String(params?.params?.view || 'PENDING').toUpperCase() as KycView;
    if (['PENDING', 'AGING', 'DOCUMENTS_UNAVAILABLE', 'VERIFIED', 'REJECTED'].includes(view))
      this.savedView(view);
    this.adminStatus(
      ['VERIFIED', 'REJECTED'].includes(this.savedView()) ? this.savedView() : 'PENDING',
    );
    const applicationId = String(params?.params?.applicationId || '');
    this.caseSubscription = this.cases.subscribe((rows) => {
      if (!this.session.isAdmin()) {
        if (rows.length) this.cases([]);
        return;
      }
      const match = applicationId && rows.find((row: any) => row.applicationId === applicationId);
      if (match) this.selectReview(match);
    });
  }
  changeView = () => {
    this.adminStatus(
      ['VERIFIED', 'REJECTED'].includes(this.savedView()) ? this.savedView() : 'PENDING',
    );
    this.adminFilter();
    navigate('admin-kyc', { view: this.savedView() });
  };
  selectReview = (row: any, event?: { detail: number }) => {
    this.openReview(row);
    this.decision('approve');
    this.reasonCode('VERIFIED_DOCUMENTS');
    this.notes('');
    if (event) focusRecordHeading(event, 'kyc-record-heading');
  };
  prepareDecision = () => {
    const approve = this.decision() === 'approve';
    if (approve && (!this.reviewConsent() || !this.reviewDocumentsAvailable())) {
      this.error('Open the documents and confirm the manual review.');
      return;
    }
    try {
      this.reviewReason(composeDecisionReason(this.reasonCode(), this.notes()));
    } catch (error: any) {
      this.error(error.message);
      return;
    }
    this.reviewDecision(this.decision());
  };
  confirmDecision = () => this.decide(this.reviewDecision() === 'approve');
  private caseSubscription!: { dispose(): void };
  disconnected() {
    this.caseSubscription.dispose();
    super.disconnected();
  }
}
export = AdminKycViewModel;
