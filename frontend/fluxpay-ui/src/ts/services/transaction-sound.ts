// A quiet, locally synthesised chime: no downloads or third-party audio requests.
let context: AudioContext | undefined;
let installed = false;
let lastPlayed = -Infinity;

export function prepareTransactionSound(): void {
  if (installed) return;
  installed = true;
  const unlock = () => {
    try {
      const Audio = window.AudioContext || (window as any).webkitAudioContext;
      if (!Audio) return;
      if (!context || context.state === 'closed') { context = new Audio(); lastPlayed = -Infinity; }
      if (context?.state === 'suspended') void context.resume().catch(() => {});
    } catch (_) { /* Audio is optional; browser policy must never block a payment. */ }
  };
  // Unlock during a real interaction, before the asynchronous payment completes.
  document.addEventListener('pointerdown', unlock, {capture:true, passive:true});
  document.addEventListener('keydown', unlock, {capture:true, passive:true});
}

export function playTransactionSuccessSound(): void {
  try {
    // Do not queue a sound that could play later for an old transaction.
    if (!context || context.state !== 'running') return;
    const now = context.currentTime;
    if (now - lastPlayed < 0.8) return;
    lastPlayed = now;
    [659.25, 830.61, 987.77].forEach((frequency, index) => {
      const oscillator = context!.createOscillator();
      const volume = context!.createGain();
      const start = now + index * 0.12;
      oscillator.type = 'sine';
      oscillator.frequency.setValueAtTime(frequency, start);
      volume.gain.setValueAtTime(0, start);
      volume.gain.linearRampToValueAtTime(0.075, start + 0.015);
      volume.gain.exponentialRampToValueAtTime(0.001, start + 0.42);
      volume.gain.linearRampToValueAtTime(0, start + 0.46);
      oscillator.connect(volume);
      volume.connect(context!.destination);
      oscillator.onended = () => { oscillator.disconnect(); volume.disconnect(); };
      oscillator.start(start);
      oscillator.stop(start + 0.47);
    });
  } catch (_) { /* A sound failure never changes the confirmed transaction result. */ }
}
