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
import { useEffect, useMemo, useState } from 'react';
import { pollVisible } from '../ide/pollVisible';
import type { AppNotice } from '../notices/contracts';
import { digestRequest, parseDigests, type ResultDigest } from './results';

/** Checks finish and reviews land between notice changes, so a shown result reads its facts again this often. */
const DIGEST_POLL_MS = 20_000;

export type Digests = { digests: ReadonlyMap<string, ResultDigest>; ready: boolean };

/**
 * The digest of every result notice, read again when the notices change and while the window shows; `ready` once the first read answered,
 * so a card does not move between groups as its facts arrive. A failed read keeps the last facts quietly, as a background poll does.
 */
export function useDigests(notices: AppNotice[]): Digests {
  const requests = useMemo(() => notices.flatMap(notice => {
    const request = digestRequest(notice);
    return request === null ? [] : [request];
  }), [notices]);
  // The same notices at the same moments ask the same question, whatever object holds them.
  const key = JSON.stringify(requests.map(request => [request, notices.find(notice => notice.id === request.noticeId)?.occurredAt]));
  const [state, setState] = useState<{ key: string | null; digests: ReadonlyMap<string, ResultDigest> }>({ key: null, digests: new Map() });

  useEffect(() => {
    if (requests.length === 0) {
      setState(current => current.key === key ? current : { key, digests: new Map() });
      return;
    }
    let live = true;
    const stop = pollVisible(async () => {
      try {
        const read = parseDigests(await window.bridge.readNoticeDigests(requests));
        if (live) setState({ key, digests: new Map(read.map(digest => [digest.noticeId, digest])) });
      }
      catch {
        if (live) setState(current => current.key === key ? current : { key, digests: current.digests });
        // ponytail: the facts of the last good read stand until the next poll; a card without one falls back to its notice's words.
      }
    }, DIGEST_POLL_MS);
    return () => {
      live = false;
      stop();
    };
    // The key covers what the requests say; the array itself is new on each render.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key]);

  return { digests: state.digests, ready: state.key === key };
}
