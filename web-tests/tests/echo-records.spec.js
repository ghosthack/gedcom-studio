// Sources, notes and media, edited on their pages, looked for on their own
// page, in the lists of records, and on the pages of the people using them.
import { test, expect } from '@playwright/test';
import { openFamily, FILE_NAME, heading, go, edit, save, region } from './family.js';

let id;
test.beforeEach(async ({ page }) => {
  ({ id } = await openFamily(page));
});

const recordLink = (page, key) => page.locator(`main a.record-link[href$="/records/${key}"] .record-title`);
const listTitle = (page, key) => page.locator('main .list-row', { has: page.locator('.chip', { hasText: new RegExp(`^${key}$`) }) }).locator('.list-title');

test('a source’s title and lines: its page, the sources list and filter, the person’s “Sources & media”', async ({ page }) => {
  await go(page, id, 'records', 'S1');
  let d = await edit(page);
  await d.getByRole('textbox', { name: 'Title' }).fill('Parish register of Segovia');
  await d.getByRole('textbox', { name: 'Author' }).fill('Father Gómez');
  await save(d, 2);

  await expect(heading(page)).toHaveText('Parish register of Segovia');
  await expect(page).toHaveTitle(`Parish register of Segovia · ${FILE_NAME}`);
  const details = region(page, 'Details');
  await expect(details.locator('.field-row', { hasText: 'TITL' }).locator('.field-value')).toHaveText('Parish register of Segovia');
  await expect(details.locator('.field-row', { hasText: 'AUTH' }).locator('.field-value')).toHaveText('Father Gómez');

  d = await edit(page);
  await expect(d.getByRole('textbox', { name: 'Title' })).toHaveValue('Parish register of Segovia');
  await expect(d.getByRole('textbox', { name: 'Author' })).toHaveValue('Father Gómez');
  await d.getByRole('button', { name: 'Cancel' }).click();

  await page.locator('main .page-sub').getByRole('link', { name: 'All sources' }).click();
  await expect(listTitle(page, 'S1')).toHaveText('Parish register of Segovia');
  await page.getByRole('searchbox', { name: 'Filter sources' }).fill('segovia');
  await expect(page.locator('main .list-row')).toHaveCount(1);
  await page.getByRole('searchbox', { name: 'Filter sources' }).fill('');

  await go(page, id, 'people', 'I0');
  await expect(recordLink(page, 'S1')).toHaveText('Parish register of Segovia');
});

test('a shared note’s text: its page, the notes list, and the Notes of everyone who uses it', async ({ page }) => {
  await go(page, id, 'records', 'N1');
  let d = await edit(page);
  await d.getByRole('textbox', { name: 'Note text' }).fill('Research by Rosa.\nSee the parish.');
  await save(d, 1);

  await expect(region(page, 'Text').locator('.note-text')).toHaveText('Research by Rosa.\nSee the parish.');
  await expect(heading(page)).toHaveText(/^Research by Rosa\./);
  d = await edit(page);
  await expect(d.getByRole('textbox', { name: 'Note text' })).toHaveValue('Research by Rosa.\nSee the parish.');
  await d.getByRole('button', { name: 'Cancel' }).click();

  await go(page, id, 'notes');
  await expect(listTitle(page, 'N1')).toHaveText(/^Research by Rosa\./);

  await go(page, id, 'people', 'I0');
  await expect(region(page, 'Notes').locator('.note-text')).toHaveText(['Likes trains.', 'Research by Rosa.\nSee the parish.']);
  await expect(recordLink(page, 'N1')).toHaveText(/^Research by Rosa\./);
  // in his form, read-only, with the new text
  d = await edit(page);
  await expect(d.locator('.item-note.is-shared .shared-text')).toHaveText('Research by Rosa.\nSee the parish.');
  await d.getByRole('button', { name: 'Cancel' }).click();
  await go(page, id, 'people', 'I3');
  await expect(region(page, 'Notes').locator('.note-text')).toHaveText(['Research by Rosa.\nSee the parish.']);
});

test('a picture’s title: its page, the media list, the person’s pictures and the viewer', async ({ page }) => {
  await go(page, id, 'records', 'M1');
  let d = await edit(page);
  await d.getByRole('textbox', { name: 'Title' }).fill('Tomás, first day of school');
  await save(d, 1);

  await expect(heading(page)).toHaveText('Tomás, first day of school');
  await expect(page.locator('main .preview-img')).toHaveAttribute('alt', 'Tomás, first day of school');
  d = await edit(page);
  await expect(d.getByRole('textbox', { name: 'Title' })).toHaveValue('Tomás, first day of school');
  await d.getByRole('button', { name: 'Cancel' }).click();

  await go(page, id, 'media');
  await expect(listTitle(page, 'M1')).toHaveText('Tomás, first day of school');

  await go(page, id, 'people', 'I0');
  await expect(recordLink(page, 'M1')).toHaveText('Tomás, first day of school');
  const pictures = region(page, 'Pictures');
  await expect(pictures.locator('.thumb-title')).toHaveText('Tomás, first day of school');
  await pictures.getByRole('button', { name: 'Show Tomás, first day of school' }).click();
  await expect(page.locator('dialog.lightbox .lightbox-caption strong')).toHaveText('Tomás, first day of school');
});
