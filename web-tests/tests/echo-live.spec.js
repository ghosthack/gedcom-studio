// Edits made elsewhere (through the API, as the desktop app or the shell
// would) appear on the page that's open, without reloading it; a form that's
// open meanwhile offers the new values, or says where they clash.
import { test, expect } from '@playwright/test';
import { api } from './daemon.js';
import { openFamily, FILE_NAME, isPhone, heading, go, edit, save, peopleList, listRow, tree, chartNode, region, eventRow } from './family.js';

let id;
test.beforeEach(async ({ page }) => {
  ({ id } = await openFamily(page));
});

const editPerson = (key, body) => api(`files/${id}/people/${key}/edit`, body);
const editFamily = (key, body) => api(`files/${id}/families/${key}/edit`, body);
const editRecord = (key, body) => api(`files/${id}/records/${key}/edit`, body);

test('a person’s page follows their name, birth, events, other names and notes', async ({ page }, info) => {
  await go(page, id, 'people', 'I0');
  await expect(heading(page)).toHaveText('Tomás Iker Fábregas');
  await editPerson('I0', {
    set: { given: 'Tomás', surname: 'Fábregas Ruiz', 'birth.date': '1978' },
    seen: { given: 'Tomás Iker', surname: 'Fábregas', 'birth.date': '14 MAR 1979' },
    events: [{ ref: 'OCCU:0', seen: { value: 'Engineer' }, set: { value: 'Teacher' } }],
    notes: [{ ref: 'NOTE:0', seen: { text: 'Likes trains.' }, set: { text: 'Likes boats.' } }],
    nicknames: { seen: ['Adri'], set: ['Fercho'] },
  });
  await expect(heading(page)).toHaveText('Tomás Fábregas Ruiz');
  await expect(page).toHaveTitle(`Tomás Fábregas Ruiz · ${FILE_NAME}`);
  await expect(page.locator('.head-lifespan')).toHaveText('b. 1978');
  await expect(eventRow(page, 'Born').locator('.event-year')).toHaveText('1978');
  await expect(eventRow(page, 'Occupation').locator('.event-value')).toHaveText('Teacher');
  await expect(page.locator('.person-head .aka')).toContainText('“Fercho”');
  await expect(region(page, 'Notes').locator('.note-text').first()).toHaveText('Likes boats.');
  if (!isPhone(info)) await expect(listRow(page.locator('#people-list'), 'I0').locator('.person-name')).toHaveText('Tomás Fábregas Ruiz');
});

test('the people list follows a rename', async ({ page }, info) => {
  await go(page, id, 'people', 'I4');
  const list = await peopleList(page, info);
  await editPerson('I3', { set: { surname: 'Ruiz' }, seen: { surname: 'Pérez' } });
  await expect(listRow(list, 'I3').locator('.person-name')).toHaveText('Ana Ruiz');
});

test('a family page follows its partners and its marriage', async ({ page }) => {
  await go(page, id, 'families', 'F2');
  await editPerson('I3', { set: { surname: 'Ruiz' }, seen: { surname: 'Pérez' } });
  await expect(heading(page)).toHaveText('Tomás Iker Fábregas & Ana Ruiz');
  await editFamily('F2', { events: [{ ref: 'MARR:0', seen: { place: '' }, set: { place: 'Segovia' } }] });
  await expect(page.locator('main .page-sub')).toHaveText('Married 2010 in Segovia · 1 child');
  await expect(eventRow(page, 'Married').locator('.event-place')).toHaveText('Segovia');
});

test('the charts follow a birth and a name', async ({ page }, info) => {
  await go(page, id, 'people', 'I1');
  const chart = await tree(page, info, 'descendants');
  await editPerson('I4', { set: { 'birth.date': '2013', given: 'Lucía Inés' }, seen: { 'birth.date': '2012', given: 'Lucía' } });
  await expect(chartNode(chart, 'Lucía').locator('.pnode-name')).toHaveText('Lucía Inés Fábregas');
  await expect(chartNode(chart, 'Lucía').locator('.pnode-life')).toHaveText('b. 2013');
});

