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
import { EventEmitter } from 'node:events';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { AppUpdater } from './AppUpdater';
import { newerRelease, RELEASES_URL } from './contracts';

const mocks = vi.hoisted(() => ({
  app: { isPackaged: true, getVersion: () => '0.5.5' },
  native: { on: vi.fn(), setFeedURL: vi.fn(), checkForUpdates: vi.fn(), quitAndInstall: vi.fn() },
  confirm: vi.fn(),
}));
vi.mock('electron', () => ({ app: mocks.app, autoUpdater: mocks.native, dialog: { showMessageBox: mocks.confirm } }));
const manifest = (version = '0.6.0') => ({ version, name: version, url: `${RELEASES_URL}/download/v${version}/ByteQuay-darwin-arm64-${version}.zip`,
  sha256: 'a'.repeat(64), size: 1024 });
let events: EventEmitter;
let fetcher = vi.fn<[], Promise<Response>>();
let updater: AppUpdater;
let running = vi.fn<[], Promise<boolean>>();
let shutdown = vi.fn<[], Promise<void>>();
beforeEach(() => {
  vi.useFakeTimers(); vi.clearAllMocks();
  vi.stubGlobal('__BYTEQUAY_SIGNED_RELEASE__', true);
  vi.stubGlobal('process', { ...process, platform: 'darwin', arch: 'arm64', mas: false });
  mocks.app.isPackaged = true;
  mocks.confirm.mockResolvedValue({ response: 1 });
  events = new EventEmitter();
  mocks.native.on.mockImplementation((event, handler) => events.on(event, handler));
  fetcher = vi.fn(async () => new Response(JSON.stringify(manifest())));
  vi.stubGlobal('fetch', fetcher);
  running = vi.fn<[], Promise<boolean>>().mockResolvedValue(false);
  shutdown = vi.fn<[], Promise<void>>().mockResolvedValue(undefined);
  updater = new AppUpdater(() => ({ isDestroyed: () => false, webContents: { send: vi.fn() } }) as never, running, shutdown);
});
afterEach(() => { updater.stop(); vi.useRealTimers(); vi.restoreAllMocks(); vi.unstubAllGlobals(); });
async function start() { updater.start(); await vi.advanceTimersByTimeAsync(0); }

