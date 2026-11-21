// Each thing a person's form edits, made through the form, then looked for
// everywhere the web app shows it: their page, the people list and search,
// their relatives' pages, the family pages, the charts and the records that
// point at them. Then the form opened again, which must show the new values.
import { test, expect } from '@playwright/test';
import { api } from './daemon.js';
import {
  openFamily, FILE_NAME, isPhone, heading, go, clickPerson, edit, save, peopleList, listRow, search, tree,
  chartNode, region, eventRow,
} from './family.js';

let id;
test.beforeEach(async ({ page }) => {
  ({ id } = await openFamily(page));
  await go(page, id, 'people', 'I0');
  await expect(heading(page)).toHaveText('Tomás Iker Fábregas');
});

test.describe('a person’s name', () => {
  test.setTimeout(60_000);

  test('shows on their page, in the list and search, on relatives’ pages, family pages, charts and records', async ({ page }, info) => {
    const NEW = 'Tomás Fábregas Ruiz';
    let d = await edit(page);
    await d.getByRole('textbox', { name: 'Given names', exact: true }).fill('Tomás');
    await d.getByRole('textbox', { name: 'Surname', exact: true }).fill('Fábregas Ruiz');
    await save(d, 2);

    await test.step('their page and the window title', async () => {
      await expect(heading(page)).toHaveText(NEW);
      await expect(page).toHaveTitle(`${NEW} · ${FILE_NAME}`);
      await expect(page.locator('.topbar .page-title')).toHaveText(NEW);
    });

    await test.step('the form, opened again', async () => {
      d = await edit(page);
      await expect(d.getByRole('heading', { name: `Edit ${NEW}` })).toBeVisible();
      await expect(d.getByRole('textbox', { name: 'Given names', exact: true })).toHaveValue('Tomás');
      await expect(d.getByRole('textbox', { name: 'Surname', exact: true })).toHaveValue('Fábregas Ruiz');
      await d.getByRole('button', { name: 'Cancel' }).click();
      await expect(d).toBeHidden();
    });

    await test.step('the people list and search', async () => {
      const list = await peopleList(page, info);
      await expect(listRow(list, 'I0').locator('.person-name')).toHaveText(NEW);
      await search(page, info, 'ruiz');
      await expect(list.locator('.person-row')).toHaveCount(1);
      await expect(listRow(list, 'I0').locator('.person-name')).toHaveText(NEW);
      await page.getByRole('searchbox', { name: 'Search people' }).fill('');
    });

    await test.step('his father’s page: a son in the family card', async () => {
      await go(page, id, 'people', 'I1');
      const fam = region(page, 'Family with Rosa');
      await expect(fam.locator('.person-row', { hasText: 'Son' }).locator('.person-name')).toHaveText(NEW);
    });

    await test.step('his wife’s page: the husband, and the card named by his given name', async () => {
      await go(page, id, 'people', 'I3');
      const fam = region(page, 'Family with Tomás');
      await expect(fam.locator('.person-row', { hasText: 'Husband' }).locator('.person-name')).toHaveText(NEW);
    });

    await test.step('his daughter’s page: the father', async () => {
      await go(page, id, 'people', 'I4');
      await expect(region(page, 'Parents').locator('.person-row', { hasText: 'Father' }).locator('.person-name')).toHaveText(NEW);
    });

    await test.step('the family pages and the families list', async () => {
      await go(page, id, 'families', 'F2');
      await expect(heading(page)).toHaveText(`${NEW} & Ana Pérez`);
      await expect(page).toHaveTitle(`${NEW} & Ana Pérez · ${FILE_NAME}`);
      await expect(region(page, 'Partners').locator('.person-row', { hasText: 'Husband' }).locator('.person-name')).toHaveText(NEW);
      await go(page, id, 'families', 'F1');
      await expect(region(page, 'Children · 1').locator('.person-name')).toHaveText(NEW);
      await go(page, id, 'families');
      await expect(page.locator('main .list-title').filter({ hasText: 'Ana Pérez' })).toHaveText(`${NEW} & Ana Pérez`);
    });

    await test.step('the charts: his daughter’s ancestors, his father’s descendants', async () => {
      await go(page, id, 'people', 'I4');
      let chart = await tree(page, info, 'pedigree');
      await expect(chartNode(chart, 'Tomás')).toHaveCount(1);
      await expect(chartNode(chart, 'Tomás').locator('.pnode-name')).toHaveText(NEW);
      await go(page, id, 'people', 'I1');
      chart = await tree(page, info, 'descendants');
      await expect(chartNode(chart, 'Tomás').locator('.pnode-name')).toHaveText(NEW);
      // and his own: the title names him; his wife is named in her family row
      await go(page, id, 'people', 'I0');
      chart = await tree(page, info, 'pedigree');
      await expect(heading(page)).toHaveText(`Ancestors of ${NEW}`);
      await expect(page).toHaveTitle(`Ancestors of ${NEW} · ${FILE_NAME}`);
    });

    await test.step('the records he uses: “used by”', async () => {
      for (const key of ['S1', 'N1', 'M1']) {
        await go(page, id, 'records', key);
        await expect(page.locator('main').getByRole('region', { name: /^Used by/ }).locator('.person-name').first()).toHaveText(NEW);
      }
    });
  });

  test('a suffix and initials', async ({ page }) => {
    // Ana has no picture: her initials show
    await go(page, id, 'people', 'I3');
    await expect(page.locator('.portrait .avatar')).toHaveText('AP');
    const d = await edit(page);
    await d.getByRole('textbox', { name: 'Surname', exact: true }).fill('Ruiz');
    await d.getByRole('textbox', { name: 'Suffix', exact: true }).fill('Jr.');
    await save(d, 2);
    await expect(heading(page)).toHaveText('Ana Ruiz Jr.');
    await expect(page.locator('.portrait .avatar')).toHaveText('AR');
    await go(page, id, 'people', 'I0');
    await expect(region(page, 'Family with Ana').locator('.person-row', { hasText: 'Wife' }).locator('.avatar')).toHaveText('AR');
    expect((await api(`files/${id}/people/I3`)).fields.suffix).toBe('Jr.');
  });
});

