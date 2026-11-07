/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
import { useEffect, useRef, useState, useSyncExternalStore, type KeyboardEvent, type RefObject } from 'react';
import { settingsRoute } from '../settings/routes';
import type { DictationEvent, DictationRefusal, VoiceSettings } from './contracts';

/** How long right ⌥ must be held alone before dictation starts; a shorter press stays an ordinary ⌥. */
export const HOLD_MS = 250;
/** The countdown to an automatic send shows once the live words have been still this long, so it stays hidden while the user talks. */
export const QUIET_MS = 500;

/** `armed` may still press Send on its own, `due` presses it once the words are in the draft, `off` leaves them there as a draft. */
export type AutoSendState = 'armed' | 'due' | 'off';

export type DictationView =
  | { kind: 'idle' }
  /**
   * `finished` is in the written language; `originals` holds the spoken words it was translated from. `heard` is when the live
   * words last changed, which starts the quiet that counts down to an automatic send.
   */
  | { kind: 'listening'; id: string; hold: boolean; finished: string; originals: string; partial: string; since: number; heard: number | null; send: AutoSendState }
  | { kind: 'finishing'; id: string; hold: boolean; finished: string; originals: string; partial: string; send: AutoSendState }
  /** The dictated words are in the draft, and Send is pressed `wait` milliseconds after `since` unless the user steps in. */
  | { kind: 'sending'; since: number; wait: number }
  | { kind: 'failed'; id: string; seconds: number }
  | { kind: 'notice'; text: string; settings: boolean };

const idle: DictationView = { kind: 'idle' };

/** The letters and digits of live words. Apple's engine adds an ending stop and capitals once the user pauses, which is not speech. */
const spoken = (text: string) => text.toLocaleLowerCase().replace(/[^\p{L}\p{N}]/gu, '');

/**
 * Applies one helper event, received at `now`, to a composer's view. `done` carries text to insert, which the caller does. Once
 * those words are in the draft, a countdown that already ran out presses Send at once, and a held dictation counts `wait` first.
 */
export function nextView(view: DictationView, event: DictationEvent, now = Date.now(), wait = 0): DictationView {
  if (!('id' in view) || view.id !== event.id) return view;
  switch (event.type) {
    case 'listening':
      return view;
    case 'partial':
      if (view.kind === 'finishing') return { ...view, partial: event.text };
      return view.kind === 'listening'
        ? { ...view, partial: event.text, heard: spoken(event.text) === '' || spoken(event.text) === spoken(view.partial) ? view.heard : now }
        : view;
    case 'final':
      return view.kind === 'listening' || view.kind === 'finishing'
        ? { ...view, finished: view.finished + event.text, originals: view.originals + (event.original ?? ''), partial: '' }
        : view;
    case 'finishing':
      return view.kind === 'listening'
        ? { kind: 'finishing', id: view.id, hold: view.hold, finished: view.finished, originals: view.originals, partial: view.partial, send: view.send }
        : view;
    case 'done':
      if (event.text.trim() === '' || (view.kind !== 'listening' && view.kind !== 'finishing')) return idle;
      return view.send === 'due' ? { kind: 'sending', since: now, wait: 0 } : view.send === 'armed' && view.hold ? { kind: 'sending', since: now, wait } : idle;
    case 'cancelled':
      return idle;
    case 'failed':
      return event.seconds > 0 ? { kind: 'failed', id: event.id, seconds: event.seconds } : { kind: 'notice', text: startFailure(event.code), settings: true };
  }
}

/** Why a dictation stopped before any audio, in the user's words; each points at the Voice setting that fixes it. */
export function startFailure(code: string): string {
  switch (code) {
    case 'microphone-denied': return 'ByteQuay can’t use the microphone.';
    case 'no-microphone': return 'No microphone found.';
    case 'model-missing': return 'The speech model isn’t downloaded yet.';
    case 'translation-missing': return 'The translation isn’t downloaded yet.';
    default: return 'Dictation couldn’t start.';
  }
}

/** A language's name in the user's own language, from a helper id such as `zh_CN` or `en`. */
export function languageName(id: string): string {
  try {
    return new Intl.DisplayNames([navigator.language], { type: 'language' }).of(id.replace('_', '-')) ?? id;
  }
  catch {
    return id;
  }
}

export function refusalNotice(refused: DictationRefusal): DictationView {
  return refused === 'busy'
    ? { kind: 'notice', text: 'Dictation is running in another composer.', settings: false }
    : { kind: 'notice', text: 'Dictation isn’t available right now.', settings: true };
}

