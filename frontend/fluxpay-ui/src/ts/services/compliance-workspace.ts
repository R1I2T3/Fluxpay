import * as ko from 'knockout';
import { fluxApi as api, PolicyDocument, PolicyChunk, ComplianceCase, CopilotAnswer } from './flux-api';
import { session } from './session';

// Keep keyboard focus inside destructive-action confirmations and return it on close.
ko.bindingHandlers.adminDialog = {
  init(element:HTMLElement){
    const previous=document.activeElement as HTMLElement|null;
    const controls=()=>Array.from(element.querySelectorAll<HTMLElement>('button:not(:disabled),input:not(:disabled),textarea:not(:disabled),[tabindex="0"]'));
    const focus=window.setTimeout(()=>controls()[0]?.focus(),0);
    const trap=(event:KeyboardEvent)=>{
      if(event.key!=='Tab')return;
      const items=controls(),first=items[0],last=items[items.length-1];
      if(!first){event.preventDefault();return;}
      if(event.shiftKey&&(document.activeElement===first||!element.contains(document.activeElement))){event.preventDefault();last.focus();}
      else if(!event.shiftKey&&(document.activeElement===last||!element.contains(document.activeElement))){event.preventDefault();first.focus();}
    };
    element.addEventListener('keydown',trap);
    ko.utils.domNodeDisposal.addDisposeCallback(element,()=>{clearTimeout(focus);element.removeEventListener('keydown',trap);if(previous?.isConnected)previous.focus();});
  }
};
ko.bindingHandlers.adminReveal = {
  update(element:HTMLElement,valueAccessor:()=>unknown){
    ko.unwrap(valueAccessor());
    window.requestAnimationFrame(()=>{if(element.isConnected)element.scrollIntoView({block:'start',behavior:window.matchMedia('(prefers-reduced-motion: reduce)').matches?'auto':'smooth'});});
  }
};

