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
import { LockKeyhole, MessageCircleQuestion, X } from 'lucide-react';
import Composer from '../Composer';
import { IconButton } from '../cards/Card';
import { DiscussionsPanel } from '../agents/discussions/DiscussionsPanel';
import type { TaskDiscussionDto } from '../agents/discussions/contracts';
import { useTaskDiscussions } from '../agents/discussions/useTaskDiscussions';
import { errorMessage } from '../errorMessage';
import { reviewThreadBridge } from '../pulls/PrivateThread';
import type { AssistantReportDto } from '../types';

/** A finished task's or review's result, whose turn its private discussion is anchored to, can be asked about. */
export const askable = ({ target, sourceTurnId, state }: AssistantReportDto): boolean =>
  sourceTurnId !== null && target.id !== null && target.workspaceId !== null && (target.kind === 'TASK' || target.kind === 'REVIEW') && state !== 'PROGRESS';

/**
 * Questions use the work's private, read-only discussion, anchored by the server to this exact result. It opens with questions drawn
 * from the card, each sent with one click, and says what it can see.
 */
export function ResultDiscussion({ report, suggestions, scope, placeholder, onClose }: {
  report: AssistantReportDto;
  suggestions: string[];
  /** The panel's heading, such as "Ask about this review". */
  scope: string;
  placeholder: string;
  onClose: () => void;
}) {
  const { target, sourceTurnId } = report;
  const [question, setQuestion] = useState('');
  const [sending, setSending] = useState(false);
  const [failure, setFailure] = useState<string | null>(null);
  const [created, setCreated] = useState<TaskDiscussionDto | null>(null);
  const focus = useMemo(() => created === null ? null : { id: created.id }, [created]);
  const bridge = useMemo(() => target.kind === 'REVIEW' && target.id !== null ? reviewThreadBridge(target.id) : undefined, [target.kind, target.id]);
  const query = target.id === null || target.workspaceId === null ? null : { taskId: target.id, workspaceId: target.workspaceId };
  const discussions = useTaskDiscussions(query, { bridge });
  if (sourceTurnId === null || query === null || !askable(report)) return null;
  const saved = discussions.discussions.filter(discussion => discussion.turnId === sourceTurnId || discussion.id === created?.id);
  const rows = created === null || saved.some(discussion => discussion.id === created.id) ? saved : [...saved, created];
  const ask = async (text: string) => {
    if (text.trim() === '' || sending) return;
    setSending(true); setFailure(null);
    try {
      const discussion = await window.bridge.askAssistantResult(target, sourceTurnId, text.trim());
      setCreated(discussion); setQuestion('');
      await discussions.refresh();
    }
    catch (cause) { setFailure(errorMessage(cause)); }
    finally { setSending(false); }
  };
  return <div className="assistant-ask" role="group" aria-label={scope}>
    <div className="assistant-ask__head">
      <MessageCircleQuestion size={13} aria-hidden="true" /><span>{scope}</span><span className="assistant-spacer" />
      <IconButton label="Close" title="Close" onClick={onClose}><X size={13} aria-hidden="true" /></IconButton>
    </div>
    {rows.length > 0 ? <DiscussionsPanel discussions={{ ...discussions, discussions: rows }} repository={target.repo} focus={focus} /> : <>
      {suggestions.length > 0 && <div className="assistant-options">
        {suggestions.map(suggestion => <button key={suggestion} type="button" className="assistant-option" disabled={sending} onClick={() => void ask(suggestion)}>{suggestion}</button>)}
      </div>}
      <Composer className="assistant-result-question" autoFocus={suggestions.length === 0} rows={2} aria-label={scope} placeholder={placeholder}
        value={question} onChange={event => setQuestion(event.target.value)} disabled={sending} busy={sending}
        onSubmit={() => { void ask(question); }} submitShortcut="mod-enter" submitEnabled={!sending && !discussions.busy && question.trim() !== ''} submitLabel="Ask" submitContent="Ask">
        <span className="assistant-dim assistant-ask__scope"><LockKeyhole size={11} aria-hidden="true" />Reads the result and the code · private</span>
      </Composer>
    </>}
    {(failure ?? discussions.error) !== null && <p className="assistant-error" role="alert">{failure ?? discussions.error}</p>}
  </div>;
}
