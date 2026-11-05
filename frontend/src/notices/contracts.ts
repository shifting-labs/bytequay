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
import type { AgentRuleRunDto, AssistantReportDto } from '../types';
import { parseAgentRuleRuns } from '../agents/contracts';

export const NOTICE_KINDS = ['TURN_FINISHED', 'QUESTION', 'APPROVAL', 'FAILURE', 'RECOVERY', 'STUCK', 'QUEUE_PAUSED', 'CI_ATTENTION', 'NEXT_WORK'] as const;
export const NOTICE_WIDTH = 340;
export type NoticeKind = typeof NOTICE_KINDS[number];
export type AppNotice = {
  delivery: 'PASSIVE' | 'INTERRUPT';
  deliveryReason: string;
  report: AssistantReportDto | null;
  ruleRun?: AgentRuleRunDto | null;
  id: string; kind: NoticeKind; taskId: string; workspaceId: string; turnId: string | null; reviewId: string | null;
  title: string; pullRequest: string | null; pullRequestTitle: string | null; changes: NoticeChanges | null;
  message: string; repository: string; laneId: string; occurredAt: string;
  /** The reviewed pull request's head, or the development task's own branch. */
  branch?: string;
  /** The Epic work a NEXT_WORK notice suggests starting next, once a pull request of the Epic merged. */
  workItemId?: string;
  /** When the agent stops waiting for a pending approval or question card. */
  expiresAt?: string;
};
/** Latest review run counts; `update` marks a run that covers only changes since the prior review. */
export type NoticeChanges = { update: boolean; commits: number; files: number; additions: number; deletions: number };
export type NoticeSnapshot = { notices: AppNotice[]; popupIds: string[]; viewedIds: string[]; active: boolean; maxHeight: number };
export type NoticeBridge = {
  read: () => Promise<NoticeSnapshot>;
  subscribe: (listener: (state: NoticeSnapshot) => void) => () => void;
  /** `seen` when the user closed the alert, not when it timed out. */
  dismiss: (id: string, seen: boolean) => Promise<void>;
  open: (id: string) => Promise<void>;
  resize: (height: number) => void;
};
/** Approvals and AskUserQuestion cards; an alert's Answer opens the Assistant panel at the card, which has the task's own buttons. */
export const APPROVAL_NOTICE = 'approval:';
/** An Artifacts question that timed out; its `taskId` is the conversation's id, and opening it shows the conversation. */
export const ARTIFACT_NOTICE = 'artifact:';
/** A rule whose record lets it move up to Suggest; it belongs to no task, so its alert's Answer opens the panel at its card. */
export const RULE_NOTICE = 'rule:';
/** A rule's finished work, asked about in place of its task's own result; its id goes on with the rule's id and the run's key. */
export const RULE_RESULT_NOTICE = 'rule-result:';
export const interrupts = (notice: AppNotice): boolean => notice.delivery === 'INTERRUPT';
export const needsAction = (notice: AppNotice): boolean => notice.kind !== 'TURN_FINISHED' && notice.kind !== 'NEXT_WORK';
/**
 * A focused decision belongs in Decision and My work's marks. Serious failures remain under Results with their interruption policy.
 */
export const isDecision = (notice: AppNotice): boolean => interrupts(notice) && needsAction(notice) && notice.kind !== 'FAILURE';
/** A decision's label says what waits on the user, as its card's own state does, rather than naming the task's internal state. */
export const NOTICE_LABELS: Record<NoticeKind, string> = {
  TURN_FINISHED: 'Turn finished', QUESTION: 'Answer question', APPROVAL: 'Approval needed',
  FAILURE: 'Turn failed', RECOVERY: 'Check the task', STUCK: 'Looks stuck', QUEUE_PAUSED: 'Resume the queue', CI_ATTENTION: 'Check CI',
  NEXT_WORK: 'Next in Epic',
};
/** What opening a notice shows, as its button says, on its alert and in the Assistant panel alike. */
export const NOTICE_ACTIONS: Record<NoticeKind, string> = {
  TURN_FINISHED: 'View response', QUESTION: 'Reply', APPROVAL: 'Review request', FAILURE: 'View log', RECOVERY: 'Inspect task', STUCK: 'View task',
  QUEUE_PAUSED: 'View queue', CI_ATTENTION: 'View task', NEXT_WORK: 'Open work',
};
export const noticeLabel = (notice: AppNotice): string => notice.id.startsWith(RULE_NOTICE) ? 'Move up?'
  : notice.id.startsWith(RULE_RESULT_NOTICE) ? 'Check suggestion'
    : notice.reviewId === null ? NOTICE_LABELS[notice.kind]
      : notice.kind === 'TURN_FINISHED' ? 'Review finished' : notice.kind === 'FAILURE' ? 'Review failed' : NOTICE_LABELS[notice.kind];
