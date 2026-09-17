import * as ko from 'knockout'; import { flux } from '../services/api-client';
export class AuthViewModel {
  mode: 'login'|'register'; email=ko.observable(''); password=ko.observable(''); fullName=ko.observable(''); busy=ko.observable(false); error=ko.observable('');
  constructor(mode: 'login'|'register') { this.mode=mode; }
  async submit() { this.error(''); if (!this.email() || !this.password() || (this.mode==='register'&&!this.fullName())) { this.error('Please complete all required fields.'); return; } this.busy(true); try { const result = this.mode==='login' ? await flux.login({email:this.email(),password:this.password()}) : await flux.register({email:this.email(),password:this.password(),fullName:this.fullName()}); sessionStorage.setItem('fluxpay.token',result.token); sessionStorage.setItem('fluxpay.user',JSON.stringify(result.user)); window.location.hash='#dashboard'; } catch(e:any) { this.error(e.message); } finally { this.busy(false); } }
}
