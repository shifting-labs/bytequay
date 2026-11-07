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
import type { AppNotice } from '../notices/contracts';
import { waitingCards } from './model';
import {
  askSuggestions, digestRequest, findingPlace, firstSentence, inDecision, laneOf, parseDigests, recommend, sentences, severityCounts, shortCommand, walkthroughSections,
  type DigestPull, type DigestReview,
  type ResultDigest,
} from './results';

const notice = (fields: Partial<AppNotice> = {}): AppNotice => ({
  delivery: 'PASSIVE', deliveryReason: 'A result you have not seen.', report: null, id: 'finished:turn', kind: 'TURN_FINISHED', taskId: 'task', workspaceId: 'ws',
  turnId: 'turn', reviewId: null, title: 'Move sessions to Redis', pullRequest: 'owner/repo#62', pullRequestTitle: null, changes: null, message: 'Draft PR #62 · checks running',
  repository: 'owner/repo', laneId: 'claude', occurredAt: '2026-11-07T09:00:00Z', ...fields,
});

const pull = (fields: Partial<DigestPull> = {}): DigestPull => ({
  number: 62, title: 'Move sessions to Redis', url: 'https://example.com/owner/repo/pull/62', state: 'open', draft: false, mine: false, author: 'dev-sam', head: 'head',
  base: 'main', additions: 412, deletions: 198, files: 11, checks: [], approvers: [], youApproved: false, mergeBlocked: false, mergeable: true, ...fields,
});

const review = (fields: Partial<DigestReview> = {}): DigestReview => ({
  reviewedHead: 'head', currentHead: 'head', filesRead: 14, filesChanged: 14, complete: true, omissions: [], warnings: [], commands: [], findings: [], fixTask: null,
  ...fields,
});

const finding = (id: string, severity: string | null, fields: Partial<DigestReview['findings'][number]> = {}) => ({
  id, label: `F-${id}`, title: `Finding ${id}`, body: 'Why it matters.', severity, path: 'src/a.ts', line: 3, outdated: false, version: 1, revision: 1, ran: null, ...fields,
});

const digest = (fields: Partial<ResultDigest> = {}): ResultDigest => ({ noticeId: 'finished:turn', host: 'GitHub', pull: null, review: null, report: null, ...fields });
const reviewNotice = notice({ id: 'finished:review', reviewId: 'r1', message: '2 open findings' });

describe('Decision or Results', () => {
  it('keeps questions, approvals and the notices that need the user in Decision, whatever their delivery, and a rule’s finished work in Results', () => {
    expect(inDecision(notice({ id: 'approval:a', kind: 'QUESTION' }))).toBe(true);
    expect(inDecision(notice({ id: 'stuck:t:1', kind: 'STUCK' }))).toBe(true);
    expect(inDecision(notice({ id: 'rule:r1:1', kind: 'QUESTION', taskId: 'rule:r1' }))).toBe(true);
    expect(inDecision(notice({ id: 'rule-result:r1:pr:1', kind: 'QUESTION' }))).toBe(false);
    expect(inDecision(notice({ kind: 'FAILURE', id: 'failed:turn' }))).toBe(false);
    const cards = waitingCards([notice({ id: 'question:q', kind: 'QUESTION', taskId: 'q' }), notice()], [], new Map([['finished:turn', digest({ pull: pull({ draft: true }) })]]));
    expect(cards.map(card => [card.key, card.group, card.group === 'finished' ? card.lane : null]))
      .toEqual([['question:q', 'decision', null], ['finished:turn', 'finished', 'next']]);
  });

  it('asks the backend only about results with something to read, naming only the facts the notice holds', () => {
    expect(digestRequest(notice())).toEqual({ noticeId: 'finished:turn', workspaceId: 'ws', pullRequest: 'owner/repo#62', turnId: 'turn' });
    expect(digestRequest(reviewNotice)).toEqual({ noticeId: 'finished:review', workspaceId: 'ws', reviewId: 'r1', pullRequest: 'owner/repo#62', turnId: 'turn' });
    expect(digestRequest(notice({ kind: 'FAILURE', id: 'failed:turn', pullRequest: null }))).toBeNull();
    expect(digestRequest(notice({ id: 'approval:a', kind: 'APPROVAL' }))).toBeNull();
    expect(digestRequest(notice({ id: 'rule-action:r:pr:1:MERGED', taskId: 'rule:r' }))).toBeNull();
  });
});

