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
import { useEffect, useState, type ReactNode } from 'react';
import {
  Check, ChevronDown, ChevronRight, CircleCheck, CircleX, Eye, EyeOff, FlaskConical, GitCommitHorizontal, GitMerge, GitPullRequest, GitPullRequestArrow, GitPullRequestClosed,
  GitPullRequestDraft, History, ListTodo, LoaderCircle, MessageCircleQuestion, MessageSquare, MessageSquareReply, RotateCw, ShieldCheck, ShieldX, Terminal,
  TriangleAlert, Wrench, X, type LucideIcon,
} from 'lucide-react';
import { renderMarkdown } from '../agents/markdown';
import { requestTaskFix } from '../agents/taskFix';
import { RULE_RESULT_NOTICE, type AppNotice } from '../notices/contracts';
import { routedPullRequestBridge } from '../pulls/PullRequestWorkspace';
import type { AgentReviewDto, ReviewFindingDto } from '../pulls/agent-review/contracts';
import { findingReviewText } from '../pulls/agent-review/model';
import { recordReviewAttentionAction, useFindingActions } from '../pulls/agent-review/useFindingActions';
import { findingFixPrompt } from '../pulls/local-review/conversation';
import { relativeTime } from '@bytequay/ui/relativeTime';
import { laneLabel } from '../settings/model';
import type { AgendaTargetDto, AssistantReportDto } from '../types';
import {
  ActionBar, Age, CardRow, Chip, EvidenceLine, OFFERED, ReceiptRow, ReplyBox, ReportEvidence, Settled, clearCard, sendToTask, settleCard, taskSource, useTaskCommits,
  type CardAction, type Strip, type Tone,
} from './Cards';
import { localDay, type WaitingCard } from './model';
import { approvePull, mergePull } from './pullActions';
import { ResultDiscussion } from './ResultDiscussion';
import {
  askSuggestions, checkCounts, findingPlace, firstSentence, headMoved, openFindings, recommend, sentences, severityCounts, shortCommand, taskEnded, type ActionId, type DigestCommand,
  type DigestFinding, type DigestPull, type DigestReview, type Lane, type ResultDigest,
} from './results';
import { settledToday, useSettled } from './settled';

/** Where a card's links go: an item's detail, the notice's own task or review page, or a web page in the app's browser. */
export type CardHost = {
  show: (target: AgendaTargetDto) => void;
  openNotice: (id: string) => void;
  openUrl: (url: string) => void;
};

const STRIPS: Record<Lane, Strip> = { decide: 'decide', next: 'next', fyi: 'fyi', done: 'fyi' };
const sha = (value: string | null): string => value === null ? 'unknown' : value.slice(0, 7);
const plural = (count: number, one: string, many = `${one}s`): string => `${count} ${count === 1 ? one : many}`;
const sentence = (text: string): string => /[.!?…]$/.test(text) ? text : `${text}.`;

const heading = (notice: AppNotice): string => notice.pullRequestTitle ?? notice.title;

const pullOf = (notice: AppNotice): { repo: string; number: number } | null => {
  const at = notice.pullRequest?.lastIndexOf('#') ?? -1;
  return notice.pullRequest == null || at < 1 ? null : { repo: notice.pullRequest.slice(0, at), number: Number(notice.pullRequest.slice(at + 1)) };
};

const pullTarget = (notice: AppNotice): AgendaTargetDto | null => {
  const pull = pullOf(notice);
  return pull === null ? null : { kind: 'PULL', repo: pull.repo, number: pull.number, workspaceId: notice.workspaceId, id: null, connectionId: null, cloudId: null };
};

/** A pull request's name as a card's source and receipts say it. */
const pullName = (notice: AppNotice, pull: DigestPull | null): string => {
  const number = pull?.number ?? pullOf(notice)?.number;
  const title = pull?.title ?? notice.pullRequestTitle ?? '';
  return number === undefined ? notice.title : `PR #${number}${title === '' ? '' : ` ${title}`}`;
};

/** The rule that started the work and what it allowed, so a card the rule raised says so. */
function RuleReceipt({ notice }: { notice: AppNotice }) {
  const run = notice.ruleRun;
  if (run?.authorization == null) return null;
  return <ReportEvidence report={{ ruleActions: [{ ruleId: run.authorization.ruleId, ruleName: run.authorization.ruleName, ruleVersion: run.authorization.ruleVersion,
    outcome: run.outcome ?? 'UNKNOWN', detail: `${run.subject} · ${run.title}` }] }} />;
}

/** A news card of Results, with its digest's facts, collapsing to its receipt once acted on. */
export function ResultCard({ notice, lane, digest, host }: { notice: AppNotice; lane: Lane; digest?: ResultDigest; host: CardHost }) {
  const props = { notice, lane, digest, host };
  const card = notice.id.startsWith(RULE_RESULT_NOTICE) ? <RuleResultCard {...props} />
    : notice.ruleRun != null && notice.taskId.startsWith('rule:') ? <RuleActionCard {...props} />
      : notice.kind === 'FAILURE' ? <FailureCard {...props} />
        : notice.kind === 'NEXT_WORK' ? <NextWorkCard {...props} />
          : notice.reviewId !== null ? <ReviewResultCard {...props} />
            : <TaskResultCard {...props} />;
  return <Settled notice={notice}>{card}</Settled>;
}

type CardProps = { notice: AppNotice; lane: Lane; digest?: ResultDigest; host: CardHost };

/** The receipt's link to where an action happened: the pull request on its host, or the app's page for it. */
const viewOn = (digest: ResultDigest | undefined, host: CardHost, label?: string) => digest?.pull == null ? undefined
  : { label: label ?? `View on ${digest.host}`, external: true, open: () => host.openUrl(digest.pull?.url ?? '') };

const SEVERITY_TITLES: Record<string, string> = { critical: 'Critical', high: 'High', medium: 'Medium', low: 'Low' };

