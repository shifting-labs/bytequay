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
import { useEffect, useRef, useState, type ReactNode } from 'react';
import {
  Activity, ArrowUpRight, ChevronDown, ChevronRight, CircleArrowUp, CircleCheck, Clock, FolderPen, GitCommitHorizontal, Globe, Hand, Hourglass, LoaderCircle, MessageCircle, MessageCircleQuestion,
  MessageSquareReply, Search, ShieldCheck, ShieldOff, SquareTerminal, TimerOff, TriangleAlert, Undo2, Upload, X, XCircle, type LucideIcon,
} from 'lucide-react';
import { clock } from '@bytequay/ui/displayText';
import { relativeTime } from '@bytequay/ui/relativeTime';
import { latestStep, PendingApprovalCard, type ApprovalDecider, type TaskStep } from '../agents/AgentConversation';
import { approvalDecisions, approvalQuestions } from '../agents/model';
import type { RunningTask } from '../agents/runningTaskModel';
import { formatDuration } from '../agents/transcript';
import { IconButton } from '../cards/Card';
import { ConfirmDialog } from '../ConfirmDialog';
import { pollVisible } from '../ide/pollVisible';
import { APPROVAL_NOTICE, ARTIFACT_NOTICE, NOTICE_ACTIONS, REPLIES_NOTICE, RULE_NOTICE, noticeLabel, type AppNotice } from '../notices/contracts';
import { errorMessage } from '../errorMessage';
import { laneLabel } from '../settings/model';
import type { AgentEventEnvelope } from '../agents/events';
import type { AgentPendingApprovalDto, AssistantReportState, AssistantRuleActionDto, HeldReviewReplyDto } from '../types';
import { useAsyncAction } from '../useAsyncAction';
import { useNow, useTimeLeft } from '../useNow';
import { firstSentence, type ResultDigest } from './results';
import { clearLater, clearingTitle, receiptOf, settle, undoClear, useSettled, type Receipt } from './settled';

const STEP_POLL_MS = 2000;

const heading = (notice: AppNotice): string => notice.pullRequestTitle ?? notice.title;

/** Where a task's card lives, small: the task, its repository and the agent. */
export const taskSource = (notice: AppNotice, ...extra: (string | null)[]): string =>
  [notice.title, notice.repository, ...extra, laneLabel(notice.laneId)].filter(part => part !== null && part !== '').join(' · ');

/** The state a card's left strip carries, in place of a label line. */
export type Strip = 'decide' | 'next' | 'fyi' | 'failed';
const STRIP_TITLES: Record<Strip, string> = { decide: 'Decide', next: 'Next step', fyi: 'FYI', failed: 'Failed' };

export type Tone = 'accent' | 'warn' | 'danger' | 'success' | 'muted';

/**
 * One card of the Assistant: the strip carries its state, then an icon, the verdict over where it comes from, and its age or deadline on
 * the right; under them its evidence as chips, anything the card adds, and its actions. Buttons that only matter under the pointer replace
 * the right side there.
 */
export function CardRow({ strip, icon, tone, tip, verdict, source, side, hover, evidence, children, actions, nowrap = false, spinning = false, urgent = false,
  focused = false, id }: {
  strip?: Strip;
  icon: LucideIcon;
  tone?: Tone;
  tip?: string;
  verdict: string;
  source: string;
  side?: ReactNode;
  hover?: ReactNode;
  evidence?: ReactNode;
  children?: ReactNode;
  actions?: ReactNode;
  /** One line, as a running task's title keeps. */
  nowrap?: boolean;
  spinning?: boolean;
  urgent?: boolean;
  /** Opened from its alert: outlined for a moment so the eye finds it. */
  focused?: boolean;
  id?: string;
}) {
  const Icon = icon;
  const body = evidence !== undefined || children !== undefined || actions !== undefined;
  return (
    <div className={`assistant-card${nowrap ? ' is-nowrap' : ''}${urgent ? ' is-urgent' : ''}${focused ? ' is-opened' : ''}${hover === undefined ? '' : ' has-hover'}`}
      data-card={id} data-strip={strip} tabIndex={id === undefined ? undefined : -1}>
      {strip !== undefined && <span className="assistant-card__strip" title={STRIP_TITLES[strip]} />}
      <span className={`assistant-card__icon${spinning ? ' is-spinning' : ''}${tone === undefined ? '' : ` is-${tone}`}`} title={tip}><Icon size={16} aria-hidden="true" /></span>
      <div className="assistant-card__main">
        <div className="assistant-card__verdict">{verdict}</div>
        <div className="assistant-card__source" title={source}>{source}</div>
      </div>
      <div className="assistant-card__side">
        {side !== undefined && <span className="assistant-card__right">{side}</span>}
        {hover !== undefined && <span className="assistant-card__hover">{hover}</span>}
      </div>
      {body && <div className="assistant-card__body">
        {evidence !== undefined && <div className="assistant-evidence">{evidence}</div>}
        {children}
        {actions}
      </div>}
    </div>
  );
}

