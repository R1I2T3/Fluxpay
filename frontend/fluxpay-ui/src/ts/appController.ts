/**
 * @license
 * Copyright (c) 2014, 2024, Oracle and/or its affiliates.
 * Licensed under The Universal Permissive License (UPL), Version 1.0
 * as shown at https://oss.oracle.com/licenses/upl/
 * @ignore
 */
import * as ko from 'knockout';
import * as ModuleUtils from 'ojs/ojmodule-element-utils';
import * as ResponsiveUtils from 'ojs/ojresponsiveutils';
import * as ResponsiveKnockoutUtils from 'ojs/ojresponsiveknockoututils';
import CoreRouter = require('ojs/ojcorerouter');
import ModuleRouterAdapter = require('ojs/ojmodulerouter-adapter');
import KnockoutRouterAdapter = require('ojs/ojknockoutrouteradapter');
import UrlParamAdapter = require('ojs/ojurlparamadapter');
import ArrayDataProvider = require('ojs/ojarraydataprovider');
import 'ojs/ojknockout';
import 'ojs/ojmodule-element';
import { ojNavigationList } from 'ojs/ojnavigationlist';
import { ojModule } from 'ojs/ojmodule-element';
import Context = require('ojs/ojcontext');
import 'ojs/ojdrawerpopup';

interface CoreRouterDetail {
  label: string;
  iconClass: string;
}

class RootViewModel {
  manner: ko.Observable<string>;
  message: ko.Observable<string | undefined>;
  smScreen: ko.Observable<boolean> | undefined;
  mdScreen: ko.Observable<boolean> | undefined;
  router: CoreRouter<CoreRouterDetail> | undefined;
  moduleAdapter: ModuleRouterAdapter<CoreRouterDetail>;
  sideDrawerOn: ko.Observable<boolean>;
  navDataProvider: ojNavigationList<string, CoreRouter.CoreRouterState<CoreRouterDetail>>['data'];
  appName: ko.Observable<string>;
  userLogin: ko.Observable<string>;
  footerLinks: Array<object>;
  selection: KnockoutRouterAdapter<CoreRouterDetail>;

  constructor() {
    // handle announcements sent when pages change, for Accessibility.
    this.manner = ko.observable('polite');
    this.message = ko.observable();

    let globalBodyElement: HTMLElement = document.getElementById('globalBody') as HTMLElement;
    globalBodyElement.addEventListener('announce', this.announcementHandler, false);

    // media queries for responsive layouts
    let smQuery: string | null = ResponsiveUtils.getFrameworkQuery('sm-only');
    if (smQuery) {
      this.smScreen = ResponsiveKnockoutUtils.createMediaQueryObservable(smQuery);
    }

    let mdQuery: string | null = ResponsiveUtils.getFrameworkQuery('md-up');
    if (mdQuery) {
      this.mdScreen = ResponsiveKnockoutUtils.createMediaQueryObservable(mdQuery);
    }

    const navData = [
      { path: '', redirect: 'dashboard' },
      { path: 'dashboard', detail: { label: 'Dashboard', iconClass: 'oj-ux-ico-bar-chart' } },
      { path: 'recipients', detail: { label: 'Recipients', iconClass: 'oj-ux-ico-contact-group' } },
      { path: 'payments-new', detail: { label: 'Send Money', iconClass: 'oj-ux-ico-send' } },
      { path: 'payments-list', detail: { label: 'Payments', iconClass: 'oj-ux-ico-list' } },
      { path: 'payment-quotes', detail: { label: 'Quotes', iconClass: 'oj-ux-ico-compare' } },
    ];
    // router setup
    const router = new CoreRouter(navData, {
      urlAdapter: new UrlParamAdapter(),
    });
    router.sync();

    this.moduleAdapter = new ModuleRouterAdapter(router);

    this.selection = new KnockoutRouterAdapter(router);

    // Setup the navDataProvider with the routes, excluding the first redirected
    // route.
    this.navDataProvider = new ArrayDataProvider(navData.slice(1), { keyAttributes: 'path' });

    // drawer
    this.sideDrawerOn = ko.observable(false);

    // close drawer on medium and larger screens
    this.mdScreen?.subscribe(() => {
      this.sideDrawerOn(false);
    });

    // header

    // application Name used in Branding Area
    this.appName = ko.observable('App Name');
    // user Info used in Global Navigation area

    this.userLogin = ko.observable('john.hancock@oracle.com');
    // footer
    this.footerLinks = [
      {
        name: 'About Oracle',
        linkId: 'aboutOracle',
        linkTarget: 'http://www.oracle.com/us/corporate/index.html#menu-about',
      },
      {
        name: 'Contact Us',
        id: 'contactUs',
        linkTarget: 'http://www.oracle.com/us/corporate/contact/index.html',
      },
      {
        name: 'Legal Notices',
        id: 'legalNotices',
        linkTarget: 'http://www.oracle.com/us/legal/index.html',
      },
      {
        name: 'Terms Of Use',
        id: 'termsOfUse',
        linkTarget: 'http://www.oracle.com/us/legal/terms/index.html',
      },
      {
        name: 'Your Privacy Rights',
        id: 'yourPrivacyRights',
        linkTarget: 'http://www.oracle.com/us/legal/privacy/index.html',
      },
    ];
    // release the application bootstrap busy state
    Context.getPageContext().getBusyContext().applicationBootstrapComplete();
  }

  announcementHandler = (event: any): void => {
    this.message(event.detail.message);
    this.manner(event.detail.manner);
  };

  // called by navigation drawer toggle button and after selection of nav drawer item
  toggleDrawer = (): void => {
    this.sideDrawerOn(!this.sideDrawerOn());
  };

  // a close listener so we can move focus back to the toggle button when the drawer closes
  openedChangedHandler = (event: CustomEvent): void => {
    if (event.detail.value === false) {
      const drawerToggleButtonElement = document.querySelector(
        '#drawerToggleButton',
      ) as HTMLElement;
      drawerToggleButtonElement.focus();
    }
  };
}

export default new RootViewModel();
