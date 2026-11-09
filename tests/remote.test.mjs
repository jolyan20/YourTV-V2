// The phone page's remote (assets/shared/control.html, tab «الريموت») against a stand-in TV: the keys in the order
// pressed, typing and YouTube's search, the apps row, and the «turn it on» card while the TV's service is off.
import { chromium } from 'playwright';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = path.join(path.dirname(fileURLToPath(import.meta.url)), '..');
const html = fs.readFileSync(path.join(ROOT, 'assets/shared/control.html'), 'utf8');
let failed = 0;
const check = (name, ok, detail = '') => { console.log(`${ok ? 'PASS' : 'FAIL'} ${name}${ok ? '' : '  -> ' + detail}`); if(!ok) failed++; };

const browser = await chromium.launch();
const ctx = await browser.newContext({ viewport: { width: 820, height: 1180 }, hasTouch: true });
const sent = [];
let serviceOn = false;
await ctx.route('http://tv.test/**', async r => {
  const u = new URL(r.request().url()), body = r.request().postData();
  const json = o => r.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(o) });
  if(u.pathname === '/') return r.fulfill({ status: 200, contentType: 'text/html; charset=utf-8', body: html });
  if(u.pathname === '/api/login') return json({ key: 'k1' });
  if(u.pathname === '/api/devices') return json([{ id: 'local', name: 'TCL' }]);
  if(u.pathname === '/api/state') return json({ tasks: [], reminders: [], family: [], screens: {}, display: {} });
  if(u.pathname === '/api/prefs') return json({ regions: [{ name: 'الرياض', cities: ['الرياض'] }], city: ['الرياض'], teams: [] });
  if(u.pathname === '/api/remote'){
    if(!body) return json({ on: serviceOn, apps: [{ pkg: 'com.google.android.youtube.tv', name: 'YouTube' }, { pkg: 'net.shahid', name: 'شاهد' }] });
    const o = JSON.parse(body); sent.push(o);
    const why = o.do === 'key' && !serviceOn && !['volup', 'voldown', 'mute', 'play'].includes(o.key) ? 'off' : '';
    return json({ ok: !why, why, on: serviceOn });
  }
  return json({});
});
const p = await ctx.newPage();
const errors = []; p.on('pageerror', e => errors.push(e.message + ' @ ' + String(e.stack).split('\n').slice(1, 3).join(' ')));
await p.goto('http://tv.test/');
await p.fill('#pin', '123456'); await p.click('#pinbox .main');
await p.waitForSelector('#app:not([hidden])');
await p.click('.tabs button[data-k="remote"]');
await p.waitForFunction(() => document.querySelectorAll('#rm-apps button').length === 2);
check('the remote tab: the TV\'s apps listed', true);
check('the service off: the card that says how to turn it on', await p.isVisible('#rm-off'));

serviceOn = true;
for(const k of ['up', 'up', 'right', 'ok']) await p.dispatchEvent(`#rm-pad button[data-k="${k}"]`, 'pointerdown');
await p.dispatchEvent('#rm-pad', 'pointerup');
await p.dispatchEvent('#rm-keys button[data-k="back"]', 'pointerdown'); await p.dispatchEvent('#rm-keys', 'pointerup');
await p.waitForTimeout(400);
const keys = sent.filter(o => o.do === 'key').map(o => o.key).join(',');
check('the keys reach the TV in the order pressed', keys === 'up,up,right,ok,back', keys);

await p.fill('#rm-text', 'سورة البقرة');
await p.click('text=ابحث في يوتيوب'); await p.waitForTimeout(300);
await p.press('#rm-text', 'Enter'); await p.waitForTimeout(300);
const typed = sent.filter(o => o.do !== 'key').map(o => o.do + ':' + o.text).join(' | ');
check('YouTube search and typing with the words', typed === 'youtube:سورة البقرة | type:سورة البقرة', typed);

await p.click('#rm-apps button[data-pkg="net.shahid"]'); await p.waitForTimeout(300);
check('an app opens from the row', sent.some(o => o.do === 'open' && o.pkg === 'net.shahid'));

await p.click('body'); await p.keyboard.press('ArrowDown'); await p.waitForTimeout(300);
check("an iPad keyboard's arrow works as the remote's", sent.at(-1).do === 'key' && sent.at(-1).key === 'down', JSON.stringify(sent.at(-1)));
await p.waitForTimeout(6500);
check('the card goes once the service is on', !(await p.isVisible('#rm-off')));
check('no script errors', !errors.length, errors.join('; '));

await browser.close();
console.log(failed ? `\n${failed} failed` : '\nall remote tests passed');
process.exit(failed ? 1 : 0);