/** How long ago a card's news came, as its right side shows it. */
export const Age = ({ at }: { at: string }) => <time className="assistant-age" dateTime={at} title={new Date(at).toLocaleString()}>{relativeTime(at, { suffix: false })}</time>;

const CARD_STATES: Record<AssistantReportState, string> = {
  APPROVAL_REQUIRED: 'Approval required', ANSWER_REQUIRED: 'Answer needed', PROPOSAL: 'Proposal', RESULT_READY: 'Result ready', COMPLETED: 'Completed',
  ATTENTION: 'Needs attention', PROGRESS: 'Progress',
};

/** A label for the cards that are not news, such as a drafted issue or an offered rule. */
export function CardState({ state }: { state: AssistantReportState }) {
  return <span className="assistant-card__state" data-state={state}>{CARD_STATES[state]}</span>;
}

/** The app's individual authorization receipts kept with a report: what each rule allowed. */
export function ReportEvidence({ report }: { report: { ruleActions: AssistantRuleActionDto[] } }) {
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
        <ul>{actions.map((action, index) => <li key={index}>{action.outcome.toLowerCase().replaceAll('_', ' ')} · {action.detail} <span className="assistant-dim">(rule revision {action.ruleVersion})</span></li>)}</ul>
      </details>;
    })}
  </>;
}

/** Evidence as an icon and a short count or name; the sentence goes in the tooltip. */
export function Chip({ icon, tone, title, mono = false, children }: { icon?: ReactNode; tone?: Tone; title: string; mono?: boolean; children: ReactNode }) {
  return <span className={`assistant-chip-e${tone === undefined ? '' : ` is-${tone}`}`} title={title}>{icon}{mono ? <span className="assistant-mono">{children}</span> : children}</span>;
}

/** A failure's own error line or the watchdog's, verbatim. */
export function EvidenceLine({ icon: Icon, children }: { icon: LucideIcon; children: ReactNode }) {
  return <div className="assistant-line-e"><Icon size={12} aria-hidden="true" /><span>{children}</span></div>;
}

/** One click a card offers; a confirm string makes the click ask in place first. */
export type CardAction = { id: string; label: string; icon?: LucideIcon; run: () => Promise<void>; confirm?: string; confirmLabel?: string };

/**
 * A card's actions: the one recommended primary, at most two secondary ones, then Ask and Clear on the right. An action that asks first
 * swaps the row for its question; a failed action says why under the row.
 */
export function ActionBar({ primary, secondary = [], onAsk, asking = false, onClear, extra }: {
  primary?: CardAction | null;
  secondary?: CardAction[];
  onAsk?: () => void;
  asking?: boolean;
  onClear?: () => void;
  /** Buttons that keep their own state, such as Stop work. */
  extra?: ReactNode;
}) {
  const { busy, error, run } = useAsyncAction();
  const [confirming, setConfirming] = useState<CardAction | null>(null);
  const click = (action: CardAction) => action.confirm === undefined ? void run(action.run) : setConfirming(action);
  if (confirming !== null) {
    return <div className="assistant-actions is-confirm" role="group" aria-label={confirming.confirm}>
      <TriangleAlert size={13} className="assistant-actions__warn" aria-hidden="true" />
      <span className="assistant-actions__ask">{confirming.confirm}</span>
      <button type="button" className="assistant-button is-primary" disabled={busy} onClick={() => void run(confirming.run).then(() => setConfirming(null))}>
        {confirming.confirmLabel ?? confirming.label}
      </button>
      <button type="button" className="assistant-button" disabled={busy} onClick={() => setConfirming(null)}>Cancel</button>
      {error !== null && <div className="assistant-card__fail" role="alert">{error}</div>}
    </div>;
  }
  const button = (action: CardAction, main: boolean) => {
    const Icon = action.icon;
    return <button key={action.id} type="button" className={`assistant-button${main ? ' is-primary' : ''}`} data-action={action.id} disabled={busy} onClick={() => click(action)}>
      {Icon !== undefined && <Icon size={13} aria-hidden="true" />}{action.label}
    </button>;
  };
  return <div className="assistant-actions">
    {primary != null && button(primary, true)}
    {secondary.slice(0, 2).map(action => button(action, false))}
    {extra}
    <span className="assistant-spacer" />
    {onAsk !== undefined && <button type="button" className="assistant-text-button" aria-expanded={asking} onClick={onAsk}><MessageCircleQuestion size={12} aria-hidden="true" />Ask</button>}
    {onClear !== undefined && <IconButton label="Clear" title="Clear" onClick={onClear}><X size={13} aria-hidden="true" /></IconButton>}
    {error !== null && <div className="assistant-card__fail" role="alert">{error}</div>}
  </div>;
}

