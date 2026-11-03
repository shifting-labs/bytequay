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
import { useRef, useState } from 'react';
import { Check, Clock, Minus, Pencil } from 'lucide-react';
import { plural, shortDate } from '@bytequay/ui/displayText';
import { errorMessage } from '../errorMessage';
import { useAsyncAction } from '../useAsyncAction';
import type {
  AgentRuleAction, AgentRuleAgreement, AgentRuleDefinitionDto, AgentRuleDto, AgentRuleEvent, AgentRuleMode, AgentRuleRunDto, AgentRuleVerdict,
} from '../types';
import { AddAction, Dialog, SettingsCard, SettingsRow, Toggle } from './primitives';

/** Each event in the words a rule's row and its check show. */
export const RULE_EVENTS: Record<AgentRuleEvent, string> = {
  REVIEW_REQUESTED: 'A review is requested from you',
  PULL_REQUEST_UPDATED: 'A pull request gets new commits',
  ISSUE_ASSIGNED: 'An issue is assigned to you',
  PULL_REQUEST_BEHIND: 'Your pull request falls behind its base',
  PULL_REQUEST_MERGED: 'Your pull request is merged',
};

const RULE_ACTIONS: Record<AgentRuleAction, string> = {
  REVIEW: 'Reviews the pull request',
  TASK: 'Starts a task',
  REBASE: 'Rebases the branch',
  BACKPORT: 'Prepares the backport',
};

const RULE_AGREEMENTS: Record<AgentRuleAgreement, string> = {
  REVIEWED: 'you review it',
  STARTED_TASK: 'you start a task on it',
  UPDATED_BRANCH: 'you update its branch',
  OPENED_BACKPORT: 'you open its backport',
};

const MODES: Record<AgentRuleMode, string> = { OBSERVE: 'Observe', SUGGEST: 'Suggest', AUTO: 'Auto' };

const VERDICTS: Record<AgentRuleVerdict, { label: string; icon: typeof Check }> = {
  AGREED: { label: 'You agreed', icon: Check },
  DISAGREED: { label: 'You did not, in time', icon: Minus },
  PENDING: { label: 'Waiting for you', icon: Clock },
};

const EXAMPLE = `---
name: Review PRs that need me
when: review-requested
does: review
agrees-when: reviewed
---
Review it for correctness first, then nits. Draft comments only.`;

/** Where a rule applies beyond its event, in the order its header names them. */
function filters(rule: AgentRuleDefinitionDto): string {
  return [
    rule.repositories.length > 0 ? `in ${rule.repositories.join(', ')}` : '',
    rule.paths.length > 0 ? `touching ${rule.paths.join(', ')}` : '',
    rule.labels.length > 0 ? `labeled ${rule.labels.join(', ')}` : '',
    rule.authors.length > 0 ? `by ${rule.authors.join(', ')}` : '',
  ].filter(part => part !== '').join(' · ');
}

/** "12 runs · 83% agree", out of the runs you answered; nothing before the first run. */
function record(rule: AgentRuleDto): string {
  if (rule.runs === 0) return '';
  const answered = rule.agreed + rule.disagreed;
  return answered === 0 ? plural(rule.runs, 'run') : `${plural(rule.runs, 'run')} · ${Math.round(rule.agreed * 100 / answered)}% agree`;
}

export default function RulesPage({ rules, busy, error, onCheck, onRecord, onSave, onPause, onRemove }: {
  rules: AgentRuleDto[];
  busy: boolean;
  error: string | null;
  /** What a text says as a rule, or a rejection saying why it is not one. */
  onCheck: (text: string) => Promise<AgentRuleDefinitionDto>;
  onRecord: (id: string) => Promise<AgentRuleRunDto[]>;
  onSave: (text: string, id: string | null) => Promise<void>;
  onPause: (rule: AgentRuleDto) => void;
  onRemove: (rule: AgentRuleDto) => void;
}) {
  const [editing, setEditing] = useState<AgentRuleDto | 'new' | null>(null);
  const [runs, setRuns] = useState<AgentRuleRunDto[]>([]);
  // The record of the rule whose box is open, so a slow read for a box since closed does not fill the next one.
  const opened = useRef<string | null>(null);
  const edit = (rule: AgentRuleDto) => {
    opened.current = rule.id;
    setRuns([]);
    setEditing(rule);
    onRecord(rule.id).then(read => { if (opened.current === rule.id) setRuns(read); }, () => undefined);
  };
  const close = () => {
    opened.current = null;
    setEditing(null);
  };

  return (
    <div className="setw-page">
      <h1>Rules</h1>
      {error !== null && <div className="setw-error" role="alert">{error}</div>}

      <div className="setw-section">
        <span className="setw-section-title">Your rules</span>
      </div>

      <SettingsCard footer={<AddAction label="Paste a rule" onClick={() => setEditing('new')} />}>
        {rules.map(rule => (
          <SettingsRow
            key={rule.id}
            title={rule.rule.name}
            subtitle={[RULE_EVENTS[rule.rule.event], record(rule), rule.paused ? 'Paused' : MODES[rule.mode]].filter(part => part !== '').join(' · ')}
          >
            <button
              type="button"
              className="setw-btn is-quiet"
              aria-label={`Edit ${rule.rule.name}`}
              disabled={busy}
              onClick={() => edit(rule)}
            >
              <Pencil size={12} strokeWidth={2} />
              <span>Edit</span>
            </button>
            <Toggle on={!rule.paused} label={`Run ${rule.rule.name}`} disabled={busy} onChange={() => onPause(rule)} />
          </SettingsRow>
        ))}
        {rules.length === 0 && <div className="setw-row-empty">No rules yet</div>}
      </SettingsCard>
      <div className="setw-footnote">A new rule starts in Observe · its record decides when it may do more</div>

      {editing !== null && (
        <RuleDialog
          rule={editing === 'new' ? null : editing}
          runs={editing === 'new' ? [] : runs}
          onCheck={onCheck}
          onCancel={close}
          onSave={async text => { await onSave(text, editing === 'new' ? null : editing.id); close(); }}
          onRemove={editing === 'new' ? undefined : () => { close(); onRemove(editing); }}
        />
      )}
    </div>
  );
}

