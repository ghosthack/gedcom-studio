import { test, expect } from '@playwright/test';
import { open, fileId, api } from './daemon.js';

// Everything the pages show can be edited: each event's type, date, place and
// value, the second of several residences, notes, other names, nicknames,
// family events and the lines of a source.
const GED = `0 HEAD
1 GEDC
2 VERS 5.5.1
1 CHAR UTF-8
0 @I0@ INDI
1 NAME Tomás Iker /Fábregas/
2 NICK Adri
1 NAME Iker /Fábregas/
2 TYPE aka
1 SEX M
1 BIRT
2 DATE 14 MAR 1979
2 PLAC Segovia, Spain
1 RESI
2 DATE 1979
1 RESI
2 DATE 1981
1 RESI
2 DATE 2005
1 GRAD
2 TYPE Técnico Químico
2 NOTE IESA, Segovia.
1 OCCU Engineer
1 NOTE Likes trains.
1 NOTE @N1@
1 SOUR @S1@
1 FAMS @F1@
0 @I1@ INDI
1 NAME Ana /Pérez/
1 SEX F
1 FAMS @F1@
0 @F1@ FAM
1 HUSB @I0@
1 WIFE @I1@
1 MARR
2 DATE 2010
0 @N1@ NOTE Shared research note.
0 @S1@ SOUR
1 TITL Parish register
1 AUTH Unknown
0 TRLR
`;

let id;
const timeline = (page) => page.locator('ol.timeline > li');
const person = () => api(`files/${id}/people/I0`);
const event = (p, ref) => p.events.find((e) => e.ref === ref);

async function edit(page) {
  await page.getByRole('button', { name: 'Edit', exact: true }).first().click();
  // the form; a confirmation can open above it
  const dialog = page.locator('dialog.dialog-form');
  await expect(dialog.getByRole('heading', { name: /^Edit / })).toBeVisible();
  return dialog;
}

async function save(dialog, changes) {
  const label = changes === 1 ? 'Save 1 change' : `Save ${changes} changes`;
  await dialog.getByRole('button', { name: label }).click();
  await expect(dialog).toBeHidden();
}

test.beforeEach(async ({ page }) => {
  const pairing = open(GED, 'tomas');
  id = fileId(pairing);
  await page.goto(pairing);
  await page.goto(`/#/f/${id}/people/I0`);
  await expect(page.getByRole('heading', { name: /Tomás Iker Fábregas/ })).toBeVisible();
});

test('the graduation’s type and date', async ({ page }) => {
  const dialog = await edit(page);
  await dialog.getByRole('textbox', { name: 'Graduated type' }).fill('Técnico en Química');
  await dialog.getByRole('textbox', { name: 'Graduated date' }).fill('dec 1997');
  await save(dialog, 2);

  const grad = timeline(page).filter({ hasText: 'Graduated' });
  await expect(grad).toContainText('Graduated · Técnico en Química');
  await expect(grad).toContainText('Dec 1997');
  expect(event(await person(), 'GRAD:0').fields).toEqual({ type: 'Técnico en Química', date: 'DEC 1997', place: '', value: '' });
});

test('the second residence, and an occupation’s text', async ({ page }) => {
  const dialog = await edit(page);
  await expect(dialog.getByRole('textbox', { name: 'Residence 2 date' })).toHaveValue('1981');
  await dialog.getByRole('textbox', { name: 'Residence 2 place' }).fill('Serrano 101, Segovia');
  await dialog.getByRole('textbox', { name: 'Occupation value' }).fill('Chemical engineer');
  await save(dialog, 2);

  await expect(timeline(page).filter({ hasText: '1981' })).toContainText('Serrano 101, Segovia');
  await expect(timeline(page).filter({ hasText: 'Occupation' })).toContainText('Chemical engineer');
  const p = await person();
  expect(event(p, 'RESI:1').fields.place).toBe('Serrano 101, Segovia');
  expect(event(p, 'RESI:0').fields.place).toBe('');
});

test('removing an event, asking first when it holds more', async ({ page }) => {
  const dialog = await edit(page);
  // the graduation has a note of its own: removing it asks
  await dialog.getByRole('button', { name: 'Remove Graduated' }).click();
  const ask = page.getByRole('dialog', { name: 'Remove Graduated?' });
  await expect(ask).toContainText('This event also has notes or sources, which will be removed too');
  await ask.getByRole('button', { name: 'Cancel' }).click();
  await expect(dialog.getByRole('button', { name: 'Undo removing Graduated' })).toBeHidden();

  // the third residence doesn't, and can be taken back
  await dialog.getByRole('button', { name: 'Remove Residence 3' }).click();
  await expect(dialog.getByRole('button', { name: 'Undo removing Residence 3' })).toBeVisible();
  await expect(dialog.getByRole('button', { name: 'Save 1 change' })).toBeVisible();
  await dialog.getByRole('button', { name: 'Undo removing Residence 3' }).click();
  await expect(dialog.getByRole('button', { name: 'Save changes' })).toBeVisible();
  await dialog.getByRole('button', { name: 'Remove Residence 3' }).click();
  await save(dialog, 1);

  await expect(timeline(page).filter({ hasText: '2005' })).toHaveCount(0);
  await expect(timeline(page).filter({ hasText: 'Residence' })).toHaveCount(2);
  expect((await person()).events.filter((e) => e.tag === 'RESI').map((e) => e.date)).toEqual(['1979', '1981']);
});

