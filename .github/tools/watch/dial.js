// The dial's drawn parts over the cleaned photo (640 px space, centre 319.5): the twelve hour marks, each a pair of
// bars from r 163 to 259 (chisel top with a small ear on its outer side, a flared foot), a gold bevel under each as
// the light falls on the real face, and the thin gold circle between the marks and the emblem.
module.exports = function dial(night){
  const cx = 318.5, cy = 331;   // the dial's own centre in the photo (fitted to its rim)
  // one bar, its across-axis (u, outward from the pair's middle) and radius (v)
  const V = v => 163 + (v - 141) * (259 - 163) / (250.5 - 141);   // the marks from r 163 to 259, the same all round
  const bar = [[3,141],[19.6,141],[19.6,144.5],[17.6,149],[17.6,231.5],[19.2,233],[18.8,238.5],[3.2,250.5]].map(([u, v]) => [u, V(v)]);
  const pts = (side, rot) => bar.map(([u, v]) => {
    const a = rot * Math.PI / 180, x = side * u, y = -v;   // before turning: 12 o'clock
    return [(cx + x * Math.cos(a) - y * Math.sin(a)).toFixed(2), (cy + x * Math.sin(a) + y * Math.cos(a)).toFixed(2)].join(' ');
  }).join(' L');
  const face = night ? '#F4FAF2' : '#17181C', edge = night ? '#C9E9C6' : '#2A2B30';
  let marks = '';
  for(let h = 0; h < 12; h++) for(const side of [-1, 1]) marks += `<path d="M${pts(side, h * 30)}Z"/>`;
  return `<g id="dialparts">
<circle cx="${cx}" cy="${cy}" r="150" fill="none" stroke="#C99A2E" stroke-width="1.7" opacity=".9"/>
<g transform="translate(1.7 1.9)" fill="#C9A03E">${marks}</g>
<g fill="${face}" stroke="${edge}" stroke-width=".6" stroke-linejoin="round">${marks}</g>
</g>`;
};
