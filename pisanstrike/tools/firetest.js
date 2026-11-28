const mineflayer = require('mineflayer');
const { execSync } = require('child_process');
const sleep = ms => new Promise(r => setTimeout(r, ms));
const GUNS = require('./guns.json');
const has = (it, key) => it && JSON.stringify(it.nbt || {}).includes(key);
const rcon = c => execSync(`python3 ../rcon.py ${JSON.stringify(c)}`);
const mk = n => new Promise(r => { const b = mineflayer.createBot({ host: '127.0.0.1', port: 25599, username: n, version: '1.20.1', auth: 'offline' }); b.once('spawn', () => r(b)); });
(async () => {
  const T = await mk('Tester'); await sleep(800); const D = await mk('Dummy'); await sleep(1500);
  let bar = '', live = false;
  T.on('messagestr', (m, pos) => { if (/\d+\/\d+/.test(m) && /❤/.test(m)) bar = m; });
  T.on('messagestr', m => { if (/BAŞLA/.test(m)) live = true; if (/satın alma süresi|Maç bitti|Tur \d+ bitti/.test(m)) live = false; });
  T.chat('/parti kur'); await sleep(400); T.chat('/parti davet Dummy'); await sleep(400); D.chat('/parti kabul Tester'); await sleep(600);
  const click = async (b, key) => { const w = b.currentWindow; const s = w.slots.findIndex((it, i) => i < w.inventoryStart && has(it, key)); await b.clickWindow(s, 0, 0); await sleep(350); };
  let p = new Promise(r => T.once('windowOpen', r)); T.setQuickBarSlot(0); await sleep(200); T.activateItem(); await p;
  await click(T, 'p:mode'); for (let i = 0; i < 2; i++) await click(T, 'p:rounds'); // duello, 13 tur
  await click(T, 'p:start'); if (T.currentWindow) T.closeWindow(T.currentWindow);
  const ammo = () => { const m = bar.match(/(\d+)\/(\d+)/); return m ? +m[1] : null; };
  const waitLive = async () => { for (let i = 0; i < 200 && !live; i++) await sleep(100); };
  const results = [];
  for (const g of GUNS) {
    const ms = 60000 / g.rpm;
    let row = { id: g.id, auto: g.auto, ms: Math.round(ms) };
    for (let attempt = 0; attempt < 3; attempt++) {
      await waitLive(); if (!live) continue;
      rcon('clear Tester');
      // rcon komutu oyuncu olarak calismaz; oyuncu kendi verir
      T.chat(`/strikeadmin ver ${g.id}`); await sleep(700);
      const slot = T.inventory.slots.findIndex((it, i) => i >= 36 && it && it.name === 'iron_horse_armor');
      if (slot < 0) { row.err = 'esya yok'; break; }
      T.setQuickBarSlot(slot - 36); await T.look(0, Math.PI / 2.2, true); await sleep(500);
      const a0 = ammo(); if (a0 == null) { await sleep(500); }
      const start = ammo();
      if (g.auto) {
        const t0 = Date.now(); while (Date.now() - t0 < 2000) { T.activateItem(); await sleep(200); }
        await sleep(400); row.held2s = start - ammo(); row.expect = Math.min(start, Math.floor(2000 / ms) + 1);
      } else {
        const gap = Math.max(300, ms + 60);
        for (let i = 0; i < 4; i++) { T.activateItem(); await sleep(gap); }
        await sleep(300); const after = ammo(); row.slow4 = start - after;
        // hizli tiklama: 120 ms arayla 6 tik
        const s2 = ammo(); for (let i = 0; i < 6; i++) { T.activateItem(); await sleep(120); }
        await sleep(Math.max(500, ms + 100)); row.fast6 = s2 - ammo(); row.fastExpect = Math.min(s2, Math.max(1, Math.floor((720 + 400) / ms)));
      }
      if (!live) { continue; }
      break;
    }
    results.push(row); console.log(JSON.stringify(row));
  }
  T.chat('/ayril'); await sleep(800); T.quit(); D.quit(); await sleep(300); process.exit(0);
})().catch(e => { console.log('HATA', e.stack); process.exit(1); });
