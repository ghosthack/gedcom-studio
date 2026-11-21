// Edit forms: a person's, a family's, and a note's, source's or media's.
//
// Everything the pages show can be edited. A person's name fields go as
// `set`/`seen`; their events, notes, other names and nicknames as lists of
// changed items, each with `seen` = what the form showed, so a change made
// elsewhere meanwhile is caught (409) and nothing is applied. Only what
// changed is sent, all in one request.
import { h, icon, replace, clear } from '../dom.js';
import { api } from '../api.js';
import { dialog, button, notice, toast, confirmDialog, SEX, plural, href } from '../ui.js';

// ---- event kinds --------------------------------------------------------------------

/** As the pages name them (the API's `label`). */
const EVENT_LABELS = {
  BIRT: 'Born', CHR: 'Christened', BAPM: 'Baptised', DEAT: 'Died', BURI: 'Buried', CREM: 'Cremated',
  ADOP: 'Adopted', BARM: 'Bar mitzvah', BASM: 'Bas mitzvah', CONF: 'Confirmed', FCOM: 'First communion',
  ORDN: 'Ordained', NATU: 'Naturalised', EMIG: 'Emigrated', IMMI: 'Immigrated', CENS: 'Census',
  PROB: 'Probate', WILL: 'Will', GRAD: 'Graduated', RETI: 'Retired', RESI: 'Residence', OCCU: 'Occupation',
  EDUC: 'Education', RELI: 'Religion', TITL: 'Title', NATI: 'Nationality', DSCR: 'Description',
  PROP: 'Property', NCHI: 'Children', NMR: 'Marriages', MARR: 'Married', DIV: 'Divorced', ENGA: 'Engaged',
  MARB: 'Banns', MARC: 'Marriage contract', MARL: 'Marriage licence', MARS: 'Marriage settlement',
  ANUL: 'Annulled', DIVF: 'Divorce filed', EVEN: 'Event', FACT: 'Fact',
};
/** As the "Add event" list names them. */
const NOUNS = {
  BIRT: 'Birth', CHR: 'Christening', BAPM: 'Baptism', DEAT: 'Death', BURI: 'Burial', CREM: 'Cremation',
  ADOP: 'Adoption', BARM: 'Bar mitzvah', BASM: 'Bas mitzvah', CONF: 'Confirmation', FCOM: 'First communion',
  ORDN: 'Ordination', NATU: 'Naturalisation', EMIG: 'Emigration', IMMI: 'Immigration', CENS: 'Census',
  PROB: 'Probate', WILL: 'Will', GRAD: 'Graduation', RETI: 'Retirement', RESI: 'Residence', OCCU: 'Occupation',
  EDUC: 'Education', RELI: 'Religion', TITL: 'Title', NATI: 'Nationality', DSCR: 'Physical description',
  PROP: 'Property', NCHI: 'Number of children', NMR: 'Number of marriages', MARR: 'Marriage', DIV: 'Divorce',
  ENGA: 'Engagement', MARB: 'Banns', MARC: 'Marriage contract', MARL: 'Marriage licence',
  MARS: 'Marriage settlement', ANUL: 'Annulment', DIVF: 'Divorce filed', EVEN: 'Other event (give its type)',
  FACT: 'Other fact (give its type)',
};
/** Facts whose text is the point (`1 OCCU Sailor`). */
const ATTRIBUTES = new Set(['OCCU', 'EDUC', 'RELI', 'TITL', 'NATI', 'DSCR', 'PROP', 'NCHI', 'NMR', 'FACT']);
const NEEDS_TYPE = new Set(['EVEN', 'FACT']);
/** Not offered again once the person has one. */
const ONCE = new Set(['BIRT', 'DEAT', 'BURI', 'CREM']);
const PERSON_TAGS = [
  ['Life', ['BIRT', 'CHR', 'BAPM', 'ADOP', 'DEAT', 'BURI', 'CREM']],
  ['Events', ['GRAD', 'RESI', 'CENS', 'EMIG', 'IMMI', 'NATU', 'RETI', 'CONF', 'FCOM', 'BARM', 'BASM', 'ORDN', 'WILL', 'PROB', 'EVEN']],
  ['Facts', ['OCCU', 'EDUC', 'RELI', 'TITL', 'NATI', 'DSCR', 'PROP', 'NCHI', 'NMR', 'FACT']],
];
const FAMILY_TAGS = [
  ['Family events', ['ENGA', 'MARB', 'MARC', 'MARL', 'MARS', 'MARR', 'DIVF', 'DIV', 'ANUL']],
  ['Other', ['RESI', 'CENS', 'EVEN', 'FACT']],
];
const VALUE_LABELS = { NCHI: 'Number', NMR: 'Number' };
const NAME_TYPES = ['aka', 'birth', 'married', 'maiden', 'immigrant'];
/** Lines often added to sources, notes and media. */
const LINE_TAGS = [
  ['TITL', 'Title'], ['AUTH', 'Author'], ['PUBL', 'Publication'], ['ABBR', 'Abbreviation'], ['TEXT', 'Text'],
  ['NOTE', 'Note'], ['DATE', 'Date'], ['PLAC', 'Place'], ['PAGE', 'Page'], ['FILE', 'File'], ['FORM', 'Format'],
  ['REFN', 'Reference number'], ['_URL', 'Web address'],
];

// ---- small pieces ------------------------------------------------------------------

let uid = 0;
const nextId = (p) => `${p}-${++uid}-${Math.random().toString(36).slice(2, 6)}`;

/** A note's text as the daemon keeps it: lines kept, trailing spaces and blank edges dropped. */
function normText(v) {
  return String(v == null ? '' : v).replace(/\r\n?/g, '\n').split('\n').map((l) => l.trimEnd()).join('\n').trim();
}
const shown = (v) => (v === '' || v == null ? 'empty' : `“${v}”`);
const capital = (s) => s.charAt(0).toUpperCase() + s.slice(1);

function autosize(el) {
  if (!el.isConnected) return;
  el.style.height = 'auto';
  el.style.height = Math.min(el.scrollHeight + 2, 420) + 'px';
}

/**
 * A labelled input for one part of something (a date, a note's text…).
 * `label` shows; `aria` (set later by `name()`) is the full accessible name,
 * such as "Residence 2 date".
 */