test('adding an event of a kind of its own, which needs a type', async ({ page }) => {
  const dialog = await edit(page);
  await dialog.getByRole('combobox', { name: 'Add an event' }).selectOption('EVEN');
  await expect(dialog.getByRole('textbox', { name: 'Event type' })).toBeFocused();
  await dialog.getByRole('textbox', { name: 'Event date' }).fill('oct 1988');
  await dialog.getByRole('button', { name: 'Save 1 change' }).click();
  // not sent without a type: the row says so
  await expect(dialog.locator('.item-event.is-new')).toContainText('needs a type');
  await expect(dialog.getByRole('alert')).toContainText('Not saved');

  await dialog.getByRole('textbox', { name: 'Event type' }).fill('First communion');
  await save(dialog, 1);
  const ev = timeline(page).filter({ hasText: 'First communion' });
  await expect(ev).toContainText('Event · First communion');
  await expect(ev).toContainText('Oct 1988');
  expect((await person()).events.find((e) => e.tag === 'EVEN').fields).toEqual({ type: 'First communion', date: 'OCT 1988', place: '', value: '' });
});

test('a note edited, another added; a shared note is only linked', async ({ page }) => {
  const dialog = await edit(page);
  await expect(dialog.locator('.item-note.is-shared')).toContainText('Shared research note.');
  await expect(dialog.getByRole('link', { name: 'Open N1' })).toBeVisible();
  await dialog.getByRole('textbox', { name: 'Note', exact: true }).fill('Likes trains\nand boats.');
  await dialog.getByRole('button', { name: 'Add note' }).click();
  await dialog.getByRole('textbox', { name: 'Note 2' }).fill('Moved in 2018.');
  await save(dialog, 2);

  const notes = page.getByRole('region', { name: 'Notes' });
  await expect(notes).toContainText('Likes trains\nand boats.');
  await expect(notes).toContainText('Moved in 2018.');
  expect((await person()).noteItems.map((n) => n.text)).toEqual(['Likes trains\nand boats.', 'Shared research note.', 'Moved in 2018.']);
});

test('other names and nicknames', async ({ page }) => {
  const dialog = await edit(page);
  await expect(dialog.getByRole('combobox', { name: 'Other name type' })).toHaveValue('aka');
  await dialog.getByRole('textbox', { name: 'Other name given names' }).fill('Iker A.');
  await dialog.getByRole('button', { name: 'Add another name' }).click();
  await dialog.getByRole('textbox', { name: 'Other name 2 given names' }).fill('Tomás');
  await dialog.getByRole('textbox', { name: 'Other name 2 surname' }).fill('Fábregas García');
  await dialog.getByRole('combobox', { name: 'Other name 2 type' }).fill('birth');
  await expect(dialog.getByRole('textbox', { name: 'Nickname' })).toHaveValue('Adri');
  await dialog.getByRole('button', { name: 'Add nickname' }).click();
  await dialog.getByRole('textbox', { name: 'Nickname 2' }).fill('Fer');
  await save(dialog, 3);

  const aka = page.locator('.aka');
  await expect(aka).toContainText('Iker A. Fábregas');
  await expect(aka).toContainText('Tomás Fábregas García');
  await expect(aka).toContainText('“Fer”');
  const p = await person();
  expect(p.nicknames).toEqual(['Adri', 'Fer']);
  expect(p.otherNames.map((n) => n.fields)).toEqual([
    { given: 'Iker A.', surname: 'Fábregas', type: 'aka' },
    { given: 'Tomás', surname: 'Fábregas García', type: 'birth' },
  ]);
});

