// Notes, sources and media: lists, record pages, and the file's warnings.
import { h, icon, replace, debounce } from '../dom.js';
import { api } from '../api.js';
import { href, card, chip, personRow, emptyState, plural, KIND, isImageName, fileName, lightbox, linkButton, button } from '../ui.js';
import { openRecordEditor } from './forms.js';

export const filters = {}; // what each list was filtered by, kept across refreshes

export async function recordsView(app, kind) {
  const fid = app.fileId;
  const info = KIND[kind];
  app.setTitle(info.label);
  const filter = h('input.input.filter-input', {
    id: 'filter-' + kind, type: 'search', placeholder: `Filter ${info.label.toLowerCase()} by title or key`,
    aria: { label: `Filter ${info.label.toLowerCase()}` }, autocomplete: 'off', spellcheck: 'false',
    value: filters[kind] || '',
  });
  const list = h('div.card.list-card');
  const count = h('span');
  let ctrl = null;
  const load = async () => {
    const q = filter.value.trim();
    filters[kind] = q;
    if (ctrl) ctrl.abort();
    const my = ctrl = new AbortController();
    let r;
    try {
      r = await api.records(fid, kind, q, my.signal);
    } catch (e) {
      if (e.name === 'AbortError') return;
      replace(list, h('p.list-error', e.message));
      return;
    }
    if (my !== ctrl) return;
    count.textContent = q ? plural(r.records.length, 'match', 'matches') : plural(r.records.length, info.one.toLowerCase(), info.label.toLowerCase());
    if (!r.records.length) {
      replace(list, emptyState(q
        ? { icon: 'search', title: `No ${info.label.toLowerCase()} match “${q}”`, text: 'The filter looks at titles and keys.' }
        : { icon: info.icon, title: `No ${info.label.toLowerCase()} in this file`, text: kind === 'media' ? 'Pictures and documents linked from the file appear here.' : `${info.label} linked from people and families appear here.` }));
      return;
    }
    replace(list, r.records.map((rec) => h('a.list-row', { href: href(fid, 'records', rec.key) }, [
      h('span.list-icon.kind-' + kind, icon(info.icon)),
      h('span.list-text', [
        h('span.list-title', rec.title),
        h('span.list-meta', rec.usedBy ? `Used by ${plural(rec.usedBy, 'record', 'records')}` : 'Not used by anyone'),
      ]),
      chip(rec.key, 'chip-mono'),
      icon('chevronRight', 'row-chevron'),
    ])));
  };
  filter.addEventListener('input', debounce(load, 150));
  await load();
  const counts = app.file.records || {};
  const seg = h('nav.segmented', { aria: { label: 'Record kind' } }, Object.entries(KIND).map(([k, v]) =>
    h('a.seg' + (k === kind ? '.is-active' : ''), { href: href(fid, k), aria: { current: k === kind ? 'page' : null } }, [
      icon(v.icon), h('span', v.label), h('span.seg-count', String(counts[k] ?? '')),
    ])));
  return h('div.page.list-page', [
    h('header.page-head', [h('div.page-head-text', [h('h1.page-title-h', info.label), h('p.page-sub', count)])]),
    h('div.list-toolbar', [seg, h('div.search-box.inline', [icon('search', 'search-icon'), filter])]),
    list,
  ]);
}

