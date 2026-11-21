// Building the page safely: every piece of GEDCOM text goes in through
// textContent or an attribute, never through innerHTML.

const SVG_NS = 'http://www.w3.org/2000/svg';

/**
 * h('a.button.primary', {href, onclick, aria: {label}}, 'text', child, [more])
 * Strings become text nodes; null/false/undefined children are skipped.
 */
export function h(spec, attrs, ...children) {
  if (attrs == null || typeof attrs !== 'object' || attrs instanceof Node || Array.isArray(attrs)) {
    if (attrs != null && attrs !== false) children.unshift(attrs);
    attrs = {};
  }
  const [tag, ...classes] = spec.split('.');
  const el = document.createElement(tag || 'div');
  if (classes.length) el.className = classes.join(' ');
  applyAttrs(el, attrs);
  append(el, children);
  return el;
}

/** An SVG element; attributes are set as presentation attributes. */
export function s(tag, attrs = {}, ...children) {
  const el = document.createElementNS(SVG_NS, tag);
  for (const [k, v] of Object.entries(attrs)) {
    if (v == null || v === false) continue;
    if (k === 'class') el.setAttribute('class', v);
    else if (k.startsWith('on') && typeof v === 'function') el.addEventListener(k.slice(2), v);
    else el.setAttribute(k, String(v));
  }
  append(el, children);
  return el;
}

function applyAttrs(el, attrs) {
  for (const [k, v] of Object.entries(attrs)) {
    if (v == null || v === false) continue;
    if (k === 'class') el.className += (el.className ? ' ' : '') + v;
    else if (k === 'text') el.textContent = v;
    else if (k === 'dataset') Object.assign(el.dataset, v);
    else if (k === 'aria') for (const [a, av] of Object.entries(v)) { if (av != null) el.setAttribute('aria-' + a, String(av)); }
    else if (k.startsWith('on') && typeof v === 'function') el.addEventListener(k.slice(2), v);
    else if (k === 'value' || k === 'checked' || k === 'disabled' || k === 'selected' || k === 'hidden') el[k] = v;
    else if (k === 'style') throw new Error('no inline styles (CSP); use a class or el.style');
    else el.setAttribute(k, v === true ? '' : String(v));
  }
}

export function append(el, children) {
  for (const c of children.flat(Infinity)) {
    if (c == null || c === false || c === true) continue;
    el.append(c instanceof Node ? c : document.createTextNode(String(c)));
  }
  return el;
}

export function clear(el) {
  while (el.firstChild) el.firstChild.remove();
  return el;
}

export function replace(el, ...children) {
  clear(el);
  return append(el, children);
}

export function debounce(fn, ms) {
  let t;
  const d = (...args) => { clearTimeout(t); t = setTimeout(() => fn(...args), ms); };
  d.cancel = () => clearTimeout(t);
  return d;
}

// ---- icons ------------------------------------------------------------------

