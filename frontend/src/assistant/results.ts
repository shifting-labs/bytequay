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
import { RULE_NOTICE, RULE_RESULT_NOTICE, type AppNotice } from '../notices/contracts';
import { flag, list, object, oneOf, optionalCount, optionalText, text } from '../pulls/wireParse';

/*
 * What a result card decides from: the backend's digest of its pull request, its review and the agent's own report, then the card's one
 * recommended action and where it sits in Results. Every chip and line comes from here, so a card never shows a fact ByteQuay lacks.
 */

const CHECK_STATES = ['failing', 'pending', 'successful', 'neutral', 'skipped', 'cancelled'] as const;
export type CheckState = typeof CHECK_STATES[number];
export type DigestCheck = { name: string; state: CheckState; url: string | null };
export type DigestPull = {
  number: number; title: string; url: string; state: 'open' | 'closed' | 'merged'; draft: boolean; mine: boolean; author: string;
  head: string | null; base: string | null; additions: number; deletions: number; files: number; checks: DigestCheck[];
  approvers: string[]; youApproved: boolean; mergeBlocked: boolean; mergeable: boolean | null;
};
export type DigestCommand = { command: string; exitCode: number | null };
export type DigestFinding = {
  id: string; label: string; title: string; body: string; severity: string | null; path: string | null; line: number | null; outdated: boolean;
  version: number; revision: number;
  /** The command the finding cites as proof, with its exit code, when the reviewer ran one. */
  ran: string | null;
};
export type DigestReview = {
  reviewedHead: string; currentHead: string | null; filesRead: number; filesChanged: number; complete: boolean; omissions: string[]; warnings: string[];
  commands: DigestCommand[];
  /** Open at the reviewed revision, the most severe first. */
  findings: DigestFinding[];
  /** The development task that wrote the pull request, which takes findings to fix. */
  fixTask: string | null;
};
export type ResultDigest = { noticeId: string; host: 'GitHub' | 'GitLab'; pull: DigestPull | null; review: DigestReview | null; report: string | null };

const strings = (value: unknown): string[] => list(value).map(item => text(item));
const integer = (value: unknown): number | null => typeof value === 'number' && Number.isInteger(value) ? value : null;

function parsePull(value: unknown): DigestPull | null {
  if (value === null || value === undefined) return null;
  const pull = object(value, 'pull request');
  return {
    number: optionalCount(pull.number) ?? 0, title: text(pull.title), url: text(pull.url), state: oneOf(pull.state, ['open', 'closed', 'merged'] as const, 'state'),
    draft: flag(pull.draft), mine: flag(pull.mine), author: text(pull.author), head: optionalText(pull.head), base: optionalText(pull.base),
    additions: integer(pull.additions) ?? 0, deletions: integer(pull.deletions) ?? 0, files: integer(pull.files) ?? 0,
    checks: list(pull.checks).map(item => {
      const check = object(item, 'check');
      return { name: text(check.name), state: oneOf(check.state, CHECK_STATES, 'check state'), url: optionalText(check.url) };
    }),
    approvers: strings(pull.approvers), youApproved: flag(pull.youApproved), mergeBlocked: flag(pull.mergeBlocked),
    mergeable: typeof pull.mergeable === 'boolean' ? pull.mergeable : null,
  };
}

function parseReview(value: unknown): DigestReview | null {
  if (value === null || value === undefined) return null;
  const review = object(value, 'review');
  return {
    reviewedHead: text(review.reviewedHead), currentHead: optionalText(review.currentHead), filesRead: integer(review.filesRead) ?? 0,
    filesChanged: integer(review.filesChanged) ?? 0, complete: review.complete !== false, omissions: strings(review.omissions), warnings: strings(review.warnings),
    commands: list(review.commands).map(item => {
      const command = object(item, 'command');
      return { command: text(command.command), exitCode: integer(command.exitCode) };
    }),
    findings: list(review.findings).map(item => {
      const finding = object(item, 'finding');
      return {
        id: text(finding.id), label: text(finding.label), title: text(finding.title), body: text(finding.body), severity: optionalText(finding.severity),
        path: optionalText(finding.path), line: integer(finding.line), outdated: flag(finding.outdated), version: integer(finding.version) ?? 0,
        revision: integer(finding.revision) ?? 0, ran: optionalText(finding.ran),
      };
    }),
    fixTask: optionalText(review.fixTask),
  };
}

