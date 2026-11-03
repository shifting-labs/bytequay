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
import { mkdir, mkdtemp, readdir, rm } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { afterEach, expect, it } from 'vitest';
import { editorPartition, removeForgottenEditorStorage } from './editorStorage';

let root = '';
afterEach(async () => { await rm(root, { recursive: true, force: true }); });

async function layout(): Promise<{ userData: string; profile: string }> {
  root = await mkdtemp(path.join(os.tmpdir(), 'bytequay-editor-storage-'));
  const userData = path.join(root, 'user-data');
  const profiles = path.join(root, 'agent-runtime', 'ide');
  for (const task of ['open-task', 'kept-task']) await mkdir(path.join(profiles, task, 'abc123'), { recursive: true });
  for (const name of ['bytequay-ide-open-task', 'bytequay-ide-kept-task', 'bytequay-ide-gone-task', 'inapp-browser']) {
    await mkdir(path.join(userData, 'Partitions', name, 'Cache'), { recursive: true });
  }
  return { userData, profile: path.join(profiles, 'open-task', 'abc123') };
}

it('removes only the editor storage of tasks whose IDE profile is gone', async () => {
  const { userData, profile } = await layout();
  await removeForgottenEditorStorage(userData, profile, 'open-task');
  expect((await readdir(path.join(userData, 'Partitions'))).sort()).toEqual(['bytequay-ide-kept-task', 'bytequay-ide-open-task', 'inapp-browser']);
  expect(editorPartition('open-task')).toBe('persist:bytequay-ide-open-task');
});

it('removes nothing when the profile path is not laid out as expected', async () => {
  const { userData, profile } = await layout();
  await removeForgottenEditorStorage(userData, profile, 'another-task');
  await removeForgottenEditorStorage(userData, path.join(root, 'elsewhere', 'open-task', 'abc123'), 'open-task');
  expect(await readdir(path.join(userData, 'Partitions'))).toHaveLength(4);
});
