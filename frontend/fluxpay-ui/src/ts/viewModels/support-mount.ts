import * as ko from 'knockout';
import * as ModuleElementUtils from 'ojs/ojmodule-element-utils';
import {session} from '../services/session';
import {notify} from '../services/notifications';

/** Host only: Plan 1 owns tickets.ts and tickets.html. */
class SupportMount {
  session=session;
  config=ko.observable<any>({view:[],viewModel:null});
  loading=ko.observable(true);
  unavailable=ko.observable(false);
  private disposed=false;
  constructor(params:any){
    void ModuleElementUtils.createConfig({name:'tickets',params}).then(config=>{
      if(!this.disposed)this.config(config);
    }).catch(()=>{if(!this.disposed){this.unavailable(true);notify('error','Support could not be loaded. Please try again.');}}).finally(()=>{if(!this.disposed)this.loading(false);});
  }
  disconnected(){this.disposed=true;}
}
export = SupportMount;
