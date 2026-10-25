// Screen tests in a real browser, offline: both pages, every card, card-only mode, old settings links.
//
//   npm install playwright@1.56.1 && npx playwright install chromium     (once)
//   node tests/page.test.mjs
//
// Network is cut; the photos and fonts the app serves from the device are served from the repo instead,
// the way PhotoCache.java answers them on the TV.
import { chromium } from 'playwright';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const page = name => 'file://' + path.join(ROOT, 'dist', name);
const INDEX = readFileSync(path.join(ROOT, 'photos/index.json'), 'utf8');
let failed = 0;
const check = (name, ok, detail = '') => {
  console.log((ok ? 'PASS ' : 'FAIL ') + name + (ok ? '' : '  -> ' + detail));
  if (!ok) failed++;
};

const browser = await chromium.launch(process.env.CHROMIUM ? { executablePath: process.env.CHROMIUM } : {});

async function open(file, hash, { width = 1920, height = 1080, seed = true } = {}) {
  const ctx = await browser.newContext({ viewport: { width, height } });
  await ctx.route(/^https?:/, r => {
    const u = r.request().url(), i = u.indexOf('/master/'), rel = i > 0 ? u.slice(i + 8) : '';
    if (/^(assets\/(backgrounds|fonts)|photos)\//.test(rel))
      return r.fulfill({ path: path.join(ROOT, rel), headers: { 'Access-Control-Allow-Origin': '*' } });
    return r.abort();
  });
  if (seed) await ctx.addInitScript(i => { try { localStorage.setItem('photoIndex', i); } catch (e) {} }, INDEX);
  const p = await ctx.newPage();
  const errors = [];
  p.on('pageerror', e => errors.push(e.message));
  await p.goto(page(file) + '#' + hash);
  await p.waitForFunction(() => document.documentElement.classList.contains('ready'), null, { timeout: 4000 })
    .catch(() => errors.push('never revealed'));
  // the screen sleeps from midnight until Fajr (the household's default): the checks must not depend on the hour
  await p.evaluate(() => { window.applySleep = () => {}; document.documentElement.classList.remove('sleep'); }).catch(() => {});
  await p.waitForTimeout(300);
  return { p, ctx, errors };
}

// ---- both pages, three screen sizes: no errors, shown, clock right, nothing off screen, Arabic digits
for (const file of ['urtv.html', 'tv.html']) {
  for (const [w, h] of [[1920, 1080], [1280, 720], [960, 540]]) {
    const { p, ctx, errors } = await open(file, 'city=جدة&lat=21.5433&lon=39.1728', { width: w, height: h });
    const tag = `${file} ${w}x${h}`;
    check(`${tag}: no script errors, page shown`, !errors.length, errors.join('; '));
    const r = await p.evaluate(() => {
      const hh = document.getElementById('ck-h').textContent, mm = document.getElementById('ck-m').textContent;
      const toN = s => +s.replace(/[٠-٩]/g, d => '٠١٢٣٤٥٦٧٨٩'.indexOf(d));
      const off = [...document.querySelectorAll('.stage *')].filter(e => {
        const b = e.getBoundingClientRect(), cs = getComputedStyle(e);
        return cs.display !== 'none' && b.width && (b.left < -2 || b.right > innerWidth + 2 || b.bottom > innerHeight + 2);
      }).map(e => e.className || e.id || e.tagName).slice(0, 5);
      // the time under the watch is written as the household writes it (02:30م, then 14:30): the rest in Arabic digits
      const cw = document.querySelector('.clockwrap'); cw.style.display = 'none';
      const latin = (document.querySelector('.stage').innerText.match(/[0-9]+/g) || []).filter(x => x !== '500');
      cw.style.display = '';
      return { hh, mm, h: toN(hh), m: toN(mm), ap: document.getElementById('ck-ap').textContent,
               sub: document.getElementById('clock-sub').textContent, off, latin,
               photo: !!document.querySelector('.photo').style.backgroundImage };
    });
    check(`${tag}: 12-hour clock under the watch (02:30م)`, /^[0-9]{2}$/.test(r.hh) && r.h >= 1 && r.h <= 12
      && /^[0-9]{2}$/.test(r.mm) && r.m < 60 && ['ص', 'م'].includes(r.ap), JSON.stringify(r));
    check(`${tag}: city from the settings`, r.sub === 'بتوقيت جدة', r.sub);
    check(`${tag}: nothing off screen`, !r.off.length, r.off.join(', '));
    check(`${tag}: no Latin digits (except S&P 500)`, !r.latin.length, r.latin.join(' '));
    check(`${tag}: a background photo`, r.photo);
    await ctx.close();
  }
}

// ---- every card beside the clock, with sample data where the card needs the network
const SAMPLE = {
  forecast: () => { FORECAST = [0, 1, 2, 3, 4].map(i => ({ date: new Date(Date.now() + i * 864e5).toISOString().slice(0, 10), code: 2, hi: 37 - i, lo: 24 - i })); },
  matches: () => {
    const ev = (id, h, a, hrs) => ({ idEvent: id, strHomeTeam: h, strAwayTeam: a, strTimestamp: new Date(Date.now() + hrs * 36e5).toISOString() });
    NEXT_LIST = { '136013': [ev('a', 'Al-Hilal', 'Al-Nassr', 3), ev('b', 'Al-Ahli', 'Al-Hilal', 80)] };
  },
};
const EXPECT = { prayers: [6, 'مواقيت اليوم'], forecast: [2, 'الطقس'], matches: [2, 'مباريات فرقك'],
                 verse: [2, null], shows: [1, 'هذا الأسبوع'] };
for (const [panel, [rows, title]] of Object.entries(EXPECT)) {
  const { p, ctx, errors } = await open('tv.html', 'teams=136013&panel=' + panel);
  // the card turns through its slides, the chosen box first: the first slide is the one checked
  await p.evaluate(() => { window.adhkarTime = () => null; window.summaryTime = () => false; window.todaysFamily = () => []; });
  if (panel === 'shows') await p.evaluate(() => { WEEK_POOL = [{ name: 'المؤسس عثمان', date: new Date().toISOString().slice(0, 10), ep: 'S6E12' }]; });
  if (SAMPLE[panel]) await p.evaluate(`(${SAMPLE[panel]})();`);
  // matches is one of the turning slides rather than the first: turn to it
  await p.evaluate(() => { const k = cardSlides().findIndex(f => f().title === PANEL_TITLE.matches);
    CARD_TURN = CONFIG.panel === 'matches' && k >= 0 ? k : 0; renderPanel(false); });
  const r = await p.evaluate(() => ({ n: document.querySelectorAll('#week-list li').length,
    hd: document.getElementById('week-hd').textContent, shown: getComputedStyle(document.querySelector('.week')).display !== 'none' }));
  check(`card ${panel}: no errors`, !errors.length, errors.join('; '));
  check(`card ${panel}: ${rows} rows, title right`, r.shown && r.n === rows && (!title || r.hd === title)
    && (title || ['آية اليوم', 'حديث اليوم'].includes(r.hd)), JSON.stringify(r));
  await ctx.close();
}
{
  const { p, ctx } = await open('tv.html', 'panel=verse');
  const both = await p.evaluate(() => {
    const day = riyadhDayNumber, out = [];
    for (const k of [0, 1]) { window.riyadhDayNumber = () => day() + k; out.push(quoteOfDay().title); }
    return out.sort().join(',');
  });
  check('card verse: a verse one day, a hadith the next', both === 'آية اليوم,حديث اليوم', both);
  await ctx.close();
}

// ---- every verse and hadith fits the card: each day's text drawn, clear of the clock and the bottom bar
for (const [width, height] of [[1920, 1080], [1280, 720], [960, 540]]) {
  const { p, ctx, errors } = await open('tv.html', 'panel=verse', { width, height });
  const r = await p.evaluate(() => {
    const day = riyadhDayNumber(), card = document.querySelector('.week'), bad = [];
    const days = 2 * Math.max(VERSES.length, HADITH.length);   // a verse on even days, a hadith on odd: all of both
    let tallest = 0;
    for (let k = 0; k < days; k++) {
      window.riyadhDayNumber = () => day + k;
      const q = quoteOfDay(), ul = document.getElementById('week-list');
      setCardTitle(document.getElementById('week-hd'), q.title); ul.innerHTML = q.rows.join(''); fitCard(ul);
      const c = card.getBoundingClientRect(), clock = document.querySelector('.clockwrap').getBoundingClientRect();
      const bar = document.querySelector('.bottom').getBoundingClientRect();
      tallest = Math.max(tallest, c.height);
      const alerts = document.querySelector('.alerts').getBoundingClientRect();
      // the card on the left half, as drawn: on screen, clear of the clock, above the alerts row and the bottom bar,
      // and its text inside it (long ones are shrunk by fitCard)
      const ulr = document.getElementById('week-list'), over = ulr.scrollHeight > ulr.clientHeight + 2;
      if (c.top < 0 || c.bottom > alerts.top - 2 || c.bottom > bar.top - 4 || c.right > clock.left - 4 || over)
        bad.push(`${Math.round(c.top)}-${Math.round(c.bottom)}/${Math.round(bar.top)} ` + card.querySelector('.quote').textContent.slice(0, 40));
    }
    return { days, bad, tallest: Math.round(tallest) };
  });
  check(`every verse and hadith fits the card at ${width}x${height} (${r.days} days, tallest ${r.tallest}px)`,
    !errors.length && !r.bad.length, `${r.bad.length} spill: ` + r.bad.slice(0, 3).join(' | ') + errors.join('; '));
  await ctx.close();
}

// ---- old settings links and «لا شيء»
for (const [hash, panel, shown] of [['', 'shows', true], ['week=0', 'none', false], ['week=1', 'shows', true],
                                     ['panel=none', 'none', false], ['panel=bogus', 'shows', true]]) {
  const { p, ctx, errors } = await open('tv.html', hash);
  const r = await p.evaluate(() => [CONFIG.panel, getComputedStyle(document.querySelector('.week')).display !== 'none']);
  check(`link #${hash || '(empty)'} → ${panel}, card ${shown ? 'shown' : 'hidden'}`,
    !errors.length && r[0] === panel && r[1] === shown, JSON.stringify(r) + errors.join('; '));
  await ctx.close();
}

// ---- card-only mode for the launcher: transparent, only the card, no photo
for (const panel of ['prayers', 'verse', 'shows']) {
  const { p, ctx, errors } = await open('tv.html', `widget=1&panel=${panel}`, { width: 461, height: 250 });
  const r = await p.evaluate(() => ({
    bg: getComputedStyle(document.body).backgroundColor,
    visible: [...document.querySelectorAll('.top,.clockwrap,.bottom,.photo')].filter(e => getComputedStyle(e).display !== 'none').length,
    card: document.querySelector('.week').getBoundingClientRect().width }));
  check(`launcher card ${panel}: transparent, only the card`, !errors.length && r.bg === 'rgba(0, 0, 0, 0)'
    && r.visible === 0 && r.card > 400, JSON.stringify(r) + errors.join('; '));
  await ctx.close();
}

// ---- last panels come back at once on the next start
{
  const ctx = await browser.newContext({ viewport: { width: 1920, height: 1080 } });
  await ctx.route(/^https?:/, r => r.abort());
  const p = await ctx.newPage();
  await p.goto(page('tv.html') + '#panel=shows');
  await p.waitForTimeout(800);
  await p.evaluate(() => { document.getElementById('week-list').innerHTML = '<li>SAVED</li>'; saveSnap(); });
  await p.reload();
  const t = await p.evaluate(() => document.getElementById('week-list').textContent);
  check('saved panels restored on the next start', t === 'SAVED', t);
  await ctx.close();
}

// ---- every box keeps its content inside it, as drawn: sample weather, series, a game, alerts, long names
for (const [width, height] of [[1920, 1080], [1280, 720], [960, 540]]) {
  const { p, ctx, errors } = await open('tv.html', 'teams=136013', { width, height });
  await p.evaluate(() => {
    const now = Date.now(), day = 864e5;
    CTRL = { screens: { list: [{ id: 'k1', name: 'سارة', kid: true, stars: 12 }, { id: 'k2', name: 'عبدالرحمن', kid: true, stars: 128 },
      { id: 'k3', name: 'نورة', kid: true, stars: 15 }] },
      family: [{ name: 'زواج خالد بن عبدالله', date: new Date(now + 9 * day).toISOString().slice(0, 10), kind: 'wedding' }] };
    FORECAST = [0, 1, 2, 3, 4].map(i => ({ date: new Date(now + i * day).toISOString().slice(0, 10), code: 2, hi: 37 - i, lo: 24 - i }));
    SERIES = { tvm1: { name: 'المؤسس عثمان الموسم السادس', kind: 'مسلسل', next: { t: now + 3 * day } },
               tvm2: { name: 'Dune: Part Three', kind: 'فيلم', movie: true, next: { t: now + 40 * day, dateOnly: true } } };
    CTRL.series = [{ id: '1' }, { id: '2' }];
    TODAY_GAMES = [{ t: now - 2 * 36e5, ev: { idEvent: 'x', strHomeTeam: 'Al-Hilal', strAwayTeam: 'Al-Nassr', idHomeTeam: '1', idAwayTeam: '2',
      strLeague: 'دوري روشن السعودي', intHomeScore: 2, intAwayScore: 1, strStatus: 'FT', strTimestamp: new Date(now - 2 * 36e5).toISOString() } }];
    FAM_CACHE.key = ''; tickFamily(); renderV2(); renderMatches(false); renderAlerts(); tickDates();
  });
  await p.waitForTimeout(1600);   // the new content has risen into place
  const bad = await p.evaluate(() => {
    // an occasion turning over at that moment: measure where it settles
    document.getAnimations().forEach(a => { try { if (a.effect.getTiming().iterations !== Infinity) a.finish(); } catch (e) {} });
    const out = [];
    document.querySelectorAll('.week,.v2-prayers,.v2-weather,.dates>div,.clockwrap,.v2-kids,.alerts,.bottom>*').forEach(box => {
      const B = box.getBoundingClientRect();
      if (getComputedStyle(box).display === 'none' || !B.width) return;
      box.querySelectorAll('*').forEach(e => {
        const cs = getComputedStyle(e), R = e.getBoundingClientRect();
        if (cs.display === 'none' || cs.visibility === 'hidden' || (e.closest('svg') && e.tagName !== 'svg') || !R.width || !R.height) return;
        if (R.left < B.left - 1 || R.right > B.right + 1 || R.top < B.top - 1 || R.bottom > B.bottom + 1)
          out.push((box.id || box.className) + ' › ' + (e.textContent || e.tagName).trim().slice(0, 25));
      });
    });
    return out;
  });
  check(`${width}x${height}: every box keeps its content inside it`, !errors.length && !bad.length, bad.slice(0, 5).join(' | ') + errors.join('; '));
  const w = await p.evaluate(() => { const f = document.querySelector('.clock-face').getBoundingClientRect(), b = document.querySelector('.clockwrap').getBoundingClientRect();
    return { round: Math.abs(f.width - f.height) < 2, inside: f.left >= b.left - 1 && f.right <= b.right + 1 && f.top >= b.top - 1 && f.bottom <= b.bottom + 1, d: Math.round(f.width) }; });
  check(`${width}x${height}: the watch is round and inside its box`, w.round && w.inside && w.d > 0.2 * height, JSON.stringify(w));
  await ctx.close();
}

// ---- a finished game's score stays up for a day, even when the API has already moved on
{
  const ctx = await browser.newContext({ viewport: { width: 1920, height: 1080 } });
  await ctx.route(/^https?:/, r => r.abort());
  const p = await ctx.newPage();
  await p.goto(page('tv.html') + '#teams=136013&panel=verse');
  await p.waitForTimeout(800);
  // the fixture row: home name (goals) × (goals) away name
  const score = t => /الهلال\D*\(٢\)\D*\(١\)\D*النصر/.test(t);
  const slot = () => p.evaluate(() => matchCard().join(' ').replace(/<[^>]+>/g, ' ').replace(/\s+/g, ' ').trim());
  const game = (id, hoursAgo, score, status) => `({idEvent:'${id}', strHomeTeam:'Al-Hilal', strAwayTeam:'Al-Nassr',
    strTimestamp:new Date(Date.now() - ${hoursAgo} * 36e5).toISOString(), intHomeScore:${score ? score[0] : null},
    intAwayScore:${score ? score[1] : null}, strStatus:'${status}'})`;

  await p.evaluate(`MATCHES['136013'] = ${game('g1', 1.8, [2, 1], 'FT')}; renderMatches();`);
  let t = await slot();
  check('whistle blown: final score at once, not «مباشر»', t.includes('انتهت') && score(t) && !t.includes('مباشر'), t);
  const marks = await p.evaluate(() => { const d = document.createElement('div'); d.innerHTML = matchCard().join('');
    return [d.querySelector('.fx-n.h.win') && d.querySelector('.fx-n.h.win').textContent, d.querySelector('.fx-n.a.lose') && d.querySelector('.fx-n.a.lose').textContent]; });
  check('the winner stands out, the loser steps back', marks[0] === 'الهلال' && marks[1] === 'النصر', JSON.stringify(marks));

  await p.evaluate(`keepResult('136013', MATCHES['136013']); MATCHES['136013'] = ${game('g2', -72, null, 'NS')};
                    RESULTS = {}; renderMatches();`);
  t = await slot();
  check('API moved to the next fixture: score still shown', t.includes('انتهت') && score(t), t);

  await p.reload();
  await p.waitForTimeout(800);
  await p.evaluate(`MATCHES['136013'] = ${game('g2', -72, null, 'NS')}; renderMatches();`);
  t = await slot();
  check('after a restart the same day: score still shown', score(t), t);

  await p.evaluate(`KEPT['136013'] = [${game('g1', 27, [2, 1], 'FT')}]; renderMatches();`);
  t = await slot();
  check('a day later: score gone, next fixture shown', !score(t) && /الهلال \(–\) × \(–\) النصر/.test(t), t);
  await ctx.close();
}

await browser.close();
console.log(failed ? `\n${failed} failed` : '\nall page tests passed');
process.exit(failed ? 1 : 0);
