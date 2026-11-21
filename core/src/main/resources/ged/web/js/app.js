// gedcom-shell web app: routing, the app frame, live updates and saving.
import { h, icon, replace, debounce } from './dom.js';
import { api, token, parsePairing, onUnauthorized, ApiError } from './api.js';
import {
  href, plural, toast, dialog, button, personRow, skeleton, errorState, emptyState,
  anyDialogOpen, closeMenu, popupMenu, KIND,
} from './ui.js';
import { startScreen } from './views/files.js';
import { pairingScreen } from './views/pairing.js';
import { personView, overviewView } from './views/person.js';
import { familyView, familiesView } from './views/family.js';
import { recordsView, recordView, warningsView } from './views/records.js';
import { pedigreeView, descendantsView } from './views/charts.js';
import { newPersonDialog } from './views/relatives.js';

const LAST_FILE = 'gedcom-shell.lastFile';
const SORT_KEY = 'gedcom-shell.sort';
const PAGE = 200;

const store = {
  get(k) { try { return localStorage.getItem(k); } catch { return null; } },
  set(k, v) { try { localStorage.setItem(k, v); } catch { /* private mode */ } },
};

const phoneQuery = window.matchMedia('(max-width: 760px)');

/** Everything the pages share: passed to every view. */
const app = {
  fileId: null,
  file: null,
  fields: null,
  revision: -1,
  instance: null,
  forms: new Set(),
  pending: 0,
  lastPerson: null,
  lastKind: 'sources',
  route: null,
  isPhone: () => phoneQuery.matches,
  navigate(hash, { replace: rep = false } = {}) {
    if (rep) history.replaceState(null, '', hash);
    else history.pushState(null, '', hash);
    navCount++;
    route();
  },
  setTitle(text) {
    document.title = text ? `${text} · ${app.file ? app.file.name : 'gedcom-shell'}` : 'gedcom-shell';
    if (frame) frame.pageTitle.textContent = text || (app.file ? app.file.name : '');
  },
  /** Runs a change; keeps live updates from treating our own edit as someone else's. */
  async mutate(fn) {
    app.pending++;
    try {
      const r = await fn();
      if (r && typeof r.revision === 'number') app.revision = Math.max(app.revision, r.revision);
      return r;
    } finally {
      app.pending--;
    }
  },
  /** After one of our own edits: refresh what's on screen. */
  async changed() {
    await Promise.all([refreshFile(), refreshList()]);
    await showView(app.route, { refresh: true });
  },
  refreshFile: () => refreshFile(),
  save: (force) => save(force),
};

let frame = null; // the app frame for the open file
let navCount = 0;

// ---- start up ----------------------------------------------------------------

onUnauthorized(() => showPairing());

