// Starts a daemon from the built jar, in a temporary home on port 7435, for
// the whole run; the returned function stops it.
import { spawn } from 'node:child_process';
import { mkdtempSync, writeFileSync, existsSync, copyFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { JAR, PORT } from './daemon.js';

export default async function globalSetup() {
  if (!existsSync(JAR)) throw new Error(`${JAR} is missing: run mvn package first`);
  const home = mkdtempSync(join(tmpdir(), 'gedcom-web-tests-'));
  writeFileSync(join(home, 'daemon.properties'), `port=${PORT}\n`);
  process.env.GEDCOM_SHELL_HOME = home;
  // a copy of its own, so a rebuild during the run doesn't pull the jar from under the daemon
  const jar = join(home, 'gedcom-shell.jar');
  copyFileSync(JAR, jar);
  process.env.GEDCOM_TEST_JAR = jar;

  // WEB_DIR=../core/src/main/resources/ged/web serves the web app from there,
  // to try changes to it without building the jar
  const web = process.env.WEB_DIR ? ['--web-dir', process.env.WEB_DIR] : [];
  const daemon = spawn('java', ['-jar', jar, 'daemon', 'run', ...web], {
    env: { ...process.env, GEDCOM_SHELL_HOME: home },
    stdio: ['ignore', 'inherit', 'inherit'],
  });
  const deadline = Date.now() + 30_000;
  for (;;) {
    try {
      if ((await fetch(`http://127.0.0.1:${PORT}/`)).ok) break;
    } catch { /* not up yet */ }
    if (daemon.exitCode != null) throw new Error(`the daemon exited (${daemon.exitCode})`);
    if (Date.now() > deadline) throw new Error('the daemon did not start in 30 s');
    await new Promise((r) => setTimeout(r, 200));
  }
  return async () => {
    daemon.kill('SIGTERM');
    await new Promise((r) => daemon.exitCode != null ? r() : daemon.once('exit', r));
  };
}