function partField({ label, kind = 'text', value = '', multiline = false, placeholder = '', hint = null, list = null, cls = '' }) {
  const id = nextId('p');
  const wrap = h('div.field.part-' + kind + (multiline ? '.part-multiline' : '') + (cls ? '.' + cls : ''));
  const el = multiline
    ? h('textarea.input.textarea', { id, rows: String(Math.min(12, Math.max(3, roughLines(value)))), spellcheck: 'true' })
    : h('input.input', { id, type: 'text', autocomplete: 'off', spellcheck: kind === 'text' ? 'true' : 'false', placeholder, list });
  if (kind === 'date') el.classList.add('input-date');
  wrap.append(h('label.field-label', { for: id }, label), el);
  if (hint) {
    const hid = id + '-hint';
    el.setAttribute('aria-describedby', hid);
    wrap.append(h('p.date-hint', { id: hid }, hint));
  }
  wrap.append(h('div.field-note'));
  const p = {
    el, wrap, label, multiline,
    get: () => (multiline ? normText(el.value) : el.value.trim()),
    set: (v) => { el.value = v == null ? '' : String(v); if (multiline) autosize(el); },
    norm: (v) => (multiline ? normText(v) : String(v == null ? '' : v).trim()),
    name: (full) => el.setAttribute('aria-label', full),
  };
  p.set(value);
  if (multiline) el.addEventListener('input', () => autosize(el));
  return p;
}

function roughLines(text) {
  return String(text || '').split('\n').reduce((n, l) => n + Math.max(1, Math.ceil(l.length / 64)), 0) + 1;
}

/** A message under a field: updated (from elsewhere), conflict or error. */
function partNote(p, kind, text, action) {
  const note = p.wrap.querySelector('.field-note');
  clear(note);
  p.wrap.classList.remove('note-updated', 'note-conflict', 'note-error', 'is-invalid');
  p.el.removeAttribute('aria-invalid');
  if (!text) return;
  p.wrap.classList.add('note-' + kind);
  if (kind === 'error') { p.wrap.classList.add('is-invalid'); p.el.setAttribute('aria-invalid', 'true'); }
  note.append(h('span', text));
  if (action) note.append(action);
  if (p.wrap.hidden) p.wrap.hidden = false;
}

/**
 * Brings in a part's value from elsewhere: kept as is when the user changed it
 * too (with a "Use theirs"), otherwise taken. `out` collects labels.
 */
function rebasePart(p, before, now, label, out, onChange) {
  const b = p.norm(before), n = p.norm(now), mine = p.get();
  if (n === b) return;
  if (mine === b) {
    p.set(n);
    out.updated.push(label);
    partNote(p, 'updated', `Changed elsewhere, was ${shown(b)}`);
  } else if (mine !== n) {
    out.clashes.push(label);
    const theirs = h('button.link-btn', { type: 'button' }, 'Use theirs');
    theirs.addEventListener('click', () => { p.set(n); onChange(); partNote(p, 'updated', `Now ${shown(n)}, as changed elsewhere`); });
    partNote(p, 'conflict', `Elsewhere it’s now ${shown(n)}. Saving keeps yours.`, theirs);
  }
}

function firstSentence(t) {
  const i = t.indexOf(';');
  return i > 0 ? t.slice(0, i) : t;
}

function addButton(label, onclick) {
  return button(label, { icon: 'plus', kind: 'ghost', cls: 'add-btn', onclick });
}

// ---- items: one event, note, name or record line --------------------------------------

/**
 * The rows of one list. Each item: {ref (null when new), orig (the parts as
 * shown), parts: {name: field}, removed, isNew, el, …}.
 */
