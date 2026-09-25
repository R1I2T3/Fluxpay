import * as ko from 'knockout';
import CoreRouter = require('ojs/ojcorerouter');
import ModuleRouterAdapter = require('ojs/ojmodulerouter-adapter');
import KnockoutRouterAdapter = require('ojs/ojknockoutrouteradapter');
import UrlParamAdapter = require('ojs/ojurlparamadapter');
import UrlPathParamAdapter = require('ojs/ojurlpathparamadapter');
import Context = require('ojs/ojcontext');
import 'ojs/ojmodule-element';
import { session, navigate } from './services/session';
import {resolveAdminEnvironment} from './services/admin-console';
import './services/experience-dialog';
import './services/transaction-celebration';
import {ToastCenter,feedbackObservable} from './services/notifications';
import {prepareTransactionSound,playTransactionSuccessSound} from './services/transaction-sound';

export function migrateTrackingBookmark(value:string):string{
  const [path,...parts]=value.replace(/^\//,'').split(';');
  if(path!=='tracking'||!parts.length)return value;
  const params=new URLSearchParams(parts.join('&'));
  return params.get('payment')?'activity/'+params.get('payment'):'payments-list';
}

class RootViewModel {
  session = session;
  menuOpen = ko.observable(false);
  message = feedbackObservable('error');
  toasts = new ToastCenter();
  transactionSuccess = ko.observable<any>();
  closeTransactionSuccess = ()=>this.transactionSuccess(undefined);
  viewTransactionActivity = ()=>{this.closeTransactionSuccess();navigate('payments-list');};
  customerNav=[
    {path:'dashboard',label:'Home',icon:'◫'},{path:'wallets',label:'Wallets',icon:'◉'},
    {path:'payments-new',label:'Send',icon:'↗'},{path:'payments-list',label:'Activity',icon:'⇄'},
    {path:'recipients',label:'Recipients',icon:'◎'},{path:'kyc',label:'Verification',icon:'◇'},
    {path:'account',label:'My account',icon:'○'}
  ];
  adminNavGroups=[
    {label:'Overview',items:[{path:'admin',label:'Overview',icon:'fa-gauge-high'},{path:'admin-statistics',label:'Statistics',icon:'fa-chart-column'}]},
    {label:'Operations',items:[{path:'admin-kyc',label:'KYC Reviews',icon:'fa-id-card'},{path:'admin-compliance',label:'Compliance Cases',icon:'fa-shield-halved'},{path:'admin-tickets',label:'Support Tickets',icon:'fa-headset'},{path:'admin-payment-operations',label:'Payment Operations',icon:'fa-wave-square'}]},
    {label:'Money Movement',items:[{path:'admin-providers',label:'Providers',icon:'fa-building-columns'},{path:'admin-routes',label:'Payout Routes',icon:'fa-route'}]},
    {label:'Policy & AI',items:[{path:'admin-policies',label:'Policy Library',icon:'fa-book'},{path:'admin-copilot',label:'Compliance Copilot',icon:'fa-robot'}]}
  ];
  adminPaths=this.adminNavGroups.flatMap(group=>group.items.map(item=>item.path));
  environment=resolveAdminEnvironment((window as any).FLUXPAY_ENVIRONMENT,window.location.hostname);
  router: CoreRouter<any>;
  moduleAdapter: ModuleRouterAdapter<any>;
  selection: KnockoutRouterAdapter<any>;
  isHome: ko.PureComputed<boolean>;
  isPublic: ko.PureComputed<boolean>;
  isAdminWorkspace: ko.PureComputed<boolean>;
  accountPath = ko.pureComputed(()=>session.isAdmin()?'admin':'dashboard');
  needsVerificationSubmission = ko.pureComputed(()=>!!session.user()&&!session.isAdmin()&&['NONE','NOT_SUBMITTED','UNVERIFIED'].includes(session.user()?.kycStatus));
  visibleNav = ko.pureComputed(()=>session.isAdmin()?[{path:'admin',label:'Overview',icon:'fa-gauge-high'}]:this.customerNav);
  bottomNav = this.customerNav.filter(item=>['dashboard','wallets','payments-new','payments-list','account'].includes(item.path)).map(item=>({...item,label:item.path==='account'?'More':item.label}));
  activeAdminPath = ko.pureComputed(()=>this.selection?.path()||'admin');
  activeTab = ko.pureComputed(()=>{
    const path=this.selection?.path();
    return path==='send'?'payments-new':['add-money','wallet-action'].includes(path||'')?'wallets':['tracking','activity','history','payments-list'].includes(path||'')?'payments-list':['recipients','kyc','tickets','account'].includes(path||'')?'account':path;
  });
  constructor(){
    prepareTransactionSound();
    window.addEventListener('fluxpay:toast',(event:any)=>{this.toasts.show(event.detail?.kind,event.detail?.message);});
    window.addEventListener('fluxpay:expired',()=>this.toasts.show('error','Your session has expired. Please log in again.'));
    document.addEventListener('invalid',event=>{const field=event.target as HTMLInputElement;this.toasts.show('error',field.validationMessage||'Please check the highlighted field.');},true);
    window.addEventListener('fluxpay:transaction-success',(event:any)=>{if(session.user()){this.toasts.clear();this.transactionSuccess(event.detail);playTransactionSuccessSound();}});
    session.user.subscribe(user=>{this.closeTransactionSuccess();if(!user)this.toasts.clear();});
    const savedQuery=new URLSearchParams(location.search||'');
    const savedRoute=savedQuery.get('ojr')||'';
    const migrated=migrateTrackingBookmark(savedRoute);
    if(migrated!==savedRoute){savedQuery.set('ojr',migrated);history.replaceState(null,'',location.pathname+'?'+savedQuery.toString()+location.hash);}
    const routes = [{path:'',redirect:'home'},{path:'tracking',redirect:'payments-list'}, ...['home','login','register',...this.customerNav.map(n=>n.path),...this.adminPaths].map(path=>({path,detail:{label:path,module:path}})),
      {path:'activity/{id}',detail:{label:'Transfer details',module:'tracking'}},
      {path:'history/{id}',detail:{label:'Transaction receipt',module:'payments-list'}},
      {path:'send/{recipient}',detail:{label:'Send to someone',module:'payments-new'}},
      {path:'add-money',detail:{label:'Add money',module:'wallets'}},
      {path:'wallet-action/{action}',detail:{label:'Move money',module:'wallets'}},
      {path:'tickets',detail:{label:'Support',module:'support-mount'}}];
    this.router = new CoreRouter(routes,{urlAdapter:new UrlParamAdapter(new UrlPathParamAdapter(''))});
    this.moduleAdapter = new ModuleRouterAdapter(this.router,{pathKey:'module'});
    this.selection = new KnockoutRouterAdapter(this.router);
    this.isHome = ko.pureComputed(()=>this.selection.path()==='home');
    this.isPublic = ko.pureComputed(()=>['home','login','register',''].includes(this.selection.path()||''));
    this.isAdminWorkspace = ko.pureComputed(()=>!this.isPublic()&&session.isAdmin());
    this.selection.path.subscribe(()=>{this.closeTransactionSuccess();this.menuOpen(false);document.querySelector?.('.profile-menu')?.removeAttribute('open');window.scrollTo({top:0});});
    window.addEventListener('fluxpay:navigate', (event:any)=> {
      const {path,params} = event.detail;
      // Existing login and account links request dashboard; admins land in Administration.
      let destination=path==='dashboard'?this.accountPath():path;
      let destinationParams=destination===path?(params||{}):{};
      if(path==='tracking'){destination=params?.payment?'activity':'payments-list';destinationParams=params?.payment?{id:params.payment}:{};}
      if(path==='payments-new'&&(params?.recipient||params?.action==='add-recipient')){destination='send';destinationParams={recipient:params.recipient||'new'};}
      if(path==='wallets'&&params?.action==='add-money'){destination='add-money';destinationParams={};}
      if(path==='wallets'&&['transfer','withdraw'].includes(params?.action)){destination='wallet-action';destinationParams={action:params.action};}
      this.message('');
      void this.router.go({path:destination,params:destinationParams}).catch(e=>this.message(e.message));
    });
    // Support old bookmarks and all in-page links through the same router.
    const fromHash=()=>{const hash=location.hash.slice(1);if(hash){const [path,query]=hash.split('?');if(routes.some(r=>r.path===path)){const params:Record<string,string>={};new URLSearchParams(query||'').forEach((value,key)=>params[key]=value);navigate(path,params);history.replaceState(null,'',location.pathname+location.search);}}};
    window.addEventListener('hashchange',fromHash);
    const initialRoute=this.router.sync().then(fromHash).catch(()=>navigate('home'));
    document.addEventListener('click', event=>{
      if(!(event.target as Element).closest('.profile-menu'))document.querySelector('.profile-menu')?.removeAttribute('open');
      const link=(event.target as Element).closest('a[data-route]') as HTMLAnchorElement;
      if(link && !(event as MouseEvent).ctrlKey && !(event as MouseEvent).metaKey){event.preventDefault();navigate(link.dataset.route!,link.dataset.action?{action:link.dataset.action}:{});}
    });
    document.addEventListener('keydown',event=>{if(event.key==='Escape'){const menu=document.querySelector<HTMLDetailsElement>('.profile-menu[open]');if(menu){menu.removeAttribute('open');menu.querySelector<HTMLElement>('summary')?.focus();}}});
    // Also handle a saved dashboard URL after the user's role has been restored.
    void Promise.all([initialRoute,session.restore()]).then(()=>{
      if(session.isAdmin()&&this.selection.path()==='dashboard')navigate('admin');
    });
    Context.getPageContext().getBusyContext().applicationBootstrapComplete();
  }
  go = (item:any)=>navigate(item.path);
  toggleMenu = ()=>this.menuOpen(!this.menuOpen());
  logout = ()=>{session.clear();navigate('home');};
}
export default new RootViewModel();
