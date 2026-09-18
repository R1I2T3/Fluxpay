import * as ko from 'knockout';
import CoreRouter = require('ojs/ojcorerouter');
import ModuleRouterAdapter = require('ojs/ojmodulerouter-adapter');
import KnockoutRouterAdapter = require('ojs/ojknockoutrouteradapter');
import UrlParamAdapter = require('ojs/ojurlparamadapter');
import Context = require('ojs/ojcontext');
import 'ojs/ojmodule-element';
import { session, navigate } from './services/session';

class RootViewModel {
  session = session;
  menuOpen = ko.observable(false);
  message = ko.observable('');
  nav = [
    {path:'dashboard',label:'Overview',icon:'◫'}, {path:'wallets',label:'Wallets & exchange',icon:'◉'},
    {path:'payments-new',label:'Send money',icon:'↗'}, {path:'payments-list',label:'Transactions',icon:'⇄'},
    {path:'recipients',label:'Recipients',icon:'◎'}, {path:'tracking',label:'Track a transfer',icon:'⌁'},
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
  visibleNav = ko.pureComputed(()=>this.nav.filter(n=>session.isAdmin()?n.path.startsWith('admin'):!n.path.startsWith('admin')));
  constructor(){
    const routes = [{path:'',redirect:'home'}, ...['home','login','register',...this.nav.map(n=>n.path)].map(path=>({path,detail:{label:path}}))];
    this.router = new CoreRouter(routes,{urlAdapter:new UrlParamAdapter()});
    this.moduleAdapter = new ModuleRouterAdapter(this.router);
    this.selection = new KnockoutRouterAdapter(this.router);
    this.isHome = ko.pureComputed(()=>this.selection.path()==='home');
    this.isPublic = ko.pureComputed(()=>['home','login','register',''].includes(this.selection.path()||''));
    this.isAdminWorkspace = ko.pureComputed(()=>!this.isPublic()&&(session.isAdmin()||['admin','admin-tickets'].includes(this.selection.path()||'')));
    this.selection.path.subscribe(()=>{this.menuOpen(false);window.scrollTo({top:0});});
    window.addEventListener('fluxpay:navigate', (event:any)=> {
      const {path,params} = event.detail;
      // Existing login and account links request dashboard; admins land in Administration.
      const destination=path==='dashboard'?this.accountPath():path;
      void this.router.go({path:destination,params:destination===path?(params||{}):{}}).catch(e=>this.message(e.message));
    });
    // Support old bookmarks and all in-page links through the same router.
    const fromHash=()=>{const hash=location.hash.slice(1);if(hash){const [path,query]=hash.split('?');if(routes.some(r=>r.path===path)){const params:Record<string,string>={};new URLSearchParams(query||'').forEach((value,key)=>params[key]=value);navigate(path,params);history.replaceState(null,'',location.pathname+location.search);}}};
    window.addEventListener('hashchange',fromHash);
    const initialRoute=this.router.sync().then(fromHash).catch(()=>navigate('home'));
    document.addEventListener('click', event=>{
      const link=(event.target as Element).closest('a[data-route]') as HTMLAnchorElement;
      if(link && !(event as MouseEvent).ctrlKey && !(event as MouseEvent).metaKey){event.preventDefault();navigate(link.dataset.route!);}
    });
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
