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
import { useAsyncAction } from '../useAsyncAction';
import { useRef, useState } from 'react';
import type { ModelProviderDto } from '../types';
import ProviderRow from './ProviderRow';
import { isClaudeAccount, isClaudeLane, isCodexAccount, type CliAccountLogin } from './model';
import { AddAction, Badge, Dialog, Segmented, SettingsCard } from './primitives';

/** Repository account setup; model overrides select the login used by its new tasks. */
export default function CliAccountsCard({ providerName = 'Claude', providers, checking, onCheck, onSignIn, onAdd, onRemove, repository = false, onRestrict }: {
  providerName?: 'Claude' | 'Codex';
  providers: ModelProviderDto[];
  checking: boolean;
  onCheck: () => void;
  onSignIn?: (providerId: string) => void;
  onAdd?: (label: string, login: CliAccountLogin) => Promise<void>;
  onRemove?: (laneId: string) => Promise<void>;
  repository?: boolean;
  onRestrict?: (laneId: string) => Promise<void>;
}) {
  const [adding, setAdding] = useState(false);
  const [removing, setRemoving] = useState<ModelProviderDto | null>(null);
  const [restricting, setRestricting] = useState<ModelProviderDto | null>(null);
  const [restrictBusy, setRestrictBusy] = useState(false);
  const [restrictError, setRestrictError] = useState<string | null>(null);
  const isAccount = providerName === 'Claude' ? isClaudeAccount : isCodexAccount;
  const accounts = providers.filter(provider => providerName === 'Claude' ? isClaudeLane(provider.id) : isCodexAccount(provider.id));
  if (accounts.length === 0 && onAdd === undefined) return null;
  return <>
    <div className="setw-section"><span className="setw-section-title">{providerName} accounts</span></div>
    <SettingsCard footer={onAdd === undefined ? undefined : <AddAction label={`Add ${providerName} account`} onClick={() => setAdding(true)} />}>
      {accounts.map(provider => (
        <ProviderRow key={provider.id} provider={provider} checking={checking} account onSignIn={onSignIn} onCheck={onCheck}>
          <Badge>{provider.workspaceId == null ? 'global' : 'this repository'}</Badge>
          {onRestrict !== undefined && isAccount(provider.id) && provider.workspaceId == null && (
            <button type="button" className="setw-btn" disabled={checking} onClick={() => { setRestrictError(null); setRestricting(provider); }}>Use only here</button>
          )}
          {onRemove !== undefined && isAccount(provider.id) && <button type="button" className="setw-btn"
            disabled={checking} onClick={() => setRemoving(provider)}>Remove</button>}
        </ProviderRow>
      ))}
    </SettingsCard>
    <div className="setw-footnote">
      {repository && providerName === 'Codex'
        ? 'After setup, choose the account for Development and Review. Accounts added here belong only to this repository; global accounts remain available here.'
        : repository
        ? 'Adding an account selects it for Development and Review in this repository. Repository accounts are hidden and cannot run in other repositories. Global accounts remain available here.'
        : 'Accounts added here are global and available to every repository. Choose one above to use it by default.'}
    </div>
    {adding && onAdd !== undefined && <AddCliAccountDialog providerName={providerName} onAdd={onAdd} onCancel={() => setAdding(false)} />}
    {removing !== null && onRemove !== undefined && <RemoveCliAccountDialog providerName={providerName} provider={removing} onRemove={onRemove} onCancel={() => setRemoving(null)} />}
    {restricting !== null && onRestrict !== undefined && <Dialog title={`Use ${restricting.label} only here?`}
      confirmLabel={restrictBusy ? 'Saving…' : 'Use only here'} confirmDisabled={restrictBusy}
      onCancel={() => { if (!restrictBusy) setRestricting(null); }} onConfirm={() => {
        setRestrictBusy(true);
        void onRestrict(restricting.id).then(() => setRestricting(null))
          .catch(failure => setRestrictError(failure instanceof Error ? failure.message : 'Could not restrict this account'))
          .finally(() => setRestrictBusy(false));
      }}>
      <p>This selects the account for Development and Review here and hides it from other repositories. Existing tasks elsewhere cannot start another turn with it.</p>
      <p>Your saved credentials are preserved.</p>
      {restrictError !== null && <div className="setw-error" role="alert">{restrictError}</div>}
    </Dialog>}
  </>;
}

