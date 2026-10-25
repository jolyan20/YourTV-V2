// The Saudi emblem as on the Makkah clock's face (fitted to the photos of the tower), in the face photo's own 640 px
// space, dial centre (318.5, 331), r 266: a dome of thirteen fronds high in the inner disc, their outer ones hanging
// almost straight down, a slender trunk down to the swords, two thin scimitars arched over and crossed under it, hilts
// down and out, and the line of writing under them.
module.exports = function emblem(night){
  const X = 318.5, C = [X, 216];
  const q = (a, c, b, t) => [(1-t)*(1-t)*a[0] + 2*(1-t)*t*c[0] + t*t*b[0], (1-t)*(1-t)*a[1] + 2*(1-t)*t*c[1] + t*t*b[1]];
  // a band along a quadratic curve, its half width w(t)
  const band = (a, c, b, w) => {
    const L = [], R = [];
    for(let i = 0; i <= 30; i++){
      const t = i / 30, p = q(a, c, b, t), p1 = q(a, c, b, Math.min(1, t + .01)), p0 = q(a, c, b, Math.max(0, t - .01));
      const dx = p1[0] - p0[0], dy = p1[1] - p0[1], l = Math.hypot(dx, dy) || 1, nx = -dy / l, ny = dx / l, h = w(t);
      L.push([p[0] + nx * h, p[1] + ny * h]); R.push([p[0] - nx * h, p[1] - ny * h]);
    }
    const f = l => l.map(p => p[0].toFixed(1) + ' ' + p[1].toFixed(1)).join(' L');
    return `M${f(L)} L${f(R.reverse())}Z`;
  };
  const curve = (a, c, b) => `M${a.map(v => v.toFixed(1)).join(' ')} Q${c.map(v => v.toFixed(1)).join(' ')} ${b.map(v => v.toFixed(1)).join(' ')}`;
  // the fronds: from the crown point up and out, then down; the middle one short and upright, the outer ones longest
  let fronds = '', ribs = '';
  // the fronds fan out from the crown point, from upright through level to hanging down and out, each bending down
  // under its own weight; drawn from the hanging ones (behind) to the upright one (in front), a full dome
  const F = [[0,42],[18,46],[36,52],[54,58],[72,62],[90,64],[108,66],[124,66],[140,62],[156,56]];   // [angle from up, length]
  const frond = (phi, len, s) => {
    const r = d => d * Math.PI / 180, bend = 34 + phi * .16;
    const tip = [X + s * Math.sin(r(phi)) * len, C[1] - Math.cos(r(phi)) * len];
    const ctl = [X + s * Math.sin(r(phi - bend)) * len * .6, C[1] - Math.cos(r(phi - bend)) * len * .6];
    const w = u => (u < .12 ? 1.8 + u / .12 * 6.4 : u < .66 ? 8.2 + (u - .12) * 1.2 : 8.8 * (1 - (u - .66) / .34) + .6);
    fronds += `<path d="${band(C, phi === 0 ? [X, C[1] - len * .6] : ctl, tip, w)}"/>`;
    ribs += `<path d="${curve(C, phi === 0 ? [X, C[1] - len * .6] : ctl, tip)}"/>`;
  };
  for(const [phi, len] of F.slice().reverse()) for(const s of phi ? [-1, 1] : [1]) frond(phi, len, s);
  // a scimitar: grip and pommel at `h`, the crossguard at `g`, the blade arched over to the tip `t` through `c`
  const sword = (h, g, c, t) => {
    const dx = h[0] - g[0], dy = h[1] - g[1], l = Math.hypot(dx, dy), nx = -dy / l, ny = dx / l;
    const f = p => p.map(v => v.toFixed(1)).join(' ');
    const w = u => (u < .72 ? 1.8 + u * 2 : 3.2 * (1 - (u - .72) / .28) + .15);   // widest near the point, as a scimitar
    return `<path class="blade" d="${band(g, c, t, w)}"/>`
      + `<path class="guard" d="M${f([g[0] + nx * 7.5, g[1] + ny * 7.5])} Q${f([g[0] + nx * 2 + dx * .12, g[1] + ny * 2 + dy * .12])} ${f(g)} Q${f([g[0] - nx * 2 + dx * .12, g[1] - ny * 2 + dy * .12])} ${f([g[0] - nx * 7.5, g[1] - ny * 7.5])}"/>`
      + `<path class="grip" d="M${f(g)} L${f(h)}"/><circle class="pommel" cx="${h[0].toFixed(1)}" cy="${h[1].toFixed(1)}" r="2.4"/>`;
  };
  const sw = s => sword([X + s * 55, 448], [X + s * 41, 433], [X - s * 34, 416], [X - s * 82, 368]);
  const gold = night ? ['#F6C86A', '#D98E2A', '#9A5A12'] : ['#FBE6A0', '#E2B243', '#A87A1C'];
  return `<g id="emblem">
<defs><linearGradient id="eg" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="${gold[0]}"/><stop offset=".5" stop-color="${gold[1]}"/><stop offset="1" stop-color="${gold[2]}"/></linearGradient>
<linearGradient id="eb" x1="0" y1="0" x2="1" y2="0"><stop offset="0" stop-color="${gold[1]}"/><stop offset=".5" stop-color="${gold[0]}"/><stop offset="1" stop-color="${gold[1]}"/></linearGradient></defs>
<style>.rib{fill:none;stroke:${gold[2]};stroke-width:.7;opacity:.55}.blade{fill:url(#eb);stroke:${gold[2]};stroke-width:.6}.guard{fill:none;stroke:${gold[1]};stroke-width:2.4;stroke-linecap:round}.grip{stroke:${gold[2]};stroke-width:3.2;stroke-linecap:round}.pommel{fill:${gold[1]};stroke:${gold[2]};stroke-width:.6}</style>
<path d="M315 218 L313.6 392 C313.2 398 311.4 402 309.6 405 L327.4 405 C325.6 402 323.8 398 323.4 392 L322 218Z" fill="url(#eb)" stroke="${gold[2]}" stroke-width=".7"/>
${sw(1)}${sw(-1)}
<g fill="url(#eg)" stroke="${gold[2]}" stroke-width="1.1" stroke-linejoin="round">${fronds}</g>
<g class="rib">${ribs}</g>
<text x="${X}" y="478" text-anchor="middle" font-family="Noto Naskh Arabic, Amiri, serif" font-size="13" fill="${night ? '#CFE9DD' : '#8A8376'}" opacity=".6" direction="rtl">المملكة العربية السعودية</text>
</g>`;
};
