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
import { useEffect, useState } from 'react';
import { subscribeRunningTasks } from '../agents/RunningTasks';
import type { RunningTask } from '../agents/runningTaskModel';
import { doneRatio, showsToday } from '../home/today';
import { useTodayAgenda, type TodayAgenda } from '../home/useTodayAgenda';
import type { AppNotice } from '../notices/contracts';
import { useTimeLeft } from '../useNow';
import type { AgendaDto } from '../types';
import { PILLS, soonestDeadline, waitingCards, type Pill, type WaitingCard } from './model';
import { useDigests } from './useDigests';

const LABELS: Record<Pill, string> = { all: 'All', decision: 'Decision', running: 'Running', finished: 'Results', today: 'Today', mywork: 'My work' };
const TITLES: Record<Pill, string> = {
  all: 'Decision, Running and Results in one view', decision: 'Waiting for your answer, soonest first', running: 'Agent tasks at work',
  finished: 'Results to decide on or one click from done', today: 'Today’s plan', mywork: 'My work',
};

/** Running tasks in every repository, from the read the sidebar shares; null until the first read answers. */
function useRunningTasks(): RunningTask[] | null {
  const [tasks, setTasks] = useState<RunningTask[] | null>(null);
  useEffect(() => subscribeRunningTasks(setTasks), []);
  return tasks;
}

/** `ready` once running tasks and the results' facts were read, so an empty view means none rather than not read yet. */
export type Waiting = { cards: WaitingCard[]; today: TodayAgenda; agenda: AgendaDto | null; ready: boolean };

/** What the pills count and open: the waiting cards, each result with its digest, and today's agenda. */
export function useWaiting(notices: AppNotice[]): Waiting {
  const today = useTodayAgenda();
  const tasks = useRunningTasks();
  const { digests, ready } = useDigests(notices);
  const agenda = today.agenda !== null && today.agenda.state !== 'NONE' && showsToday(today.agenda, 'global') ? today.agenda : null;
  return { cards: waitingCards(notices, tasks ?? [], digests), today, agenda, ready: tasks !== null && ready };
}

/** Results asks for attention only for what is left to decide or one click from done; FYI and Done never count. */
export const counted = (card: WaitingCard): boolean => card.group === 'finished' ? card.lane === 'decide' || card.lane === 'next' : true;

/**
 * The pills under a composer, each a door to one view: a count only where it changes what to do next, zero counts hidden, and an empty
 * pill dim and opening nothing. Decision says when its soonest card stops waiting, in the warning colour near the end; My work shows a
 * dot while Needs you holds work.
 */
export default function AssistantPills({ waiting, needsYou, open, onPick }: {
  waiting: Waiting;
  needsYou: boolean;
  open: Pill | null;
  onPick: (pill: Pill) => void;
}) {
  const { cards, agenda } = waiting;
  const count = (group: WaitingCard['group']) => cards.filter(card => card.group === group && counted(card)).length;
  const soonest = soonestDeadline(cards);
  const left = useTimeLeft(soonest);
  const ratio = agenda === null ? null : doneRatio(agenda.items);
  const counts: Record<Pill, string | null> = {
    all: null, decision: String(count('decision')), running: String(count('running')), finished: String(count('finished')),
    today: ratio === null || ratio.total === 0 ? null : `${ratio.done}/${ratio.total}`, mywork: null,
  };
  // Results with only FYI or Done work still opens, quietly, without a count.
  const empty = (pill: Pill): boolean => pill === 'all' ? cards.length === 0 : pill === 'today' ? agenda === null
    : pill === 'mywork' ? false : pill === 'finished' ? !cards.some(card => card.group === 'finished') : counts[pill] === '0';
  return (
    <div className="assistant-pills" role="group" aria-label="Assistant views">
      {PILLS.map(pill => {
        const none = empty(pill);
        const urgent = pill === 'decision' && left?.urgent === true;
        return (
          <button key={pill} type="button" data-pill={pill} aria-disabled={none} aria-pressed={open === pill}
            className={`assistant-pill${open === pill ? ' is-open' : ''}${none ? ' is-empty' : ''}${urgent ? ' is-urgent' : ''}`}
            title={none ? 'Nothing here' : pill === 'mywork' && needsYou ? 'Needs you has work' : TITLES[pill]}
            onClick={() => { if (!none) onPick(pill); }}>
            {LABELS[pill]}
            {!none && counts[pill] !== null && counts[pill] !== '0' && pill !== 'all' && <span className="assistant-pill__count">{counts[pill]}</span>}
            {pill === 'decision' && left !== null && <><span className="assistant-pill__count">·</span><span className="assistant-pill__left">{left.text}</span></>}
            {pill === 'mywork' && needsYou && <span className="assistant-pill__dot" aria-label="Needs you has work" />}
          </button>
        );
      })}
    </div>
  );
}
