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
import { createRef, useState } from 'react';
import { flushSync } from 'react-dom';
import Composer from '../../Composer';
import { createFixtureRoot } from '../../fixtures/reactFixture';
import { assert, button, controlTimers, runScenarios, settle, until } from '../../fixtures/harness';
import { settingsRoute } from '../../settings/routes';
import type { Bridge } from '../../types';
import type { DictationEvent, DictationStart, VoiceSettings } from '../contracts';
import { AUTO_SEND, QUIET_MS, saveAutoSend } from '../useDictation';
import '../../css/base.css';
import '../../css/agents.css';

const DRAFT = 'Rename the helper';
let calls: string[] = [];
let emit: (event: DictationEvent) => void = () => {};
let voiceChanged: (settings: VoiceSettings) => void = () => {};
let nextStart: DictationStart = { id: 'd1' };
let sent = 0;
let submitted: string | null = null;
let canSend = true;
let escapes = 0;

window.bridge = {
  getVoiceSettings: async () => ({ enabled: false }),
  onVoiceChanged: (listener: (settings: VoiceSettings) => void) => { voiceChanged = listener; return () => {}; },
  startDictation: async () => { calls.push('start'); return nextStart; },
  stopDictation: async (id: string) => { calls.push(`stop ${id}`); },
  cancelDictation: async (id: string) => { calls.push(`cancel ${id}`); },
  retryDictation: async (id: string) => { calls.push(`retry ${id}`); return { id }; },
  discardDictation: async (id: string) => { calls.push(`discard ${id}`); },
  onDictationEvent: (listener: (event: DictationEvent) => void) => {
    emit = listener;
    return () => { if (emit === listener) emit = () => {}; };
  },
} as unknown as Bridge;
document.addEventListener('keydown', event => { if (event.key === 'Escape') escapes++; });

const { host, root, dispose } = createFixtureRoot();
const field = createRef<HTMLTextAreaElement>();
let mounts = 0;

function Host() {
  const [draft, setDraft] = useState(DRAFT);
  return <Composer inputRef={field} aria-label="Message" value={draft} onChange={event => setDraft(event.target.value)}
    onSubmit={() => { sent++; submitted = draft; }} submitEnabled={canSend} />;
}

async function mount(focus = true): Promise<HTMLTextAreaElement> {
  calls = [];
  sent = 0;
  submitted = null;
  escapes = 0;
  flushSync(() => root.render(<Host key={++mounts} />));
  await settle();
  const input = field.current;
  assert(input !== null, 'The composer forwards its textarea to the host');
  if (focus) {
    input.focus();
    input.setSelectionRange(input.value.length, input.value.length);
  }
  return input;
}

const strip = () => host.querySelector('.agt-dictation')?.textContent ?? '';
const mic = () => host.querySelector<HTMLButtonElement>('[aria-label="Dictate (hold right ⌥)"], [aria-label="Stop dictation"]');
const key = (input: HTMLTextAreaElement, type: 'keydown' | 'keyup', init: KeyboardEventInit) =>
  input.dispatchEvent(new KeyboardEvent(type, { bubbles: true, cancelable: true, ...init }));
const rightOption = { key: 'Alt', code: 'AltRight', altKey: true };
const send = async (event: DictationEvent) => {
  flushSync(() => emit(event));
  await settle();
};
const escape = (input: HTMLTextAreaElement) => key(input, 'keydown', { key: 'Escape', code: 'Escape' });
// The default countdown is 3 seconds; tests hold its timer and run it, so the quiet before it shows is the only real wait.
const counting = () => /sending in [1-3] · Esc stops/.test(strip());
const type = (input: HTMLTextAreaElement, text: string) => {
  input.focus();
  document.execCommand('insertText', false, text);
};

async function listening(id: string): Promise<HTMLTextAreaElement> {
  nextStart = { id };
  const input = await mount();
  mic()?.click();
  await until(() => calls.includes('start') && strip().includes('Listening…'), 'A click on the mic starts listening');
  return input;
}