function itemList({ key, onChange, describe }) {
  const list = { key, items: [], el: h('div.items.items-' + key), onChange };

  list.changedParts = (it) => (it.isNew || it.removed ? [] : Object.keys(it.parts).filter((k) => it.parts[k].get() !== it.parts[k].norm(it.orig[k])));
  list.isChanged = (it) => (it.isNew ? it.meaningful() : it.removed || list.changedParts(it).length > 0);
  list.count = () => list.items.reduce((n, it) => n + (it.isNew ? (it.meaningful() ? 1 : 0) : it.removed ? 1 : list.changedParts(it).length), 0);

  /** Puts names on rows and inputs: "Residence 2", "Residence 2 date". */
  list.relabel = () => {
    const totals = new Map(), seen = new Map();
    for (const it of list.items) totals.set(it.base, (totals.get(it.base) || 0) + 1);
    for (const it of list.items) {
      const n = (seen.get(it.base) || 0) + 1;
      seen.set(it.base, n);
      it.name = totals.get(it.base) > 1 || it.alwaysNumber ? `${it.base} ${n}` : it.base;
      it.titleEl.textContent = it.displayTitle ? it.displayTitle(it.name) : it.name;
      for (const p of Object.values(it.parts)) p.name(it.partName ? it.partName(it.name, p) : `${it.name} ${p.label.toLowerCase()}`);
      if (it.removeBtn) it.removeBtn.setAttribute('aria-label', `Remove ${it.name}`);
      if (it.undoBtn) it.undoBtn.setAttribute('aria-label', `Undo removing ${it.name}`);
      if (it.relabel) it.relabel(it.name);
    }
  };

  list.mark = (it) => {
    for (const [k, p] of Object.entries(it.parts)) {
      const changed = it.isNew ? p.get() !== '' : p.get() !== p.norm(it.orig[k]);
      p.wrap.classList.toggle('is-changed', changed && !it.removed);
      if (p.wrap.classList.contains('note-error')) partNote(p, null, null);
    }
    it.el.classList.toggle('is-edited', !it.isNew && !it.removed && list.changedParts(it).length > 0);
  };

  /** A row: a head with the item's name and a remove button, its body, and a "removed" bar. */
  list.row = (it, { body, headExtra = [], cls = '' }) => {
    it.titleEl = h('span.item-title');
    it.removeBtn = h('button.btn.btn-icon.btn-ghost.item-remove', { type: 'button', title: 'Remove' }, icon('trash'));
    it.undoBtn = h('button.link-btn.item-undo', { type: 'button' }, 'Undo');
    it.removeBtn.addEventListener('click', () => list.remove(it));
    it.undoBtn.addEventListener('click', () => list.undo(it));
    it.el = h('div.item' + (cls ? '.' + cls : '') + (it.isNew ? '.is-new' : ''), { dataset: { ref: it.ref || '' } }, [
      h('div.item-head', [
        it.titleEl,
        h('div.item-extra', headExtra),
        h('span.item-gone', [h('span', 'Removed when you save'), it.undoBtn]),
        it.removeBtn,
      ]),
      h('div.item-body', body),
    ]);
    for (const p of Object.values(it.parts)) {
      p.el.addEventListener('input', () => { list.mark(it); onChange(); });
      p.el.addEventListener('change', () => { list.mark(it); onChange(); });
    }
    return it.el;
  };

  list.add = (it, { focus = false, at = null } = {}) => {
    if (at) { list.items.splice(list.items.indexOf(at), 0, it); list.el.insertBefore(it.el, at.el); }
    else { list.items.push(it); list.el.append(it.el); }
    list.relabel();
    for (const p of Object.values(it.parts)) if (p.multiline) autosize(p.el);
    if (focus) {
      const first = Object.values(it.parts).find((p) => !p.wrap.hidden);
      if (first) first.el.focus();
      it.el.scrollIntoView({ block: 'nearest', behavior: 'smooth' });
    }
  };

  list.remove = async (it) => {
    if (it.isNew) {
      const i = list.items.indexOf(it);
      list.items.splice(i, 1);
      it.el.remove();
      list.relabel();
      onChange();
      const next = list.items[i] || list.items[i - 1];
      if (next && next.removeBtn) next.removeBtn.focus();
      return;
    }
    const warning = describe && describe.beforeRemove ? describe.beforeRemove(it) : null;
    if (warning) {
      const ok = await confirmDialog({ title: `Remove ${it.name}?`, text: warning, confirm: 'Remove', danger: true });
      if (!ok) return;
    }
    it.removed = true;
    it.el.classList.add('is-removed');
    for (const p of Object.values(it.parts)) p.el.disabled = true;
    list.mark(it);
    if (describe && describe.afterRemove) describe.afterRemove(it, true);
    onChange();
    it.undoBtn.focus();
  };

  list.undo = (it) => {
    it.removed = false;
    it.el.classList.remove('is-removed');
    for (const p of Object.values(it.parts)) p.el.disabled = false;
    list.mark(it);
    if (describe && describe.afterRemove) describe.afterRemove(it, false);
    onChange();
    it.removeBtn.focus();
  };

  /** Drops the rows that came from the record (keeping new ones) and shows `fresh` instead. */
  list.replaceExisting = (fresh, build) => {
    const kept = list.items.filter((it) => it.isNew);
    for (const it of list.items) if (!it.isNew) it.el.remove();
    list.items = [];
    for (const d of fresh) { const it = build(d, false); list.items.push(it); list.el.append(it.el); }
    for (const it of kept) { list.items.push(it); list.el.append(it.el); }
    list.relabel();
    for (const it of list.items) for (const p of Object.values(it.parts)) if (p.multiline) autosize(p.el);
  };

  /**
   * Brings in a fresh copy of the list. When the same refs are there, each
   * part is rebased; when items were added or removed elsewhere, refs may
   * now name other items, so the list is shown afresh (new rows kept) and
   * the user's edits to it are reported as dropped.
   */
  list.rebase = (fresh, build, partsOf, out) => {
    const existing = list.items.filter((it) => !it.isNew);
    const byRef = new Map(fresh.map((f) => [f.ref, f]));
    const same = existing.length === fresh.length && existing.every((it) => byRef.has(it.ref));
    if (!same) {
      const lost = existing.filter((it) => list.isChanged(it)).map((it) => it.name);
      list.replaceExisting(fresh, build);
      out.reloaded.push({ list: describe.title, lost });
      return;
    }
    for (const it of existing) {
      const f = byRef.get(it.ref);
      const now = partsOf(f);
      const was = it.orig;
      const differs = Object.keys(now).some((k) => String(now[k] ?? '') !== String(was[k] ?? ''));
      if (it.removed && differs) {
        list.undo(it);
        out.restored.push(it.name);
      }
      for (const [k, p] of Object.entries(it.parts)) rebasePart(p, was[k], now[k], `${it.name} ${p.label.toLowerCase()}`, out, () => { list.mark(it); onChange(); });
      it.orig = { ...now };
      if (it.refresh) it.refresh(f);
      list.mark(it);
    }
  };

  /** The item named by a refused edit's ref (`RESI:1`), or a new one of `tag`. */
  list.find = (ref, part) => {
    let it = list.items.find((x) => x.ref === ref);
    if (!it) {
      const news = list.items.filter((x) => x.isNew && (x.tag || '') === ref);
      it = news.find((x) => part && x.parts[part] && x.parts[part].get() === '') || news[0];
    }
    return it || null;
  };

  return list;
}

// ---- the dialog, saving and conflicts -------------------------------------------------

/**
 * The dialog around a form, and its save: `count()` the changes, `body()` the
 * request (or null), `check()` problems found before sending, `send(body)`,
 * `current(errBody)` the latest copy in a 409, `rebase(current)` brings it in,
 * `refused(message)` marks the field a 422 names.
 */
