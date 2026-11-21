// The ancestors chart (a pedigree drawn left to right) and the descendants tree.
import { h, s, icon } from '../dom.js';
import { api } from '../api.js';
import { href, avatar, linkButton, life, prettyDate } from '../ui.js';
import { addRelativeDialog } from './relatives.js';

const DEPTH_KEY = 'gedcom-shell.depth.';

function depthFor(app, kind) {
  const saved = Number(localStorage.getItem(DEPTH_KEY + kind));
  if (saved) return saved;
  return kind === 'pedigree' ? (app.isPhone() ? 3 : 4) : 3;
}

function chartHead(app, p, kind, depth, rerender) {
  const fid = app.fileId;
  const max = kind === 'pedigree' ? 8 : 6;
  const select = h('select.select', { id: 'chart-depth', aria: { label: 'Generations' } },
    Array.from({ length: max }, (_, i) => h('option', { value: String(i + 1), selected: i + 1 === depth }, `${i + 1} ${i ? 'generations' : 'generation'}`)));
  select.addEventListener('change', () => {
    localStorage.setItem(DEPTH_KEY + kind, select.value);
    rerender();
  });
  return h('header.page-head.chart-head', [
    h('div.page-head-text', [
      h('h1.page-title-h', [kind === 'pedigree' ? 'Ancestors of ' : 'Descendants of ', h('a.title-link', { href: href(fid, 'people', p.key) }, p.name)]),
      h('p.page-sub', kind === 'pedigree' ? 'Click someone to see their ancestors; click the person on the left to open their page.' : 'Click someone to see their descendants; click the top person to open their page.'),
    ]),
    h('div.chart-tools', [
      h('nav.segmented', { aria: { label: 'Chart' } }, [
        h('a.seg' + (kind === 'pedigree' ? '.is-active' : ''), { href: href(fid, 'pedigree', p.key), aria: { current: kind === 'pedigree' ? 'page' : null } }, [icon('pedigree'), h('span', 'Ancestors')]),
        h('a.seg' + (kind === 'descendants' ? '.is-active' : ''), { href: href(fid, 'descendants', p.key), aria: { current: kind === 'descendants' ? 'page' : null } }, [icon('descendants'), h('span', 'Descendants')]),
      ]),
      select,
    ]),
  ]);
}

// ---- ancestors ---------------------------------------------------------------------

export async function pedigreeView(app, key) {
  if (!key) return noPerson(app);
  const depth = depthFor(app, 'pedigree');
  const root = await api.pedigree(app.fileId, key, depth);
  app.setTitle('Ancestors of ' + root.name);
  const page = h('div.page.chart-page');
  const rerender = async () => {
    const node = await pedigreeView(app, key);
    page.replaceWith(node);
  };
  page.append(chartHead(app, root, 'pedigree', depth, rerender), pedigreeChart(app, root, depth));
  return page;
}

function pedigreeChart(app, root, depth) {
  const phone = app.isPhone();
  const W = phone ? 168 : 212, BH = phone ? 56 : 60, GAP = phone ? 28 : 48, ROW = 10, PAD = 20;
  const items = [];
  let cursor = PAD;
  let maxGen = 0;

  const lay = (p, gen, child, side) => {
    const item = { p, gen, child, side, parents: [] };
    maxGen = Math.max(maxGen, gen);
    if (p && gen < depth) {
      for (const [k, sd] of [['father', 'father'], ['mother', 'mother']]) {
        if (p[k]) item.parents.push(lay(p[k], gen + 1, item, sd));
        else if (gen <= 1) item.parents.push(lay(null, gen + 1, item, sd));
      }
    }
    if (item.parents.length) {
      const ys = item.parents.map((x) => x.y);
      item.y = (Math.min(...ys) + Math.max(...ys)) / 2;
    } else {
      item.y = cursor + BH / 2;
      cursor += BH + ROW;
    }
    item.x = PAD + gen * (W + GAP);
    items.push(item);
    return item;
  };
  const top = lay(root, 0, null, null);
  const width = PAD * 2 + (maxGen + 1) * W + maxGen * GAP;
  const height = cursor - ROW + PAD;

  const lines = s('svg', { class: 'chart-lines', width, height, viewBox: `0 0 ${width} ${height}`, 'aria-hidden': 'true' });
  const canvas = h('div.chart-canvas', [lines]);
  canvas.style.width = width + 'px';
  canvas.style.height = height + 'px';

  for (const it of items) {
    if (it.child) {
      const x1 = it.child.x + W, y1 = it.child.y, x2 = it.x, y2 = it.y, xm = x1 + GAP / 2;
      lines.append(s('path', { d: `M${x1} ${y1} C${xm} ${y1} ${xm} ${y2} ${x2} ${y2}`, class: 'chart-line' + (it.p ? '' : ' is-empty') }));
    }
    let el;
    if (it.p) {
      const isRoot = it === top;
      const target = isRoot ? href(app.fileId, 'people', it.p.key) : href(app.fileId, 'pedigree', it.p.key);
      el = h('a.pnode.sex-' + it.p.sex + (isRoot ? '.is-root' : ''), {
        href: target, title: isRoot ? `Open ${it.p.name}` : `Ancestors of ${it.p.name}`,
      }, [
        avatar(it.p, 'sm'),
        h('span.pnode-text', [h('span.pnode-name', it.p.name || '(no name)'), h('span.pnode-life', life(it.p.lifespan))]),
      ]);
    } else {
      const child = it.child.p;
      el = h('button.pnode.is-empty', {
        type: 'button', onclick: () => addRelativeDialog(app, child, it.side),
        aria: { label: `Add ${it.side} of ${child.name}` },
      }, [h('span.placeholder-avatar', icon('plus')), h('span.pnode-text', [h('span.pnode-name', `Add ${it.side}`), h('span.pnode-life', 'Not recorded')])]);
    }
    el.style.left = it.x + 'px';
    el.style.top = it.y - BH / 2 + 'px';
    el.style.width = W + 'px';
    el.style.height = BH + 'px';
    canvas.append(el);
  }
  const viewport = h('div.chart-viewport', { tabindex: '0', aria: { label: 'Ancestors chart; drag or scroll to move around' } }, canvas);
  pannable(viewport);
  // start with the root person in view, vertically centred
  requestAnimationFrame(() => {
    viewport.scrollTop = Math.max(0, top.y - viewport.clientHeight / 2);
    viewport.scrollLeft = 0;
  });
  return viewport;
}

