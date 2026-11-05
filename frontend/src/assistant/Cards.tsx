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
  CircleArrowUp, CircleCheck, CircleX, Clock, ExternalLink, Eye, GitPullRequest, Hand, Hourglass, ListTodo, LoaderCircle, MessageCircleQuestion, TimerOff,
  ShieldCheck, ShieldX, TriangleAlert, X, type LucideIcon,
} from 'lucide-react';
import { latestStep, PendingApprovalCard, type ApprovalDecider, type TaskStep } from '../agents/AgentConversation';
import type { RunningTask } from '../agents/runningTaskModel';
import { formatDuration } from '../agents/transcript';
import { IconButton } from '../cards/Card';
import { ConfirmDialog } from '../ConfirmDialog';
import { pollVisible } from '../ide/pollVisible';
import { APPROVAL_NOTICE, NOTICE_ACTIONS, RULE_NOTICE, RULE_RESULT_NOTICE, noticeLabel, type AppNotice } from '../notices/contracts';
import { errorMessage } from '../errorMessage';
import { laneLabel } from '../settings/model';
import type { AgentEventEnvelope } from '../agents/events';
import type { AgendaTargetDto, AgentPendingApprovalDto, AssistantItemDto, AssistantReportDto, AssistantReportState, AssistantRuleActionDto } from '../types';
import { useAsyncAction } from '../useAsyncAction';
import { useNow, useTimeLeft } from '../useNow';
import { ResultDiscussion } from './ResultDiscussion';

const STEP_POLL_MS = 2000;

const heading = (notice: AppNotice): string => notice.pullRequestTitle ?? notice.title;
const where = (notice: AppNotice): string => `${notice.repository} · ${laneLabel(notice.laneId)}`;

/**
 * One card of the Assistant: an icon, the title over where it lives, then its state, words or buttons on the right; a decision's
 * command, question and buttons go in a body under it. Buttons that only matter under the pointer replace the right side there.
 */
function CardRow({ icon, state, tone, tip, title, meta, below, right, hover, body, wrapTitle = false, urgent = false, focused = false, id }: {
  icon: LucideIcon;
  state: AssistantReportState;
  tone?: 'accent' | 'warn' | 'danger';
  tip?: string;
  title: string;
  meta: string;
  below?: ReactNode;
  right?: ReactNode;
  hover?: ReactNode;
  body?: ReactNode;
  wrapTitle?: boolean;
  urgent?: boolean;
  /** Opened from its alert: outlined for a moment so the eye finds it. */
  focused?: boolean;
  id?: string;
}) {
  const Icon = icon;
  return (
    <div className={`assistant-card${wrapTitle ? ' has-wrapped-title' : ''}${urgent ? ' is-urgent' : ''}${focused ? ' is-opened' : ''}${hover === undefined ? '' : ' has-hover'}`} data-card={id} data-state={state}
      tabIndex={id === undefined ? undefined : -1}>
      <span className={`assistant-card__icon${state === 'PROGRESS' ? ' is-spinning' : ''}${tone === undefined ? '' : ` is-${tone}`}`} title={tip}><Icon size={16} aria-hidden="true" /></span>
      <div className="assistant-card__main">
        <CardState state={state} />
        <div className="assistant-card__title" title={title}>{title}</div>
        <div className="assistant-card__meta" title={meta}>{meta}</div>
        {below}
      </div>
      <div className="assistant-card__side">
        {right !== undefined && <span className="assistant-card__right">{right}</span>}
        {hover !== undefined && <span className="assistant-card__hover">{hover}</span>}
      </div>
      {body !== undefined && <div className="assistant-card__body">{body}</div>}
    </div>
  );
}

const CARD_STATES: Record<AssistantReportState, { label: string; icon: LucideIcon }> = {
  APPROVAL_REQUIRED: { label: 'Approval required', icon: Hand },
  ANSWER_REQUIRED: { label: 'Answer needed', icon: MessageCircleQuestion },
  PROPOSAL: { label: 'Proposal', icon: CircleArrowUp },
  RESULT_READY: { label: 'Result ready', icon: Eye },
  COMPLETED: { label: 'Completed', icon: CircleCheck },
  ATTENTION: { label: 'Needs attention', icon: TriangleAlert },
  PROGRESS: { label: 'Progress', icon: LoaderCircle },
};

export function CardState({ state }: { state: AssistantReportState }) {
  return <span className="assistant-card__state" data-state={state}>{CARD_STATES[state].label}</span>;
}