function SeverityPill({ severity }: { severity: string | null }) {
  if (severity === null) return null;
  return <span className={`assistant-severity is-${severity}`}><span className="assistant-sev-dot" />{SEVERITY_TITLES[severity] ?? severity}</span>;
}

/** Files read out of files changed; warning-toned with what was skipped in the tooltip when coverage is incomplete. */
function FilesChip({ review }: { review: DigestReview }) {
  const { filesRead, filesChanged, complete } = review;
  if (filesChanged === 0) return null;
  const partial = !complete || filesRead > 0 && filesRead < filesChanged;
  const skipped = [...review.omissions, ...review.warnings].join(' ');
  if (filesRead === 0 && !partial) return <Chip icon={<Check size={12} aria-hidden="true" />} tone="success" title={`${filesChanged} changed files`}>{plural(filesChanged, 'file')}</Chip>;
  return partial
    ? <Chip icon={<TriangleAlert size={12} aria-hidden="true" />} tone="warn" title={skipped === '' ? `Read ${filesRead} of the ${filesChanged} changed files` : skipped}>{filesRead}/{filesChanged} files</Chip>
    : <Chip icon={<Check size={12} aria-hidden="true" />} tone="success" title={`Read ${filesRead} of the ${filesChanged} changed files`}>{filesRead}/{filesChanged} files</Chip>;
}

const passed = (command: DigestCommand): boolean => command.exitCode === 0;
const exitText = (command: DigestCommand): string => `${command.command} · exit ${command.exitCode ?? 'unknown'}`;

/** One chip per command the reviewer ran, ✓ or ✗ by exit code; more than three fold into one chip with each in its tooltip. */
function CommandChips({ commands }: { commands: DigestCommand[] }) {
  if (commands.length === 0) return null;
  const mark = (ok: boolean) => ok ? <Check size={12} aria-hidden="true" /> : <X size={12} aria-hidden="true" />;
  if (commands.length > 3) {
    const failed = commands.filter(command => !passed(command)).length;
    return <Chip icon={mark(failed === 0)} tone={failed === 0 ? 'success' : 'danger'} title={commands.map(exitText).join('\n')}>
      {failed === 0 ? plural(commands.length, 'command') : `${failed} of ${commands.length} commands failed`}
    </Chip>;
  }
  return <>{commands.map(command => <Chip key={command.command} mono icon={mark(passed(command))} tone={passed(command) ? 'success' : 'danger'}
    title={exitText(command)}>{shortCommand(command.command)}</Chip>)}</>;
}

/** The pull request's checks for its current head, as counts: failing first, then running, then all passing. */
function ChecksChip({ pull, label = false }: { pull: DigestPull; label?: boolean }) {
  const checks = checkCounts(pull.checks);
  if (checks.total === 0) return null;
  if (checks.failed > 0) {
    return <>
      <Chip icon={<X size={12} aria-hidden="true" />} tone="danger" title={`${checks.failed} of ${checks.total} checks failed`}><span className="assistant-bad">{checks.failed} failing</span></Chip>
      {checks.passed > 0 && <Chip icon={<Check size={12} aria-hidden="true" />} tone="success" title={`${checks.passed} of ${checks.total} checks passed`}>{checks.passed}/{checks.total}</Chip>}
    </>;
  }
  if (checks.running > 0) {
    return <Chip icon={<LoaderCircle size={12} aria-hidden="true" />} tone="accent" title={`${checks.total - checks.running} of ${checks.total} checks finished, all passing so far`}>
      {checks.total - checks.running}/{checks.total} running
    </Chip>;
  }
  return <Chip icon={<Check size={12} aria-hidden="true" />} tone="success" title={label ? 'CI on the PR head' : `All ${checks.total} checks passed`}>
    {label ? `checks ${checks.passed}/${checks.total}` : `${checks.passed}/${checks.total}`}
  </Chip>;
}

function ApprovalsChip({ pull }: { pull: DigestPull }) {
  if (pull.approvers.length === 0) return null;
  return <Chip icon={<Check size={12} aria-hidden="true" />} tone="success" title={`Approved by ${pull.approvers.map(login => `@${login}`).join(', ')}`}>
    {plural(pull.approvers.length, 'approval')}
  </Chip>;
}

function LinesChip({ pull }: { pull: DigestPull }) {
  if (pull.additions === 0 && pull.deletions === 0) return null;
  return <span className="assistant-lines" title={`${plural(pull.files, 'file')} · ${pull.additions} added · ${pull.deletions} deleted`}>
    <span className="is-added">+{pull.additions}</span><span className="is-deleted">−{pull.deletions}</span>
  </span>;
}

/** What the review read, ran and found, as chips; on the user's own pull request also its checks and approvals. */
function ReviewChips({ review, pull }: { review: DigestReview; pull: DigestPull | null }) {
  const open = openFindings(review);
  const rated = severityCounts(open);
  // Nits and findings about the whole pull request carry no severity; they still count, so the chips add up to the headline.
  const other = open.length - rated.reduce((sum, entry) => sum + entry.count, 0);
  return <>
    {rated.map(({ severity, count }) => <Chip key={severity} icon={<span className={`assistant-sev-dot is-${severity}`} />}
      title={`${count} open ${severity}-severity ${count === 1 ? 'finding' : 'findings'}`}>{count} {severity}</Chip>)}
    {other > 0 && <Chip icon={<span className="assistant-sev-dot is-none" />} title={`${other} open ${other === 1 ? 'finding' : 'findings'} with no severity, such as nits`}>
      {rated.length === 0 ? plural(other, 'finding') : `${other} other`}
    </Chip>}
    <FilesChip review={review} />
    <CommandChips commands={review.commands} />
    {pull?.mine === true && <><ChecksChip pull={pull} label /><ApprovalsChip pull={pull} /></>}
  </>;
}

