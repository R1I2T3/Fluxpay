// tests/admin-kyc.test.cjs
const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const ko = require('knockout');
const {load} = require('./helpers/load-typescript.cjs');
const read = file => fs.readFileSync(path.join(__dirname, '../src', file), 'utf8');

class PageStub {
  constructor() {
    this.session={isAdmin:()=>true};
    this.busy=ko.observable(false);this.error=ko.observable('');this.notice=ko.observable('');
    this.cases=ko.observableArray([]);this.adminStatus=ko.observable('PENDING');this.adminPage=ko.observable(0);
    this.review=ko.observable();this.reviewReason=ko.observable('');this.reviewConsent=ko.observable(false);this.reviewDecision=ko.observable('');
    this.reviewDocumentsAvailable=ko.pureComputed(()=>Boolean(this.review()?.documents?.length)&&this.review().documents.every(item=>item.available));
    // Test-only extensions for focused-view filtering and decision capture.
    this.search=ko.observable('');
    this.decideCalls=[];
    this.opened=[];
  }
  adminFilter() {}
  openReview(row) { this.review(row); this.opened.push(row); }
  openDocument() {}
  closeDocument() {}
  decide(approve) { this.decideCalls.push(approve); return Promise.resolve(); }
  disconnected() {}
}

const helpers=load('ts/services/admin-console.ts', {'./flux-api':{}}, {Date});
const ViewModel=load('ts/viewModels/admin-kyc.ts', {
  knockout:ko,
  '../services/page':{Page:PageStub},
  '../services/admin-console':helpers,
  '../services/session':{navigate:()=>{}},
  '../services/admin-dialog':{}
}, {window:{requestAnimationFrame:callback=>callback()}});

test('KYC decisions require a reason code and compose the existing reason string', () => {
  const vm=new ViewModel({params:{view:'PENDING'}});
  vm.review({status:'PENDING',documents:[{available:true}]});
  vm.reviewConsent(true);vm.decision('reject');vm.reasonCode('DOCUMENT_UNREADABLE');vm.notes('Upload a clearer image.');
  vm.prepareDecision();
  assert.equal(vm.reviewDecision(),'reject');
  assert.equal(vm.reviewReason(),'DOCUMENT_UNREADABLE — Upload a clearer image.');
});

test('KYC uses full-row controls and keeps every review action in one dialog', () => {
  const html=read('ts/views/admin-kyc.html');
  assert.match(html,/class="review-list-row"[^>]*data-bind="[^"]*selectReview/);
  assert.doesNotMatch(html,/>\s*Open\s*<\/button>/);
  assert.match(html,/<!-- ko if:review -->[\s\S]*class="[^"]*admin-confirmation[^"]*"[\s\S]*adminDialog:/);
  assert.match(html,/<!-- ko if:reviewDecision -->[\s\S]*confirmDecision/);
  assert.match(html,/<!-- ko if:documentPreview -->[\s\S]*closeReviewDocument/);
  assert.ok(html.indexOf('kyc-decision-form')>html.indexOf('admin-workflow-modal'));
  assert.match(html,/maskDocument\(docNumber\)/);
  assert.doesNotMatch(html,/OCR|automated mismatch|AI match/i);
  assert.doesNotMatch(html,/copy document|reveal document/i);
});

test('KYC document mode restores focus to the triggering evidence card', () => {
  const vm=new ViewModel({params:{view:'PENDING'}});
  let focused=false;
  const trigger={focus:()=>{focused=true;}};
  vm.openDocument=()=>Promise.resolve();
  vm.closeDocument=()=>{};
  vm.openReviewDocument({id:'document-1'},{currentTarget:trigger});
  vm.closeReviewDocument();
  assert.equal(focused,true);
});

test('AGING view shows only pending reviews older than 24 hours', () => {
  const fixedNow=Date.parse('2026-09-22T12:00:00Z');
  const originalNow=Date.now;
  Date.now=()=>fixedNow;
  try {
    const vm=new ViewModel({params:{view:'AGING'}});
    assert.equal(vm.savedView(),'AGING');
    assert.equal(vm.adminStatus(),'PENDING');
    vm.cases([
      {applicationId:'old',status:'PENDING',fullName:'Old Applicant',email:'old@test.example',submittedAt:'2026-09-20T12:00:00Z',documents:[{available:true}]},
      {applicationId:'recent',status:'PENDING',fullName:'Recent Applicant',email:'recent@test.example',submittedAt:'2026-09-22T11:00:00Z',documents:[{available:true}]},
      {applicationId:'verified-old',status:'VERIFIED',fullName:'Verified Applicant',email:'verified@test.example',submittedAt:'2026-09-20T12:00:00Z',documents:[{available:true}]}
    ]);
    assert.deepEqual(Array.from(vm.visibleReviews(), row=>row.applicationId),['old']);
  } finally {
    Date.now=originalNow;
  }
});

