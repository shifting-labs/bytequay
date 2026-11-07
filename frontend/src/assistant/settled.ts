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
import { useSyncExternalStore } from 'react';
import type { LucideIcon } from 'lucide-react';
import { localDay } from './model';

/*
 * What the user did from a card, kept for the app's session: the card collapses to its receipt until the next read drops its notice, and
 * the receipt then joins Done today. A Clear waits ten seconds for Undo before the notice goes.
 * ponytail: held in memory, so Done today forgets acted cards on restart; persist receipts if that day view matters across launches.
 */

export type ReceiptLink = { label: string; open: () => void; external?: boolean };

export type Receipt = {
  noticeId: string;
  icon: LucideIcon;
  tone?: 'success' | 'purple' | 'muted';
  /** What happened, such as "Approved". */
  lead: string;
  /** On what, such as "PR #58 Add rate-limit headers · at a1b2c3d". */
  rest: string;
  link?: ReceiptLink;
  /** Takes a private, local change back, such as a dismissed finding. */
  undo?: () => Promise<void>;
  at: number;
};

const CLEAR_MS = 10_000;

const receipts = new Map<string, Receipt>();
const clearing = new Map<string, { title: string; timer: ReturnType<typeof setTimeout> }>();
const listeners = new Set<() => void>();
let version = 0;

const changed = () => {
  version += 1;
  listeners.forEach(listener => listener());
};

const subscribe = (listener: () => void) => {
  listeners.add(listener);
  return () => { listeners.delete(listener); };
};

/** Re-renders when a receipt or a pending clear changes. */
export function useSettled(): number {
  return useSyncExternalStore(subscribe, () => version);
}

/** The card's action happened: its receipt shows in its place, and its notice goes so it does not ask again. */
export function settle(receipt: Receipt, remove: (noticeId: string) => Promise<void>): void {
  receipts.set(receipt.noticeId, receipt);
  changed();
  void remove(receipt.noticeId).catch(() => {});
}

/** A receipt whose private change was taken back: the card returns as it was. */
export function unsettle(noticeId: string): void {
  if (receipts.delete(noticeId)) changed();
}

export const receiptOf = (noticeId: string): Receipt | undefined => receipts.get(noticeId);

/** Today's receipts, newest first, for Done today. */
export function settledToday(now = Date.now()): Receipt[] {
  const today = localDay(now);
  return [...receipts.values()].filter(receipt => localDay(receipt.at) === today).sort((left, right) => right.at - left.at);
}

/** Clears the card after ten seconds unless Undo comes first; the timer outlives the panel. */
export function clearLater(noticeId: string, title: string, remove: (noticeId: string) => Promise<void>): void {
  if (clearing.has(noticeId)) return;
  const timer = setTimeout(() => {
    clearing.delete(noticeId);
    changed();
    void remove(noticeId).catch(() => {});
  }, CLEAR_MS);
  clearing.set(noticeId, { title, timer });
  changed();
}

export function undoClear(noticeId: string): void {
  const pending = clearing.get(noticeId);
  if (pending === undefined) return;
  clearTimeout(pending.timer);
  clearing.delete(noticeId);
  changed();
}

export const clearingTitle = (noticeId: string): string | undefined => clearing.get(noticeId)?.title;