export function parseDigests(value: unknown): ResultDigest[] {
  return list(value, 'digests').map(item => {
    const digest = object(item, 'digest');
    return {
      noticeId: text(digest.noticeId), host: digest.host === 'GitLab' ? 'GitLab' : 'GitHub', pull: parsePull(digest.pull), review: parseReview(digest.review),
      report: optionalText(digest.report),
    };
  });
}

/** Questions, approvals and the notices that need the user live in Decision; everything else is a result. A card counts in one pill. */
export function inDecision(notice: AppNotice): boolean {
  if (notice.id.startsWith(RULE_NOTICE)) return true;
  if (notice.id.startsWith(RULE_RESULT_NOTICE)) return false;
  return notice.kind === 'QUESTION' || notice.kind === 'APPROVAL' || notice.kind === 'STUCK' || notice.kind === 'RECOVERY' || notice.kind === 'QUEUE_PAUSED'
    || notice.kind === 'CI_ATTENTION';
}

/** What the backend needs to digest a result: only the facts the notice holds; one with nothing to read is not asked about. */
export type DigestRequest = { noticeId: string; workspaceId: string; reviewId?: string; pullRequest?: string; turnId?: string };

export function digestRequest(notice: AppNotice): DigestRequest | null {
  if (inDecision(notice) || notice.taskId.startsWith('rule:')) return null;
  const request: DigestRequest = { noticeId: notice.id, workspaceId: notice.workspaceId };
  if (notice.reviewId !== null) request.reviewId = notice.reviewId;
  if (notice.pullRequest !== null) request.pullRequest = notice.pullRequest;
  if (notice.turnId !== null && notice.kind === 'TURN_FINISHED') request.turnId = notice.turnId;
  return request.reviewId === undefined && request.pullRequest === undefined && request.turnId === undefined ? null : request;
}

export type CheckCounts = { passed: number; failed: number; running: number; total: number };

export function checkCounts(checks: DigestCheck[]): CheckCounts {
  const failed = checks.filter(check => check.state === 'failing' || check.state === 'cancelled').length;
  const running = checks.filter(check => check.state === 'pending').length;
  return { passed: checks.length - failed - running, failed, running, total: checks.length };
}

export const SEVERITIES = ['critical', 'high', 'medium', 'low'] as const;

/** Open findings by severity, most severe first, zeros left out. */
export function severityCounts(findings: DigestFinding[]): { severity: string; count: number }[] {
  return SEVERITIES.map(severity => ({ severity, count: findings.filter(finding => finding.severity === severity).length })).filter(entry => entry.count > 0);
}

/** The findings a card shows: once the head moved they apply to code nobody reviewed, so only an up-to-date review shows them. */
export const openFindings = (review: DigestReview): DigestFinding[] => review.findings.filter(finding => !finding.outdated);

export const headMoved = (review: DigestReview): boolean => review.currentHead !== null && review.currentHead !== review.reviewedHead;

/** The card's one recommended action, by the state alone; null when nothing is one click away. */
export type ActionId = 'approve' | 'merge' | 'post-findings' | 'fix-findings' | 'open-review' | 're-review' | 'mark-ready' | 'fix-ci' | 'push' | 'close' | 'retry'
  | 'open-task';

function mergeReady(pull: DigestPull): boolean {
  const checks = checkCounts(pull.checks);
  return pull.state === 'open' && !pull.draft && checks.failed === 0 && checks.running === 0 && pull.approvers.length > 0 && !pull.mergeBlocked && pull.mergeable !== false;
}

/** A failed turn leaves its task open for another; a failed task, whose notice is keyed by the task, takes none. */
export const taskEnded = (notice: AppNotice): boolean => notice.id === `failed:${notice.taskId}`;