/** The words to insert at the caret, with one space on each side that touches other text. */
export function spaced(before: string, text: string, after = ''): string {
  const words = text.trim();
  return `${before === '' || /\s$/.test(before) ? '' : ' '}${words}${after === '' || /^\s/.test(after) ? '' : ' '}`;
}

/** The user typed, clicked, pressed Send or Esc during a countdown, or left the window: this dictation no longer sends on its own. */
export function stepIn(view: DictationView): DictationView {
  if (view.kind === 'sending') return idle;
  return (view.kind === 'listening' || view.kind === 'finishing') && view.send !== 'off' ? { ...view, send: 'off' } : view;
}

/** The whole seconds left before Send is pressed on its own, while the user should see them; null when nothing counts down. */
export function countdown(view: DictationView, now: number, wait: number): number | null {
  // Holding right ⌥ means still talking, so a held dictation counts down only once its words are in the draft.
  const at = view.kind === 'sending' ? view.since + view.wait
    : view.kind === 'listening' && view.send === 'armed' && !view.hold && view.heard !== null && now - view.heard >= QUIET_MS ? view.heard + wait : null;
  return at === null || at <= now ? null : Math.ceil((at - now) / 1000);
}

/** Pressing Send once the user has been quiet for `seconds`; only composers use it, so this window's storage keeps it. */
export type AutoSend = { on: boolean; seconds: number };
export const AUTO_SEND: AutoSend = { on: true, seconds: 3 };
export const AUTO_SEND_SECONDS = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10];
const AUTO_SEND_KEY = 'bytequay.dictation.auto-send';

export function readAutoSend(saved: string | null): AutoSend {
  try {
    const value: unknown = JSON.parse(saved ?? 'null');
    if (typeof value !== 'object' || value === null) return AUTO_SEND;
    const { on, seconds } = value as Record<string, unknown>;
    return { on: on !== false, seconds: typeof seconds === 'number' && AUTO_SEND_SECONDS.includes(seconds) ? seconds : AUTO_SEND.seconds };
  }
  catch {
    return AUTO_SEND;
  }
}

let autoSend: AutoSend | null = null;
const autoSendListeners = new Set<() => void>();

function currentAutoSend(): AutoSend {
  if (autoSend === null) {
    try { autoSend = readAutoSend(window.localStorage.getItem(AUTO_SEND_KEY)); }
    catch { autoSend = AUTO_SEND; }
  }
  return autoSend;
}

function subscribeAutoSend(listener: () => void): () => void {
  autoSendListeners.add(listener);
  return () => { autoSendListeners.delete(listener); };
}

export function saveAutoSend(next: AutoSend): void {
  autoSend = next;
  try { window.localStorage.setItem(AUTO_SEND_KEY, JSON.stringify(next)); }
  catch { /* Storage is optional; this window keeps the choice until it reloads. */ }
  for (const notify of autoSendListeners) notify();
}

export function useAutoSend(): AutoSend {
  return useSyncExternalStore(subscribeAutoSend, currentAutoSend, () => AUTO_SEND);
}

// The Voice setting, read once and kept current for every composer in this window.
const OFF: VoiceSettings = { enabled: false, spoken: null, written: null };
let voice = OFF;
let watching = false;
const voiceListeners = new Set<() => void>();

function subscribeVoice(listener: () => void): () => void {
  // Fixtures and windows without the voice bridge simply have no mic.
  if (!watching && typeof window.bridge?.getVoiceSettings === 'function') {
    watching = true;
    const apply = (settings: VoiceSettings) => {
      voice = settings;
      for (const notify of voiceListeners) notify();
    };
    void window.bridge.getVoiceSettings().then(apply).catch(() => {});
    window.bridge.onVoiceChanged(apply);
  }
  voiceListeners.add(listener);
  return () => { voiceListeners.delete(listener); };
}

// The composer dictating now; Electron main allows one dictation at a time.
let owner: object | null = null;
const ownerListeners = new Set<() => void>();

function setOwner(next: object | null): void {
  owner = next;
  for (const notify of ownerListeners) notify();
}

function subscribeOwner(listener: () => void): () => void {
  ownerListeners.add(listener);
  return () => { ownerListeners.delete(listener); };
}

/**
 * Dictation for one composer: the mic, hold right ⌥, the strip's view, insertion of the finished text, and the automatic send,
 * which calls `send` the way the composer's own Send button would.
 */
