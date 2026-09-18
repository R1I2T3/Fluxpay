import { AuthPage } from '../services/auth-page';
class ViewModel extends AuthPage {
  constructor(params: any) {
    super('login', params);
  }
}
export = ViewModel;