/** What an alert's button says: a card answered in the panel says Answer; a rule's finished work opens. */
export const noticeAction = (notice: AppNotice): string => notice.id.startsWith(APPROVAL_NOTICE) || notice.id.startsWith(RULE_NOTICE) ? 'Answer'
  : notice.id.startsWith(RULE_RESULT_NOTICE) ? 'Open' : NOTICE_ACTIONS[notice.kind];
/** An unanswered approval's alert comes back once, this long before the agent stops waiting. */
export const APPROVAL_NUDGE_MS = 60_000;
/** An approval's time left as its card, the Decision pill and its alert say it; null once it has expired. */
export function approvalTimeLeft(expiresAt: string, now: number): { text: string; urgent: boolean } | null {
  const left = Date.parse(expiresAt) - now;
  if (!(left > 0)) return null;
  return { text: left < 60_000 ? `${Math.ceil(left / 1000)} s` : `${Math.round(left / 60_000)} min`, urgent: left < 120_000 };
}
const deadline = (notice: AppNotice): number => notice.expiresAt === undefined ? Number.POSITIVE_INFINITY : Date.parse(notice.expiresAt);
/**
 * The one alert drawn: an approval in its last minute first, soonest deadline first, since its nudge can come after newer alerts;
 * otherwise the newest.
 */
export function shownAlert(popups: AppNotice[], now: number): AppNotice | null {
  const closing = popups.filter(notice => deadline(notice) - now <= APPROVAL_NUDGE_MS);
  if (closing.length > 0) return closing.reduce((soonest, notice) => deadline(notice) < deadline(soonest) ? notice : soonest);
  return popups.reduce<AppNotice | null>((newest, notice) => newest === null || Date.parse(notice.occurredAt) > Date.parse(newest.occurredAt) ? notice : newest, null);
}
export function parseNotices(value: unknown): AppNotice[] {
  if (!Array.isArray(value)) throw new Error('Invalid notices');
  const ids = new Set<string>();
  return value.map(raw => {
    if (raw === null || typeof raw !== 'object') throw new Error('Invalid notice');
    const item = raw as Record<string, unknown>;
    for (const key of ['id', 'kind', 'title', 'message', 'occurredAt', 'taskId', 'workspaceId', 'repository', 'laneId', 'deliveryReason']) {
      if (typeof item[key] !== 'string' || !(item[key] as string).trim() || (item[key] as string).length > 1000) throw new Error('Invalid notice field');
    }
    if ((item.delivery !== 'PASSIVE' && item.delivery !== 'INTERRUPT') || !NOTICE_KINDS.includes(item.kind as NoticeKind) || !Number.isFinite(Date.parse(item.occurredAt as string))
      || ids.has(item.id as string)) throw new Error('Invalid notice identity');
    ids.add(item.id as string);
    for (const key of ['turnId', 'reviewId']) {
      if (item[key] !== null && (typeof item[key] !== 'string' || !(item[key] as string).trim() || (item[key] as string).length > 200)) throw new Error('Invalid notice identity');
    }
    for (const key of ['pullRequest', 'pullRequestTitle']) {
      if (item[key] !== null && (typeof item[key] !== 'string' || !(item[key] as string).trim() || (item[key] as string).length > 1000)) throw new Error('Invalid notice context');
    }
    const workItemId = item.workItemId ?? null;
    if (workItemId !== null && (typeof workItemId !== 'string' || !workItemId.trim() || workItemId.length > 200)) throw new Error('Invalid notice context');
    const branch = item.branch ?? null;
    if (branch !== null && (typeof branch !== 'string' || !branch.trim() || branch.length > 1000)) throw new Error('Invalid notice context');
    const expiresAt = item.expiresAt ?? null;
    if (expiresAt !== null && (typeof expiresAt !== 'string' || !Number.isFinite(Date.parse(expiresAt)))) throw new Error('Invalid notice context');
    return { delivery: item.delivery, deliveryReason: item.deliveryReason, report: parseReport(item.report), id: item.id, kind: item.kind, taskId: item.taskId, workspaceId: item.workspaceId, turnId: item.turnId, reviewId: item.reviewId,
      title: item.title, pullRequest: item.pullRequest, pullRequestTitle: item.pullRequestTitle, changes: parseChanges(item.changes),
      message: item.message, repository: item.repository, laneId: item.laneId, occurredAt: item.occurredAt,
      ...(item.ruleRun == null ? {} : { ruleRun: parseAgentRuleRuns([item.ruleRun])[0] }),
      ...(branch === null ? {} : { branch }), ...(workItemId === null ? {} : { workItemId }), ...(expiresAt === null ? {} : { expiresAt }) } as AppNotice;
  });
}
function parseChanges(raw: unknown): NoticeChanges | null {
  if (raw === null) return null;
  if (typeof raw !== 'object') throw new Error('Invalid notice context');
  const { update, commits, files, additions, deletions } = raw as Record<string, unknown>;
  const counts = [commits, files, additions, deletions];
  if (typeof update !== 'boolean' || counts.some(count => !Number.isSafeInteger(count) || (count as number) < 0)) throw new Error('Invalid notice context');
  return { update, commits, files, additions, deletions } as NoticeChanges;
}

