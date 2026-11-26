// A person's page, and the file overview shown when no one is chosen.
import { h, icon } from '../dom.js';
import { api } from '../api.js';
import {
  href, card, chip, personRow, avatar, croppedPhoto, lightbox, button, linkButton, popupMenu,
  prettyDate, SEX, KIND, plural, fileName, life,
} from '../ui.js';
import { openPersonEditor } from './forms.js';
import { addRelativeDialog, linkDialog, unlinkDialog } from './relatives.js';

export async function personView(app, key) {
  const p = await api.person(app.fileId, key);
  app.setTitle(p.name);
  return renderPerson(app, p);
}

function renderPerson(app, p) {
  const isHome = app.file.home && app.file.home.toUpperCase() === p.key.toUpperCase();
  const fid = app.fileId;

  // ---- header
  const first = p.photos.find((x) => x.url);
  const portrait = first
    ? h('button.portrait', { type: 'button', aria: { label: `Show pictures of ${p.name}` }, onclick: () => lightbox(p.photos, p.photos.indexOf(first)) },
      croppedPhoto(first.url, first.crop, 'portrait-frame'))
    : h('div.portrait.is-initials', avatar(p, 'xl'));

  const aka = [
    ...p.otherNames.map((o) => h('span.aka-item', [o.name, o.type ? h('span.aka-type', o.type) : null])),
    ...p.nicknames.map((n) => h('span.aka-item', `“${n}”`)),
  ];

  const birth = p.events.find((e) => e.tag === 'BIRT');
  const death = p.events.find((e) => e.tag === 'DEAT');
  const lifeLine = [
    birth && (birth.date || birth.place) ? h('span', [h('span.muted', 'Born '), prettyDate(birth.date) || '', birth.place ? (birth.date ? ' in ' : 'in ') + birth.place : '']) : null,
    death && (death.date || death.place) ? h('span', [h('span.muted', 'Died '), prettyDate(death.date) || '', death.place ? (death.date ? ' in ' : 'in ') + death.place : '']) : (p.deceased || death ? h('span.muted', 'Deceased') : null),
  ].filter(Boolean);

  const addBtn = button('Add relative', { icon: 'plus', cls: 'has-caret' });
  addBtn.append(icon('chevronDown', 'caret'));
  addBtn.setAttribute('aria-haspopup', 'menu');
  addBtn.addEventListener('click', () => popupMenu(addBtn, [
    { heading: 'Add a new person as…' },
    { label: 'Child', icon: 'person', onSelect: () => addRelativeDialog(app, p, 'child') },
    { label: 'Spouse or partner', icon: 'rings', onSelect: () => addRelativeDialog(app, p, 'spouse') },
    { label: 'Father', icon: 'person', onSelect: () => addRelativeDialog(app, p, 'father') },
    { label: 'Mother', icon: 'person', onSelect: () => addRelativeDialog(app, p, 'mother') },
    { label: 'Sibling', icon: 'people', onSelect: () => addRelativeDialog(app, p, 'sibling') },
    'sep',
    { label: 'Link someone already in the file…', icon: 'link', onSelect: () => linkDialog(app, p) },
  ]));

  const actions = h('div.head-actions', [
    button('Edit', { kind: 'primary', icon: 'edit', onclick: () => openPersonEditor(app, p) }),
    addBtn,
    button('Link', { icon: 'link', title: 'Link someone already in the file', onclick: () => linkDialog(app, p) }),
    linkButton('Ancestors', href(fid, 'pedigree', p.key), { icon: 'pedigree', cls: 'hide-sm' }),
    linkButton('Descendants', href(fid, 'descendants', p.key), { icon: 'descendants', cls: 'hide-sm' }),
  ]);

  const head = h('header.person-head', [
    portrait,
    h('div.head-text', [
      h('div.eyebrow', [
        chip(p.key, 'chip-mono'),
        chip(SEX[p.sex] || 'Unknown', 'chip-sex-' + p.sex),
        isHome ? chip([icon('home'), 'Home person'], 'chip-accent') : null,
      ]),
      // the name comes with its suffix; an older daemon sent it apart
      h('h1.page-title-h', [p.name || '(no name)', p.suffix && !(p.name || '').endsWith(p.suffix) ? h('span.suffix', ' ' + p.suffix) : null]),
      p.lifespan ? h('p.head-lifespan', life(p.lifespan)) : null,
      lifeLine.length ? h('p.head-life', lifeLine) : null,
      aka.length ? h('p.aka', [h('span.aka-label', 'Also known as'), ...aka]) : null,
    ]),
    actions,
  ]);

  // ---- left column
  const left = [];
  left.push(card('Life events', p.events.length ? timeline(p.events) : emptyMini('No events recorded yet.', button('Add events', { icon: 'edit', kind: 'ghost', onclick: () => openPersonEditor(app, p) })), {
    actions: [button('', { icon: 'edit', kind: 'ghost', title: 'Edit events', aria: { label: 'Edit events' }, onclick: () => openPersonEditor(app, p) })],
  }));
  if (p.photos.length) left.push(card(`Pictures`, gallery(p.photos), { actions: [h('span.card-count', String(p.photos.length))] }));
  left.push(card('Notes', p.notes.length ? notesBlock(p.notes) : emptyMini('No notes yet.', button('Add a note', { icon: 'plus', kind: 'ghost', onclick: () => openPersonEditor(app, p, { focus: 'notes' }) })), {
    actions: p.notes.length ? [button('', { icon: 'edit', kind: 'ghost', title: 'Edit notes', aria: { label: 'Edit notes' }, onclick: () => openPersonEditor(app, p, { focus: 'notes' }) })] : null,
  }));
  if (p.records.length) left.push(card('Sources & media', recordLinks(fid, p.records)));

  // ---- right column: family
  const right = [];
  right.push(...parentsCards(app, p));
  right.push(...familyCards(app, p));
  if (p.halfSiblings.length) {
    right.push(card('Half-siblings', h('div.rows', p.halfSiblings.map((s) => personRow(fid, s, { role: sibRole(s, true) })))));
  }

  return h('div.page.person-page', [
    head,
    h('div.person-grid', [h('div.col', left), h('div.col', right)]),
  ]);
}

