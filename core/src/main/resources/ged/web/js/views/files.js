// The start screen: open files, recent files, and opening one by path.
import { h, icon, replace } from '../dom.js';
import { api } from '../api.js';
import { href, plural, button, toast, emptyState, errorState, notice, confirmDialog, chip } from '../ui.js';

const LAST_FILE = 'gedcom-shell.lastFile';

/** The folder a file is in, shortened from the left when long. */
function shortPath(path) {
  const dir = path.replace(/[\\/][^\\/]*$/, '') || path;
  return dir.length > 46 ? '…' + dir.slice(-45) : dir;
}

export async function startScreen(root, app, { onOpen }) {
  const inner = h('div.start-inner');
  const page = h('div.start', inner);
  replace(root, page);
  const head = h('header.start-head', [
    h('img.start-mark', { src: '/icons/icon.svg', alt: '', width: 48, height: 48 }),
    h('div', [
      h('h1.start-title', 'gedcom-shell'),
      h('p.start-sub', 'Your family trees, open on this computer.'),
    ]),
  ]);
  const openSection = h('section.start-section', { aria: { labelledby: 'open-h' } }, [
    h('h2.section-title', { id: 'open-h' }, 'Open files'),
    h('div.file-grid', [h('div.file-card.skel-card', [h('div.skel.w50.tall'), h('div.skel.w80'), h('div.skel.w40.thin')])]),
  ]);
  inner.append(head, openSection);

  let data;
  try {
    data = await api.files();
  } catch (e) {
    if (e.status === 401) return;
    replace(inner, head, errorState(e, () => startScreen(root, app, { onOpen })));
    return;
  }
  const last = localStorage.getItem(LAST_FILE);
  const files = [...data.files].sort((a, b) => (b.id === last) - (a.id === last));

  // open files
  const grid = h('div.file-grid');
  if (!files.length) {
    grid.append(emptyState({ icon: 'folder', title: 'No files are open', text: 'Open a GEDCOM file below, or pick one you used lately.' }));
  }
  for (const f of files) grid.append(fileCard(f, f.id === last));
  replace(openSection, h('h2.section-title', { id: 'open-h' }, 'Open files'), grid);

  // open by path
  const input = h('input.input', {
    id: 'open-path', type: 'text', placeholder: '/Users/you/Family/family.ged', autocomplete: 'off',
    spellcheck: 'false', autocapitalize: 'off', aria: { describedby: 'open-hint' },
  });
  const err = h('div.form-error-slot');
  const go = button('Open', { kind: 'primary', type: 'submit', icon: 'folder' });
  const form = h('form.open-form', { novalidate: true }, [
    h('label.label', { for: 'open-path' }, 'Path to a GEDCOM file'),
    h('div.input-row', [input, go]),
    h('p.hint', { id: 'open-hint' }, 'An absolute path on the computer running gedcom-shell. ~/ means your home folder.'),
    err,
  ]);
  form.addEventListener('submit', async (e) => {
    e.preventDefault();
    const path = input.value.trim();
    replace(err);
    if (!path) { input.focus(); return; }
    go.disabled = true;
    go.classList.add('is-busy');
    try {
      const f = await api.open(path);
      toast(`Opened ${f.name}`, { kind: 'success' });
      onOpen(f.id);
    } catch (ex) {
      if (ex.status === 401) return;
      replace(err, notice('error', ex.message));
      input.focus();
    } finally {
      go.disabled = false;
      go.classList.remove('is-busy');
    }
  });

  const openBy = h('section.start-section', { aria: { labelledby: 'path-h' } }, [
    h('h2.section-title', { id: 'path-h' }, 'Open a file'),
    h('div.card', h('div.card-body', form)),
  ]);

  // recent
  const recent = data.recent.filter((r) => !r.open);
  const recentSection = h('section.start-section', { aria: { labelledby: 'recent-h' } }, [
    h('h2.section-title', { id: 'recent-h' }, 'Recent'),
  ]);
  if (!recent.length) {
    recentSection.append(h('p.muted.small', data.recent.length ? 'Every recent file is open.' : 'Files you open will be listed here.'));
  } else {
    const list = h('div.card.recent-list', recent.map((r) => recentRow(r)));
    recentSection.append(list);
  }
  inner.append(openBy, recentSection);

  api.daemon().then((d) => {
    inner.append(h('footer.start-foot', `gedcom-shell ${d.version} · ${d.scheme}://${d.bind}:${d.port}`));
  }).catch(() => {});

  function fileCard(f, isLast) {
    const a = h('a.file-card', { href: href(f.id) }, [
      h('div.file-card-top', [
        h('span.file-icon', icon('file')),
        h('div.file-card-titles', [
          h('span.file-card-name', f.name),
          h('span.file-card-path', { title: f.path }, shortPath(f.path)),
        ]),
      ]),
      h('div.file-card-stats', [
        h('span', [h('strong', f.people.toLocaleString()), f.people === 1 ? ' person' : ' people']),
        h('span', [h('strong', f.families.toLocaleString()), f.families === 1 ? ' family' : ' families']),
        h('span', [h('strong', (f.records.sources || 0).toLocaleString()), ' sources']),
      ]),
      h('div.file-card-chips', [
        isLast ? chip('Last opened', 'chip-accent') : null,
        f.dirty ? chip(`${f.dirtyCount} unsaved`, 'chip-warn') : null,
        f.changedOnDisk ? chip('Changed on disk', 'chip-warn') : null,
        f.warnings.length ? chip(plural(f.warnings.length, 'warning', 'warnings')) : null,
        chip(`GEDCOM ${f.gedcom}`, 'chip-quiet'),
      ]),
    ]);
    const close = h('button.btn.btn-icon.btn-ghost.file-close', { type: 'button', title: `Close ${f.name}`, aria: { label: `Close ${f.name}` } }, icon('close'));
    close.addEventListener('click', async () => {
      let force = false;
      if (f.dirty) {
        force = await confirmDialog({
          title: `Close ${f.name} without saving?`,
          text: `It has ${plural(f.dirtyCount || 0, 'unsaved edit', 'unsaved edits')}. Closing without saving drops them — open it and save first to keep them.`,
          confirm: 'Close without saving', danger: true,
        });
        if (!force) return;
      }
      try {
        await api.close(f.id, force);
        toast(`Closed ${f.name}`);
        startScreen(root, app, { onOpen });
      } catch (e) { toast(e.message, { kind: 'error' }); }
    });
    return h('div.file-card-wrap', [a, close]);
  }

  function recentRow(r) {
    const forget = h('button.btn.btn-icon.btn-ghost', { type: 'button', title: 'Remove from recent', aria: { label: `Remove ${r.name} from recent` } }, icon('close'));
    forget.addEventListener('click', async () => {
      const ok = await confirmDialog({ title: `Remove ${r.name} from recent?`, text: 'The file itself is not touched; it just leaves this list.', confirm: 'Remove' });
      if (!ok) return;
      try {
        await api.forget(r.id);
        row.remove();
      } catch (e) { toast(e.message, { kind: 'error' }); }
    });
    const main = r.exists
      ? h('a.recent-main', { href: href(r.id) }, [h('span.file-icon.small', icon('file')), h('span.recent-text', [h('span.recent-name', r.name), h('span.recent-path', r.path)])])
      : h('div.recent-main.is-missing', [h('span.file-icon.small', icon('file')), h('span.recent-text', [h('span.recent-name', [r.name, ' ', chip('Not found', 'chip-warn')]), h('span.recent-path', r.path)])]);
    const row = h('div.recent-row', [main, forget]);
    return row;
  }
}