/** Facts kept with the report, including the app's individual authorization receipts. */
export function ReportEvidence({ report }: { report: Pick<AssistantReportDto, 'evidence' | 'ruleActions'> }) {
  const rules = new Map<string, AssistantRuleActionDto[]>();
  for (const action of report.ruleActions) {
    const key = JSON.stringify([action.ruleId, action.ruleVersion, action.outcome]);
    rules.set(key, [...rules.get(key) ?? [], action]);
  }
  return <>
    {[...rules].map(([id, actions]) => {
      const allowed = actions.every(action => action.outcome === 'ALLOWED');
      return <details className="assistant-rule-receipt" key={id}>
        <summary><ShieldCheck size={13} aria-hidden="true" />
          {allowed ? `Allowed ${actions.length} ${actions.length === 1 ? 'request' : 'requests'}` : `${actions.length} rule ${actions.length === 1 ? 'action' : 'actions'}`} under {actions[0].ruleName}
        </summary>
        <ul>{actions.map((action, index) => <li key={index}><strong>{action.outcome.toLowerCase().replaceAll('_', ' ')}</strong> · {action.detail} <span className="assistant-dim">(rule revision {action.ruleVersion})</span></li>)}</ul>
      </details>;
    })}
    {report.evidence.length > 0 && <details className="assistant-report-evidence">
      <summary>Evidence · {report.evidence.length}</summary>
      <ul>{report.evidence.map((entry, index) => <li key={index}>{entry}</li>)}</ul>
    </details>}
  </>;
}

/** One place to inspect the source evidence, authorization and prior outcomes without expanding the conversation. */
export function ReportActivity({ report, history = [] }: { report: AssistantReportDto; history?: AssistantItemDto[] }) {
  return <details className="assistant-report-activity">
    <summary>Activity{history.length > 0 ? ` · ${history.length} earlier ${history.length === 1 ? 'update' : 'updates'}` : ''}</summary>
    <p>{report.deliveryReason}</p>
    {report.outcome !== null && <p>{report.outcome.toLowerCase().replaceAll('_', ' ')}</p>}
    <ReportEvidence report={report} />
    {history.length > 0 && <ol>{history.map(previous => <li key={previous.id}>
      <time dateTime={previous.at}>{new Date(previous.at).toLocaleString()}{previous.report?.outcome === null ? ' · superseded update' : ''}</time>
      <div>{previous.text}</div>
      {previous.report?.outcome != null && <span className="assistant-dim">{previous.report.outcome.toLowerCase().replaceAll('_', ' ')}</span>}
      {previous.report?.assessment != null && <p>{previous.report.assessment}</p>}
      {previous.report !== null && <ReportEvidence report={previous.report} />}
    </li>)}</ol>}
  </details>;
}

/** Durable result presentation stays intact after its transient notice is seen or cleared. */
export function DurableReportCard({ report, text, onOpen, onClear, activity, summary, meta }: {
  report: AssistantReportDto; text: string; onOpen: () => void; onClear?: () => void; activity?: AssistantItemDto[]; summary?: string; meta?: string;
}) {
  const outcomes: Record<string, string> = { ANSWERED: 'Answered', ALLOWED: 'Allowed', DENIED: 'Denied', EXPIRED: 'Expired', CANCELLED: 'Cancelled' };
  const ReceiptIcon = report.outcome === 'DENIED' || report.outcome === 'EXPIRED' || report.outcome === 'CANCELLED' ? ShieldX : ShieldCheck;
  if (report.outcome !== null && report.outcome in outcomes) return <div className="assistant-report-resolved">
    <div className="assistant-receipt">
      <ReceiptIcon className="assistant-receipt__icon" size={14} aria-hidden="true" />
      <span className="assistant-receipt__text"><strong>{outcomes[report.outcome] ?? report.outcome}</strong> · {text}</span>
      <button type="button" className="assistant-button" onClick={onOpen}>Open work</button>
    </div>
    <ReportActivity report={report} history={activity} />
  </div>;
  const unavailable = report.state === 'APPROVAL_REQUIRED' || report.state === 'ANSWER_REQUIRED';
  const state = unavailable ? 'ATTENTION' : report.state;
  return <CardRow wrapTitle state={state} icon={CARD_STATES[state].icon} title={text} meta={meta ?? report.target.repo ?? ''}
    right={<button type="button" className="assistant-button" onClick={onOpen}>{state === 'RESULT_READY' ? report.target.kind === 'REVIEW' ? 'Read full review' : 'Review result' : 'Open work'}</button>}
    body={<>
      {unavailable && <div className="assistant-card__why">This request is no longer available here. Open the work to see its latest state.</div>}
      {(report.assessment ?? summary) != null && <div className="assistant-card__assessment">{report.assessment ?? summary}</div>}
      <ReportActivity report={report} history={activity} />
      <ResultDiscussion key={report.sourceTurnId ?? report.noticeId} report={report} />
      {onClear !== undefined && <button type="button" className="assistant-link" onClick={onClear}>Clear result</button>}
    </>} />;
}

