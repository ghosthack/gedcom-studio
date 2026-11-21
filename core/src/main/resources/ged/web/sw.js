// gedcom-shell service worker: keeps the app's own files so it opens
// quickly and offline. It never touches /api/: family data always comes
// fresh from the daemon (and the token never meets a cache).
const VERSION = 'gedcom-shell-v4';
const SHELL = [
  '/',
  '/index.html',
  '/app.css',
  '/manifest.webmanifest',
  '/icons/icon.svg',
  '/icons/icon-192.png',
  '/icons/icon-512.png',
  '/js/app.js',
  '/js/api.js',
  '/js/dom.js',
  '/js/ui.js',
  '/js/views/files.js',
  '/js/views/pairing.js',
  '/js/views/person.js',
  '/js/views/family.js',
  '/js/views/records.js',
  '/js/views/charts.js',
  '/js/views/forms.js',
  '/js/views/relatives.js',
];

self.addEventListener('install', (event) => {
  event.waitUntil(caches.open(VERSION).then((c) => c.addAll(SHELL)).then(() => self.skipWaiting()));
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys()
      .then((keys) => Promise.all(keys.filter((k) => k !== VERSION).map((k) => caches.delete(k))))
      .then(() => self.clients.claim()),
  );
});

self.addEventListener('fetch', (event) => {
  const req = event.request;
  if (req.method !== 'GET') return;
  const url = new URL(req.url);
  if (url.origin !== self.location.origin) return;
  if (url.pathname.startsWith('/api/')) return; // never cached
  const isPage = req.mode === 'navigate';
  const key = isPage ? '/index.html' : url.pathname;
  if (!isPage && !SHELL.includes(key)) return;
  // network first, so a new version shows at once; the cache when offline
  event.respondWith(
    fetch(req)
      .then((res) => {
        if (res.ok) {
          const copy = res.clone();
          caches.open(VERSION).then((c) => c.put(key, copy));
        }
        return res;
      })
      .catch(() => caches.match(key).then((hit) => hit || Response.error())),
  );
});
