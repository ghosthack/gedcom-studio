// Browser tests of the web app, against a real daemon started from the built
// jar (core/target/gedcom-shell.jar) in a home of its own. Run `mvn package`
// first, then `npm test` here.
import { defineConfig } from '@playwright/test';

export default defineConfig({
  testDir: './tests',
  globalSetup: './tests/global-setup.js',
  // one daemon, shared: files are separate per test, the daemon is not
  workers: 1,
  timeout: 30_000,
  reporter: process.env.CI ? [['list'], ['html', { open: 'never' }]] : 'list',
  use: {
    baseURL: 'http://127.0.0.1:7435/',
    trace: 'retain-on-failure',
  },
  projects: [
    { name: 'desktop', use: { browserName: 'chromium', viewport: { width: 1280, height: 860 } } },
    { name: 'phone', use: { browserName: 'chromium', viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true } },
  ],
});
