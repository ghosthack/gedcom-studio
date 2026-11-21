// Shared pieces of the interface: dialogs, toasts, menus, the lightbox,
// avatars and the small building blocks every page uses.
import { h, icon, clear, append } from './dom.js';

// ---- routes -------------------------------------------------------------------

/** `#/f/ID/people/I1`, each part encoded. */
export function href(fileId, ...parts) {
  return '#/f/' + encodeURIComponent(fileId) + parts.map((p) => '/' + encodeURIComponent(p)).join('');
}

export const KIND = {
  notes: { label: 'Notes', one: 'Note', icon: 'note' },
  sources: { label: 'Sources', one: 'Source', icon: 'source' },
  media: { label: 'Media', one: 'Media', icon: 'media' },
};

export const SEX = { M: 'Male', F: 'Female', U: 'Unknown' };

/** `1850–~1920` → `1850–c. 1920`: the API's short forms, made readable. */
export function life(s) {
  return (s || '').replace(/~\s*/g, 'c.\u202f');
}

export function plural(n, one, many) {
  return `${n.toLocaleString()} ${n === 1 ? one : many}`;
}

// ---- small blocks -------------------------------------------------------------------

export function button(label, { kind = '', icon: ic, onclick, title, type = 'button', disabled, cls = '', aria } = {}) {
  const b = h('button.btn' + (kind ? '.btn-' + kind : '') + (cls ? '.' + cls.split(' ').join('.') : ''),
    { type, onclick, title, disabled, aria });
  if (ic) b.append(icon(ic));
  if (label) b.append(h('span', label));
  if (!label) b.classList.add('btn-icon');
  return b;
}

export function linkButton(label, hrefValue, { kind = '', icon: ic, cls = '' } = {}) {
  const a = h('a.btn' + (kind ? '.btn-' + kind : '') + (cls ? '.' + cls : ''), { href: hrefValue });
  if (ic) a.append(icon(ic));
  if (label) a.append(h('span', label));
  return a;
}

export function chip(text, cls = '') {
  return h('span.chip' + (cls ? '.' + cls : ''), text);
}

export function emptyState({ icon: ic = 'search', title, text, action }) {
  return h('div.empty', [
    h('div.empty-icon', icon(ic)),
    h('p.empty-title', title),
    text ? h('p.empty-text', text) : null,
    action || null,
  ]);
}

export function errorState(err, retry) {
  return h('div.empty.empty-error', [
    h('div.empty-icon', icon('warning')),
    h('p.empty-title', err.status === 404 ? 'Not found' : 'Something went wrong'),
    h('p.empty-text', err.message),
    retry ? button('Try again', { icon: 'refresh', onclick: retry }) : null,
  ]);
}

export function card(title, body, { actions, cls = '', id } = {}) {
  return h('section.card' + (cls ? '.' + cls : ''), { id, aria: { label: title || null } }, [
    title || actions ? h('header.card-head', [title ? h('h2.card-title', title) : h('span'), actions ? h('div.card-actions', actions) : null]) : null,
    h('div.card-body', body),
  ]);
}

export function skeleton(kind = 'page') {
  const line = (cls = '') => h('div.skel' + (cls ? '.' + cls : ''));
  if (kind === 'list') {
    return h('div.skel-list', { aria: { busy: 'true', label: 'Loading' } },
      Array.from({ length: 8 }, () => h('div.skel-row', [line('skel-avatar'), h('div.skel-stack', [line('w60'), line('w30.thin')])])));
  }
  return h('div.page.skel-page', { aria: { busy: 'true', label: 'Loading' } }, [
    h('div.skel-head', [line('skel-photo'), h('div.skel-stack', [line('w50.tall'), line('w30'), line('w40.thin')])]),
    h('div.grid-2', [
      h('div.card', h('div.card-body', [line('w30'), line('w80'), line('w70'), line('w60'), line('w75')])),
      h('div.card', h('div.card-body', [line('w30'), line('w60'), line('w50'), line('w70')])),
    ]),
  ]);
}

// ---- avatars and pictures -------------------------------------------------------------

