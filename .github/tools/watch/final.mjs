// Makes assets/watch/makkah-day.jpg and makkah-night.jpg (1200 px) from the face photographs: run in this folder
//   python3 -I clean.py ../../../assets/watch/<day photo>.jpg day-clean3.jpg     the dial emptied to its own colour
//   python3 -I cleann.py ../../../assets/watch/<night photo>.jpg night-clean.jpg
//   node final.mjs                                                                the marks, circle and emblem drawn
// (playwright and scipy needed; dial.js and emblem.js hold the drawn parts, in the photographs' 640 px space)
import { createRequire } from 'node:module';
const req = createRequire(import.meta.url);
const pw = createRequire('/opt/node-tools/node_modules/')('playwright');
const emblem = req('./emblem.js'), dial = req('./dial.js'), fs = req('fs');
const b = await pw.chromium.launch();
for (const [src, out, night] of [['day-clean3.jpg','makkah-day.jpg',false],['night-clean.jpg','makkah-night.jpg',true]]) {
  const face = 'data:image/jpeg;base64,' + fs.readFileSync(src).toString('base64');
  const p = await b.newPage({viewport:{width:640,height:640}, deviceScaleFactor:1.875});   // 1200 px
  // the photo enlarged smoothly, the emblem drawn sharp over it; at night the emblem lit a little warmer
  await p.setContent(`<body style="margin:0"><svg width="640" height="640" viewBox="0 0 640 640"><image href="${face}" width="640" height="640" style="image-rendering:auto"/>`
    + dial(night) + `<g ${night ? 'filter="url(#lit)"' : ''}>${emblem()}</g><defs><filter id="lit"><feColorMatrix type="matrix" values="1.08 0 0 0 0  0 1.04 0 0 0  0 0 .9 0 0  0 0 0 1 0"/></filter></defs></svg></body>`);
  await p.waitForTimeout(300);
  await p.screenshot({path: out, type:'jpeg', quality: 90});
  await p.close();
}
await b.close();
