// Opening a GEDCOM text in the test daemon, as a pairing address the browser
// can go to: http://127.0.0.1:PORT/#pair=TOKEN&open=ID.
import { execFileSync } from 'node:child_process';
import { mkdtempSync, writeFileSync, readFileSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

export const PORT = 7435;
export const JAR = join(dirname(fileURLToPath(import.meta.url)), '..', '..', 'core', 'target', 'gedcom-shell.jar');

/** Writes `ged` to a new file and returns the address that pairs and opens it. */
export function open(ged, name = 'tree') {
  return openFile(ged, { name }).pairing;
}

/**
 * Writes `ged` (and `files`, name → contents, beside it) to a new folder and
 * opens it: `{pairing, id, path}`.
 */
export function openFile(ged, { name = 'tree', files = {} } = {}) {
  const home = process.env.GEDCOM_SHELL_HOME;
  const dir = mkdtempSync(join(home, 'files-'));
  const file = join(dir, `${name}.ged`);
  writeFileSync(file, ged);
  for (const [n, data] of Object.entries(files)) writeFileSync(join(dir, n), data);
  const out = execFileSync('java', ['-jar', process.env.GEDCOM_TEST_JAR || JAR, 'daemon', 'pair', file], {
    env: { ...process.env, GEDCOM_SHELL_HOME: home },
    encoding: 'utf8',
  });
  const url = out.match(/http:\/\/127\.0\.0\.1:\d+\/#pair=[^\s]+/);
  if (!url) throw new Error(`no pairing address in:\n${out}`);
  return { pairing: url[0], id: fileId(url[0]), path: file };
}

/** The file id in a pairing address. */
export function fileId(pairing) {
  return new URL(pairing.replace('#', '?')).searchParams.get('open');
}

/** Calls the test daemon's API with its token: `api('files/ID/people/I0')`, or POSTs `body`. */
export async function api(path, body) {
  const token = readFileSync(join(process.env.GEDCOM_SHELL_HOME, 'token'), 'utf8').trim();
  const res = await fetch(`http://127.0.0.1:${PORT}/api/v1/${path}`, {
    method: body === undefined ? 'GET' : 'POST',
    headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const data = await res.json();
  if (!res.ok) throw new Error(`${res.status}: ${data.error}`);
  return data;
}
