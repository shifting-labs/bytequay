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
import { renderToStaticMarkup } from 'react-dom/server';
import { expect, it, vi } from 'vitest';
import { UpdateAction } from './UpdateButton';

it('omits the update row entirely when current', () => {
  expect(renderToStaticMarkup(<UpdateAction state={{ status: 'idle' }} onClick={vi.fn()} />)).toBe('');
});
it('exposes the new version and automatic restart even when the sidebar label is collapsed', () => {
  const markup = renderToStaticMarkup(<UpdateAction state={{ status: 'available', version: '0.6.0' }} onClick={vi.fn()} />);
  expect(markup).toContain('aria-label="Update to v0.6.0"');
  expect(markup).toContain('restart automatically'); expect(markup).not.toContain('disabled=""');
});
it.each(['confirming', 'downloading', 'restarting'] as const)('prevents repeat actions while %s', status => {
  const markup = renderToStaticMarkup(<UpdateAction state={{ status, version: '0.6.0' }} onClick={vi.fn()} />);
  expect(markup).toContain('disabled=""'); expect(markup).toContain('aria-busy="true"');
});
it('makes update failures readable and retryable', () => {
  const markup = renderToStaticMarkup(<UpdateAction state={{ status: 'error', version: '0.6.0', message: 'Could not verify the update.' }} onClick={vi.fn()} />);
  expect(markup).toContain('role="alert"'); expect(markup).toContain('Retry v0.6.0'); expect(markup).not.toContain('disabled=""');
});
