// Adding relatives, linking people already in the file, and unlinking.
import { h, icon, replace, debounce } from '../dom.js';
import { api } from '../api.js';
import { dialog, button, notice, toast, avatar, href, SEX, life } from '../ui.js';

const RELATIONS = [
  ['child', 'Child'],
  ['spouse', 'Spouse'],
  ['father', 'Father'],
  ['mother', 'Mother'],
  ['sibling', 'Sibling'],
];

function segmented(name, options, value, onChange) {
  const wrap = h('div.seg-input', { role: 'radiogroup' });
  for (const [v, label] of options) {
    const id = `${name}-${v}`;
    const input = h('input', { type: 'radio', name, id, value: v, checked: v === value });
    input.addEventListener('change', () => onChange(v));
    wrap.append(input, h('label', { for: id }, label));
  }
  return wrap;
}

function field(label, control, { id, hint } = {}) {
  return h('div.field', [h('label.field-label', { for: id }, label), control, hint ? h('p.hint', hint) : null]);
}

function partnerLabel(f) {
  if (f.partner) return `With ${f.partner.name}${f.partner.lifespan ? ' (' + life(f.partner.lifespan) + ')' : ''}`;
  return 'Without a recorded partner';
}

/**
 * A new person as `relation` of `p`. `p` may be a full person or a summary;
 * for a child, when `p` has several families, the family is asked for.
 */
