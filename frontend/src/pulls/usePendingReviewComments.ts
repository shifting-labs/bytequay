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
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { errorMessage } from '../errorMessage';
import type { InlineReviewCommentDto } from '../types';
import type { ReviewDraftDto } from './agent-review/contracts';
import { usePullRequestBridge, usePullRequestRoute } from './PullRequestWorkspace';

const CHANGED = 'bytequay:review-draft-changed';

export function reviewDraftChanged(): void {
  window.dispatchEvent(new Event(CHANGED));
}

/** Staging writes the existing local draft, so leaving either PR surface cannot discard comments. */
export function usePendingReviewComments(target: { repo: string; number: number } | null) {
  const bridge = usePullRequestBridge();
  const route = usePullRequestRoute();
  const repo = target?.repo ?? null;
  const number = target?.number ?? null;
  const key = JSON.stringify([route, repo?.toLowerCase(), number]);
  const [loaded, setLoaded] = useState<{ key: string; draft: ReviewDraftDto } | null>(null);
  const [error, setError] = useState<string | null>(null);
  const request = useRef(0);
  const saving = useRef(false);
  const refresh = useCallback(async () => {
    const ticket = ++request.current;
    if (repo === null || number === null) return;
    try {
      const draft = await bridge.getReviewDraft(repo, number);
      if (ticket === request.current) { setLoaded({ key, draft }); setError(null); }
    }
    catch (failure) {
      if (ticket === request.current) setError(`Could not load pending review comments. ${errorMessage(failure)}`);
    }
  }, [bridge, key, number, repo]);
  useEffect(() => {
    setError(null);
    void refresh();
    const changed = () => { void refresh(); };
    window.addEventListener(CHANGED, changed);
    return () => { window.removeEventListener(CHANGED, changed); };
  }, [refresh]);
  const draft = loaded?.key === key ? loaded.draft : null;
  const comments = useMemo(() => draft?.items
    .filter(item => item.source === 'MANUAL' && item.included && !item.published && item.publication === null)
    .map(item => ({ ...item, draftItemId: item.id, startSide: item.startLine === null ? null : item.side })) ?? [], [draft]);

  const onChange = async (next: InlineReviewCommentDto[]) => {
    if (repo === null || number === null || draft === null) throw new Error('Wait for the saved review draft to load, then try again.');
    if (saving.current) throw new Error('Wait for the pending comment to finish saving.');
    saving.current = true;
    request.current++;
    let saved = draft;
    try {
      for (const comment of comments) {
        const replacement = next.find(item => item.draftItemId === comment.draftItemId);
        if (replacement === undefined) saved = await bridge.removeReviewDraftComment(repo, number, saved.version, comment.draftItemId);
        else if (replacement.body !== comment.body) saved = await bridge.editReviewDraftComment(repo, number, saved.version, comment.draftItemId, replacement.body, true);
      }
      for (const comment of next.filter(item => item.draftItemId === undefined)) {
        saved = await bridge.addReviewDraftComment(repo, number, saved.version, comment);
      }
    }
    finally {
      setLoaded(current => current?.key === key && saved.version >= current.draft.version ? { key, draft: saved } : current);
      saving.current = false;
      reviewDraftChanged();
    }
  };
  return { comments, onChange, error, refresh };
}