/** A pairing address (`#pair=TOKEN&open=ID`): keep the token, then drop it from the address bar. */
function consumePairing() {
  const hash = location.hash;
  if (!/[#&]pair=/.test(hash)) return false;
  const p = parsePairing(hash);
  history.replaceState(null, '', location.pathname + location.search);
  if (!p) return false;
  token.set(p.token);
  if (p.open) history.replaceState(null, '', href(p.open));
  return true;
}

function boot() {
  consumePairing();
  window.addEventListener('popstate', () => {
    navCount = Math.max(0, navCount - 1);
    if (consumePairing()) navCount = 0;
    route();
  });
  document.addEventListener('keydown', onKey);
  phoneQuery.addEventListener('change', () => { if (frame) markRoute(); });
  document.addEventListener('click', onLinkClick);

  registerServiceWorker();
  if (!token.get()) return showPairing();
  if (!location.hash || location.hash === '#' || location.hash === '#/') {
    const last = store.get(LAST_FILE);
    if (last) {
      api.files().then((r) => {
        const open = r.files.find((f) => f.id === last);
        if (open) history.replaceState(null, '', href(last));
        route();
      }).catch((e) => { if (e.status !== 401) route(); });
      return;
    }
  }
  route();
}

/** In-app links push history (so back works) and count, for the phone back button. */
function onLinkClick(e) {
  const a = e.target.closest && e.target.closest('a[href^="#/"]');
  if (!a || e.defaultPrevented || e.button !== 0 || e.metaKey || e.ctrlKey || e.shiftKey || e.altKey || a.target) return;
  e.preventDefault();
  closeMenu();
  if (a.getAttribute('href') === location.hash) { route(); return; }
  app.navigate(a.getAttribute('href'));
}

function registerServiceWorker() {
  if (!('serviceWorker' in navigator) || !window.isSecureContext) return;
  navigator.serviceWorker.register('/sw.js').catch(() => { /* the app works without it */ });
}

// ---- routes ------------------------------------------------------------------

function parse(hash) {
  const parts = (hash || '').replace(/^#\/?/, '').split('/').filter(Boolean).map((p) => {
    try { return decodeURIComponent(p); } catch { return p; }
  });
  if (!parts.length) return { name: 'start' };
  if (parts[0] === 'files') return { name: 'start' };
  if (parts[0] === 'f' && parts[1]) {
    return { name: 'file', fileId: parts[1], section: parts[2] || '', key: parts[3] || '' };
  }
  return { name: 'start' };
}

async function route() {
  closeMenu();
  const r = parse(location.hash);
  if (!token.get()) return showPairing();
  if (r.name === 'start') {
    leaveFile();
    app.route = r;
    return showStart();
  }
  const ok = await enterFile(r.fileId);
  if (!ok) return;
  if (!r.section) {
    const k = app.isPhone() ? '' : (app.lastPerson || app.file.home);
    history.replaceState(null, '', k ? href(app.fileId, 'people', k) : href(app.fileId, 'people'));
    return route();
  }
  app.route = r;
  markRoute();
  await showView(r);
}

async function showStart() {
  frame = null;
  const root = document.getElementById('app');
  document.body.className = 'is-start';
  app.setTitle('');
  await startScreen(root, app, { onOpen: (id) => app.navigate(href(id)) });
}

function showPairing(message) {
  if (document.body.classList.contains('is-pairing')) return;
  leaveFile();
  frame = null;
  document.body.className = 'is-pairing';
  document.title = 'Pair this browser · gedcom-shell';
  pairingScreen(document.getElementById('app'), {
    message,
    onPaired: (open) => {
      if (open) history.replaceState(null, '', href(open));
      else if (!location.hash.startsWith('#/f/')) history.replaceState(null, '', '#/');
      route();
    },
  });
}

// ---- the open file -------------------------------------------------------------

let liveCtrl = null;

async function enterFile(id) {
  if (app.fileId === id && frame) return true;
  leaveFile();
  const root = document.getElementById('app');
  try {
    const [file, fields] = await Promise.all([api.file(id), app.fields ? Promise.resolve(app.fields) : api.fields()]);
    app.fileId = file.id;
    app.file = file;
    app.fields = fields;
    app.revision = file.revision;
    app.instance = file.instance || null;
    app.lastPerson = null;
    store.set(LAST_FILE, file.id);
  } catch (e) {
    if (e.status === 401) return false;
    document.body.className = 'is-start';
    replace(root, h('div.start', h('div.start-inner', [
      errorState(e.status === 404
        ? new ApiError(404, null, 'This file isn’t open in gedcom-shell any more, and it isn’t in the recent list.')
        : e, () => route()),
      h('p.center', h('a.btn', { href: '#/files' }, 'Show files')),
    ])));
    return false;
  }
  buildFrame(root);
  startLive(app.fileId);
  refreshList();
  return true;
}

function leaveFile() {
  if (liveCtrl) liveCtrl.abort();
  liveCtrl = null;
  app.fileId = null;
  app.file = null;
  for (const f of app.forms) f.close && f.close();
  app.forms.clear();
}

async function refreshFile() {
  if (!app.fileId) return;
  try {
    const f = await api.file(app.fileId);
    if (f.id !== app.fileId) return;
    app.file = f;
    // app.revision and app.instance are left to the live loop: moving them on here
    // could swallow an edit (or a daemon restart) the page hasn't loaded yet
    updateFrame();
  } catch { /* the live loop will notice */ }
}

// ---- the frame: top bar, sidebar, main ------------------------------------------

const NAV = [
  { section: 'people', label: 'People', icon: 'people' },
  { section: 'pedigree', label: 'Tree', icon: 'pedigree' },
  { section: 'families', label: 'Families', icon: 'family' },
  { section: 'notes', label: 'Notes', icon: 'note' },
  { section: 'sources', label: 'Sources', icon: 'source' },
  { section: 'media', label: 'Media', icon: 'media' },
  { section: 'warnings', label: 'Warnings', icon: 'warning' },
];

function navHref(section) {
  const f = app.fileId;
  if (section === 'people') return app.lastPerson && !app.isPhone() ? href(f, 'people', app.lastPerson) : href(f, 'people');
  if (section === 'pedigree') return href(f, 'pedigree', app.lastPerson || app.file.home || '');
  if (section === 'records') return href(f, app.lastKind);
  return href(f, section);
}

function buildFrame(root) {
  document.body.className = 'is-file';
  const searchInput = h('input.input.search-input', {
    type: 'search', placeholder: 'Search people', autocomplete: 'off', spellcheck: 'false',
    enterkeyhint: 'search', aria: { label: 'Search people', controls: 'people-list' },
  });
  const sortSelect = h('select.select.select-sm', { aria: { label: 'Sort people' } }, [
    h('option', { value: 'name' }, 'By name'),
    h('option', { value: 'born' }, 'By birth'),
    h('option', { value: 'file' }, 'File order'),
  ]);
  sortSelect.value = store.get(SORT_KEY) || 'name';
  const list = h('div.people-list', { id: 'people-list', role: 'list' });
  const listHead = h('div.list-count');
  const more = h('button.btn.btn-ghost.more-btn', { type: 'button', hidden: true }, 'Show more');
  const addPersonBtn = button('', { icon: 'plus', kind: 'ghost', title: 'New person', aria: { label: 'New person' }, onclick: () => newPersonDialog(app) });

  const sidebar = h('aside.sidebar', { aria: { label: 'People' } }, [
    h('div.sidebar-head', [
      h('h2.sidebar-title', 'People'),
      addPersonBtn,
    ]),
    h('div.search-box', [
      icon('search', 'search-icon'),
      searchInput,
      h('kbd.search-kbd', { aria: { hidden: 'true' } }, '/'),
    ]),
    h('div.list-tools', [listHead, sortSelect]),
    h('div.list-scroll', [list, more]),
  ]);

  const main = h('main.main', { id: 'main', tabindex: '-1' });

  // top bar
  const back = h('button.btn.btn-icon.btn-ghost.back-btn', { type: 'button', aria: { label: 'Back' } }, icon('chevronLeft'));
  back.addEventListener('click', () => {
    if (navCount > 0) history.back();
    else app.navigate(app.route && app.route.section === 'people' ? '#/files' : href(app.fileId, 'people'));
  });
  const fileBtn = h('button.file-btn', { type: 'button', aria: { haspopup: 'menu', expanded: 'false' } }, [
    h('span.file-name'), icon('chevronDown', 'file-chevron'),
  ]);
  fileBtn.addEventListener('click', () => fileMenu(fileBtn));
  const pageTitle = h('span.page-title');
  const tabs = h('nav.tabs', { aria: { label: 'Sections' } }, NAV.map((n) => {
    const a = h('a.tab', { href: '#', dataset: { section: n.section } }, [icon(n.icon), h('span.tab-label', n.label)]);
    if (n.section === 'warnings') a.append(h('span.tab-count', { hidden: true }));
    return a;
  }));
  const conn = h('span.conn', { hidden: true, role: 'status' }, [h('span.conn-dot'), h('span.conn-text', 'Reconnecting…')]);
  const disk = h('span.disk-chip', { hidden: true, title: 'Another program changed the file since gedcom-shell read it' }, [icon('warning'), h('span', 'Changed on disk')]);
  const badge = h('span.unsaved', { hidden: true, title: 'Edits are held by gedcom-shell until you save the file' });
  const saveBtn = h('button.btn.save-btn', { type: 'button', title: 'Save (⌘S)', aria: { keyshortcuts: 'Meta+S Control+S' } }, [icon('save'), h('span.save-label', 'Save')]);
  saveBtn.addEventListener('click', () => save(false));

  const topbar = h('header.topbar', [
    h('div.topbar-start', [
      h('a.brand', { href: '#/files', title: 'Files', aria: { label: 'gedcom-shell — files' } }, h('img', { src: '/icons/icon.svg', alt: '', width: 26, height: 26 })),
      back, fileBtn, pageTitle,
    ]),
    tabs,
    h('div.topbar-end', [conn, disk, badge, saveBtn]),
  ]);

  const tabbar = h('nav.tabbar', { aria: { label: 'Sections' } }, [
    ['people', 'People', 'search'], ['pedigree', 'Tree', 'pedigree'], ['families', 'Families', 'family'],
    ['records', 'Records', 'source'], ['files', 'Files', 'folder'],
  ].map(([section, label, ic]) => h('a.tabbar-item', { href: '#', dataset: { section } }, [icon(ic), h('span', label)])));

  replace(root, h('div.frame', [topbar, h('div.frame-body', [sidebar, main]), tabbar]));
  frame = { topbar, sidebar, main, list, listHead, more, searchInput, sortSelect, tabs, tabbar, fileBtn, pageTitle, conn, disk, badge, saveBtn, back };

  const search = debounce(() => refreshList(), 140);
  searchInput.addEventListener('input', search);
  searchInput.addEventListener('keydown', (e) => {
    if (e.key === 'ArrowDown') { const first = list.querySelector('.person-link'); if (first) { e.preventDefault(); first.focus(); } }
    if (e.key === 'Escape' && searchInput.value) { e.preventDefault(); searchInput.value = ''; refreshList(); }
    if (e.key === 'Enter') { const first = list.querySelector('.person-link'); if (first) first.click(); }
  });
  list.addEventListener('keydown', (e) => {
    if (e.key !== 'ArrowDown' && e.key !== 'ArrowUp') return;
    const links = [...list.querySelectorAll('.person-link')];
    const i = links.indexOf(document.activeElement);
    if (i < 0) return;
    e.preventDefault();
    if (e.key === 'ArrowUp' && i === 0) searchInput.focus();
    else (links[i + (e.key === 'ArrowDown' ? 1 : -1)] || links[i]).focus();
  });
  sortSelect.addEventListener('change', () => { store.set(SORT_KEY, sortSelect.value); refreshList(); });
  more.addEventListener('click', () => refreshList({ append: true }));
  updateFrame();
}

function updateFrame() {
  if (!frame || !app.file) return;
  const f = app.file;
  frame.fileBtn.querySelector('.file-name').textContent = f.name;
  frame.fileBtn.title = f.path;
  const n = f.dirtyCount || 0;
  frame.badge.hidden = !f.dirty;
  frame.badge.textContent = n ? `${n} unsaved` : 'Unsaved';
  frame.saveBtn.classList.toggle('btn-primary', !!f.dirty);
  frame.saveBtn.classList.toggle('btn-quiet', !f.dirty);
  frame.saveBtn.setAttribute('aria-label', f.dirty ? `Save ${f.name} (${n} unsaved)` : `Save ${f.name} (no unsaved edits)`);
  frame.disk.hidden = !f.changedOnDisk;
  const wc = frame.tabs.querySelector('.tab-count');
  wc.hidden = !f.warnings.length;
  wc.textContent = f.warnings.length;
  markRoute();
}

function markRoute() {
  if (!frame || !app.route) return;
  const r = app.route;
  const section = r.section;
  const peopleIndex = section === 'people' && !r.key;
  document.body.classList.toggle('route-people-index', peopleIndex);
  document.body.classList.toggle('route-detail', !!r.key && ['people', 'families', 'records'].includes(section));
  document.body.classList.toggle('route-chart', section === 'pedigree' || section === 'descendants');
  for (const a of frame.tabs.querySelectorAll('.tab')) {
    const s = a.dataset.section;
    a.href = navHref(s);
    const active = s === section || (s === 'pedigree' && section === 'descendants')
      || (s === 'sources' && section === 'records' && app.lastKind === 'sources')
      || (s === 'notes' && section === 'records' && app.lastKind === 'notes')
      || (s === 'media' && section === 'records' && app.lastKind === 'media');
    a.classList.toggle('is-active', active);
    if (active) a.setAttribute('aria-current', 'page'); else a.removeAttribute('aria-current');
  }
  for (const a of frame.tabbar.querySelectorAll('.tabbar-item')) {
    const s = a.dataset.section;
    a.href = s === 'files' ? '#/files' : s === 'people' ? href(app.fileId, 'people') : navHref(s);
    const active = s === section
      || (s === 'pedigree' && section === 'descendants')
      || (s === 'records' && ['notes', 'sources', 'media', 'records'].includes(section))
      || (s === 'people' && section === 'warnings');
    a.classList.toggle('is-active', active);
    if (active) a.setAttribute('aria-current', 'page'); else a.removeAttribute('aria-current');
  }
  for (const row of frame.list.querySelectorAll('.person-row')) {
    const cur = section === 'people' && row.dataset.key === r.key;
    row.classList.toggle('is-current', cur);
    const link = row.querySelector('.person-link');
    if (cur) link.setAttribute('aria-current', 'page'); else link.removeAttribute('aria-current');
  }
}

function fileMenu(anchor) {
  const f = app.file;
  popupMenu(anchor, [
    { heading: f.path },
    { label: 'Files…', icon: 'folder', href: '#/files' },
    { label: 'Save', icon: 'save', hint: isMac() ? '⌘S' : 'Ctrl+S', onSelect: () => save(false) },
    { label: `Start at ${f.home ? 'the home person' : '…'}`, icon: 'home', href: f.home ? href(f.id, 'people', f.home) : '#/files' },
    { label: 'Warnings', icon: 'warning', hint: f.warnings.length ? String(f.warnings.length) : '', href: href(f.id, 'warnings') },
    'sep',
    { label: 'Close file', icon: 'close', onSelect: () => closeFile() },
  ]);
}

async function closeFile() {
  const f = app.file;
  const doClose = async (force) => {
    await api.close(f.id, force);
    toast(`Closed ${f.name}`);
    app.navigate('#/files');
  };
  try {
    await doClose(false);
  } catch (e) {
    if (e.status !== 409) { toast(e.message, { kind: 'error' }); return; }
    const d = dialog({
      title: `Close ${f.name} without saving?`,
      size: 'sm',
      body: h('p.dialog-text', `${f.name} has ${plural(f.dirtyCount || 0, 'unsaved edit', 'unsaved edits')}. Closing without saving drops them.`),
      actions: [
        button('Cancel', { kind: 'ghost', onclick: () => d.close() }),
        button('Close without saving', { kind: 'danger', onclick: async () => { d.close(); try { await doClose(true); } catch (err) { toast(err.message, { kind: 'error' }); } } }),
        button('Save and close', { kind: 'primary', onclick: async () => { d.close(); if (await save(false)) doClose(false).catch((err) => toast(err.message, { kind: 'error' })); } }),
      ],
    });
  }
}

// ---- the people list ---------------------------------------------------------------

let listCtrl = null;
let listState = { q: null, sort: null, items: [], total: 0 };

async function refreshList({ append = false } = {}) {
  if (!frame) return;
  const q = frame.searchInput.value.trim();
  const sort = frame.sortSelect.value;
  if (listCtrl) listCtrl.abort();
  const ctrl = listCtrl = new AbortController();
  const offset = append ? listState.items.length : 0;
  const keepScroll = !append && listState.q === q && listState.sort === sort ? frame.list.parentElement.scrollTop : null;
  if (!append && listState.q !== q) frame.list.setAttribute('aria-busy', 'true');
  if (!listState.items.length && !append) replace(frame.list, skeleton('list'));
  try {
    const r = await api.people(app.fileId, { q, sort, offset, limit: append ? PAGE : Math.max(PAGE, keepScroll != null ? listState.items.length : 0) }, ctrl.signal);
    if (ctrl !== listCtrl) return;
    listState = { q, sort, items: append ? listState.items.concat(r.people) : r.people, total: r.total };
    renderList(append ? r.people : null);
    if (keepScroll != null) frame.list.parentElement.scrollTop = keepScroll;
    else if (!append) frame.list.parentElement.scrollTop = 0;
  } catch (e) {
    if (e.name === 'AbortError' || ctrl !== listCtrl) return;
    if (e.status === 401) return;
    replace(frame.list, h('p.list-error', e.message));
  } finally {
    if (ctrl === listCtrl) frame.list.removeAttribute('aria-busy');
  }
}

function renderList(appended) {
  const { q, items, total } = listState;
  frame.listHead.textContent = q ? (total ? plural(total, 'match', 'matches') : 'No matches') : plural(total, 'person', 'people');
  const current = app.route && app.route.section === 'people' ? app.route.key : null;
  const rows = (appended || items).map((p) => {
    const row = personRow(app.fileId, p, {
      note: p.matched ? `“${p.matched}”` : null,
      current: p.key === current,
      size: 'sm',
    });
    row.dataset.key = p.key;
    row.setAttribute('role', 'listitem');
    return row;
  });
  if (appended) frame.list.append(...rows);
  else if (!items.length) {
    replace(frame.list, q
      ? emptyState({ icon: 'search', title: `No one matches “${q}”`, text: 'Search looks at names, other names and nicknames. Try fewer letters.' })
      : emptyState({ icon: 'people', title: 'No people yet', text: 'Add the first person to start the tree.', action: button('New person', { icon: 'plus', onclick: () => newPersonDialog(app) }) }));
  } else replace(frame.list, rows);
  frame.more.hidden = items.length >= total;
  frame.more.textContent = `Show more (${(total - items.length).toLocaleString()} left)`;
}

// ---- views ------------------------------------------------------------------------

const VIEWS = {
  people: (r) => (r.key ? personView(app, r.key) : overviewView(app)),
  families: (r) => (r.key ? familyView(app, r.key) : familiesView(app)),
  notes: () => recordsView(app, 'notes'),
  sources: () => recordsView(app, 'sources'),
  media: () => recordsView(app, 'media'),
  records: (r) => recordView(app, r.key),
  pedigree: (r) => pedigreeView(app, r.key || app.lastPerson || app.file.home),
  descendants: (r) => descendantsView(app, r.key || app.lastPerson || app.file.home),
  warnings: () => warningsView(app),
};

let viewSeq = 0;

async function showView(r, { refresh = false } = {}) {
  if (!frame || !r || r.name !== 'file') return;
  const render = VIEWS[r.section];
  const main = frame.main;
  const seq = ++viewSeq;
  if (!render) {
    replace(main, errorState(new ApiError(404, null, 'There is no such page.')));
    return;
  }
  if (r.section === 'people' && r.key) app.lastPerson = r.key;
  if (KIND[r.section]) app.lastKind = r.section;
  const scroll = refresh ? main.scrollTop : 0;
  if (!refresh) {
    replace(main, skeleton(['families', 'notes', 'sources', 'media', 'warnings'].includes(r.section) ? 'list' : 'page'));
    main.scrollTop = 0;
  }
  try {
    const node = await render(r);
    if (seq !== viewSeq) return;
    const focusInside = refresh && main.contains(document.activeElement) && document.activeElement !== main ? keepFocus(document.activeElement) : null;
    replace(main, node);
    if (refresh) {
      main.scrollTop = scroll;
      if (focusInside) focusInside(main);
    } else if (navCount > 0 && !main.contains(document.activeElement) && document.activeElement !== frame.searchInput) {
      main.focus({ preventScroll: true });
    }
    markRoute();
  } catch (e) {
    if (seq !== viewSeq || e.status === 401) return;
    if (refresh && e.status === 404) {
      // the thing on screen went away (unlinked, removed): say so, keep the frame
      replace(main, errorState(new ApiError(404, null, 'This no longer exists in the file — it was changed elsewhere.')));
      return;
    }
    replace(main, errorState(e, () => showView(r)));
  }
}

/** Remembers which control had focus so a refresh can give it back. */
function keepFocus(el) {
  const id = el.id;
  const hrefAttr = el.getAttribute && el.getAttribute('href');
  return (root) => {
    const again = (id && root.querySelector('#' + CSS.escape(id))) || (hrefAttr && root.querySelector(`a[href="${CSS.escape(hrefAttr)}"]`));
    if (again) again.focus({ preventScroll: true });
  };
}

// ---- live updates ---------------------------------------------------------------

function startLive(fileId) {
  const ctrl = new AbortController();
  liveCtrl = ctrl;
  (async () => {
    let backoff = 1000;
    let recovering = false;
    while (!ctrl.signal.aborted && app.fileId === fileId) {
      try {
        // after an error, ask without waiting so the indicator clears at once
        const r = await api.changes(fileId, app.revision, recovering ? 0 : 25, ctrl.signal);
        if (ctrl.signal.aborted) return;
        recovering = false;
        setConnected(true);
        backoff = 1000;
        if (r.instance && r.instance !== app.instance) {
          // the daemon restarted: its revisions started over, so reload everything
          app.instance = r.instance;
          app.revision = r.revision;
          await onChangedElsewhere();
        } else if (r.revision !== app.revision && app.pending === 0) {
          app.revision = r.revision;
          await onChangedElsewhere();
        } else if (app.pending > 0) {
          await sleep(300, ctrl.signal);
        }
      } catch (e) {
        if (e.name === 'AbortError' || ctrl.signal.aborted) return;
        if (e.status === 401) return;
        if (e.status === 404) {
          toast('This file was closed elsewhere.', { kind: 'error' });
          app.navigate('#/files');
          return;
        }
        setConnected(false);
        recovering = true;
        await sleep(backoff, ctrl.signal, true);
        backoff = Math.min(Math.round(backoff * 1.6), 10000);
      }
    }
  })();
}

let wakeUp = null;
/** Waits; a retry wait also ends when the browser is back online or the page is shown again. */
function sleep(ms, signal, wakeable = false) {
  return new Promise((resolve) => {
    const done = () => { clearTimeout(t); if (wakeUp === done) wakeUp = null; resolve(); };
    const t = setTimeout(done, ms);
    if (wakeable) wakeUp = done;
    if (signal) signal.addEventListener('abort', done, { once: true });
  });
}
const wake = () => { if (wakeUp) wakeUp(); };
window.addEventListener('online', wake);
window.addEventListener('focus', wake);
document.addEventListener('visibilitychange', () => { if (!document.hidden) wake(); });

let wasDisconnected = false;
function setConnected(ok) {
  if (!frame) return;
  frame.conn.hidden = ok;
  if (!ok) wasDisconnected = true;
  else if (wasDisconnected) {
    wasDisconnected = false;
    refreshList();
  }
}

async function onChangedElsewhere() {
  await Promise.all([refreshFile(), refreshList()]);
  for (const f of app.forms) f.externalChange && f.externalChange();
  await showView(app.route, { refresh: true });
}

// ---- saving -------------------------------------------------------------------------

let saving = false;
async function save(force) {
  if (!app.fileId || saving) return false;
  const f = app.file;
  if (!force && f && !f.dirty) {
    toast(`${f.name} is up to date — there’s nothing to save.`);
    return true;
  }
  saving = true;
  frame.saveBtn.classList.add('is-busy');
  frame.saveBtn.disabled = true;
  try {
    const r = await app.mutate(() => api.save(app.fileId, force));
    toast(r.saved ? `Saved ${f.name} — ${plural(r.records, 'record', 'records')} written.` : `${f.name} is up to date.`, { kind: 'success' });
    await refreshFile();
    return true;
  } catch (e) {
    if (e.status === 409 && e.body.changedOnDisk) return changedOnDiskDialog();
    toast(e.message, { kind: 'error', timeout: 9000 });
    return false;
  } finally {
    saving = false;
    if (frame) {
      frame.saveBtn.classList.remove('is-busy');
      frame.saveBtn.disabled = false;
    }
  }
}

function changedOnDiskDialog() {
  const f = app.file;
  return new Promise((resolve) => {
    let result = false;
    const d = dialog({
      title: `${f.name} was changed by another program`,
      size: 'sm',
      cls: 'dialog-warn',
      body: [
        h('p.dialog-text', `Since gedcom-shell read ${f.name}, another program has written to it. Saving now would replace that program’s changes with yours.`),
        h('ul.choice-list', [
          h('li', [h('strong', 'Overwrite'), ' writes your version. Whatever the other program wrote is lost.']),
          h('li', [h('strong', 'Cancel'), ` keeps your ${plural(f.dirtyCount || 0, 'unsaved edit', 'unsaved edits')} safe in gedcom-shell, so you can look at the file first.`]),
        ]),
      ],
      actions: [
        button('Cancel', { kind: 'ghost', onclick: () => d.close() }),
        button('Overwrite file', { kind: 'danger', icon: 'save', onclick: async () => { d.close(); result = await save(true); resolve(result); } }),
      ],
      onClose: () => { setTimeout(() => resolve(result), 0); },
    });
  });
}

// ---- keyboard --------------------------------------------------------------------

function isMac() { return /Mac|iPhone|iPad/.test(navigator.platform || navigator.userAgent); }

function onKey(e) {
  if ((e.metaKey || e.ctrlKey) && !e.shiftKey && !e.altKey && e.key.toLowerCase() === 's') {
    e.preventDefault();
    if (app.fileId && !anyDialogOpen()) save(false);
    return;
  }
  if (e.key === '/' && !e.metaKey && !e.ctrlKey && !e.altKey) {
    const t = e.target;
    const typing = t && (t.isContentEditable || /^(INPUT|TEXTAREA|SELECT)$/.test(t.tagName));
    if (typing || anyDialogOpen() || !frame) return;
    e.preventDefault();
    if (app.isPhone() && !(app.route && app.route.section === 'people' && !app.route.key)) {
      app.navigate(href(app.fileId, 'people'));
    }
    frame.searchInput.focus();
    frame.searchInput.select();
  }
}

boot();
