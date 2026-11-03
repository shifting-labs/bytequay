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
import { access, readdir, rm } from 'node:fs/promises';
import path from 'node:path';

const PREFIX = 'bytequay-ide-';

/** Each task's editor keeps its own browser storage, so the bundle cache that speeds a resume belongs to that task. */
export function editorPartition(taskId: string): string {
  return `persist:${PREFIX}${taskId}`;
}

/**
 * Removes the editor storage of tasks whose backend IDE profile is gone. The backend deletes a profile with its task's
 * worktree, and the storage left behind is mostly that task's cached editor bundle, often a few hundred megabytes.
 * `profile` is the opening editor's own profile (`<profiles>/<taskId>/<commit>`); a different layout sweeps nothing.
 */
export async function removeForgottenEditorStorage(userData: string, profile: string, taskId: string): Promise<void> {
  const taskProfile = path.dirname(profile);
  if (path.basename(taskProfile) !== taskId || !await present(taskProfile)) return;
  const profiles = path.dirname(taskProfile);
  const partitions = path.join(userData, 'Partitions');
  const names = await readdir(partitions).catch(() => [] as string[]);
  await Promise.all(names.filter(name => name.startsWith(PREFIX)).map(async name => {
    if (!await present(path.join(profiles, name.slice(PREFIX.length)))) await rm(path.join(partitions, name), { recursive: true, force: true });
  }));
}

// Only a profile that is certainly missing counts as gone; an unreadable one keeps its storage.
function present(file: string): Promise<boolean> {
  return access(file).then(() => true, (failure: unknown) => (failure as NodeJS.ErrnoException).code !== 'ENOENT');
}
