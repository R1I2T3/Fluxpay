import * as ko from 'knockout';
import { fluxApi as api, PolicyDocument, PolicyChunk, PolicyGuidance, ComplianceCase, CopilotAnswer } from './flux-api';
import { composeDecisionReason, diffFields, prioritizeComplianceCases, FieldChange } from './admin-console';
import { session } from './session';
import {feedbackObservable} from './notifications';

const policyCategories = ['KYC','AML','PAYMENT_REVIEW','COUNTRY_RULE','SUPPORT'];
let nextPolicyDraftId = 0;

export class PolicyDraft {
  readonly id:string;
  title:ko.Observable<string>;
  category:ko.Observable<string>;
  content:ko.Observable<string>;
  clearExistingChunks:ko.Observable<boolean>;
  error:ko.Observable<string>;
  constructor(value:{id?:string;title?:string;category?:string;content?:string;clearExistingChunks?:boolean}={}){
    this.id=value.id||`policy-draft-${++nextPolicyDraftId}`;
    this.title=ko.observable(value.title||'');
    this.category=ko.observable(value.category||'PAYMENT_REVIEW');
    this.content=ko.observable(value.content||'');
    this.clearExistingChunks=ko.observable(value.clearExistingChunks??true);
    this.error=feedbackObservable('error');
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

ko.bindingHandlers.adminReveal = {
  update(element:HTMLElement,valueAccessor:()=>unknown){
    ko.unwrap(valueAccessor());
    window.requestAnimationFrame(()=>{if(element.isConnected)element.scrollIntoView({block:'start',behavior:window.matchMedia('(prefers-reduced-motion: reduce)').matches?'auto':'smooth'});});
  }
};

// The model output is untrusted. This intentionally supports only **bold** and constructs every
// node as text, rather than rendering model-provided HTML.
ko.bindingHandlers.policyAnswer = {
  update(element:HTMLElement,valueAccessor:()=>unknown){
    const value=String(ko.unwrap(valueAccessor())??'');
    element.replaceChildren();
    const bold=/\*\*([^*\n]+)\*\*/g;
    let cursor=0,match:RegExpExecArray|null;
    while((match=bold.exec(value))!==null){
      element.append(document.createTextNode(value.slice(cursor,match.index)));
      const strong=document.createElement('strong');
      strong.textContent=match[1];
      element.append(strong);
      cursor=match.index+match[0].length;
    }
    element.append(document.createTextNode(value.slice(cursor)));
  }
};

/** Administrative UI only: the case catalogue is global, not customer-scoped. */
export class ComplianceWorkspace {
  session = session;
  busy = ko.observable(false);
  error = feedbackObservable('error',()=>!this.disposed);
  notice = feedbackObservable('success',()=>!this.disposed);
  policies = ko.observableArray<PolicyDocument>([]);
  policyDrafts = ko.observableArray<PolicyDraft>([]);
  draftEditor = ko.observable<PolicyDraft>();
  draftEditorTarget = ko.observable<PolicyDraft>();
  draftViewer = ko.observable<PolicyDraft>();
  policyEdit = ko.observable<PolicyDraft>();
  policyEditSnapshot = ko.observable('');
  chunkEdit = ko.observable<PolicyChunk>();
  chunkEditContent = ko.observable('');
  pendingChunk = ko.observable<PolicyChunk>();
  pendingGuidance = ko.observable<PolicyGuidance>();
  policyEditClearChunks = ko.pureComputed<boolean>({
    read:()=>this.policyEdit()?.clearExistingChunks()??true,
    write:(value:boolean)=>{const draft=this.policyEdit();if(draft)draft.clearExistingChunks(value);}
  });
  policyImportError = feedbackObservable('error',()=>!this.disposed);
  cases = ko.observableArray<ComplianceCase>([]);
  chunks = ko.observableArray<PolicyChunk>([]);
  guidance = ko.observableArray<PolicyGuidance>([]);
  policyView = ko.observable<'policy'|'advanced'>('policy');
  guidanceCaseId = ko.observable('');
  guidanceContent = ko.observable('');
  guidanceEdit = ko.observable<PolicyGuidance>();
  guidanceEditContent = ko.observable('');
  guidanceCaseViewer = ko.observable<ComplianceCase>();
  policy = ko.observable<PolicyDocument>();
  selectedCase = ko.observable<ComplianceCase>();
  status = ko.observable('ALL');
  riskFilter = ko.observable('ALL');
  originFilter = ko.observable('ALL');
  caseSort = ko.observable<'created-desc'|'created-asc'|'risk-desc'|'risk-asc'>('created-desc');
  casePage = ko.observable(0);
  readonly casePageSize = 10;
  policyPage = ko.observable(0);
  readonly policyPageSize = 10;
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
  pendingDecision = ko.observable<'approve'|'reject'>('approve');
  decisionCode = ko.observable('RULES_SATISFIED');
  decisionNotes = ko.observable('');
  savedCaseView = ko.observable<'HIGH_RISK'|'REQUOTE_REQUIRED'|'REVIEW_EXPIRING'|'OPEN'|'COMPLETED'>('OPEN');
  readonly decisionCodes = {
    approve: [{value:'RULES_SATISFIED',label:'Rules satisfied'},{value:'EVIDENCE_VERIFIED',label:'Evidence verified'},{value:'OTHER',label:'Other'}],
    reject: [{value:'POLICY_VIOLATION',label:'Policy violation'},{value:'INSUFFICIENT_EVIDENCE',label:'Insufficient evidence'},{value:'RISK_NOT_ACCEPTABLE',label:'Risk not acceptable'},{value:'OTHER',label:'Other'}]
  };
  confirmation = ko.observable<'approve'|'reject'|'delete-case'|'delete-policy'|'delete-chunk'|'delete-guidance'|'index'|''>('');
  question = ko.observable('');
  copilotPaymentId = ko.observable('');
  answer = ko.observable<CopilotAnswer>();
  answeredQuestion = ko.observable('');
  policyChanges = ko.observableArray<FieldChange>([]);
  policyChangeReview = ko.observable(false);
  draftPublishReview = ko.observable(false);
  policyModalMode = ko.pureComputed<''|'detail'|'advanced'|'edit'|'review'|'chunk-edit'|'guidance-edit'|'case-view'|'confirmation'>(()=>{
    if(this.policyChangeReview())return 'review';
    if(this.policyEdit())return 'edit';
    if(this.chunkEdit())return 'chunk-edit';
    if(this.guidanceEdit())return 'guidance-edit';
    if(this.guidanceCaseViewer())return 'case-view';
    if(this.confirmation()&&this.policy())return 'confirmation';
    return this.policy()?(this.policyView()==='advanced'?'advanced':'detail'):'';
  });
  policySavedView = ko.observable<'ALL'|'UNINDEXED'>('ALL');
  savedPolicies = ko.pureComputed(()=>this.filteredPolicies().filter(item=>this.policySavedView()==='ALL'||!(item.chunks||[]).some(chunk=>!chunk.manual)));
  visiblePolicies = ko.pureComputed(()=>{
    const filtered=this.savedPolicies();
    return filtered.slice(this.policyPage()*this.policyPageSize,(this.policyPage()+1)*this.policyPageSize);
  });
  filteredPolicies = ko.pureComputed(()=>this.policies().filter(p=>(this.categoryFilter()==='ALL'||p.category===this.categoryFilter())&&(p.title+' '+p.content).toLowerCase().includes(this.search().toLowerCase())));
  pagedPolicies = ko.pureComputed(()=>this.filteredPolicies().slice(this.policyPage()*this.policyPageSize,(this.policyPage()+1)*this.policyPageSize));
  policyPageCount = ko.pureComputed(()=>Math.max(1,Math.ceil(this.savedPolicies().length/this.policyPageSize)));
  filteredCases = ko.pureComputed(()=>{
    const rank:{[key:string]:number}={LOW:1,MEDIUM:2,HIGH:3};
    const result=this.cases().filter(c=>(this.status()==='ALL'||c.status===this.status())&&(this.riskFilter()==='ALL'||c.risk===this.riskFilter())&&(this.originFilter()==='ALL'||(this.originFilter()==='PAYMENT_REVIEW'?!!c.reviewReference:!c.reviewReference))&&(c.id+' '+c.paymentId+' '+c.risk+' '+c.riskReasons.join(' ')).toLowerCase().includes(this.search().toLowerCase()));
    return result.sort((a,b)=>this.caseSort().startsWith('risk')?(rank[a.risk]-rank[b.risk])*(this.caseSort()==='risk-desc'?-1:1):(new Date(a.createdAt).getTime()-new Date(b.createdAt).getTime())*(this.caseSort()==='created-desc'?-1:1));
  });
  pagedCases = ko.pureComputed(()=>this.filteredCases().slice(this.casePage()*this.casePageSize,(this.casePage()+1)*this.casePageSize));
  casePageCount = ko.pureComputed(()=>Math.max(1,Math.ceil(this.filteredCases().length/this.casePageSize)));
  completedCases = ko.pureComputed(()=>this.cases().filter(item=>item.status!=='OPEN'));
  completedCaseLabel = (item:ComplianceCase)=>item.risk+' · '+item.status+' · '+this.date(item.decidedAt||item.createdAt);
  savedCases = ko.pureComputed(()=>this.filteredCases().filter(item=>{
    const view = this.savedCaseView();
    if(view==='HIGH_RISK')return item.status==='OPEN'&&item.risk==='HIGH';
    if(view==='REQUOTE_REQUIRED')return item.status==='OPEN'&&item.requoteRequired;
    if(view==='REVIEW_EXPIRING'){
      const expires = Date.parse(item.reviewExpiresAt||'');
      return item.status==='OPEN'&&Number.isFinite(expires)&&expires>=Date.now()&&expires-Date.now()<=24*60*60*1000;
    }
    return view==='COMPLETED'?item.status!=='OPEN':item.status==='OPEN';
  }));
  prioritizedCases = ko.pureComputed(()=>prioritizeComplianceCases(this.savedCases()));
  canDeleteCase = ko.pureComputed(()=>this.selectedCase()?.status==='OPEN'&&!this.selectedCase()?.reviewReference);
  date = (value:string|null)=>value?new Date(value).toLocaleString():'—';
  private disposed = false;
  liveResponse=ko.observable(false);
  streamedAnswer=ko.observable(false);
  private streamAbort?:AbortController;
  private epoch = 0;
  private sessionChanged = session.user.subscribe(()=>{this.streamAbort?.abort();this.epoch++;this.clear();});
  private policySavedViewChanged = this.policySavedView.subscribe(()=>{this.policyPage(0);});
  private noticeChanged = this.notice.subscribe(value=>this.dismissToast(this.notice,value));
  private errorChanged = this.error.subscribe(value=>this.dismissToast(this.error,value));
  private clear(){this.policies([]);this.policyDrafts([]);this.draftEditor(undefined);this.draftEditorTarget(undefined);this.draftViewer(undefined);this.policyEdit(undefined);this.policyChanges([]);this.policyChangeReview(false);this.draftPublishReview(false);this.chunkEdit(undefined);this.chunkEditContent('');this.pendingChunk(undefined);this.pendingGuidance(undefined);this.policySavedView('ALL');this.policyImportError('');this.cases([]);this.policy(undefined);this.selectedCase(undefined);this.chunks([]);this.guidance([]);this.guidanceCaseViewer(undefined);this.policyView('policy');this.guidanceCaseId('');this.guidanceContent('');this.answer(undefined);this.confirmation('');this.decisionReason('');this.pendingDecision('approve');this.decisionCode('RULES_SATISFIED');this.decisionNotes('');this.question('');this.copilotPaymentId('');this.answeredQuestion('');this.title('');this.content('');this.chunkContent('');this.paymentId('');this.reasons('');this.suggestedAction('');this.policyForm(false);this.caseForm(false);this.casePage(0);this.policyPage(0);this.error('');this.notice('');}
  dispose(){this.streamAbort?.abort();this.disposed=true;this.epoch++;this.clear();this.sessionChanged.dispose();this.policySavedViewChanged.dispose();this.noticeChanged.dispose();this.errorChanged.dispose();}
  private dismissToast(target:ko.Observable<string>,value:string){
    if(!value||typeof window==='undefined'||!window.setTimeout)return;
    window.setTimeout(()=>{if(!this.disposed&&target()===value)target('');},5000);
  }
  async run(action:()=>Promise<void>){
    if(this.busy()||this.disposed)return;
    if(!session.isAdmin()){this.error('An administrator account is required.');return;}
    this.busy(true);this.error('');this.notice('');
    const epoch=this.epoch;
    try{await action();}catch(e:any){if(!this.disposed&&epoch===this.epoch)this.error(e.message||'The service could not complete this request.');}
    finally{if(this.disposed||epoch!==this.epoch)this.clear();this.busy(false);}
  }
  resetSearch(){this.search('');this.error('');this.notice('');this.confirmation('');}
  loadPolicies = ()=>this.run(async()=>{this.policies(await api.policies());this.policyPage(0);});
  previousPolicyPage = ()=>this.policyPage(Math.max(0,this.policyPage()-1));
  nextPolicyPage = ()=>this.policyPage(Math.min(this.policyPageCount()-1,this.policyPage()+1));
  loadCases = ()=>this.run(async()=>{this.cases(await api.complianceCases('ALL'));this.casePage(0);});
  previousCasePage = ()=>this.casePage(Math.max(0,this.casePage()-1));
  nextCasePage = ()=>this.casePage(Math.min(this.casePageCount()-1,this.casePage()+1));
  toggleRiskSort = ()=>{this.caseSort(this.caseSort()==='risk-desc'?'risk-asc':'risk-desc');this.casePage(0);};
  toggleCreatedSort = ()=>{this.caseSort(this.caseSort()==='created-desc'?'created-asc':'created-desc');this.casePage(0);};
  openPolicy = (p:{id:string})=>{this.policyView('policy');return this.run(()=>this.readPolicy(p.id));};
  private async readPolicy(id:string){const [p,c,g,allCases]=await Promise.all([api.policy(id),api.policyChunks(id),api.policyGuidance(id),api.complianceCases('ALL')]);this.policy(p);this.chunks(c);this.guidance(g);this.cases(allCases);this.chunkContent('');}
  closePolicy = ()=>{if(!this.busy()){this.policy(undefined);this.policyView('policy');this.confirmation('');}};
  loadPolicyJson = (contextOrEvent:unknown,event?:Event)=>{
    const input=(event??contextOrEvent as Event).target as HTMLInputElement;
    const files=Array.from(input.files??[]);
    this.policyImportError('');
    if(!files.length){this.policyImportError('Choose one or more JSON policy files.');return;}
    input.value='';
    const jsonFiles=files.filter(file=>file.name.toLowerCase().endsWith('.json')||file.type==='application/json');
    if(jsonFiles.length!==files.length)this.policyImportError('Only JSON policy files were imported; non-JSON files were skipped.');
    const read=(file:File)=>new Promise<PolicyDraft[]>((resolve,reject)=>{
      const reader=new FileReader();
      reader.onerror=()=>reject(new Error(`Unable to read ${file.name}.`));
      reader.onload=()=>{
        try{resolve(parsePolicyImport(reader.result));}
        catch(e:any){reject(new Error(`${file.name}: ${e.message||'Unable to import policy JSON.'}`));}
      };
      reader.readAsText(file);
    });
    void Promise.allSettled(jsonFiles.map(read)).then(results=>{
      if(this.disposed)return;
      const drafts:PolicyDraft[]=[];
      const errors:string[]=[];
      results.forEach(result=>{
        if(result.status==='fulfilled')drafts.push(...result.value);
        else errors.push(result.reason?.message||'Unable to import a policy JSON file.');
      });
      if(drafts.length)this.policyDrafts.push(...drafts);
      if(errors.length)this.policyImportError([this.policyImportError(),...errors].filter(Boolean).join(' '));
    });
  };
  addManualPolicyDraft = ()=>{if(!this.busy()){this.policyImportError('');this.draftEditorTarget(undefined);this.draftEditor(new PolicyDraft());}};
  editPolicyDraft = (draft:PolicyDraft)=>{if(!this.busy()){this.draftEditorTarget(draft);this.draftEditor(new PolicyDraft({id:draft.id,title:draft.title(),category:draft.category(),content:draft.content()}));}};
  viewPolicyDraft = (draft:PolicyDraft)=>{if(!this.busy())this.draftViewer(draft);};
  savePolicyDraft = ()=>{const editor=this.draftEditor();if(!editor)return false;try{this.policyDraftPayload(editor);}catch(e:any){editor.error(e.message);return false;}const target=this.draftEditorTarget();if(target){target.title(editor.title());target.category(editor.category());target.content(editor.content());}else this.policyDrafts.push(editor);this.draftEditor(undefined);this.draftEditorTarget(undefined);return false;};
  closePolicyDraftEditor = ()=>{if(!this.busy()){this.draftEditor(undefined);this.draftEditorTarget(undefined);}};
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
  requestPolicyDraftPublish=()=>{
    if(this.busy()||!this.policyDrafts().length)return;
    try{this.policyDrafts().forEach(draft=>this.policyDraftPayload(draft));}
    catch(error:any){this.policyImportError(error.message);return;}
    this.draftPublishReview(true);
  };
  confirmPolicyDraftPublish=async()=>{if(!this.draftPublishReview())return;this.draftPublishReview(false);await this.createPolicyDrafts();};
  createPolicyDrafts = ()=>this.run(async()=>{
    const completed=new Set<string>();
    for(const draft of this.policyDrafts()){
      try{draft.error('');await api.createPolicy(this.policyDraftPayload(draft));completed.add(draft.id);}
      catch(e:any){draft.error(e.message||'The policy could not be created.');}
    }
    if(completed.size){
      this.policyDrafts(this.policyDrafts().filter(draft=>!completed.has(draft.id)));
      this.policies(await api.policies());
      if(this.policyDrafts().length===0){this.policyImportError('');this.policy(undefined);this.policyView('policy');this.notice('Policies have been added to the policy library.');}
    }
  });
  openPolicyEdit = (policy:Pick<PolicyDocument,'id'|'title'|'category'|'content'>)=>{
    if(!this.busy()){this.error('');const draft=new PolicyDraft(policy);this.policyEdit(draft);this.policyEditSnapshot(JSON.stringify({title:draft.title(),category:draft.category(),content:draft.content(),clearExistingChunks:draft.clearExistingChunks()}));}
  };
  requestPolicyEditSave=()=>{
    const draft=this.policyEdit();
    if(this.busy()||!draft)return;
    let payload:{title:string;category:string;content:string;clearExistingChunks:boolean};
    try{payload={...this.policyDraftPayload(draft),clearExistingChunks:draft.clearExistingChunks()};}
    catch(error:any){draft.error(error.message);return;}
    const current=this.policies().find(item=>item.id===draft.id)||this.policy();
    const baseline={...(current||{}),clearExistingChunks:true};
    this.policyChanges(diffFields(baseline,payload,{
      title:'Title',category:'Category',content:'Content',clearExistingChunks:'Clear existing chunks'
    },['title','category','content','clearExistingChunks']));
    if(!this.policyChanges().length){draft.error('No policy changes to review.');return;}
    this.policyChangeReview(true);
  };
  confirmPolicyEditSave=async()=>{if(!this.policyChangeReview())return;this.policyChangeReview(false);await this.savePolicyEdit();};
  savePolicyEdit = ()=>this.run(async()=>{
    const draft=this.policyEdit();
    if(!draft)throw new Error('Select a policy to edit.');
    let body:{title:string;category:string;content:string;clearExistingChunks:boolean};
    try{draft.error('');body={...this.policyDraftPayload(draft),clearExistingChunks:draft.clearExistingChunks()};}
    catch(e:any){draft.error(e.message||'Enter valid policy details.');return;}
    try{
      const updated=await api.updatePolicy(draft.id,body);
      this.policy(updated);if(body.clearExistingChunks)this.chunks([]);this.policyEdit(undefined);this.policies(await api.policies());
      this.notice(body.clearExistingChunks?'Policy updated. Rebuild its index before asking Copilot.':'Policy updated. Existing chunks and index were retained.');
    }catch(e:any){draft.error(e.message||'The policy could not be updated.');}
  });
  closePolicyEdit = ()=>{const draft=this.policyEdit();if(this.busy()||!draft)return;const current=JSON.stringify({title:draft.title(),category:draft.category(),content:draft.content(),clearExistingChunks:draft.clearExistingChunks()});if(current!==this.policyEditSnapshot()&&!window.confirm('Discard unsaved policy changes?'))return;this.policyEdit(undefined);this.policyEditSnapshot('');this.policyChanges([]);this.policyChangeReview(false);};
  openAdvancedFromEdit = ()=>{
    const draft=this.policyEdit();
    if(this.busy()||!draft)return;
    const current=JSON.stringify({title:draft.title(),category:draft.category(),content:draft.content(),clearExistingChunks:draft.clearExistingChunks()});
    if(current!==this.policyEditSnapshot()&&!window.confirm('Discard unsaved policy changes and open advanced settings?'))return;
    this.policyEdit(undefined);this.policyEditSnapshot('');this.policyView('advanced');
  };
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
  openChunkEdit = (chunk:PolicyChunk)=>{
    if(!this.busy()&&chunk.manual){this.error('');this.chunkEdit(chunk);this.chunkEditContent(chunk.content);}
  };
  closeChunkEdit = ()=>{if(!this.busy()){this.chunkEdit(undefined);this.chunkEditContent('');}};
  saveChunkEdit = ()=>this.run(async()=>{
    const policy=this.policy(),chunk=this.chunkEdit(),content=this.chunkEditContent().trim();
    if(!policy||!chunk||!chunk.manual)throw new Error('Only manually added chunks can be edited.');
    if(!content)throw new Error('Chunk text cannot be blank.');
    await api.updatePolicyChunk(policy.id,chunk.id,content);
    this.chunkEdit(undefined);this.chunkEditContent('');await this.readPolicy(policy.id);this.notice('Manual chunk updated.');
  });
  requestDeleteChunk = (chunk:PolicyChunk)=>{
    if(!this.busy()){this.error('');this.pendingChunk(chunk);this.confirmation('delete-chunk');}
  };
  addGuidance = ()=>this.run(async()=>{
    const policy=this.policy(),caseId=this.guidanceCaseId().trim(),content=this.guidanceContent().trim();
    if(!policy||!this.uuid(caseId)||!content)throw new Error('Choose a completed case ID and enter guidance text.');
    await api.addPolicyGuidance(policy.id,{complianceCaseId:caseId,content});this.guidanceCaseId('');this.guidanceContent('');await this.readPolicy(policy.id);this.notice('Guidance added. Rebuild the search index to make it available to Copilot.');
  });
  openGuidanceEdit = (item:PolicyGuidance)=>{if(!this.busy()){this.guidanceEdit(item);this.guidanceEditContent(item.content);}};
  closeGuidanceEdit = ()=>{if(!this.busy()){this.guidanceEdit(undefined);this.guidanceEditContent('');}};
  saveGuidanceEdit = ()=>this.run(async()=>{const policy=this.policy(),item=this.guidanceEdit(),content=this.guidanceEditContent().trim();if(!policy||!item||!content)throw new Error('Guidance text cannot be blank.');await api.updatePolicyGuidance(policy.id,item.id,content);this.guidanceEdit(undefined);this.guidanceEditContent('');await this.readPolicy(policy.id);this.notice('Policy guidance updated. Rebuild the index to update Copilot.');});
  deleteGuidance = (item:PolicyGuidance)=>this.run(async()=>{const policy=this.policy();if(!policy||!window.confirm('Delete this policy guidance?'))return;await api.deletePolicyGuidance(policy.id,item.id);await this.readPolicy(policy.id);this.notice('Policy guidance deleted.');});
  requestDeleteGuidance=(item:PolicyGuidance)=>{if(!this.busy()){this.pendingGuidance(item);this.confirmation('delete-guidance');}};
  openGuidanceCase = (item:PolicyGuidance)=>this.openGuidanceCaseById(item.complianceCaseId);
  openSelectedGuidanceCase = ()=>this.openGuidanceCaseById(this.guidanceCaseId().trim());
  private openGuidanceCaseById(caseId:string){
    if(!this.uuid(caseId)||this.busy())return;
    const loaded=this.completedCases().find(item=>item.id===caseId);
    if(loaded){this.guidanceCaseViewer(loaded);return;}
    this.run(async()=>{const item=await api.complianceCase(caseId);if(!item?.id)throw new Error('The completed compliance case could not be loaded.');this.guidanceCaseViewer(item);});
  }
  closeGuidanceCase = ()=>{if(!this.busy())this.guidanceCaseViewer(undefined);};
  openCase = (c:{id:string})=>this.run(async()=>{this.selectedCase(await api.complianceCase(c.id));this.decisionReason('');this.pendingDecision('approve');this.decisionCode('RULES_SATISFIED');this.decisionNotes('');this.confirmation('');});
  closeCase = ()=>{if(!this.busy()){this.selectedCase(undefined);this.confirmation('');this.pendingDecision('approve');this.decisionCode('RULES_SATISFIED');this.decisionNotes('');}};
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
  prepareDecision = (action:'approve'|'reject')=>{
    try{this.decisionReason(composeDecisionReason(this.decisionCode(),this.decisionNotes()));}
    catch(error:any){this.error(error.message);return;}
    this.askConfirmation(action);
  };
  cancelConfirmation = ()=>{if(!this.busy()){this.confirmation('');this.pendingChunk(undefined);this.pendingGuidance(undefined);}};
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
    }else if(action==='delete-chunk'){
      const chunk=this.pendingChunk();
      if(!p||!chunk)throw new Error('Select a policy chunk to delete.');
      await api.deletePolicyChunk(p.id,chunk.id);this.pendingChunk(undefined);this.confirmation('');await this.readPolicy(p.id);this.notice(chunk.manual?'Manual chunk deleted.':'Indexed chunk deleted. Rebuilding the index will restore indexed chunks from the policy document.');
    }else if(action==='delete-guidance'){
      const item=this.pendingGuidance();
      if(!p||!item)throw new Error('Select policy guidance to delete.');
      await api.deletePolicyGuidance(p.id,item.id);this.pendingGuidance(undefined);this.confirmation('');await this.readPolicy(p.id);this.notice('Policy guidance deleted.');
    }else if(action==='index'){
      if(!p)throw new Error('Select a policy first.');
      const result=await api.indexPolicy(p.id);this.confirmation('');this.notice('Index published: '+result.chunkCount+' chunks.');await this.readPolicy(p.id);
    }
  });
  ask = ()=>this.run(async()=>{
    const question=this.question().trim(),paymentId=this.copilotPaymentId().trim();
    if(!question)throw new Error('Enter a policy question.');
    if(paymentId&&!this.uuid(paymentId))throw new Error('Enter a valid payment UUID or leave it blank.');
    this.answer(undefined);this.answeredQuestion(question);this.streamedAnswer(this.liveResponse());
    if(!this.liveResponse()){this.answer(await api.askCopilot(question,paymentId||undefined));return;}
    const epoch=this.epoch;this.streamAbort=new AbortController();this.answer({answer:'',sources:[]});
    try{await api.streamCopilot(question,paymentId||undefined,delta=>{if(!this.disposed&&epoch===this.epoch&&session.isAdmin())this.answer({answer:(this.answer()?.answer||'')+delta,sources:[]});},this.streamAbort.signal);}
    catch(e:any){if(e.name==='AbortError')this.notice('Live response stopped. Request a cited answer when you are ready.');else throw e;}
    finally{this.streamAbort=undefined;}
  });
  stopLiveResponse=()=>this.streamAbort?.abort();
  getCitedAnswer=()=>{if(!this.busy()){this.liveResponse(false);void this.ask();}};
}