/** What an action did, on one line, with one link to where it happened; a private change also offers Undo. */
export function ReceiptRow({ receipt, onUndone }: { receipt: Receipt; onUndone?: () => void }) {
  const { busy, error, run } = useAsyncAction();
  const Icon = receipt.icon;
  const { link, undo } = receipt;
  return <div className={`assistant-receipt${receipt.tone === undefined ? '' : ` is-${receipt.tone}`}`} data-card={receipt.noticeId}>
    <Icon className="assistant-receipt__icon" size={14} aria-hidden="true" />
    <span className="assistant-receipt__text" title={`${receipt.lead} · ${receipt.rest}`}><span className="assistant-receipt__lead">{receipt.lead}</span> · {receipt.rest}</span>
    {undo !== undefined && <button type="button" className="assistant-button" disabled={busy} onClick={() => void run(async () => { await undo(); onUndone?.(); })}>
      <Undo2 size={12} aria-hidden="true" />Undo
    </button>}
    {link !== undefined && <button type="button" className="assistant-button" onClick={link.open}>{link.label}{link.external === true && <ArrowUpRight size={12} aria-hidden="true" />}</button>}
    {error !== null && <span className="assistant-error" role="alert">{error}</span>}
  </div>;
}

/**
 * A card the user acted on or cleared shows its receipt instead; a clear waits ten seconds for Undo. Wraps every news card, so each
 * collapses the same way.
 */
export function Settled({ notice, children }: { notice: AppNotice; children: ReactNode }) {
  useSettled();
  const receipt = receiptOf(notice.id);
  if (receipt !== undefined) return <ReceiptRow receipt={receipt} />;
  const cleared = clearingTitle(notice.id);
  if (cleared !== undefined) {
    return <div className="assistant-receipt is-muted" data-card={notice.id}>
      <X className="assistant-receipt__icon" size={14} aria-hidden="true" />
      <span className="assistant-receipt__text"><span className="assistant-receipt__lead">Cleared</span> · {cleared}</span>
      <button type="button" className="assistant-button" onClick={() => undoClear(notice.id)}><Undo2 size={12} aria-hidden="true" />Undo</button>
    </div>;
  }
  return <>{children}</>;
}

/** Clear with ten seconds to take it back. */
export const clearCard = (notice: AppNotice): void => clearLater(notice.id, heading(notice), id => window.bridge.removeAppNotice(id));

/** Keeps the card's receipt and lets its notice go. */
export const settleCard = (receipt: Omit<Receipt, 'at'>): void => settle({ ...receipt, at: Date.now() }, id => window.bridge.removeAppNotice(id));