export function initials(p) {
  const g = (p.given || '').trim();
  const sn = (p.surname || '').trim();
  let a = g ? g[0] : '';
  let b = sn ? sn[0] : '';
  if (!a && !b) {
    const parts = (p.name || '?').trim().split(/\s+/);
    a = parts[0] ? parts[0][0] : '?';
    b = parts.length > 1 ? parts[parts.length - 1][0] : '';
  }
  return (a + b).toUpperCase();
}

export function avatar(p, size = 'md') {
  const el = h('span.avatar.avatar-' + size + '.sex-' + (p && p.sex ? p.sex : 'U'), { aria: { hidden: 'true' } });
  if (p && p.photo && p.photoCrop) {
    const frame = croppedPhoto(p.photo, p.photoCrop, 'avatar-frame');
    frame.querySelector('img').addEventListener('error', () => { frame.remove(); el.textContent = initials(p); });
    el.append(frame);
  } else if (p && p.photo) {
    const img = h('img', { src: p.photo, alt: '', loading: 'lazy', decoding: 'async', draggable: 'false' });
    img.addEventListener('error', () => { img.remove(); el.textContent = initials(p); });
    el.append(img);
  } else {
    el.textContent = p ? initials(p) : '?';
  }
  return el;
}

/**
 * A picture shown through its GEDCOM crop (pixels of the original; a width or
 * height of 0 runs to the edge), scaled to fill its frame.
 */
export function croppedPhoto(url, crop, cls = '') {
  const frame = h('span.photo-frame' + (cls ? '.' + cls : ''));
  const img = h('img', { src: url, alt: '', decoding: 'async', draggable: 'false' });
  frame.append(img);
  if (crop && (crop.left || crop.top || crop.width || crop.height)) {
    frame.classList.add('is-cropped');
    const place = () => {
      const nw = img.naturalWidth, nh = img.naturalHeight;
      if (!nw || !nh) return;
      const fw = frame.clientWidth, fh = frame.clientHeight;
      if (!fw || !fh) return;
      const left = Math.min(crop.left, nw - 1), top = Math.min(crop.top, nh - 1);
      const cw = Math.max(1, crop.width ? Math.min(crop.width, nw - left) : nw - left);
      const ch = Math.max(1, crop.height ? Math.min(crop.height, nh - top) : nh - top);
      const scale = Math.max(fw / cw, fh / ch);
      img.style.width = nw * scale + 'px';
      img.style.height = nh * scale + 'px';
      img.style.left = -(left * scale) + (fw - cw * scale) / 2 + 'px';
      img.style.top = -(top * scale) + (fh - ch * scale) / 2 + 'px';
    };
    img.addEventListener('load', () => requestAnimationFrame(place));
    if (typeof ResizeObserver !== 'undefined') new ResizeObserver(place).observe(frame);
  }
  img.addEventListener('error', () => frame.classList.add('is-broken'));
  return frame;
}

// ---- people in lists -----------------------------------------------------------------

/**
 * A row for a person: avatar, name, lifespan and an optional role; the
 * whole row is a link. `actions` sit beside the link, not inside it.
 */
export function personRow(fileId, p, { role, note, actions, current, size = 'md' } = {}) {
  const link = h('a.person-link', { href: href(fileId, 'people', p.key), aria: { current: current ? 'page' : null } }, [
    avatar(p, size),
    h('span.person-text', [
      h('span.person-name', p.name || '(no name)'),
      h('span.person-meta', [
        p.lifespan ? h('span.lifespan', life(p.lifespan)) : null,
        note ? h('span.person-note', note) : null,
      ]),
    ]),
    role ? h('span.role', role) : null,
  ]);
  return h('div.person-row' + (current ? '.is-current' : ''), [link, actions && actions.length ? h('div.row-actions', actions) : null]);
}

// ---- toasts ---------------------------------------------------------------------