declare global { interface Window { noticeBridge: NoticeBridge } }

function parseReport(value: unknown): AssistantReportDto | null {
  if (value === null) return null;
  if (value === undefined || typeof value !== 'object') throw new Error('Invalid notice report');
  const row = value as Record<string, unknown>;
  const states = ['APPROVAL_REQUIRED', 'ANSWER_REQUIRED', 'PROPOSAL', 'RESULT_READY', 'COMPLETED', 'ATTENTION', 'PROGRESS'];
  if (!states.includes(String(row.state)) || row.delivery !== 'PASSIVE' && row.delivery !== 'INTERRUPT'
    || typeof row.deliveryReason !== 'string' || !row.deliveryReason.trim()
    || typeof row.noticeId !== 'string' || !row.noticeId.trim()) throw new Error('Invalid notice report');
  for (const field of ['sourceTurnId', 'assessment', 'outcome']) {
    if (row[field] !== null && typeof row[field] !== 'string') throw new Error('Invalid notice report');
  }
  if (!Array.isArray(row.evidence) || row.evidence.some(entry => typeof entry !== 'string') || !Array.isArray(row.ruleActions)) throw new Error('Invalid notice report');
  for (const action of row.ruleActions) {
    if (action === null || typeof action !== 'object') throw new Error('Invalid notice rule receipt');
    const record = action as Record<string, unknown>;
    if (['ruleId', 'ruleName', 'outcome', 'detail'].some(field => typeof record[field] !== 'string')
      || !Number.isSafeInteger(record.ruleVersion) || Number(record.ruleVersion) < 1) throw new Error('Invalid notice rule receipt');
  }
  if (row.target === null || typeof row.target !== 'object') throw new Error('Invalid notice report target');
  const target = row.target as Record<string, unknown>;
  if (!['TASK', 'REVIEW', 'WORK', 'PULL', 'ISSUE', 'JIRA'].includes(String(target.kind))) throw new Error('Invalid notice report target');
  for (const field of ['id', 'workspaceId', 'repo', 'connectionId', 'cloudId']) {
    if (target[field] !== null && typeof target[field] !== 'string') throw new Error('Invalid notice report target');
  }
  if (target.number !== null && (!Number.isSafeInteger(target.number) || Number(target.number) < 1)) throw new Error('Invalid notice report target');
  return row as AssistantReportDto;
}