/** The review's verdict in one sentence. */
function reviewVerdict(review: DigestReview, yours: boolean, merge: boolean): string {
  if (headMoved(review)) return 'This review is out of date. The pull request changed after it ran.';
  const open = openFindings(review);
  if (open.length > 0) return `${plural(open.length, 'finding')}${yours ? ' on your PR' : ''}. ${sentence(open[0].title)}`;
  const read = review.complete ? review.filesChanged : review.filesRead;
  const found = read === 0 ? 'No problems found.' : review.complete ? `No problems found in ${plural(read, 'file')}.` : `No problems found in the ${plural(read, 'file')} it read.`;
  return merge ? `${found} Your PR is ready to merge.` : found;
}

/** The user's unsent review draft, which a one-click Approve or Post must not send for them. */
function useDraftPending(notice: AppNotice, wanted: boolean): boolean {
  const [pending, setPending] = useState(false);
  const { workspaceId, pullRequest } = notice;
  useEffect(() => {
    const pull = pullOf({ pullRequest } as AppNotice);
    if (!wanted || pull === null) return;
    let live = true;
    routedPullRequestBridge(window.bridge, workspaceId).getReviewDraft(pull.repo, pull.number)
      .then(draft => { if (live) setPending(draft.body.trim() !== '' || draft.items.length > 0 || draft.reviewPublication !== null); }, () => {});
    return () => { live = false; };
  }, [workspaceId, pullRequest, wanted]);
  return pending;
}

/** Posts the open findings as one review, comments only: refused, sending nothing, when the user's draft holds anything else. */
async function postFindings(notice: AppNotice, review: DigestReview, findings: DigestFinding[]): Promise<boolean> {
  const pull = pullOf(notice);
  if (pull === null || notice.reviewId === null) throw new Error('This review has no pull request to post to');
  const bridge = routedPullRequestBridge(window.bridge, notice.workspaceId);
  let draft = await bridge.getReviewDraft(pull.repo, pull.number);
  if (draft.reviewPublication !== null) throw new Error('A review you sent has no answer yet. Open the pull request to settle it first.');
  const ids = new Set(findings.map(finding => finding.id));
  if (draft.body.trim() !== '' || draft.items.some(item => item.findingId === null || !ids.has(item.findingId))) return false;
  for (const finding of findings) {
    if (!draft.items.some(item => item.findingId === finding.id)) draft = await bridge.addReviewDraftFinding(pull.repo, pull.number, draft.version, notice.reviewId, finding.id);
  }
  const saved = await bridge.saveReviewDraft(pull.repo, pull.number, draft.version, draft.body, 'COMMENT');
  await bridge.submitReviewDraft(pull.repo, pull.number, saved.version, saved.body.trim(), 'COMMENT', review.reviewedHead);
  return true;
}

/** Sends every open finding to the task that wrote the pull request, one request each, as the review's own Request fix does. */
async function fixFindings(reviewId: string, findings: DigestFinding[]): Promise<string> {
  const review = await window.bridge.getAgentReview(reviewId);
  const taskId = review.sourceTaskId;
  if (taskId == null) throw new Error('No development task is associated with this review');
  const keys = new Map<string, string>();
  for (const wanted of findings) {
    const finding = review.findings.find(each => each.id === wanted.id);
    if (finding === undefined) continue;
    const message = findingFixPrompt(review, finding, FIX_WORDS);
    await requestTaskFix(window.bridge, keys, `${finding.id}:${finding.revision}:${message}`, taskId, review.workspaceId, message);
  }
  recordReviewAttentionAction(review, 'prompt');
  return taskId;
}

const FIX_WORDS = 'Fix this finding.';

/**
 * A private review's result: the verdict, what the reviewer read and ran, and the most severe finding with its walkthrough folded away;
 * then the one click the state allows, such as Approve, Merge, Post the comments, or Fix them on the user's own pull request.
 */
