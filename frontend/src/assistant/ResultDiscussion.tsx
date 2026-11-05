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
import { useMemo, useState } from 'react';
import Composer from '../Composer';
import { DiscussionsPanel } from '../agents/discussions/DiscussionsPanel';
import type { TaskDiscussionDto } from '../agents/discussions/contracts';
import { useTaskDiscussions } from '../agents/discussions/useTaskDiscussions';
import { errorMessage } from '../errorMessage';
import { reviewThreadBridge } from '../pulls/PrivateThread';
import type { AssistantReportDto } from '../types';

/** Questions use the work's private, read-only discussion, anchored by the server to this exact result. */
export function ResultDiscussion({ report }: { report: AssistantReportDto }) {
  const { target, sourceTurnId } = report;
  const [open, setOpen] = useState(false);
  const [question, setQuestion] = useState('');
  const [sending, setSending] = useState(false);
  const [failure, setFailure] = useState<string | null>(null);
  const [created, setCreated] = useState<TaskDiscussionDto | null>(null);
  const focus = useMemo(() => created === null ? null : { id: created.id }, [created]);
  const bridge = useMemo(() => target.kind === 'REVIEW' && target.id !== null ? reviewThreadBridge(target.id) : undefined, [target.kind, target.id]);
  const query = target.id === null || target.workspaceId === null ? null : { taskId: target.id, workspaceId: target.workspaceId };
  const discussions = useTaskDiscussions(query, { active: open, bridge });
  if (sourceTurnId === null || query === null || target.kind !== 'TASK' && target.kind !== 'REVIEW' || report.state === 'PROGRESS') return null;
  const saved = discussions.discussions.filter(discussion => discussion.turnId === sourceTurnId || discussion.id === created?.id);
  const rows = created === null || saved.some(discussion => discussion.id === created.id) ? saved : [...saved, created];
  const ask = async () => {
    if (question.trim() === '' || sending) return;
    setSending(true); setFailure(null);
    try {
      const discussion = await window.bridge.askAssistantResult(target, sourceTurnId, question.trim());
      setCreated(discussion); setQuestion('');
      await discussions.refresh();
    }
    catch (cause) { setFailure(errorMessage(cause)); }
    finally { setSending(false); }
  };
  return <div className="assistant-result-discussion">
    <button type="button" className="assistant-link" aria-expanded={open} onClick={() => setOpen(value => !value)}>{open ? 'Close discussion' : 'Ask about this'}</button>
    {open && <>
      {rows.length > 0 ? <DiscussionsPanel discussions={{ ...discussions, discussions: rows }} repository={target.repo} focus={focus} />
        : <Composer className="assistant-result-question" autoFocus rows={2} aria-label="Ask about this result" placeholder="What would you like to understand?"
          value={question} onChange={event => setQuestion(event.target.value)} disabled={sending} busy={sending}
          onSubmit={() => { void ask(); }} submitShortcut="mod-enter" submitEnabled={!sending && !discussions.busy && question.trim() !== ''} submitLabel="Ask" submitContent="Ask">
          <span className="assistant-dim">Private discussion · reads the result and code</span>
        </Composer>}
      {(failure ?? discussions.error) !== null && <p className="assistant-error" role="alert">{failure ?? discussions.error}</p>}
    </>}
  </div>;
}