function RuleActionResult({ notice, onShow, onOpen, onClear }: {
  notice: AppNotice; onShow: (target: AgendaTargetDto) => void; onOpen: () => void; onClear: () => void;
}) {
  const run = notice.ruleRun;
  if (run == null) return null;
  const state: AssistantReportState = run.outcome === 'MERGED' ? 'COMPLETED' : run.outcome === 'QUEUED' ? 'PROGRESS'
    : run.outcome === 'RESULT_READY' ? 'RESULT_READY' : 'ATTENTION';
  const pull = pullOf(notice);
  const open = () => {
    if (pull === null || notice.reviewId !== null) onOpen();
    else onShow({ kind: 'PULL', repo: pull.repo, number: pull.number, workspaceId: notice.workspaceId, id: null, connectionId: null, cloudId: null });
  };
  return <CardRow state={state} icon={CARD_STATES[state].icon} title={heading(notice)} meta={where(notice)}
    right={<button type="button" className="assistant-button" onClick={open}>{notice.reviewId !== null ? 'Read full review' : pull === null ? 'Open' : 'Open pull request'}</button>}
    body={<>
      <div className="assistant-card__assessment">{notice.message}</div>
      <details className="assistant-report-evidence"><summary>Activity</summary><p>{notice.deliveryReason}</p>
      {run.authorization !== null && <ReportEvidence report={{ evidence: [], ruleActions: [{ ruleId: run.authorization.ruleId,
        ruleName: run.authorization.ruleName, ruleVersion: run.authorization.ruleVersion, outcome: run.outcome ?? 'UNKNOWN', detail: `${run.subject} · ${run.title}` }] }} />}
      </details>
      <div className="assistant-card__buttons"><span className="assistant-spacer" />
        <button type="button" className="assistant-link" onClick={onClear}>Clear result</button>
      </div>
    </>} />;
}

function StatePill({ text, expiresAt, urgent }: { text: string; expiresAt?: string; urgent?: boolean }) {
  const left = useTimeLeft(expiresAt);
  return <span className={`assistant-state${urgent === true || left?.urgent === true ? ' is-warn' : ''}`}>{text}{left !== null && ` · ${left.text} left`}</span>;
}

/** Stop, asked first, since it ends the turn's work. */
export function StopButton({ turnId, label = 'Stop work…' }: { turnId: string; label?: string }) {
  const [asking, setAsking] = useState(false);
  const [state, setState] = useState<'idle' | 'stopping' | 'stopped'>('idle');
  const [error, setError] = useState<string | null>(null);
  if (state === 'stopped') return <span className="assistant-dim">Stopped</span>;
  const stop = () => {
    setState('stopping');
    setError(null);
    window.bridge.cancelAgentTurn(turnId).then(() => setState('stopped'))
      .catch((cause: unknown) => { setState('idle'); setError(errorMessage(cause)); }).finally(() => setAsking(false));
  };
  return <>
    <button type="button" className="assistant-button" disabled={state === 'stopping'} title={error ?? undefined} onClick={() => setAsking(true)}>{label}</button>
    {asking && (
      <ConfirmDialog title="Stop this work?" confirmLabel="Stop work" destructive busy={state === 'stopping'} onConfirm={stop} onCancel={() => setAsking(false)}>
        The current turn in this task stops. Its saved changes stay, and other work continues.
      </ConfirmDialog>
    )}
  </>;
}