let toastHost;
export function toast(message, { kind = 'info', action, timeout = 5200 } = {}) {
  if (!toastHost) {
    toastHost = h('div.toasts', { role: 'status', aria: { live: 'polite' } });
    if (toastHost.showPopover) toastHost.setAttribute('popover', 'manual');
    document.body.append(toastHost);
  }
  // in the top layer, so a toast shows above an open dialog
  if (toastHost.showPopover) {
    try {
      if (toastHost.matches(':popover-open')) toastHost.hidePopover();
      toastHost.showPopover();
    } catch { /* not supported: it stays in the page */ }
  }
  const ic = kind === 'error' ? 'warning' : kind === 'success' ? 'check' : 'note';
  const t = h('div.toast.toast-' + kind, [
    h('span.toast-icon', icon(ic)),
    h('span.toast-text', message),
  ]);
  if (action) {
    const a = action.href
      ? h('a.toast-action', { href: action.href, onclick: () => dismiss() }, action.label)
      : h('button.toast-action', { type: 'button', onclick: () => { dismiss(); action.onClick(); } }, action.label);
    t.append(a);
  }
  const x = h('button.toast-close', { type: 'button', aria: { label: 'Dismiss' }, onclick: () => dismiss() }, icon('close'));
  t.append(x);
  toastHost.append(t);
  requestAnimationFrame(() => t.classList.add('is-in'));
  let timer = setTimeout(dismiss, timeout);
  t.addEventListener('mouseenter', () => clearTimeout(timer));
  t.addEventListener('mouseleave', () => { timer = setTimeout(dismiss, 2500); });
  function dismiss() {
    clearTimeout(timer);
    t.classList.remove('is-in');
    t.classList.add('is-out');
    setTimeout(() => t.remove(), 220);
  }
  return dismiss;
}

// ---- dialogs ----------------------------------------------------------------------

const openDialogs = new Set();
export function anyDialogOpen() { return openDialogs.size > 0; }

/**
 * A modal dialog. `body` is a node (or array); `actions` are buttons for the
 * footer. With `onSubmit`, the dialog is a form: Enter submits. `onCancel`
 * may return false to keep it open (Esc and the close button ask it first).
 */
export function dialog({ title, subtitle, body, actions = [], size = 'md', onSubmit, onCancel, onClose, cls = '' }) {
  const id = 'dlg-' + Math.random().toString(36).slice(2, 8);
  const el = h('dialog.dialog.dialog-' + size + (cls ? '.' + cls : ''), { aria: { labelledby: id + '-t' } });
  const inner = onSubmit ? h('form.dialog-inner', { novalidate: true }) : h('div.dialog-inner');
  const closeBtn = h('button.btn.btn-icon.btn-ghost.dialog-x', { type: 'button', aria: { label: 'Close' } }, icon('close'));
  const head = h('header.dialog-head', [
    h('div.dialog-titles', [h('h2.dialog-title', { id: id + '-t' }, title), subtitle ? h('p.dialog-sub', subtitle) : null]),
    closeBtn,
  ]);
  const bodyEl = h('div.dialog-body', body);
  const foot = h('footer.dialog-foot', actions);
  append(inner, [head, bodyEl, actions.length ? foot : null]);
  el.append(inner);
  document.body.append(el);
  let closed = false;
  const api = {
    el, body: bodyEl, foot,
    close(result) {
      if (closed) return;
      closed = true;
      openDialogs.delete(api);
      el.classList.add('is-closing');
      setTimeout(() => { if (el.open) el.close(); el.remove(); }, 140);
      if (onClose) onClose(result);
    },
    tryCancel() {
      if (onCancel && onCancel() === false) return;
      api.close(null);
    },
    setBusy(busy) {
      el.classList.toggle('is-busy', busy);
      for (const b of foot.querySelectorAll('button')) b.disabled = busy;
    },
  };
  closeBtn.addEventListener('click', () => api.tryCancel());
  el.addEventListener('cancel', (e) => { e.preventDefault(); api.tryCancel(); });
  el.addEventListener('mousedown', (e) => { if (e.target === el) el.dataset.downOutside = '1'; else delete el.dataset.downOutside; });
  el.addEventListener('click', (e) => {
    // a click on the backdrop (the dialog itself, outside its box)
    if (e.target === el && el.dataset.downOutside && !onSubmit) api.tryCancel();
  });
  if (onSubmit) inner.addEventListener('submit', (e) => { e.preventDefault(); onSubmit(e); });
  openDialogs.add(api);
  el.showModal();
  const first = el.querySelector('[autofocus]') || bodyEl.querySelector('input:not([type=hidden]), select, textarea');
  if (first) first.focus();
  else closeBtn.focus();
  return api;
}

