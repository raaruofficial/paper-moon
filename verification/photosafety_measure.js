/**
 * Photosafety P-6 MEASUREMENT harness (bankai #3 MAJOR-5 / item 19:
 * cure decided, re-measurement gates the flip).
 *
 * What this measures: the rate of `.spike` element onsets ("NEW
 * ATH" flash elements) per second while a ride runs, per speed
 * setting, on the most ATH-dense scenario in the shipped datasets —
 * the worst case the bundle can produce. Each spike onset is a
 * full-contrast element flashing in with an animation; the onset
 * rate is the photosafety-relevant unit.
 *
 * What this is NOT: a device measurement. It runs the real bundle
 * in jsdom at jsdom's requestAnimationFrame cadence against wall
 * clock. Treat the numbers as a reproducible instrument reading
 * for comparing builds (before/after a cure), not as a clinical
 * photosensitivity assessment. The ~28 onsets/sec figure quoted in
 * the bankai record was produced by a method not preserved in this
 * repo; this harness is the standing instrument going forward, and
 * any cure attempt must be re-measured with it.
 *
 * This is a measurement, not a gate: it always exits 0 and prints
 * the readings. Run: NODE_PATH=<jsdom> node verification/photosafety_measure.js
 */
const fs = require('fs');
const path = require('path');
const { JSDOM } = require('jsdom');

const html = fs.readFileSync(
  path.join(__dirname, '..', 'app', 'src', 'main', 'assets', 'game', 'index.html'),
  'utf8'
);
const tick = (ms) => new Promise((r) => setTimeout(r, ms));

function makeBridge() {
  const resolve = (win, id, payload) =>
    setTimeout(() => win.__paperMoonWalletResolve(id, JSON.stringify(payload)), 0);
  const bridge = {
    getVersion: () => '2',
    isConnected: () => true,
    getPublicKey: () => 'FakeWallet7777777777777777777777777777777',
    getBoundIdentity: () => 'FakeWallet7777777777777777777777777777777',
    getPriceInfo: () =>
      JSON.stringify({
        itemId: 'day-pass', itemDisplay: 'Day Pass — 24h unlimited rides',
        usdAnchor: '$3.50', railAmount: '3.50 USDC',
        conversionBasis: 'USDC ≈ 1:1 with USD', railOpen: false
      }),
    getEntitlement: () =>
      JSON.stringify({
        bound: true, passActive: true,
        passExpiresAtMs: Date.now() + 3600e3,
        pending: false, rookieClaimed: true, rookieRemaining: 0
      }),
    connectWallet: (id) => { resolve(bridge._win, id, { ok: true, data: {} }); return '{}'; },
    requestSignIn: (id) => { resolve(bridge._win, id, { ok: true, data: {} }); return '{}'; },
    claimRookie: (id) => { resolve(bridge._win, id, { ok: false, error: 'claimed' }); return '{}'; },
    consumeRookieRide: (id) => { resolve(bridge._win, id, { ok: true, data: { rookieRemaining: 0 } }); return '{}'; },
    requestPurchase: (id) => { resolve(bridge._win, id, { ok: false, error: 'rail closed' }); return '{}'; }
  };
  return bridge;
}

async function loadPage(onsets) {
  const bridge = makeBridge();
  const dom = new JSDOM(html, {
    runScripts: 'dangerously',
    pretendToBeVisual: true,
    url: 'file:///android_asset/game/index.html',
    beforeParse(window) {
      window.PaperMoonWallet = bridge;
      bridge._win = window;
      window.HTMLCanvasElement.prototype.getContext = function () {
        return new Proxy({}, { get: (t, k) => (k === 'canvas' ? {} : () => {}), set: () => true });
      };
      window.AudioContext = class {
        createOscillator() { return { connect() {}, start() {}, stop() {}, frequency: {} }; }
        createGain() { return { connect() {}, gain: {} }; }
      };
      window.scrollTo = () => {};
      // Count spike onsets at the DOM boundary: every .spike
      // element spawnSpike appends to <body>.
      const origAppend = window.Node.prototype.appendChild;
      window.Node.prototype.appendChild = function (child) {
        if (child && child.className === 'spike') onsets.push(performance.now());
        return origAppend.call(this, child);
      };
    }
  });
  await tick(60);
  return dom;
}

/** New-running-max count over a candle series: the ATH density that
 *  drives spike onsets. Uses the same series the bundle's own
 *  `ultimate` reads (candle[4]). */
function athDensity(candles) {
  let max = -Infinity, n = 0;
  for (const c of candles) { if (c[4] > max) { max = c[4]; n++; } }
  return n;
}

(async () => {
  const probe = await loadPage([]);
  const scenarios = probe.window.SCENARIOS;
  probe.window.close();
  const stats = scenarios.map((s) => ({
    id: s.id, candles: s.candles.length, ath: athDensity(s.candles)
  }));
  // The sustained worst case: the most ATH candles among rides long
  // enough to sustain a rate (>= 100 candles). A 10-candle ride can
  // spike at most a handful of times and ends before a rate exists.
  const sustained = stats.filter((s) => s.candles >= 100).sort((a, b) => b.ath - a.ath);
  const worst = sustained[0] || stats.slice().sort((a, b) => b.ath - a.ath)[0];
  console.log(`scenarios: ${scenarios.length}; sustained worst case: ${worst.id} ` +
    `(${worst.ath} ATH candles / ${worst.candles} candles)`);
  console.log('speed | spike onsets | ride-active window | onsets/sec');
  for (const speed of [1, 4, 16, 64]) {
    const onsets = [];
    const dom = await loadPage(onsets);
    const { document } = dom.window;
    const card = document.querySelector(`[data-open="${worst.id}"]`);
    card.click();
    await tick(60);
    document.querySelector('[data-launch]').click();
    await tick(60);
    const speedBtn = document.querySelector(`[data-speed="${speed}"]`);
    if (speedBtn) speedBtn.click();
    // Count from loop start until the ride ends (or a 20s cap):
    // the rate is over the ride-active window, not a fixed clock.
    const CAP_MS = 20000;
    let waited = 0;
    while (waited < CAP_MS) {
      await tick(100);
      waited += 100;
      if (document.querySelector('#ride').classList.contains('hidden')) break;
    }
    const activeSec = waited / 1000;
    const rate = onsets.length / activeSec;
    console.log(`${speed}×   | ${onsets.length} | ${activeSec.toFixed(1)}s | ${rate.toFixed(2)}`);
    dom.window.close();
  }
  console.log('\nMethod: jsdom (pretendToBeVisual raf), wall clock, real bundle + datasets,');
  console.log('pass-active fake bridge. Instrument reading for build comparison — NOT a');
  console.log('device measurement and NOT a photosensitivity clearance.');
  process.exit(0);
})().catch((e) => { console.error('MEASUREMENT ERROR', e); process.exit(2); });
