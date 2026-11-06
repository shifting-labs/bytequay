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
import { assert, button, changeInput, element, runScenarios, settle } from '../../fixtures/harness';
import type { AppNotice } from '../../notices/contracts';
import type { AssistantItemDto, AssistantReportDto, Bridge, HeldReviewReplyDto } from '../../types';
import { DurableReportCard, NoticeCard, ResultCard } from '../Cards';
import RuleOfferCard from '../RuleOfferCard';
import type { TaskDiscussionDto } from '../../agents/discussions/contracts';
import '../../index.css';
import '../../css/assistant.css';

const RULE = '6f1c9a52-3d0e-4b8a-9a1d-2b7c4e5f6a70';

const STORAGE = `---
name: Review pull requests that touch storage
when: review-requested
repositories: [acme/warehouse]
paths: [src/storage/**]
does: review
agrees-when: reviewed
---
Check the storage changes for bugs and missing tests.`;

const OFFER: AssistantItemDto = { id: 'offer-1', conversationId: 'c1', at: '2026-11-06T09:00:00Z', kind: 'RULE', targets: {}, notReady: false,
  text: 'You reviewed 9 of the 11 pull requests that touched src/storage', receipt: null, list: null, draft: null, report: null, key: null, rule: STORAGE };

const MOVE_UP: AppNotice = { delivery: 'INTERRUPT', deliveryReason: 'A decision needs your attention.', report: null,
  id: `rule:${RULE}:1793001600000`, kind: 'QUESTION', taskId: `rule:${RULE}`, workspaceId: 'rules', turnId: null, reviewId: null,
  title: 'Review PRs that need me', pullRequest: null, pullRequestTitle: null, changes: null,
  message: 'Agreed 10 of 12 times. Move it to Suggest?', repository: 'Rules', laneId: 'rules', occurredAt: '2026-11-04T09:00:00Z',
};

const RESULT: AppNotice = { delivery: 'INTERRUPT', deliveryReason: 'A decision needs your attention.', report: null,
  id: `rule-result:${RULE}:pr:4530:requested:0`, kind: 'QUESTION', taskId: 'task-1', workspaceId: 'ws1', turnId: 'turn-1', reviewId: 'review-1',
  title: 'Review acme/warehouse#4530', pullRequest: 'acme/warehouse#4530', pullRequestTitle: 'Cache block reads', changes: null,
  message: 'Review PRs that need me · drafted its findings; nothing is posted until you publish them', repository: 'acme/warehouse', laneId: 'claude',
  occurredAt: '2026-11-04T09:30:00Z',
};

const REPLIES: AppNotice = { delivery: 'INTERRUPT', deliveryReason: 'A decision needs your attention.', report: null,
  id: 'replies:task-2:1793001600000', kind: 'APPROVAL', taskId: 'task-2', workspaceId: 'ws1', turnId: 'turn-2', reviewId: null,
  title: 'Cache block reads', pullRequest: 'acme/warehouse#4530', pullRequestTitle: 'Cache block reads', changes: null,
  message: '2 review replies are ready to check before they post.', repository: 'acme/warehouse', laneId: 'claude', occurredAt: '2026-11-04T09:30:00Z',
};

const HELD: HeldReviewReplyDto[] = [
  { commentId: 11, author: 'bob', comment: 'Rename this to blockCache so it matches the reader.', url: 'https://github.com/acme/warehouse/pull/4530#discussion_r11',
    reply: 'Renamed it to blockCache in both places.', fixed: true, postAt: '2026-11-04T09:55:00Z' },
  { commentId: 21, author: 'carol', comment: 'Use a map here?', url: 'https://github.com/acme/warehouse/pull/4530#discussion_r21',
    reply: 'A list keeps the order the API promises, so I left it.', fixed: false, postAt: '2026-11-04T09:55:00Z' },
];