test.describe('a person’s sex', () => {
  test('changes the chip, their roles on relatives’ pages, and the charts', async ({ page }, info) => {
    test.setTimeout(45_000);
    await go(page, id, 'people', 'I4');
    let d = await edit(page);
    await expect(d.getByRole('combobox', { name: 'Sex' })).toHaveValue('F');
    await d.getByRole('combobox', { name: 'Sex' }).selectOption('M');
    await save(d, 1);
    await expect(page.locator('.person-head .chip-sex-M')).toHaveText('Male');

    d = await edit(page);
    await expect(d.getByRole('combobox', { name: 'Sex' })).toHaveValue('M');
    await d.getByRole('button', { name: 'Cancel' }).click();

    await clickPerson(page, region(page, 'Parents'), 'Tomás');
    const fam = region(page, 'Family with Ana');
    await expect(fam.locator('.person-row', { hasText: 'Lucía' }).locator('.role')).toHaveText('Son');
    await expect(fam.locator('.person-row', { hasText: 'Lucía' }).locator('.avatar')).toHaveClass(/sex-M/);
    await go(page, id, 'families', 'F2');
    await expect(region(page, 'Children · 1').locator('.role')).toHaveText('Son');
    await go(page, id, 'people', 'I0');
    const chart = await tree(page, info, 'descendants');
    await expect(chartNode(chart, 'Lucía')).toHaveClass(/sex-M/);
  });

  test('a wife made a man is a husband on her partner’s page', async ({ page }) => {
    await go(page, id, 'people', 'I3');
    const d = await edit(page);
    await d.getByRole('combobox', { name: 'Sex' }).selectOption('U');
    await save(d, 1);
    await expect(page.locator('.person-head .chip-sex-U')).toHaveText('Unknown');
    await go(page, id, 'people', 'I0');
    await expect(region(page, 'Family with Ana').locator('.person-row', { hasText: 'Ana' }).locator('.role')).toHaveText('Partner');
  });
});

