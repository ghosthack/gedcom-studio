// What the forms edit reaches the file when it's saved; and the shapes of
// names and notes that need care: words after a surname, GEDCOM 7 shared notes.
import { test, expect } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { api, openFile } from './daemon.js';
import { openFamily, heading, go, edit, save, region } from './family.js';

test('form edits, saved, are in the file', async ({ page }) => {
  test.setTimeout(45_000);
  const { id, path } = await openFamily(page);
  await go(page, id, 'people', 'I0');
  let d = await edit(page);
  await d.getByRole('textbox', { name: 'Surname', exact: true }).fill('Fábregas Ruiz');
  await d.getByRole('textbox', { name: 'Born date' }).fill('abt 1978');
  await d.getByRole('textbox', { name: 'Residence place' }).fill('Serrano 101, Cuenca');
  await d.getByRole('textbox', { name: 'Note', exact: true }).fill('Likes trains\nand boats.');
  await d.getByRole('textbox', { name: 'Nickname' }).fill('Fercho');
  await save(d, 5);
  await go(page, id, 'families', 'F2');
  d = await edit(page);
  await d.getByRole('textbox', { name: 'Married place' }).fill('Segovia');
  await save(d, 1);
  await go(page, id, 'records', 'S1');
  d = await edit(page);
  await d.getByRole('textbox', { name: 'Title' }).fill('Parish register of Segovia');
  await save(d, 1);

  await expect(page.locator('.unsaved')).toHaveText('3 unsaved');
  await page.locator('.save-btn').click();
  await expect(page.locator('.unsaved')).toBeHidden();
  const text = readFileSync(path, 'utf8').replace(/\r\n/g, '\n');
  for (const line of [
    '1 NAME Tomás Iker /Fábregas Ruiz/', '2 NICK Fercho', '2 DATE ABT 1978', '2 PLAC Serrano 101, Cuenca',
    '1 NOTE Likes trains\n2 CONT and boats.', '2 PLAC Segovia', '1 TITL Parish register of Segovia',
  ]) expect(text).toContain(line);
  expect(text).not.toContain('NICK Adri\n');
});

test('another name with words after the surname keeps them', async ({ page }) => {
  const { pairing, id } = openFile(`0 HEAD
1 GEDC
2 VERS 5.5.1
1 CHAR UTF-8
0 @I0@ INDI
1 NAME María /López/
1 NAME María /López/ de Pérez
2 TYPE married
0 TRLR
`, { name: 'maria' });
  await page.goto(pairing);
  await go(page, id, 'people', 'I0');
  const aka = page.locator('.person-head .aka .aka-item');
  await expect(aka).toHaveText(['María López de Pérezmarried']);
  let d = await edit(page);
  await expect(d.getByRole('textbox', { name: 'Other name surname' })).toHaveValue('López');
  await expect(d.getByRole('textbox', { name: 'Other name given names' })).toHaveValue('María de Pérez');
  await d.getByRole('textbox', { name: 'Other name surname' }).fill('López Díaz');
  await save(d, 1);
  await expect(aka).toHaveText(['María López Díaz de Pérezmarried']);
  d = await edit(page);
  await expect(d.getByRole('textbox', { name: 'Other name surname' })).toHaveValue('López Díaz');
  await expect(d.getByRole('textbox', { name: 'Other name given names' })).toHaveValue('María de Pérez');
});

test('a GEDCOM 7 shared note shows, edits on its page, and unlinks', async ({ page }) => {
  const { pairing, id } = openFile(`0 HEAD
1 GEDC
2 VERS 7.0
0 @I0@ INDI
1 NAME Ana /Pérez/
1 NOTE Her own note.
1 SNOTE @N1@
0 @N1@ SNOTE Shared in seven.
0 TRLR
`, { name: 'seven' });
  await page.goto(pairing);
  await go(page, id, 'people', 'I0');
  const notes = region(page, 'Notes').locator('.note-text');
  await expect(notes).toHaveText(['Her own note.', 'Shared in seven.']);
  let d = await edit(page);
  await expect(d.locator('.item-note.is-shared .shared-text')).toHaveText('Shared in seven.');
  await expect(d.getByRole('link', { name: 'Open N1' })).toBeVisible();
  await d.getByRole('button', { name: 'Cancel' }).click();

  await go(page, id, 'records', 'N1');
  d = await edit(page);
  await d.getByRole('textbox', { name: 'Note text' }).fill('Shared in seven, edited.');
  await save(d, 1);
  await go(page, id, 'people', 'I0');
  await expect(notes).toHaveText(['Her own note.', 'Shared in seven, edited.']);

  d = await edit(page);
  await d.getByRole('button', { name: 'Remove Shared note' }).click();
  await save(d, 1);
  await expect(notes).toHaveText(['Her own note.']);
  expect((await api(`files/${id}/people/I0`)).noteItems.map((n) => n.text)).toEqual(['Her own note.']);
  await expect(heading(page)).toHaveText('Ana Pérez');
});
