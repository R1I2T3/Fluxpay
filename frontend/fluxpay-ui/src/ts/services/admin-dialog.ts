import * as ko from 'knockout';

// Keep keyboard focus inside administrator dialogs and return it when they close.
ko.bindingHandlers.adminDialog = {
  init(element: HTMLElement) {
    const previous = document.activeElement as HTMLElement | null;
    const controls = () =>
      Array.from(
        element.querySelectorAll<HTMLElement>(
          'a[href],button:not(:disabled),input:not(:disabled),select:not(:disabled),textarea:not(:disabled),[tabindex="0"]',
        ),
      );
    const focus = window.setTimeout(() => controls()[0]?.focus(), 0);
    const trap = (event: KeyboardEvent) => {
      if (event.key !== 'Tab') return;
      const items = controls();
      const first = items[0];
      const last = items[items.length - 1];
      if (!first) {
        event.preventDefault();
        return;
      }
      if (
        event.shiftKey &&
        (document.activeElement === first || !element.contains(document.activeElement))
      ) {
        event.preventDefault();
        last.focus();
      } else if (
        !event.shiftKey &&
        (document.activeElement === last || !element.contains(document.activeElement))
      ) {
        event.preventDefault();
        first.focus();
      }
    };
    element.addEventListener('keydown', trap);
    ko.utils.domNodeDisposal.addDisposeCallback(element, () => {
      window.clearTimeout(focus);
      element.removeEventListener('keydown', trap);
      if (previous?.isConnected) previous.focus();
    });
  },
};