export async function addRelativeDialog(app, p, relation = 'child', { family } = {}) {
  let person = p;
  if (!person.families) {
    try { person = await api.person(app.fileId, p.key); } catch (e) { toast(e.message, { kind: 'error' }); return; }
  }
  let rel = relation;
  const uid = Math.random().toString(36).slice(2, 7);
  const given = h('input.input', { id: 'given-' + uid, type: 'text', autocomplete: 'off', placeholder: 'Given names' });
  const surname = h('input.input', { id: 'surname-' + uid, type: 'text', autocomplete: 'off', placeholder: 'Surname' });
  const sex = h('select.select', { id: 'sex-' + uid }, [h('option', { value: '' }, 'Not known'), ...['M', 'F'].map((v) => h('option', { value: v }, SEX[v]))]);
  const born = h('input.input.input-date', { id: 'born-' + uid, type: 'text', autocomplete: 'off', placeholder: 'e.g. 12 JUN 1850', spellcheck: 'false' });
  const famBox = h('div.fam-choice');
  const err = h('div.form-error-slot');
  let chosenFamily = family || (person.families[0] && person.families[0].family) || null;

  const surnameFor = (r) => {
    if (r === 'father' || r === 'sibling') return person.surname || '';
    if (r === 'child') {
      if (person.sex === 'M') return person.surname || '';
      const fam = person.families.find((f) => f.family === chosenFamily);
      if (fam && fam.partner && fam.partner.sex === 'M') return fam.partner.surname || '';
      return person.surname || '';
    }
    return '';
  };
  let autoSurname = surnameFor(rel);
  surname.value = autoSurname;
  surname.addEventListener('input', () => { autoSurname = null; });
  // husband or wife: fills the empty place in `family`
  const fixed = (rel === 'husband' || rel === 'wife') && !!family;
  const sexFor = (r) => (r === 'father' || r === 'husband' ? 'M' : r === 'mother' || r === 'wife' ? 'F' : r === 'spouse' ? (person.sex === 'M' ? 'F' : person.sex === 'F' ? 'M' : '') : '');
  sex.value = sexFor(rel);

  const renderFamilies = () => {
    replace(famBox);
    if (rel !== 'child' || person.families.length < 2) return;
    famBox.append(h('p.field-label', 'Which family?'));
    for (const f of person.families) {
      const id = `fam-${uid}-${f.family}`;
      const input = h('input', { type: 'radio', name: 'family-' + uid, id, value: f.family, checked: f.family === chosenFamily });
      input.addEventListener('change', () => {
        chosenFamily = f.family;
        if (autoSurname !== null) { autoSurname = surnameFor(rel); surname.value = autoSurname; }
      });
      famBox.append(h('label.radio-card', { for: id }, [input, h('span.radio-text', [h('span', partnerLabel(f)), h('span.radio-meta', `${f.family} · ${f.children.length} ${f.children.length === 1 ? 'child' : 'children'}`)])]));
    }
  };
  renderFamilies();

  const relSeg = segmented('rel-' + uid, RELATIONS, rel, (v) => {
    rel = v;
    sex.value = sexFor(v);
    if (autoSurname !== null) { autoSurname = surnameFor(v); surname.value = autoSurname; }
    renderFamilies();
    d.el.querySelector('.dialog-title').textContent = titleFor(v);
  });
  const titleFor = (r) => (r === 'husband' || r === 'wife') ? `Add a ${r} to family ${family}` : `Add ${({ child: 'a child', spouse: 'a spouse or partner', father: 'a father', mother: 'a mother', sibling: 'a sibling' })[r]} of ${person.name}`;

  const submit = button('Add person', { kind: 'primary', type: 'submit', icon: 'plus' });
  const d = dialog({
    title: titleFor(rel),
    subtitle: fixed ? `A new person is added to the file and takes the empty place in ${family}.` : 'A new person is added to the file and linked.',
    size: 'md',
    body: [
      fixed ? null : field('Relation', relSeg),
      famBox,
      h('div.field-grid', [
        field('Given names', given, { id: given.id }),
        field('Surname', surname, { id: surname.id }),
        field('Sex', sex, { id: sex.id }),
        field('Birth date', born, { id: born.id, hint: 'Optional. For example 1850, ABT 1850 or 12 JUN 1850.' }),
      ]),
      err,
    ],
    actions: [button('Cancel', { kind: 'ghost', onclick: () => d.close() }), submit],
    onSubmit: async () => {
      replace(err);
      const g = given.value.trim(), sn = surname.value.trim().replace(/\//g, '');
      if (!g && !sn) { replace(err, notice('error', 'Give the new person a name — at least a given name or a surname.')); given.focus(); return; }
      const body = { relation: rel, name: `${g} /${sn}/`.trim(), sex: sex.value || undefined, fields: {} };
      if (born.value.trim()) body.fields['birth.date'] = born.value.trim();
      if (rel === 'child' && person.families.length > 1 && chosenFamily) body.family = chosenFamily;
      else if ((rel === 'child' || fixed) && family) body.family = family;
      d.setBusy(true);
      try {
        const r = await app.mutate(() => api.addRelative(app.fileId, person.key, body));
        d.close();
        toast(r.message, { kind: 'success', action: r.person ? { label: 'Open', href: href(app.fileId, 'people', r.person.key) } : null, timeout: 8000 });
        app.changed();
      } catch (e) {
        if (e.status !== 401) replace(err, notice('error', e.message, { title: 'Not added' }));
      } finally {
        d.setBusy(false);
      }
    },
  });
  given.focus();
}

/** A person with no relatives yet. */
export function newPersonDialog(app) {
  const uid = Math.random().toString(36).slice(2, 7);
  const given = h('input.input', { id: 'ng-' + uid, type: 'text', autocomplete: 'off' });
  const surname = h('input.input', { id: 'ns-' + uid, type: 'text', autocomplete: 'off' });
  const sex = h('select.select', { id: 'nx-' + uid }, [h('option', { value: '' }, 'Not known'), ...['M', 'F'].map((v) => h('option', { value: v }, SEX[v]))]);
  const born = h('input.input.input-date', { id: 'nb-' + uid, type: 'text', autocomplete: 'off', placeholder: 'e.g. 12 JUN 1850', spellcheck: 'false' });
  const err = h('div.form-error-slot');
  const d = dialog({
    title: 'New person',
    subtitle: 'They start without relatives; link them from their page.',
    body: [h('div.field-grid', [
      field('Given names', given, { id: given.id }),
      field('Surname', surname, { id: surname.id }),
      field('Sex', sex, { id: sex.id }),
      field('Birth date', born, { id: born.id }),
    ]), err],
    actions: [button('Cancel', { kind: 'ghost', onclick: () => d.close() }), button('Add person', { kind: 'primary', type: 'submit', icon: 'plus' })],
    onSubmit: async () => {
      replace(err);
      const g = given.value.trim(), sn = surname.value.trim().replace(/\//g, '');
      if (!g && !sn) { replace(err, notice('error', 'Give the new person a name.')); given.focus(); return; }
      const body = { name: `${g} /${sn}/`, sex: sex.value || undefined, fields: born.value.trim() ? { 'birth.date': born.value.trim() } : {} };
      d.setBusy(true);
      try {
        const r = await app.mutate(() => api.addPerson(app.fileId, body));
        d.close();
        toast(r.message, { kind: 'success' });
        await app.changed();
        if (r.person) app.navigate(href(app.fileId, 'people', r.person.key));
      } catch (e) {
        if (e.status !== 401) replace(err, notice('error', e.message, { title: 'Not added' }));
      } finally {
        d.setBusy(false);
      }
    },
  });
}

/** Makes someone already in the file a relative of `p`. */
export async function linkDialog(app, p, { as, title } = {}) {
  let person = p;
  if (!person.families) {
    try { person = await api.person(app.fileId, p.key); } catch (e) { toast(e.message, { kind: 'error' }); return; }
  }
  const uid = Math.random().toString(36).slice(2, 7);
  const asSelect = h('select.select', { id: 'as-' + uid }, [
    h('option', { value: 'children' }, 'Child'),
    h('option', { value: 'spouses' }, 'Spouse or partner'),
    h('option', { value: 'father' }, 'Father'),
    h('option', { value: 'mother' }, 'Mother'),
    h('option', { value: 'siblings' }, 'Sibling'),
    person.families.length ? h('optgroup', { label: 'Child in a family' }, person.families.map((f) =>
      h('option', { value: `families/${f.family}/children` }, `Child · ${partnerLabel(f).replace(/^W/, 'w')}`))) : null,
  ]);
  if (as && ![...asSelect.options].some((o) => o.value === as)) asSelect.append(h('option', { value: as }, as.replace(/^families\/(\w+)\//, 'In $1 as ')));
  if (as) asSelect.value = as;
  else if (person.families.length > 1) asSelect.value = `families/${person.families[0].family}/children`;

  const q = h('input.input', { id: 'q-' + uid, type: 'search', autocomplete: 'off', spellcheck: 'false', placeholder: 'Type a name', aria: { controls: 'res-' + uid } });
  const results = h('div.pick-list', { id: 'res-' + uid, role: 'listbox', aria: { label: 'People' } });
  const err = h('div.form-error-slot');
  let chosen = null;
  let ctrl = null;
  const search = debounce(async () => {
    if (ctrl) ctrl.abort();
    const my = ctrl = new AbortController();
    const text = q.value.trim();
    try {
      const r = await api.people(app.fileId, { q: text, limit: 30, sort: 'name' }, my.signal);
      if (my !== ctrl) return;
      const list = r.people.filter((x) => x.key !== person.key);
      if (!list.length) { replace(results, h('p.pick-empty', text ? `No one matches “${text}”.` : 'No one else in the file yet.')); return; }
      replace(results, list.map((x) => {
        const opt = h('button.pick-item', { type: 'button', role: 'option', aria: { selected: chosen && chosen.key === x.key ? 'true' : 'false' } }, [
          avatar(x, 'sm'),
          h('span.person-text', [h('span.person-name', x.name), h('span.person-meta', [x.lifespan ? h('span.lifespan', life(x.lifespan)) : null, h('span.person-note', x.key), x.matched ? h('span.person-note', `“${x.matched}”`) : null])]),
          icon('check', 'pick-check'),
        ]);
        opt.addEventListener('click', () => {
          chosen = x;
          for (const o of results.querySelectorAll('.pick-item')) o.setAttribute('aria-selected', 'false');
          opt.setAttribute('aria-selected', 'true');
          submitBtn.disabled = false;
          submitBtn.querySelector('span').textContent = `Link ${x.given || x.name}`;
        });
        return opt;
      }));
    } catch (e) {
      if (e.name !== 'AbortError') replace(results, h('p.pick-empty', e.message));
    }
  }, 140);
  q.addEventListener('input', search);
  q.addEventListener('keydown', (e) => {
    if (e.key === 'ArrowDown') { const f = results.querySelector('.pick-item'); if (f) { e.preventDefault(); f.focus(); } }
  });
  results.addEventListener('keydown', (e) => {
    const items = [...results.querySelectorAll('.pick-item')];
    const i = items.indexOf(document.activeElement);
    if (e.key === 'ArrowDown' && i >= 0) { e.preventDefault(); (items[i + 1] || items[i]).focus(); }
    if (e.key === 'ArrowUp' && i >= 0) { e.preventDefault(); if (i === 0) q.focus(); else items[i - 1].focus(); }
  });
  search();

  const submitBtn = button('Link', { kind: 'primary', type: 'submit', icon: 'link', disabled: true });
  const d = dialog({
    title: title || `Link someone to ${person.name}`,
    subtitle: 'Choose a person already in the file and how they are related.',
    size: 'md',
    body: [
      field('Relationship', asSelect, { id: asSelect.id }),
      field('Person', q, { id: q.id }),
      results,
      err,
    ],
    actions: [button('Cancel', { kind: 'ghost', onclick: () => d.close() }), submitBtn],
    onSubmit: async () => {
      replace(err);
      if (!chosen) { replace(err, notice('error', 'Choose a person from the list first.')); return; }
      d.setBusy(true);
      try {
        const r = await app.mutate(() => api.link(app.fileId, person.key, chosen.key, asSelect.value));
        d.close();
        toast(r.message, { kind: 'success', timeout: 8000 });
        app.changed();
      } catch (e) {
        if (e.status !== 401) replace(err, notice('error', e.message, { title: 'Not linked' }));
      } finally {
        d.setBusy(false);
        submitBtn.disabled = !chosen;
      }
    },
  });
  q.focus();
}

/**
 * Takes a relative out of the family that connects them to `p`.
 * `other` is the relative (null when `p` leaves a family).
 */
export function unlinkDialog(app, p, path, other, roleText) {
  const who = other ? other.name : p.name;
  const text = other
    ? `${other.name} will no longer be recorded as the ${roleText} of ${p.name}.`
    : `${p.name} will no longer be ${roleText}.`;
  const err = h('div.form-error-slot');
  const confirmBtn = button('Unlink', { kind: 'danger', icon: 'unlink' });
  const d = dialog({
    title: `Unlink ${who}?`,
    size: 'sm',
    body: [
      h('p.dialog-text', text),
      notice('info', [
        h('strong', 'Nobody is deleted. '),
        h('span', 'Both people stay in the file with everything recorded about them; only the link between them goes. A family left connecting nobody is removed.'),
      ]),
      err,
    ],
    actions: [button('Cancel', { kind: 'ghost', onclick: () => d.close() }), confirmBtn],
  });
  confirmBtn.addEventListener('click', async () => {
    replace(err);
    d.setBusy(true);
    try {
      const r = await app.mutate(() => api.unlink(app.fileId, p.key, path));
      d.close();
      toast(r.message, { kind: 'success', timeout: 8000 });
      await app.changed();
    } catch (e) {
      if (e.status !== 401) replace(err, notice('error', e.message, { title: 'Not unlinked' }));
    } finally {
      d.setBusy(false);
    }
  });
  confirmBtn.focus();
}