function emptyMini(text, action) {
  return h('div.empty-mini', [h('p', text), action || null]);
}

function sibRole(s, half) {
  const base = s.sex === 'M' ? 'Brother' : s.sex === 'F' ? 'Sister' : 'Sibling';
  return half ? 'Half-' + base.toLowerCase() : base;
}

// ---- timeline ---------------------------------------------------------------------

/** Dated events in year order; an undated one stays after the event before it. */
function chronological(events) {
  let last = -Infinity;
  return events
    .map((e, i) => {
      const y = e.year != null ? e.year : (e.tag === 'BIRT' ? -Infinity : last);
      if (e.year != null) last = e.year;
      return { e, i, y };
    })
    .sort((a, b) => (a.y - b.y) || (a.i - b.i))
    .map((x) => x.e);
}

/** An event's text; not the `Y` that only marks it as known (`1 DEAT Y`). */
function eventValue(e) {
  if (e.fields) return e.fields.value;
  return e.value === 'Y' && !ATTRIBUTE_TAGS.has(e.tag) ? '' : e.value;
}
const ATTRIBUTE_TAGS = new Set(['OCCU', 'EDUC', 'RELI', 'TITL', 'NATI', 'DSCR', 'PROP', 'NCHI', 'NMR', 'FACT', 'IDNO', 'SSN', 'CAST']);

export function timeline(events) {
  return h('ol.timeline', chronological(events).map((e) => {
    const label = e.type && e.type.toLowerCase() !== e.label.toLowerCase() ? `${e.label} · ${e.type}` : e.label;
    return h('li.event.event-' + e.tag.toLowerCase(), [
      h('div.event-year', e.year != null ? String(e.year) : ''),
      h('div.event-dot', { aria: { hidden: 'true' } }),
      h('div.event-body', [
        h('div.event-label', label),
        eventValue(e) ? h('div.event-value', eventValue(e)) : null,
        e.date ? h('div.event-date', [icon('calendar'), h('span', prettyDate(e.date))]) : null,
        e.place ? h('div.event-place', [icon('pin'), h('span', e.place)]) : null,
      ]),
    ]);
  }));
}

export function notesBlock(notes) {
  return h('div.notes', notes.map((n) => h('p.note-text', n)));
}

export function recordLinks(fileId, records) {
  return h('div.rows', records.map((r) => h('a.record-link', { href: href(fileId, 'records', r.key) }, [
    h('span.record-icon.kind-' + r.kind, icon(KIND[r.kind] ? KIND[r.kind].icon : 'file')),
    h('span.record-text', [h('span.record-title', r.title), h('span.record-meta', `${KIND[r.kind] ? KIND[r.kind].one : r.kind} · ${r.key}`)]),
    icon('chevronRight', 'row-chevron'),
  ])));
}

