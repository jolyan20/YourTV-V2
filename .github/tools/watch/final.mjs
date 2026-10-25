import { createRequire } from 'node:module';
const req = createRequire(import.meta.url);
const pw = createRequire('/opt/node-tools/node_modules/')('playwright');
const emblem = req('./emblem.js'), dial = req('./dial.js'), fs = req('fs');
const out = process.argv[2] || '.';
const b = await pw.chromium.launch();
for (const [src, name, night] of [['day-clean3.jpg','makkah-day.jpg',false],['night-clean.jpg','makkah-night.jpg',true]]) {
  const face = 'data:image/jpeg;base64,' + fs.readFileSync(src).toString('base64');
  const p = await b.newPage({viewport:{width:640,height:640}, deviceScaleFactor:1.875});   // 1200 px
  // the dial's colour as in the photos of the tower: by day a warm white, at night the lit emerald with the inner
  // disc a deeper sea green; the edge feathered into the photo's rim
  const [o1, o2, i1, i2] = night ? ['#16A57C', '#0A8C69', '#3C9079', '#2F7C68'] : ['#F3F0E7', '#E3DED0', '#F1EEE5', '#E9E5DA'];
  const paint = `<defs><radialGradient id="dg" cx="318.5" cy="331" r="269" gradientUnits="userSpaceOnUse"><stop offset="0" stop-color="${o1}"/><stop offset="1" stop-color="${o2}"/></radialGradient>
<radialGradient id="ig" cx="318.5" cy="331" r="150" gradientUnits="userSpaceOnUse"><stop offset="0" stop-color="${i1}"/><stop offset="1" stop-color="${i2}"/></radialGradient>
<filter id="fe"><feGaussianBlur stdDeviation="1.2"/></filter></defs>
<circle cx="318.5" cy="331" r="268.5" fill="url(#dg)" filter="url(#fe)"/><circle cx="318.5" cy="331" r="269" fill="none" stroke="#B88A2C" stroke-width="2.2"/><circle cx="318.5" cy="331" r="150" fill="url(#ig)"/>`;
  await p.setContent(`<body style="margin:0"><svg width="640" height="640" viewBox="0 0 640 640"><image href="${face}" width="640" height="640" style="image-rendering:auto"/>`
    + paint + dial(night) + `</svg></body>`);
  await p.waitForTimeout(300);
  await p.screenshot({path: out + '/' + name, type:'jpeg', quality: 90});
  await p.close();
}
await b.close();