/** Resolves true when confirmed. */
export function confirmDialog({ title, text, details, confirm = 'OK', danger = false, cancel = 'Cancel' }) {
  return new Promise((resolve) => {
    let result = false;
    const ok = button(confirm, { kind: danger ? 'danger' : 'primary' });
    const no = button(cancel, { kind: 'ghost' });
    const d = dialog({
      title, size: 'sm',
      body: [text ? h('p.dialog-text', text) : null, details || null],
      actions: [no, ok],
      onClose: () => resolve(result),
    });
    ok.addEventListener('click', () => { result = true; d.close(true); });
    no.addEventListener('click', () => d.close(false));
    ok.focus();
  });
}

/** An inline message box for forms: kind is error, warn or info. */
export function notice(kind, content, { title } = {}) {
  return h('div.notice.notice-' + kind, { role: kind === 'error' ? 'alert' : 'status' }, [
    h('span.notice-icon', icon(kind === 'info' ? 'note' : 'warning')),
    h('div.notice-text', [title ? h('strong', title) : null, content]),
  ]);
}

// ---- menus ---------------------------------------------------------------------

let openMenu = null;
export function closeMenu() { if (openMenu) openMenu(); }

/** A small popup menu under `anchor`: items are {label, icon, onSelect, href, danger, hint} or 'sep'. */
export function popupMenu(anchor, items, { align = 'start' } = {}) {
  closeMenu();
  const menu = h('div.menu', { role: 'menu' });
  const entries = [];
  for (const it of items) {
    if (it === 'sep') { menu.append(h('div.menu-sep', { role: 'separator' })); continue; }
    if (it.heading) { menu.append(h('div.menu-heading', it.heading)); continue; }
    const content = [it.icon ? icon(it.icon) : h('span.icon-space'), h('span.menu-label', it.label), it.hint ? h('span.menu-hint', it.hint) : null];
    const el = it.href
      ? h('a.menu-item' + (it.danger ? '.is-danger' : ''), { role: 'menuitem', href: it.href, tabindex: '-1' }, content)
      : h('button.menu-item' + (it.danger ? '.is-danger' : ''), { type: 'button', role: 'menuitem', tabindex: '-1' }, content);
    el.addEventListener('click', () => { close(); if (it.onSelect) it.onSelect(); });
    entries.push(el);
    menu.append(el);
  }
  document.body.append(menu);
  const r = anchor.getBoundingClientRect();
  const mw = menu.offsetWidth, mh = menu.offsetHeight;
  let left = align === 'end' ? r.right - mw : r.left;
  left = Math.max(8, Math.min(left, window.innerWidth - mw - 8));
  let top = r.bottom + 6;
  if (top + mh > window.innerHeight - 8) top = Math.max(8, r.top - mh - 6);
  menu.style.left = left + 'px';
  menu.style.top = top + 'px';
  anchor.setAttribute('aria-expanded', 'true');
  requestAnimationFrame(() => menu.classList.add('is-in'));
  if (entries[0]) entries[0].focus();
  const onDown = (e) => { if (!menu.contains(e.target) && !anchor.contains(e.target)) close(); };
  const onKey = (e) => {
    const i = entries.indexOf(document.activeElement);
    if (e.key === 'Escape') { e.preventDefault(); e.stopPropagation(); close(); anchor.focus(); }
    else if (e.key === 'ArrowDown') { e.preventDefault(); entries[(i + 1) % entries.length].focus(); }
    else if (e.key === 'ArrowUp') { e.preventDefault(); entries[(i - 1 + entries.length) % entries.length].focus(); }
    else if (e.key === 'Tab') close();
  };
  setTimeout(() => document.addEventListener('mousedown', onDown), 0);
  document.addEventListener('keydown', onKey, true);
  window.addEventListener('resize', close, { once: true });
  function close() {
    if (openMenu !== close) return;
    openMenu = null;
    anchor.setAttribute('aria-expanded', 'false');
    document.removeEventListener('mousedown', onDown);
    document.removeEventListener('keydown', onKey, true);
    menu.remove();
  }
  openMenu = close;
  return close;
}