export function run() {
  return runScenarios([
    ['Shows the mic only while Voice is on', async () => {
      await mount();
      assert(mic() === null, 'No mic while Voice is off');
      flushSync(() => voiceChanged({ enabled: true, spoken: null, written: null }));
      await until(() => mic() !== null, 'The mic appears as soon as Voice is turned on');
    }],
    ['Shows live words and inserts the finished text at the caret, without sending', async () => {
      const input = await listening('d1');
      await send({ id: 'd1', type: 'listening' });
      await send({ id: 'd1', type: 'partial', text: 'add a te' });
      assert(strip().includes('add a te'), 'Live words show in the strip');
      assert(input.value === DRAFT, 'Live words never touch the draft');
      await send({ id: 'd1', type: 'final', text: 'Add a test.' });
      mic()?.click();
      await until(() => calls.includes('stop d1'), 'A second click stops listening');
      await send({ id: 'd1', type: 'finishing' });
      assert(strip().includes('Finishing up'), 'The strip says it is finishing');
      await send({ id: 'd1', type: 'done', text: 'Add a test.' });
      await until(() => input.value === `${DRAFT} Add a test.`, 'The finished text lands at the caret after one space');
      assert(strip() === '' && sent === 0, 'The strip closes and nothing is sent');
      document.execCommand('undo');
      await until(() => input.value === DRAFT, '⌘Z removes the dictated words');
    }],
    ['Words go at the end of a draft never clicked into, and keep a space from text after the caret', async () => {
      nextStart = { id: 'd7' };
      let input = await mount(false);
      mic()?.click();
      await until(() => strip().includes('Listening…'), 'The mic starts listening without a click in the text');
      await send({ id: 'd7', type: 'done', text: 'Add a test.' });
      await until(() => input.value === `${DRAFT} Add a test.`, 'The words follow the existing draft');
      nextStart = { id: 'd8' };
      input = await mount();
      input.setSelectionRange(0, 0);
      mic()?.click();
      await until(() => strip().includes('Listening…'), 'Dictation starts at the caret');
      await send({ id: 'd8', type: 'done', text: 'Please' });
      await until(() => input.value === `Please ${DRAFT}`, 'The words stay where the caret was, apart from the text after it');
    }],
    ['Holding right ⌥ dictates; another key keeps ⌥ ordinary', async () => {
      nextStart = { id: 'd2' };
      const input = await mount();
      key(input, 'keydown', rightOption);
      await until(() => strip().includes('Listening…'), 'Holding right ⌥ starts dictation');
      assert(strip().includes('release to stop'), 'The strip says how to stop');
      key(input, 'keyup', rightOption);
      await until(() => calls.includes('stop d2'), 'Releasing right ⌥ stops it');
      await send({ id: 'd2', type: 'cancelled' });
      calls = [];
      key(input, 'keydown', rightOption);
      key(input, 'keydown', { key: 'é', code: 'KeyE', altKey: true });
      await new Promise(resolve => window.setTimeout(resolve, 400));
      key(input, 'keyup', rightOption);
      await settle();
      // A late stop or cancel of the session that just ended is not a new dictation.
      assert(!calls.includes('start'), `An ⌥ character does not start dictation: ${calls.join(', ')}`);
    }],
    ['Esc cancels without closing the panel around the composer', async () => {
      const input = await listening('d3');
      key(input, 'keydown', { key: 'Escape', code: 'Escape' });
      await until(() => calls.includes('cancel d3'), 'Esc cancels the dictation');
      assert(escapes === 0, 'Esc stops at the composer');
      await send({ id: 'd3', type: 'cancelled' });
      assert(strip() === '' && input.value === DRAFT, 'Nothing is inserted');
    }],
    ['A failure keeps the recording for Retry or Discard', async () => {
      const input = await listening('d4');
      await send({ id: 'd4', type: 'failed', code: 'failed', seconds: 4 });
      assert(strip().includes('Couldn’t turn 4 s of speech into text. Your recording is kept.'), 'The strip explains the failure');
      button('Retry', host).click();
      await until(() => calls.includes('retry d4') && strip().includes('Finishing up'), 'Retry transcribes the recording again');
      await send({ id: 'd4', type: 'done', text: 'again' });
      await until(() => input.value === `${DRAFT} again`, 'The retried text lands in the draft');
      nextStart = { id: 'd5' };
      mic()?.click();
      await until(() => strip().includes('Listening…'), 'Dictation starts again');
      await send({ id: 'd5', type: 'failed', code: 'failed', seconds: 2 });
      button('Discard', host).click();
      await until(() => calls.includes('discard d5') && strip() === '', 'Discard deletes the recording and closes the strip');
    }],
    ['Shows finished words in the written language, with what was said on hover', async () => {
      flushSync(() => voiceChanged({ enabled: true, spoken: 'zh_CN', written: 'en' }));
      const input = await listening('d9');
      assert(strip().includes('into English'), 'The strip says which language the words are written in');
      await send({ id: 'd9', type: 'partial', text: '加一个' });
      await send({ id: 'd9', type: 'final', text: 'Add a test.', original: '加一个测试。' });
      const finished = host.querySelector('.agt-dictation__words [title]');
      assert(finished?.textContent === 'Add a test.' && finished.getAttribute('title') === '加一个测试。', 'Translated words keep what was said as hover text');
      await send({ id: 'd9', type: 'done', text: 'Add a test.' });
      await until(() => input.value === `${DRAFT} Add a test.`, 'The translation lands in the draft');
      nextStart = { id: 'd10' };
      mic()?.click();
      await until(() => strip().includes('Listening…'), 'Dictation starts again');
      await send({ id: 'd10', type: 'failed', code: 'translation-missing', seconds: 0 });
      assert(strip().includes('The translation isn’t downloaded yet.'), 'A missing translation says so');
      flushSync(() => voiceChanged({ enabled: true, spoken: null, written: null }));
    }],
    ['Says why a start was refused and when no speech was heard', async () => {
      nextStart = { refused: 'busy' };
      await mount();
      mic()?.click();
      await until(() => strip().includes('Dictation is running in another composer.'), 'A busy start says so');
      nextStart = { refused: 'microphone-denied' };
      mic()?.click();
      await until(() => strip().includes('Dictation isn’t available right now.'), 'A blocked start says so');
      button('Open Voice settings', host).click();
      assert(window.location.hash === settingsRoute('features'), 'The strip opens the Voice setting');
      const input = await listening('d6');
      await send({ id: 'd6', type: 'done', text: '' });
      assert(strip().includes('No speech heard') && input.value === DRAFT, 'Silence inserts nothing');
    }],
    ['Presses Send once the user stops talking, with the dictated words', async () => {
      const timers = controlTimers(3000);
      try {
        const input = await listening('d11');
        await send({ id: 'd11', type: 'partial', text: 'add a test' });
        assert(!counting(), 'No countdown while the words keep coming');
        await until(counting, 'A countdown shows once the words are still');
        await send({ id: 'd11', type: 'partial', text: 'add a test' });
        assert(counting(), 'The same live words again are not new speech');
        assert(timers.tick() === 1 && sent === 0, 'One countdown runs, and nothing is sent before the words land');
        await until(() => calls.includes('stop d11'), 'The countdown stops listening');
        await send({ id: 'd11', type: 'finishing' });
        await send({ id: 'd11', type: 'done', text: 'Add a test.' });
        await until(() => sent === 1, 'Send is pressed once the words are in the draft');
        assert(submitted === `${DRAFT} Add a test.` && input.value === submitted, `Send carries the dictated words: ${submitted}`);
        assert(strip() === '', 'The strip closes');
      }
      finally { timers.restore(); }
    }],
    ['Speaking again restarts the countdown', async () => {
      const timers = controlTimers(3000);
      try {
        await listening('d12');
        await send({ id: 'd12', type: 'partial', text: 'add' });
        await until(counting, 'A countdown shows in the quiet');
        await send({ id: 'd12', type: 'final', text: 'Add' });
        assert(counting(), 'Finished words are not new speech');
        await send({ id: 'd12', type: 'partial', text: ' a test' });
        assert(!counting(), 'New words hide the countdown');
        assert(timers.tick() === 1, 'Only the quiet after the newest words counts');
        await until(() => calls.includes('stop d12'), 'That quiet stops listening');
        await send({ id: 'd12', type: 'cancelled' });
      }
      finally { timers.restore(); }
    }],
    ['Esc, typing, a click in the text, or the mic stops the countdown and keeps the words', async () => {
      const timers = controlTimers(3000);
      try {
        let input = await listening('d13');
        await send({ id: 'd13', type: 'partial', text: 'add a test' });
        await until(counting, 'A countdown shows');
        escape(input);
        await settle();
        assert(escapes === 0 && !calls.includes('cancel d13'), 'Esc stops at the composer and keeps listening');
        assert(strip().includes('add a test') && strip().includes('Esc cancels'), `The words stay, and the next Esc cancels: ${strip()}`);
        await send({ id: 'd13', type: 'partial', text: 'add a test now' });
        assert(timers.tick() === 0, 'Nothing counts down for this dictation any more');
        mic()?.click();
        await until(() => calls.includes('stop d13'), 'The mic still stops listening');
        await send({ id: 'd13', type: 'done', text: 'Add a test now.' });
        await until(() => input.value === `${DRAFT} Add a test now.`, 'The words land as a draft');

        input = await listening('d14');
        await send({ id: 'd14', type: 'partial', text: 'add a test' });
        await until(counting, 'A countdown shows again');
        type(input, '!');
        await settle();
        assert(!counting() && timers.tick() === 0, 'Typing stops it');
        escape(input);
        await until(() => calls.includes('cancel d14'), 'Esc then cancels as usual');
        await send({ id: 'd14', type: 'cancelled' });

        input = await listening('d15');
        await send({ id: 'd15', type: 'partial', text: 'add a test' });
        await until(counting, 'A countdown shows once more');
        input.dispatchEvent(new PointerEvent('pointerdown', { bubbles: true }));
        await settle();
        assert(!counting() && timers.tick() === 0, 'A click in the text stops it');
        await send({ id: 'd15', type: 'partial', text: 'add a test here' });
        mic()?.click();
        await until(() => calls.includes('stop d15'), 'The mic stops listening');
        await send({ id: 'd15', type: 'done', text: 'Add a test here.' });
        await until(() => input.value === `${DRAFT} Add a test here.`, 'The words land as a draft');
        await settle();
        assert(sent === 0, 'Nothing is sent');
      }
      finally { timers.restore(); }
    }],
    ['Holding right ⌥ counts down once its words are in the draft', async () => {
      const timers = controlTimers(3000);
      try {
        nextStart = { id: 'd16' };
        let input = await mount();
        key(input, 'keydown', rightOption);
        await until(() => strip().includes('release to stop'), 'Holding right ⌥ starts dictation');
        await send({ id: 'd16', type: 'partial', text: 'add a test' });
        assert(timers.tick() === 0, 'Holding the key is still talking');
        key(input, 'keyup', rightOption);
        await until(() => calls.includes('stop d16'), 'Letting go stops listening');
        await send({ id: 'd16', type: 'finishing' });
        await send({ id: 'd16', type: 'done', text: 'Add a test.' });
        await until(() => strip().includes('Sending in 3'), `A countdown shows once the words are in the draft: ${strip()}`);
        assert(input.value === `${DRAFT} Add a test.` && sent === 0, 'The words wait in the draft');
        assert(timers.tick() === 1, 'One countdown runs');
        await until(() => sent === 1 && strip() === '', 'Then Send is pressed and the strip closes');
        assert(submitted === `${DRAFT} Add a test.`, `Send carries the dictated words: ${submitted}`);

        nextStart = { id: 'd17' };
        input = await mount();
        key(input, 'keydown', rightOption);
        await until(() => strip().includes('release to stop'), 'Dictation starts again');
        key(input, 'keyup', rightOption);
        await until(() => calls.includes('stop d17'), 'Letting go stops it');
        await send({ id: 'd17', type: 'done', text: 'Add a test.' });
        await until(() => strip().includes('Sending in'), 'Its countdown shows');
        escape(input);
        await settle();
        assert(strip() === '' && escapes === 0 && timers.tick() === 0, 'Esc stops the countdown at the composer');
        assert(sent === 0 && input.value === `${DRAFT} Add a test.`, 'and keeps the draft');
      }
      finally { timers.restore(); }
    }],
    ['Never presses a disabled Send, or sends when no words were heard', async () => {
      const timers = controlTimers(3000);
      try {
        canSend = false;
        let input = await listening('d18');
        await send({ id: 'd18', type: 'partial', text: 'add a test' });
        assert(timers.tick() === 1, 'The countdown runs');
        await until(() => calls.includes('stop d18'), 'and stops listening');
        await send({ id: 'd18', type: 'done', text: 'Add a test.' });
        await until(() => input.value === `${DRAFT} Add a test.`, 'The words land in the draft');
        await settle();
        assert(sent === 0, 'A disabled Send stays unpressed');

        canSend = true;
        input = await listening('d19');
        await send({ id: 'd19', type: 'partial', text: 'um' });
        assert(timers.tick() === 1, 'The countdown runs');
        await until(() => calls.includes('stop d19'), 'and stops listening');
        await send({ id: 'd19', type: 'done', text: '' });
        await settle();
        assert(strip().includes('No speech heard') && input.value === DRAFT && sent === 0, 'Nothing is sent without words');
      }
      finally {
        timers.restore();
        canSend = true;
      }
    }],
    ['Stays a draft with the switch off, and counts the seconds chosen in Settings', async () => {
      saveAutoSend({ on: false, seconds: 3 });
      let timers = controlTimers(3000);
      try {
        await listening('d20');
        await send({ id: 'd20', type: 'partial', text: 'add a test' });
        await new Promise(resolve => window.setTimeout(resolve, QUIET_MS + 200));
        assert(!counting() && strip().includes('click the mic to stop') && timers.tick() === 0, 'No countdown with the switch off');
        await send({ id: 'd20', type: 'cancelled' });
      }
      finally { timers.restore(); }
      saveAutoSend({ on: true, seconds: 1 });
      timers = controlTimers(1000);
      try {
        await listening('d21');
        await send({ id: 'd21', type: 'partial', text: 'add a test' });
        assert(timers.tick() === 1, 'The countdown takes the chosen second');
        await until(() => calls.includes('stop d21'), 'and then stops listening');
        await send({ id: 'd21', type: 'cancelled' });
      }
      finally {
        timers.restore();
        saveAutoSend(AUTO_SEND);
      }
    }],
  ], () => {
    flushSync(() => root.render(null));
  }).finally(dispose);
}
