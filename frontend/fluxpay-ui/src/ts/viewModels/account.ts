import { Page } from '../services/page';
import {navigate} from '../services/session';
class ViewModel extends Page {
  constructor(params:any) { super('account',params); }
  logout=()=>{this.session.clear();navigate('home');};
}
export = ViewModel;
