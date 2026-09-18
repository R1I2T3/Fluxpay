import * as ko from 'knockout';
import { Page } from './page';
import { LandingMotion } from './landing-motion';

/** Shared presentation only; authentication stays in the existing Page/API flow. */
export class AuthPage extends Page {
  passwordVisible = ko.observable(false);
  motionPaused = ko.observable(window.matchMedia('(prefers-reduced-motion: reduce)').matches);
  private motion?: LandingMotion;

  togglePassword = () => this.passwordVisible(!this.passwordVisible());
  toggleMotion = () => {
    this.motionPaused(!this.motionPaused());
    this.motion?.refresh();
  };

  connected() {
    this.motion?.dispose();
    const root = document.querySelector<HTMLElement>('.fp-auth');
    if (root)
      this.motion = new LandingMotion(
        root,
        () => 0,
        () => this.motionPaused(),
      );
  }

  disconnected() {
    this.motion?.dispose();
    this.motion = undefined;
    this.password('');
    this.passwordVisible(false);
    super.disconnected();
  }
}