function gallery(photos) {
  return h('div.gallery', photos.map((ph, i) => {
    if (!ph.url) {
      return h('div.thumb.is-missing', { title: ph.file }, [icon('media'), h('span.thumb-missing', 'Not on this computer'), h('span.thumb-file', fileName(ph.file))]);
    }
    return h('button.thumb', { type: 'button', aria: { label: `Show ${ph.title || fileName(ph.file)}` }, onclick: () => lightbox(photos, i) }, [
      croppedPhoto(ph.url, ph.crop, 'thumb-frame'),
      ph.title ? h('span.thumb-title', ph.title) : null,
    ]);
  }));
}

// ---- family -----------------------------------------------------------------------

function unlinkBtn(app, p, path, other, roleText) {
  return h('button.btn.btn-icon.btn-ghost.unlink-btn', {
    type: 'button', title: `Unlink ${other.name}`, aria: { label: `Unlink ${other.name} as ${roleText}` },
    onclick: () => unlinkDialog(app, p, path, other, roleText),
  }, icon('unlink'));
}

function placeholderRow(label, onclick) {
  return h('button.placeholder-row', { type: 'button', onclick }, [h('span.placeholder-avatar', icon('plus')), h('span', label)]);
}

function parentsCards(app, p) {
  const fid = app.fileId;
  if (!p.parents.length) {
    return [card('Parents', h('div.rows', [
      placeholderRow('Add father', () => addRelativeDialog(app, p, 'father')),
      placeholderRow('Add mother', () => addRelativeDialog(app, p, 'mother')),
      h('div.card-foot-actions', [button('Link existing parent', { icon: 'link', kind: 'ghost', onclick: () => linkDialog(app, p, { as: 'father' }) })]),
    ]))];
  }
  return p.parents.map((par, i) => {
    const rows = [];
    if (par.father) rows.push(personRow(fid, par.father, { role: 'Father', actions: [unlinkBtn(app, p, 'father', par.father, 'father')] }));
    else rows.push(placeholderRow('Add father', () => addRelativeDialog(app, p, 'father')));
    if (par.mother) rows.push(personRow(fid, par.mother, { role: 'Mother', actions: [unlinkBtn(app, p, 'mother', par.mother, 'mother')] }));
    else rows.push(placeholderRow('Add mother', () => addRelativeDialog(app, p, 'mother')));
    const sibs = par.siblings.length
      ? [h('h3.sub-title', `Siblings · ${par.siblings.length}`), ...par.siblings.map((s) => personRow(fid, s, { role: sibRole(s), actions: [unlinkBtn(app, p, 'siblings/' + s.key, s, 'sibling')] }))]
      : [];
    const title = p.parents.length > 1 ? `Parents · ${i + 1} of ${p.parents.length}` : 'Parents';
    return card(title, h('div.rows', [...rows, ...sibs]), {
      actions: [h('a.card-link', { href: href(fid, 'families', par.family), title: `Open family ${par.family}` }, [h('span', 'Family'), icon('chevronRight')])],
    });
  });
}