function editorDialog(app, cfg) {
  const top = h('div.form-top');
  const live = h('div.form-live');
  const saveBtn = button('Save changes', { kind: 'primary', type: 'submit', icon: 'check' });
  const updateSaveLabel = () => {
    const n = cfg.count();
    saveBtn.querySelector('span').textContent = n ? `Save ${plural(n, 'change', 'changes')}` : 'Save changes';
  };

  const d = dialog({
    title: cfg.title,
    subtitle: cfg.subtitle || null,
    size: 'lg',
    cls: 'dialog-form' + (cfg.cls ? ' ' + cfg.cls : ''),
    body: [top, live, ...cfg.sections],
    actions: [
      h('p.foot-note', [icon('save'), h('span', 'Edits are held until you save the file.')]),
      button('Cancel', { kind: 'ghost', onclick: () => d.tryCancel() }),
      saveBtn,
    ],
    onSubmit: () => submit(),
    onCancel: () => {
      const n = cfg.count();
      if (!n) return true;
      confirmDialog({ title: 'Discard your changes?', text: `You made ${plural(n, 'change', 'changes')} in this form. Closing drops them.`, confirm: 'Discard', danger: true })
        .then((ok) => { if (ok) d.close(); });
      return false;
    },
    onClose: () => app.forms.delete(entry),
  });
  for (const t of d.el.querySelectorAll('textarea')) autosize(t);

  const entry = {
    close: () => d.close(),
    externalChange: () => {
      replace(live, h('div.live-banner', { role: 'status' }, [
        icon('refresh'),
        h('span', `${capital(cfg.what)} may have changed elsewhere.`),
        h('button.link-btn', { type: 'button', onclick: reloadValues }, 'Reload values'),
      ]));
    },
  };
  app.forms.add(entry);

  const summary = (out) => [
    out.updated.length ? h('span', `Updated from elsewhere: ${out.updated.join(', ')}. `) : null,
    out.clashes.length ? h('span', `Changed by both of you: ${out.clashes.join(', ')}: yours will be saved unless you pick theirs. `) : null,
    out.restored.length ? h('span', `${out.restored.join(', ')} changed elsewhere, so ${out.restored.length === 1 ? 'it is' : 'they are'} no longer marked for removal. `) : null,
    ...out.reloaded.map((r) => h('span', `${r.list} were added or removed elsewhere, so that list now shows the latest${r.lost.length ? `; your changes to ${r.lost.join(', ')} were dropped: make them again` : ''}. `)),
  ];
  const newOut = () => ({ updated: [], clashes: [], restored: [], reloaded: [] });

  async function reloadValues() {
    replace(live);
    try {
      const out = newOut();
      cfg.rebase(await cfg.reload(), out);
      updateSaveLabel();
      if (!out.updated.length && !out.clashes.length && !out.restored.length && !out.reloaded.length) toast('Nothing in this form changed elsewhere.');
      else replace(top, notice('warn', summary(out), { title: 'Values reloaded' }));
    } catch (e) {
      replace(top, notice('error', e.status === 404 ? `${capital(cfg.what)} no longer exists.` : e.message, { title: 'Couldn’t reload' }));
    }
  }

  async function submit() {
    replace(top);
    cfg.clearNotes();
    const problem = cfg.check();
    if (problem) {
      replace(top, notice('error', problem, { title: 'Not saved' }));
      return;
    }
    const body = cfg.body();
    if (!body) { d.close(); return; }
    d.setBusy(true);
    saveBtn.classList.add('is-busy');
    try {
      const r = await app.mutate(() => cfg.send(body));
      d.close();
      const n = r.changes.length;
      toast(n ? `Updated ${cfg.name}: ${plural(n, 'change', 'changes')}. Save the file to keep them.` : `Nothing changed for ${cfg.name}.`, { kind: 'success' });
      app.changed();
    } catch (e) {
      if (e.status === 409 && e.body.conflict) {
        const out = newOut();
        const current = cfg.current(e.body);
        if (current) cfg.rebase(current, out);
        updateSaveLabel();
        replace(top, notice('warn', [
          h('span', `Someone changed ${cfg.name} while you were editing, so nothing was saved. `),
          ...summary(out),
          h('span', 'Your other changes are still here: check the marked fields and save again.'),
        ], { title: 'Changed elsewhere' }));
        top.scrollIntoView({ block: 'nearest' });
      } else if (e.status === 422) {
        const label = cfg.refused(e.message);
        replace(top, notice('error', label || e.message, { title: 'Not saved' }));
      } else if (e.status !== 401) {
        replace(top, notice('error', e.message, { title: 'Not saved' }));
      }
    } finally {
      d.setBusy(false);
      saveBtn.classList.remove('is-busy');
    }
  }

  return { d, updateSaveLabel, top };
}

function group(title, cls, children, { count } = {}) {
  return h('fieldset.form-group.group-' + cls, [
    h('legend.group-title', [title, count != null ? h('span.group-count', count) : null]),
    ...children,
  ]);
}

// ---- people and families ----------------------------------------------------------------

export function openPersonEditor(app, person, opts = {}) {
  return openEditor(app, {
    kind: 'person',
    subject: person,
    title: `Edit ${person.name || person.key}`,
    name: person.name || person.key,
    what: 'this person',
    tags: PERSON_TAGS,
    reload: () => api.person(app.fileId, person.key),
    send: (body) => api.editPerson(app.fileId, person.key, body),
    current: (b) => b.person,
    focus: opts.focus,
  });
}

export function openFamilyEditor(app, family, opts = {}) {
  return openEditor(app, {
    kind: 'family',
    subject: family,
    title: `Edit family ${family.key}`,
    subtitle: family.title,
    name: `family ${family.key}`,
    what: 'this family',
    tags: FAMILY_TAGS,
    reload: () => api.family(app.fileId, family.key),
    send: (body) => api.editFamily(app.fileId, family.key, body),
    current: (b) => b.family,
    focus: opts.focus,
  });
}

