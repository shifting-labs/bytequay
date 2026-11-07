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
import { describe, expect, it } from 'vitest';
import { AUTO_SEND, countdown, languageName, nextView, QUIET_MS, readAutoSend, refusalNotice, spaced, startFailure, stepIn, type DictationView } from './useDictation';

const listening = { kind: 'listening', id: 'd1', hold: true, finished: '', originals: '', partial: '', since: 0, heard: null, send: 'armed' } satisfies DictationView;
const talking = { ...listening, hold: false } satisfies DictationView;

describe('a composer dictation view', () => {
  it('replaces live words, appends finished ones, and ignores other dictations', () => {
    let view = nextView({ ...listening, send: 'off' }, { id: 'd1', type: 'partial', text: 'Hel' });
    expect(view).toMatchObject({ finished: '', partial: 'Hel' });
    view = nextView(view, { id: 'd1', type: 'final', text: 'Hello.' });
    expect(view).toMatchObject({ finished: 'Hello.', partial: '' });
    view = nextView(view, { id: 'd1', type: 'partial', text: ' Again' });
    expect(nextView(view, { id: 'other', type: 'final', text: 'Not mine.' })).toBe(view);
    view = nextView(view, { id: 'd1', type: 'finishing' });
    expect(view).toEqual({ kind: 'finishing', id: 'd1', hold: true, finished: 'Hello.', originals: '', partial: ' Again', send: 'off' });
    expect(nextView(view, { id: 'd1', type: 'done', text: 'Hello. Again.' })).toEqual({ kind: 'idle' });
  });

  it('starts the quiet when the live words change, not when they repeat or finish', () => {
    let view = nextView(talking, { id: 'd1', type: 'partial', text: 'Add' }, 1000);
    expect(view).toMatchObject({ heard: 1000 });
    view = nextView(view, { id: 'd1', type: 'partial', text: 'Add' }, 1500);
    view = nextView(view, { id: 'd1', type: 'partial', text: ' ' }, 1600);
    expect(view).toMatchObject({ heard: 1000 });
    // After a pause the engine ends the sentence and capitalizes it; neither is speech.
    expect(nextView({ ...talking, partial: 'add', heard: 1000 }, { id: 'd1', type: 'partial', text: 'Add.' }, 1650)).toMatchObject({ heard: 1000, partial: 'Add.' });
    expect(nextView({ ...talking, partial: '加上重试', heard: 1000 }, { id: 'd1', type: 'partial', text: '加上重试。' }, 1650)).toMatchObject({ heard: 1000 });
    view = nextView(view, { id: 'd1', type: 'final', text: 'Add.' }, 1700);
    expect(view).toMatchObject({ heard: 1000, finished: 'Add.' });
    expect(nextView(view, { id: 'd1', type: 'partial', text: 'a test' }, 2000)).toMatchObject({ heard: 2000 });
  });

  it('counts down only after the words have been still a moment, and never while right ⌥ is held', () => {
    const heard = { ...talking, heard: 1000 };
    expect(countdown(heard, 1000 + QUIET_MS - 1, 3000)).toBeNull();
    expect(countdown(heard, 1000 + QUIET_MS, 3000)).toBe(3);
    expect(countdown(heard, 2000, 3000)).toBe(2);
    expect(countdown(heard, 3999, 3000)).toBe(1);
    expect(countdown(heard, 4000, 3000)).toBeNull();
    expect(countdown(talking, 5000, 3000)).toBeNull();
    expect(countdown({ ...heard, hold: true }, 2000, 3000)).toBeNull();
    expect(countdown({ ...heard, send: 'off' }, 2000, 3000)).toBeNull();
    expect(countdown({ kind: 'sending', since: 1000, wait: 3000 }, 1000, 1)).toBe(3);
    expect(countdown({ kind: 'sending', since: 1000, wait: 0 }, 1000, 3000)).toBeNull();
  });

  it('presses Send once the words land: at once after a countdown, after one more for a held dictation', () => {
    const done = { id: 'd1', type: 'done', text: 'Add a test.' } as const;
    expect(nextView({ ...talking, send: 'due' }, done, 5000, 3000)).toEqual({ kind: 'sending', since: 5000, wait: 0 });
    expect(nextView({ kind: 'finishing', id: 'd1', hold: true, finished: '', originals: '', partial: '', send: 'armed' }, done, 5000, 3000))
      .toEqual({ kind: 'sending', since: 5000, wait: 3000 });
    // Words that end without a countdown, such as a toggled dictation the helper ended, stay a draft.
    expect(nextView(talking, done, 5000, 3000)).toEqual({ kind: 'idle' });
    expect(nextView({ ...listening, send: 'off' }, done, 5000, 3000)).toEqual({ kind: 'idle' });
    expect(nextView({ ...talking, send: 'due' }, { ...done, text: ' ' }, 5000, 3000)).toEqual({ kind: 'idle' });
  });

  it('keeps the words as a draft once the user steps in', () => {
    expect(stepIn({ ...talking, heard: 1000 })).toMatchObject({ kind: 'listening', send: 'off', heard: 1000 });
    expect(stepIn({ kind: 'finishing', id: 'd1', hold: false, finished: '', originals: '', partial: '', send: 'due' })).toMatchObject({ send: 'off' });
    expect(stepIn({ kind: 'sending', since: 0, wait: 3000 })).toEqual({ kind: 'idle' });
    const quiet: DictationView = { ...talking, send: 'off' };
    expect(stepIn(quiet)).toBe(quiet);
  });

  it('reads the saved automatic send, on after 3 seconds unless chosen otherwise', () => {
    expect(readAutoSend(null)).toEqual(AUTO_SEND);
    expect(AUTO_SEND).toEqual({ on: true, seconds: 3 });
    expect(readAutoSend('{"on":false,"seconds":7}')).toEqual({ on: false, seconds: 7 });
    expect(readAutoSend('{"seconds":10}')).toEqual({ on: true, seconds: 10 });
    for (const seconds of ['0', '11', '2.5', '"4"']) expect(readAutoSend(`{"on":true,"seconds":${seconds}}`)).toEqual(AUTO_SEND);
    expect(readAutoSend('not json')).toEqual(AUTO_SEND);
  });

  it('keeps the spoken words beside their translation', () => {
    let view = nextView(listening, { id: 'd1', type: 'final', text: 'Add a test.', original: '加一个测试。' });
    view = nextView(view, { id: 'd1', type: 'final', text: ' Then run it.', original: '然后运行它。' });
    expect(view).toMatchObject({ finished: 'Add a test. Then run it.', originals: '加一个测试。然后运行它。' });
  });

  it('says why a dictation stopped before any audio', () => {
    expect(nextView(listening, { id: 'd1', type: 'failed', code: 'translation-missing', seconds: 0 })).toEqual({
      kind: 'notice', text: 'The translation isn’t downloaded yet.', settings: true,
    });
    expect(startFailure('model-missing')).toBe('The speech model isn’t downloaded yet.');
    expect(startFailure('microphone-denied')).toBe('ByteQuay can’t use the microphone.');
    expect(startFailure('failed')).toBe('Dictation couldn’t start.');
  });

  it('names languages from the helper ids', () => {
    expect(languageName('en')).toBe('English');
    expect(languageName('zh_CN')).toContain('Chinese');
  });

  it('keeps a failure with audio for Retry, and explains one without', () => {
    expect(nextView(listening, { id: 'd1', type: 'failed', code: 'failed', seconds: 4 })).toEqual({ kind: 'failed', id: 'd1', seconds: 4 });
    expect(nextView(listening, { id: 'd1', type: 'failed', code: 'microphone-denied', seconds: 0 })).toMatchObject({ kind: 'notice', settings: true });
    expect(nextView(listening, { id: 'd1', type: 'cancelled' })).toEqual({ kind: 'idle' });
  });

  it('points a refusal at the Voice setting unless another composer is dictating', () => {
    expect(refusalNotice('busy')).toMatchObject({ settings: false });
    expect(refusalNotice('microphone-denied')).toMatchObject({ settings: true });
  });

  it('puts one space between dictated words and any text they touch', () => {
    expect(spaced('Rename the helper', ' add a test. ')).toBe(' add a test.');
    expect(spaced('', 'Add a test.')).toBe('Add a test.');
    expect(spaced('First line\n', 'Second.')).toBe('Second.');
    expect(spaced('Done. ', 'Next.')).toBe('Next.');
    expect(spaced('', 'Please', 'rename it')).toBe('Please ');
    expect(spaced('Rename', 'the retry', ' helper')).toBe(' the retry');
  });
});