function ReviewResultCard({ notice, lane, digest, host }: CardProps) {
  const [asking, setAsking] = useState<string[] | null>(null);
  const review = digest?.review ?? null;
  const pull = digest?.pull ?? null;
  const recommended = recommend(notice, digest);
  const draftPending = useDraftPending(notice, recommended === 'approve');
  const target = pullTarget(notice);
  const openReview: CardAction = { id: 'open-review', label: 'Open review', run: async () => host.openNotice(notice.id) };
  const openPull: CardAction = { id: 'open-pr', label: 'Open PR', run: async () => { if (target !== null) host.show(target); else host.openNotice(notice.id); } };
  if (review === null) {
    return <CardRow strip={STRIPS[lane]} icon={Eye} tone="accent" verdict={sentence(notice.message)} source={reviewSource(notice, pull, null)} side={<Age at={notice.occurredAt} />}
      actions={<ActionBar secondary={[openReview]} onClear={() => clearCard(notice)} />}><RuleReceipt notice={notice} /></CardRow>;
  }
  const moved = headMoved(review);
  const open = openFindings(review);
  const own = review.fixTask !== null;
  const name = pullName(notice, pull);
  const number = pull?.number ?? pullOf(notice)?.number;
  const primary = ((): CardAction | null => {
    switch (recommended) {
      case 'approve': return draftPending ? { id: 'draft', label: 'Open your draft review', run: async () => { if (target !== null) host.show(target); } }
        : { id: 'approve', label: `Approve PR #${number}`, icon: CircleCheck, run: async () => {
          const pullRef = pullOf(notice);
          if (pullRef === null) return;
          const sent = await approvePull(pullRef.repo, pullRef.number, review.reviewedHead, notice.workspaceId);
          if (!sent) { if (target !== null) host.show(target); return; }
          settleCard({ noticeId: notice.id, icon: CircleCheck, tone: 'success', lead: 'Approved', rest: `${name} · at ${sha(review.reviewedHead)}`, link: viewOn(digest, host) });
        } };
      case 'merge': return mergeAction(notice, pull, name, digest, host);
      case 'post-findings': return { id: 'post', label: `Post ${plural(open.length, 'comment')}`, icon: MessageSquare, run: async () => {
        const posted = await postFindings(notice, review, open);
        if (!posted) { host.openNotice(notice.id); return; }
        settleCard({ noticeId: notice.id, icon: MessageSquare, lead: `Posted ${plural(open.length, 'comment')}`, rest: name, link: viewOn(digest, host, 'View review') });
      } };
      case 'fix-findings': return { id: 'fix', label: `Fix ${plural(open.length, 'finding')}`, icon: Wrench, run: async () => {
        if (notice.reviewId === null) return;
        const taskId = await fixFindings(notice.reviewId, open);
        settleCard({ noticeId: notice.id, icon: Wrench, lead: `Fix requested · ${open.map(finding => finding.label).join(', ')}`, rest: `${name} · now in Running`,
          link: { label: 'Open task', open: () => host.show({ kind: 'TASK', id: taskId, workspaceId: notice.workspaceId, repo: null, number: null, connectionId: null, cloudId: null }) } });
      } };
      case 'open-review': return { ...openReview };
      case 're-review': return { id: 're-review', label: `Re-review PR #${number}`, icon: RotateCw, run: async () => {
        if (notice.reviewId === null) return;
        await window.bridge.updateAgentReview(notice.reviewId);
        settleCard({ noticeId: notice.id, icon: RotateCw, lead: 'Re-review started', rest: name, link: { label: 'Open review', open: () => host.openNotice(notice.id) } });
      } };
      default: return null;
    }
  })();
  const secondary = [open.length > 0 && !moved ? openReview : openPull].filter(action => action.id !== primary?.id);
  const was = severityCounts(review.findings).map(({ severity, count }) => `${count} ${severity}`).join(' ');
  return (
    <CardRow strip={STRIPS[lane]} icon={moved ? History : Eye} tone={moved ? 'warn' : 'accent'} verdict={reviewVerdict(review, own || pull?.mine === true, recommended === 'merge')}
      source={reviewSource(notice, pull, review)} side={<Age at={notice.occurredAt} />}
      evidence={moved ? <>
        <Chip icon={<TriangleAlert size={12} aria-hidden="true" />} tone="warn" title={`Reviewed ${sha(review.reviewedHead)} · now ${sha(review.currentHead)}`}>head moved</Chip>
        {was !== '' && <span className="assistant-dim">Was {was}</span>}
      </> : <ReviewChips review={review} pull={pull} />}
      actions={<ActionBar primary={primary} secondary={secondary} asking={asking !== null} onClear={() => clearCard(notice)}
        onAsk={notice.report === null ? undefined : () => setAsking(current => current === null ? askSuggestions(digest) : null)} />}>
      <RuleReceipt notice={notice} />
      {!moved && open.length > 0 && notice.reviewId !== null && <Findings notice={notice} reviewId={notice.reviewId} findings={open} own={own} host={host}
        onAsk={suggestions => setAsking(suggestions)} />}
      {asking !== null && notice.report !== null && <ResultDiscussion report={notice.report} suggestions={asking} scope="Ask about this review"
        placeholder={open.length > 0 ? `Ask about ${open.map(finding => finding.label).join(', ')} or the diff` : 'Ask about this review or the diff'} onClose={() => setAsking(null)} />}
    </CardRow>
  );
}

function reviewSource(notice: AppNotice, pull: DigestPull | null, review: DigestReview | null): string {
  const whose = review?.fixTask != null ? 'your task' : pull?.mine === true ? 'your PR' : pull !== null ? `@${pull.author}` : null;
  return [pullName(notice, pull), notice.repository, whose, `${laneLabel(notice.laneId)} review`].filter(part => part !== null).join(' · ');
}

function mergeAction(notice: AppNotice, pull: DigestPull | null, name: string, digest: ResultDigest | undefined, host: CardHost): CardAction | null {
  const pullRef = pullOf(notice);
  if (pull === null || pullRef === null) return null;
  const into = pull.base ?? 'its base branch';
  return { id: 'merge', label: `Merge PR #${pull.number}`, icon: GitMerge, confirm: `Merge PR #${pull.number} into ${into}? This can’t be undone.`, run: async () => {
    await mergePull(pullRef.repo, pullRef.number, pull.head, notice.workspaceId);
    settleCard({ noticeId: notice.id, icon: GitMerge, tone: 'purple', lead: 'Merged', rest: `${name} into ${into}`, link: viewOn(digest, host) });
  } };
}

/** The open findings once the review is read in full: the most severe as a box, the rest one line each until opened. */
function Findings({ notice, reviewId, findings, own, host, onAsk }: {
  notice: AppNotice; reviewId: string; findings: DigestFinding[]; own: boolean; host: CardHost; onAsk: (suggestions: string[]) => void;
}) {
  const [review, setReview] = useState<AgentReviewDto | null>(null);
  useEffect(() => {
    let live = true;
    window.bridge.getAgentReview(reviewId).then(read => { if (live) setReview(read); }, () => {});
    return () => { live = false; };
  }, [reviewId]);
  const body = { notice, findings, own, host, onAsk };
  return review === null ? <FindingList {...body} review={null} actions={null} /> : <ActingFindings {...body} review={review} onReview={setReview} />;
}

type FindingListProps = { notice: AppNotice; findings: DigestFinding[]; own: boolean; host: CardHost; onAsk: (suggestions: string[]) => void };

/** The review page's own finding actions, so the Assistant cannot drift from them. */
function ActingFindings({ review, onReview, ...props }: FindingListProps & { review: AgentReviewDto; onReview: (review: AgentReviewDto) => void }) {
  const { actions, error } = useFindingActions({ review, onReviewChange: onReview, onNavigate: () => props.host.openNotice(props.notice.id) });
  return <>
    <FindingList {...props} review={review} actions={actions} />
    {error !== null && <div className="assistant-card__fail" role="alert">{error}</div>}
  </>;
}

type Handled = { lead: string; undo?: () => Promise<void> };

