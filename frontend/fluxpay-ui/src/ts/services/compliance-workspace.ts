import * as ko from 'knockout';
import { fluxApi as api, PolicyDocument, PolicyChunk, ComplianceCase, CopilotAnswer } from './flux-api';
import { session } from './session';

const policyCategories = ['KYC','AML','PAYMENT_REVIEW','COUNTRY_RULE','SUPPORT'];
let nextPolicyDraftId = 0;

export class PolicyDraft {
  readonly id:string;
  title:ko.Observable<string>;
  category:ko.Observable<string>;
  content:ko.Observable<string>;
  error:ko.Observable<string>;
  constructor(value:{id?:string;title?:string;category?:string;content?:string}={}){
    this.id=value.id||`policy-draft-${++nextPolicyDraftId}`;
    this.title=ko.observable(value.title||'');
    this.category=ko.observable(value.category||'PAYMENT_REVIEW');
    this.content=ko.observable(value.content||'');
    this.error=ko.observable('');
  }
}

const normalizePolicyText=(value:string)=>value.trim().replace(/\s+/g,' ').toLowerCase();
const importError=(message:string):never=>{throw new Error(message);};

/** Parses imported JSON without changing the workspace until every entry has passed validation. */
export function parsePolicyImport(raw:unknown):PolicyDraft[]{
  let root=raw;
  if(typeof root==='string'){
    try{root=JSON.parse(root);}catch{return importError('Policy import must contain valid JSON.');}
  }
  if(!root||typeof root!=='object')return importError('Policy import must be a policy object or array.');
  const entries=Array.isArray(root)?root:[root];
  const duplicates=new Set<string>();
  return entries.map((entry,index)=>{
    if(!entry||typeof entry!=='object'||Array.isArray(entry))return importError(`Policy ${index+1} must be an object.`);
    const {title,category,content}=entry as {title?:unknown;category?:unknown;content?:unknown};
    if(typeof title!=='string'||typeof category!=='string'||typeof content!=='string')return importError(`Policy ${index+1} requires a title, category, and content.`);
    const trimmedTitle=title.trim(),trimmedCategory=category.trim(),trimmedContent=content.trim();
    if(!trimmedTitle||!trimmedContent)return importError(`Policy ${index+1} requires a non-blank title and content.`);
    if(trimmedTitle.length>200)return importError(`Policy ${index+1} title must be 200 characters or fewer.`);
    if(!policyCategories.includes(trimmedCategory))return importError(`Policy ${index+1} has an invalid category.`);
    const key=normalizePolicyText(trimmedTitle)+'\u0000'+normalizePolicyText(trimmedContent);
    if(duplicates.has(key))return importError(`Policy ${index+1} duplicates another policy title and content.`);
    duplicates.add(key);
    return new PolicyDraft({title:trimmedTitle,category:trimmedCategory,content:trimmedContent});
  });
}

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
  policyDrafts = ko.observableArray<PolicyDraft>([]);
  policyEdit = ko.observable<PolicyDraft>();
  policyImportError = ko.observable('');
  cases = ko.observableArray<ComplianceCase>([]);
  chunks = ko.observableArray<PolicyChunk>([]);
  policy = ko.observable<PolicyDocument>();
  selectedCase = ko.observable<ComplianceCase>();
  status = ko.observable('OPEN');
  search = ko.observable('');
  categoryFilter = ko.observable('ALL');
  categories = policyCategories;
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
  private clear(){this.policies([]);this.policyDrafts([]);this.policyEdit(undefined);this.policyImportError('');this.cases([]);this.policy(undefined);this.selectedCase(undefined);this.chunks([]);this.answer(undefined);this.confirmation('');this.decisionReason('');this.question('');this.copilotPaymentId('');this.answeredQuestion('');this.title('');this.content('');this.chunkContent('');this.paymentId('');this.reasons('');this.suggestedAction('');this.policyForm(false);this.caseForm(false);this.error('');this.notice('');}
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
  loadPolicyJson = (event:Event)=>{
    const input=event.target as HTMLInputElement;
    const file=input.files?.[0];
    this.policyImportError('');
    if(!file||(!file.name.toLowerCase().endsWith('.json')&&file.type!=='application/json')){this.policyImportError('Choose a JSON policy file.');return;}
    const reader=new FileReader();
    reader.onerror=()=>{if(!this.disposed)this.policyImportError('Unable to read policy import file.');};
    reader.onload=()=>{
      if(this.disposed)return;
      try{this.policyDrafts.push(...parsePolicyImport(reader.result));}
      catch(e:any){this.policyImportError(e.message||'Unable to import policy JSON.');}
    };
    reader.readAsText(file);
  };
  addManualPolicyDraft = ()=>{if(!this.busy()){this.policyImportError('');this.policyDrafts.push(new PolicyDraft());}};
  removePolicyDraft = (draft:PolicyDraft|string)=>{
    if(this.busy())return;
    const id=typeof draft==='string'?draft:draft.id;
    this.policyDrafts(this.policyDrafts().filter(value=>value.id!==id));
  };
  private policyDraftPayload(draft:PolicyDraft){
    const title=draft.title().trim(),category=draft.category().trim(),content=draft.content().trim();
    if(!title||title.length>200||!content||!this.categories.includes(category))throw new Error('Enter a title (up to 200 characters), category and policy content.');
    return {title,category,content};
  }
  createPolicyDrafts = ()=>this.run(async()=>{
    const completed=new Set<string>();
    for(const draft of this.policyDrafts()){
      try{draft.error('');await api.createPolicy(this.policyDraftPayload(draft));completed.add(draft.id);}
      catch(e:any){draft.error(e.message||'The policy could not be created.');}
    }
    if(completed.size){this.policyDrafts(this.policyDrafts().filter(draft=>!completed.has(draft.id)));this.policies(await api.policies());}
  });
  openPolicyEdit = (policy:Pick<PolicyDocument,'id'|'title'|'category'|'content'>)=>{
    if(!this.busy()){this.error('');this.policyEdit(new PolicyDraft(policy));}
  };
  savePolicyEdit = ()=>this.run(async()=>{
    const draft=this.policyEdit();
    if(!draft)throw new Error('Select a policy to edit.');
    let body:{title:string;category:string;content:string};
    try{draft.error('');body=this.policyDraftPayload(draft);}
    catch(e:any){draft.error(e.message||'Enter valid policy details.');return;}
    try{
      const updated=await api.updatePolicy(draft.id,body);
      this.policy(updated);this.chunks([]);this.policyEdit(undefined);this.policies(await api.policies());
      this.notice('Policy updated. Rebuild its index before asking Copilot.');
    }catch(e:any){draft.error(e.message||'The policy could not be updated.');}
  });
  closePolicyEdit = ()=>{if(!this.busy())this.policyEdit(undefined);};
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
