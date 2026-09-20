import { Page } from '../services/page';
import * as ko from 'knockout';
import {fluxApi} from '../services/flux-api';
class ViewModel extends Page {
  status=ko.pureComputed(()=>this.kyc()?.status||this.session.user()?.kycStatus||'NONE');
  canResubmit=ko.pureComputed(()=>this.status()==='REJECTED');
  canSkip=ko.pureComputed(()=>['NONE','NOT_SUBMITTED','UNVERIFIED'].includes(this.status()));
  skipForNow=()=>{if(!this.busy())this.go('dashboard');};
  canSubmit=ko.pureComputed(()=>['NONE','NOT_SUBMITTED','UNVERIFIED','REJECTED'].includes(this.status()));
  statusMessage=ko.pureComputed(()=>this.status()==='VERIFIED'?'You’re verified and ready to move money.':this.status()==='PENDING'?'Your documents are being reviewed. You don’t need to upload them again.':this.canResubmit()?'Please check the review note and submit corrected documents.':'Add your identity details to get started.');
  constructor(params:any) { super('kyc',params); }
  submitKyc=()=>this.run(async()=>{
    if(!this.canSubmit())throw new Error('Your current verification cannot be resubmitted.');
    if(!this.docNumber().trim()||!this.documents().length)throw new Error('Enter the document number and choose at least one document.');
    if(this.documents().some(d=>!d.file))throw new Error('Choose the actual document files to upload.');
    const result=await fluxApi.uploadKyc(this.docType(),this.docNumber().trim(),this.documents().map(d=>d.file));
    this.kyc(result);this.documents([]);this.docNumber('');
    try{await this.session.restore();}catch{this.notice('Documents submitted. Refresh to update your account status.');}
  },'Your verification has been submitted for review.');
}
export = ViewModel;
