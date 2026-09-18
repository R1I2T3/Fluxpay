import { AuthPage } from '../services/auth-page';
class ViewModel extends AuthPage {
  constructor(params: any) {
    super('register', params);
  }
}
export = ViewModel;