const ICONS = {
  search: ['c:11,11,7', 'M20 20l-3.6-3.6'],
  people: ['c:9,7.5,3.5', 'M2.5 20v-.5A4.5 4.5 0 0 1 7 15h4a4.5 4.5 0 0 1 4.5 4.5v.5', 'M16 4.2a3.5 3.5 0 0 1 0 6.6', 'M18.5 15.2a4.5 4.5 0 0 1 3 4.3v.5'],
  person: ['c:12,8,4', 'M4.5 20.5a7.5 7.5 0 0 1 15 0'],
  family: ['c:8.5,12,5', 'c:15.5,12,5'],
  note: ['M6 3.5h8.5L19 8v12.5H6z', 'M14 3.5V8.5h5', 'M9 13h7', 'M9 16.5h5'],
  source: ['M5 4.5A1.5 1.5 0 0 1 6.5 3H19v14.5H6.5A1.5 1.5 0 0 0 5 19z', 'M5 19a1.5 1.5 0 0 0 1.5 1.5H19', 'M9 7.5h6'],
  media: ['M4.5 4.5h15a1 1 0 0 1 1 1v13a1 1 0 0 1-1 1h-15a1 1 0 0 1-1-1v-13a1 1 0 0 1 1-1z', 'c:9,9.5,1.8', 'M20.5 15.5l-5-5L5 19.5'],
  warning: ['M12 3.5l9.5 16.5h-19z', 'M12 10v4.5', 'M12 17.2v.3'],
  save: ['M5 3.5h11l3.5 3.5v13.5H5z', 'M8.5 3.5v4.5h6.5V3.5', 'M8 20.5v-6.5h8.5v6.5'],
  edit: ['M4 20h4L19.5 8.5l-4-4L4 16z', 'M13.5 6.5l4 4'],
  plus: ['M12 5v14', 'M5 12h14'],
  link: ['M10 14a4 4 0 0 0 5.7 0l3-3a4 4 0 0 0-5.7-5.7l-1.2 1.2', 'M14 10a4 4 0 0 0-5.7 0l-3 3a4 4 0 0 0 5.7 5.7l1.2-1.2'],
  unlink: ['M9.5 6.5l1.5-1.5a4 4 0 0 1 5.7 5.7l-1.5 1.5', 'M14.5 17.5L13 19a4 4 0 0 1-5.7-5.7l1.5-1.5', 'M4 4l16 16'],
  pedigree: ['M3 10h5v4H3z', 'M16 3.5h5v4h-5z', 'M16 16.5h5v4h-5z', 'M8 12h4', 'M12 5.5v13', 'M12 5.5h4', 'M12 18.5h4'],
  descendants: ['M9.5 3h5v4h-5z', 'M3 17h5v4H3z', 'M16 17h5v4h-5z', 'M12 7v5', 'M5.5 12h13', 'M5.5 12v5', 'M18.5 12v5'],
  chevronLeft: ['M15 18l-6-6 6-6'],
  chevronRight: ['M9 6l6 6-6 6'],
  chevronDown: ['M6 9l6 6 6-6'],
  close: ['M6 6l12 12', 'M18 6L6 18'],
  trash: ['M4.5 6.5h15', 'M9.5 6.5V4.5h5v2', 'M6.5 6.5l.9 13h9.2l.9-13', 'M10.2 10.5v6', 'M13.8 10.5v6'],
  pin: ['M12 21s-6.5-5.8-6.5-10.5a6.5 6.5 0 0 1 13 0C18.5 15.2 12 21 12 21z', 'c:12,10.5,2.3'],
  home: ['M3.5 11L12 4l8.5 7', 'M6 9.5V20h12V9.5', 'M10 20v-5h4v5'],
  folder: ['M3.5 7a2 2 0 0 1 2-2h4l2 2h7a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2h-13a2 2 0 0 1-2-2z'],
  file: ['M6 3.5h8.5L19 8v12.5H6z', 'M14 3.5V8.5h5'],
  more: ['c:5.5,12,1.2', 'c:12,12,1.2', 'c:18.5,12,1.2'],
  check: ['M5 12.5l4.5 4.5L19 7'],
  refresh: ['M19.5 11.5a7.5 7.5 0 1 1-2.2-5.3', 'M19.5 4.5v5h-5'],
  external: ['M14 4h6v6', 'M20 4l-9 9', 'M18 14v5a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V7a1 1 0 0 1 1-1h5'],
  calendar: ['M4.5 6.5h15v13h-15z', 'M4.5 10.5h15', 'M8.5 4v4', 'M15.5 4v4'],
  key: ['c:8,15,4', 'M11 12l8.5-8.5', 'M16.5 6.5l2.5 2.5'],
  cloudOff: ['M4 4l16 16', 'M8.5 6.3A6 6 0 0 1 17.7 10 4 4 0 0 1 20 17', 'M6.2 8.6A4.5 4.5 0 0 0 7 18h10'],
  heart: ['M12 20s-7.5-4.6-7.5-10A4.3 4.3 0 0 1 12 7.3 4.3 4.3 0 0 1 19.5 10c0 5.4-7.5 10-7.5 10z'],
  rings: ['c:9,13,5', 'c:15,13,5', 'M7.5 5.5L9 3.5l1.5 2'],
  menu: ['M4 7h16', 'M4 12h16', 'M4 17h16'],
  zoomIn: ['c:11,11,7', 'M20 20l-3.6-3.6', 'M11 8v6', 'M8 11h6'],
  zoomOut: ['c:11,11,7', 'M20 20l-3.6-3.6', 'M8 11h6'],
  target: ['c:12,12,7.5', 'c:12,12,2.5'],
};

export function icon(name, cls = '') {
  const parts = ICONS[name] || ICONS.file;
  const el = s('svg', { viewBox: '0 0 24 24', class: 'icon' + (cls ? ' ' + cls : ''), 'aria-hidden': 'true', focusable: 'false' });
  for (const p of parts) {
    if (p.startsWith('c:')) {
      const [cx, cy, r] = p.slice(2).split(',');
      el.append(s('circle', { cx, cy, r }));
    } else {
      el.append(s('path', { d: p }));
    }
  }
  return el;
}