/** A decision, answered in place: an approval or a question with the task's own buttons, read fresh, never from the notice's text. */
export function ApprovalCard({ notice, focused, conversation, onAnswered, onOpen }: {
  notice: AppNotice;
  focused: boolean;
  conversation: string;
  onAnswered: () => void;
  onOpen: () => void;
  onClear: () => void;
}) {
  const [approval, setApproval] = useState<AgentPendingApprovalDto | null | undefined>(undefined);
  const [error, setError] = useState<string | null>(null);
  const { taskId, turnId, reviewId } = notice;
  const approvalId = notice.id.slice(APPROVAL_NOTICE.length);
  const expiresAt = notice.expiresAt ?? approval?.expiresAt;
  const left = useTimeLeft(expiresAt);

  useEffect(() => {
    let live = true;
    if (turnId === null) {
      setApproval(null);
      return;
    }
    const read = reviewId === null ? window.bridge.readAgentPendingApprovals(taskId, turnId) : window.bridge.readAgentReviewApprovals(reviewId, taskId, turnId);
    void read.then(pending => { if (live) setApproval(pending.find(item => item.approvalId === approvalId) ?? null); })
      .catch(() => { if (live) { setError('Unable to check this request'); setApproval(null); } });
    return () => { live = false; };
  }, [approvalId, reviewId, taskId, turnId]);

  // A stale notice must never offer permission again after a rule or another surface answered it.
  if (approval === null) return <div className="assistant-receipt" data-card={notice.id}>
    <ShieldCheck className="assistant-receipt__icon" size={14} aria-hidden="true" />
    <span className="assistant-receipt__text">{error ?? 'Request no longer pending'} · {heading(notice)}</span>
    <button type="button" className="assistant-button" onClick={onOpen}>Open work</button>
  </div>;
  const decide: ApprovalDecider = (id, decision, answers) => {
    setError(null);
    const resolved = reviewId === null
      ? window.bridge.resolveAgentApproval(id, decision, undefined, answers)
      : window.bridge.resolveAgentReviewApproval(reviewId, id, decision, answers);
    // The answer stands whether or not its receipt is kept, so a lost receipt never brings the card back.
    void resolved.then(() => window.bridge.recordAssistantAnswer(conversation, id).catch((): void => undefined)).then(onAnswered)
      .catch((cause: unknown) => setError(errorMessage(cause)));
  };
  const question = notice.kind === 'QUESTION';
  const outside = approval !== undefined && !approval.sandboxed;
  return (
    <CardRow state={question ? 'ANSWER_REQUIRED' : 'APPROVAL_REQUIRED'} id={notice.id} focused={focused} icon={question ? MessageCircleQuestion : Hand}
      tip={outside ? approval?.title : undefined} title={heading(notice)} meta={where(notice)}
      urgent={left?.urgent === true}
      right={<StatePill text={question ? 'Waiting for you' : outside ? 'Outside sandbox' : 'Permission requested'} expiresAt={expiresAt} />}
      body={<>
        {approval === undefined ? <div className="assistant-dim">Loading…</div> : <PendingApprovalCard approval={approval} onDecide={decide} />}
        {error !== null && <p className="assistant-error" role="alert">{error}</p>}
      </>} />
  );
}

const NOTICE_ICONS: Partial<Record<AppNotice['kind'], LucideIcon>> = { QUESTION: MessageCircleQuestion, RECOVERY: Hourglass, STUCK: TimerOff, QUEUE_PAUSED: Clock };

type NoticeCardProps = { notice: AppNotice; focused: boolean; onOpen: () => void; onClear: () => void };

/** A decision waiting on the user: a rule's card is answered here, any other in its task. */
export function NoticeCard(props: NoticeCardProps) {
  if (props.notice.id.startsWith(RULE_NOTICE)) return <RuleCard {...props} />;
  if (props.notice.id.startsWith(RULE_RESULT_NOTICE)) return <RuleResultCard {...props} />;
  return <TaskNoticeCard {...props} />;
}

/** A rule whose record lets it do the work itself: move it up to Suggest, or keep it observing for now. */
function RuleCard({ notice, focused, onClear }: NoticeCardProps) {
  const { busy, error, run } = useAsyncAction();
  const rule = notice.taskId.slice(RULE_NOTICE.length);
  const answer = (choose: (id: string) => Promise<unknown>) => void run(async () => { await choose(rule); onClear(); });
  return (
    <CardRow state="PROPOSAL" id={notice.id} focused={focused} icon={CircleArrowUp} tone="accent" title={notice.title} meta="Rule"
      right={<StatePill text={noticeLabel(notice)} />}
      body={<>
        <div className="assistant-card__why">{notice.message}</div>
        {error !== null && <div className="assistant-card__fail" role="alert">{error}</div>}
        <div className="assistant-card__buttons">
          <button type="button" className="assistant-button" disabled={busy} onClick={() => answer(id => window.bridge.moveAgentRuleUp(id))}>Move to Suggest</button>
          <button type="button" className="assistant-button" disabled={busy} onClick={() => answer(id => window.bridge.keepAgentRuleObserving(id))}>Keep observing</button>
        </div>
      </>} />
  );
}

