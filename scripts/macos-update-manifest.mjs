import { createHash } from 'node:crypto';
import { createReadStream } from 'node:fs';
import { glob, readFile, stat, writeFile } from 'node:fs/promises';
import { basename, dirname, join } from 'node:path';
import { pathToFileURL } from 'node:url';

/** Run after notarization and ZIP verification; the ZIP must contain the stapled, signed app. */
export async function makeUpdateManifest(zip, version, tag) {
  if (!/^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-[0-9A-Za-z.-]+)?$/.test(version)
    || tag !== `v${version}`) throw new Error('The release tag must match frontend/package.json');
  const match = /^ByteQuay-darwin-(arm64|x64)-(.+)\.zip$/.exec(basename(zip));
  if (!match || match[2] !== version) throw new Error('The update ZIP must match the release version and architecture');
  const hash = createHash('sha256');
  for await (const chunk of createReadStream(zip)) hash.update(chunk);
  return {
    version, name: version,
    url: `https://github.com/bytequay/bytequay/releases/download/${tag}/${basename(zip)}`,
    pub_date: new Date().toISOString(),
    sha256: hash.digest('hex'), size: (await stat(zip)).size,
  };
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  if (process.env.BYTEQUAY_SIGN_RELEASE !== '1') throw new Error('Update manifests require a signed release build');
  const { version } = JSON.parse(await readFile('frontend/package.json', 'utf8'));
  const tag = process.env.GITHUB_REF_TYPE === 'tag' ? process.env.GITHUB_REF_NAME : `v${version}`;
  let count = 0;
  for await (const zip of glob('frontend/out/make/**/*.zip')) {
    const manifest = await makeUpdateManifest(zip, version, tag);
    const arch = basename(zip).split('-')[2];
    await writeFile(join(dirname(zip), `update-darwin-${arch}.json`), JSON.stringify(manifest, null, 2) + '\n');
    count++;
  }
  if (count === 0) throw new Error('No verified update ZIP was produced');
}
