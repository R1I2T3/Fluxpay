import * as ko from 'knockout';
import CoreRouter = require('ojs/ojcorerouter');
import ModuleRouterAdapter = require('ojs/ojmodulerouter-adapter');
import KnockoutRouterAdapter = require('ojs/ojknockoutrouteradapter');
import UrlParamAdapter = require('ojs/ojurlparamadapter');
import UrlPathParamAdapter = require('ojs/ojurlpathparamadapter');
import Context = require('ojs/ojcontext');
import 'ojs/ojmodule-element';
import { session, navigate } from './services/session';

export function migrateTrackingBookmark(value:string):string{
  const [path,...parts]=value.replace(/^\//,'').split(';');
  if(path!=='tracking'||!parts.length)return value;
  const params=new URLSearchParams(parts.join('&'));
  return params.get('payment')?'activity/'+params.get('payment'):'payments-list';
}

class RootViewModel {
  session = session;
  menuOpen = ko.observable(false);
  message = ko.observable('');
  nav = [
    {path:'dashboard',label:'Home',icon:'◫'}, {path:'wallets',label:'Wallets',icon:'◉'},
    {path:'payments-new',label:'Send',icon:'↗'}, {path:'payments-list',label:'Activity',icon:'⇄'},
    {path:'recipients',label:'Recipients',icon:'◎'},
    {path:'kyc',label:'Verification',icon:'◇'}, {path:'account',label:'My account',icon:'○'},
    {path:'admin',label:'Administration',icon:'⊞'},
    {path:'admin-tickets',label:'Support review',icon:'?'},
    {path:'tickets',label:'Support tickets',icon:'?'}
  ];
  router: CoreRouter<any>;
  moduleAdapter: ModuleRouterAdapter<any>;
  selection: KnockoutRouterAdapter<any>;
  isHome: ko.PureComputed<boolean>;
  isPublic: ko.PureComputed<boolean>;
  isAdminWorkspace: ko.PureComputed<boolean>;
  accountPath = ko.pureComputed(()=>session.isAdmin()?'admin':'dashboard');
<<<<<<< HEAD
  visibleNav = ko.pureComputed(()=>this.nav.filter(n=>session.isAdmin()?n.path.startsWith('admin'):!n.path.startsWith('admin')));
=======
  needsVerificationSubmission = ko.pureComputed(()=>!!session.user()&&!session.isAdmin()&&['NONE','NOT_SUBMITTED','UNVERIFIED'].includes(session.user()?.kycStatus));
  visibleNav = ko.pureComputed(()=>this.nav.filter(n=>session.isAdmin()?n.path==='admin':n.path!=='admin'));
  bottomNav = this.nav.filter(n=>['dashboard','wallets','payments-new','payments-list','account'].includes(n.path)).map(n=>({...n,label:n.path==='account'?'More':n.label}));
  activeTab = ko.pureComputed(()=>{
    const path=this.selection?.path();
    return path==='send'?'payments-new':path==='add-money'?'wallets':['tracking','activity','history','payments-list'].includes(path||'')?'payments-list':['recipients','kyc','tickets','account'].includes(path||'')?'account':path;
  });
>>>>>>> d0ef60141524f31198ba72c7972e79ca562d4f00
  constructor(){
    const savedQuery=new URLSearchParams(location.search||'');
    const savedRoute=savedQuery.get('ojr')||'';
    const migrated=migrateTrackingBookmark(savedRoute);
    if(migrated!==savedRoute){savedQuery.set('ojr',migrated);history.replaceState(null,'',location.pathname+'?'+savedQuery.toString()+location.hash);}
    const routes = [{path:'',redirect:'home'},{path:'tracking',redirect:'payments-list'}, ...['home','login','register',...this.nav.map(n=>n.path)].map(path=>({path,detail:{label:path,module:path}})),
      {path:'activity/{id}',detail:{label:'Transfer details',module:'tracking'}},
      {path:'history/{id}',detail:{label:'Transaction receipt',module:'payments-list'}},
      {path:'send/{recipient}',detail:{label:'Send to someone',module:'payments-new'}},
      {path:'add-money',detail:{label:'Add money',module:'wallets'}},
      {path:'tickets',detail:{label:'Support',module:'support-mount'}}];
    this.router = new CoreRouter(routes,{urlAdapter:new UrlParamAdapter(new UrlPathParamAdapter(''))});
    this.moduleAdapter = new ModuleRouterAdapter(this.router,{pathKey:'module'});
    this.selection = new KnockoutRouterAdapter(this.router);
    this.isHome = ko.pureComputed(()=>this.selection.path()==='home');
    this.isPublic = ko.pureComputed(()=>['home','login','register',''].includes(this.selection.path()||''));
<<<<<<< HEAD
    this.isAdminWorkspace = ko.pureComputed(()=>!this.isPublic()&&(session.isAdmin()||['admin','admin-tickets'].includes(this.selection.path()||'')));
    this.selection.path.subscribe(()=>{this.menuOpen(false);window.scrollTo({top:0});});
=======
    this.isAdminWorkspace = ko.pureComputed(()=>!this.isPublic()&&(session.isAdmin()||this.selection.path()==='admin'));
    this.selection.path.subscribe(()=>{this.menuOpen(false);document.querySelector?.('.profile-menu')?.removeAttribute('open');window.scrollTo({top:0});});
>>>>>>> d0ef60141524f31198ba72c7972e79ca562d4f00
    window.addEventListener('fluxpay:navigate', (event:any)=> {
      const {path,params} = event.detail;
      // Existing login and account links request dashboard; admins land in Administration.
      let destination=path==='dashboard'?this.accountPath():path;
      let destinationParams=destination===path?(params||{}):{};
      if(path==='tracking'){destination=params?.payment?'activity':'payments-list';destinationParams=params?.payment?{id:params.payment}:{};}
      if(path==='payments-new'&&(params?.recipient||params?.action==='add-recipient')){destination='send';destinationParams={recipient:params.recipient||'new'};}
      if(path==='wallets'&&params?.action==='add-money'){destination='add-money';destinationParams={};}
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