export function StatePill({ text, expiresAt, urgent }: { text: string; expiresAt?: string; urgent?: boolean }) {
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

/** Words to the task itself, from a card's button or reply box: one key per send, so a send repeated after its answer was lost arrives once. */
export const sendToTask = (taskId: string, text: string, key: string): Promise<{ queued: boolean }> => window.bridge.sendWordsToTask(taskId, text, key);

/** A reply box that goes to the task: Enter sends, Escape puts it away when it can fold. */
export function ReplyBox({ placeholder, label, onSend, onCancel }: {
  placeholder: string;
  label: string;
  onSend: (text: string, key: string) => Promise<void>;
  onCancel?: () => void;
}) {
  const [text, setText] = useState('');
  const [key, setKey] = useState(() => crypto.randomUUID());
  const { busy, error, run } = useAsyncAction();
  const input = useRef<HTMLTextAreaElement>(null);
  useEffect(() => { if (onCancel !== undefined) input.current?.focus(); }, [onCancel]);
  const send = () => {
    const words = text.trim();
    if (words === '' || busy) return;
    void run(async () => {
      await onSend(words, key);
      setText('');
      setKey(crypto.randomUUID());
    });
  };
  return <div className="assistant-reply-to">
    <textarea ref={input} className="assistant-reply-to__text" aria-label={label} placeholder={placeholder} rows={1} maxLength={16 * 1024} value={text} disabled={busy}
      onChange={event => setText(event.target.value)}
      onKeyDown={event => {
        if (event.key === 'Escape' && onCancel !== undefined) {
          event.preventDefault();
          onCancel();
        }
        else if (event.key === 'Enter' && !event.shiftKey && !event.nativeEvent.isComposing) {
          event.preventDefault();
          send();
        }
      }} />
    <button type="button" className="assistant-button" disabled={text.trim() === '' || busy} onClick={send}>Send</button>
    {error !== null && <div className="assistant-card__fail" role="alert">{error}</div>}
  </div>;
}

/** The words an offer sends: the step the result left to the user, named so the task's own tool may do it at once. */
export const OFFERED: Record<'PUSH' | 'CLOSE', { label: string; words: string; icon: LucideIcon }> = {
  PUSH: { label: 'Let ByteQuay push it', words: "Push this task's committed branch with push_task_branch.", icon: Upload },
  CLOSE: { label: 'Let ByteQuay close it', words: "Close this task's pull request with close_task_pull_request.", icon: XCircle },
};

/** The commits a task made on its branch, read from its checkout once, for an offer that pushes them. */
export function useTaskCommits(notice: AppNotice, wanted: boolean): number | null {
  const [commits, setCommits] = useState<number | null>(null);
  useEffect(() => {
    if (!wanted) return;
    let live = true;
    window.bridge.listAgentTaskChanges(notice.workspaceId, [notice.taskId])
      .then(changes => { if (live) setCommits(changes[notice.taskId]?.commits ?? null); }, () => {});
    return () => { live = false; };
  }, [notice.workspaceId, notice.taskId, wanted]);
  return commits;
}

const plural = (count: number, one: string, many = `${one}s`) => `${count} ${count === 1 ? one : many}`;

/** What an approval runs, the agent's own words for why, and what it needs; a line with nothing is left out. */
export type ApprovalLines = { runs: string | null; why: string | null; needs: { icon: LucideIcon; text: string }[] };

export function approvalLines(approval: Pick<AgentPendingApprovalDto, 'approvalKind' | 'detail' | 'sandboxed'>): ApprovalLines {
  const needs: { icon: LucideIcon; text: string }[] = [];
  if (!approval.sandboxed && approval.approvalKind === 'COMMAND') needs.push({ icon: ShieldOff, text: 'Outside the sandbox' });
  // Claude's request is its tool's input, after a sentence when it would leave the sandbox.
  const outside = "Runs outside Claude's filesystem sandbox with host file access.\n";
  const json = approval.detail.startsWith(outside) ? approval.detail.slice(outside.length) : approval.detail;
  try {
    const input: unknown = JSON.parse(json);
    if (typeof input === 'object' && input !== null && !Array.isArray(input)) {
      const fields = input as Record<string, unknown>;
      const said = (name: string) => typeof fields[name] === 'string' && fields[name] !== '' ? fields[name] as string : null;
      if (approval.detail.startsWith(outside) && needs.length === 0) needs.push({ icon: ShieldOff, text: 'Outside the sandbox' });
      const path = said('file_path') ?? said('notebook_path');
      if (path !== null) needs.push({ icon: FolderPen, text: `Write to ${path}` });
      return { runs: said('command') ?? said('url'), why: said('description'), needs };
    }
  }
  catch {
    // Not a tool's input: Codex's own lines, the command first, then what it asked for.
  }
  const lines = approval.detail.split('\n');
  const tagged = (tag: string) => lines.find(line => line.startsWith(tag))?.slice(tag.length).trim() || null;
  const network = tagged('Network approval:');
  if (network !== null) needs.push({ icon: Globe, text: 'Network access' });
  const permissions = tagged('Additional permissions:');
  if (permissions !== null) needs.push({ icon: ShieldOff, text: permissions });
  const root = tagged('Requested write root:');
  if (root !== null) needs.push({ icon: FolderPen, text: `Write to ${root}` });
  const command = approval.approvalKind === 'COMMAND' ? lines[0]?.trim() || null : null;
  const reason = tagged('Reason:') ?? (approval.approvalKind !== 'COMMAND' && lines[0] !== undefined && !lines[0].includes(':') ? lines[0].trim() || null : null);
  return { runs: command, why: reason, needs };
}

/** The approval's verdict: who asks to do what, and where. */
function approvalVerdict(agent: string, approval: AgentPendingApprovalDto, lines: ApprovalLines): string {
  const outside = lines.needs.some(need => need.icon === ShieldOff);
  if (approval.approvalKind === 'COMMAND') return `${agent} asks to run a command${outside ? ' outside the sandbox' : ''}.`;
  if (approval.approvalKind === 'FILE_CHANGE' || lines.needs.some(need => need.icon === FolderPen)) return `${agent} asks to write outside the project folder.`;
  return approval.title.endsWith('.') ? approval.title : `${approval.title}.`;
}

/**
 * A decision, answered in place: an approval says what runs, why and what it needs; a question offers the agent's own options, one click
 * each, or its form when one click cannot answer it. Read fresh, never from the notice's text.
 */
export function ApprovalCard({ notice, focused, conversation, onAnswered, onOpen }: {
  notice: AppNotice;
  focused: boolean;
  conversation: string;
  onAnswered: () => void;
  onOpen: () => void;
}) {
  const [approval, setApproval] = useState<AgentPendingApprovalDto | null | undefined>(undefined);
  const [error, setError] = useState<string | null>(null);
  const [answering, setAnswering] = useState(false);
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
    <button type="button" className="assistant-button" onClick={onOpen}>Open task</button>
  </div>;
  const decide: ApprovalDecider = (id, decision, answers) => {
    setError(null);
    setAnswering(true);
    const resolved = reviewId === null
      ? window.bridge.resolveAgentApproval(id, decision, undefined, answers)
      : window.bridge.resolveAgentReviewApproval(reviewId, id, decision, answers);
    // The answer stands whether or not its receipt is kept, so a lost receipt never brings the card back.
    void resolved.then(() => window.bridge.recordAssistantAnswer(conversation, id).catch((): void => undefined)).then(onAnswered)
      .catch((cause: unknown) => setError(errorMessage(cause))).finally(() => setAnswering(false));
  };
  const agent = laneLabel(notice.laneId);
  const source = taskSource(notice);
  const questions = approval === undefined ? [] : approvalQuestions(approval);
  const urgent = left?.urgent === true;
  if (approval !== undefined && questions.length > 0) {
    const single = questions.length === 1 && !questions[0].multiSelect ? questions[0] : null;
    const decisions = approvalDecisions({ actionable: approval.actionable, outcome: null, decisions: approval.availableDecisions });
    const allow = decisions.find(choice => choice.decision === 'ALLOW')?.decision;
    return (
      <CardRow strip="decide" id={notice.id} focused={focused} urgent={urgent} icon={MessageCircleQuestion} tone="warn"
        verdict={single === null ? `${agent} has ${plural(questions.length, 'question')} before it goes on.` : single.question} source={source}
        side={<StatePill text="Waiting" expiresAt={expiresAt} />}>
        {single !== null && allow !== undefined
          ? <div className="assistant-options" role="radiogroup" aria-label={single.question}>
            {single.options.map(option => <button key={option.label} type="button" className="assistant-option" title={option.description || undefined} disabled={answering}
              onClick={() => decide(approval.approvalId, allow, { [single.question]: option.label })}>{option.label}</button>)}
          </div>
          : <div className="assistant-question-form"><PendingApprovalCard approval={approval} onDecide={decide} /></div>}
        {single !== null && allow !== undefined && <ReplyBox label="Answer in your own words" placeholder="Or answer in your own words"
          onSend={async text => decide(approval.approvalId, allow, { [single.question]: text })} />}
        {error !== null && <p className="assistant-card__fail" role="alert">{error}</p>}
      </CardRow>
    );
  }
  const lines = approval === undefined ? null : approvalLines(approval);
  const outside = approval !== undefined && !approval.sandboxed;
  const decisions = approval === undefined ? [] : approvalDecisions({ actionable: approval.actionable, outcome: null, decisions: approval.availableDecisions });
  return (
    <CardRow strip="decide" id={notice.id} focused={focused} urgent={urgent} icon={Hand} tone="warn" tip={outside ? approval?.title : undefined}
      verdict={approval === undefined || lines === null ? heading(notice) : approvalVerdict(agent, approval, lines)} source={source}
      side={<StatePill text={outside ? 'Outside sandbox' : 'Permission requested'} expiresAt={expiresAt} />}
      actions={approval !== undefined && decisions.length > 0 ? <div className="assistant-actions">
        {decisions.map(choice => <button key={choice.offered} type="button" className={`assistant-button${choice.decision === 'ALLOW' ? ' is-primary' : ''}`} disabled={answering}
          aria-label={`${choice.label}: ${approval.title}`} onClick={() => decide(approval.approvalId, choice.decision)}>
          {choice.decision === 'ALLOW' && <ShieldCheck size={13} aria-hidden="true" />}{choice.label}
        </button>)}
      </div> : undefined}>
      {approval === undefined ? <div className="assistant-dim">Loading…</div> : lines !== null && <div className="assistant-facts">
        {lines.runs !== null && <div className="assistant-fact-line"><span>Runs</span><div><code className="assistant-command">{lines.runs}</code></div></div>}
        {lines.why !== null && <div className="assistant-fact-line"><span>Why</span><div>“{lines.why}”</div></div>}
        {lines.needs.length > 0 && <div className="assistant-fact-line"><span>Needs</span><div className="assistant-needs">
          {lines.needs.map(need => <span key={need.text}><need.icon size={12} aria-hidden="true" />{need.text}</span>)}
        </div></div>}
        {lines.runs === null && lines.why === null && lines.needs.length === 0 && approval.detail !== '' && <pre className="assistant-command is-block">{approval.detail}</pre>}
      </div>}
      {approval !== undefined && !approval.actionable && <div className="assistant-dim">Waiting on the provider</div>}
      {error !== null && <p className="assistant-card__fail" role="alert">{error}</p>}
    </CardRow>
  );
}

type NoticeCardProps = { notice: AppNotice; focused: boolean; digest?: ResultDigest; onOpen: () => void; onClear: () => void };

/** A decision waiting on the user that is not an approval: a rule ready to move up, or a notice that needs the task's page. */
export function NoticeCard(props: NoticeCardProps) {
  if (props.notice.id.startsWith(RULE_NOTICE)) return <RuleCard {...props} />;
  if (props.notice.id.startsWith(REPLIES_NOTICE)) return <RepliesCard {...props} />;
  return <Settled notice={props.notice}><TaskNoticeCard {...props} /></Settled>;
}

/** Replies Auto-Review fix holds before they post: each under the comment it answers, then post them now or keep them back. */
function RepliesCard({ notice, focused, onOpen, onClear }: NoticeCardProps) {
  const [replies, setReplies] = useState<HeldReviewReplyDto[] | null>(null);
  const left = useTimeLeft(notice.expiresAt);
  const { taskId, workspaceId } = notice;
  useEffect(() => {
    let live = true;
    void window.bridge.readHeldReviewReplies(taskId, workspaceId).then(held => { if (live) setReplies(held); })
      .catch(() => { if (live) setReplies([]); });
    return () => { live = false; };
  }, [taskId, workspaceId, notice.id]);
  // Posted, kept back or answered elsewhere: a stale card must not offer the post again.
  if (replies !== null && replies.length === 0) return <div className="assistant-receipt" data-card={notice.id}>
    <MessageSquareReply className="assistant-receipt__icon" size={14} aria-hidden="true" />
    <span className="assistant-receipt__text">Replies no longer held · {heading(notice)}</span>
    <button type="button" className="assistant-button" onClick={onOpen}>Open work</button>
  </div>;
  const answer = (act: (taskId: string, workspaceId: string) => Promise<unknown>) => async () => { await act(taskId, workspaceId); onClear(); };
  const postAt = replies?.[0]?.postAt;
  return (
    <CardRow strip="decide" id={notice.id} focused={focused} urgent={left?.urgent === true} icon={MessageSquareReply} tone="warn" verdict={notice.message}
      source={taskSource(notice)} side={<StatePill text={noticeLabel(notice)} expiresAt={notice.expiresAt} />}
      actions={replies === null ? undefined : <ActionBar primary={{ id: 'post', label: 'Post now', icon: MessageSquareReply, run: answer(window.bridge.postHeldReviewReplies) }}
        secondary={[{ id: 'keep', label: 'Don’t post', run: answer(window.bridge.keepHeldReviewReplies) }, { id: 'open', label: 'Open task', run: async () => onOpen() }]} />}>
      {postAt !== undefined && notice.expiresAt === undefined && <EvidenceLine icon={Clock}>Posting at {clock(postAt)}.</EvidenceLine>}
      {replies === null ? <div className="assistant-dim">Loading…</div> : <ul className="assistant-replies">
        {replies.map(reply => <li key={reply.commentId}>
          {reply.comment !== null && <a className="assistant-replies__comment" href={reply.url ?? undefined} target="_blank" rel="noreferrer"
            title={reply.comment}>{reply.author !== null && `${reply.author}: `}{reply.comment}</a>}
          <div className="assistant-replies__reply">
            {reply.fixed
              ? <CircleCheck size={13} aria-label="Fixed; the thread is resolved after the reply" />
              : <MessageCircle size={13} aria-label="Not changed; the thread stays open" />}
            <span>{reply.reply}</span>
          </div>
        </li>)}
      </ul>}
    </CardRow>
  );
}

/** A rule whose record lets it do the work itself: move it up to Suggest, or keep it observing for now. */
function RuleCard({ notice, focused, onClear }: NoticeCardProps) {
  const rule = notice.taskId.slice(RULE_NOTICE.length);
  const answer = (choose: (id: string) => Promise<unknown>) => async () => { await choose(rule); onClear(); };
  // The notice asks, "Agreed 12 of 12 times. Move it to Suggest?"; the record is the evidence, the question is the card.
  const record = notice.message.replace(/\s*Move it to Suggest\?\s*$/, '');
  return (
    <CardRow strip="decide" id={notice.id} focused={focused} icon={CircleArrowUp} tone="accent" verdict={`${notice.title} is ready to move up to Suggest.`} source="Rule · Observing"
      side={<StatePill text={noticeLabel(notice)} />}
      actions={<ActionBar primary={{ id: 'move-up', label: 'Move to Suggest', icon: CircleArrowUp, run: answer(id => window.bridge.moveAgentRuleUp(id)) }}
        secondary={[{ id: 'keep', label: 'Keep observing', run: answer(id => window.bridge.keepAgentRuleObserving(id)) }]} />}>
      {record !== '' && <EvidenceLine icon={Activity}>{record}</EvidenceLine>}
    </CardRow>
  );
}

const NOTICE_ICONS: Partial<Record<AppNotice['kind'], LucideIcon>> = { QUESTION: MessageCircleQuestion, RECOVERY: Hourglass, STUCK: TimerOff, QUEUE_PAUSED: Clock };

/**
 * A decision the task's own page answers: a question asked at the end of a turn, which takes a reply here, a turn that looks stuck, a
 * recovery, a paused queue, or CI Autofix waiting on the user. The line is the notice's own, verbatim.
 */
function TaskNoticeCard({ notice, focused, digest, onOpen, onClear }: NoticeCardProps) {
  const [full, setFull] = useState(false);
  const left = useTimeLeft(notice.expiresAt);
  const offer = notice.report?.offer ?? null;
  const commits = useTaskCommits(notice, offer === 'PUSH');
  const stuck = notice.kind === 'STUCK';
  const asked = notice.kind === 'QUESTION' && !notice.id.startsWith(ARTIFACT_NOTICE);
  const open: CardAction = { id: 'open', label: NOTICE_ACTIONS[notice.kind] === 'View task' ? 'Open task' : NOTICE_ACTIONS[notice.kind], icon: stuck ? SquareTerminal
    : notice.kind === 'RECOVERY' ? Search : undefined, run: async () => onOpen() };
  if (asked) {
    // The question's whole text comes with the result's facts; the notice holds its first part.
    const question = digest?.report ?? notice.message;
    const verdict = firstSentence(question, 200);
    return (
      <CardRow strip="decide" id={notice.id} focused={focused} urgent={left?.urgent === true} icon={MessageCircleQuestion} tone="warn" verdict={verdict} source={taskSource(notice)}
        side={<StatePill text="Waiting" expiresAt={notice.expiresAt} />}>
        {question.trim() !== verdict && <>
          {full && <div className="assistant-full">{question}</div>}
          <button type="button" className="assistant-fold-toggle" aria-expanded={full} onClick={() => setFull(value => !value)}>
            {full ? <ChevronDown size={12} aria-hidden="true" /> : <ChevronRight size={12} aria-hidden="true" />}{full ? 'Less' : 'Full question'}
          </button>
        </>}
        {offer !== null && <OfferLine notice={notice} offer={offer} commits={commits} />}
        <ReplyBox label="Answer in your own words" placeholder="Or answer in your own words"
          onSend={async (text, key) => {
            await sendToTask(notice.taskId, text, key);
            settleCard({ noticeId: notice.id, icon: MessageSquareReply, lead: `Answered “${firstSentence(text, 60)}”`, rest: notice.title,
              link: { label: 'Open task', open: onOpen } });
          }} />
      </CardRow>
    );
  }
  const verdict = stuck ? 'This turn looks stuck.' : notice.kind === 'QUEUE_PAUSED' ? 'Queued messages are paused.' : notice.kind === 'CI_ATTENTION' ? 'CI Autofix needs you.'
    : firstSentence(notice.message, 200);
  const line = stuck || notice.kind === 'QUEUE_PAUSED' || notice.kind === 'CI_ATTENTION' ? notice.message : notice.message.slice(verdict.length).trim();
  return (
    <CardRow strip="decide" id={notice.id} focused={focused} urgent={left?.urgent === true} icon={NOTICE_ICONS[notice.kind] ?? TriangleAlert} tone="warn" verdict={verdict}
      source={taskSource(notice)} side={<StatePill text={noticeLabel(notice)} expiresAt={notice.expiresAt} urgent={stuck || notice.kind === 'RECOVERY'} />}
      actions={<ActionBar primary={notice.kind === 'QUEUE_PAUSED' || notice.kind === 'CI_ATTENTION' ? null : open}
        secondary={notice.kind === 'QUEUE_PAUSED' || notice.kind === 'CI_ATTENTION' ? [open] : []}
        extra={stuck && notice.turnId !== null ? <StopButton turnId={notice.turnId} /> : undefined} onClear={onClear} />}>
      {line !== '' && <EvidenceLine icon={Activity}>{line}</EvidenceLine>}
    </CardRow>
  );
}

/** An offer that applies to the task, kept apart from the answer: one click sends the task the words for its own tool. */
export function OfferLine({ notice, offer, commits }: { notice: AppNotice; offer: 'PUSH' | 'CLOSE'; commits: number | null }) {
  const { busy, error, run } = useAsyncAction();
  const [key] = useState(() => crypto.randomUUID());
  const { label, words, icon: Icon } = OFFERED[offer];
  const branch = notice.branch ?? 'its branch';
  const text = offer === 'CLOSE' ? 'Its pull request is still open.' : commits !== null && commits > 0 ? `${plural(commits, 'commit')} on ${branch} ${commits === 1 ? 'is' : 'are'} not pushed.`
    : `${branch} is not pushed.`;
  return <div className="assistant-offer">
    <GitCommitHorizontal size={13} aria-hidden="true" />
    <span>{text}</span>
    <button type="button" className="assistant-button" disabled={busy} onClick={() => void run(async () => {
      await sendToTask(notice.taskId, words, key);
      settleCard({ noticeId: notice.id, icon: Icon, lead: offer === 'PUSH' ? `Asked to push ${branch}` : 'Asked to close the pull request', rest: notice.title,
        link: { label: 'Open task', open: () => void window.bridge.openAppNotice(notice.id) } });
    })}><Icon size={13} aria-hidden="true" />{label}</button>
    {error !== null && <div className="assistant-card__fail" role="alert">{error}</div>}
  </div>;
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
    <CardRow nowrap spinning icon={LoaderCircle} tone="accent" verdict={task.title}
      source={[task.repository, task.pullRequestNumber === null ? null : `#${task.pullRequestNumber}`].filter(Boolean).join(' · ')}
      side={step === null ? 'Working' : took !== null && took >= 1000 ? `${step.text} · ${formatDuration(took)}` : step.text}
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
