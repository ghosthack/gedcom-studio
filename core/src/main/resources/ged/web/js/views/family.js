// A family's page and the list of families.
import { h, icon, replace, debounce } from '../dom.js';
import { api } from '../api.js';
import { href, card, chip, personRow, button, emptyState, prettyDate, plural, popupMenu } from '../ui.js';
import { timeline, notesBlock, recordLinks } from './person.js';
import { openFamilyEditor } from './forms.js';
import { filters } from './records.js';
import { addRelativeDialog, linkDialog, unlinkDialog } from './relatives.js';

export async function familyView(app, key) {
  const f = await api.family(app.fileId, key);
  app.setTitle(f.title);
  const fid = app.fileId;
  const anchor = f.husband || f.wife; // the person relationship commands run from

  const partner = (p, role, slot) => {
    if (p) {
      return h('div.partner', [
        personRow(fid, p, { role, size: 'lg', actions: [h('button.btn.btn-icon.btn-ghost.unlink-btn', {
          type: 'button', aria: { label: `Take ${p.name} out of this family` }, title: 'Unlink',
          onclick: () => unlinkDialog(app, p, 'families/' + f.key, null, `${role.toLowerCase()} in family ${f.key}`),
        }, icon('unlink'))] }),
      ]);
    }
    const other = slot === 'husband' ? f.wife : (f.husband || null);
    const from = other || f.children[0] || null; // whom the add command runs from
    if (!from) return h('div.partner.is-empty', h('p.muted.small.pad', `No ${slot} recorded.`));
    const btn = h('button.placeholder-row', { type: 'button', aria: { haspopup: 'menu' } },
      [h('span.placeholder-avatar', icon('plus')), h('span', `Add ${slot === 'husband' ? 'a husband' : 'a wife'}`)]);
    btn.addEventListener('click', () => popupMenu(btn, [
      { label: `New person as ${slot}`, icon: 'plus', onSelect: () => addRelativeDialog(app, from, slot, { family: f.key }) },
      other ? { label: `Link someone already in the file`, icon: 'link', onSelect: () => linkDialog(app, other, { as: `families/${f.key}/${slot}`, title: `Choose the ${slot} in family ${f.key}` }) } : null,
    ].filter(Boolean)));
    return h('div.partner.is-empty', btn);
  };

  const married = f.events.find((e) => e.tag === 'MARR');
  const divorced = f.fields && f.fields.divorced === 'yes';

  const addChild = () => {
    if (!anchor) return;
    addRelativeDialog(app, anchor, 'child', { family: f.key });
  };
  const childMenuBtn = button('Add child', { icon: 'plus', disabled: !anchor, cls: 'has-caret' });
  childMenuBtn.append(icon('chevronDown', 'caret'));
  childMenuBtn.addEventListener('click', () => popupMenu(childMenuBtn, [
    { label: 'New person as child', icon: 'plus', onSelect: addChild },
    { label: 'Link existing person as child', icon: 'link', onSelect: () => linkDialog(app, anchor, { as: `families/${f.key}/children` }) },
  ]));

  const head = h('header.page-head.family-head', [
    h('span.head-icon', icon('rings')),
    h('div.page-head-text', [
      h('div.eyebrow', [chip(f.key, 'chip-mono'), divorced ? chip('Divorced', 'chip-warn') : null]),
      h('h1.page-title-h', f.title),
      h('p.page-sub', [
        married ? ['Married', married.date ? ' ' + prettyDate(married.date) : '', married.place ? ' in ' + married.place : ''] : 'No marriage recorded',
        ' · ', plural(f.children.length, 'child', 'children'),
      ]),
    ]),
    h('div.head-actions', [
      button('Edit', { kind: 'primary', icon: 'edit', onclick: () => openFamilyEditor(app, f) }),
      childMenuBtn,
    ]),
  ]);

  const children = f.children.length
    ? f.children.map((c, i) => personRow(fid, c, {
      role: `${c.sex === 'M' ? 'Son' : c.sex === 'F' ? 'Daughter' : 'Child'}`,
      note: null,
      actions: [h('button.btn.btn-icon.btn-ghost.unlink-btn', {
        type: 'button', title: 'Unlink', aria: { label: `Take ${c.name} out of this family` },
        onclick: () => unlinkDialog(app, c, 'families/' + f.key, null, `a child in family ${f.key}`),
      }, icon('unlink'))],
    }))
    : [h('p.muted.small.pad', 'No children recorded.')];

  const left = [
    card('Partners', h('div.partners', [partner(f.husband, 'Husband', 'husband'), h('span.partner-join', { aria: { hidden: 'true' } }, icon('rings')), partner(f.wife, 'Wife', 'wife')])),
    card(f.children.length ? `Children · ${f.children.length}` : 'Children', h('div.rows', [
      children,
      anchor ? h('button.placeholder-row', { type: 'button', onclick: addChild }, [h('span.placeholder-avatar', icon('plus')), h('span', 'Add child')]) : null,
    ])),
  ];
  const right = [
    card('Events', f.events.length ? timeline(f.events) : h('div.empty-mini', [h('p', 'No events recorded.'), button('Add events', { icon: 'edit', kind: 'ghost', onclick: () => openFamilyEditor(app, f) })]), {
      actions: [button('', { icon: 'edit', kind: 'ghost', aria: { label: 'Edit events' }, title: 'Edit events', onclick: () => openFamilyEditor(app, f) })],
    }),
    card('Notes', f.notes.length ? notesBlock(f.notes) : h('div.empty-mini', [h('p', 'No notes yet.'), button('Add a note', { icon: 'plus', kind: 'ghost', onclick: () => openFamilyEditor(app, f, { focus: 'notes' }) })]), {
      actions: f.notes.length ? [button('', { icon: 'edit', kind: 'ghost', aria: { label: 'Edit notes' }, title: 'Edit notes', onclick: () => openFamilyEditor(app, f, { focus: 'notes' }) })] : null,
    }),
    f.records.length ? card('Sources & media', recordLinks(fid, f.records)) : null,
  ];

  return h('div.page.family-page', [head, h('div.person-grid', [h('div.col', left), h('div.col', right)])]);
}

