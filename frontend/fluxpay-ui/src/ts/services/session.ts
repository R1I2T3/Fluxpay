import * as ko from 'knockout';
import { fluxApi } from './flux-api';
export function navigate(path:string,params:Record<string,string>={}) { window.dispatchEvent(new CustomEvent('fluxpay:navigate',{detail:{path,params}})); }
const user=ko.observable<any>(null);
export const session = {
  user, isAdmin:ko.pureComputed(()=>user()?.role==='ADMIN'),
  set(result:any){sessionStorage.setItem('fluxpay.token',result.token);user(result.user);window.dispatchEvent(new Event('fluxpay:session'));},
  clear(){sessionStorage.removeItem('fluxpay.token');user(null);window.dispatchEvent(new Event('fluxpay:session'));},
  async restore(){if(!sessionStorage.getItem('fluxpay.token'))return;try{user(await fluxApi.me());}catch{user(null);} },
};
window.addEventListener('fluxpay:expired',()=>{session.clear();navigate('home');});