function RuleDialog({ rule, runs, onCheck, onSave, onCancel, onRemove }: {
  rule: AgentRuleDto | null;
  runs: AgentRuleRunDto[];
  onCheck: (text: string) => Promise<AgentRuleDefinitionDto>;
  onSave: (text: string) => Promise<void>;
  onCancel: () => void;
  onRemove?: () => void;
}) {
  const [text, setText] = useState(rule?.text ?? '');
  const [checked, setChecked] = useState<AgentRuleDefinitionDto | null>(null);
  const [problem, setProblem] = useState<string | null>(null);
  // A reply to an older text would describe what is no longer in the box, so only the latest text's reply is shown.
  const latest = useRef(text);
  const { busy, error, run } = useAsyncAction();
  const ready = !busy && text.trim() !== '';
  const check = () => {
    setProblem(null);
    const asked = text;
    onCheck(asked).then(
      definition => { if (latest.current === asked) setChecked(definition); },
      (cause: unknown) => { if (latest.current === asked) { setChecked(null); setProblem(errorMessage(cause)); } },
    );
  };

  return (
    <Dialog
      title={rule === null ? 'Paste a rule' : 'Edit rule'}
      confirmLabel="Save rule"
      confirmDisabled={!ready}
      destructive={onRemove === undefined ? undefined : (
        <button type="button" className="setw-btn is-danger" disabled={busy} onClick={onRemove}>Remove rule</button>
      )}
      onConfirm={() => { if (ready) void run(() => onSave(text)); }}
      onCancel={() => { if (!busy) onCancel(); }}
    >
      {error !== null && <div className="setw-error" role="alert">{error}</div>}
      <textarea
        className="setw-rule-text"
        rows={12}
        value={text}
        disabled={busy}
        aria-label="Rule text"
        maxLength={20000}
        placeholder={EXAMPLE}
        onChange={event => { latest.current = event.target.value; setText(event.target.value); setChecked(null); setProblem(null); }}
      />
      <div className="setw-rule-actions">
        <button type="button" className="setw-btn" disabled={!ready} onClick={check}>Check rule</button>
      </div>
      {problem !== null && <div className="setw-error" role="alert">{problem}</div>}
      {checked !== null && (
        <div className="setw-rule-check" role="status" aria-label="What the rule says">
          <div className="setw-rule-name">{checked.name}</div>
          <div>When: {RULE_EVENTS[checked.event]}{filters(checked) === '' ? '' : ` · ${filters(checked)}`}</div>
          <div>Does: {RULE_ACTIONS[checked.action]}</div>
          <div>Agrees when {RULE_AGREEMENTS[checked.agreement]} within {checked.withinDays} days</div>
        </div>
      )}
      {rule !== null && runs.length > 0 && (
        <div className="setw-rule-record" aria-label="Record">
          <div className="setw-rule-record-title">Record since {shortDate(rule.recordSince)}</div>
          {runs.map(run => {
            const verdict = VERDICTS[run.verdict];
            return (
              <div key={`${run.subject} ${run.occurredAt}`} className="setw-rule-run">
                <span className={`setw-rule-run-verdict is-${run.verdict.toLowerCase()}`} role="img" aria-label={verdict.label} title={verdict.label}>
                  <verdict.icon size={12} strokeWidth={2} />
                </span>
                <span className="setw-rule-run-subject">{run.subject}</span>
                <span className="setw-rule-run-title">{run.title}</span>
                <span className="setw-rule-run-date">{shortDate(run.occurredAt)}</span>
              </div>
            );
          })}
        </div>
      )}
      <div className="setw-dialog-hint">
        {rule === null ? 'A saved rule starts in Observe' : 'A changed header starts a new record in Observe; a new name or new instructions keep it'}
      </div>
    </Dialog>
  );
}