export function useDictation(field: RefObject<HTMLTextAreaElement | null>, disabled: boolean, send: () => void) {
  const [me] = useState(() => ({}));
  // Server-rendered markup has no voice bridge, so no mic.
  const settings = useSyncExternalStore(subscribeVoice, () => voice, () => OFF);
  const on = settings.enabled;
  const auto = useAutoSend();
  const wait = auto.seconds * 1000;
  const elsewhere = useSyncExternalStore(subscribeOwner, () => owner !== null && owner !== me, () => false);
  const [view, setView] = useState<DictationView>(idle);
  const [now, setNow] = useState(() => Date.now());
  const hold = useRef<{ timer: number | undefined; held: boolean }>({ timer: undefined, held: false });
  const live = useRef<string | null>(null);
  const press = useRef(send);
  // Inserted words reach the host through the same change event as typing, which would otherwise count as stepping in.
  const inserting = useRef(false);
  const id = 'id' in view ? view.id : null;
  const listening = view.kind === 'listening' ? view.id : null;
  const quiet = view.kind === 'listening' && view.send === 'armed' && !view.hold ? view.heard : null;
  const sending = view.kind === 'sending' ? view : null;
  const left = countdown(view, now, wait);

  const release = () => {
    if (owner === me) setOwner(null);
  };
  const begin = async (byHold: boolean) => {
    if (owner !== null) return;
    setOwner(me);
    try {
      const result = await window.bridge.startDictation();
      if ('refused' in result) {
        release();
        setView(refusalNotice(result.refused));
        return;
      }
      const since = Date.now();
      setNow(since);
      setView({ kind: 'listening', id: result.id, hold: byHold, finished: '', originals: '', partial: '', since, heard: null, send: auto.on ? 'armed' : 'off' });
      // Right ⌥ came up while the helper was starting.
      if (byHold && !hold.current.held) void window.bridge.stopDictation(result.id);
    }
    catch {
      release();
      setView(refusalNotice('unavailable'));
    }
  };

  useEffect(() => {
    live.current = view.kind === 'listening' || view.kind === 'finishing' ? view.id : null;
    press.current = send;
  });

  useEffect(() => {
    if (id === null) return;
    return window.bridge.onDictationEvent(event => {
      if (event.id !== id) return;
      if ((event.type === 'done' || event.type === 'cancelled' || event.type === 'failed') && owner === me) setOwner(null);
      if (event.type === 'done') {
        const input = field.current;
        if (input === null || event.text.trim() === '') {
          setView({ kind: 'notice', text: 'No speech heard', settings: false });
          return;
        }
        input.focus();
        inserting.current = true;
        try {
          // Typed through the editing stack, so the host's onChange saves it and ⌘Z removes it.
          document.execCommand('insertText', false, spaced(input.value.slice(0, input.selectionStart), event.text, input.value.slice(input.selectionEnd)));
        }
        finally {
          inserting.current = false;
        }
      }
      const at = Date.now();
      setNow(at);
      setView(current => nextView(current, event, at, currentAutoSend().seconds * 1000));
    });
  }, [id, field, me]);

  useEffect(() => {
    if (listening === null) return;
    const tick = window.setInterval(() => setNow(Date.now()), 1000);
    const blur = () => {
      // Nothing is sent while the user looks elsewhere; the words stay in the draft.
      setView(stepIn);
      void window.bridge.stopDictation(listening);
    };
    window.addEventListener('blur', blur);
    return () => {
      window.clearInterval(tick);
      window.removeEventListener('blur', blur);
    };
  }, [listening]);

  // Each second of a countdown shows as it starts, which the clock's tick would miss.
  const counting = quiet !== null || sending !== null;
  useEffect(() => {
    if (!counting) return;
    const tick = window.setInterval(() => setNow(Date.now()), 250);
    return () => window.clearInterval(tick);
  }, [counting]);

  // The quiet ran out: listening stops, and Send is pressed once the words are in the draft.
  useEffect(() => {
    if (quiet === null || listening === null) return;
    const timer = window.setTimeout(() => {
      setView(current => (current.kind === 'listening' && current.id === listening && current.send === 'armed' ? { ...current, send: 'due' } : current));
      void window.bridge.stopDictation(listening);
    }, wait);
    return () => window.clearTimeout(timer);
  }, [quiet, listening, wait]);

  useEffect(() => {
    if (sending === null) return;
    const leave = () => setView(stepIn);
    window.addEventListener('blur', leave);
    const timer = window.setTimeout(() => {
      setView(current => (current === sending ? idle : current));
      // Never an emptied draft; `press` also leaves a disabled Send alone, as a click would.
      if (field.current !== null && field.current.value.trim() !== '') press.current();
    }, sending.wait);
    return () => {
      window.clearTimeout(timer);
      window.removeEventListener('blur', leave);
    };
  }, [sending, field]);

  useEffect(() => {
    if (view.kind !== 'notice') return;
    const timer = window.setTimeout(() => setView(current => (current === view ? idle : current)), view.settings ? 6000 : 2000);
    return () => window.clearTimeout(timer);
  }, [view]);

  useEffect(() => () => {
    window.clearTimeout(hold.current.timer);
    // The draft goes with the composer, so its words have nowhere to land.
    if (live.current !== null) void window.bridge.cancelDictation(live.current);
    if (owner === me) setOwner(null);
  }, [me]);

  const ready = view.kind === 'idle' || view.kind === 'notice' || view.kind === 'sending';
  const stop = () => {
    if (view.kind === 'listening') void window.bridge.stopDictation(view.id);
  };
  const cancel = () => {
    if (view.kind === 'listening' || view.kind === 'finishing') void window.bridge.cancelDictation(view.id);
  };
  const abandonHold = () => {
    window.clearTimeout(hold.current.timer);
    hold.current.timer = undefined;
  };

  return {
    available: on,
    /** The written language's name while dictated words are translated before they reach the draft. */
    into: settings.written === null ? null : languageName(settings.written),
    view,
    elapsed: view.kind === 'listening' ? Math.max(0, Math.floor((now - view.since) / 1000)) : 0,
    /** Seconds left before Send is pressed on its own, while that countdown shows. */
    countdown: left,
    micDisabled: disabled || elsewhere || view.kind === 'finishing',
    /** The user's own edit, click in the text, or Send: a dictation that would send itself stays a draft. */
    stepIn: () => {
      if (!inserting.current) setView(stepIn);
    },
    toggle: () => {
      const input = field.current;
      if (input !== null) {
        // A text box never clicked into keeps its caret at the start; dictated words then follow the draft instead.
        if (document.activeElement !== input && input.selectionStart === 0 && input.selectionEnd === 0) input.setSelectionRange(input.value.length, input.value.length);
        // Esc and the finished words both work through the text box.
        input.focus();
      }
      // The mic stops a countdown too: listening ends and the words stay a draft.
      setView(stepIn);
      if (view.kind === 'listening') stop();
      else if (ready) void begin(false);
    },
    retry: async () => {
      if (view.kind !== 'failed' || owner !== null) return;
      setOwner(me);
      const result = await window.bridge.retryDictation(view.id).catch((): { refused: DictationRefusal } => ({ refused: 'unavailable' }));
      if ('refused' in result) {
        release();
        setView(refusalNotice(result.refused));
      }
      // Retried words land as a draft; only speech the user just finished sends itself.
      else setView({ kind: 'finishing', id: result.id, hold: false, finished: '', originals: '', partial: '', send: 'off' });
    },
    discard: () => {
      if (view.kind !== 'failed') return;
      void window.bridge.discardDictation(view.id);
      setView(idle);
    },
    openSettings: () => { window.location.hash = settingsRoute('features'); },
    keyDown: (event: KeyboardEvent<HTMLTextAreaElement>) => {
      if (!on) return;
      if (event.key === 'Escape' && (left !== null || view.kind === 'sending' || (view.kind === 'finishing' && view.send === 'due'))) {
        // Esc during a countdown stops only the countdown; the words stay, and a second Esc cancels a dictation still listening.
        event.preventDefault();
        event.stopPropagation();
        setView(stepIn);
        return;
      }
      if (event.key === 'Escape' && (view.kind === 'listening' || view.kind === 'finishing')) {
        // Esc ends the dictation only; it must not also close the panel around the composer.
        event.preventDefault();
        event.stopPropagation();
        cancel();
        return;
      }
      if (event.code === 'AltRight' && !event.repeat && !event.metaKey && !event.ctrlKey && !event.shiftKey) {
        if (ready && !disabled && !elsewhere) {
          hold.current.held = true;
          hold.current.timer = window.setTimeout(() => {
            hold.current.timer = undefined;
            void begin(true);
          }, HOLD_MS);
        }
        return;
      }
      // Another key while ⌥ is down is an ordinary ⌥ shortcut or character.
      abandonHold();
    },
    keyUp: (event: KeyboardEvent<HTMLTextAreaElement>) => {
      if (event.code !== 'AltRight') return;
      hold.current.held = false;
      if (hold.current.timer !== undefined) abandonHold();
      else if (view.kind === 'listening' && view.hold) stop();
    },
  };
}