test.describe('birth and death', () => {
  test.setTimeout(60_000);

  test('a birth’s date and place: the heading, the timeline, the list, relatives and charts', async ({ page }, info) => {
    let d = await edit(page);
    await d.getByRole('textbox', { name: 'Born date' }).fill('abt 1978');
    await d.getByRole('textbox', { name: 'Born place' }).fill('Cuenca, Spain');
    await save(d, 2);

    await expect(page.locator('.head-lifespan')).toHaveText('b. c. 1978');
    await expect(page.locator('.head-life')).toHaveText('Born about 1978 in Cuenca, Spain');
    const born = eventRow(page, 'Born');
    await expect(born.locator('.event-year')).toHaveText('1978');
    await expect(born.locator('.event-date')).toHaveText('about 1978');
    await expect(born.locator('.event-place')).toHaveText('Cuenca, Spain');

    d = await edit(page);
    await expect(d.getByRole('textbox', { name: 'Born date' })).toHaveValue('ABT 1978');
    await expect(d.getByRole('textbox', { name: 'Born place' })).toHaveValue('Cuenca, Spain');
    await d.getByRole('button', { name: 'Cancel' }).click();

    const list = await peopleList(page, info);
    await expect(listRow(list, 'I0').locator('.lifespan')).toHaveText('b. c. 1978');
    // sorted by birth, he still comes after his father and before his wife
    await page.getByRole('combobox', { name: 'Sort people' }).selectOption('born');
    await expect.poll(() => list.locator('.person-row').evaluateAll((rows) => rows.map((r) => r.dataset.key)))
      .toEqual(['I1', 'I0', 'I3', 'I4', 'I2']);
    await page.getByRole('combobox', { name: 'Sort people' }).selectOption('name');

    await go(page, id, 'people', 'I1');
    await expect(region(page, 'Family with Rosa').locator('.person-row', { hasText: 'Tomás' }).locator('.lifespan')).toHaveText('b. c. 1978');
    await go(page, id, 'people', 'I3');
    await expect(region(page, 'Family with Tomás Iker').locator('.person-row', { hasText: 'Tomás' }).locator('.lifespan')).toHaveText('b. c. 1978');
    await go(page, id, 'families', 'F2');
    await expect(region(page, 'Partners').locator('.person-row', { hasText: 'Tomás' }).locator('.lifespan')).toHaveText('b. c. 1978');
    await go(page, id, 'people', 'I4');
    const chart = await tree(page, info, 'pedigree');
    await expect(chartNode(chart, 'Tomás').locator('.pnode-life')).toHaveText('b. c. 1978');
    await go(page, id, 'records', 'S1');
    await expect(page.locator('main').getByRole('region', { name: /^Used by/ }).locator('.lifespan')).toHaveText('b. c. 1978');
  });

  test('a death added: the lifespan, the heading, the timeline, relatives and charts', async ({ page }, info) => {
    let d = await edit(page);
    await d.getByRole('combobox', { name: 'Add an event' }).selectOption('DEAT');
    await d.getByRole('textbox', { name: 'Died date' }).fill('3 mar 2020');
    await d.getByRole('textbox', { name: 'Died place' }).fill('Castilla');
    await save(d, 1);

    await expect(page.locator('.head-lifespan')).toHaveText('1979–2020');
    await expect(page.locator('.head-life')).toContainText('Died 3 Mar 2020 in Castilla');
    const died = eventRow(page, 'Died');
    await expect(died.locator('.event-year')).toHaveText('2020');
    await expect(died.locator('.event-date')).toHaveText('3 Mar 2020');
    await expect(died.locator('.event-place')).toHaveText('Castilla');
    // last in the timeline: after the undated occupation that follows the residence
    await expect(page.locator('main ol.timeline > li').last()).toContainText('Died');

    d = await edit(page);
    await expect(d.getByRole('textbox', { name: 'Died date' })).toHaveValue('3 MAR 2020');
    // a person has one death: it's no longer offered
    await expect(d.getByRole('combobox', { name: 'Add an event' }).locator('option[value=DEAT]')).toHaveCount(0);
    await d.getByRole('button', { name: 'Cancel' }).click();

    const list = await peopleList(page, info);
    await expect(listRow(list, 'I0').locator('.lifespan')).toHaveText('1979–2020');
    await go(page, id, 'people', 'I4');
    await expect(region(page, 'Parents').locator('.person-row', { hasText: 'Tomás' }).locator('.lifespan')).toHaveText('1979–2020');
    const chart = await tree(page, info, 'pedigree');
    await expect(chartNode(chart, 'Tomás').locator('.pnode-life')).toHaveText('1979–2020');
    await go(page, id, 'people', 'I3');
    let desc = await tree(page, info, 'descendants');
    await expect(desc.locator('.dfam-partner')).toContainText('1979–2020');
    await go(page, id, 'people', 'I1');
    desc = await tree(page, info, 'descendants');
    await expect(chartNode(desc, 'Tomás').locator('.pnode-life')).toHaveText('1979–2020');
  });

  test('a death with no date or place still says so', async ({ page }) => {
    await go(page, id, 'people', 'I2');
    const d = await edit(page);
    await d.getByRole('combobox', { name: 'Add an event' }).selectOption('DEAT');
    await save(d, 1);
    await expect(page.locator('.head-lifespan')).toHaveText('†');
    await expect(page.locator('.head-life')).toHaveText('Deceased');
    const died = eventRow(page, 'Died');
    await expect(died).toHaveCount(1);
    // the file's `DEAT Y` is a marker, not something to show
    await expect(died.locator('.event-value')).toHaveCount(0);
    await expect(died).not.toContainText('Y');
  });

  test('a death removed', async ({ page }, info) => {
    await go(page, id, 'people', 'I1');
    await expect(page.locator('.head-lifespan')).toHaveText('1950–2010');
    const d = await edit(page);
    await d.getByRole('button', { name: 'Remove Died' }).click();
    await save(d, 1);
    await expect(page.locator('.head-lifespan')).toHaveText('b. 1950');
    await expect(eventRow(page, 'Died')).toHaveCount(0);
    await expect(page.locator('.head-life')).not.toContainText('Died');
    await clickPerson(page, region(page, 'Family with Rosa'), 'Tomás');
    await expect(region(page, 'Parents').locator('.person-row', { hasText: 'Juan' }).locator('.lifespan')).toHaveText('b. 1950');
    const chart = await tree(page, info, 'pedigree');
    await expect(chartNode(chart, 'Juan').locator('.pnode-life')).toHaveText('b. 1950');
  });
});