it('checks metadata on startup and hourly without downloading, and hides equal or older versions', async () => {
  await start();
  expect(updater.read()).toEqual({ status: 'available', version: '0.6.0' });
  expect(mocks.native.checkForUpdates).not.toHaveBeenCalled();
  fetcher.mockResolvedValueOnce(new Response(JSON.stringify(manifest('0.5.5'))));
  await vi.advanceTimersByTimeAsync(60 * 60_000);
  expect(updater.read()).toEqual({ status: 'idle' });
  expect(newerRelease(manifest('0.5.4'), '0.5.5', 'arm64')).toBeNull();
  expect(newerRelease(manifest('0.10.0'), '0.9.9', 'arm64')).toBe('0.10.0');
});
it.each(['0.6.0-beta.1', '01.6.0', '0.6', '9007199254740992.0.0'])('rejects a non-stable or invalid version %s', version => {
  expect(() => newerRelease(manifest(version), '0.5.5', 'arm64')).toThrow();
});
it('rejects foreign downloads, a wrong architecture, absent checksums, and an inconsistent name', () => {
  for (const change of [{ url: 'https://example.test/update.zip' }, { sha256: null }, { size: -1 }, { name: '0.7.0' }]) {
    expect(() => newerRelease({ ...manifest(), ...change }, '0.5.5', 'arm64')).toThrow();
  }
  expect(() => newerRelease(manifest(), '0.5.5', 'x64')).toThrow();
});
it.each(['development', 'unsigned', 'linux', 'mas'])('does not check from %s builds', async mode => {
  if (mode === 'development') mocks.app.isPackaged = false;
  if (mode === 'unsigned') vi.stubGlobal('__BYTEQUAY_SIGNED_RELEASE__', false);
  if (mode === 'linux') vi.stubGlobal('process', { ...process, platform: 'linux' });
  if (mode === 'mas') vi.stubGlobal('process', { ...process, mas: true });
  await start(); await updater.install();
  expect(fetcher).not.toHaveBeenCalled();
  expect(mocks.native.checkForUpdates).not.toHaveBeenCalled();
});
it('downloads only after a click, pins the release, ignores repeat clicks, and restarts after shutdown', async () => {
  await start(); await Promise.all([updater.install(), updater.install()]);
  expect(mocks.confirm).not.toHaveBeenCalled();
  expect(mocks.native.checkForUpdates).toHaveBeenCalledTimes(1);
  expect(mocks.native.setFeedURL).toHaveBeenCalledWith({ url: `${RELEASES_URL}/download/v0.6.0/update-darwin-arm64.json` });
  let finish!: () => void;
  shutdown.mockReturnValue(new Promise<void>(resolve => { finish = resolve; }));
  events.emit('update-downloaded'); events.emit('update-downloaded');
  expect(updater.read().status).toBe('restarting');
  expect(shutdown).toHaveBeenCalledTimes(1);
  expect(mocks.native.quitAndInstall).not.toHaveBeenCalled();
  finish(); await vi.advanceTimersByTimeAsync(0);
  expect(mocks.native.quitAndInstall).toHaveBeenCalledTimes(1);
});
it('confirms running work once before downloading and never asks again before automatic restart', async () => {
  await start(); running.mockResolvedValue(true);
  await updater.install();
  expect(mocks.confirm).toHaveBeenCalledWith(expect.anything(), expect.objectContaining({ buttons: ['Cancel', 'Update and restart'], defaultId: 0 }));
  events.emit('update-downloaded'); await vi.advanceTimersByTimeAsync(0);
  expect(mocks.confirm).toHaveBeenCalledTimes(1);
  expect(mocks.native.quitAndInstall).toHaveBeenCalledTimes(1);
});
it('cancels without downloading or stopping work', async () => {
  await start(); running.mockResolvedValue(true); mocks.confirm.mockResolvedValue({ response: 0 });
  await updater.install();
  expect(updater.read().status).toBe('available');
  expect(mocks.native.checkForUpdates).not.toHaveBeenCalled(); expect(shutdown).not.toHaveBeenCalled();
});
it('fails closed if running work cannot be checked', async () => {
  await start(); running.mockRejectedValue(new Error('Backend offline'));
  await updater.install();
  expect(updater.read().status).toBe('error');
  expect(mocks.native.checkForUpdates).not.toHaveBeenCalled(); expect(shutdown).not.toHaveBeenCalled();
});
it('keeps the installed app running on a native download or signature error and allows retry', async () => {
  await start(); await updater.install(); events.emit('error', new Error('Invalid signature'));
  expect(updater.read().status).toBe('error'); expect(shutdown).not.toHaveBeenCalled();
  events.emit('update-downloaded');
  expect(mocks.native.quitAndInstall).not.toHaveBeenCalled();
  await updater.install(); expect(mocks.native.checkForUpdates).toHaveBeenCalledTimes(2);
});
it('retries a failed shutdown without downloading or confirming again', async () => {
  await start(); running.mockResolvedValue(true); await updater.install();
  shutdown.mockRejectedValueOnce(new Error('Backend still stopping'));
  events.emit('update-downloaded'); await vi.advanceTimersByTimeAsync(0);
  expect(updater.read().status).toBe('error'); expect(mocks.native.quitAndInstall).not.toHaveBeenCalled();
  await updater.install();
  expect(mocks.native.checkForUpdates).toHaveBeenCalledTimes(1); expect(mocks.confirm).toHaveBeenCalledTimes(1);
  expect(mocks.native.quitAndInstall).toHaveBeenCalledTimes(1);
});
it('does not let a late metadata response replace a download or restart after quit', async () => {
  await start();
  let response!: (value: Response) => void;
  fetcher.mockReturnValue(new Promise<Response>(resolve => { response = resolve; }));
  const pending = updater.check();
  await updater.install(); response(new Response(JSON.stringify(manifest('0.7.0')))); await pending;
  expect(updater.read()).toEqual({ status: 'downloading', version: '0.6.0' });
  updater.stop(); events.emit('update-downloaded'); expect(shutdown).not.toHaveBeenCalled();
});
it('stays quiet when a metadata check fails or the first updater release does not exist yet', async () => {
  fetcher.mockResolvedValueOnce(new Response(null, { status: 404 })); await start();
  expect(updater.read()).toEqual({ status: 'idle' });
  fetcher.mockRejectedValueOnce(new Error('Offline')); await updater.check();
  expect(updater.read()).toEqual({ status: 'idle' });
});