function FindingList({ notice, findings, own, host, onAsk, review, actions }: FindingListProps & {
  review: AgentReviewDto | null; actions: ReturnType<typeof useFindingActions>['actions'] | null;
}) {
  const [opened, setOpened] = useState<string | null>(findings[0]?.id ?? null);
  const [handled, setHandled] = useState<ReadonlyMap<string, Handled>>(new Map());
  const full = (id: string): ReviewFindingDto | undefined => review?.findings.find(finding => finding.id === id);
  const handle = (id: string, value: Handled | null) => setHandled(current => {
    const next = new Map(current);
    if (value === null) next.delete(id);
    else next.set(id, value);
    return next;
  });
  const act = (finding: DigestFinding): FindingAct[] => {
    const dto = full(finding.id);
    if (actions === null || dto === undefined) return [];
    const ask = { id: 'ask', label: 'Ask', icon: MessageCircleQuestion, run: async () => onAsk([finding.severity === null ? `Why does ${finding.label} matter?`
      : `Why is ${finding.label} ${finding.severity}?`, `How would I fix ${finding.label}?`]) };
    const dismiss = { id: 'dismiss', label: 'Dismiss', icon: EyeOff, run: async () => {
      await actions.onResolve(dto, 'Dismissed from the Assistant', false);
      handle(finding.id, { lead: `Resolved ${finding.label} privately`, undo: async () => {
        if (notice.reviewId === null) return;
        const current = (await window.bridge.getAgentReview(notice.reviewId)).findings.find(each => each.id === finding.id);
        if (current !== undefined) await actions.onReopen(current);
        handle(finding.id, null);
      } });
    } };
    const main = own && actions.onRequestFix !== undefined
      ? { id: 'fix', label: 'Request fix', icon: Wrench, run: async () => { await actions.onRequestFix?.(dto, FIX_WORDS); handle(finding.id, { lead: `Fix requested · ${finding.label}` }); } }
      : { id: 'post', label: 'Post as comment', icon: MessageSquare, run: async () => { await actions.onPostNow(dto, findingReviewText(dto)); handle(finding.id, { lead: `Posted ${finding.label}` }); } };
    return [main, ask, dismiss];
  };
  return <div className="assistant-findings">
    {findings.map(finding => {
      const done = handled.get(finding.id);
      if (done !== undefined) {
        return <div key={finding.id} className="assistant-finding-done">
          <CircleCheck size={13} aria-hidden="true" /><span>{done.lead}</span>
          {done.undo !== undefined && <button type="button" className="assistant-text-button" onClick={() => void done.undo?.()}>Undo</button>}
        </div>;
      }
      return finding.id === opened
        ? <FindingBox key={finding.id} finding={finding} full={full(finding.id)} acts={act(finding)} onOpenAt={() => host.openNotice(notice.id)} repository={notice.repository} />
        : <button key={finding.id} type="button" className="assistant-finding-row" onClick={() => setOpened(finding.id)}>
          <ChevronRight size={12} aria-hidden="true" /><SeverityPill severity={finding.severity} /><span className="assistant-mono is-dim">{finding.label}</span>
          <span className="assistant-finding-row__title" title={finding.title}>{finding.title}</span>
          {findingPlace(finding) !== null && <span className="assistant-mono assistant-finding-row__place" title={findingPlace(finding)?.full}>{findingPlace(finding)?.short}</span>}
        </button>;
    })}
  </div>;
}

type FindingAct = { id: string; label: string; icon: LucideIcon; run: () => Promise<void> };

/** One finding: severity, id, title and where it is, its explanation, the walkthrough folded away, and what to do with it. */
function FindingBox({ finding, full, acts, onOpenAt, repository }: {
  finding: DigestFinding; full: ReviewFindingDto | undefined; acts: FindingAct[]; onOpenAt: () => void; repository: string;
}) {
  const [walkthrough, setWalkthrough] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const run = (act: FindingAct) => {
    setBusy(true);
    setError(null);
    act.run().catch((cause: unknown) => setError(cause instanceof Error ? cause.message : String(cause))).finally(() => setBusy(false));
  };
  const where = findingPlace(finding);
  // Two sentences at most; the whole explanation stays in the review, and in the tooltip.
  const said = sentences(finding.body, 2);
  return <div className="assistant-finding">
    <div className="assistant-finding__head">
      <SeverityPill severity={finding.severity} />
      <span className="assistant-mono is-dim">{finding.label}</span>
      <span className="assistant-finding__title">{finding.title}</span>
      {where !== null && <button type="button" className="assistant-path" title={`Open ${where.full}`} onClick={onOpenAt}>{where.short}</button>}
    </div>
    <div className="assistant-finding__body" title={said === finding.body.replace(/\s+/g, ' ').trim() ? undefined : finding.body}>{renderMarkdown(said, repository)}</div>
    {full?.walkthrough != null && full.walkthrough !== '' && <>
      <button type="button" className="assistant-fold-toggle" aria-expanded={walkthrough} onClick={() => setWalkthrough(value => !value)}>
        {walkthrough ? <ChevronDown size={12} aria-hidden="true" /> : <ChevronRight size={12} aria-hidden="true" />}Walkthrough
      </button>
      {walkthrough && <>
        <div className="assistant-walkthrough">{renderMarkdown(full.walkthrough, repository)}</div>
        <div className="assistant-ran" title={finding.ran === null ? 'The finding cites no command the reviewer ran' : `The finding cites a command the reviewer ran: ${finding.ran}`}>
          <FlaskConical size={12} aria-hidden="true" />{finding.ran === null ? 'Traced from source' : 'Ran it'}
        </div>
      </>}
    </>}
    {acts.length > 0 && <div className="assistant-finding__actions">
      {acts.map(act => <button key={act.id} type="button" className="assistant-text-button" disabled={busy} onClick={() => run(act)}><act.icon size={12} aria-hidden="true" />{act.label}</button>)}
    </div>}
    {error !== null && <div className="assistant-card__fail" role="alert">{error}</div>}
  </div>;
}