function openEditor(app, cfg) {
  const subject = cfg.subject;
  const isPerson = cfg.kind === 'person';
  const dates = 'Dates: ' + app.fields.dates;
  let shell = null;
  const onChange = () => { if (shell) shell.updateSaveLabel(); refreshPicker(); };

  // ---- name fields (set/seen), as before
  const defs = isPerson ? app.fields.person.filter((x) => x.group === 'name') : [];
  const labels = new Map(defs.map((x) => [x.key, x.label]));
  let seen = {};
  for (const x of defs) seen[x.key] = subject.fields && subject.fields[x.key] != null ? String(subject.fields[x.key]) : '';
  const inputs = new Map();
  const nameControl = (x) => {
    const id = nextId('f-' + x.key.replace(/\W/g, '-'));
    const wrap = h('div.field');
    let el, get, set;
    if (x.kind === 'sex') {
      el = h('select.select', { id, name: x.key }, [
        seen[x.key] === '' ? h('option', { value: '' }, 'Not recorded') : null,
        ...['M', 'F', 'U'].map((v) => h('option', { value: v }, SEX[v])),
      ]);
      get = () => el.value;
      set = (v) => {
        if (v === '' && !el.querySelector('option[value=""]')) el.prepend(h('option', { value: '' }, 'Not recorded'));
        el.value = v;
      };
    } else {
      el = h('input.input', { id, name: x.key, type: 'text', autocomplete: 'off', spellcheck: 'true' });
      get = () => el.value.trim();
      set = (v) => { el.value = v; };
    }
    wrap.append(h('label.field-label', { for: id }, x.label), el, h('div.field-note'));
    set(seen[x.key]);
    const i = { get, set, el, wrap, norm: (v) => String(v == null ? '' : v).trim(), label: x.label };
    const mark = () => { wrap.classList.toggle('is-changed', get() !== seen[x.key]); if (wrap.classList.contains('note-error')) partNote(i, null, null); onChange(); };
    el.addEventListener('input', mark);
    el.addEventListener('change', mark);
    inputs.set(x.key, i);
    return h('div.form-row.is-single', h('div.row-fields', wrap));
  };
  const changedSet = () => {
    const set = {};
    for (const [k, i] of inputs) if (i.get() !== seen[k]) set[k] = i.get();
    return set;
  };

  // ---- events
  const events = itemList({
    key: 'events', onChange,
    describe: {
      title: 'Events',
      beforeRemove: (it) => (it.details ? 'This event also has notes or sources, which will be removed too.' : null),
    },
  });
  const buildEvent = (data, isNew) => {
    const tag = data.tag;
    const f = isNew ? { type: '', date: '', place: '', value: '' } : data.fields;
    const it = {
      ref: isNew ? null : data.ref, tag, isNew, removed: false, details: !!data.details,
      orig: isNew ? null : { ...data.fields }, base: data.label || EVENT_LABELS[tag] || tag, parts: {},
      meaningful: () => true,
    };
    const showValue = ATTRIBUTES.has(tag) || f.value !== '';
    const needsType = NEEDS_TYPE.has(tag);
    if (showValue) it.parts.value = partField({ label: VALUE_LABELS[tag] || 'Value', value: f.value, cls: 'f-value' });
    it.parts.type = partField({ label: 'Type', value: f.type, cls: 'f-type', placeholder: needsType ? (tag === 'EVEN' ? 'Needed, e.g. First communion' : 'Needed, e.g. Blood group') : 'What kind, if it matters' });
    it.parts.date = partField({ label: 'Date', kind: 'date', value: f.date, hint: dates, placeholder: 'e.g. 12 JUN 1850', cls: 'f-date' });
    it.parts.place = partField({ label: 'Place', kind: 'place', value: f.place, placeholder: 'Town, region, country', cls: 'f-place' });
    const grid = h('div.item-grid.ev-grid', Object.values(it.parts).map((p) => p.wrap));
    const extra = [];
    const syncBoth = () => grid.classList.toggle('has-both', !!it.parts.value && !it.parts.type.wrap.hidden);
    if (!needsType && f.type === '') {
      it.parts.type.wrap.hidden = true;
      const reveal = h('button.btn.btn-ghost.btn-small.add-type', { type: 'button' }, [icon('plus'), h('span', 'Type')]);
      reveal.addEventListener('click', () => {
        it.parts.type.wrap.hidden = false;
        reveal.remove();
        syncBoth();
        it.parts.type.el.focus();
      });
      it.relabel = (name) => reveal.setAttribute('aria-label', `Add a type to ${name}`);
      extra.push(reveal);
    }
    syncBoth();
    it.refresh = (fresh) => { it.details = !!fresh.details; };
    events.row(it, { body: grid, headExtra: extra, cls: 'item-event.tag-' + tag.toLowerCase() });
    return it;
  };
  for (const e of subject.events || []) { const it = buildEvent(e, false); events.items.push(it); events.el.append(it.el); }
  events.relabel();

  const picker = h('select.select.add-picker', { aria: { label: 'Add an event' } });
  function refreshPicker() {
    const have = new Set(events.items.filter((it) => !it.removed).map((it) => it.tag));
    const keep = picker.value;
    clear(picker);
    picker.append(h('option', { value: '' }, 'Add an event…'));
    for (const [label, tags] of cfg.tags) {
      picker.append(h('optgroup', { label }, tags.filter((t) => !(ONCE.has(t) && have.has(t))).map((t) => h('option', { value: t }, NOUNS[t]))));
    }
    picker.value = keep;
  }
  refreshPicker();
  picker.addEventListener('change', () => {
    const tag = picker.value;
    picker.value = '';
    if (!tag) return;
    events.add(buildEvent({ tag }, true), { focus: true });
    onChange();
  });

  // ---- notes
  const notes = itemList({
    key: 'notes', onChange,
    describe: { title: 'Notes' },
  });
  const openShared = (key) => (e) => {
    e.preventDefault();
    const go = () => { shell.d.close(); location.hash = href(app.fileId, 'records', key); };
    if (!count()) { go(); return; }
    confirmDialog({ title: 'Leave this form?', text: `Opening the note drops the ${plural(count(), 'change', 'changes')} you made here.`, confirm: 'Discard and open', danger: true })
      .then((ok) => { if (ok) go(); });
  };
  const buildNote = (data, isNew) => {
    const it = {
      ref: isNew ? null : data.ref, isNew, removed: false, shared: isNew ? null : data.shared,
      orig: isNew ? null : { text: data.text }, base: isNew || !data.shared ? 'Note' : 'Shared note', parts: {},
      alwaysNumber: false,
      meaningful: () => !!(it.parts.text && it.parts.text.get()),
    };
    let body;
    if (it.shared) {
      const text = h('p.shared-text', data.text);
      it.refresh = (fresh) => { text.textContent = fresh.text; };
      body = h('div.shared-note', [
        text,
        h('p.shared-meta', [
          icon('note'),
          h('span', `Shared note ${it.shared}: edit it on its own page. Removing it here only unlinks it.`),
          h('a.inline-link', { href: href(app.fileId, 'records', it.shared), onclick: openShared(it.shared) }, `Open ${it.shared}`),
        ]),
      ]);
      it.orig = { text: data.text };
    } else {
      it.parts.text = partField({ label: 'Text', multiline: true, value: isNew ? '' : data.text });
      it.partName = (name) => name;
      body = it.parts.text.wrap;
    }
    notes.row(it, { body, cls: it.shared ? 'item-note.is-shared' : 'item-note' });
    return it;
  };
  for (const n of subject.noteItems || []) { const it = buildNote(n, false); notes.items.push(it); notes.el.append(it.el); }
  notes.relabel();

  // ---- other names and nicknames
  const names = itemList({ key: 'names', onChange, describe: { title: 'Other names' } });
  const typeList = nextId('name-types');
  const buildName = (data, isNew) => {
    const f = isNew ? { given: '', surname: '', type: '' } : data.fields;
    const it = {
      ref: isNew ? null : data.ref, isNew, removed: false, orig: isNew ? null : { ...data.fields }, base: 'Other name',
      parts: {
        given: partField({ label: 'Given names', value: f.given, cls: 'f-given' }),
        surname: partField({ label: 'Surname', value: f.surname, cls: 'f-surname' }),
        type: partField({ label: 'Type', value: f.type, list: typeList, placeholder: 'aka, birth, married…', cls: 'f-ntype' }),
      },
      meaningful: () => !!(it.parts.given.get() || it.parts.surname.get()),
    };
    names.row(it, { body: h('div.item-grid.name-grid', Object.values(it.parts).map((p) => p.wrap)), cls: 'item-name' });
    return it;
  };
  if (isPerson) {
    for (const n of subject.otherNames || []) { const it = buildName(n, false); names.items.push(it); names.el.append(it.el); }
    names.relabel();
  }

  let nickSeen = isPerson ? [...(subject.nicknames || [])] : [];
  const nickList = h('div.nick-list');
  const nickNote = h('div.field-note.nick-note');
  const nickRows = [];
  const nicknames = () => nickRows.map((r) => r.input.value.trim()).filter(Boolean);
  const nickChanged = () => nicknames().join('\u0000') !== nickSeen.join('\u0000');
  const relabelNicks = () => nickRows.forEach((r, i) => {
    const n = nickRows.length > 1 ? `Nickname ${i + 1}` : 'Nickname';
    r.input.setAttribute('aria-label', n);
    r.remove.setAttribute('aria-label', `Remove ${n}`);
  });
  const addNick = (value, focus) => {
    const input = h('input.input', { type: 'text', autocomplete: 'off', spellcheck: 'false', value, placeholder: 'Nickname' });
    const remove = h('button.btn.btn-icon.btn-ghost.item-remove', { type: 'button', title: 'Remove' }, icon('close'));
    const row = h('div.nick-row', [input, remove]);
    const r = { input, remove, row };
    input.addEventListener('input', () => { markNicks(); onChange(); });
    remove.addEventListener('click', () => {
      const i = nickRows.indexOf(r);
      nickRows.splice(i, 1);
      row.remove();
      relabelNicks();
      markNicks();
      onChange();
      const next = nickRows[i] || nickRows[i - 1];
      (next ? next.input : addNickBtn).focus();
    });
    nickRows.push(r);
    nickList.append(row);
    relabelNicks();
    if (focus) input.focus();
  };
  const markNicks = () => {
    nickList.classList.toggle('is-changed', nickChanged());
    if (nickNote.closest('.note-error')) setNickNote(null);
  };
  const nickWrap = h('div.nick-field', [nickList, nickNote]);
  const setNickNote = (kind, text, action) => {
    clear(nickNote);
    nickWrap.classList.remove('note-updated', 'note-conflict', 'note-error');
    if (!text) return;
    nickWrap.classList.add('note-' + kind);
    nickNote.append(h('span', text));
    if (action) nickNote.append(action);
  };
  const setNicks = (list) => { for (const r of [...nickRows]) r.row.remove(); nickRows.length = 0; list.forEach((n) => addNick(n, false)); markNicks(); };
  setNicks(nickSeen);
  const addNickBtn = addButton('Add nickname', () => { addNick('', true); onChange(); });

  // ---- counting, sending
  const lists = isPerson ? [events, notes, names] : [events, notes];
  const count = () => Object.keys(changedSet()).length + lists.reduce((n, l) => n + l.count(), 0) + (nickChanged() ? 1 : 0);

  const pick = (obj, keys) => Object.fromEntries(keys.map((k) => [k, obj[k]]));
  const values = (it) => Object.fromEntries(Object.entries(it.parts).map(([k, p]) => [k, p.get()]));
  const nonEmpty = (o) => Object.fromEntries(Object.entries(o).filter(([, v]) => v !== ''));
  const ops = (list, addOf) => {
    const out = [];
    for (const it of list.items) {
      if (it.isNew) {
        if (it.meaningful()) out.push({ add: addOf(it), set: nonEmpty(values(it)) });
      } else if (it.removed) {
        out.push({ ref: it.ref, seen: { ...it.orig }, remove: true });
      } else {
        const changed = list.changedParts(it);
        if (changed.length) out.push({ ref: it.ref, seen: { ...it.orig }, set: pick(values(it), changed) });
      }
    }
    return out;
  };
  const body = () => {
    const b = {};
    const set = changedSet();
    if (Object.keys(set).length) { b.set = set; b.seen = pick(seen, Object.keys(set)); }
    const ev = ops(events, (it) => it.tag);
    if (ev.length) b.events = ev;
    const no = ops(notes, () => '');
    if (no.length) b.notes = no;
    if (isPerson) {
      const na = ops(names, () => '');
      if (na.length) b.names = na;
      if (nickChanged()) b.nicknames = { seen: nickSeen, set: nicknames() };
    }
    return Object.keys(b).length ? b : null;
  };
  const check = () => {
    for (const it of events.items) {
      if (it.removed || !NEEDS_TYPE.has(it.tag) || it.parts.type.get()) continue;
      const msg = `${it.tag === 'EVEN' ? 'An event' : 'A fact'} needs a type, such as ${it.tag === 'EVEN' ? '“First communion”' : '“Blood group”'}.`;
      partNote(it.parts.type, 'error', msg);
      it.parts.type.el.focus();
      return `${it.name}: ${msg}`;
    }
    return null;
  };
  const clearNotes = () => {
    for (const i of inputs.values()) partNote(i, null, null);
    for (const l of lists) for (const it of l.items) for (const p of Object.values(it.parts)) partNote(p, null, null);
    setNickNote(null);
  };
  const rebase = (current, out) => {
    // name fields
    for (const [k, i] of inputs) {
      const now = current.fields && current.fields[k] != null ? String(current.fields[k]) : '';
      rebasePart(i, seen[k], now, labels.get(k) || k, out, () => { i.wrap.classList.toggle('is-changed', i.get() !== seen[k]); onChange(); });
    }
    for (const k of inputs.keys()) seen[k] = current.fields && current.fields[k] != null ? String(current.fields[k]) : '';
    for (const [k, i] of inputs) i.wrap.classList.toggle('is-changed', i.get() !== seen[k]);
    events.rebase(current.events || [], buildEvent, (f) => ({ ...f.fields }), out);
    notes.rebase(current.noteItems || [], buildNote, (f) => ({ text: f.text }), out);
    if (isPerson) {
      names.rebase(current.otherNames || [], buildName, (f) => ({ ...f.fields }), out);
      const fresh = current.nicknames || [];
      if (fresh.join('\u0000') !== nickSeen.join('\u0000')) {
        const mine = nicknames();
        const label = 'Nicknames';
        if (mine.join('\u0000') === nickSeen.join('\u0000')) {
          setNicks(fresh);
          out.updated.push(label);
          setNickNote('updated', `Changed elsewhere, were ${nickSeen.length ? nickSeen.join(', ') : 'none'}`);
        } else if (mine.join('\u0000') !== fresh.join('\u0000')) {
          out.clashes.push(label);
          const theirs = h('button.link-btn', { type: 'button' }, 'Use theirs');
          theirs.addEventListener('click', () => { setNicks(fresh); onChange(); setNickNote('updated', 'Now as changed elsewhere'); });
          setNickNote('conflict', `Elsewhere they’re now ${fresh.length ? fresh.join(', ') : 'none'}. Saving keeps yours.`, theirs);
        }
        nickSeen = [...fresh];
        markNicks();
      }
    }
    refreshPicker();
  };
  /** Marks the field a 422 names: `given: …`, `RESI:1.date: …`, `EVEN: needs a type`, `NAME:1: …`. */
  const refused = (message) => {
    const field = /^([a-z][a-z.]*):\s*(.*)$/s.exec(message);
    if (field && inputs.has(field[1])) {
      const i = inputs.get(field[1]);
      partNote(i, 'error', firstSentence(field[2]));
      i.el.focus();
      return `${labels.get(field[1]) || field[1]}: ${field[2]}`;
    }
    const m = /^([A-Z_]+(?::\d+)?)(?:\.([a-z]+))?:?\s+(.*)$/s.exec(message);
    if (!m) return null;
    const [, ref, part, text] = m;
    const list = /^S?NOTE\b/.test(ref) ? notes : ref.startsWith('NAME') ? names : events;
    const it = list.find(ref, part || (/needs a type/.test(text) ? 'type' : null));
    if (!it) return null;
    const p = it.parts[part] || (/needs a type/.test(text) ? it.parts.type : null) || Object.values(it.parts)[0];
    if (p) {
      p.wrap.hidden = false;
      partNote(p, 'error', capital(firstSentence(text.replace(/^:\s*/, ''))));
      p.el.focus();
    }
    return `${it.name}${p && p !== it.parts.text ? ' ' + p.label.toLowerCase() : ''}: ${text.replace(/^:\s*/, '')}`;
  };

  // ---- layout
  const sections = [];
  if (isPerson) {
    sections.push(group('Name', 'name', [h('div.form-rows', defs.map(nameControl))]));
    sections.push(group('Other names and nicknames', 'aka', [
      names.el,
      h('div.list-add', addButton('Add another name', () => { names.add(buildName(null, true), { focus: true }); onChange(); })),
      h('datalist', { id: typeList }, NAME_TYPES.map((t) => h('option', { value: t }))),
      h('h3.sub-label', 'Nicknames'),
      nickWrap,
      h('div.list-add', addNickBtn),
    ]));
  }
  sections.push(group('Events', 'events', [
    events.el,
    h('div.list-add.add-row', [icon('plus'), picker]),
  ]));
  const notesGroup = group('Notes', 'notes', [
    notes.el,
    h('div.list-add', addButton('Add note', () => { notes.add(buildNote(null, true), { focus: true }); onChange(); })),
  ]);
  sections.push(notesGroup);

  shell = editorDialog(app, {
    title: cfg.title, subtitle: cfg.subtitle, name: cfg.name, what: cfg.what, sections,
    count, body, check, clearNotes, rebase, refused,
    send: cfg.send, current: cfg.current, reload: cfg.reload,
  });
  shell.updateSaveLabel();
  if (cfg.focus === 'notes') requestAnimationFrame(() => notesGroup.scrollIntoView({ block: 'start' }));
  if (cfg.focus === 'events') requestAnimationFrame(() => sections[sections.length - 2].scrollIntoView({ block: 'start' }));
  return shell.d;
}