/** Drag with the mouse to move around; touch scrolls natively. */
function pannable(viewport) {
  let start = null, moved = false;
  const move = (e) => {
    const dx = e.clientX - start.x, dy = e.clientY - start.y;
    if (!moved && Math.hypot(dx, dy) < 5) return;
    if (!moved) viewport.classList.add('is-panning');
    moved = true;
    viewport.scrollLeft = start.l - dx;
    viewport.scrollTop = start.t - dy;
  };
  const up = () => {
    start = null;
    viewport.classList.remove('is-panning');
    window.removeEventListener('pointermove', move);
    window.removeEventListener('pointerup', up);
  };
  viewport.addEventListener('pointerdown', (e) => {
    if (e.pointerType !== 'mouse' || e.button !== 0) return;
    start = { x: e.clientX, y: e.clientY, l: viewport.scrollLeft, t: viewport.scrollTop };
    moved = false;
    window.addEventListener('pointermove', move);
    window.addEventListener('pointerup', up);
  });
  viewport.addEventListener('click', (e) => {
    if (moved) { e.preventDefault(); e.stopPropagation(); moved = false; }
  }, true);
  viewport.addEventListener('dragstart', (e) => e.preventDefault());
}

// ---- descendants ----------------------------------------------------------------------

export async function descendantsView(app, key) {
  if (!key) return noPerson(app);
  const depth = depthFor(app, 'descendants');
  const root = await api.descendants(app.fileId, key, depth);
  app.setTitle('Descendants of ' + root.name);
  const page = h('div.page.chart-page');
  const rerender = async () => {
    const node = await descendantsView(app, key);
    page.replaceWith(node);
  };
  let count = 0;
  const node = (p, isRoot) => {
    if (!isRoot) count++;
    const target = isRoot ? href(app.fileId, 'people', p.key) : href(app.fileId, 'descendants', p.key);
    const card = h('a.dnode.sex-' + p.sex + (isRoot ? '.is-root' : ''), { href: target, title: isRoot ? `Open ${p.name}` : `Descendants of ${p.name}` }, [
      avatar(p, 'sm'),
      h('span.pnode-text', [h('span.pnode-name', p.name || '(no name)'), h('span.pnode-life', life(p.lifespan))]),
    ]);
    const fams = (p.families || []).map((f) => h('li.dfam', [
      h('div.dfam-head', [
        icon('rings', 'dfam-icon'),
        f.partner
          ? h('a.dfam-partner', { href: href(app.fileId, 'people', f.partner.key) }, [h('span', f.partner.name), f.partner.lifespan ? h('span.dfam-life', life(f.partner.lifespan)) : null])
          : h('span.muted', 'Partner not recorded'),
        f.married && f.married.date ? h('span.dfam-married', `Married ${prettyDate(f.married.date)}`) : null,
        h('a.dfam-link', { href: href(app.fileId, 'families', f.family), aria: { label: `Family ${f.family}` }, title: `Family ${f.family}` }, f.family),
      ]),
      f.children.length ? h('ul.dkids', f.children.map((c) => h('li.dkid', node(c, false)))) : h('p.dnone', 'No children recorded'),
    ]));
    return h('div.dperson', [card, fams.length ? h('ul.dfams', fams) : null]);
  };
  const tree = node(root, true);
  page.append(
    chartHead(app, root, 'descendants', depth, rerender),
    h('div.card.dtree-card', [
      h('div.dtree', tree),
      count ? null : h('p.muted.small.pad', `No descendants of ${root.name} are recorded.`),
    ]),
  );
  return page;
}

function noPerson(app) {
  app.setTitle('Tree');
  return h('div.page', h('div.empty', [
    h('div.empty-icon', icon('pedigree')),
    h('p.empty-title', 'No one to draw yet'),
    h('p.empty-text', 'Choose a person first, then open their tree.'),
    linkButton('Find a person', href(app.fileId, 'people'), { icon: 'search' }),
  ]));
}
