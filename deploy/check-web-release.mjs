import { readFile, readdir } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { resolve, relative, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
const root = resolve(dirname(fileURLToPath(import.meta.url)), 'web');
const manifest = JSON.parse(await readFile(resolve(root, 'asset-manifest.json'), 'utf8'));
const expected = new Set();
for (const asset of manifest.files) {
  if (!/^dist\/[a-zA-Z0-9_./-]+$/.test(asset.path) || asset.path.includes('..')) throw Error('Invalid asset path');
  const file = await readFile(resolve(root, asset.path));
  if (createHash('sha256').update(file).digest('hex') !== asset.sha256 || file.length !== asset.bytes) throw Error(`Asset mismatch: ${asset.path}`);
  expected.add(asset.path);
}
async function inspect(folder) {
  for (const entry of await readdir(folder, { withFileTypes: true })) {
    const path = resolve(folder, entry.name);
    if (entry.isSymbolicLink()) throw Error('Asset symlinks are not allowed');
    if (entry.isDirectory()) await inspect(path);
    else if (!expected.has(relative(root, path).replaceAll('\\', '/'))) throw Error(`Unlisted asset: ${entry.name}`);
  }
}
await inspect(resolve(root, 'dist'));
if (!expected.has('dist/index.html') || expected.has('dist/firebase-config.json')) throw Error('Missing entry point or embedded Firebase configuration');
console.log(`Verified ${expected.size} built web assets. Firebase configuration is supplied by the existing VM mount.`);