// ---- notes, sources and media -----------------------------------------------------------

/** Lines that aren't shown for editing: continuations, and change stamps with what's under them. */
function hiddenLine(f, stamps) {
  if (f.tag === 'CONT' || f.tag === 'CONC') return true;
  if (f.tag === 'CHAN') { stamps.push(f.ref + '.'); return true; }
  return stamps.some((s) => f.ref.startsWith(s));
}

export function openRecordEditor(app, record) {
  const kindOne = { notes: 'note', sources: 'source', media: 'media record' }[record.kind] || 'record';
  let shell = null;
  const onChange = () => { if (shell) shell.updateSaveLabel(); };

  // a note's own text
  let seenText = record.text || '';
  const textPart = record.kind === 'notes' ? partField({ label: 'Text', multiline: true, value: seenText }) : null;
  if (textPart) {
    textPart.name('Note text');
    const mark = () => { textPart.wrap.classList.toggle('is-changed', textPart.get() !== textPart.norm(seenText)); if (textPart.wrap.classList.contains('note-error')) partNote(textPart, null, null); onChange(); };
    textPart.el.addEventListener('input', mark);
  }
  const textChanged = () => !!textPart && textPart.get() !== textPart.norm(seenText);

  const lines = itemList({
    key: 'lines', onChange,
    describe: {
      title: 'Lines',
      beforeRemove: (it) => {
        const under = lines.items.filter((x) => x.ref && x.ref.startsWith(it.ref + '.')).length;
        return under ? `${plural(under, 'line', 'lines')} under it will be removed too.` : null;
      },
      afterRemove: (it, removed) => {
        for (const x of lines.items) {
          if (!x.ref || !x.ref.startsWith(it.ref + '.')) continue;
          x.el.classList.toggle('is-gone-with-parent', removed || lines.items.some((y) => y !== it && y.removed && x.ref.startsWith(y.ref + '.')));
        }
      },
    },
  });
  const tagList = nextId('line-tags');
  const buildLine = (f, isNew) => {
    const it = {
      ref: isNew ? null : f.ref, isNew, removed: false, tag: isNew ? '' : f.tag,
      orig: isNew ? null : { value: f.value }, base: isNew ? 'New line' : f.label, parts: {},
      meaningful: () => !!(it.parts.tag.get() || it.parts.value.get()),
    };
    let body;
    if (isNew) {
      it.parts.tag = partField({ label: 'Tag', value: '', list: tagList, placeholder: 'e.g. PUBL', cls: 'f-tag' });
      it.parts.tag.el.classList.add('input-tag');
      it.parts.value = partField({ label: 'Value', value: '', cls: 'f-lvalue' });
      body = h('div.item-grid.line-new-grid', [it.parts.tag.wrap, it.parts.value.wrap]);
    } else if (f.editable) {
      const multi = f.tag === 'TEXT' || f.tag === 'NOTE' || /\n/.test(f.value);
      it.parts.value = partField({ label: 'Value', value: f.value, multiline: multi, kind: f.tag === 'DATE' ? 'date' : 'text', hint: f.tag === 'DATE' ? 'Dates: ' + app.fields.dates : null, cls: 'f-solo' });
      it.partName = (name) => name;
      body = it.parts.value.wrap;
    } else {
      body = h('p.line-fixed', [
        f.link ? icon('link') : null,
        h('span', f.value || '—'),
        h('span.line-why', f.link ? 'A link: remove it to unlink' : 'Can’t be edited'),
      ]);
    }
    const depth = isNew ? 0 : Math.min(f.depth, 4);
    it.displayTitle = (name) => name;
    lines.row(it, { body, cls: `item-line.depth-${depth}` + (isNew ? '.is-added' : '') });
    if (!isNew) it.titleEl.after(h('span.line-tag', f.tag));
    // for a new line, its name follows its tag
    if (isNew) {
      it.parts.tag.el.addEventListener('input', () => {
        it.tag = it.parts.tag.get().toUpperCase();
        it.base = it.tag ? `New ${it.tag} line` : 'New line';
        lines.relabel();
      });
    }
    return it;
  };
  const shownLines = () => { const stamps = []; return (record.fields || []).filter((f) => !hiddenLine(f, stamps)); };
  for (const f of shownLines()) { const it = buildLine(f, false); lines.items.push(it); lines.el.append(it.el); }
  lines.relabel();

  const underRemoved = (it) => lines.items.some((y) => y !== it && y.removed && it.ref && it.ref.startsWith(y.ref + '.'));
  const count = () => (textChanged() ? 1 : 0) + lines.items.reduce((n, it) => n + (underRemoved(it) ? 0 : (it.isNew ? (it.meaningful() ? 1 : 0) : it.removed ? 1 : lines.changedParts(it).length)), 0);
  const body = () => {
    const b = {};
    if (textChanged()) { b.text = textPart.get(); b.seenText = seenText; }
    const ops = [];
    for (const it of lines.items) {
      if (underRemoved(it)) continue;
      if (it.isNew) {
        if (it.meaningful()) ops.push({ add: it.parts.tag.get().toUpperCase(), set: { value: it.parts.value.get(), under: '' } });
      } else if (it.removed) ops.push({ ref: it.ref, seen: { tag: it.tag }, remove: true });
      else if (lines.changedParts(it).length) ops.push({ ref: it.ref, seen: { value: it.orig.value }, set: { value: it.parts.value.get() } });
    }
    if (ops.length) b.lines = ops;
    return Object.keys(b).length ? b : null;
  };
  const check = () => {
    if (textPart && textChanged() && !textPart.get()) {
      partNote(textPart, 'error', 'A note needs some text. To get rid of it, unlink it from everyone who uses it.');
      textPart.el.focus();
      return 'A note needs some text.';
    }
    for (const it of lines.items) {
      if (!it.isNew || !it.meaningful()) continue;
      const tag = it.parts.tag.get();
      if (!/^_?[A-Za-z][A-Za-z0-9_]{0,30}$/.test(tag)) {
        partNote(it.parts.tag, 'error', tag ? 'A tag is letters and digits, such as PUBL' : 'Give the line a tag, such as PUBL');
        it.parts.tag.el.focus();
        return `${it.name}: ${tag ? `“${tag}” isn’t a tag` : 'it needs a tag'}.`;
      }
      if (!it.parts.value.get()) {
        partNote(it.parts.value, 'error', 'Give the line a value');
        it.parts.value.el.focus();
        return `${it.name} needs a value.`;
      }
    }
    return null;
  };
  const clearNotes = () => {
    if (textPart) partNote(textPart, null, null);
    for (const it of lines.items) for (const p of Object.values(it.parts)) partNote(p, null, null);
  };
  const rebase = (current, out) => {
    if (textPart) {
      rebasePart(textPart, seenText, current.text || '', 'Text', out, onChange);
      seenText = current.text || '';
      textPart.wrap.classList.toggle('is-changed', textChanged());
    }
    // the same lines, with the same tags, at the same places: rebase their values
    const stamps = [];
    const fresh = (current.fields || []).filter((f) => !hiddenLine(f, stamps));
    const existing = lines.items.filter((it) => !it.isNew);
    const same = existing.length === fresh.length && existing.every((it, i) => it.ref === fresh[i].ref && it.tag === fresh[i].tag);
    if (!same) {
      const lost = existing.filter((it) => lines.isChanged(it)).map((it) => it.name);
      lines.replaceExisting(fresh, buildLine);
      out.reloaded.push({ list: 'Lines', lost });
    } else {
      lines.rebase(fresh, buildLine, (f) => ({ value: f.value }), out);
    }
    record = current;
  };
  const refused = (message) => {
    const m = /^line ([\d.]+)\b[^:]*:?\s*(.*)$/s.exec(message);
    let it = null, p = null;
    if (m) { it = lines.items.find((x) => x.ref === m[1]); p = it && it.parts.value; }
    const add = /^([A-Z_][A-Z0-9_]*) needs a value/.exec(message) || /^'([^']*)' is not a tag/.exec(message);
    if (!it && add) { it = lines.items.find((x) => x.isNew && x.parts.tag.get().toUpperCase() === add[1].toUpperCase()); p = it && (/needs a value/.test(message) ? it.parts.value : it.parts.tag); }
    if (!it && /^a note needs/.test(message) && textPart) { partNote(textPart, 'error', capital(message)); textPart.el.focus(); return capital(message); }
    if (!it || !p) return null;
    partNote(p, 'error', capital(firstSentence(m ? m[2] : message)));
    p.el.focus();
    return `${it.name}: ${m ? m[2] : message}`;
  };

  const sections = [];
  if (textPart) sections.push(group('Text', 'text', [textPart.wrap]));
  sections.push(group(record.kind === 'notes' ? 'More about this note' : 'Lines', 'lines', [
    lines.el,
    h('div.list-add', addButton('Add line', () => { lines.add(buildLine(null, true), { focus: true }); onChange(); })),
    h('datalist', { id: tagList }, LINE_TAGS.map(([t, l]) => h('option', { value: t, label: l }))),
  ]));

  shell = editorDialog(app, {
    title: `Edit ${kindOne} ${record.key}`, subtitle: record.title, name: `${kindOne} ${record.key}`, what: `this ${kindOne}`,
    cls: 'dialog-record', sections, count, body, check, clearNotes, rebase, refused,
    send: (b) => api.editRecord(app.fileId, record.key, b),
    current: (b) => b.record,
    reload: () => api.record(app.fileId, record.key),
  });
  shell.updateSaveLabel();
  return shell.d;
}
