// A family's events and notes, edited in its form, looked for on the family
// page, the families list, both partners' pages and the descendants chart.
import { test, expect } from '@playwright/test';
import { openFamily, heading, go, edit, save, tree, region, eventRow } from './family.js';

let id;
test.beforeEach(async ({ page }) => {
  ({ id } = await openFamily(page));
  await go(page, id, 'families', 'F2');
  await expect(heading(page)).toHaveText('Tomás Iker Fábregas & Ana Pérez');
});

const famFact = (page) => page.locator('main .card', { has: page.locator('.card-title', { hasText: /^Family with/ }) }).locator('.fam-fact');

test('a marriage’s date and place: the family page and list, both partners’ pages, the descendants chart', async ({ page }, info) => {
  test.setTimeout(45_000);
  await expect(page.locator('main .page-sub')).toHaveText('Married 2010 · 1 child');
  let d = await edit(page);
  await d.getByRole('textbox', { name: 'Married date' }).fill('12 jun 2010');
  await d.getByRole('textbox', { name: 'Married place' }).fill('Segovia');
  await save(d, 2);

  await expect(page.locator('main .page-sub')).toHaveText('Married 12 Jun 2010 in Segovia · 1 child');
  const marr = eventRow(page, 'Married');
  await expect(marr.locator('.event-date')).toHaveText('12 Jun 2010');
  await expect(marr.locator('.event-place')).toHaveText('Segovia');

  d = await edit(page);
  await expect(d.getByRole('textbox', { name: 'Married date' })).toHaveValue('12 JUN 2010');
  await expect(d.getByRole('textbox', { name: 'Married place' })).toHaveValue('Segovia');
  await d.getByRole('button', { name: 'Cancel' }).click();

  await go(page, id, 'families');
  await expect(page.locator('main .list-row', { hasText: 'Ana Pérez' }).locator('.list-meta')).toHaveText('Married 2010 · 1 child');

  for (const key of ['I0', 'I3']) {
    await go(page, id, 'people', key);
    await expect(famFact(page)).toHaveText('Married 12 Jun 2010 · Segovia');
  }
  await go(page, id, 'people', 'I1');
  const chart = await tree(page, info, 'descendants');
  await expect(chart.locator('.dfam', { hasText: 'Ana Pérez' }).locator('.dfam-married').first()).toHaveText('Married 12 Jun 2010');
});

test('a marriage removed', async ({ page }) => {
  const d = await edit(page);
  await d.getByRole('button', { name: 'Remove Married' }).click();
  await save(d, 1);
  await expect(page.locator('main .page-sub')).toHaveText('No marriage recorded · 1 child');
  await expect(region(page, 'Events')).toContainText('No events recorded.');
  await go(page, id, 'people', 'I0');
  await expect(famFact(page)).toHaveCount(0);
  await go(page, id, 'families');
  await expect(page.locator('main .list-row', { hasText: 'Ana Pérez' }).locator('.list-meta')).toHaveText('No marriage date · 1 child');
});

test('a divorce added: the chip, and the partners become former ones', async ({ page }) => {
  const d = await edit(page);
  await d.getByRole('combobox', { name: 'Add an event' }).selectOption('DIV');
  await d.getByRole('textbox', { name: 'Divorced date' }).fill('2015');
  await save(d, 1);
  await expect(page.locator('main .family-head .chip-warn')).toHaveText('Divorced');
  await expect(page.locator('main ol.timeline > li .event-label')).toHaveText(['Married', 'Divorced']);
  await go(page, id, 'people', 'I0');
  const card = region(page, 'Family with Ana');
  await expect(card.locator('.person-row', { hasText: 'Ana' }).locator('.role')).toHaveText('Former wife');
  await expect(card.locator('.chip-warn')).toHaveText('Divorced');
  await go(page, id, 'people', 'I3');
  await expect(region(page, 'Family with Tomás Iker').locator('.person-row', { hasText: 'Tomás' }).locator('.role')).toHaveText('Former husband');
});

test('a family’s notes', async ({ page }) => {
  await region(page, 'Notes').getByRole('button', { name: 'Add a note' }).click();
  let d = page.locator('dialog.dialog-form');
  await d.getByRole('button', { name: 'Add note' }).click();
  await d.getByRole('textbox', { name: 'Note', exact: true }).fill('Civil wedding,\nthen a party.');
  await save(d, 1);
  await expect(region(page, 'Notes').locator('.note-text')).toHaveText(['Civil wedding,\nthen a party.']);
  d = await edit(page);
  await expect(d.getByRole('textbox', { name: 'Note', exact: true })).toHaveValue('Civil wedding,\nthen a party.');
  await d.getByRole('textbox', { name: 'Note', exact: true }).fill('Civil wedding.');
  await save(d, 1);
  await expect(region(page, 'Notes').locator('.note-text')).toHaveText(['Civil wedding.']);
});
