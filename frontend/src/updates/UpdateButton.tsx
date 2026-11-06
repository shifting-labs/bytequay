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
import { Download, LoaderCircle, RotateCw } from 'lucide-react';
import type { AppUpdateState } from './contracts';

export default function UpdateButton() {
  const [state, setState] = useState<AppUpdateState>({ status: 'idle' });
  useEffect(() => {
    let live = true;
    let changed = false;
    const unsubscribe = window.bridge.onAppUpdate(next => { changed = true; if (live) setState(next); });
    void window.bridge.readAppUpdate().then(next => { if (live && !changed) setState(next); }).catch(() => {});
    return () => { live = false; unsubscribe(); };
  }, []);
  return <UpdateAction state={state} onClick={() => {
    void window.bridge.installAppUpdate().catch(() => setState(previous => previous.status === 'idle' ? previous
      : { status: 'error', version: previous.version, message: 'Could not start the update. Please retry.' }));
  }} />;
}

export function UpdateAction({ state, onClick }: { state: AppUpdateState; onClick: () => void }) {
  if (state.status === 'idle') return null;
  const busy = !['available', 'error'].includes(state.status);
  const label = state.status === 'available' ? `Update to v${state.version}` : state.status === 'error' ? `Retry v${state.version}`
    : state.status === 'confirming' ? 'Checking running work…' : state.status === 'downloading' ? 'Downloading and verifying…' : 'Restarting…';
  const detail = state.message ?? `Install ByteQuay ${state.version}. The app will restart automatically when the download is verified.`;
  return <div className="app-sidebar__update" aria-live="polite">
    <button type="button" className="app-sidebar__item" aria-label={label} title={detail} disabled={busy} aria-busy={busy}
      onClick={onClick}>
      <span aria-hidden="true" className={busy ? 'bq-spinner is-spinning' : undefined}>
        {busy ? <LoaderCircle size={16} /> : state.status === 'error' ? <RotateCw size={16} /> : <Download size={16} />}
      </span>
      <span className="app-sidebar__label">{label}</span>
    </button>
    {state.message && <p className="app-sidebar__update-error" role="alert">{state.message}</p>}
  </div>;
}