export function recommend(notice: AppNotice, digest: ResultDigest | undefined): ActionId | null {
  if (notice.id.startsWith(RULE_RESULT_NOTICE) || notice.taskId.startsWith('rule:')) return null;
  if (notice.kind === 'FAILURE') return notice.reviewId !== null ? 're-review' : taskEnded(notice) ? 'open-task' : 'retry';
  if (notice.kind !== 'TURN_FINISHED') return null;
  const pull = digest?.pull ?? null;
  if (notice.reviewId !== null) {
    const review = digest?.review ?? null;
    if (review === null || pull !== null && pull.state !== 'open') return null;
    if (headMoved(review)) return 're-review';
    if (openFindings(review).length > 0) return review.fixTask !== null ? 'fix-findings' : pull?.mine === true ? 'open-review' : 'post-findings';
    if (pull === null) return null;
    if (!pull.mine) return pull.youApproved || pull.head !== null && pull.head !== review.reviewedHead ? null : 'approve';
    return mergeReady(pull) ? 'merge' : null;
  }
  if (notice.report?.offer === 'PUSH') return 'push';
  if (notice.report?.offer === 'CLOSE') return 'close';
  if (pull === null || pull.state !== 'open') return null;
  const checks = checkCounts(pull.checks);
  if (checks.failed > 0) return 'fix-ci';
  if (checks.running > 0) return null;
  if (pull.draft) return 'mark-ready';
  return mergeReady(pull) ? 'merge' : null;
}

/** Where a result sits in Results: findings to decide on, one click left, nothing to do yet, or done. */
export type Lane = 'decide' | 'next' | 'fyi' | 'done';

export function laneOf(notice: AppNotice, digest: ResultDigest | undefined): Lane {
  if (notice.id.startsWith(RULE_RESULT_NOTICE) || notice.kind === 'FAILURE') return 'next';
  if (notice.ruleRun?.outcome === 'MERGED') return 'done';
  if (notice.kind === 'NEXT_WORK' || notice.ruleRun?.outcome === 'QUEUED' || notice.report?.state === 'PROGRESS') return 'fyi';
  const pull = digest?.pull ?? null;
  if (pull !== null && pull.state !== 'open') return 'done';
  const review = digest?.review ?? null;
  if (review !== null && !headMoved(review) && openFindings(review).length > 0) return 'decide';
  return recommend(notice, digest) === null ? 'fyi' : 'next';
}

/**
 * A command's short name for its chip: the tool a runner such as `npx` or `pnpm exec` starts, else the program and its first plain word,
 * as `pnpm test --filter api` reads `pnpm test`.
 */
export function shortCommand(command: string): string {
  const words = command.replace(/^\s*cd\s+\S+\s*&&\s*/, '').split(/\s+/).filter(word => word !== '' && !/^[A-Z_][A-Z0-9_]*=/.test(word));
  const base = (word: string) => word.slice(word.lastIndexOf('/') + 1);
  const runner = ['npx', 'bunx'].includes(words[0] ?? '') ? 1 : ['exec', 'dlx', 'run'].includes(words[1] ?? '') && ['pnpm', 'npm', 'yarn', 'uv', 'poetry', 'bundle'].includes(words[0] ?? '') ? 2
    : words[0] === 'python' && words[1] === '-m' ? 2 : 0;
  if (runner > 0 && words[runner] !== undefined) return base(words[runner]);
  const second = words[1];
  return second !== undefined && /^[a-z][\w:-]*$/i.test(second) && second.length <= 20 ? `${base(words[0])} ${second}` : base(words[0] ?? command);
}

