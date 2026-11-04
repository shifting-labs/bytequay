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
import { createFixtureRoot } from '../../fixtures/reactFixture';
import { assert, button, runScenarios, settle } from '../../fixtures/harness';
import type { AppNotice } from '../../notices/contracts';
import type { Bridge } from '../../types';
import { NoticeCard } from '../Cards';

const RULE = '6f1c9a52-3d0e-4b8a-9a1d-2b7c4e5f6a70';

const MOVE_UP: AppNotice = {
  id: `rule:${RULE}:1793001600000`, kind: 'QUESTION', taskId: `rule:${RULE}`, workspaceId: 'rules', turnId: null, reviewId: null,
  title: 'Review PRs that need me', pullRequest: null, pullRequestTitle: null, changes: null,
  message: 'Agreed 10 of 12 times. Move it to Suggest?', repository: 'Rules', laneId: 'rules', occurredAt: '2026-11-04T09:00:00Z',
};

const RESULT: AppNotice = {
  id: `rule-result:${RULE}:pr:4530:requested:0`, kind: 'QUESTION', taskId: 'task-1', workspaceId: 'ws1', turnId: 'turn-1', reviewId: 'review-1',
  title: 'Review acme/warehouse#4530', pullRequest: 'acme/warehouse#4530', pullRequestTitle: 'Cache block reads', changes: null,
  message: 'Review PRs that need me · drafted its findings; nothing is posted until you publish them', repository: 'acme/warehouse', laneId: 'claude',
  occurredAt: '2026-11-04T09:30:00Z',
};

function render(notice: AppNotice) {
  const { host, root, dispose } = createFixtureRoot();
  let opened = 0;
  let cleared = 0;
  flushSync(() => root.render(<NoticeCard notice={notice} focused={false} onOpen={() => { opened++; }} onClear={() => { cleared++; }} />));
  // Read through functions: an assertion on a plain field would narrow it to the count it checked.
  return { host, opened: () => opened, cleared: () => cleared, dispose };
}

export async function run(): Promise<{ name: string; error?: string }[]> {
  const calls: string[] = [];
  let refused = false;
  window.bridge = {
    moveAgentRuleUp: async (id: string) => {
      calls.push(`up ${id}`);
      if (refused) throw new Error("That rule's record does not let it move up yet");
      return [];
    },
    keepAgentRuleObserving: async (id: string) => { calls.push(`keep ${id}`); return []; },
    dismissAgentRuleRun: async (id: string, key: string) => { calls.push(`dismiss ${id} ${key}`); return []; },
  } as unknown as Bridge;
  return runScenarios([
    ['moves a rule up or keeps it observing from its card, and says why a move was refused', async () => {
      const card = render(MOVE_UP);
      try {
        const text = card.host.textContent ?? '';
        assert(text.includes('Review PRs that need me') && text.includes('Agreed 10 of 12 times. Move it to Suggest?') && text.includes('Move up?'),
          `The card names the rule and its record: ${text}`);
        button('Move to Suggest', card.host).click();
        await settle();
        assert(calls.at(-1) === `up ${RULE}` && card.cleared() === 1, `Move to Suggest moves the rule up and clears the card: ${calls.join(' | ')}`);
        button('Keep observing', card.host).click();
        await settle();
        assert(calls.at(-1) === `keep ${RULE}` && card.cleared() === 2, `Keep observing keeps it and clears the card: ${calls.join(' | ')}`);
        refused = true;
        button('Move to Suggest', card.host).click();
        await settle();
        const alert = card.host.querySelector('[role="alert"]')?.textContent ?? '';
        assert(alert.includes('does not let it move up yet') && card.cleared() === 2, `A refused move says why and keeps the card: ${alert}`);
      }
      finally {
        refused = false;
        card.dispose();
      }
    }],
    ["opens a rule's finished work, or dismisses it as the run it came from", async () => {
      const card = render(RESULT);
      try {
        const text = card.host.textContent ?? '';
        assert(text.includes('Review · Cache block reads') && text.includes('Check suggestion') && text.includes('nothing is posted until you publish them'),
          `The card names the work and that nothing was posted: ${text}`);
        button('Open', card.host).click();
        await settle();
        assert(card.opened() === 1 && card.cleared() === 0, 'Open opens the work and keeps the card until the record decides');
        button('Dismiss', card.host).click();
        await settle();
        assert(calls.at(-1) === `dismiss ${RULE} pr:4530:requested:0` && card.cleared() === 1, `Dismiss names the rule and the run: ${calls.join(' | ')}`);
      }
      finally {
        card.dispose();
      }
    }],
  ]);
}