function AddCliAccountDialog({ providerName, onAdd, onCancel }: { providerName: 'Claude' | 'Codex'; onAdd: (label: string, login: CliAccountLogin) => Promise<void>; onCancel: () => void }) {
  const [kind, setKind] = useState<'email' | 'gateway'>('email');
  const [label, setLabel] = useState('');
  const [email, setEmail] = useState('');
  const [gatewayUrl, setGatewayUrl] = useState('');
  const [token, setToken] = useState('');
  const emailInput = useRef<HTMLInputElement>(null);
  const gatewayInput = useRef<HTMLInputElement>(null);
  const { busy, error, run } = useAsyncAction(failure => failure instanceof Error ? failure.message : `Could not add the ${providerName} account`);
  const gateway = kind === 'gateway';
  const ready = label.trim() !== '' && (gateway ? gatewayUrl.trim() !== '' && token.trim() !== '' : email.trim() !== '');
  const add = async () => {
    if (busy || !ready || !(gateway ? gatewayInput : emailInput).current?.reportValidity()) return;
    return run(async () => {
      await onAdd(label.trim(), gateway ? { gatewayUrl: gatewayUrl.trim(), token: token.trim() } : { email: email.trim() });
      onCancel();
    });
  };
  return <Dialog title={`Add ${providerName} account`} confirmLabel={busy ? 'Adding…' : gateway ? 'Add account' : 'Add and sign in'}
    confirmDisabled={busy || !ready} onConfirm={() => void add()} onCancel={onCancel}>
    <label className="setw-field-label" htmlFor={`${providerName}-account-name`}>Name</label>
    <input id={`${providerName}-account-name`} aria-label={`${providerName} account name`} placeholder="Work or Personal" maxLength={80} value={label}
      disabled={busy} onChange={event => setLabel(event.target.value)} />
    <div className="setw-field-label">Sign in with</div>
    <Segmented label={`${providerName} account sign-in`} value={kind} onChange={setKind}
      options={[{ value: 'email', label: `${providerName} login` }, { value: 'gateway', label: 'Gateway token' }]} />
    {gateway ? <>
      <label className="setw-field-label" htmlFor={`${providerName}-account-gateway`}>Gateway URL</label>
      <input id={`${providerName}-account-gateway`} ref={gatewayInput} aria-label={`${providerName} gateway URL`} type="url" required
        placeholder={providerName === 'Codex' ? 'https://llm.example.com/v1' : 'https://llm.example.com'} maxLength={2048} value={gatewayUrl}
        disabled={busy} onChange={event => setGatewayUrl(event.target.value)} />
      <label className="setw-field-label" htmlFor={`${providerName}-account-token`}>Token</label>
      <input id={`${providerName}-account-token`} aria-label={`${providerName} gateway token`} type="password" className="setw-mono" placeholder="sk-…"
        value={token} disabled={busy} onChange={event => setToken(event.target.value)} />
      <div className="setw-dialog-hint">{providerName === 'Codex' ? 'Use the Responses API base URL, including /v1. Choose a standard Codex model supported by your gateway after adding the account.' : 'Used as ANTHROPIC_BASE_URL and ANTHROPIC_AUTH_TOKEN'} · token stored encrypted on this Mac</div>
    </> : <>
      <label className="setw-field-label" htmlFor={`${providerName}-account-email`}>Email</label>
      <input id={`${providerName}-account-email`} ref={emailInput} aria-label={`${providerName} account email`} type="email" required
        placeholder="you@example.com" autoComplete="email" maxLength={254} value={email}
        disabled={busy} onChange={event => setEmail(event.target.value)} />
      {providerName === 'Codex' && <div className="setw-dialog-hint">Choose this email in the browser. ByteQuay checks it after sign-in. Then select this account for Development and Review.</div>}
    </>}
    {error !== null && <div className="setw-error" role="alert">{error}</div>}
  </Dialog>;
}

function RemoveCliAccountDialog({ providerName, provider, onRemove, onCancel }: {
  providerName: 'Claude' | 'Codex';
  provider: ModelProviderDto;
  onRemove: (laneId: string) => Promise<void>;
  onCancel: () => void;
}) {
  const { busy, error, run } = useAsyncAction(failure => failure instanceof Error ? failure.message : `Could not remove the ${providerName} account`);
  const remove = async () => {
    if (busy) return;
    return run(async () => {
      await onRemove(provider.id);
      onCancel();
    });
  };
  return <Dialog title={`Remove ${provider.label}?`} confirmLabel={busy ? 'Removing…' : 'Remove account'}
    confirmDisabled={busy} onConfirm={() => void remove()} onCancel={() => { if (!busy) onCancel(); }}>
    <p>This removes the account from {provider.workspaceId == null ? 'ByteQuay for every repository' : 'this repository'}. Running turns can finish, but existing tasks cannot start another turn with it. Choose another account for new work.</p>
    <p>{providerName === 'Claude'
      ? 'Saved Claude login and session files remain on this Mac. This does not sign out of Claude or delete its credentials; a gateway account’s saved token is deleted.'
      : 'Saved Codex login and session files remain on this Mac. This does not sign out of Codex; a gateway account’s saved token is deleted.'}</p>
    {error !== null && <div className="setw-error" role="alert">{error}</div>}
  </Dialog>;
}