/** The icon and tone a development result's pull request reads at a glance. */
function taskLook(pull: DigestPull | null, recommended: ActionId | null, lane: Lane): { icon: LucideIcon; tone: Tone } {
  if (recommended === 'push') return { icon: GitCommitHorizontal, tone: 'accent' };
  if (pull === null) return { icon: CircleCheck, tone: lane === 'fyi' ? 'muted' : 'accent' };
  const failing = checkCounts(pull.checks).failed > 0;
  if (pull.draft) return { icon: GitPullRequestDraft, tone: failing ? 'danger' : lane === 'fyi' ? 'muted' : 'accent' };
  return { icon: GitPullRequest, tone: failing ? 'danger' : recommended === 'merge' ? 'success' : 'accent' };
}

/**
 * A development task's result: the agent's own outcome, shortened, with the whole report on expand; its pull request as chips; then the
 * one click the state recommends, such as Mark ready, Merge, Let ByteQuay fix CI or push it.
 */
function TaskResultCard({ notice, lane, digest, host }: CardProps) {
  const [full, setFull] = useState(false);
  const [replying, setReplying] = useState(false);
  const pull = digest?.pull ?? null;
  const recommended = recommend(notice, digest);
  const commits = useTaskCommits(notice, recommended === 'push');
  // Without the agent's own report, the notice's words stand in, such as the Assistant's assessment of a task it follows.
  const report = digest?.report ?? (notice.message === '' ? null : notice.message);
  const verdict = report === null ? 'The task finished.' : firstSentence(report);
  const name = pullName(notice, pull);
  const look = taskLook(pull, recommended, lane);
  const target = pullTarget(notice);
  const openTask: CardAction = { id: 'open-task', label: 'Open task', run: async () => host.openNotice(notice.id) };
  const openPull: CardAction = { id: 'open-pr', label: 'Open PR', run: async () => { if (target !== null) host.show(target); } };
  const openChecks: CardAction = { ...openPull, id: 'open-checks', label: 'Open checks' };
  const continueWork: CardAction = { id: 'continue', label: 'Continue this work', icon: MessageSquareReply, run: async () => setReplying(value => !value) };
  const words = (text: string, lead: string) => async () => {
    await sendToTask(notice.taskId, text, crypto.randomUUID());
    settleCard({ noticeId: notice.id, icon: lead === 'Fixing CI' ? Wrench : MessageSquareReply, lead, rest: `${notice.title} · now in Running`, link: { label: 'Open task', open: () => host.openNotice(notice.id) } });
  };
  const failing = pull === null ? [] : pull.checks.filter(check => check.state === 'failing' || check.state === 'cancelled');
  const primary = ((): CardAction | null => {
    switch (recommended) {
      case 'mark-ready': return { id: 'ready', label: `Mark PR #${pull?.number} ready`, icon: GitPullRequestArrow, run: async () => {
        const pullRef = pullOf(notice);
        if (pullRef === null) return;
        await window.bridge.markPrReadyForReview(pullRef.repo, pullRef.number, notice.workspaceId);
        settleCard({ noticeId: notice.id, icon: GitPullRequestArrow, lead: 'Marked ready', rest: name, link: { label: 'Open PR', open: () => { if (target !== null) host.show(target); } } });
      } };
      case 'merge': return mergeAction(notice, pull, name, digest, host);
      case 'fix-ci': return { id: 'fix-ci', label: 'Let ByteQuay fix CI', icon: Wrench, run: words(
        `Checks are failing on PR #${pull?.number}: ${failing.map(check => check.url === null ? check.name : `${check.name} (${check.url})`).join(', ')}. Read their logs and fix them.`,
        'Fixing CI') };
      case 'push':
      case 'close': {
        const offer = OFFERED[recommended === 'push' ? 'PUSH' : 'CLOSE'];
        return { id: recommended, label: offer.label, icon: offer.icon, run: async () => {
          await sendToTask(notice.taskId, offer.words, crypto.randomUUID());
          settleCard({ noticeId: notice.id, icon: offer.icon, lead: recommended === 'push' ? `Asked to push ${notice.branch ?? 'its branch'}` : 'Asked to close the pull request',
            rest: notice.title, link: { label: 'Open task', open: () => host.openNotice(notice.id) } });
        } };
      }
      default: return null;
    }
  })();
  const secondary = recommended === 'merge' ? [openPull, continueWork] : recommended === 'fix-ci' ? [openChecks, continueWork] : [continueWork, openTask];
  return (
    <CardRow strip={STRIPS[lane]} icon={look.icon} tone={look.tone} verdict={verdict} source={taskSource(notice, pull === null ? notice.branch === undefined ? null : `branch ${notice.branch}` : `PR #${pull.number}`)}
      side={<Age at={notice.occurredAt} />}
      evidence={pull !== null ? <>
        <Chip icon={pull.draft ? <GitPullRequestDraft size={12} aria-hidden="true" /> : <GitPullRequest size={12} aria-hidden="true" />} title={pull.draft ? 'Draft pull request' : 'Ready for review'}>
          {pull.draft ? 'Draft' : 'Ready'}
        </Chip>
        <ChecksChip pull={pull} />
        <ApprovalsChip pull={pull} />
        <LinesChip pull={pull} />
      </> : recommended === 'push' ? <Chip icon={<GitCommitHorizontal size={12} aria-hidden="true" />} title={`Committed on ${notice.branch ?? 'its branch'}, not pushed yet`}>
        {commits !== null && commits > 0 ? `${plural(commits, 'commit')} not pushed` : 'Not pushed'}
      </Chip> : undefined}
      actions={<ActionBar primary={primary} secondary={secondary} onClear={() => clearCard(notice)} />}>
      <RuleReceipt notice={notice} />
      {failing.length > 0 && <div className="assistant-checks">
        {failing.map(check => <button key={check.name} type="button" className="assistant-check" title="Open this check"
          onClick={() => check.url === null ? target !== null && host.show(target) : host.openUrl(check.url)}><X size={12} aria-hidden="true" />{check.name}</button>)}
      </div>}
      {report !== null && report.trim() !== verdict && <>
        {full && <div className="assistant-full">{renderMarkdown(report, notice.repository)}</div>}
        <button type="button" className="assistant-fold-toggle" aria-expanded={full} onClick={() => setFull(value => !value)}>
          {full ? <ChevronDown size={12} aria-hidden="true" /> : <ChevronRight size={12} aria-hidden="true" />}{full ? 'Less' : 'Full report'}
        </button>
      </>}
      {replying && <ReplyBox label="Continue this work" placeholder="Describe the next change" onCancel={() => setReplying(false)}
        onSend={async (text, key) => {
          const { queued } = await sendToTask(notice.taskId, text, key);
          settleCard({ noticeId: notice.id, icon: MessageSquareReply, lead: queued ? 'Queued your words' : 'Sent your words', rest: notice.title,
            link: { label: 'Open task', open: () => host.openNotice(notice.id) } });
        }} />}
    </CardRow>
  );
}