test.describe('events', () => {
  test('each part of an event shows in its timeline row, which moves to its date', async ({ page }) => {
    const rows = page.locator('main ol.timeline > li .event-label');
    await expect(rows).toHaveText(['Born', 'Residence', 'Occupation']);
    let d = await edit(page);
    await d.getByRole('button', { name: 'Add a type to Residence' }).click();
    await d.getByRole('textbox', { name: 'Residence type' }).fill('Family home');
    await d.getByRole('textbox', { name: 'Residence date' }).fill('from 1975 to 1999');
    await d.getByRole('textbox', { name: 'Residence place' }).fill('Serrano 101, Segovia');
    await d.getByRole('textbox', { name: 'Occupation value' }).fill('Chemical engineer');
    await d.getByRole('textbox', { name: 'Occupation date' }).fill('2005');
    await save(d, 5);

    // the residence now starts before the birth; the occupation is dated
    await expect(rows).toHaveText(['Residence · Family home', 'Born', 'Occupation']);
    const resi = eventRow(page, 'Residence');
    await expect(resi.locator('.event-year')).toHaveText('1975');
    await expect(resi.locator('.event-date')).toHaveText('from 1975 to 1999');
    await expect(resi.locator('.event-place')).toHaveText('Serrano 101, Segovia');
    const occu = eventRow(page, 'Occupation');
    await expect(occu.locator('.event-value')).toHaveText('Chemical engineer');
    await expect(occu.locator('.event-year')).toHaveText('2005');

    d = await edit(page);
    // the form lists them as the page does
    await expect(d.locator('.item-event .item-title')).toHaveText(['Residence', 'Born', 'Occupation']);
    await expect(d.getByRole('textbox', { name: 'Residence type' })).toHaveValue('Family home');
    await expect(d.getByRole('textbox', { name: 'Residence date' })).toHaveValue('FROM 1975 TO 1999');
    await expect(d.getByRole('textbox', { name: 'Residence place' })).toHaveValue('Serrano 101, Segovia');
    await expect(d.getByRole('textbox', { name: 'Occupation value' })).toHaveValue('Chemical engineer');
    await expect(d.getByRole('textbox', { name: 'Occupation date' })).toHaveValue('2005');

    // a type cleared, a place cleared: gone from the row
    await d.getByRole('textbox', { name: 'Residence type' }).fill('');
    await d.getByRole('textbox', { name: 'Residence place' }).fill('');
    await save(d, 2);
    await expect(rows).toHaveText(['Residence', 'Born', 'Occupation']);
    await expect(eventRow(page, 'Residence').locator('.event-place')).toHaveCount(0);
  });

  test('an event added with every part', async ({ page }) => {
    const d = await edit(page);
    await d.getByRole('combobox', { name: 'Add an event' }).selectOption('EMIG');
    await d.getByRole('button', { name: 'Add a type to Emigrated' }).click();
    await d.getByRole('textbox', { name: 'Emigrated type' }).fill('To Spain');
    await d.getByRole('textbox', { name: 'Emigrated date' }).fill('bef 2002');
    await d.getByRole('textbox', { name: 'Emigrated place' }).fill('Ezeiza');
    await save(d, 1);
    const emig = eventRow(page, 'Emigrated');
    await expect(emig.locator('.event-label')).toHaveText('Emigrated · To Spain');
    await expect(emig.locator('.event-date')).toHaveText('before 2002');
    await expect(emig.locator('.event-place')).toHaveText('Ezeiza');
    await expect(page.locator('main ol.timeline > li .event-label')).toHaveText(['Born', 'Residence', 'Occupation', 'Emigrated · To Spain']);
  });

  test('a bad date is refused at the field, and nothing changes', async ({ page }) => {
    const d = await edit(page);
    await d.getByRole('textbox', { name: 'Residence date' }).fill('sometime');
    await d.getByRole('textbox', { name: 'Residence place' }).fill('Somewhere');
    await d.getByRole('button', { name: 'Save 2 changes' }).click();
    await expect(d.getByRole('alert')).toContainText('Not saved');
    await expect(d.getByRole('textbox', { name: 'Residence date' })).toHaveAttribute('aria-invalid', 'true');
    await d.getByRole('textbox', { name: 'Residence date' }).fill('1991');
    await save(d, 2);
    await expect(eventRow(page, 'Residence').locator('.event-year')).toHaveText('1991');
    await expect(eventRow(page, 'Residence').locator('.event-place')).toHaveText('Somewhere');
  });
});