/** Administrative UI only: the case catalogue is global, not customer-scoped. */
export class ComplianceWorkspace {
  session = session;
  busy = ko.observable(false);
  error = ko.observable('');
  notice = ko.observable('');
  policies = ko.observableArray<PolicyDocument>([]);
  cases = ko.observableArray<ComplianceCase>([]);
  chunks = ko.observableArray<PolicyChunk>([]);
  policy = ko.observable<PolicyDocument>();
  selectedCase = ko.observable<ComplianceCase>();
  status = ko.observable('OPEN');
  search = ko.observable('');
  categoryFilter = ko.observable('ALL');
  categories = ['KYC','AML','PAYMENT_REVIEW','COUNTRY_RULE','SUPPORT'];
  title = ko.observable('');
  category = ko.observable('PAYMENT_REVIEW');
  content = ko.observable('');
  chunkContent = ko.observable('');
  policyForm = ko.observable(false);
  caseForm = ko.observable(false);
  paymentId = ko.observable('');
  risk = ko.observable('MEDIUM');
  reasons = ko.observable('');
  suggestedAction = ko.observable('');
  decisionReason = ko.observable('');
  confirmation = ko.observable<'approve'|'reject'|'delete-case'|'delete-policy'|'index'|''>('');
  question = ko.observable('');
  copilotPaymentId = ko.observable('');
  answer = ko.observable<CopilotAnswer>();
  answeredQuestion = ko.observable('');
  filteredPolicies = ko.pureComputed(()=>this.policies().filter(p=>(this.categoryFilter()==='ALL'||p.category===this.categoryFilter())&&(p.title+' '+p.content).toLowerCase().includes(this.search().toLowerCase())));
  filteredCases = ko.pureComputed(()=>this.cases().filter(c=>(c.id+' '+c.paymentId+' '+c.risk+' '+c.riskReasons.join(' ')).toLowerCase().includes(this.search().toLowerCase())));
  canDeleteCase = ko.pureComputed(()=>this.selectedCase()?.status==='OPEN'&&!this.selectedCase()?.reviewReference);
  date = (value:string|null)=>value?new Date(value).toLocaleString():'—';
  private disposed = false;
  private epoch = 0;
  private sessionChanged = session.user.subscribe(()=>{this.epoch++;this.clear();});
  private clear(){this.policies([]);this.cases([]);this.policy(undefined);this.selectedCase(undefined);this.chunks([]);this.answer(undefined);this.confirmation('');this.decisionReason('');this.question('');this.copilotPaymentId('');this.answeredQuestion('');this.title('');this.content('');this.chunkContent('');this.paymentId('');this.reasons('');this.suggestedAction('');this.policyForm(false);this.caseForm(false);this.error('');this.notice('');}
  dispose(){this.disposed=true;this.epoch++;this.clear();this.sessionChanged.dispose();}
  async run(action:()=>Promise<void>){
    if(this.busy()||this.disposed)return;
    if(!session.isAdmin()){this.error('An administrator account is required.');return;}
    this.busy(true);this.error('');this.notice('');
    const epoch=this.epoch;
    try{await action();}catch(e:any){if(!this.disposed&&epoch===this.epoch)this.error(e.message||'The service could not complete this request.');}
    finally{if(this.disposed||epoch!==this.epoch)this.clear();this.busy(false);}
  }
  resetSearch(){this.search('');this.error('');this.notice('');this.confirmation('');}
  loadPolicies = ()=>this.run(async()=>{this.policies(await api.policies());});
  loadCases = ()=>this.run(async()=>{this.cases(await api.complianceCases(this.status()));});
  openPolicy = (p:{id:string})=>this.run(()=>this.readPolicy(p.id));
  private async readPolicy(id:string){const [p,c]=await Promise.all([api.policy(id),api.policyChunks(id)]);this.policy(p);this.chunks(c);this.chunkContent('');}
  closePolicy = ()=>{if(!this.busy()){this.policy(undefined);this.confirmation('');}};
  newPolicy = ()=>{this.policy(undefined);this.title('');this.content('');this.category('PAYMENT_REVIEW');this.policyForm(true);};
  savePolicy = ()=>this.run(async()=>{
    const title=this.title().trim(),content=this.content().trim();
    if(!title||title.length>200||!content||!this.categories.includes(this.category()))throw new Error('Enter a title (up to 200 characters), category and policy content.');
    const p=await api.createPolicy({title,category:this.category(),content});
    this.policyForm(false);this.policy(p);this.chunks(p.chunks||[]);this.title('');this.content('');
    this.notice('Policy created. Index it to make its content available to Copilot.');
    this.policies(await api.policies());
  });
  addChunk = ()=>this.run(async()=>{
    const content=this.chunkContent().trim(),p=this.policy();if(!p||!content)throw new Error('Enter text for the new chunk.');
    await api.addPolicyChunk(p.id,content);this.chunkContent('');this.notice('Chunk added. Indexing rebuilds chunks from the original document, not these manual additions.');
    await this.readPolicy(p.id);
  });
  openCase = (c:{id:string})=>this.run(async()=>{this.selectedCase(await api.complianceCase(c.id));this.decisionReason('');this.confirmation('');});
  closeCase = ()=>{if(!this.busy()){this.selectedCase(undefined);this.confirmation('');}};
  newCase = ()=>{this.selectedCase(undefined);this.paymentId('');this.risk('MEDIUM');this.reasons('');this.suggestedAction('');this.caseForm(true);};
  private uuid(value:string){return /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value);}
  saveCase = ()=>this.run(async()=>{
    const paymentId=this.paymentId().trim(),riskReasons=this.reasons().split('\n').map(r=>r.trim()).filter(Boolean),suggestedAction=this.suggestedAction().trim();
    if(!this.uuid(paymentId)||!riskReasons.length||!suggestedAction||suggestedAction.length>400||!['LOW','MEDIUM','HIGH'].includes(this.risk()))throw new Error('Enter a valid payment UUID, at least one reason, and a suggested action up to 400 characters.');
    this.selectedCase(await api.createComplianceCase({paymentId,risk:this.risk(),riskReasons,suggestedAction}));
    this.caseForm(false);this.decisionReason('');this.status('OPEN');this.notice('Manual case created. Decisions on manual cases do not change the payment state.');
    this.cases(await api.complianceCases(this.status()));
  });
  askConfirmation = (action:'approve'|'reject'|'delete-case'|'delete-policy'|'index')=>{if(!this.busy()){this.error('');this.confirmation(action);}};
  cancelConfirmation = ()=>{if(!this.busy())this.confirmation('');};
  confirm = ()=>this.run(async()=>{
    const action=this.confirmation(),c=this.selectedCase(),p=this.policy();
    if(action==='approve'||action==='reject'){
      if(!c||c.status!=='OPEN')throw new Error('Refresh and select an open case.');
      if(this.decisionReason().trim().length>500)throw new Error('Decision notes must be 500 characters or fewer.');
      this.selectedCase(await api.decideComplianceCase(c.id,action,this.decisionReason().trim()));this.confirmation('');
      this.notice(c.reviewReference?'Decision saved. The linked payment has been updated.':'Decision saved. This manual case does not change payment state.');
      this.cases(await api.complianceCases(this.status()));
    }else if(action==='delete-case'){
      if(!c||!this.canDeleteCase())throw new Error('Only open manual cases can be deleted.');
      await api.deleteComplianceCase(c.id);this.selectedCase(undefined);this.confirmation('');this.notice('Manual case deleted.');this.cases(await api.complianceCases(this.status()));
    }else if(action==='delete-policy'){
      if(!p)throw new Error('Select a policy first.');
      await api.deletePolicy(p.id);this.policy(undefined);this.chunks([]);this.confirmation('');this.answer(undefined);this.notice('Policy and its indexed content deleted.');this.policies(await api.policies());
    }else if(action==='index'){
      if(!p)throw new Error('Select a policy first.');
      const result=await api.indexPolicy(p.id);this.confirmation('');this.notice('Index published: '+result.chunkCount+' chunks.');await this.readPolicy(p.id);
    }
  });
  ask = ()=>this.run(async()=>{
    const question=this.question().trim(),paymentId=this.copilotPaymentId().trim();
    if(!question)throw new Error('Enter a policy question.');
    if(paymentId&&!this.uuid(paymentId))throw new Error('Enter a valid payment UUID or leave it blank.');
    this.answer(undefined);this.answeredQuestion(question);this.answer(await api.askCopilot(question,paymentId||undefined));
  });
}