/** Failed work: what failed, in the failure's own words, and the way forward; a failed task takes no new turns, so it has no Retry. */
function FailureCard({ notice, lane, host }: CardProps) {
  const review = notice.reviewId !== null;
  const ended = taskEnded(notice);
  const rule = notice.taskId.startsWith('rule:');
  const target = pullTarget(notice);
  const verdict = review ? 'The review didn’t finish.' : rule ? sentence(notice.message) : ended ? 'The task failed and has ended.' : 'The last turn failed. The task is still open.';
  const openTask: CardAction = { id: 'open', label: review ? 'Open review' : 'Open task', run: async () => host.openNotice(notice.id) };
  const primary: CardAction | null = rule ? null : review ? { id: 're-review', label: `Re-review PR #${pullOf(notice)?.number ?? ''}`.trim(), icon: RotateCw, run: async () => {
    if (notice.reviewId === null) return;
    await window.bridge.resumeAgentReview(notice.reviewId);
    settleCard({ noticeId: notice.id, icon: RotateCw, lead: 'Re-review started', rest: pullName(notice, null), link: { label: 'Open review', open: () => host.openNotice(notice.id) } });
  } } : ended ? openTask : { id: 'retry', label: 'Retry', icon: RotateCw, run: async () => {
    await sendToTask(notice.taskId, 'Try again.', crypto.randomUUID());
    settleCard({ noticeId: notice.id, icon: RotateCw, lead: 'Retried', rest: `${notice.title} · now in Running`, link: { label: 'Open task', open: () => host.openNotice(notice.id) } });
  } };
  const secondary = rule ? [{ id: 'open-pr', label: 'Open PR', run: async () => { if (target !== null) host.show(target); } }] : primary?.id === 'open' ? [] : [openTask];
  return (
    <CardRow strip={lane === 'next' ? 'failed' : STRIPS[lane]} icon={CircleX} tone="danger" verdict={verdict}
      source={review ? reviewSource(notice, null, null) : taskSource(notice)} side={<Age at={notice.occurredAt} />}
      actions={<ActionBar primary={primary} secondary={secondary} onClear={() => clearCard(notice)} />}>
      {!rule && notice.message !== '' && <EvidenceLine icon={Terminal}>{notice.message}</EvidenceLine>}
      <RuleReceipt notice={notice} />
    </CardRow>
  );
}

/** A rule's finished work, asked about in place of its task's result: Open and Dismiss both count toward the rule's record. */
function RuleResultCard({ notice, lane, digest, host }: CardProps) {
  // The rule's id holds no colon; the run's key, which follows it, can.
  const rest = notice.id.slice(RULE_RESULT_NOTICE.length);
  const rule = rest.slice(0, rest.indexOf(':'));
  const key = rest.slice(rest.indexOf(':') + 1);
  const name = notice.message.split(' · ')[0];
  const review = digest?.review ?? null;
  const pull = digest?.pull ?? null;
  const verdict = review === null ? sentence(notice.message) : reviewVerdict(review, false, false);
  return (
    <CardRow strip={STRIPS[lane]} icon={Eye} tone="accent" verdict={verdict}
      source={[pullName(notice, pull), notice.repository, `rule ${name}`].join(' · ')} side={<Age at={notice.occurredAt} />}
      evidence={review === null ? undefined : <ReviewChips review={review} pull={null} />}
      actions={<ActionBar secondary={[{ id: 'open', label: 'Open', run: async () => host.openNotice(notice.id) },
        { id: 'dismiss', label: 'Dismiss', run: async () => { await window.bridge.dismissAgentRuleRun(rule, key); await window.bridge.removeAppNotice(notice.id); } }]} />} />
  );
}

/** A rule's own action on a pull request, such as waiting in the merge queue or failing, with what the rule allowed. */
function RuleActionCard({ notice, lane, host }: CardProps) {
  const run = notice.ruleRun;
  const target = pullTarget(notice);
  const failed = notice.kind === 'FAILURE';
  return (
    <CardRow strip={failed ? 'failed' : STRIPS[lane]} icon={failed ? CircleX : run?.outcome === 'QUEUED' ? LoaderCircle : GitPullRequest} tone={failed ? 'danger' : 'accent'}
      verdict={sentence(notice.message)} source={[heading(notice), notice.repository, 'Rules'].join(' · ')} side={<Age at={notice.occurredAt} />}
      actions={<ActionBar secondary={[{ id: 'open-pr', label: 'Open PR', run: async () => { if (target !== null) host.show(target); else host.openNotice(notice.id); } }]}
        onClear={() => clearCard(notice)} />}>
      <RuleReceipt notice={notice} />
    </CardRow>
  );
}

/** The Epic's next work once a pull request of it merged: nothing to do yet but start it. */
function NextWorkCard({ notice, lane, host }: CardProps) {
  return (
    <CardRow strip={STRIPS[lane]} icon={ListTodo} tone="muted" verdict={`Next in Epic: ${sentence(notice.title)}`} source={notice.repository} side={<Age at={notice.occurredAt} />}
      actions={<ActionBar secondary={[{ id: 'open', label: 'Open work', run: async () => host.openNotice(notice.id) }]} onClear={() => clearCard(notice)} />}>
      {notice.message !== '' && <EvidenceLine icon={GitMerge}>{notice.message}</EvidenceLine>}
    </CardRow>
  );
}

