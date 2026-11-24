// Full functional test suite for Grid Gaming Lounge v2.0 (web layer, real Chromium).
const { chromium } = require('playwright');
const fs = require('fs');
const URL = 'file://' + require('path').resolve(__dirname, '../../assets/index.html');
const OUT = require('os').tmpdir() + '/';
const sleep = ms => new Promise(r => setTimeout(r, ms));
const results = []; let section = '';
const ok = (name, cond, detail = '') => { results.push({ section, name, pass: !!cond, detail: cond ? '' : String(detail) }); };
const charge = (m, h, f) => Math.floor(m / 60) * f + Math.floor((m % 60) / 30) * h;
const RATES = { steering: { normal: [70, 120], gta: [0, 0] }, ps: { normal: [[40, 80], [60, 120], [75, 150], [90, 180]], gta: [[50, 100], [70, 140], [85, 170], [100, 200]] } };

function seedScript({ native = true, seed = true } = {}) {
  return `(() => {
    window.__calls = [];
    ${native ? `window.AndroidBridge = {
      loadState: () => localStorage.getItem('__native') || '',
      saveState: j => { localStorage.setItem('__native', j); return true; },
      syncSessions: j => __calls.push(['sync', j]),
      exportBackup: (j, n) => { __calls.push(['export', n, j.length]); return 'Saved to Download/GridGamingLounge/' + n; },
      saveFile: (n, m, t) => { __calls.push(['saveFile', n, m, t]); return 'Saved'; },
      shareText: t => __calls.push(['share', t]), openUrl: u => __calls.push(['url', u]), printHtml: (h, n) => __calls.push(['print', n, h]),
      backupNow: () => 'Auto-backup updated',
      getSettings: () => JSON.stringify({ orientation: localStorage.getItem('__orient') || 'auto', keepOn: localStorage.getItem('__keep') !== 'false', notifications: true, batteryUnrestricted: false, exactAlarms: true, lastAutoBackup: '', backupLocation: 'Download/GridGamingLounge' }),
      setOrientation: m => { localStorage.setItem('__orient', m); __calls.push(['orient', m]); },
      setKeepScreenOn: v => { localStorage.setItem('__keep', String(v)); __calls.push(['keep', v]); },
      openBatterySettings: () => __calls.push(['battery']), openNotificationSettings: () => __calls.push(['notif']), openAppInfo: () => __calls.push(['appinfo'])
    };` : ''}
    ${seed ? `if (!localStorage.getItem('__seeded')) { localStorage.setItem('__seeded', '1');
      const now = Date.now(), names = ["Steering Simulator","PS Screen 1","PS Screen 2","PS Screen 3","PS Screen 4","PS Screen 5"];
      const R = ${JSON.stringify(RATES)};
      const ses = (m, left, x) => ({ game: 'normal', controllers: 2, customer: '', startedAt: now - (m * 60000 - left), endsAt: now + left, initialMinutes: m, durationMinutes: m, rate30: 60, rate60: 100, alert5: left < 300000, alert15: left < 15000, expired: left < 0, ...x });
      const st = { rates: { steering: { normal: { half: R.steering.normal[0], hour: R.steering.normal[1] }, gta: { half: 0, hour: 0 } },
        ps: Array.from({ length: 5 }, () => ({ normal: R.ps.normal.map(([h, o]) => ({ half: h, hour: o })), gta: R.ps.gta.map(([h, o]) => ({ half: h, hour: o })) })) },
        stations: names.map(n => ({ name: n, session: null })), records: [], entries: [] };
      st.stations[1].session = ses(90, 60 * 60000, { customer: 'Kiran' });
      st.stations[2].session = ses(60, -125000, { game: 'gta', controllers: 4 });
      st.stations[5].session = ses(30, 4000, {});
      localStorage.setItem('gridGamingLounge_v1', JSON.stringify(st)); }` : ''}
  })();`;
}
async function newPage(browser, opts = {}) {
  const ctx = await browser.newContext({ viewport: opts.viewport || { width: 412, height: 915 }, acceptDownloads: true });
  const p = await ctx.newPage();
  p.errs = [];
  p.on('pageerror', e => p.errs.push('PAGEERROR ' + e.message));
  p.on('console', m => { if (m.type() === 'error') p.errs.push('CONSOLE ' + m.text()); });
  p.on('dialog', d => { p.errs.push('NATIVE DIALOG ' + d.message()); d.accept(); });
  await p.addInitScript(seedScript(opts));
  await p.goto(URL); await sleep(400);
  return p;
}
const last = p => p.locator('.sheet-wrap').last();
const toasts = p => p.textContent('#toasts');
const ev = (p, f, a) => p.evaluate(f, a);
async function yes(p) { await last(p).locator('[data-ok]').click(); await sleep(250); }
async function no(p) { await last(p).locator('.sheet-foot [data-close]').click(); await sleep(250); }
async function closeAll(p) { await ev(p, () => closeAllSheets()); await sleep(260); }