describe('the one recommended action and where a result sits', () => {
  it('offers Approve on someone else’s clean review at the head it saw, Merge on the user’s own ready one, and nothing once approved', () => {
    expect(recommend(reviewNotice, digest({ review: review(), pull: pull() }))).toBe('approve');
    expect(laneOf(reviewNotice, digest({ review: review(), pull: pull() }))).toBe('next');
    expect(recommend(reviewNotice, digest({ review: review(), pull: pull({ head: 'newer' }) }))).toBeNull();
    expect(recommend(reviewNotice, digest({ review: review(), pull: pull({ youApproved: true }) }))).toBeNull();
    expect(laneOf(reviewNotice, digest({ review: review(), pull: pull({ youApproved: true }) }))).toBe('fyi');
    const ready = pull({ mine: true, approvers: ['lee'], checks: [{ name: 'test', state: 'successful', url: null }] });
    expect(recommend(reviewNotice, digest({ review: review(), pull: ready }))).toBe('merge');
    expect(recommend(reviewNotice, digest({ review: review(), pull: { ...ready, approvers: [] } }))).toBeNull();
  });

  it('puts findings under Decide: Post them on someone else’s pull request, Fix them on the user’s task, Open review on their own without one', () => {
    const findings = review({ findings: [finding('1', 'high'), finding('2', 'medium')] });
    expect(laneOf(reviewNotice, digest({ review: findings, pull: pull() }))).toBe('decide');
    expect(recommend(reviewNotice, digest({ review: findings, pull: pull() }))).toBe('post-findings');
    expect(recommend(reviewNotice, digest({ review: { ...findings, fixTask: 'dev' }, pull: pull({ mine: true }) }))).toBe('fix-findings');
    expect(recommend(reviewNotice, digest({ review: findings, pull: pull({ mine: true }) }))).toBe('open-review');
    // Once the head moved, the findings apply to code nobody reviewed: only Re-review is offered.
    const moved = digest({ review: { ...findings, currentHead: 'newer' }, pull: pull() });
    expect([recommend(reviewNotice, moved), laneOf(reviewNotice, moved)]).toEqual(['re-review', 'next']);
  });

  it('recommends Mark ready, Merge, a CI fix or a push from a development result’s state, and folds merged work into Done', () => {
    const passing = [{ name: 'test', state: 'successful' as const, url: null }];
    expect(recommend(notice(), digest({ pull: pull({ draft: true, checks: passing }) }))).toBe('mark-ready');
    expect(recommend(notice(), digest({ pull: pull({ checks: passing, approvers: ['lee'] }) }))).toBe('merge');
    expect(recommend(notice(), digest({ pull: pull({ draft: true, checks: [...passing, { name: 'lint', state: 'failing', url: null }] }) }))).toBe('fix-ci');
    const running = digest({ pull: pull({ draft: true, checks: [...passing, { name: 'lint', state: 'pending', url: null }] }) });
    expect([recommend(notice(), running), laneOf(notice(), running)]).toEqual([null, 'fyi']);
    const offered = notice({ report: { delivery: 'PASSIVE', deliveryReason: '', state: 'RESULT_READY', noticeId: 'finished:turn', target: { kind: 'TASK', id: 'task',
      workspaceId: 'ws', repo: null, number: null, connectionId: null, cloudId: null }, sourceTurnId: 'turn', assessment: null, outcome: null, evidence: [], ruleActions: [],
      offer: 'PUSH' } });
    expect(recommend(offered, digest())).toBe('push');
    expect(laneOf(notice(), digest({ pull: pull({ state: 'merged' }) }))).toBe('done');
  });

  it('retries a failed turn, re-reviews a failed review, and only opens a failed task, which takes no new turns', () => {
    expect(recommend(notice({ kind: 'FAILURE', id: 'failed:turn' }), undefined)).toBe('retry');
    expect(recommend(notice({ kind: 'FAILURE', id: 'failed:task' }), undefined)).toBe('open-task');
    expect(recommend(notice({ kind: 'FAILURE', id: 'review-stopped:run', reviewId: 'r1' }), undefined)).toBe('re-review');
    expect(laneOf(notice({ kind: 'FAILURE', id: 'failed:turn' }), undefined)).toBe('next');
  });
});