type DoneRow = { key: string; icon: LucideIcon; tone: 'success' | 'purple' | 'danger' | 'muted'; title: string; what: string; at: number; open: () => void };

/** Merged, closed or acted-on work folds into one row, “Done today · N”, that lists one line each with no buttons. */
export function DoneDigest({ cards, host }: { cards: WaitingCard[]; host: CardHost }) {
  const [open, setOpen] = useState(false);
  useSettled();
  const today = localDay(Date.now());
  const done = cards.flatMap(card => card.group === 'finished' && card.lane === 'done' && localDay(card.at) === today ? [card] : []);
  const rows: DoneRow[] = [
    ...done.map((card): DoneRow => {
      const pull = card.digest?.pull ?? null;
      const merged = pull?.state === 'merged' || card.notice.ruleRun?.outcome === 'MERGED';
      const target = pullTarget(card.notice);
      return { key: card.key, icon: merged ? GitMerge : GitPullRequestClosed, tone: merged ? 'purple' : 'danger', title: pullName(card.notice, pull),
        what: merged ? 'Merged' : 'Closed', at: card.at, open: () => { if (target !== null) host.show(target); else host.openNotice(card.notice.id); } };
    }),
    ...settledToday().map((receipt): DoneRow => ({ key: `receipt:${receipt.noticeId}`, icon: receipt.icon, tone: receipt.tone ?? 'success', title: receipt.rest, what: receipt.lead,
      at: receipt.at, open: receipt.link?.open ?? (() => {}) })),
  ].sort((left, right) => right.at - left.at);
  if (rows.length === 0) return null;
  return <div className={`assistant-done${open ? ' is-open' : ''}`}>
    <button type="button" className="assistant-done__head" aria-expanded={open} onClick={() => setOpen(value => !value)}>
      <CircleCheck size={16} className="assistant-done__icon" aria-hidden="true" /><span>Done today · {rows.length}</span><span className="assistant-spacer" />
      {open ? <ChevronDown size={14} aria-hidden="true" /> : <ChevronRight size={14} aria-hidden="true" />}
    </button>
    {open && <div className="assistant-done__rows">
      {rows.map(row => <button key={row.key} type="button" className="assistant-done__row" onClick={row.open}>
        <row.icon size={14} className={`is-${row.tone}`} aria-hidden="true" /><span className="assistant-done__title">{row.title}</span>
        <span className="assistant-done__what">{row.what}</span><span className="assistant-done__at">{relativeTime(row.at, { suffix: false })}</span>
      </button>)}
    </div>}
  </div>;
}

/** Results in groups: what to decide first, then one click left, then nothing to do yet, with done work folded into one row. */
export function ResultGroups({ cards, host, section }: { cards: WaitingCard[]; host: CardHost; section: (label: string) => ReactNode }) {
  const lane = (wanted: Lane) => cards.filter(card => card.group === 'finished' && card.lane === wanted);
  const group = (wanted: Lane, label: string) => {
    const shown = lane(wanted);
    return shown.length === 0 ? null : <>
      {section(`${label} · ${shown.length}`)}
      {shown.map(card => card.group === 'finished' && <ResultCard key={card.key} notice={card.notice} lane={card.lane} digest={card.digest} host={host} />)}
    </>;
  };
  return <>
    {group('decide', 'Decide')}
    {group('next', 'Next step')}
    {group('fyi', 'FYI')}
    <DoneDigest cards={cards} host={host} />
  </>;
}

/** All in groups: Results to decide, then Next step, Running folded to one line, FYI, and Done. */
export function AllResults({ cards, host, section, running }: { cards: WaitingCard[]; host: CardHost; section: (label: string) => ReactNode; running: ReactNode }) {
  const lane = (wanted: Lane) => cards.filter(card => card.group === 'finished' && card.lane === wanted);
  const group = (wanted: Lane, label: string) => {
    const shown = lane(wanted);
    return shown.length === 0 ? null : <>
      {section(`${label} · ${shown.length}`)}
      {shown.map(card => card.group === 'finished' && <ResultCard key={card.key} notice={card.notice} lane={card.lane} digest={card.digest} host={host} />)}
    </>;
  };
  return <>
    {group('decide', 'Results · decide')}
    {group('next', 'Next step')}
    {running}
    {group('fyi', 'FYI')}
    <DoneDigest cards={cards} host={host} />
  </>;
}

/**
 * A conversation's report after its notice is gone: the line it reported and, for a decision, how it was answered; the work opens from
 * here, and the result can still be asked about.
 */
export function DurableReportCard({ report, text, onOpen }: { report: AssistantReportDto; text: string; onOpen: () => void }) {
  const outcomes: Record<string, string> = { ANSWERED: 'Answered', ALLOWED: 'Allowed', DENIED: 'Denied', EXPIRED: 'Expired', CANCELLED: 'Cancelled' };
  const Receipt = report.outcome === 'DENIED' || report.outcome === 'EXPIRED' || report.outcome === 'CANCELLED' ? ShieldX : ShieldCheck;
  if (report.outcome !== null && report.outcome in outcomes) {
    return <div className="assistant-report-resolved">
      <ReceiptRow receipt={{ noticeId: report.noticeId, icon: Receipt, lead: outcomes[report.outcome] ?? report.outcome, rest: text, at: 0, link: { label: 'Open work', open: onOpen } }} />
      <ReportEvidence report={report} />
    </div>;
  }
  const done = report.state === 'COMPLETED';
  return <CardRow icon={done ? CircleCheck : Eye} tone={done ? 'success' : 'accent'} verdict={text} source={report.target.repo ?? ''}
    actions={<ActionBar secondary={[{ id: 'open', label: 'Open work', run: async () => onOpen() }]} />}>
    {report.assessment !== null && <div className="assistant-card__assessment">{report.assessment}</div>}
    <ReportEvidence report={report} />
    <ResultDiscussion report={report} suggestions={[]} scope="Ask about this result" placeholder="What would you like to understand?" collapsed />
  </CardRow>;
}