export async function recordView(app, key) {
  const fid = app.fileId;
  const r = await api.record(fid, key);
  const info = KIND[r.kind] || { one: r.kind, icon: 'file', label: r.kind };
  app.setTitle(r.title);
  app.lastKind = KIND[r.kind] ? r.kind : app.lastKind;

  const blocks = [];
  const fileField = r.fields.find((f) => f.tag === 'FILE');
  const formField = r.fields.find((f) => f.tag === 'FORM' || f.tag === 'MIME');
  if (r.kind === 'media') {
    const name = fileField ? fileField.value : '';
    const image = r.mime ? /^image\//i.test(r.mime) : isImageName(name) || (formField && /^(image\/)?(jpe?g|png|gif|webp|bmp|svg)/i.test(formField.value));
    if (r.file && image) {
      const photo = { url: r.file, title: r.title, file: name, date: '' };
      blocks.push(h('section.card.media-preview', [
        h('button.preview-btn', { type: 'button', aria: { label: `Show ${r.title} larger` }, onclick: () => lightbox([photo], 0) },
          h('img.preview-img', { src: r.file, alt: r.title, decoding: 'async' })),
      ]));
    } else if (r.file) {
      blocks.push(card('File', h('div.rows', [
        h('a.record-link', { href: r.file, target: '_blank', rel: 'noopener noreferrer' }, [
          h('span.record-icon', icon('file')),
          h('span.record-text', [h('span.record-title', fileName(name) || 'Open the file'), h('span.record-meta', 'Opens in a new tab')]),
          icon('external', 'row-chevron'),
        ]),
      ])));
    } else if (name) {
      blocks.push(h('div.notice.notice-info', [h('span.notice-icon', icon('media')), h('div.notice-text', [h('strong', 'The file isn’t on this computer'), h('span.mono-path', name)])]));
    }
  }
  const edit = () => openRecordEditor(app, r);
  if (r.text) blocks.push(card('Text', h('p.note-text.note-large', r.text), { actions: [button('', { icon: 'edit', kind: 'ghost', title: 'Edit the text', aria: { label: 'Edit the text' }, onclick: edit })] }));

  const shown = r.fields.filter((f) => !(r.kind === 'notes' && f.tag === 'CONT'));
  if (shown.length) {
    blocks.push(card('Details', h('dl.fields', shown.map((f) => h('div.field-row.depth-' + Math.min(f.depth, 5), [
      h('dt.field-label', [f.label, f.label !== f.tag ? h('span.field-tag', f.tag) : null]),
      h('dd.field-value', f.link && f.linkKind
        ? h('a.inline-link', { href: href(fid, 'records', f.link) }, [icon(KIND[f.linkKind] ? KIND[f.linkKind].icon : 'link'), h('span', f.value)])
        : f.value ? f.value : h('span.muted', '—')),
    ]))), { actions: [button('', { icon: 'edit', kind: 'ghost', title: 'Edit the details', aria: { label: 'Edit the details' }, onclick: edit })] }));
  }

  const users = r.users.map((u) => {
    if (u.kind === 'person' && u.person) return personRow(fid, u.person, { role: 'Person' });
    const target = u.kind === 'family' ? href(fid, 'families', u.key) : href(fid, 'records', u.key);
    const ic = u.kind === 'family' ? 'rings' : (KIND[u.kind] ? KIND[u.kind].icon : 'file');
    return h('a.record-link', { href: target }, [
      h('span.record-icon', icon(ic)),
      h('span.record-text', [h('span.record-title', u.title), h('span.record-meta', `${u.kind === 'family' ? 'Family' : (KIND[u.kind] ? KIND[u.kind].one : u.kind)} · ${u.key}`)]),
      icon('chevronRight', 'row-chevron'),
    ]);
  });
  const usersCard = card(r.users.length ? `Used by · ${r.users.length}` : 'Used by', users.length ? h('div.rows', users) : h('p.muted.small.pad', 'Nothing in the file points at this yet.'));

  return h('div.page.record-page', [
    h('header.page-head', [
      h('span.head-icon.kind-' + r.kind, icon(info.icon)),
      h('div.page-head-text', [
        h('div.eyebrow', [chip(r.key, 'chip-mono'), chip(info.one, 'chip-quiet')]),
        h('h1.page-title-h', r.title),
        h('p.page-sub', [r.usedBy ? `Used by ${plural(r.usedBy, 'record', 'records')}` : 'Not used by anyone', ' · ', h('a', { href: href(fid, r.kind) }, `All ${info.label.toLowerCase()}`)]),
      ]),
      h('div.head-actions', [button('Edit', { kind: 'primary', icon: 'edit', onclick: edit })]),
    ]),
    h('div.person-grid', [h('div.col', blocks.length ? blocks : [card('Details', h('p.muted.small.pad', 'This record is empty.'))]), h('div.col', usersCard)]),
  ]);
}

export async function warningsView(app) {
  const f = await api.file(app.fileId);
  app.file = f;
  app.setTitle('Warnings');
  const list = f.warnings.length
    ? h('ol.card.warning-list', f.warnings.map((w) => h('li.warning-row', [h('span.warning-icon', icon('warning')), h('span.warning-text', w)])))
    : h('div.card', emptyState({ icon: 'check', title: 'No warnings', text: `${f.name} was read without any problems.` }));
  return h('div.page.list-page', [
    h('header.page-head', [h('div.page-head-text', [
      h('h1.page-title-h', 'Warnings'),
      h('p.page-sub', f.warnings.length ? `${plural(f.warnings.length, 'thing', 'things')} gedcom-shell noticed while reading ${f.name}. They don’t stop you from browsing or editing.` : 'Everything read cleanly.'),
    ])]),
    list,
    h('p', linkButton('Back to people', href(app.fileId, 'people'), { icon: 'chevronLeft', kind: 'ghost' })),
  ]);
}