describe('what a card says', () => {
  it('names a command by the program and its first word, or the tool a runner starts', () => {
    expect(shortCommand('pnpm test --filter api')).toBe('pnpm test');
    expect(shortCommand('pnpm exec tsc --noEmit -p api')).toBe('tsc');
    expect(shortCommand('cd backend && mvn -q verify')).toBe('mvn');
    expect(shortCommand('npx vitest run refresh.spec.ts')).toBe('vitest');
    expect(shortCommand('CI=1 ./node_modules/.bin/eslint src')).toBe('eslint src');
    expect(shortCommand('python -m pytest tests')).toBe('pytest');
  });

  it('shortens the agent’s own report to its first sentence', () => {
    expect(firstSentence('Moved session storage to Redis and removed the in-memory store. Tests cover expiry.')).toBe('Moved session storage to Redis and removed the in-memory store.');
    expect(firstSentence('- Added retry with backoff.\n- Kept the old path.')).toBe('Added retry with backoff.');
    expect(firstSentence('x'.repeat(200), 20)).toBe(`${'x'.repeat(19)}…`);
  });

  it('counts open findings by severity, most severe first, and draws Ask’s questions from the card', () => {
    const findings = review({ findings: [finding('2', 'medium'), finding('1', 'high'), finding('3', 'medium', { outdated: true })], commands: [{ command: 'pnpm test', exitCode: 0 }] });
    expect(severityCounts(findings.findings)).toEqual([{ severity: 'high', count: 1 }, { severity: 'medium', count: 2 }]);
    expect(askSuggestions(digest({ review: findings }))).toEqual(['Why is F-2 medium?', 'Is it safe to merge without fixing F-1?', 'What did the reviewer run?']);
    expect(askSuggestions(digest({ review: review({ complete: false }) }))).toEqual(['Is it safe to merge?', 'What did the reviewer check?', 'What did it skip?']);
    expect(askSuggestions(digest())).toEqual([]);
  });

  it('parses the backend’s digest and refuses one that is malformed', () => {
    const [read] = parseDigests([{ noticeId: 'n', host: 'GitLab', report: null, review: null, pull: { ...pull(), checks: [{ name: 'test', state: 'failing', url: null }] } }]);
    expect(read.host).toBe('GitLab');
    expect(read.pull?.checks).toEqual([{ name: 'test', state: 'failing', url: null }]);
    expect(() => parseDigests([{ noticeId: 'n', host: 'GitHub', pull: { ...pull(), state: 'opened' } }])).toThrow();
  });
});

describe('a finding as a card shows it', () => {
  it('names where it is by the file and line, and says at most two sentences', () => {
    expect(findingPlace({ path: 'core/engine/src/main/java/io/example/Analyzer.java', line: 2403 })).toEqual({ short: 'Analyzer.java:2403',
      full: 'core/engine/src/main/java/io/example/Analyzer.java:2403' });
    expect(findingPlace({ path: null, line: null })).toBeNull();
    expect(sentences('Update `view.md` first. Then the docs. Then the guide.', 2)).toBe('Update `view.md` first. Then the docs.');
    expect(sentences('One sentence about `iceberg.refresh-period`.', 2)).toBe('One sentence about `iceberg.refresh-period`.');
  });

  it('splits a walkthrough into its labelled parts and numbered steps, keeping each step’s code as code', () => {
    const walkthrough = [
      '**Input:** a view created with `GRACE PERIOD 0`. Query: `SELECT x FROM mv`.',
      '',
      '1. `core/src/Analyzer.java:2403-2405`',
      '   ```java',
      '   boolean versioned = period.isPresent();',
      '   **not a label** inside code',
      '   2. not a step either',
      '   ```',
      '   The query has a period, so `versioned` is true.',
      '2. The second step says its words first.',
      '',
      '**Expected, per `docs/view.md:61`:** queries on a stale view fail.',
      '**Actual (executed at head):** the versioned query returns stale rows.',
      '',
      '**Fix:** say so in the docs.',
    ].join('\n');
    const sections = walkthroughSections(walkthrough);
    expect(sections.map(section => [section.title, section.detail])).toEqual([['Input', null], ['Step 1', 'core/src/Analyzer.java:2403-2405'], ['Step 2', null],
      ['Expected, per docs/view.md:61', null], ['Actual (executed at head)', null], ['Fix', null]]);
    expect(sections[1].body).toBe('```java\nboolean versioned = period.isPresent();\n**not a label** inside code\n2. not a step either\n```\nThe query has a period, so `versioned` is true.');
    expect(sections[2].body).toBe('The second step says its words first.');
    expect(sections[0].body).toBe('a view created with `GRACE PERIOD 0`. Query: `SELECT x FROM mv`.');
  });

  it('leaves a walkthrough with no marked parts whole, and gathers what comes before the first part as context', () => {
    expect(walkthroughSections('Just one paragraph.\n\n```ts\ncode()\n```')).toEqual([]);
    expect(walkthroughSections('Some setup first.\n\n**Fix:** do it.').map(section => section.title)).toEqual(['Context', 'Fix']);
  });
});
