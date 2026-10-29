// Read-only audit of every reachable Git blob. Never prints matched secret values.
const {execFileSync} = require('node:child_process');
const fs = require('node:fs');
const crypto = require('node:crypto');
const git = args => execFileSync('git', args, {maxBuffer: 512 * 1024 * 1024});
const objects = git(['rev-list', '--objects', '--all']).toString('utf8').trim().split('\n')
  .map(line => { const i = line.indexOf(' '); return {id: i < 0 ? line : line.slice(0, i), path: i < 0 ? '' : line.slice(i + 1)}; });
const output = execFileSync('git', ['cat-file', '--batch'], {
  input: objects.map(o => o.id).join('\n') + '\n', maxBuffer: 512 * 1024 * 1024
});
const patterns = [
  /-----BEGIN (?:RSA |EC |OPENSSH |DSA |ENCRYPTED )?PRIVATE KEY-----/,
  /\bgh[pousr]_[A-Za-z0-9_]{30,}\b/,
  /\bgithub_pat_[A-Za-z0-9_]{35,}\b/,
  /\bAKIA[0-9A-Z]{16}\b/,
  /\bxox[baprs]-[A-Za-z0-9-]{20,}\b/
];
const testPaths = git(['ls-files', 'review']).toString('utf8').trim().split('\n').filter(p => p.endsWith('.java'));
const testText = testPaths.map(p => fs.readFileSync(p, 'utf8')).join('\n');
const findings = [], fixtures = [], sensitivePaths = [];
let offset = 0, blobs = 0, textBlobs = 0;
function strings(value) {
  if (typeof value === 'string') return value ? [value] : [];
  if (value && typeof value === 'object') return Object.values(value).flatMap(strings);
  return [];
}
for (const object of objects) {
  const end = output.indexOf(10, offset);
  const header = output.toString('ascii', offset, end).split(' ');
  const size = Number(header[2]);
  const body = output.subarray(end + 1, end + 1 + size);
  offset = end + 1 + size + 1;
  if (header[1] !== 'blob') continue;
  blobs++;
  if (/\.(?:pem|p12|pfx|jks)$|(?:^|\/)\.env$|(?:^|\/)testserver\/|identity-signing-keypair\.txt$|(?:^|\/)identity\.txt$/i.test(object.path)) sensitivePaths.push(object.path);
  if (body.includes(0)) continue;
  textBlobs++;
  const text = body.toString('utf8');
  if (patterns.some(pattern => pattern.test(text))) findings.push({object: object.id, path: object.path});
  if (/review\/.*\/config\/.*\/(?:endpoint-credentials|servers)\.json$/.test(object.path)) {
    const values = strings(JSON.parse(text));
    const unmatched = values.filter(value => !testText.includes(value));
    fixtures.push({object: object.id, path: object.path, endpoints: Object.keys(JSON.parse(text)),
      nonemptyStringCount: values.length, allValuesPresentInTestSources: unmatched.length === 0,
      unmatchedValueHashes: unmatched.map(v => crypto.createHash('sha256').update(v).digest('hex'))});
  }
}
console.log(JSON.stringify({reachableObjects: objects.length, scannedBlobs: blobs, scannedTextBlobs: textBlobs,
  knownSecretPatternMatches: findings, sensitiveRuntimePaths: sensitivePaths, historicalConfigFixtures: fixtures}, null, 2));
if (findings.length || sensitivePaths.length || fixtures.some(f => !f.allValuesPresentInTestSources)) process.exitCode = 1;