/** A rule's finished work: open it to keep or change it, which its record then counts, or dismiss it as not wanted. */
function RuleResultCard({ notice, focused, onOpen, onClear }: NoticeCardProps) {
  const { busy, error, run } = useAsyncAction();
  // The rule's id holds no colon; the run's key, which follows it, can.
  const rest = notice.id.slice(RULE_RESULT_NOTICE.length);
  const rule = rest.slice(0, rest.indexOf(':'));
  const key = rest.slice(rest.indexOf(':') + 1);
  const review = notice.reviewId !== null;
  return (
    <CardRow state="RESULT_READY" id={notice.id} focused={focused} icon={Eye} tone="accent" title={review ? `Review · ${heading(notice)}` : heading(notice)}
      meta={where(notice)} right={<StatePill text={noticeLabel(notice)} />}
      body={<>
        <div className="assistant-card__why">{notice.message}</div>
        {error !== null && <div className="assistant-card__fail" role="alert">{error}</div>}
        <div className="assistant-card__buttons">
          <button type="button" className="assistant-button" disabled={busy} onClick={onOpen}>Open</button>
          <button type="button" className="assistant-button" disabled={busy}
            onClick={() => void run(async () => { await window.bridge.dismissAgentRuleRun(rule, key); onClear(); })}>Dismiss</button>
        </div>
      </>} />
  );
}

/**
 * A decision the task's own page answers, such as a question asked at the end of a turn or a blocked task: why, then where to answer. A
 * turn that looks stuck can also be stopped here.
 */
function TaskNoticeCard({ notice, focused, onOpen, onClear }: NoticeCardProps) {
  const left = useTimeLeft(notice.expiresAt);
  const warn = notice.kind === 'RECOVERY' || notice.kind === 'STUCK';
  return (
    <CardRow state={notice.kind === 'QUESTION' ? 'ANSWER_REQUIRED' : 'ATTENTION'} id={notice.id} focused={focused} icon={NOTICE_ICONS[notice.kind] ?? TriangleAlert} tone={warn ? 'warn' : undefined}
      title={heading(notice)} meta={where(notice)} urgent={left?.urgent === true}
      right={<StatePill text={noticeLabel(notice)} expiresAt={notice.expiresAt} urgent={warn} />}
      body={<>
        {notice.message !== '' && <div className="assistant-card__why">{notice.message}</div>}
        <div className="assistant-card__buttons">
          <button type="button" className="assistant-button" onClick={onOpen}>{NOTICE_ACTIONS[notice.kind]}</button>
          {notice.kind === 'STUCK' && notice.turnId !== null && <StopButton turnId={notice.turnId} label="Stop work…" />}
          <span className="assistant-spacer" />
          <IconButton label={`Clear “${heading(notice)}”`} title="Clear" onClick={onClear}><X size={13} aria-hidden="true" /></IconButton>
        </div>
      </>} />
  );
}

const pullOf = (notice: AppNotice): { repo: string; number: number } | null => {
  const at = notice.pullRequest?.lastIndexOf('#') ?? -1;
  return notice.pullRequest == null || at < 1 ? null : { repo: notice.pullRequest.slice(0, at), number: Number(notice.pullRequest.slice(at + 1)) };
};

/**
 * A result, not an event: what is ready and its evidence as short counts, then the next action; a failure says so, and why, in one line.
 */
