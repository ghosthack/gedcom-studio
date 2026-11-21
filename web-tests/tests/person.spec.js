import { test, expect } from '@playwright/test';
import { open } from './daemon.js';

// Grouped by kind, as real files often are: the residences first, then a
// graduation with a type and nothing else.
const GED = `0 HEAD
1 GEDC
2 VERS 5.5.1
1 CHAR UTF-8
0 @I0@ INDI
1 NAME Tomás Iker /Fábregas/
1 SEX M
1 BIRT
2 DATE 14 MAR 1979
2 PLAC Segovia, Provincia de Castilla, Spain
1 RESI
2 DATE 1979
1 RESI
2 DATE 1981
1 RESI
2 DATE 2005
1 GRAD
2 TYPE Técnico Químico
1 BAPM
2 DATE APR 1980
1 EVEN
2 TYPE First communion
2 DATE OCT 1988
0 TRLR
`;

const years = (page) => page.locator('ol.timeline > li .event-year').allInnerTexts();

test.beforeEach(async ({ page }) => {
  const pairing = open(GED, 'tomas');
  await page.goto(pairing);
  // a phone starts on the people list: go to his page itself
  const id = new URL(pairing.replace('#', '?')).searchParams.get('open');
  await page.goto(`/#/f/${id}/people/I0`);
  await expect(page.getByRole('heading', { name: /Tomás Iker Fábregas/ })).toBeVisible();
});

test('the timeline is in date order', async ({ page }) => {
  expect(await years(page)).toEqual(['1979', '1979', '1980', '1981', '1988', '2005', '']);
  await expect(page.locator('ol.timeline > li').nth(6)).toContainText('Graduated');
});

test('an event with only a type shows in the edit form, named by its type, and can be dated', async ({ page }) => {
  await page.getByRole('button', { name: 'Edit', exact: true }).first().click();
  const dialog = page.getByRole('dialog');
  // every event the timeline shows, in its order, and nothing else
  await expect(dialog.locator('.item-event .item-title')).toHaveText(
    ['Born', 'Residence 1', 'Baptised', 'Residence 2', 'Event', 'Residence 3', 'Graduated']);
  await expect(dialog.getByRole('textbox', { name: 'Graduated type' })).toHaveValue('Técnico Químico');
  await expect(dialog.getByRole('textbox', { name: 'Event type' })).toHaveValue('First communion');

  await dialog.getByRole('textbox', { name: 'Graduated date' }).fill('dec 1997');
  await dialog.getByRole('button', { name: 'Save 1 change' }).click();

  await expect.poll(() => years(page)).toEqual(['1979', '1979', '1980', '1981', '1988', '1997', '2005']);
  await expect(page.locator('ol.timeline > li').nth(5)).toContainText('Graduated · Técnico Químico');
  await expect(page.locator('ol.timeline > li').nth(5)).toContainText('Dec 1997');
});