/** The agent's own outcome, shortened to its first sentence for the verdict; the rest stays in its full report. */
export function firstSentence(report: string, limit = 160): string {
  const line = report.split('\n').map(each => each.replace(/^\s*(?:[-*+]|#+|\d+\.)\s+/, '').trim()).find(each => each !== '') ?? '';
  const sentence = /^.*?[.!?](?=\s|$)/.exec(line)?.[0] ?? line;
  return sentence.length <= limit ? sentence : `${sentence.slice(0, limit - 1).trimEnd()}…`;
}

/** At most this many sentences of a text, as a finding's box shows its explanation; the whole text stays in the review. */
export function sentences(text: string, count: number): string {
  const flat = text.replace(/\s+/g, ' ').trim();
  const ends = [...flat.matchAll(/[.!?](?=\s|$)/g)];
  return ends.length <= count ? flat : flat.slice(0, (ends[count - 1].index ?? flat.length - 1) + 1);
}

/** Where a finding is, short: the file's name and line, since a card has no room for the whole path; the tooltip has it. */
export function findingPlace(finding: Pick<DigestFinding, 'path' | 'line'>): { short: string; full: string } | null {
  if (finding.path === null) return null;
  const line = finding.line === null ? '' : `:${finding.line}`;
  return { short: `${finding.path.slice(finding.path.lastIndexOf('/') + 1)}${line}`, full: `${finding.path}${line}` };
}

/** One part of a reviewer's walkthrough, shown as a card of its own: a labelled section such as "Input" or "Fix", or a numbered step. */
export type WalkSection = { title: string; detail: string | null; body: string };

/**
 * Splits a walkthrough's Markdown where the reviewer marked its parts: a line opening with a bold label ("**Fix:** …") or a numbered
 * step ("1. `path:line`"). A step's lines lose the list's indent, so its code renders as code; nothing inside a code fence splits.
 * A walkthrough with fewer than two parts stays whole.
 */
export function walkthroughSections(markdown: string): WalkSection[] {
  const sections: WalkSection[] = [];
  let current: WalkSection | null = null;
  let fence: string | null = null;
  let indent = 0;
  const add = (line: string) => {
    if (current === null) {
      current = { title: 'Context', detail: null, body: '' };
      sections.push(current);
    }
    current.body = current.body === '' ? line : `${current.body}\n${line}`;
  };
  for (const raw of markdown.split('\n')) {
    const marker = raw.trimStart().slice(0, 3);
    if (fence === null && (marker === '```' || marker === '~~~')) fence = marker;
    else if (fence !== null && marker === fence) fence = null;
    else if (fence === null) {
      const label = /^\*\*([^*]+?):?\*\*:?\s*(.*)$/.exec(raw);
      const step = /^(\d+)[.)]\s+(.*)$/.exec(raw);
      if (label !== null) {
        current = { title: label[1].replace(/:\s*$/, '').replaceAll('`', ''), detail: null, body: '' };
        sections.push(current);
        indent = 0;
        if (label[2] !== '') add(label[2]);
        continue;
      }
      if (step !== null) {
        // A step that opens with where it is, such as `path:line`, names it in its header; any other words stay in its body.
        const place = /^`([^`]+)`$/.exec(step[2].trim());
        current = { title: `Step ${step[1]}`, detail: place?.[1] ?? null, body: '' };
        sections.push(current);
        indent = raw.length - step[2].length;
        if (place === null) add(step[2]);
        continue;
      }
    }
    const lead = raw.length - raw.trimStart().length;
    add(raw.slice(Math.min(lead, indent)));
  }
  return sections.length < 2 ? [] : sections.map(section => ({ ...section, body: section.body.trim() }));
}

/** What a card's Ask opens with: two or three questions drawn from the card itself. */
export function askSuggestions(digest: ResultDigest | undefined): string[] {
  const review = digest?.review ?? null;
  if (review === null) return [];
  const open = openFindings(review);
  const ran = review.commands.length > 0 || review.findings.some(finding => finding.ran !== null) ? 'What did the reviewer run?' : 'What did the reviewer check?';
  if (open.length === 0) return ['Is it safe to merge?', ran, ...review.complete ? [] : ['What did it skip?']];
  const top = open[0];
  const other = open[1];
  return [
    top.severity === null ? `Why does ${top.label} matter?` : `Why is ${top.label} ${top.severity}?`,
    other === undefined ? `How would I fix ${top.label}?` : `Is it safe to merge without fixing ${other.label}?`,
    ran,
  ];
}
