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
import { useState } from 'react';
import { Eye, LoaderCircle, ScrollText, TriangleAlert } from 'lucide-react';
import { CardState } from './Cards';
import type { AssistantItemDto } from '../types';
import { useAsyncAction } from '../useAsyncAction';

const NAME = /^name:[ \t]*(\S.*)$/m;

/**
 * A rule the assistant offered from the user's own work, with the data behind it. The user may change its text in place; Save rule
 * stores it in Observe, as Check rule reads it, and Not now leaves it unsaved. The assistant saves nothing; once answered, the receipt
 * says what became of it and the card goes.
 */
export default function RuleOfferCard({ item, answered, onAnswered }: {
  item: AssistantItemDto;
  answered: boolean;
  onAnswered: () => void;
}): React.JSX.Element | null {
  const [text, setText] = useState(item.rule ?? '');
  const { busy, error, run } = useAsyncAction();
  if (item.rule == null || answered) return null;
  const answer = (rule: string | null) => run(async () => {
    await window.bridge.answerAssistantRule(item.conversationId, item.id, rule);
    onAnswered();
  });
  return (
    <form className="assistant-draft assistant-rule" aria-label="Offered rule" onSubmit={event => { event.preventDefault(); void answer(text); }}>
      <CardState state="PROPOSAL" />
      <div className="assistant-draft__head">
        <ScrollText size={14} aria-hidden="true" />
        <span className="assistant-rule__name">{NAME.exec(text)?.[1].trim() ?? 'New rule'}</span>
      </div>
      <p className="assistant-rule__why">{item.text}</p>
      <div className="assistant-draft__fields">
        <textarea className="assistant-draft__body assistant-rule__text" aria-label="Rule" value={text} spellCheck={false} disabled={busy}
          onChange={event => setText(event.target.value)} />
        {error !== null && <p className="assistant-draft__error" role="alert"><TriangleAlert size={14} aria-hidden="true" />{error}</p>}
      </div>
      <div className="assistant-draft__actions">
        <Eye size={12} aria-hidden="true" />
        <span className="assistant-draft__note" title="A new rule only records what it would have done, until its record lets it do more">Starts in Observe</span>
        <span className="assistant-spacer" />
        <button type="button" className="assistant-button" disabled={busy} onClick={() => void answer(null)}>Not now</button>
        <button type="submit" className="assistant-button is-primary" aria-busy={busy} disabled={busy || text.trim() === ''}>
          {busy && <span className="bq-spinner is-spinning"><LoaderCircle className="agt-spin" size={12} aria-hidden="true" /></span>}
          {busy ? 'Saving…' : 'Save rule'}
        </button>
      </div>
    </form>
  );
}
