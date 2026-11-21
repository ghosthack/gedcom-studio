// Pairing: the browser needs the daemon's token once.
import { h, icon, replace } from '../dom.js';
import { api, token, parsePairing } from '../api.js';
import { button, notice } from '../ui.js';

export async function pairingScreen(root, { onPaired, message }) {
  const hadToken = !!token.get();
  const input = h('input.input', {
    id: 'pair-input', type: 'text', autocomplete: 'off', spellcheck: 'false', autocapitalize: 'off',
    placeholder: 'http://127.0.0.1:7380/#pair=…', aria: { describedby: 'pair-hint' },
  });
  const err = h('div.form-error-slot');
  const go = button('Pair this browser', { kind: 'primary', type: 'submit', icon: 'key' });
  const form = h('form.pair-form', { novalidate: true }, [
    h('label.label', { for: 'pair-input' }, 'Pairing address or token'),
    input,
    h('p.hint', { id: 'pair-hint' }, 'Paste the whole address the command printed, or just the token after “pair=”.'),
    err,
    go,
  ]);
  const status = h('div.pair-status');
  const card = h('div.pair-card', [
    h('img.pair-mark', { src: '/icons/icon.svg', alt: '', width: 56, height: 56 }),
    h('h1.pair-title', hadToken ? 'Pair this browser again' : 'Connect this browser'),
    h('p.pair-lead', hadToken
      ? 'gedcom-shell didn’t accept the key this browser had — the daemon may have a new token. Pair once more to carry on.'
      : 'Your GEDCOM files stay on your computer, held open by gedcom-shell. To let this browser see them, pair it once.'),
    status,
    h('ol.pair-steps', [
      h('li', [h('span.step-n', '1'), h('div', ['On the computer running gedcom-shell, open a terminal and run', h('code.cmd', 'gedcom-shell daemon pair')])]),
      h('li', [h('span.step-n', '2'), h('div', 'Open the address it prints in this browser — or paste it here:')]),
    ]),
    form,
    h('p.pair-foot', [icon('key'), h('span', 'The key is kept in this browser only. Anyone who has it can read and change your open files, so keep it to yourself.')]),
  ]);
  replace(root, h('div.pair', card));
  if (message) replace(status, notice('warn', message));
  input.focus();

  // is the daemon there at all?
  api.ping().catch(() => {
    replace(status, notice('warn', [
      'This page can’t reach the gedcom-shell daemon. Start it with ',
      h('code', 'gedcom-shell daemon start'),
      ', then reload.',
    ], { title: 'Daemon not running' }));
  });

  form.addEventListener('submit', async (e) => {
    e.preventDefault();
    replace(err);
    const p = parsePairing(input.value);
    if (!p) {
      replace(err, notice('error', 'That doesn’t look like a pairing address or token. It should contain “#pair=” followed by a long code.'));
      input.focus();
      return;
    }
    const previous = token.get();
    token.set(p.token);
    go.disabled = true;
    try {
      await fetchCheck();
      onPaired(p.open);
    } catch (ex) {
      if (previous) token.set(previous); else token.clear();
      replace(err, notice('error', ex.status === 401
        ? 'gedcom-shell didn’t accept that token. Run “gedcom-shell daemon pair” again and use the newest address.'
        : ex.message));
      input.focus();
    } finally {
      go.disabled = false;
    }
  });
}

/** Checks the token without triggering the app-wide 401 handler. */
async function fetchCheck() {
  const res = await fetch('/api/v1/daemon', { headers: { Authorization: 'Bearer ' + token.get() }, cache: 'no-store' })
    .catch(() => { const e = new Error('Can’t reach the gedcom-shell daemon.'); e.status = 0; throw e; });
  if (!res.ok) {
    const e = new Error(res.status === 401 ? 'not accepted' : `The daemon answered ${res.status}.`);
    e.status = res.status;
    throw e;
  }
}
