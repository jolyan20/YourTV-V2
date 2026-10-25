// The Saudi emblem as on the Makkah clock's face, in the face photo's own 640 px space: a palm of eleven fronds in a
// dome (the lowest two hanging), a slender ringed trunk, and two scimitars crossed under it, hilts down.
module.exports = function emblem(){
  const C = [319, 268];
  const rad = a => a * Math.PI / 180;
  // a frond: an arch from the crown, rising first and bending out and down to its tip, broad in its middle third
  const frond = (ang, len, droop, w) => {
    const up = ang - (ang + 90) * .45, a0 = rad(up), a1 = rad(ang);
    const tip = [C[0] + Math.cos(a1) * len, C[1] + Math.sin(a1) * len + droop];
    const ctl = [C[0] + Math.cos(a0) * len * .78, C[1] + Math.sin(a0) * len * .78];
    const pt = t => [(1-t)*(1-t)*C[0] + 2*(1-t)*t*ctl[0] + t*t*tip[0], (1-t)*(1-t)*C[1] + 2*(1-t)*t*ctl[1] + t*t*tip[1]];
    const left = [], right = [], spine = [];
    for(let i = 0; i <= 24; i++){
      const t = i / 24, p = pt(t), q = pt(Math.min(1, t + .01)), r0 = pt(Math.max(0, t - .01));
      const dx = q[0] - r0[0], dy = q[1] - r0[1], L = Math.hypot(dx, dy) || 1, nx = -dy / L, ny = dx / L;
      const hw = w * (t < .15 ? t / .15 : t < .7 ? 1 : 1 - (t - .7) / .3 * .55);   // full from a sixth on, blunt at the tip
      left.push([p[0] + nx * hw, p[1] + ny * hw]); right.push([p[0] - nx * hw, p[1] - ny * hw]); spine.push(p);
    }
    const f = l => l.map(p => p[0].toFixed(1) + ' ' + p[1].toFixed(1)).join(' L');
    const e = left[left.length - 1], r1 = right[right.length - 1];
    return `<path d="M${f(left)} L${f(right.reverse())}Z"/><path class="rib" d="M${f(spine)}"/>`;
  };
  const fronds = [[-90,68,0,10.5],[-70,68,6,10.5],[-110,68,6,10.5],[-50,66,14,10.5],[-130,66,14,10.5],[-30,62,24,10],[-150,62,24,10],
                  [-12,54,28,9.5],[-168,54,28,9.5],[6,44,32,8.5],[-186,44,32,8.5]];
  // a scimitar: hilt (pommel, grip, crossguard) at `h`, the blade sweeping up to `t`, convex below, widest near the tip
  const sword = (h, t, bend, side) => {
    const dx = t[0] - h[0], dy = t[1] - h[1], L = Math.hypot(dx, dy), ux = dx / L, uy = dy / L, nx = uy * side, ny = -ux * side;
    const g = [h[0] + ux * 16, h[1] + uy * 16];                // crossguard
    const c = [(g[0] + t[0]) / 2 - nx * bend, (g[1] + t[1]) / 2 - ny * bend];
    const back = [(g[0] + t[0]) / 2 - nx * (bend - 21), (g[1] + t[1]) / 2 - ny * (bend - 21)];
    const f = p => p.map(v => v.toFixed(1)).join(' ');
    return `<path class="blade" d="M${f([g[0]+nx*2.2, g[1]+ny*2.2])} Q${f(c)} ${f(t)} Q${f(back)} ${f([g[0]-nx*2.6, g[1]-ny*2.6])}Z"/>`
      + `<path class="guard" d="M${f([g[0]+nx*9, g[1]+ny*9])} L${f([g[0]-nx*9, g[1]-ny*9])}"/>`
      + `<path class="grip" d="M${f(g)} L${f(h)}"/><circle class="pommel" cx="${h[0].toFixed(1)}" cy="${h[1].toFixed(1)}" r="3.2"/>`;
  };
  const rings = Array.from({length: 13}, (_, i) => { const y = 284 + i * 8.6, w = 4.4 + i * .18; return `<path d="M${(319.4 - w).toFixed(1)} ${y.toFixed(1)}q${w.toFixed(1)} 3 ${(2*w).toFixed(1)} 0"/>`; }).join('');
  return `<g id="emblem">
<defs><linearGradient id="eg" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#FFF0A8"/><stop offset=".45" stop-color="#F2C640"/><stop offset="1" stop-color="#C08A18"/></linearGradient>
<linearGradient id="eb" x1="0" y1="0" x2="1" y2="0"><stop offset="0" stop-color="#E9B83A"/><stop offset=".5" stop-color="#FFF1B0"/><stop offset="1" stop-color="#D9A42C"/></linearGradient></defs>
<g fill="url(#eg)" stroke="#9C6E12" stroke-width="1.1" stroke-linejoin="round">${fronds.map(f => frond(...f)).join('')}</g>
<style>.rib{fill:none;stroke:#B07D16;stroke-width:.9;opacity:.7}.blade{fill:url(#eb);stroke:#9C6E12;stroke-width:1}.guard{stroke:#B98516;stroke-width:3.4;stroke-linecap:round}.grip{stroke:#C99A2E;stroke-width:3.6;stroke-linecap:round}.pommel{fill:#E9B83A;stroke:#9C6E12;stroke-width:.8}</style>
<path d="M316.4 274 L315.4 384 C315 390 313.6 394 312.4 397 L327 397 C325.6 394 324.4 390 324 384 L322.8 274Z" fill="url(#eb)" stroke="#9C6E12" stroke-width="1"/>
${sword([279, 448], [416, 382], 17, 1)}
${sword([362, 448], [224, 387], 17, -1)}
</g>`;
};
