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
import { useEffect, useId, useLayoutEffect, useRef, useState, type RefObject, type TextareaHTMLAttributes } from 'react';
import { BookMarked, BookOpen, Bug, CircleDot, Eraser, FileText, FlaskConical, Gauge, GitBranch, GitPullRequest, ListChecks } from 'lucide-react';
import { errorMessage } from '../errorMessage';
import type { ComposerSuggestionCommand, ComposerSuggestionDto } from '../types';
import { referenceLabel } from './composerReference';

const COMMANDS = [
  { name: 'backport', detail: 'Backport a PR through related workspaces', icon: GitBranch },
  { name: 'fix', detail: 'Fix an open issue', icon: CircleDot },
  { name: 'review', detail: 'Review someone else’s open PR', icon: ListChecks },
  { name: 'contribute', detail: 'Find one small, worthwhile improvement', icon: GitPullRequest },
  { name: 'artifacts', detail: 'Ask about this repository in Artifacts', icon: FileText },
];
/** The backend's TaskCommand.FOCUSES: the word after @contribute that narrows it to one kind of change. */
const FOCUSES = [
  { name: 'bug', detail: 'A problem users hit, with a regression test', icon: Bug },
  { name: 'performance', detail: 'Faster or leaner, with before and after numbers', icon: Gauge },
  { name: 'cleanup', detail: 'Simpler code, same behavior', icon: Eraser },
  { name: 'tests', detail: 'A missing test or a flaky one', icon: FlaskConical },
  { name: 'docs', detail: 'Docs or messages that disagree with the code', icon: BookOpen },
];
const LIST_LABELS: Record<ComposerSuggestionCommand, string> = {
  BACKPORT: 'Merged pull requests', FIX: 'Open issues', REVIEW: 'Open pull requests from others',
};
/** The backend's page size: a full page means there may be more. */
const PAGE_SIZE = 20;
type Cursor = { value: string; caret: number; end: number };
type Search = { command: ComposerSuggestionCommand; repository: string; query: string; start: number; end: number };
type Repository = { id: string; name: string };

/** The word being typed at the caret, as the slice of the draft it replaces when chosen. */
function word(cursor: Cursor, typed: string): { query: string; start: number; end: number } {
  const suffix = /\S$/.test(typed) ? cursor.value.slice(cursor.caret).match(/^\S*/)?.[0] ?? '' : '';
  return { query: (typed + suffix).trim(), start: cursor.caret - typed.length, end: cursor.caret + suffix.length };
}