// ---- lightbox ---------------------------------------------------------------------

/** Full-screen viewer for `photos` ({url, title, date, file}), starting at `index`. */
export function lightbox(photos, index = 0) {
  const shown = photos.filter((p) => p.url);
  if (!shown.length) return;
  let i = Math.max(0, shown.indexOf(photos[index]));
  const img = h('img.lightbox-img', { alt: '' });
  const caption = h('div.lightbox-caption');
  const counter = h('span.lightbox-count');
  const prev = h('button.lightbox-nav.prev', { type: 'button', aria: { label: 'Previous picture' } }, icon('chevronLeft'));
  const next = h('button.lightbox-nav.next', { type: 'button', aria: { label: 'Next picture' } }, icon('chevronRight'));
  const close = h('button.lightbox-close', { type: 'button', aria: { label: 'Close' } }, icon('close'));
  const stage = h('div.lightbox-stage', [img]);
  const el = h('dialog.lightbox', { aria: { label: 'Pictures' } }, [stage, prev, next, close, h('div.lightbox-bar', [caption, counter])]);
  document.body.append(el);
  const show = () => {
    const p = shown[i];
    img.classList.remove('is-loaded');
    img.src = p.url;
    img.alt = p.title || p.file || 'Picture';
    clearChildren(caption);
    caption.append(h('strong', p.title || fileName(p.file) || 'Picture'));
    const meta = [p.date, p.file].filter(Boolean).join(' · ');
    if (meta) caption.append(h('span', meta));
    counter.textContent = shown.length > 1 ? `${i + 1} / ${shown.length}` : '';
    prev.hidden = next.hidden = shown.length < 2;
  };
  img.addEventListener('load', () => img.classList.add('is-loaded'));
  const go = (d) => { i = (i + d + shown.length) % shown.length; show(); };
  prev.addEventListener('click', () => go(-1));
  next.addEventListener('click', () => go(1));
  const done = () => { document.removeEventListener('keydown', onKey); el.close(); el.remove(); openDialogs.delete(entry); };
  const entry = { close: done };
  close.addEventListener('click', done);
  stage.addEventListener('click', (e) => { if (e.target === stage) done(); });
  el.addEventListener('cancel', (e) => { e.preventDefault(); done(); });
  const onKey = (e) => {
    if (e.key === 'ArrowLeft') go(-1);
    else if (e.key === 'ArrowRight') go(1);
  };
  document.addEventListener('keydown', onKey);
  let sx = null;
  stage.addEventListener('touchstart', (e) => { sx = e.touches[0].clientX; }, { passive: true });
  stage.addEventListener('touchend', (e) => {
    if (sx == null) return;
    const dx = e.changedTouches[0].clientX - sx;
    if (Math.abs(dx) > 50) go(dx < 0 ? 1 : -1);
    sx = null;
  });
  openDialogs.add(entry);
  show();
  el.showModal();
  close.focus();
}

function clearChildren(el) { clear(el); }

export function fileName(path) {
  if (!path) return '';
  const parts = String(path).split(/[\\/]/);
  return parts[parts.length - 1];
}

export function isImageName(name) {
  return /\.(jpe?g|png|gif|webp|bmp|svg|avif|heic|tiff?)$/i.test(name || '');
}

/** "Born · 12 JUN 1850" style date for display: GEDCOM words made gentler. */
export function prettyDate(d) {
  if (!d) return '';
  return d
    .replace(/^ABT\b/, 'about').replace(/^EST\b/, 'estimated').replace(/^CAL\b/, 'calculated')
    .replace(/^BEF\b/, 'before').replace(/^AFT\b/, 'after')
    .replace(/^BET (.+) AND (.+)$/, 'between $1 and $2')
    .replace(/^FROM (.+) TO (.+)$/, 'from $1 to $2')
    .replace(/\b(JAN|FEB|MAR|APR|MAY|JUN|JUL|AUG|SEP|OCT|NOV|DEC)\b/g, (m) => m[0] + m.slice(1).toLowerCase());
}
