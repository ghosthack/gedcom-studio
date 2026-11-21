// The daemon's JSON API (docs/api.md). The token travels only in the
// Authorization header; it is never put into an address.

const TOKEN_KEY = 'gedcom-shell.token';
const BASE = '/api/v1/';

export class ApiError extends Error {
  constructor(status, body, message) {
    super(message || (body && body.error) || `request failed (${status})`);
    this.status = status;
    this.body = body || {};
  }
}

let unauthorizedHandler = () => {};
export function onUnauthorized(fn) { unauthorizedHandler = fn; }

export const token = {
  get() { try { return localStorage.getItem(TOKEN_KEY) || ''; } catch { return ''; } },
  set(t) { try { localStorage.setItem(TOKEN_KEY, t); } catch { /* private mode */ } },
  clear() { try { localStorage.removeItem(TOKEN_KEY); } catch { /* private mode */ } },
};

/** Pulls a token out of a pasted pairing address (`…/#pair=TOKEN&open=ID`) or a bare token. */
export function parsePairing(text) {
  const t = (text || '').trim();
  if (!t) return null;
  const m = t.match(/[#&?]pair=([^&\s#]+)/);
  if (m) {
    const open = t.match(/[#&?]open=([0-9a-fA-F]+)/);
    return { token: decodeURIComponent(m[1]), open: open ? open[1] : null };
  }
  if (/^[A-Za-z0-9_\-.~+/=]{16,}$/.test(t)) return { token: t, open: null };
  return null;
}

function enc(key) { return encodeURIComponent(key); }

export async function request(method, path, body, { signal } = {}) {
  const headers = { Accept: 'application/json' };
  const t = token.get();
  if (t) headers.Authorization = 'Bearer ' + t;
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  let res;
  try {
    res = await fetch(BASE + path, {
      method, headers, signal, cache: 'no-store', credentials: 'omit',
      body: body === undefined ? undefined : JSON.stringify(body),
    });
  } catch (e) {
    if (e.name === 'AbortError') throw e;
    const err = new ApiError(0, null, 'Can’t reach the gedcom-shell daemon');
    err.network = true;
    throw err;
  }
  let data = null;
  const text = await res.text();
  if (text) { try { data = JSON.parse(text); } catch { data = { error: text.slice(0, 200) }; } }
  if (res.status === 401) {
    unauthorizedHandler();
    throw new ApiError(401, data);
  }
  if (!res.ok) throw new ApiError(res.status, data);
  return data;
}

const get = (p, o) => request('GET', p, undefined, o);
const post = (p, b = {}, o) => request('POST', p, b, o);

function qs(params) {
  const u = new URLSearchParams();
  for (const [k, v] of Object.entries(params)) if (v != null && v !== '') u.set(k, v);
  const s = u.toString();
  return s ? '?' + s : '';
}

export const api = {
  ping: () => get('ping'),
  daemon: () => get('daemon'),
  fields: () => get('fields'),
  files: () => get('files'),
  open: (path) => post('files', { path }),
  file: (id) => get(`files/${enc(id)}`),
  save: (id, force = false) => post(`files/${enc(id)}/save`, { force }),
  close: (id, force = false) => post(`files/${enc(id)}/close`, { force }),
  forget: (id) => post(`files/${enc(id)}/forget`),
  changes: (id, after, wait, signal) => get(`files/${enc(id)}/changes${qs({ after, wait })}`, { signal }),

  people: (id, { q, sort, offset, limit } = {}, signal) => get(`files/${enc(id)}/people${qs({ q, sort, offset, limit })}`, { signal }),
  person: (id, key) => get(`files/${enc(id)}/people/${enc(key)}`),
  pedigree: (id, key, depth) => get(`files/${enc(id)}/people/${enc(key)}/pedigree${qs({ depth })}`),
  descendants: (id, key, depth) => get(`files/${enc(id)}/people/${enc(key)}/descendants${qs({ depth })}`),
  editPerson: (id, key, body) => post(`files/${enc(id)}/people/${enc(key)}/edit`, body),
  addPerson: (id, body) => post(`files/${enc(id)}/people`, body),
  addRelative: (id, key, body) => post(`files/${enc(id)}/people/${enc(key)}/add`, body),
  link: (id, key, person, as) => post(`files/${enc(id)}/people/${enc(key)}/link`, { person, as }),
  unlink: (id, key, path) => post(`files/${enc(id)}/people/${enc(key)}/unlink`, { path }),

  families: (id, { q, offset, limit } = {}, signal) => get(`files/${enc(id)}/families${qs({ q, offset, limit })}`, { signal }),
  family: (id, key) => get(`files/${enc(id)}/families/${enc(key)}`),
  editFamily: (id, key, body) => post(`files/${enc(id)}/families/${enc(key)}/edit`, body),

  records: (id, kind, q, signal) => get(`files/${enc(id)}/records${qs({ kind, q })}`, { signal }),
  record: (id, key) => get(`files/${enc(id)}/records/${enc(key)}`),
  editRecord: (id, key, body) => post(`files/${enc(id)}/records/${enc(key)}/edit`, body),
};