function familyCards(app, p) {
  const fid = app.fileId;
  if (!p.families.length) {
    return [card('Spouses & children', h('div.rows', [
      h('p.muted.small.pad', 'No partner or children recorded.'),
      placeholderRow('Add spouse or partner', () => addRelativeDialog(app, p, 'spouse')),
      placeholderRow('Add child', () => addRelativeDialog(app, p, 'child')),
    ]))];
  }
  // A family marked hidden (`1 _HIDE Y`) is left out until the link after the
  // others asks for it; its own page and every list still show it.
  const cards = p.families.map((f) => {
    const partnerRole = f.partner ? (f.partner.sex === 'M' ? 'Husband' : f.partner.sex === 'F' ? 'Wife' : 'Partner') : '';
    const rows = [];
    if (f.partner) rows.push(personRow(fid, f.partner, { role: f.divorced ? 'Former ' + partnerRole.toLowerCase() : partnerRole, actions: [unlinkBtn(app, p, 'spouses/' + f.partner.key, f.partner, partnerRole.toLowerCase())] }));
    else {
      const slot = f.role === 'husband' ? 'wife' : 'husband';
      const btn = h('button.placeholder-row', { type: 'button', aria: { haspopup: 'menu' } }, [h('span.placeholder-avatar', icon('plus')), h('span', `Add ${slot === 'husband' ? 'a husband' : 'a wife'}`)]);
      btn.addEventListener('click', () => popupMenu(btn, [
        { label: `New person as ${slot}`, icon: 'plus', onSelect: () => addRelativeDialog(app, p, slot, { family: f.family }) },
        { label: 'Link someone already in the file', icon: 'link', onSelect: () => linkDialog(app, p, { as: `families/${f.family}/${slot}`, title: `Choose the ${slot} in family ${f.family}` }) },
      ]));
      rows.push(btn);
    }
    const facts = [];
    if (f.married) {
      const m = f.married;
      facts.push(h('span.fam-fact', [icon('rings'), h('span', ['Married', m.date ? ' ' + prettyDate(m.date) : '', m.place ? ' · ' + m.place : ''])]));
    }
    if (f.divorced) facts.push(chip('Divorced', 'chip-warn'));
    const kids = f.children.map((c) => personRow(fid, c, {
      role: c.sex === 'M' ? 'Son' : c.sex === 'F' ? 'Daughter' : 'Child',
      actions: [unlinkBtn(app, p, 'children/' + c.key, c, 'child')],
    }));
    const famMenu = h('button.btn.btn-icon.btn-ghost', { type: 'button', aria: { label: `Family ${f.family} actions`, haspopup: 'menu' } }, icon('more'));
    famMenu.addEventListener('click', () => popupMenu(famMenu, [
      { label: 'Open family page', icon: 'family', href: href(fid, 'families', f.family) },
      { label: 'Add child', icon: 'plus', onSelect: () => addRelativeDialog(app, p, 'child', { family: f.family }) },
      { label: 'Link existing child', icon: 'link', onSelect: () => linkDialog(app, p, { as: `families/${f.family}/children` }) },
      'sep',
      { label: `Leave family ${f.family}…`, icon: 'unlink', danger: true, onSelect: () => unlinkDialog(app, p, 'families/' + f.family, null, `a partner in family ${f.family}`) },
    ], { align: 'end' }));
    const title = f.partner ? `Family with ${f.partner.given || f.partner.name}` : 'Family';
    return card(title, h('div.rows', [
      rows,
      facts.length ? h('div.fam-facts', facts) : null,
      h('h3.sub-title', f.children.length ? `Children · ${f.children.length}` : 'Children'),
      kids.length ? kids : h('p.muted.small.pad', 'No children recorded.'),
      placeholderRow('Add child', () => addRelativeDialog(app, p, 'child', { family: f.family })),
    ]), { actions: [famMenu] });
  });
  const hidden = cards.filter((c, i) => p.families[i].hidden);
  if (!hidden.length) return cards;
  hidden.forEach((c) => { c.hidden = true; });
  const reveal = h('p.hidden-families', h('button.link-btn', {
    type: 'button',
    onclick: () => { hidden.forEach((c) => { c.hidden = false; }); reveal.remove(); },
  }, hiddenLabel(hidden.length)));
  return [...cards, reveal];
}

function hiddenLabel(n) {
  return n === 1 ? 'Show 1 hidden family' : `Show ${n} hidden families`;
}

// ---- overview ------------------------------------------------------------------------

export async function overviewView(app) {
  const f = await api.file(app.fileId);
  app.file = f;
  app.setTitle('Overview');
  let home = null;
  if (f.home) { try { home = await api.person(app.fileId, f.home); } catch { home = null; } }
  const stat = (n, label, hrefValue, ic) => h('a.stat', { href: hrefValue }, [h('span.stat-icon', icon(ic)), h('span.stat-n', n.toLocaleString()), h('span.stat-label', label)]);
  const fid = app.fileId;
  return h('div.page.overview', [
    h('header.page-head', [
      h('div.page-head-text', [
        h('div.eyebrow', [chip(`GEDCOM ${f.gedcom}`, 'chip-quiet'), chip(f.encoding, 'chip-quiet')]),
        h('h1.page-title-h', f.name),
        h('p.page-sub.mono-path', f.path),
      ]),
    ]),
    h('div.stats', [
      stat(f.people, f.people === 1 ? 'person' : 'people', href(fid, 'people'), 'people'),
      stat(f.families, f.families === 1 ? 'family' : 'families', href(fid, 'families'), 'family'),
      stat(f.records.sources || 0, 'sources', href(fid, 'sources'), 'source'),
      stat(f.records.notes || 0, 'notes', href(fid, 'notes'), 'note'),
      stat(f.records.media || 0, 'media', href(fid, 'media'), 'media'),
    ]),
    home ? card('Home person', h('div.rows', [
      personRow(fid, home, { role: 'Starts here', size: 'lg' }),
      h('p.muted.small.pad', 'The file starts at this person: the first one in it.'),
    ])) : null,
    f.warnings.length ? card('Warnings', h('div.rows', [
      h('p.small.pad', `${plural(f.warnings.length, 'thing', 'things')} looked odd while reading the file.`),
      h('div.card-foot-actions', linkButton('Show warnings', href(fid, 'warnings'), { icon: 'warning' })),
    ])) : null,
    h('p.overview-tip.muted.small', [icon('search'), h('span', ['Find anyone with the search on the left — press ', h('kbd', '/'), ' to jump there.'])]),
  ]);
}