const heldBridge = (held: () => HeldReviewReplyDto[], calls: string[]): Partial<Bridge> => ({
  readHeldReviewReplies: async (taskId: string, workspaceId: string) => { calls.push(`read ${taskId} ${workspaceId}`); return held(); },
  postHeldReviewReplies: async (taskId: string) => { calls.push(`post ${taskId}`); return []; },
  keepHeldReviewReplies: async (taskId: string) => { calls.push(`keep back ${taskId}`); return []; },
});

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
    ['saves an offered rule as the user changed it, says why a save was refused, or leaves it unsaved', async () => {
      const fixture = createFixtureRoot();
      const answers: unknown[][] = [];
      let refuse = true;
      let answered = 0;
      // Read through a function: an assertion on the count itself would narrow it to the number it checked.
      const answeredCount = () => answered;
      const previous = window.bridge;
      window.bridge = { ...previous,
        answerAssistantRule: async (...args: unknown[]) => {
          answers.push(args);
          if (refuse) {
            refuse = false;
            throw new Error('At most 50 rules can be saved');
          }
        },
      } as Bridge;
      const show = (done: boolean) => flushSync(() => fixture.root.render(<RuleOfferCard item={OFFER} answered={done} onAnswered={() => { answered++; }} />));
      try {
        show(false);
        const text = fixture.host.textContent ?? '';
        assert(text.includes('Review pull requests that touch storage') && text.includes('You reviewed 9 of the 11') && text.includes('Starts in Observe'),
          `The card names the rule and the data behind it: ${text}`);
        const box = element<HTMLTextAreaElement>('textarea', fixture.host);
        assert(box.value === STORAGE, 'The whole rule file shows, to change in place');
        const edited = STORAGE.replace('bugs and missing tests', 'bugs');
        changeInput(box, edited); await settle();
        button('Save rule', fixture.host).click(); await settle();
        const alert = fixture.host.querySelector('[role="alert"]')?.textContent ?? '';
        assert(alert.includes('At most 50 rules') && answeredCount() === 0, `A refused save says why and keeps the card: ${alert}`);
        button('Save rule', fixture.host).click(); await settle();
        assert(JSON.stringify(answers[1]) === JSON.stringify(['c1', 'offer-1', edited]) && answeredCount() === 1, `Save sends the rule as changed: ${JSON.stringify(answers)}`);
        button('Not now', fixture.host).click(); await settle();
        assert(JSON.stringify(answers[2]) === JSON.stringify(['c1', 'offer-1', null]) && answeredCount() === 2, 'Not now saves nothing');
        show(true);
        assert(fixture.host.querySelector('form') === null, 'An answered offer leaves only its receipt');
      }
      finally { fixture.dispose(); window.bridge = previous; }
    }],
    ['asks privately about the exact result without starting or replying to the task', async () => {
      const fixture = createFixtureRoot();
      const asked: unknown[][] = [];
      const report = quietReport();
      let discussion: TaskDiscussionDto | null = null;
      const previous = window.bridge;
      window.bridge = { ...previous,
        listTaskDiscussions: async () => discussion === null ? [] : [discussion],
        askAssistantResult: async (...args: unknown[]) => {
          asked.push(args);
          discussion = { id: 'discussion-1', taskId: 'task-1', turnId: 'source-turn', quote: 'The saved task result explains the cache invalidation change.',
            occurrence: 0, resolved: false, pending: false, providerLane: 'codex', version: 1, createdAt: RESULT.occurredAt, updatedAt: RESULT.occurredAt,
            comment: null, replyDraft: null, replyDraftError: null, messages: [
              { id: 'question', author: 'USER', body: String(args[2]), createdAt: RESULT.occurredAt, error: null },
              { id: 'answer', author: 'AGENT', body: 'The focused checks cover invalidation after a write.', createdAt: RESULT.occurredAt, error: null },
            ] };
          return discussion;
        },
        replyFromAssistant: async () => { throw new Error('Reading a result must not execute a task turn'); },
      } as Bridge;
      try {
        flushSync(() => fixture.root.render(<DurableReportCard report={report} text="Cache invalidation updated" onOpen={() => {}} />));
        button('Ask about this', fixture.host).click(); await settle();
        changeInput(element<HTMLTextAreaElement>('textarea', fixture.host), 'What do the checks cover?'); await settle();
        button('Ask', fixture.host).click(); await settle();
        assert(asked.length === 1 && JSON.stringify(asked[0]) === JSON.stringify([report.target, report.sourceTurnId, 'What do the checks cover?']),
          'Ask sends the immutable result identity and question, without sending a generated quote or task input');
        assert(fixture.host.textContent?.includes('The focused checks cover invalidation after a write.'), 'The private answer appears directly under the result');
        assert(fixture.host.querySelector('.agt-disc-card__source')?.textContent?.includes('The saved task result'), 'The retained source passage is inspectable');
        assert(!fixture.host.querySelector('.assistant-result-question'), 'An existing discussion takes further questions');
      }
      finally { fixture.dispose(); window.bridge = previous; }
    }],
    ['shows pending interpretation passively and keeps its source openable', async () => {
      const report = { ...quietReport(), state: 'PROGRESS' as const, assessment: null };
      const card = render({ ...RESULT, id: 'finished:source-turn', kind: 'TURN_FINISHED', message: 'Reviewing the result in the background', delivery: 'PASSIVE', deliveryReason: report.deliveryReason, report }, true);
      try {
        assert(card.host.querySelector('[data-state="PROGRESS"]') !== null, 'Pending interpretation is Progress rather than a stale ready result');
        assert(card.host.textContent?.includes('Reviewing the result') && !card.host.textContent.includes('Ask about this'), 'Pending summary says what is happening and does not ask about an unfinished assessment');
        button('Open work', card.host).click(); await settle();
        assert(card.reviewed() === 1, 'The underlying result remains accessible during background interpretation');
      }
      finally { card.dispose(); }
    }],
    ['moves a rule up or keeps it observing from its card, and says why a move was refused', async () => {
      const card = render({ ...MOVE_UP, delivery: 'PASSIVE' }, true);
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
    ['shows held review replies under their comments, posts them now or keeps them back, and turns stale', async () => {
      let held = HELD;
      const previous = window.bridge;
      window.bridge = { ...previous, ...heldBridge(() => held, calls) } as Bridge;
      const card = render(REPLIES);
      try {
        await settle();
        const text = card.host.textContent ?? '';
        assert(calls.at(-1) === 'read task-2 ws1' && text.includes('Replies ready') && text.includes('carol: Use a map here?')
          && text.includes('A list keeps the order the API promises') && text.includes('Posting at'), `Each reply shows under the comment it answers: ${text}`);
        assert(card.host.querySelector('a.assistant-replies__comment')?.getAttribute('href') === HELD[0].url, 'A comment links to its thread');
        button('Post now', card.host).click();
        await settle();
        assert(calls.at(-1) === 'post task-2' && card.cleared() === 1, `Post now posts the task's replies and clears the card: ${calls.join(' | ')}`);
        button('Don’t post', card.host).click();
        await settle();
        assert(calls.at(-1) === 'keep back task-2' && card.cleared() === 2, `Don't post keeps them back and clears the card: ${calls.join(' | ')}`);
      }
      finally { card.dispose(); }
      const countdown = render({ ...REPLIES, id: `${REPLIES.id}:last`, expiresAt: new Date(Date.now() + 240_000).toISOString(),
        message: '2 review replies post when the timer ends.' });
      try {
        await settle();
        const text = countdown.host.textContent ?? '';
        assert(text.includes('Posting replies · 4 min left') && !text.includes('Posting at'), `The second alert counts down: ${text}`);
      }
      finally { countdown.dispose(); }
      held = [];
      const stale = render(REPLIES);
      try {
        await settle();
        const text = stale.host.textContent ?? '';
        assert(text.includes('Replies no longer held') && !text.includes('Post now'), `A card whose replies went out offers no post: ${text}`);
      }
      finally {
        stale.dispose();
        window.bridge = previous;
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


function quietReport(): AssistantReportDto {
  return { state: 'RESULT_READY', delivery: 'PASSIVE', deliveryReason: 'The requested checks passed. No decision is needed.',
    noticeId: 'finished:source-turn', target: { kind: 'TASK', id: 'task-1', workspaceId: 'ws1', repo: 'owner/repository', number: null, connectionId: null, cloudId: null },
    sourceTurnId: 'source-turn', assessment: 'The cache now invalidates after a write. Focused checks passed; the changes are ready for review.', outcome: null,
    evidence: ['Focused cache checks passed', 'Three files changed'], ruleActions: [{ ruleId: 'build-access', ruleName: 'Build access', ruleVersion: 2,
      outcome: 'ALLOWED', detail: 'Read dependencies from packages.example.test' }] };
}

let preview: { dispose: () => void } | null = null;
export async function showQuietPreview(): Promise<void> {
  preview?.dispose();
  const fixture = createFixtureRoot();
  preview = fixture;
  document.body.style.background = 'var(--bg)';
  fixture.host.className = 'assistant-home';
  fixture.host.style.cssText = 'padding:32px;max-width:640px;margin:auto';
  flushSync(() => fixture.root.render(<div className="assistant-work-report">
    <p style={{ color: 'var(--fg-muted)' }}>Assistant · Results</p>
    <DurableReportCard report={quietReport()} text="Cache invalidation updated" onOpen={() => {}} />
    <DurableReportCard report={{ ...quietReport(), state: 'PROGRESS', assessment: 'Reviewing the next result in the background. You can keep chatting.' }} text="Reviewing a pull request" onOpen={() => {}} />
  </div>));
  await settle();
}

export async function showRepliesPreview(): Promise<void> {
  preview?.dispose();
  window.bridge = { ...window.bridge, ...heldBridge(() => HELD, []) } as Bridge;
  const fixture = createFixtureRoot();
  preview = fixture;
  document.body.style.background = 'var(--bg)';
  fixture.host.className = 'assistant-home';
  fixture.host.style.cssText = 'padding:32px;max-width:640px;margin:auto';
  flushSync(() => fixture.root.render(<div className="assistant-work-report">
    <p style={{ color: 'var(--fg-muted)' }}>Assistant · Decision</p>
    <NoticeCard notice={REPLIES} focused={false} onOpen={() => {}} onClear={() => {}} />
    <NoticeCard notice={{ ...REPLIES, id: `${REPLIES.id}:last`, expiresAt: new Date(Date.now() + 240_000).toISOString(), message: '2 review replies post when the timer ends.' }}
      focused={false} onOpen={() => {}} onClear={() => {}} />
  </div>));
  await settle();
}
