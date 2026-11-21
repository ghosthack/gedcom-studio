// A small family for the echo tests, and the moves a user makes to see it:
// open a person, edit them, look them up in the list, open the tree.
//
//   Juan Fábregas (1950–2010) & Rosa Gómez          F1
//     └ Tomás Iker Fábregas (b. 1979) & Ana Pérez   F2, married 2010
//         └ Lucía Fábregas (b. 2012)
//
// Tomás has a nickname, another name, an occupation, a residence, a note of
// his own and a note (N1) he shares with Ana; a source (S1) and a picture (M1).
import { expect } from '@playwright/test';
import { openFile } from './daemon.js';

export const GED = `0 HEAD
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
2 DATE 1990
2 PLAC Cuenca
1 OCCU Engineer
1 NOTE Likes trains.
1 NOTE @N1@
1 SOUR @S1@
1 OBJE @M1@
1 FAMC @F1@
1 FAMS @F2@
0 @I1@ INDI
1 NAME Juan /Fábregas/
1 SEX M
1 BIRT
2 DATE 1950
1 DEAT
2 DATE 2010
1 FAMS @F1@
0 @I2@ INDI
1 NAME Rosa /Gómez/
1 SEX F
1 FAMS @F1@
0 @I3@ INDI
1 NAME Ana /Pérez/
1 SEX F
1 BIRT
2 DATE 1981
1 NOTE @N1@
1 FAMS @F2@
0 @I4@ INDI
1 NAME Lucía /Fábregas/
1 SEX F
1 BIRT
2 DATE 2012
1 FAMC @F2@
0 @F1@ FAM
1 HUSB @I1@
1 WIFE @I2@
1 CHIL @I0@
0 @F2@ FAM
1 HUSB @I0@
1 WIFE @I3@
1 CHIL @I4@
1 MARR
2 DATE 2010
0 @N1@ NOTE Shared research note.
0 @S1@ SOUR
1 TITL Parish register
1 AUTH Unknown
0 @M1@ OBJE
1 FILE tomas.png
2 FORM png
2 TITL Tomás at school
0 TRLR
`;

// a 1×1 picture, so the photo shows
const PNG = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==', 'base64');

export const FILE_NAME = 'family.ged';

/** Opens the family in the browser; `{id, path}`. */
export async function openFamily(page) {
  const f = openFile(GED, { name: 'family', files: { 'tomas.png': PNG } });
  await page.goto(f.pairing);
  return f;
}

export const isPhone = (info) => info.project.name === 'phone';

export const heading = (page) => page.getByRole('heading', { level: 1 });

/** Goes to a page of the open file by its address, as a bookmark or a reload would. */
export async function go(page, id, ...parts) {
  await page.goto(`/#/f/${id}/${parts.join('/')}`);
  await expect(page.locator('main [aria-busy]')).toHaveCount(0);
}

/** Opens someone's page by clicking their row in a card or list. */
export async function clickPerson(page, scope, name) {
  await scope.locator('a.person-link').filter({ has: page.locator('.person-name', { hasText: name }) }).first().click();
  await expect(heading(page)).toContainText(name);
}

/** Opens the edit form on the page shown. */
export async function edit(page) {
  await page.locator('main').getByRole('button', { name: 'Edit', exact: true }).first().click();
  const dialog = page.locator('dialog.dialog-form');
  await expect(dialog.getByRole('heading', { name: /^Edit / })).toBeVisible();
  return dialog;
}

export async function save(dialog, changes) {
  const label = changes === 1 ? 'Save 1 change' : `Save ${changes} changes`;
  await dialog.getByRole('button', { name: label }).click();
  await expect(dialog).toBeHidden();
}

/** The people list: in the sidebar on a computer, the People tab on a phone. */
export async function peopleList(page, info) {
  if (isPhone(info)) await page.locator('nav.tabbar').getByRole('link', { name: 'People' }).click();
  const list = page.locator('#people-list');
  await expect(list).toBeVisible();
  await expect(list.locator('.person-row').first()).toBeVisible();
  return list;
}

export const listRow = (list, key) => list.locator(`.person-row[data-key="${key}"]`);

/** Searches the people list; the rows shown. */
export async function search(page, info, q) {
  const list = await peopleList(page, info);
  await page.getByRole('searchbox', { name: 'Search people' }).fill(q);
  await expect(page.locator('.list-count')).toHaveText(/match/);
  return list;
}

/** From a person's page to their tree: the buttons on a computer, the Tree tab on a phone. */
export async function tree(page, info, kind) {
  if (isPhone(info)) {
    await page.locator('nav.tabbar').getByRole('link', { name: 'Tree' }).click();
    if (kind === 'descendants') await page.getByRole('navigation', { name: 'Chart' }).getByRole('link', { name: 'Descendants' }).click();
  } else {
    await page.locator('main .head-actions').getByRole('link', { name: kind === 'pedigree' ? 'Ancestors' : 'Descendants' }).click();
  }
  await expect(heading(page)).toContainText(kind === 'pedigree' ? 'Ancestors of' : 'Descendants of');
  return page.locator('main .chart-page');
}

/** A chart's box for a person, by name. */
export const chartNode = (chart, name) => chart.locator('.pnode, .dnode').filter({ has: chart.page().locator('.pnode-name', { hasText: name }) });

/** A card on the page, by its title. */
export const region = (page, name) => page.locator('main').getByRole('region', { name, exact: true });

/** A row of the life events card, by its label. */
export const eventRow = (page, label) => page.locator('main ol.timeline > li').filter({ has: page.locator('.event-label', { hasText: label }) });
