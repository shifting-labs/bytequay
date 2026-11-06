import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { mkdtemp, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';
import { makeUpdateManifest } from './macos-update-manifest.mjs';

test('pins a release ZIP by tag and architecture, with its exact size and digest', async () => {
  const folder = await mkdtemp(join(tmpdir(), 'bytequay-update-'));
  try {
    for (const arch of ['arm64', 'x64']) {
      const zip = join(folder, `ByteQuay-darwin-${arch}-0.6.0.zip`);
      const bytes = Buffer.from('fixture ZIP bytes'); await writeFile(zip, bytes);
      const manifest = await makeUpdateManifest(zip, '0.6.0', 'v0.6.0');
      assert.equal(manifest.url, `https://github.com/bytequay/bytequay/releases/download/v0.6.0/ByteQuay-darwin-${arch}-0.6.0.zip`);
      assert.equal(manifest.name, manifest.version);
      assert.equal(manifest.size, bytes.length);
      assert.equal(manifest.sha256, createHash('sha256').update(bytes).digest('hex'));
      await assert.rejects(makeUpdateManifest(zip, '0.6.0', 'v0.7.0'), /tag must match/);
      await assert.rejects(makeUpdateManifest(zip, '0.7.0', 'v0.7.0'), /ZIP must match/);
    }
    await assert.rejects(makeUpdateManifest(join(folder, 'unknown.zip'), '0.6.0', 'v0.6.0'), /ZIP must match/);
  } finally { await rm(folder, { recursive: true, force: true }); }
});
