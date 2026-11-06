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
import { flushSync } from 'react-dom';
import UpdateButton from './UpdateButton';
import type { AppUpdateState } from './contracts';
import { createFixtureRoot } from '../fixtures/reactFixture';
import { assert, runScenarios, settle } from '../fixtures/harness';
import '../css/base.css';

export async function run() {
  return runScenarios([
    ['shows availability, forwards one click, follows progress, and unsubscribes', async () => {
      const view = createFixtureRoot();
      let listener: ((state: AppUpdateState) => void) | null = null;
      let clicks = 0;
      window.bridge = {
        readAppUpdate: async () => ({ status: 'idle' }),
        onAppUpdate: receive => { listener = receive; return () => { listener = null; }; },
        installAppUpdate: async () => { clicks++; listener?.({ status: 'downloading', version: '0.6.0' }); },
      } as typeof window.bridge;
      try {
        flushSync(() => view.root.render(<aside className="app-sidebar"><UpdateButton /></aside>));
        await settle(); assert(!view.host.querySelector('button'), 'Current installs have no update button');
        const publish = (state: AppUpdateState) => listener?.(state);
        flushSync(() => publish({ status: 'available', version: '0.6.0' }));
        const button = view.host.querySelector('button'); assert(button, 'A new release appears');
        flushSync(() => button.click());
        button.click();
        assert(clicks === 1 && button.disabled, 'Progress disables repeat clicks');
        flushSync(() => publish({ status: 'error', version: '0.6.0', message: 'Verification failed. Retry.' }));
        assert(view.host.querySelector('[role="alert"]') && !button.disabled, 'Failure exposes a retry');
        flushSync(() => publish({ status: 'restarting', version: '0.6.0' }));
        assert(button.disabled && button.textContent?.includes('Restarting'), 'No second restart button appears');
        flushSync(() => publish({ status: 'idle' }));
        assert(!view.host.querySelector('button'), 'The action disappears when current');
      } finally { view.dispose(); }
      assert(listener === null, 'Unmount releases the listener');
    }],
    ['does not replace a new event with an old initial snapshot', async () => {
      const view = createFixtureRoot();
      const initial = Promise.withResolvers<AppUpdateState>();
      window.bridge = {
        readAppUpdate: () => initial.promise,
        onAppUpdate: receive => { receive({ status: 'available', version: '0.6.0' }); return () => {}; },
        installAppUpdate: async () => {},
      } as typeof window.bridge;
      try {
        flushSync(() => view.root.render(<UpdateButton />));
        initial.resolve({ status: 'idle' }); await settle();
        assert(view.host.querySelector('button')?.textContent?.includes('0.6.0'), 'The live event wins over stale metadata');
      } finally { view.dispose(); }
    }],
  ]);
}
