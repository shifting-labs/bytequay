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
export type AppUpdateState = { status: 'idle' } | {
  status: 'available' | 'confirming' | 'downloading' | 'restarting' | 'error';
  version: string;
  message?: string;
};

export const RELEASES_URL = 'https://github.com/bytequay/bytequay/releases';

export function releaseVersion(value: unknown): number[] | null {
  if (typeof value !== 'string' || !/^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$/.test(value)) return null;
  const parts = value.split('.').map(Number);
  return parts.every(Number.isSafeInteger) ? parts : null;
}

/** Stable releases only; neither old installers nor another architecture can become an update. */
export function newerRelease(value: unknown, current: string, arch: string): string | null {
  if (!value || typeof value !== 'object') throw new Error('Invalid update manifest');
  const entry = value as Record<string, unknown>;
  const next = releaseVersion(entry.version);
  const installed = releaseVersion(current);
  if (!next || !installed || entry.name !== entry.version
    || entry.url !== `${RELEASES_URL}/download/v${entry.version}/ByteQuay-darwin-${arch}-${entry.version}.zip`
    || typeof entry.sha256 !== 'string' || !/^[a-f0-9]{64}$/.test(entry.sha256)
    || !Number.isSafeInteger(entry.size) || Number(entry.size) <= 0) throw new Error('Invalid update manifest');
  const difference = next.findIndex((part, index) => part !== installed[index]);
  return difference >= 0 && next[difference] > installed[difference] ? entry.version as string : null;
}
