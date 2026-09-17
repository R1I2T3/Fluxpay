import * as ko from 'knockout';
import { LandingMotion } from '../services/landing-motion';

class ViewModel {
  scene = ko.observable(0);
  showcaseCurrency = ko.observable('USD');
  private preference = window.matchMedia('(prefers-reduced-motion: reduce)');
  motionPaused = ko.observable(this.preference.matches);
  sceneCaption = ko.pureComputed(
    () => ['A new adventure', 'A little more freedom', 'The life you live'][this.scene()],
  );
  sceneTitle = ko.pureComputed(
    () => ['Go a little further.', 'Room for what’s next.', 'Make it your own.'][this.scene()],
  );
  currencySymbol = ko.pureComputed(
    () => ({ USD: '$', EUR: '€', INR: '₹' })[this.showcaseCurrency()] || '$',
  );
  private motion?: LandingMotion;
  private subscriptions: ko.Subscription[] = [];
  private onPreference = () => {
    this.motionPaused(this.preference.matches);
    this.motion?.refresh();
  };

  connected() {
    const root = document.querySelector<HTMLElement>('.fp-landing');
    if (!root || this.motion) return;
    this.motion = new LandingMotion(
      root,
      () => this.scene(),
      // Reduced motion is the initial default; an explicit Play action can opt in.
      () => this.motionPaused(),
    );
    this.subscriptions = [
      this.scene.subscribe(() => this.motion?.refresh()),
      this.motionPaused.subscribe(() => this.motion?.refresh()),
    ];
    this.preference.addEventListener('change', this.onPreference);
  }

  selectScene = (index: number) => this.scene(index);
  toggleMotion = () => this.motionPaused(!this.motionPaused());
  sceneKey = (_: unknown, event: KeyboardEvent) => {
    const count = 3;
    let next = this.scene();
    if (event.key === 'ArrowRight') next = (next + 1) % count;
    else if (event.key === 'ArrowLeft') next = (next + count - 1) % count;
    else if (event.key === 'Home') next = 0;
    else if (event.key === 'End') next = count - 1;
    else return true;
    event.preventDefault();
    this.scene(next);
    document.getElementById('scene-tab-' + next)?.focus();
    return false;
  };

  disconnected() {
    this.motion?.dispose();
    this.motion = undefined;
    this.subscriptions.forEach((subscription) => subscription.dispose());
    this.subscriptions = [];
    this.preference.removeEventListener('change', this.onPreference);
  }
}
export = ViewModel;