export function ResultCard({ notice, onShow, onOpen, onClear }: {
  notice: AppNotice;
  /** Opens an item, after the Assistant closes. */
  onShow: (target: AgendaTargetDto) => void;
  onOpen: () => void;
  onClear: () => void;
}) {
  if (notice.id.startsWith(RULE_NOTICE) || notice.id.startsWith(RULE_RESULT_NOTICE)) return <NoticeCard notice={notice} focused={false} onOpen={onOpen} onClear={onClear} />;
  if (notice.report !== null) return <DurableReportCard report={notice.report} text={heading(notice)} summary={notice.message} meta={where(notice)}
    onOpen={onOpen} onClear={onClear} />;
  if (notice.ruleRun != null) return <RuleActionResult notice={notice} onShow={onShow} onOpen={onOpen} onClear={onClear} />;
  const failed = notice.kind === 'FAILURE';
  const review = notice.reviewId !== null;
  const pull = pullOf(notice);
  const { changes } = notice;
  // A development result's pull request is where it is reviewed; the rest are read in their task. Opened either way, it has been seen,
  // so it leaves Results, as opening it in its task does.
  const next = failed ? 'Open' : review ? 'Read' : pull !== null ? 'Review' : notice.kind === 'NEXT_WORK' ? NOTICE_ACTIONS.NEXT_WORK : 'Open';
  const act = () => {
    if (pull === null || failed || review) {
      onOpen();
      return;
    }
    onShow({ kind: 'PULL', repo: pull.repo, number: pull.number, workspaceId: notice.workspaceId, id: null, connectionId: null, cloudId: null });
    onClear();
  };
  const evidence = [
    // A development result's message is where its pull request stands, such as "Draft PR #58 · checks running".
    pull !== null && <span key="pr"><GitPullRequest size={13} aria-hidden="true" />{review ? notice.pullRequest : notice.message}</span>,
    changes !== null && changes.files > 0 && (
      <span key="lines" className="assistant-lines" aria-label={`${changes.files} files, ${changes.additions} added, ${changes.deletions} deleted`}>
        <span className="is-added">+{changes.additions}</span><span className="is-deleted">−{changes.deletions}</span>
      </span>
    ),
  ].filter(Boolean);
  return (
    <CardRow state={failed ? 'ATTENTION' : 'RESULT_READY'} icon={failed ? CircleX : review ? Eye : pull !== null ? GitPullRequest : notice.kind === 'NEXT_WORK' ? ListTodo : Eye}
      tone={failed ? 'danger' : undefined} title={review ? `Review · ${heading(notice)}` : heading(notice)} meta={where(notice)}
      below={failed ? <div className="assistant-card__fail">Failed · {notice.message}</div>
        : evidence.length > 0 ? <div className="assistant-card__evidence">{evidence}</div>
          : notice.message !== '' && <div className="assistant-card__evidence" title={notice.message}><span className="assistant-card__line">{notice.message}</span></div>}
      right={<>
        <button type="button" className="assistant-button" onClick={act}>{next}</button>
        <IconButton label={`Open “${heading(notice)}”`} title="Open" onClick={onOpen}><ExternalLink size={14} aria-hidden="true" /></IconButton>
        <span className="assistant-reveal"><IconButton label={`Clear “${heading(notice)}”`} title="Clear" onClick={onClear}><X size={13} aria-hidden="true" /></IconButton></span>
      </>} />
  );
}

type Live = { step: TaskStep | null; turnId: string | null };

/** The task's newest step and its current turn, from its latest history page. */
function useLive(taskId: string): Live {
  const [live, setLive] = useState<Live>({ step: null, turnId: null });
  useEffect(() => pollVisible(async () => {
    try {
      const events: AgentEventEnvelope[] = (await window.bridge.readAgentHistory(taskId)).events;
      setLive({ step: latestStep(events), turnId: events.findLast(event => event.turnId !== null)?.turnId ?? null });
    }
    catch { /* The last step stays through a failed read. */ }
  }, STEP_POLL_MS), [taskId]);
  return live;
}

/** A task at work: its current step and how long that has run; under the pointer, Open and Stop take their place. */
export function RunningCard({ task, onOpen }: { task: RunningTask; onOpen: () => void }) {
  const { step, turnId } = useLive(task.id);
  const now = useNow(step !== null && step.endedAt === null);
  const took = step === null ? null : (step.endedAt ?? now) - step.startedAt;
  return (
    <CardRow state="PROGRESS" icon={LoaderCircle} tone="accent" title={task.title}
      meta={[task.repository, task.pullRequestNumber === null ? null : `#${task.pullRequestNumber}`].filter(Boolean).join(' · ')}
      right={step === null ? 'Working' : took !== null && took >= 1000 ? `${step.text} · ${formatDuration(took)}` : step.text}
      hover={<>
        <button type="button" className="assistant-button" onClick={onOpen}>Open</button>
        {task.kind === 'TASK' && turnId !== null && <StopButton turnId={turnId} />}
      </>} />
  );
}

/** All is fine: the running tasks folded to one line that opens them. */
export function RunningFold({ count, onOpen }: { count: number; onOpen: () => void }) {
  return (
    <button type="button" className="assistant-fold" title="Open Running" onClick={onOpen}>
      <LoaderCircle size={16} className="assistant-fold__icon" aria-hidden="true" />
      <span>{count} running · all fine</span>
    </button>
  );
}