(async () => {
  const browser = await chromium.launch();
  const p = await newPage(browser);

  // ================= STATIONS =================
  section = 'Stations & timers';
  ok('6 station cards render', (await p.$$('#stations article')).length === 6);
  const statuses = await ev(p, () => state.stations.map(st => status(st.session, Date.now())));
  ok('Statuses: PS1 running, PS2 time-up, PS5 ending, others free', JSON.stringify(statuses) === JSON.stringify(['free', 'running', 'over', 'free', 'free', 'ending']), statuses);
  const t1a = await p.textContent('#t1'); await sleep(1300); const t1b = await p.textContent('#t1');
  ok('Running timer counts down', t1a !== t1b && t1b < t1a, `${t1a} -> ${t1b}`);
  ok('Expired timer shows 00:00:00', (await p.textContent('#t2')) === '00:00:00');
  ok('Expired card caption "Time up at …"', /^Time up at /.test(await p.textContent('#st2 .sc-cap')));
  ok('Expired card button reads "Bill now" (red)', (await p.textContent('#st2 [data-act=bill]')).includes('Bill now') && await p.$('#st2 .btn.danger'));
  // PS5 had 4 s left at seed time -> must stop at zero, not continue
  await sleep(4200);
  ok('Time-up toast shown', (await toasts(p)).includes('Time up — PS Screen 5'), await toasts(p));
  ok('Live session reaching zero stops at 00:00:00', (await p.textContent('#t5')) === '00:00:00', await p.textContent('#t5'));
  await sleep(2100);
  ok('…and stays at 00:00:00 (does not count past zero)', (await p.textContent('#t5')) === '00:00:00');
  ok('Card switched to "Time up" state', (await p.getAttribute('#st5', 'class')).includes('over'));

  ok('Time-up recorded in activity log', await ev(p, () => state.log.some(l => l.text === 'Time up on PS Screen 5')));
  ok('Legend counts update', (await p.textContent('#legend')).includes('2 time up'), await p.textContent('#legend'));

  section = 'Start session';
  await p.click('#st3 [data-act=start]'); await sleep(300);
  let allPrices = true; let detail = '';
  for (const g of ['normal', 'gta']) for (const c of [1, 2, 3, 4]) for (const m of [30, 60, 90, 120, 150, 180, 210, 240]) {
    await last(p).locator(`[data-k=game][data-v=${g}]`).click(); await last(p).locator(`[data-k=ctrl][data-v="${c}"]`).click(); await last(p).locator(`[data-k=mins][data-v="${m}"]`).click();
    const [h, f] = RATES.ps[g][c - 1]; const want = charge(m, h, f);
    const got = Number((await p.textContent('#ssPrice')).replace(/[₹,]/g, ''));
    if (got !== want) { allPrices = false; detail += `${g}/${c}c/${m}m want ${want} got ${got}; `; }
  }
  ok('Start price correct for all 64 PS combinations (game × controllers × duration)', allPrices, detail);
  await closeAll(p);
  await p.click('#st0 [data-act=start]'); await sleep(300);
  ok('Steering has no controller choice', !(await last(p).locator('[data-k=ctrl]').count()));
  await last(p).locator('[data-k=game][data-v=gta]').click();
  ok('Missing rate → Start disabled + warning', await p.$eval('#ssGo', b => b.disabled) && (await p.textContent('#ssRates')).includes('No rate set'));
  await last(p).locator('[data-k=game][data-v=normal]').click(); await last(p).locator('[data-k=mins][data-v="90"]').click();
  ok('Steering normal 1h30 = ₹190', (await p.textContent('#ssPrice')) === '₹190.00', await p.textContent('#ssPrice'));
  await p.fill('#ssName', 'Arjun'); await p.fill('#ssPhone', '+91 98765 43210');
  await p.click('#ssGo'); await sleep(350);
  const s0 = await ev(p, () => state.stations[0].session);
  ok('Session saved with rates, 90 min, customer', s0 && s0.durationMinutes === 90 && s0.rate30 === 70 && s0.rate60 === 120 && s0.customer === 'Arjun');
  ok('Phone normalised and customer created', await ev(p, () => state.customers.length === 1 && state.customers[0].phone === '9876543210'));
  ok('Start toast shown', (await toasts(p)).includes('Steering Simulator started'));
  ok('Native alarms synced on start', await ev(p, () => JSON.parse(__calls.filter(c => c[0] === 'sync').pop()[1]).some(x => x.i === 0 && !x.paused)));
  ok('Last choices remembered for next start', await ev(p, () => drafts[0].mins === 90));

  section = 'Running session controls';
  const endBefore = s0.endsAt;
  await p.click('#st0 [data-act=extend]'); await sleep(250);
  ok('+30 min adds exactly 30 min and bills 2h', await ev(p, e => state.stations[0].session.endsAt - e === 1800000 && state.stations[0].session.durationMinutes === 120, endBefore));
  // extend an expired session: new end = now + 30
  await p.click('#st2 [data-act=extend]'); await sleep(250);
  ok('Extending a time-up session restarts from now (+30)', await ev(p, () => { const s = state.stations[2].session; return Math.abs(s.endsAt - Date.now() - 1800000) < 3000 && s.durationMinutes === 90 && !s.expired; }));
  await p.click('#st0 .sc-timer'); await sleep(300);
  ok('Tapping a running card opens its control sheet', (await last(p).locator('h3').textContent()) === 'Steering Simulator');
  await last(p).locator('[data-a=ext60]').click(); await sleep(250);
  ok('+1 hour from sheet → 3h booked', await ev(p, () => state.stations[0].session.durationMinutes === 180));
  await last(p).locator('[data-a=pause]').click(); await sleep(250);
  const frozen1 = await p.textContent('#sst0'); await sleep(2100); const frozen2 = await p.textContent('#sst0');
  ok('Pause freezes the timer', frozen1 === frozen2, `${frozen1} vs ${frozen2}`);
  ok('Card shows Paused state + Resume button', (await p.getAttribute('#st0', 'class')).includes('paused') && await p.$('#st0 [data-act=resume]'));
  ok('Native told session is paused (alarms off)', await ev(p, () => JSON.parse(__calls.filter(c => c[0] === 'sync').pop()[1]).find(x => x.i === 0).paused === true));
  const endPaused = await ev(p, () => state.stations[0].session.endsAt);
  await last(p).locator('[data-a=resume]').click(); await sleep(250);
  ok('Resume pushes end time by the paused duration', await ev(p, e => { const d = state.stations[0].session.endsAt - e; return d >= 2000 && d < 5000; }, endPaused));
  ok('Billing unaffected by pause (still 3h booked)', await ev(p, () => durationCharge(state.stations[0].session) === 360));
  await closeAll(p);
  await p.click('#st5 .sc-timer'); await sleep(300);
  ok('Pause disabled when time is up', await last(p).locator('[data-a=pause]').isDisabled());
  await closeAll(p);

  section = 'Snacks & stock';
  await p.click('.tab[data-view=more]'); await p.click('[data-page=inventory]');
  for (const [n, pr, stk] of [['Cold drink', '40', '3'], ['Chips', '20', ''], ['Coffee', '30', '10']]) {
    await p.click('#addProdBtn'); await p.fill('#pdName', n); await p.fill('#pdPrice', pr); await p.fill('#pdStock', stk); await p.click('#pdGo'); await sleep(250);
  }
  ok('3 items added (stock tracked / untracked)', await ev(p, () => state.products.map(x => x.stock).join() === '3,,10'));
  await p.click('#addProdBtn'); await p.click('#pdGo'); await sleep(200);
  ok('Item without name/price rejected', (await toasts(p)).includes('Enter a name'));
  await closeAll(p);
  await p.click('[data-restock] >> nth=1'); await p.fill('#rsQty', '5'); await p.fill('#rsCost', '100'); await p.click('#rsGo'); await sleep(250);
  ok('Restock adds stock and records cost as expense', await ev(p, () => state.products[2].stock === 15 && state.entries.some(e => e.category === 'Snacks stock' && e.amount === 100)));
  await p.click('[data-edit] >> nth=1'); await p.fill('#pdPrice', '25'); await p.click('#pdGo'); await sleep(200);
  ok('Edit item price', await ev(p, () => state.products[1].price === 25));
  await p.click('#pg-inventory [data-back]'); await p.click('.tab[data-view=stations]');
  await p.click('#st1 .sc-timer'); await sleep(300);
  await last(p).locator('[data-a=snack]').click(); await sleep(300);
  for (let k = 0; k < 4; k++) { await last(p).locator('.prod').nth(0).click(); await sleep(60); }
  ok('Adding beyond stock warns', (await toasts(p)).includes('out of stock'));
  await last(p).locator('[data-ix="0"][data-d="-1"]').click(); await sleep(100);
  await last(p).locator('.prod').nth(1).click();
  ok('Picker footer shows count & total', (await p.textContent('#ppDone')) === 'Done · 4 items · ₹145.00', await p.textContent('#ppDone'));
  await p.click('#ppDone'); await sleep(300);
  ok('Snacks shown on session sheet & running bill', (await last(p).locator('.kv.total b').textContent()) === '₹305.00', await last(p).locator('.kv.total b').textContent());
  ok('Card shows snack count', (await p.textContent('#st1 .sc-meta')).includes('4 snacks'));
  await closeAll(p);

  section = 'Billing & checkout';
  await p.click('#st1 [data-act=bill]'); await sleep(350);
  ok('Early finish offers "Bill played" option', await last(p).locator('[data-k=basis]').count() === 2);
  await p.fill('#coDisc', '9999');
  ok('₹ discount capped at subtotal', (await p.textContent('#coTotals .kv.total b')) === '₹0.00');
  await last(p).locator('[data-k=discType][data-v=pct]').click(); await p.fill('#coDisc', '150'); await sleep(50);
  ok('% discount capped at 100%', (await p.textContent('#coTotals .kv.total b')) === '₹0.00');
  await p.fill('#coDisc', '10'); await sleep(50);
  ok('10% off ₹305 = ₹274.50', (await p.textContent('#coTotals .kv.total b')) === '₹274.50', await p.textContent('#coTotals .kv.total b'));
  await last(p).locator('[data-k=basis][data-v=played]').click(); await sleep(50);
  // independent expectation: played minutes rounded UP to the next 30-minute block (min 30), PS1 rates 60/30m, 100/hr
  const startedAt = await ev(p, () => state.stations[1].session.startedAt);
  const elapsedMin = Math.ceil((Date.now() - startedAt) / 60000), MIN = Math.max(30, Math.ceil(elapsedMin / 30) * 30), PC = charge(MIN, 60, 100);
  const SUB = PC + 145, T = Math.round(SUB * 0.9 * 100) / 100, DISC = Math.round((SUB - T) * 100) / 100;
  const inr = n => '₹' + n.toLocaleString('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
  const inrS = n => (n < 0 ? '−₹' : '₹') + Math.abs(n).toLocaleString('en-IN', { maximumFractionDigits: 2 });
  ok(`Played basis bills played time (${elapsedMin} min played → ${MIN} min → ${inr(PC)} + snacks − 10% = ${inr(T)})`, (await p.textContent('#coTotals .kv.total b')) === inr(T), await p.textContent('#coTotals .kv.total b'));
  ok('Wallet disabled without a customer', await last(p).locator('[data-k=method][data-v=wallet]').isDisabled());
  ok('Pay later disabled without phone', await last(p).locator('[data-k=method][data-v=credit]').isDisabled());
  await p.fill('#coPhone', '9000011111'); await sleep(80);
  ok('Pay later enabled once phone entered', !(await last(p).locator('[data-k=method][data-v=credit]').isDisabled()));
  await last(p).locator('[data-k=method][data-v=split]').click(); await p.fill('#coCash', '500'); await sleep(50);
  ok('Split: cash above total leaves UPI ₹0', (await p.textContent('#coUpi')) === '₹0.00');
  await p.fill('#coCash', '100'); await sleep(50);
  ok('Split: UPI = total − cash', (await p.textContent('#coUpi')) === inr(T - 100), await p.textContent('#coUpi'));
  await p.click('#coDone'); await sleep(450);
  const r1 = await ev(p, () => state.records[state.records.length - 1]);
  ok('Bill saved with invoice #1001', r1.invoiceNo === 1001 && r1.amount === T && r1.basis === 'played' && r1.minutes === MIN);
  ok('Split stored as cash 100 + UPI remainder', r1.pay.cash === 100 && Math.abs(r1.pay.upi - (T - 100)) < 0.001, JSON.stringify(r1.pay));
  ok('Discount stored (10%)', Math.abs(r1.discount - DISC) < 0.001, r1.discount);
  ok('Stock reduced (cold drink 3 → 0)', await ev(p, () => state.products[0].stock === 0));
  ok('Customer created from billing phone with visit+spend', await ev(p, (t) => { const c = state.customers.find(c => c.phone === '9000011111'); return c && c.name === 'Kiran' && c.visits === 1 && c.spent === t; }, T));
  ok('Station freed after billing', await ev(p, () => state.stations[1].session === null) && (await p.getAttribute('#st1', 'class')).includes('free'));
  ok('Receipt opened', (await last(p).locator('h3').textContent()) === 'Receipt #1001');
  await last(p).locator('#rcWa').click(); await last(p).locator('#rcShare').click(); await last(p).locator('#rcPrint').click();
  const urls = await ev(p, () => __calls.filter(c => ['url', 'share', 'print'].includes(c[0])));
  ok('WhatsApp opens wa.me/91… with receipt text', urls[0][1].startsWith('https://wa.me/919000011111?text=') && decodeURIComponent(urls[0][1]).includes('*Total: ' + inr(T) + '*'));
  ok('Share sends receipt text', urls[1][1].includes('Invoice #1001'));
  ok('Print sends printable HTML', urls[2][1] === 'Receipt-1001' && urls[2][2].includes('<!doctype html>') && urls[2][2].includes(inr(T)));
  await closeAll(p);
  // pay later on the time-up station
  await p.click('#st5 [data-act=bill]'); await sleep(350);
  ok('Time-up session has no "played" option', await last(p).locator('[data-k=basis]').count() === 0);
  await p.fill('#coName', 'Kiran'); await p.fill('#coPhone', '9000011111'); await sleep(80);
  await last(p).locator('[data-k=method][data-v=credit]').click(); await p.click('#coDone'); await sleep(400);
  ok('Pay later adds to customer due', await ev(p, () => state.customers.find(c => c.phone === '9000011111').due === 60));
  ok('Invoice numbers increment (#1002)', await ev(p, () => state.records[state.records.length - 1].invoiceNo === 1002));
  await closeAll(p);
  // quick sale validation & wallet
  await p.click('#quickSaleBtn'); await sleep(600); await closeAll(p);
  await p.click('#quickSaleBtn'); await sleep(300); await ev(p, () => sheetStack[sheetStack.length - 1].close()); await sleep(260);
  await p.click('#coDone'); await sleep(150);
  ok('Quick sale with no items is blocked', (await toasts(p)).includes('Add at least one item'));
  await closeAll(p);

  section = 'Customers';
  await p.click('.tab[data-view=customers]'); await sleep(200);
  ok('Customer list shows 2', (await p.$$('#custList [data-cid]')).length === 2);
  await p.fill('#cSearch', '9000'); await sleep(80);
  ok('Search by phone', (await p.$$('#custList [data-cid]')).length === 1);
  await p.fill('#cSearch', 'arj'); await sleep(80);
  ok('Search by name', (await p.textContent('#custList')).includes('Arjun'));
  await p.fill('#cSearch', ''); await p.click('[data-f=due]'); await sleep(80);
  ok('"With dues" filter', (await p.$$('#custList [data-cid]')).length === 1);
  ok('Dues KPI = ₹60', (await p.textContent('#cDue')) === '₹60');
  await p.click('#custList [data-cid]'); await sleep(300);
  await last(p).locator('[data-a=due]').click(); await sleep(250); await p.fill('#miAmt', '100'); await p.click('#miGo'); await sleep(150);
  ok('Collecting more than due is rejected', (await toasts(p)).includes('Due is only'));
  await p.fill('#miAmt', '60'); await last(p).locator('[data-k=method][data-v=upi]').click(); await p.click('#miGo'); await sleep(300);
  ok('Due collected → 0, logged as collection (not sales)', await ev(p, () => state.customers.find(c => c.phone === '9000011111').due === 0 && state.collections.length === 1 && state.collections[0].method === 'upi'));
  await last(p).locator('[data-a=topup]').click(); await sleep(250); await p.click('[data-amt="500"]'); await p.click('#miGo'); await sleep(300);
  ok('Wallet top-up ₹500', await ev(p, () => state.customers.find(c => c.phone === '9000011111').wallet === 500));
  await last(p).locator('.sheet-foot [data-a=del]').click(); await sleep(200);
  ok('Delete blocked while wallet has balance', (await toasts(p)).includes('Clear the due and wallet'));
  await last(p).locator('[data-a=wa]').click();
  ok('Customer WhatsApp opens', await ev(p, () => __calls.filter(c => c[0] === 'url').pop()[1].startsWith('https://wa.me/919000011111')));
  await closeAll(p);
  await p.click('[data-f=all]'); await p.click('#addCustBtn'); await sleep(200); await p.fill('#ecName', 'Dup'); await p.fill('#ecPhone', '9000011111'); await p.click('#ecGo'); await sleep(150);
  ok('Duplicate phone rejected', (await toasts(p)).includes('Another customer has this phone'));
  await p.fill('#ecPhone', '9111122222'); await p.click('#ecGo'); await sleep(250);
  ok('New customer added manually', await ev(p, () => state.customers.some(c => c.name === 'Dup')));
  await p.click('#custList [data-cid] >> nth=0'); await sleep(250);
  await last(p).locator('.sheet-foot [data-a=edit]').click(); await sleep(200); await p.fill('#ecName', 'Dup Edited'); await p.click('#ecGo'); await sleep(250);
  ok('Edit customer updates name', await ev(p, () => state.customers.some(c => c.name === 'Dup Edited')));
  await last(p).locator('.sheet-foot [data-a=del]').click(); await sleep(250); await yes(p); await sleep(200);
  ok('Delete customer with zero balance', await ev(p, () => !state.customers.some(c => c.name === 'Dup Edited')));
  await closeAll(p);
  // wallet payment via quick sale
  await p.click('.tab[data-view=stations]'); await p.click('#quickSaleBtn'); await sleep(600);
  for (let k = 0; k < 2; k++) await last(p).locator('.prod').nth(2).click();
  await p.click('#ppDone'); await sleep(250);
  await p.fill('#coName', 'Kiran · 9000011111'); await sleep(80);
  ok('Picking "Name · phone" suggestion fills both fields', (await p.inputValue('#coName')) === 'Kiran' && (await p.inputValue('#coPhone')) === '9000011111');
  await last(p).locator('[data-k=method][data-v=wallet]').click(); await p.click('#coDone'); await sleep(400);
  ok('Wallet pays counter sale (500 − 60 = 440)', await ev(p, () => state.customers.find(c => c.phone === '9000011111').wallet === 440));
  ok('Counter sale recorded with no gaming time', await ev(p, () => { const r = state.records[state.records.length - 1]; return r.station === 'Counter sale' && r.timeAmount === 0 && r.itemsAmount === 60; }));
  await closeAll(p);

  section = 'Bookings';
  await p.click('.tab[data-view=bookings]'); await sleep(150);
  await p.click('#newBookingBtn'); await sleep(250); await p.click('#bkSave'); await sleep(150);
  ok('Booking without name/phone rejected', (await toasts(p)).includes('Enter a name or phone'));
  const at = d => { const t = new Date(Date.now() + d * 60000); return String(t.getHours()).padStart(2, '0') + ':' + String(t.getMinutes()).padStart(2, '0'); };
  await p.fill('#bkName', 'Sneha'); await p.fill('#bkPhone', '9123456780'); await p.fill('#bkTime', at(20)); await p.selectOption('#bkSt', '4'); await p.click('#bkSave'); await sleep(250);
  await p.click('#newBookingBtn'); await sleep(250); await p.fill('#bkName', 'Ravi'); await p.fill('#bkTime', at(50)); await p.selectOption('#bkSt', '4'); await p.dispatchEvent('#bkSt', 'change'); await sleep(80);
  ok('Overlapping booking warning', (await p.textContent('#bkWarn')).includes('Overlaps with Sneha'));
  await p.selectOption('#bkSt', 'ps'); await p.click('#bkSave'); await sleep(250);
  ok('2 bookings listed', (await p.$$('#bookList [data-bid]')).length === 2);
  ok('Tab badge shows 2', (await p.textContent('#bookDot')) === '2');
  await p.click('.tab[data-view=stations]'); await sleep(150);
  ok('Station card shows "Booked" pill', (await p.textContent('#st4')).includes('Booked'));
  ok('Upcoming strip on Stations', (await p.textContent('#bookStrip')).includes('Sneha'));
  await p.click('#st4 [data-act=start]'); await sleep(250);
  ok('Starting a booked station warns about overlap', (await p.textContent('#ssWarn')).includes('booked at'));
  await closeAll(p);
  await p.click('.tab[data-view=bookings]'); await p.click('[data-bstart] >> nth=1'); await sleep(350);
  ok('"Any PS" booking starts on first free PS screen', (await last(p).locator('h3').textContent()) === 'Start PS Screen 1');
  await p.click('#ssGo'); await sleep(300);
  ok('Booking marked played after start', await ev(p, () => state.bookings.find(b => b.name === 'Ravi').status === 'done'));
  await p.click('.tab[data-view=bookings]'); await p.click('#bookList [data-bid] >> nth=0'); await sleep(250);
  await p.click('#bkNoshow'); await sleep(200); await yes(p);
  ok('Mark no-show', await ev(p, () => state.bookings.find(b => b.name === 'Sneha').status === 'noshow'));
  await p.click('#bNext'); await sleep(100);
  ok('Next-day view is empty', (await p.textContent('#bookList')).includes('No bookings for tomorrow'));
  await p.click('#bPrev');

  section = 'Money: day book & reports';
  await p.click('.tab[data-view=money]'); await sleep(150);
  await p.click('#eSave'); await sleep(150);
  ok('Expense without amount rejected', (await toasts(p)).includes('Enter a valid amount'));
  await p.click('#eQuick [data-c=Electricity]'); await p.fill('#eAmt', '250'); await p.click('#eSave'); await sleep(200);
  await p.click('#eType [data-v=income]'); await p.fill('#eAmt', '75'); await p.fill('#eCat', 'Repair'); await p.click('#eSave'); await sleep(200);
  const sum = await ev(p, () => summary(localDay()));
  const want = { sales: T + 60 + 60, inc: 75, exp: 100 + 250 };
  ok('Day totals: sales/income/expenses', sum.sales === want.sales && sum.inc === want.inc && sum.exp === want.exp, JSON.stringify([sum.sales, sum.inc, sum.exp]));
  ok('KPI net = sales + income − expenses', (await p.textContent('#aNet')) === inrS(T + 120 + 75 - 350), await p.textContent('#aNet'));
  ok('Cash drawer: cash bills 100 + cash top-up 500 + income 75 − expenses 350 = ₹325', (await p.textContent('#drawer')).includes('₹325.00'), await p.textContent('#drawer'));
  ok('Ledger lists bills, entries and collections', (await p.$$('#ledger .li')).length === 3 + 3 + 2);
  await p.click('#ledger [data-rid] >> nth=0'); await sleep(250);
  ok('Tapping a bill reopens its receipt', (await last(p).locator('h3').textContent()).startsWith('Receipt #'));
  await closeAll(p);
  await p.click('#ledger [data-del] >> nth=0'); await sleep(200); await no(p);
  ok('Delete entry → Cancel keeps it', await ev(p, () => state.entries.length === 3));
  await p.click('#ledger [data-del] >> nth=0'); await sleep(200); await yes(p);
  ok('Delete entry → Confirm removes it', await ev(p, () => state.entries.length === 2));
  ok('"Next day" disabled on today', await p.$eval('#dNext', b => b.disabled));
  await p.click('#dPrev'); await sleep(100);
  await p.fill('#eAmt', '40'); await p.fill('#eCat', 'Old'); await p.click('#eSave'); await sleep(200);
  ok('Entry on a past day is stored on that day', await ev(p, () => state.entries.some(e => e.category === 'Old' && e.day === shiftDay(localDay(), -1))));
  await p.click('#moneySeg [data-m=reports]'); await sleep(200);
  let rangesOk = true;
  for (const v of ['today', 'yesterday', '7', '30', 'month', 'lastmonth']) { await p.selectOption('#rRange', v); await sleep(80); if (!(await p.textContent('#rSales')).startsWith('₹')) rangesOk = false; }
  ok('All 6 report ranges render', rangesOk && p.errs.length === 0, p.errs.join(' | '));
  await p.selectOption('#rRange', 'today'); await sleep(80);
  ok('Report total sales matches day book', (await p.textContent('#rSales')) === inrS(T + 120), await p.textContent('#rSales'));
  ok('Report split: gaming vs snacks', (await p.textContent('#rGaming')) === inrS(PC + 60) && (await p.textContent('#rSnacks')) === '₹205', [await p.textContent('#rGaming'), await p.textContent('#rSnacks')]);
  ok('Payment breakdown lists Cash, UPI, Wallet, Pay later', ['Cash', 'UPI', 'Wallet', 'Pay later'].every(async x => true) && /Cash.*UPI.*Wallet.*Pay later/s.test(await p.textContent('#rPay')), await p.textContent('#rPay'));
  ok('Top snacks lists items', (await p.textContent('#rSnackList')).includes('Cold drink'));
  await p.click('#csvBtn'); await sleep(150);
  const csv = await ev(p, () => __calls.filter(c => c[0] === 'saveFile').pop());
  const lines = csv[3].replace(/^﻿/, '').split('\r\n');
  ok('CSV export: header + 3 bills + 2 entries + 2 collections', lines.length === 8 && lines[0].startsWith('Type,Date') && csv[2] === 'text/csv', lines.length);
  ok('CSV quoting safe & has UTF-8 BOM', csv[3].charCodeAt(0) === 0xfeff);

  section = 'More: rates, shop, settings, log';
  await p.click('.tab[data-view=more]'); await p.click('[data-page=rates]'); await sleep(150);
  await p.fill('#rPs input[data-r="p.normal.0.hour"]', '95');
  ok('Rate edit marks unsaved', (await p.textContent('#rState')) === 'Unsaved changes');
  await p.click('.tab[data-view=stations]'); await sleep(250);
  ok('Leaving with unsaved rates asks first', (await last(p).locator('h3').textContent()) === 'Unsaved rate changes');
  await no(p);
  ok('"Stay" keeps you on Rates with the edit', (await p.inputValue('#rPs input[data-r="p.normal.0.hour"]')) === '95');
  await p.click('#rCopy'); await sleep(200); await yes(p);
  await p.click('#rSave'); await sleep(200);
  ok('Copy-to-all + Save applies to all 5 PS screens', await ev(p, () => state.rates.ps.every(x => x.normal[0].hour === 95)));
  ok('Running sessions keep old rates', await ev(p, () => state.stations[1].session.rate60 === 80));
  await p.click('#pg-rates [data-back]'); await p.click('[data-page=shop]');
  await p.fill('#shName', 'Pixel Arena'); await p.fill('#shGst', '18'); await p.fill('#shUpi', 'pixel@upi'); await p.click('#shSave'); await sleep(150);
  ok('Shop name updates header and logo', (await p.textContent('#shopTitle')) === 'Pixel Arena' && (await p.textContent('#logoL')) === 'P');
  await p.click('#pg-shop [data-back]'); await p.click('[data-page=app]'); await sleep(150);
  await p.click('#rotOpts [data-v=landscape]'); await p.click('#keepOn'); await p.click('#bgStatus [data-fix]');
  const nat = await ev(p, () => __calls.filter(c => ['orient', 'keep', 'battery'].includes(c[0])).map(c => c.join(':')));
  ok('Rotation, keep-screen-on and battery fix call Android', nat.join() === 'orient:landscape,keep:false,battery', nat);
  await p.click('#pg-app [data-back]'); await p.click('[data-page=activity]'); await sleep(100);
  await p.fill('#logSearch', 'Bill #'); await sleep(80);
  ok('Activity log searchable (3 bills)', (await p.$$('#logList .li')).length === 3, (await p.$$('#logList .li')).length);
  await p.click('#pg-activity [data-back]');
  // GST on receipts
  await p.click('.tab[data-view=money]'); await p.click('#moneySeg [data-m=day]'); await p.click('#dToday').catch(() => {}); await ev(p, () => { viewDay = localDay(); renderDay(); });
  await p.click('#ledger [data-rid] >> nth=0'); await sleep(250);
  const rc = await last(p).locator('.rcpt').textContent();
  ok('Receipt shows shop name, GST 18% and UPI', rc.includes('Pixel Arena') && rc.includes('Incl. GST 18%') && rc.includes('pixel@upi'), rc);
  await closeAll(p);

  section = 'Back button';
  await p.click('.tab[data-view=more]'); await p.click('[data-page=backup]');
  await p.click('#exportBtn'); await sleep(100);
  ok('Download backup goes to Android Downloads', await ev(p, () => __calls.some(c => c[0] === 'export')));
  await p.click('#clearBtn'); await sleep(250);
  ok('Back closes an open sheet first', await ev(p, () => handleBack()) && await ev(p, () => sheetStack.length === 0));
  ok('Back leaves a More sub-page', await ev(p, () => handleBack()) && await ev(p, () => curPage === null));
  ok('Back returns to Stations tab', await ev(p, () => handleBack()) && await ev(p, () => curTab === 'stations'));
  ok('Back on Stations lets Android minimise app', (await ev(p, () => handleBack())) === false);

  section = 'Saving & recovery';
  const SNAP = () => JSON.stringify({ r: state.records.length, c: state.customers.length, p: state.products.length, b: state.bookings.length, e: state.entries.length, s: state.stations.map(x => x.session ? x.session.endsAt : 0) });
  const before = await ev(p, SNAP);
  await p.reload(); await sleep(400);
  const afterRestart = await ev(p, SNAP);
  ok('All data survives an app restart', afterRestart === before, before + ' vs ' + afterRestart);
  ok('Running sessions survive restart (same end times)', afterRestart === before);
  await ev(p, () => localStorage.removeItem('gridGamingLounge_v1')); await p.reload(); await sleep(400);
  const afterWipe = await ev(p, SNAP);
  ok('Recovers from the Android copy if browser storage is wiped', afterWipe === before, before + ' vs ' + afterWipe);
  ok('No errors in the whole session', p.errs.length === 0, p.errs.join(' | '));
  const fullState = await ev(p, () => JSON.stringify(state));

  // ---------- Browser-only mode (no Android bridge): backup/restore/clear ----------
  section = 'Backup, restore & old data';
  const q = await newPage(browser, { native: false, seed: false });
  await q.evaluate(s => { localStorage.setItem('gridGamingLounge_v1', s); }, fullState); await q.reload(); await sleep(300);
  await q.click('.tab[data-view=more]'); await q.click('[data-page=backup]');
  const [dl] = await Promise.all([q.waitForEvent('download'), q.click('#exportBtn')]);
  const bpath = OUT + 'backup-test.json'; await dl.saveAs(bpath);
  const bk = JSON.parse(fs.readFileSync(bpath, 'utf8'));
  ok('Backup file contains everything', bk.data.records.length === JSON.parse(fullState).records.length && bk.data.customers.length && bk.data.products.length && bk.data.bookings.length);
  await q.click('#clearBtn'); await sleep(250); await q.locator('.sheet-wrap').last().locator('[data-ok]').click(); await sleep(250);
  ok('Clear all data empties the app', await q.evaluate(() => state.records.length === 0 && state.customers.length === 0 && state.stations.every(s => !s.session)));
  fs.writeFileSync(OUT + 'bad.json', '{"hello":1}');
  await q.setInputFiles('#importFile', OUT + 'bad.json'); await sleep(250);
  ok('Invalid backup file rejected', (await q.textContent('#toasts')).includes('not a Grid Gaming Lounge backup'));
  await q.setInputFiles('#importFile', bpath); await sleep(250); await q.locator('.sheet-wrap').last().locator('[data-ok]').click(); await sleep(300);
  ok('Restore brings everything back', await q.evaluate(n => state.records.length === n && state.customers.length > 0, JSON.parse(fullState).records.length));
  // v1.0 backup (flat hourly rates, no new fields)
  const v10 = { app: 'Grid Gaming Lounge', version: 1, data: { rates: { steering: { normal: 120, gta: 160 }, ps: Array.from({ length: 5 }, () => ({ normal: [80, 120, 150, 180], gta: [100, 140, 170, 200] })) }, stations: ['Steering Simulator', 'PS Screen 1', 'PS Screen 2', 'PS Screen 3', 'PS Screen 4', 'PS Screen 5'].map(n => ({ name: n, session: null })), records: [{ id: 'x', station: 'PS Screen 1', game: 'normal', controllers: 1, startedAt: Date.now() - 3600000, endedAt: Date.now(), amount: 80, day: '2026-11-20' }], entries: [] } };
  fs.writeFileSync(OUT + 'v10.json', JSON.stringify(v10));
  await q.setInputFiles('#importFile', OUT + 'v10.json'); await sleep(250); await q.locator('.sheet-wrap').last().locator('[data-ok]').click(); await sleep(300);
  ok('Version 1.0 backup imports and rates convert', await q.evaluate(() => state.rates.steering.normal.hour === 120 && state.rates.steering.normal.half === 60 && state.rates.ps[0].normal[1].hour === 120 && Array.isArray(state.products)));
  await q.click('.tab[data-view=money]'); await q.click('#moneySeg [data-m=reports]'); await q.selectOption('#rRange', '30'); await sleep(150);
  ok('Old bills (no payment method) show in reports', (await q.textContent('#rPay')).includes('Not recorded'));
  const [dl2] = await Promise.all([q.waitForEvent('download'), q.click('#csvBtn')]);
  ok('CSV downloads in browser mode', (await dl2.suggestedFilename()).endsWith('.csv'));
  ok('No errors in browser-only mode', q.errs.length === 0, q.errs.join(' | '));

  // ---------- Layout at every size, every tab ----------
  section = 'Layout (no sideways scrolling)';
  const sizes = [[360, 740, 'small phone'], [412, 915, 'phone'], [915, 412, 'phone landscape'], [800, 1280, 'tablet portrait'], [1280, 800, 'tablet landscape']];
  const L = await newPage(browser, { native: true, seed: false });
  await L.evaluate(s => { localStorage.setItem('gridGamingLounge_v1', s); localStorage.setItem('__native', s); }, fullState); await L.reload(); await sleep(300);
  for (const [w, h, label] of sizes) {
    await L.setViewportSize({ width: w, height: h }); await sleep(120);
    const bad = [];
    for (const tab of ['stations', 'bookings', 'customers', 'money', 'more']) {
      await L.evaluate(t => show(t), tab); await sleep(80);
      const views = tab === 'money' ? ['day', 'reports'] : tab === 'more' ? [null, 'inventory', 'rates', 'shop', 'app', 'backup', 'activity'] : [0];
      for (const v of views) {
        if (tab === 'money') await L.evaluate(m => document.querySelector(`#moneySeg [data-m=${m}]`).click(), v);
        if (tab === 'more') await L.evaluate(pg => showPage(pg), v);
        await sleep(60);
        const ov = await L.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
        if (ov > 1) bad.push(`${tab}${v ? '/' + v : ''}(+${ov}px)`);
      }
      if (tab === 'more') await L.evaluate(() => showPage(null));
    }
    // sheets too
    await L.evaluate(() => show('stations')); await L.evaluate(() => startSheet(3)); await sleep(300);
    const sov = await L.evaluate(() => { const b = document.querySelector('.sheet-body'); return b.scrollWidth - b.clientWidth; });
    if (sov > 1) bad.push('start-sheet');
    await L.evaluate(() => closeAllSheets()); await sleep(260);
    await L.evaluate(() => billSheet({ kind: 'session', i: 1 })); await sleep(300);
    const bov = await L.evaluate(() => { const b = document.querySelector('.sheet-body'); return b.scrollWidth - b.clientWidth; });
    if (bov > 1) bad.push('bill-sheet');
    await L.evaluate(() => closeAllSheets()); await sleep(260);
    ok(`${label} ${w}×${h}: all tabs, pages and sheets fit`, bad.length === 0, bad.join(', '));
  }
  await L.setViewportSize({ width: 360, height: 740 }); await L.evaluate(() => show('stations')); await sleep(200);
  await L.screenshot({ path: OUT + 'layout-360.png' });
  await L.setViewportSize({ width: 915, height: 412 }); await sleep(200);
  await L.screenshot({ path: OUT + 'layout-landscape.png' });
  ok('No errors in layout pass', L.errs.length === 0, L.errs.join(' | '));

  await browser.close();
  // ---------- report ----------
  let cur = '', pass = 0;
  for (const r of results) { if (r.section !== cur) { cur = r.section; console.log('\n== ' + cur); } console.log((r.pass ? '  PASS ' : '  FAIL ') + r.name + (r.pass ? '' : '  →  ' + r.detail)); if (r.pass) pass++; }
  console.log(`\nTOTAL: ${pass}/${results.length} passed`);
  fs.writeFileSync(OUT + 'results.json', JSON.stringify(results, null, 1));
})().catch(e => { console.error('TEST CRASH', e); process.exit(1); });