test('a record’s page follows its title and text', async ({ page }) => {
  await go(page, id, 'records', 'S1');
  await editRecord('S1', { lines: [{ ref: '0', seen: { value: 'Parish register' }, set: { value: 'Parish book' } }] });
  await expect(heading(page)).toHaveText('Parish book');
  await go(page, id, 'records', 'N1');
  await editRecord('N1', { seenText: 'Shared research note.', text: 'Shared, and changed elsewhere.' });
  await expect(region(page, 'Text').locator('.note-text')).toHaveText('Shared, and changed elsewhere.');
});

test('an open person form offers what changed elsewhere, keeping the user’s own edits', async ({ page }) => {
  await go(page, id, 'people', 'I0');
  const d = await edit(page);
  await d.getByRole('textbox', { name: 'Residence place' }).fill('Serrano 101, Cuenca');
  await editPerson('I0', {
    set: { surname: 'Fábregas Ruiz' }, seen: { surname: 'Fábregas' },
    events: [{ ref: 'OCCU:0', seen: { value: 'Engineer' }, set: { value: 'Teacher' } }],
  });
  // the page beneath has moved on; the form says so
  await expect(heading(page)).toHaveText('Tomás Iker Fábregas Ruiz');
  await expect(d.locator('.live-banner')).toContainText('This person may have changed elsewhere');
  await d.getByRole('button', { name: 'Reload values' }).click();
  await expect(d.getByRole('textbox', { name: 'Surname', exact: true })).toHaveValue('Fábregas Ruiz');
  await expect(d.getByRole('textbox', { name: 'Occupation value' })).toHaveValue('Teacher');
  await expect(d.getByRole('textbox', { name: 'Residence place' })).toHaveValue('Serrano 101, Cuenca');
  await expect(d.locator('.form-top')).toContainText('Updated from elsewhere: Surname, Occupation value');
  await save(d, 1);

  await expect(eventRow(page, 'Residence').locator('.event-place')).toHaveText('Serrano 101, Cuenca');
  await expect(eventRow(page, 'Occupation').locator('.event-value')).toHaveText('Teacher');
  await expect(heading(page)).toHaveText('Tomás Iker Fábregas Ruiz');
});

test('an open family form brings in a marriage changed elsewhere', async ({ page }) => {
  await go(page, id, 'families', 'F2');
  const d = await edit(page);
  await d.getByRole('textbox', { name: 'Married place' }).fill('Segovia');
  await editFamily('F2', { events: [{ ref: 'MARR:0', seen: { date: '2010' }, set: { date: '2011' } }] });
  await expect(page.locator('main .page-sub')).toHaveText('Married 2011 · 1 child');
  await d.getByRole('button', { name: 'Reload values' }).click();
  await expect(d.getByRole('textbox', { name: 'Married date' })).toHaveValue('2011');
  await save(d, 1);
  await expect(page.locator('main .page-sub')).toHaveText('Married 2011 in Segovia · 1 child');
});

test('an open record form: the same line changed both here and elsewhere', async ({ page }) => {
  await go(page, id, 'records', 'S1');
  const d = await edit(page);
  await d.getByRole('textbox', { name: 'Title' }).fill('Mine');
  await d.getByRole('textbox', { name: 'Author' }).fill('Father Gómez');
  await editRecord('S1', { lines: [{ ref: '0', seen: { value: 'Parish register' }, set: { value: 'Theirs' } }] });
  await expect(heading(page)).toHaveText('Theirs');
  await d.getByRole('button', { name: 'Save 2 changes' }).click();
  await expect(d.locator('.form-top')).toContainText('Changed elsewhere');
  await expect(d.locator('.note-conflict')).toContainText('Elsewhere it’s now “Theirs”');
  await expect(d.getByRole('textbox', { name: 'Author' })).toHaveValue('Father Gómez');
  await d.getByRole('button', { name: 'Use theirs' }).click();
  await save(d, 1);
  await expect(heading(page)).toHaveText('Theirs');
  await expect(region(page, 'Details').locator('.field-row', { hasText: 'AUTH' }).locator('.field-value')).toHaveText('Father Gómez');
});
