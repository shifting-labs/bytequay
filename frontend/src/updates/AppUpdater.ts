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
import { app, autoUpdater, dialog, type BrowserWindow } from 'electron';
import { newerRelease, RELEASES_URL, releaseVersion, type AppUpdateState } from './contracts';

declare const __BYTEQUAY_SIGNED_RELEASE__: boolean;
const CHECK_INTERVAL = 60 * 60_000;

/** Metadata checks never start Squirrel: its check downloads, so only the user's click may call it. */
export class AppUpdater {
  private state: AppUpdateState = { status: 'idle' };
  private timer?: ReturnType<typeof setInterval>;
  private checking = false;
  private stopped = false;
  private downloaded = false;
  private enabled = false;

  constructor(
    private readonly window: () => BrowserWindow | null,
    private readonly hasRunningWork: () => Promise<boolean>,
    private readonly prepareRestart: () => Promise<void>,
  ) {}

  read(): AppUpdateState { return this.state; }

  start(): void {
    if (this.enabled || !app.isPackaged || process.platform !== 'darwin' || process.mas
      || typeof __BYTEQUAY_SIGNED_RELEASE__ === 'undefined' || !__BYTEQUAY_SIGNED_RELEASE__
      || !['arm64', 'x64'].includes(process.arch) || !releaseVersion(app.getVersion())) return;
    this.enabled = true;
    autoUpdater.on('error', this.failed);
    autoUpdater.on('update-not-available', this.unavailable);
    autoUpdater.on('update-downloaded', this.ready);
    void this.check();
    this.timer = setInterval(() => { void this.check(); }, CHECK_INTERVAL);
    this.timer.unref();
  }

  stop(): void {
    this.stopped = true;
    clearInterval(this.timer);
  }

  private publish(state: AppUpdateState): void {
    this.state = state;
    const window = this.window();
    if (window && !window.isDestroyed()) window.webContents.send('app:update-changed', state);
  }

  async check(): Promise<void> {
    if (!this.enabled || this.stopped || this.checking || !['idle', 'available'].includes(this.state.status)) return;
    this.checking = true;
    try {
      const response = await fetch(`${RELEASES_URL}/latest/download/update-darwin-${process.arch}.json`, {
        signal: AbortSignal.timeout(20_000), cache: 'no-store',
      });
      if (response.status === 404) return;
      if (!response.ok) throw new Error(`Update check returned ${response.status}`);
      const version = newerRelease(await response.json(), app.getVersion(), process.arch);
      // A click while the request was in flight owns the state until it finishes.
      if (!this.stopped && ['idle', 'available'].includes(this.state.status)) {
        this.publish(version === null ? { status: 'idle' } : { status: 'available', version });
      }
    }
    catch (error) { console.warn('Unable to check for a ByteQuay update', error); }
    finally { this.checking = false; }
  }

  async install(): Promise<void> {
    if (!this.enabled || this.stopped || !['available', 'error'].includes(this.state.status) || !('version' in this.state)) return;
    const { version } = this.state;
    this.publish({ status: 'confirming', version });
    try {
      // A failed restart preparation can retry the already verified update without another download or prompt.
      const running = !this.downloaded && await this.hasRunningWork();
      if (this.stopped) return;
      if (running) {
        const window = this.window();
        if (!window || window.isDestroyed()) throw new Error('Open ByteQuay to start the update.');
        const result = await dialog.showMessageBox(window, {
          type: 'warning', title: 'Update ByteQuay', message: 'Update and interrupt running work?',
          detail: `ByteQuay ${version} will download and restart automatically. Running tasks and terminal commands will be interrupted when it restarts, including work started during the download.`,
          buttons: ['Cancel', 'Update and restart'], defaultId: 0, cancelId: 0, noLink: true,
        });
        if (result.response !== 1) { this.publish({ status: 'available', version }); return; }
      }
      if (this.stopped) return;
      if (this.downloaded) { await this.restart(); return; }
      this.publish({ status: 'downloading', version });
      // Pin the feed to the version the user clicked, even if a newer release appears during the download.
      autoUpdater.setFeedURL({ url: `${RELEASES_URL}/download/v${version}/update-darwin-${process.arch}.json` });
      autoUpdater.checkForUpdates();
    }
    catch (error) { this.failed(error); }
  }

  private failed = (error: unknown): void => {
    console.warn('ByteQuay update failed', error);
    if (this.stopped || this.state.status === 'idle') return;
    this.publish({ status: 'error', version: this.state.version, message: this.downloaded
      ? 'The update is verified, but ByteQuay could not finish shutting down. Retry to restart.'
      : 'Could not download or verify the update. ByteQuay is still running. Retry when you’re ready.' });
  };

  private unavailable = (): void => {
    if (this.state.status === 'downloading') this.publish({ status: 'idle' });
  };

  private ready = (): void => {
    if (this.stopped || this.state.status !== 'downloading') return;
    // Squirrel emits this only after verifying the downloaded application's code signature.
    this.downloaded = true;
    void this.restart().catch(this.failed);
  };

  private async restart(): Promise<void> {
    if (this.state.status === 'idle' || this.stopped) return;
    this.publish({ status: 'restarting', version: this.state.version });
    await this.prepareRestart();
    if (!this.stopped) autoUpdater.quitAndInstall();
  }
}