export async function familiesView(app) {
  app.setTitle('Families');
  const fid = app.fileId;
  const PAGE = 500;
  const filter = h('input.input.filter-input', { id: 'filter-families', type: 'search', placeholder: 'Search by partner’s name or key', aria: { label: 'Search families' }, autocomplete: 'off', spellcheck: 'false', value: filters.families || '' });
  const list = h('div.card.list-card');
  const count = h('span.list-count');
  const more = h('button.btn.btn-ghost', { type: 'button', hidden: true });
  let items = [], total = 0, ctrl = null;
  const row = (f) => h('a.list-row', { href: href(fid, 'families', f.key) }, [
    h('span.list-icon', icon('rings')),
    h('span.list-text', [h('span.list-title', f.title), h('span.list-meta', [f.married ? `Married ${prettyDate(f.married)}` : 'No marriage date', ' · ', plural(f.children, 'child', 'children')])]),
    chip(f.key, 'chip-mono'),
    icon('chevronRight', 'row-chevron'),
  ]);
  const load = async (append = false) => {
    const q = filter.value.trim();
    filters.families = q;
    if (ctrl) ctrl.abort();
    const my = ctrl = new AbortController();
    let r;
    try {
      r = await api.families(fid, { q, offset: append ? items.length : 0, limit: PAGE }, my.signal);
    } catch (e) {
      if (e.name === 'AbortError') return;
      replace(list, h('p.list-error', e.message));
      return;
    }
    if (my !== ctrl) return;
    items = append ? items.concat(r.families) : r.families;
    total = r.total;
    count.textContent = q ? (total ? plural(total, 'match', 'matches') : 'No matches') : plural(total, 'family', 'families');
    more.hidden = items.length >= total;
    more.textContent = `Show more (${(total - items.length).toLocaleString()} left)`;
    if (!items.length) {
      replace(list, emptyState(q ? { icon: 'search', title: `No families match “${q}”`, text: 'Search looks at the partners’ names and the family’s key.' } : { icon: 'family', title: 'No families yet', text: 'Families appear when people are linked as partners or parents.' }));
    } else if (append) list.append(...r.families.map(row));
    else replace(list, items.map(row));
  };
  filter.addEventListener('input', debounce(() => load(), 150));
  more.addEventListener('click', () => load(true));
  await load();
  return h('div.page.list-page', [
    h('header.page-head', [
      h('div.page-head-text', [h('h1.page-title-h', 'Families'), h('p.page-sub', count)]),
    ]),
    h('div.list-toolbar', [h('div.search-box.inline', [icon('search', 'search-icon'), filter])]),
    list,
    h('p.center', more),
  ]);
}
