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
import { NoticeCard, ResultCard } from '../Cards';

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

function render(notice: AppNotice, result = false) {
  const { host, root, dispose } = createFixtureRoot();
  let opened = 0;
  let reviewed = 0;
  let cleared = 0;
  flushSync(() => root.render(result
    ? <ResultCard notice={notice} onShow={target => { assert(target.kind === 'PULL' && target.number === 4530, 'Open resolves the pull request rather than a synthetic task'); opened++; }}
      onOpen={() => { assert(notice.reviewId !== null, 'A rule without a review must open the pull request'); reviewed++; }} onClear={() => { cleared++; }} />
    : <NoticeCard notice={notice} focused={false} onOpen={() => { opened++; }} onClear={() => { cleared++; }} />));
  // Read through functions: an assertion on a plain field would narrow it to the count it checked.
  return { host, opened: () => opened, reviewed: () => reviewed, cleared: () => cleared, dispose };
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
    ['shows automatic merge outcomes with attribution and opens the actual pull request', async () => {
      const notice: AppNotice = { ...RESULT, id: `rule-action:${RULE}:pr:4530`, taskId: `rule:${RULE}`, kind: 'TURN_FINISHED', reviewId: null, turnId: null,
        message: 'Merged the approved pull request.', ruleRun: { subject: 'acme/warehouse#4530', title: 'Cache block reads', occurredAt: RESULT.occurredAt,
          verdict: 'PENDING', suggestion: false, skipped: null, outcome: 'MERGED', authorization: { ruleId: RULE, ruleName: 'Merge approved work', ruleVersion: 2 } } };
      const card = render(notice, true);
      try {
        assert(card.host.querySelector('[data-state="COMPLETED"]') !== null && card.host.textContent?.includes('under Merge approved work'), 'A verified merge is completed and attributed');
        assert(!card.host.textContent?.includes('Check suggestion') && !card.host.textContent?.includes('Dismiss'), 'An already authorized action does not ask for acceptance');
        button('Open pull request', card.host).click();
        await settle();
        assert(card.opened() === 1, 'Open uses the pull request');
      }
      finally { card.dispose(); }
      const run = notice.ruleRun;
      if (run == null) throw new Error('The result needs its rule run');
      const uncertain = render({ ...notice, kind: 'FAILURE', ruleRun: { ...run, outcome: 'UNKNOWN' } }, true);
      try { assert(uncertain.host.querySelector('[data-state="ATTENTION"]') !== null, 'Unknown remote outcomes need attention, never a retry or completion claim'); }
      finally { uncertain.dispose(); }
    }],
    ['shows the Assistant assessment inline and opens the full private review', async () => {
      const summary = 'The cache now reuses block reads. One correctness risk needs your review: invalidation can leave stale data after a write.';
      const notice: AppNotice = { ...RESULT, id: 'finished:turn-1', kind: 'TURN_FINISHED', message: summary,
        ruleRun: { subject: 'acme/warehouse#4530', title: 'Cache block reads', occurredAt: RESULT.occurredAt,
          verdict: 'PENDING', suggestion: false, skipped: null, outcome: 'RESULT_READY',
          authorization: { ruleId: RULE, ruleName: 'Review changes to my lines', ruleVersion: 3 } } };
      const card = render(notice, true);
      try {
        assert(card.host.querySelector('[data-state="RESULT_READY"]') !== null, 'A completed review is a result to inspect, not completed work');
        assert(card.host.querySelector('.assistant-card__assessment')?.textContent === summary, 'The Assistant summary is readable without opening the full review');
        assert(card.host.textContent?.includes('under Review changes to my lines') && card.host.textContent.includes('rule revision 3'), 'The card retains the rule and revision that started the review');
        assert(!card.host.textContent?.includes('The latest response is ready to read.'), 'The generic ready message does not replace the assessment');
        button('Read full review', card.host).click();
        await settle();
        assert(card.reviewed() === 1 && card.opened() === 0 && card.cleared() === 0, 'Read full review opens the linked private review without clearing it or opening the PR');
        button('Clear result', card.host).click();
        await settle();
        assert(card.cleared() === 1, 'The result can still be cleared');
      }
      finally { card.dispose(); }
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