test('DOCUMENTS_UNAVAILABLE view shows only pending reviews missing originals', () => {
  const vm=new ViewModel({params:{view:'DOCUMENTS_UNAVAILABLE'}});
  assert.equal(vm.savedView(),'DOCUMENTS_UNAVAILABLE');
  assert.equal(vm.adminStatus(),'PENDING');
  vm.cases([
    {applicationId:'no-docs',status:'PENDING',fullName:'No Docs',email:'nodocs@test.example',submittedAt:'2026-09-22T10:00:00Z',documents:[]},
    {applicationId:'missing-file',status:'PENDING',fullName:'Missing File',email:'missing@test.example',submittedAt:'2026-09-22T10:00:00Z',documents:[{available:true},{available:false}]},
    {applicationId:'complete',status:'PENDING',fullName:'Complete',email:'complete@test.example',submittedAt:'2026-09-22T10:00:00Z',documents:[{available:true}]},
    {applicationId:'verified-missing',status:'VERIFIED',fullName:'Verified Missing',email:'verified@test.example',submittedAt:'2026-09-22T10:00:00Z',documents:[]}
  ]);
  assert.deepEqual(Array.from(vm.visibleReviews(), row=>row.applicationId).sort(),['missing-file','no-docs']);
});

test('approval requires document consent and available originals', () => {
  const vm=new ViewModel({params:{view:'PENDING'}});
  vm.review({status:'PENDING',documents:[{available:true}]});
  vm.decision('approve');vm.reasonCode('VERIFIED_DOCUMENTS');vm.notes('');
  vm.reviewConsent(false);
  vm.prepareDecision();
  assert.match(vm.error(),/manual review/i);
  assert.equal(vm.reviewDecision(),'');
  vm.reviewConsent(true);
  vm.prepareDecision();
  assert.equal(vm.reviewDecision(),'approve');
  assert.equal(vm.reviewReason(),'VERIFIED_DOCUMENTS');
  const blocked=new ViewModel({params:{view:'PENDING'}});
  blocked.review({status:'PENDING',documents:[{available:false}]});
  blocked.reviewConsent(true);blocked.decision('approve');blocked.reasonCode('VERIFIED_DOCUMENTS');blocked.notes('');
  blocked.prepareDecision();
  assert.match(blocked.error(),/manual review/i);
  assert.equal(blocked.reviewDecision(),'');
});

test('rejection without a reason code keeps the decision pending', () => {
  const vm=new ViewModel({params:{view:'PENDING'}});
  vm.review({status:'PENDING',documents:[{available:true}]});
  vm.reviewConsent(false);vm.decision('reject');vm.reasonCode('');vm.notes('Needs a clearer image.');
  vm.prepareDecision();
  assert.match(vm.error(),/reason code/i);
  assert.equal(vm.reviewDecision(),'');
});

test('confirmDecision preserves expectedVersion for approval and rejection', async () => {
  for (const decision of ['approve','reject']) {
    const vm=new ViewModel({params:{view:'PENDING'}});
    let captured=null;
    vm.decide=(approve)=>{captured={approve,version:vm.review().version,reason:vm.reviewReason()};return Promise.resolve();};
    vm.review({applicationId:'app-1',version:7,status:'PENDING',documents:[{available:true}]});
    if (decision==='approve') { vm.reviewConsent(true);vm.decision('approve');vm.reasonCode('VERIFIED_DOCUMENTS');vm.notes(''); }
    else { vm.reviewConsent(false);vm.decision('reject');vm.reasonCode('DOCUMENT_UNREADABLE');vm.notes('Upload a clearer image.'); }
    vm.prepareDecision();
    assert.equal(vm.reviewDecision(),decision==='approve'?'approve':'reject');
    await vm.confirmDecision();
    assert.equal(captured.approve,decision==='approve');
    assert.equal(captured.version,7);
    assert.ok(captured.reason.length>0);
  }
});

test('non-admin updates make no API request', () => {
  const vm=new ViewModel({params:{view:'PENDING'}});
  let apiCalls=0;
  vm.decide=()=>{apiCalls++;return Promise.resolve();};
  const openedBefore=vm.opened.length;
  vm.session={isAdmin:()=>false};
  vm.cases([{applicationId:'a1',status:'PENDING',fullName:'Applicant One',email:'a1@test.example',submittedAt:'2026-09-20T00:00:00Z',documents:[{available:true}]}]);
  assert.equal(vm.cases().length,0);
  assert.equal(apiCalls,0);
  assert.equal(vm.opened.length,openedBefore);
});

test('KYC list arriving after logout is cleared immediately', () => {
  const vm=new ViewModel({params:{view:'PENDING'}});
  vm.cases([{applicationId:'a1',status:'PENDING',fullName:'Applicant One',email:'a1@test.example',submittedAt:'2026-09-20T00:00:00Z',documents:[{available:true}]}]);
  assert.equal(vm.cases().length,1);
  vm.session={isAdmin:()=>false};
  vm.cases([{applicationId:'late',status:'PENDING',fullName:'Late Arrival',email:'late@test.example',submittedAt:'2026-09-20T00:00:00Z',documents:[{available:true}]}]);
  assert.equal(vm.cases().length,0);
});

test('KYC template keeps read-only preview, empty state and terminal decisions explicit', () => {
  const html=read('ts/views/admin-kyc.html');
  for (const token of ['kycPdfPreview','openReviewDocument','closeReviewDocument','documentPreview','decidedAt','rejectReason']) assert.ok(html.includes(token), token);
  assert.ok(html.includes('savedView'));
  assert.match(html,/Reset filters/i);
});