test.describe('other names and nicknames', () => {
  test('show by the heading, and find the person in search', async ({ page }, info) => {
    const aka = page.locator('.person-head .aka');
    await expect(aka).toContainText('Iker Fábregas');
    await expect(aka).toContainText('“Adri”');
    let d = await edit(page);
    await d.getByRole('textbox', { name: 'Other name surname' }).fill('Ruiz');
    await d.getByRole('combobox', { name: 'Other name type' }).fill('married');
    await d.getByRole('textbox', { name: 'Nickname' }).fill('Fercho');
    await save(d, 3);

    await expect(aka.locator('.aka-item')).toHaveText(['Iker Ruizmarried', '“Fercho”']);
    await expect(aka.locator('.aka-type')).toHaveText('married');
    await expect(aka).not.toContainText('Adri”');

    d = await edit(page);
    await expect(d.getByRole('textbox', { name: 'Other name surname' })).toHaveValue('Ruiz');
    await expect(d.getByRole('combobox', { name: 'Other name type' })).toHaveValue('married');
    await expect(d.getByRole('textbox', { name: 'Nickname' })).toHaveValue('Fercho');
    // removing them all
    await d.getByRole('button', { name: 'Remove Other name' }).click();
    await d.getByRole('button', { name: 'Remove Nickname' }).click();
    await save(d, 2);
    await expect(aka).toHaveCount(0);

    // added back, then searched for
    d = await edit(page);
    await d.getByRole('button', { name: 'Add nickname' }).click();
    await d.getByRole('textbox', { name: 'Nickname' }).fill('Pocho');
    await save(d, 1);
    const list = await search(page, info, 'pocho');
    await expect(listRow(list, 'I0').locator('.person-note')).toHaveText('“Pocho”');
  });
});

test.describe('notes', () => {
  test('a note of their own shows in the Notes card; a removed one goes', async ({ page }) => {
    const notes = region(page, 'Notes');
    await expect(notes.locator('.note-text')).toHaveText(['Likes trains.', 'Shared research note.']);
    let d = await edit(page);
    await d.getByRole('textbox', { name: 'Note', exact: true }).fill('Likes trains,\n\nand boats.');
    await save(d, 1);
    await expect(notes.locator('.note-text').first()).toHaveText('Likes trains,\n\nand boats.');
    d = await edit(page);
    await expect(d.getByRole('textbox', { name: 'Note', exact: true })).toHaveValue('Likes trains,\n\nand boats.');
    await d.getByRole('button', { name: 'Remove Note', exact: true }).click();
    await save(d, 1);
    await expect(notes.locator('.note-text')).toHaveText(['Shared research note.']);
  });

  test('a note added to someone without notes', async ({ page }) => {
    await go(page, id, 'people', 'I4');
    await region(page, 'Notes').getByRole('button', { name: 'Add a note' }).click();
    const d = page.locator('dialog.dialog-form');
    await d.getByRole('button', { name: 'Add note' }).click();
    await d.getByRole('textbox', { name: 'Note', exact: true }).fill('Born at home.');
    await save(d, 1);
    await expect(region(page, 'Notes').locator('.note-text')).toHaveText(['Born at home.']);
  });

  test('a shared note unlinked from one person stays with the other', async ({ page }) => {
    const d = await edit(page);
    await d.getByRole('button', { name: 'Remove Shared note' }).click();
    await save(d, 1);
    await expect(region(page, 'Notes').locator('.note-text')).toHaveText(['Likes trains.']);
    await expect(region(page, 'Sources & media')).not.toContainText('Shared research note.');
    await go(page, id, 'records', 'N1');
    await expect(page.locator('main').getByRole('region', { name: 'Used by · 1' }).locator('.person-name')).toHaveText('Ana Pérez');
  });
});