function itemSearch(cursor: Cursor, backport: boolean, scoped: boolean): Search | null {
  const prefix = cursor.value.slice(0, cursor.caret);
  // Outside a repository, the repository comes between the command and the item.
  const match = (scoped ? /^\s*@(fix|review)[ \t]+(\S+)[ \t]+(\S*)$/i : /^\s*@(backport|fix|review)[ \t]+()(\S*)$/i).exec(prefix);
  if (match === null || (!backport && match[1].toLowerCase() === 'backport')) return null;
  const { query, start, end } = word(cursor, match[3]);
  if (query.length > 500 || /^https?:\/\//i.test(query)) return null;
  return { command: match[1].toUpperCase() as ComposerSuggestionCommand, repository: match[2].toLowerCase(), query, start, end };
}

function repositorySearch(cursor: Cursor): { query: string; start: number; end: number } | null {
  const match = /^\s*@(fix|review|contribute)[ \t]+(\S*)$/i.exec(cursor.value.slice(0, cursor.caret));
  return match === null ? null : word(cursor, match[2]);
}

function focusSearch(cursor: Cursor, scoped: boolean): { query: string; start: number; end: number } | null {
  const match = (scoped ? /^\s*@contribute[ \t]+\S+[ \t]+(\S*)$/i : /^\s*@contribute[ \t]+(\S*)$/i).exec(cursor.value.slice(0, cursor.caret));
  return match === null ? null : word(cursor, match[1]);
}

/**
 * Command completion and repository suggestions share one caret and one keyboard selection. With `repository`, for a composer that
 * lives in no repository, a command takes a watched repository first, and `preferred` (its owner/name or workspace id) leads the list.
 */
export function useTaskSuggestions({ draft, onDraftChange, workspaceId, inputRef, disabled, backport, artifacts, referenceSelected = false, onChooseReference, repository }: {
  draft: string;
  onDraftChange: (draft: string) => void;
  workspaceId?: string;
  inputRef: RefObject<HTMLTextAreaElement | null>;
  disabled: boolean;
  backport: boolean;
  artifacts: boolean;
  referenceSelected?: boolean;
  onChooseReference?: (command: ComposerSuggestionCommand, item: ComposerSuggestionDto) => void;
  repository?: { preferred: string | null };
}) {
  const listId = useId();
  const scoped = repository !== undefined;
  const [repositories, setRepositories] = useState<Repository[] | null>(null);
  const [repositoryVisit, setRepositoryVisit] = useState(0);
  const composing = useRef(false);
  const nextCaret = useRef<number | null>(null);
  const dismissed = useRef<Cursor | null>(null);
  const [cursor, setCursor] = useState<Cursor | null>(null);
  // -1 is the list's default: its first row, or none while an optional focus waits for a typed letter or an arrow key.
  const [chosen, setChosen] = useState(-1);
  const [loaded, setLoaded] = useState<{ key: string; items: ComposerSuggestionDto[]; error: string | null; page: number; more: boolean } | null>(null);
  const [loadingMore, setLoadingMore] = useState<string | null>(null);
  const offered = COMMANDS.filter(entry => entry.name === 'artifacts' ? artifacts : entry.name !== 'backport' || backport);
  const current = !disabled && cursor?.value === draft && cursor.caret === cursor.end ? cursor : null;
  const match = current === null ? null : /^\s*@([^\s@]*)$/.exec(draft.slice(0, current.caret));
  const token = match === null || current === null ? null : (match[1] + (draft.slice(current.caret).match(/^\S*/)?.[0] ?? '')).toLowerCase();
  const commands = token === null ? [] : offered.filter(entry => entry.name.startsWith(token));
  const typed = current === null || referenceSelected ? null : itemSearch(current, backport, scoped);
  const searchWorkspace = !scoped ? workspaceId : repositories?.find(each => each.name.toLowerCase() === typed?.repository)?.id;
  const search = typed === null || !searchWorkspace ? null : typed;
  const command = search?.command;
  const query = search?.query;
  const key = command === undefined ? null : JSON.stringify([searchWorkspace, command, query]);
  const result = loaded?.key === key ? loaded : null;
  const items = result?.items ?? [];
  const more = result !== null && loadingMore === result.key;
  const repositoryWord = !scoped || current === null || search !== null ? null : repositorySearch(current);
  const preferred = repository?.preferred?.toLowerCase();
  const leads = (each: Repository) => each.id === repository?.preferred || each.name.toLowerCase() === preferred;
  const found = repositoryWord === null ? null : (repositories ?? [])
    .filter(each => each.name.toLowerCase().includes(repositoryWord.query.toLowerCase()))
    .sort((a, b) => Number(leads(b)) - Number(leads(a)));
  const focusWord = current === null || search !== null || found !== null ? null : focusSearch(current, scoped);
  // A word that names no focus closes the list, so Enter sends it as the user's own instructions.
  const focuses = focusWord === null ? null : FOCUSES.filter(each => each.name.startsWith(focusWord.query.toLowerCase()));
  const focusing = focuses !== null && focuses.length > 0 ? focuses : null;
  const open = commands.length > 0 || search !== null || found !== null || focusing !== null;
  const count = search !== null ? items.length : found !== null ? found.length : focusing !== null ? focusing.length : commands.length;
  const active = chosen >= 0 && chosen < count ? chosen : focusing !== null && focusWord?.query === '' ? -1 : 0;
  const optionId = (index: number) => `${listId}-${search !== null ? items[index].number : found !== null ? found[index].id
    : focusing !== null ? focusing[index].name : commands[index].name}`;
  const activeId = count > 0 && active >= 0 ? optionId(active) : undefined;

  useEffect(() => {
    if (!scoped) return;
    let live = true;
    // A failed read lists no repositories; the command typed out in full still starts.
    void window.bridge.listWorkspaces().then(
      workspaces => { if (live) setRepositories(workspaces.map(each => ({ id: each.id, name: `${each.monitoredOwner}/${each.monitoredRepo}` }))); },
      () => { if (live) setRepositories([]); },
    );
    return () => { live = false; };
  }, [scoped, repositoryVisit]);

  useEffect(() => {
    if (key === null || command === undefined || query === undefined || !searchWorkspace) return;
    let live = true;
    const timer = window.setTimeout(() => {
      void window.bridge.getComposerSuggestions(searchWorkspace, command, query).then(
        items => { if (live) setLoaded({ key, items, error: null, page: 1, more: items.length === PAGE_SIZE }); },
        failure => { if (live) setLoaded({ key, items: [], error: errorMessage(failure), page: 1, more: false }); },
      );
    }, query === '' ? 0 : 180);
    return () => { live = false; window.clearTimeout(timer); };
  }, [command, key, query, searchWorkspace]);

  const loadMore = () => {
    if (result === null || !result.more || more || command === undefined || query === undefined || !searchWorkspace) return;
    const { key: from, page } = result;
    setLoadingMore(from);
    void window.bridge.getComposerSuggestions(searchWorkspace, command, query, page + 1).then(
      next => setLoaded(current => current?.key !== from ? current : { ...current, page: page + 1, more: next.length === PAGE_SIZE,
        items: [...current.items, ...next.filter(item => !current.items.some(each => each.number === item.number))] }),
      () => setLoaded(current => current?.key !== from ? current : { ...current, more: false }),
    ).finally(() => setLoadingMore(current => current === from ? null : current));
  };

  const update = (input: HTMLTextAreaElement) => {
    const position = { value: input.value, caret: input.selectionStart, end: input.selectionEnd };
    if (composing.current || (dismissed.current?.value === position.value && dismissed.current.caret === position.caret)) return;
    dismissed.current = null;
    setCursor(position);
    if (position.value !== cursor?.value || position.caret !== cursor.caret) setChosen(-1);
  };
  useLayoutEffect(() => {
    if (nextCaret.current === null || inputRef.current === null) return;
    inputRef.current.focus();
    inputRef.current.setSelectionRange(nextCaret.current, nextCaret.current);
    nextCaret.current = null;
    update(inputRef.current);
  });
  useEffect(() => {
    if (activeId !== undefined) document.getElementById(activeId)?.scrollIntoView({ block: 'nearest' });
  }, [activeId]);
  const dismiss = () => {
    dismissed.current = cursor;
    setCursor(null);
  };
  const choose = (index: number) => {
    if (found !== null && repositoryWord !== null) {
      const prefix = draft.slice(0, repositoryWord.start) + found[index].name + ' ';
      nextCaret.current = prefix.length;
      onDraftChange(prefix + draft.slice(repositoryWord.end).trimStart());
    }
    else if (focusing !== null && focusWord !== null) {
      const prefix = draft.slice(0, focusWord.start) + focusing[index].name + ' ';
      nextCaret.current = prefix.length;
      onDraftChange(prefix + draft.slice(focusWord.end).trimStart());
    }
    else if (search === null) {
      const name = commands[index].name;
      nextCaret.current = name.length + 2;
      onDraftChange(`@${name} ${draft.replace(/^\s*@\S*\s*/, '')}`);
    }
    else {
      const prefix = draft.slice(0, search.start) + items[index].url + ' ';
      onChooseReference?.(search.command, items[index]);
      nextCaret.current = onChooseReference === undefined ? prefix.length : search.start + referenceLabel(items[index]).length + 1;
      onDraftChange(prefix + draft.slice(search.end).trimStart());
    }
    setCursor(null);
    setChosen(-1);
  };
  const inputProps: TextareaHTMLAttributes<HTMLTextAreaElement> = {
    'aria-autocomplete': 'list',
    'aria-expanded': open,
    'aria-controls': open ? listId : undefined,
    'aria-activedescendant': activeId,
    onChange: event => { onDraftChange(event.currentTarget.value); update(event.currentTarget); },
    onFocus: event => {
      dismissed.current = null;
      update(event.currentTarget);
      if (scoped) setRepositoryVisit(visit => visit + 1);
    },
    onSelect: event => update(event.currentTarget),
    onBlur: () => setCursor(null),
    onCompositionStart: () => { composing.current = true; setCursor(null); },
    onCompositionEnd: event => { composing.current = false; update(event.currentTarget); },
    onKeyDown: event => {
      if (!open || event.nativeEvent.isComposing || composing.current) return;
      if (event.key === 'Escape') {
        event.preventDefault();
        dismiss();
      }
      else if ((event.key === 'Enter' || event.key === 'Tab') && !event.shiftKey) {
        // An optional focus nobody picked leaves Enter to send and Tab to move on.
        if (active < 0) return;
        // While searching, Enter must never turn an unselected query into a task.
        if (event.key === 'Enter' || count > 0) event.preventDefault();
        if (count > 0) choose(active);
      }
      else if (event.key === 'ArrowDown' && search !== null && active === count - 1 && result?.more) {
        event.preventDefault();
        loadMore();
      }
      else if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
        event.preventDefault();
        if (count > 0) setChosen(active < 0 ? (event.key === 'ArrowDown' ? 0 : count - 1) : (active + (event.key === 'ArrowDown' ? 1 : count - 1)) % count);
      }
    },
  };
  return {
    inputProps,
    list: open && <div className={`agt-mentions${search === null && found === null ? '' : ' is-items'}`} id={listId} role="listbox"
      aria-label={search !== null ? LIST_LABELS[search.command] : found !== null ? 'Repositories' : focusing !== null ? 'Contribution focus' : 'Task suggestions'}
      aria-busy={search !== null ? result === null || more : found !== null && repositories === null}
      onScroll={event => { const list = event.currentTarget; if (search !== null && list.scrollTop + list.clientHeight >= list.scrollHeight - 48) loadMore(); }}>
      {found !== null ? <>
        <div className="agt-mentions__heading">Repositories<span>Search by name</span></div>
        {found.map((each, index) => <button key={each.id} type="button" id={optionId(index)} role="option" aria-selected={index === active}
          tabIndex={-1} onMouseDown={event => event.preventDefault()} onClick={() => choose(index)}>
          <BookMarked size={15} aria-hidden="true" />{each.name}
        </button>)}
        {found.length === 0 && <div className="agt-mentions__status" role="status">{repositories === null ? 'Loading repositories…' : 'No matching repositories'}</div>}
      </> : focusing !== null ? focusing.map((entry, index) => <button key={entry.name} type="button" id={optionId(index)} role="option"
        aria-selected={index === active} tabIndex={-1} onMouseDown={event => event.preventDefault()} onClick={() => choose(index)}>
        <entry.icon size={15} strokeWidth={1.8} aria-hidden="true" />{entry.name}<span>{entry.detail}</span>
      </button>) : search === null ? commands.map((entry, index) => <button key={entry.name} type="button" id={optionId(index)} role="option" aria-selected={index === active}
        tabIndex={-1} onMouseDown={event => event.preventDefault()} onClick={() => choose(index)}>
        <entry.icon size={15} strokeWidth={1.8} aria-hidden="true" />@{entry.name}<span>{entry.detail}</span>
      </button>) : <>
        <div className="agt-mentions__heading">{LIST_LABELS[search.command]}<span>Search by title or number</span></div>
        {items.map((item, index) => <button key={item.number} type="button" title={item.title} id={optionId(index)} role="option" aria-selected={index === active}
          tabIndex={-1} onMouseDown={event => event.preventDefault()} onClick={() => choose(index)}>
          {search.command === 'FIX' ? <CircleDot size={15} aria-hidden="true" /> : <GitPullRequest size={15} aria-hidden="true" />}
          <span className="agt-mentions__item"><strong>{item.title}</strong><span>#{item.number} · {item.author}</span></span>
        </button>)}
        {more && <div className="agt-mentions__status" role="status">Loading more…</div>}
        {items.length === 0 && <div className="agt-mentions__status" role="status">
          {result === null ? 'Loading suggestions…' : result.error !== null ? `Could not load suggestions: ${result.error}` : 'No matching results'}
        </div>}
      </>}
    </div>,
  };
}