test('a change made elsewhere meanwhile is brought in, keeping the user’s edits', async ({ page }) => {
  const dialog = await edit(page);
  await dialog.getByRole('textbox', { name: 'Residence 2 place' }).fill('Serrano 101');
  await dialog.getByRole('textbox', { name: 'Residence 2 date' }).fill('1980');
  // someone else redates the same residence
  await api(`files/${id}/people/I0/edit`, { events: [{ ref: 'RESI:1', seen: { date: '1981' }, set: { date: '1982' } }] });
  await dialog.getByRole('button', { name: 'Save 2 changes' }).click();
  await expect(dialog.locator('.form-top')).toContainText('Changed elsewhere');
  await expect(dialog.locator('.form-top')).toContainText('Residence 2 date');
  await expect(dialog.locator('.note-conflict')).toContainText('Elsewhere it’s now “1982”');
  await expect(dialog.getByRole('textbox', { name: 'Residence 2 place' })).toHaveValue('Serrano 101');
  await dialog.getByRole('button', { name: 'Use theirs' }).click();
  await save(dialog, 1);

  const r = event(await person(), 'RESI:1').fields;
  expect([r.date, r.place]).toEqual(['1982', 'Serrano 101']);
});

test('a family’s events and notes', async ({ page }) => {
  await page.goto(`/#/f/${id}/families/F1`);
  await expect(page.getByRole('heading', { name: /Tomás Iker Fábregas & Ana Pérez/ })).toBeVisible();
  const dialog = await edit(page);
  await expect(dialog.getByRole('textbox', { name: 'Married date' })).toHaveValue('2010');
  await dialog.getByRole('textbox', { name: 'Married place' }).fill('Segovia');
  await dialog.getByRole('combobox', { name: 'Add an event' }).selectOption('ENGA');
  await dialog.getByRole('textbox', { name: 'Engaged date' }).fill('2009');
  await dialog.getByRole('button', { name: 'Add note' }).click();
  await dialog.getByRole('textbox', { name: 'Note' }).fill('Civil wedding.');
  await save(dialog, 3);

  const events = page.getByRole('region', { name: 'Events' });
  await expect(events.locator('ol.timeline > li')).toHaveCount(2);
  await expect(events.locator('ol.timeline > li').first()).toContainText('Engaged');
  await expect(events).toContainText('Segovia');
  await expect(page.getByRole('region', { name: 'Notes' })).toContainText('Civil wedding.');
  const f = await api(`files/${id}/families/F1`);
  expect(f.events.map((e) => e.ref)).toEqual(['ENGA:0', 'MARR:0']);
});

test('a source’s lines', async ({ page }) => {
  await page.goto(`/#/f/${id}/records/S1`);
  await expect(page.getByRole('heading', { name: 'Parish register' })).toBeVisible();
  await page.getByRole('button', { name: 'Edit', exact: true }).click();
  const dialog = page.locator('dialog.dialog-form');
  await dialog.getByRole('textbox', { name: 'Title' }).fill('Parish register of Segovia');
  await dialog.getByRole('button', { name: 'Remove Author' }).click();
  await dialog.getByRole('button', { name: 'Add line' }).click();
  await dialog.getByRole('combobox', { name: 'New line tag' }).fill('publ');
  await dialog.getByRole('textbox', { name: 'New PUBL line value' }).fill('Diocese of Segovia');
  await save(dialog, 3);

  await expect(page.getByRole('heading', { name: 'Parish register of Segovia' })).toBeVisible();
  const details = page.getByRole('region', { name: 'Details' });
  await expect(details).toContainText('Diocese of Segovia');
  await expect(details).not.toContainText('Unknown');
  const s = await api(`files/${id}/records/S1`);
  expect(s.fields.filter((x) => x.tag !== 'CHAN' && x.depth === 0).map((x) => [x.tag, x.value]))
    .toEqual([['TITL', 'Parish register of Segovia'], ['PUBL', 'Diocese of Segovia']]);
});

test('a shared note’s text', async ({ page }) => {
  await page.goto(`/#/f/${id}/records/N1`);
  await page.getByRole('button', { name: 'Edit', exact: true }).click();
  const dialog = page.locator('dialog.dialog-form');
  await dialog.getByRole('textbox', { name: 'Note text' }).fill('Shared research note,\nupdated.');
  await save(dialog, 1);
  await expect(page.getByRole('region', { name: 'Text' })).toContainText('Shared research note,\nupdated.');
});

test('the form fits the screen, with room for fingers', async ({ page }, info) => {
  const dialog = await edit(page);
  await dialog.getByRole('button', { name: 'Add another name' }).click();
  const body = dialog.locator('.dialog-body');
  const [scroll, client] = await body.evaluate((el) => [el.scrollWidth, el.clientWidth]);
  expect(scroll).toBeLessThanOrEqual(client);
  if (info.project.name === 'phone') {
    for (const b of await dialog.locator('.item-remove, .add-type, .add-btn').all()) {
      const box = await b.boundingBox();
      expect(Math.min(box.width, box.height)).toBeGreaterThanOrEqual(40);
    }
  }
});
