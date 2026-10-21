/**
 * Photosafety P-6 MEASUREMENT — device-paced Chromium instrument
 * (AC-10 seal instrument; supersedes the jsdom reading as primary).
 *
 * Why this exists: the fix wave's jsdom harness
 * (photosafety_measure.js) measured 0.40–1.10 spike onsets/sec and
 * could not reproduce the bankai record's ~28/sec. jsdom's rAF
 * cadence is not a device frame clock, so that reading is a
 * build-comparison instrument only. This harness runs the REAL
 * bundle in real headless Chromium 152 (the same engine family as
 * the Android WebView the game ships in) at a phone viewport
 * (412×915 CSS px, DPR 2), real time, on the sustained worst case
 * (eth-defi, 92 ATH / 610 candles — the most ATH-dense ride in the
 * shipped datasets).
 *
 * Frame pacing note: onset RATES in this bundle are not
 * frame-count-driven — the ride loop accumulates candles from
 * elapsed time (dt, clamped at 50 ms), so onsets/sec is a property
 * of the loop's rate table {1:1.2, 4:5, 16:20, 64:60 candles/sec}
 * and the data, on any renderer that keeps frames coming. (CDP
 * virtual time was tried first and rejected: in this Chromium
 * build it advances timers while starving rAF, i.e. it is LESS
 * device-faithful than real time.)
 *
 * Windows: every ride is measured WHOLE, at native pacing
 * (610 candles at 1.2 / 5 / 20 / 60 candles/sec ≈ 508 s / 122 s /
 * 31 s / 11 s). The reported max-in-any-1s is therefore the true
 * worst second the shipped data and rate table can produce at
 * that speed. (The bundle's state is closure-private, and this
 * harness does not patch the measured copy to reach it — a
 * densest-segment jump was considered and rejected on exactly
 * that ground.)
 *
 * What it counts: `.spike` element onsets (every spawnSpike DOM
 * append) and `#banner` shows, timestamped on the page clock.
 * Reported per speed: onsets in window, window seconds, average
 * onsets/sec, and MAX ONSETS IN ANY SLIDING 1-SECOND WINDOW — the
 * photosafety-relevant figure (WCAG 2.3.1: no more than three
 * flashes in any one-second period).
 *
 * Fidelity limits (stated, not hidden): headless Chromium on a
 * server CPU is not a phone GPU; frame drops would LOWER onset
 * rates (dt clamp caps catch-up), so a clean run here is the
 * upper envelope a device can reach, not a typical-device sample.
 * Audio inert. This is the best device-faithful measurement
 * achievable in this environment; it is still not a clinical
 * photosensitivity assessment and not a physical-device reading.
 *
 * Method: the shipped asset is copied to a scratch dir with an
 * autopilot script injected ahead of it (fake pass-active bridge,
 * the same entitlement shape as the jsdom harness; opens the
 * worst-case card, launches, sets the speed, counts). The shipped
 * asset itself is never modified by this harness. Chrome is
 * driven over CDP (--dump-dom returns empty in this build).
 *
 * Pacing validity (added after two starved runs on the loaded
 * sprint VM): the compositor is forced to produce frames
 * (screencast, frames discarded) and an in-page probe reports the
 * window's average fps and worst frame gap. A row is tagged
 * PACING-VALID only if the ride finished AND avg fps >= 50 AND
 * worst gap < 250 ms (the loop clamps dt at 50 ms, so gaps under
 * that keep game time == wall time). Starved rows are reported
 * but must not be quoted as measurements.
 *
 * Run: node verification/photosafety_chromium.js [bundlePath]
 * Exit 0 always (measurement, not a gate); prints a table.
 */
const fs = require('fs');
const os = require('os');
const path = require('path');
const http = require('http');
const { spawn } = require('child_process');
const WebSocket = require('/home/hatch/workspace/ts-spaces/bomb-plate-duel/node_modules/ws');

const CHROME = process.env.CHROME_BIN || '/opt/meta-chromium/chrome';
// Device scale factor for the run. Default 2 (device-faithful
// raster). PS_DSF=1 quarters the software-raster cost on loaded
// hosts; onset TIMING is DPR-independent (the loop is dt-driven
// and onsets are DOM appends on the page clock), so a pacing-
// valid DSF-1 run measures the same timing behaviour. The DSF in
// use is printed with the results and must be quoted with them.
const DSF = +(process.env.PS_DSF || '2');
const bundlePath = process.argv[2] ||
  path.join(__dirname, '..', 'app', 'src', 'main', 'assets', 'game', 'index.html');
const html = fs.readFileSync(bundlePath, 'utf8');

const BRIDGE_AND_AUTOPILOT = `
<script>
window.__ps = { spikes: [], banners: [], launchedAt: null, doneAt: null, speed: null };
(function () {
  var resolve = function (id, payload) {
    setTimeout(function () { window.__paperMoonWalletResolve(id, JSON.stringify(payload)); }, 0);
  };
  var bridge = {
    getVersion: function () { return '2'; },
    isConnected: function () { return true; },
    getPublicKey: function () { return 'FakeWallet7777777777777777777777777777777'; },
    getBoundIdentity: function () { return 'FakeWallet7777777777777777777777777777777'; },
    getPriceInfo: function () {
      return JSON.stringify({ itemId: 'day-pass', itemDisplay: 'Day Pass — 24h unlimited rides',
        usdAnchor: '$3.50', railAmount: '3.50 USDC', conversionBasis: 'USDC ≈ 1:1 with USD', railOpen: false });
    },
    getEntitlement: function () {
      return JSON.stringify({ bound: true, passActive: true, passExpiresAtMs: Date.now() + 3600e3,
        pending: false, rookieClaimed: true, rookieRemaining: 0 });
    },
    connectWallet: function (id) { resolve(id, { ok: true, data: {} }); return '{}'; },
    requestSignIn: function (id) { resolve(id, { ok: true, data: {} }); return '{}'; },
    claimRookie: function (id) { resolve(id, { ok: false, error: 'claimed' }); return '{}'; },
    consumeRookieRide: function (id) { resolve(id, { ok: true, data: { rookieRemaining: 0 } }); return '{}'; },
    requestPurchase: function (id) { resolve(id, { ok: false, error: 'rail closed' }); return '{}'; }
  };
  window.PaperMoonWallet = bridge;
  // Count spike onsets at the DOM boundary (same point as the jsdom harness).
  var origAppend = Node.prototype.appendChild;
  Node.prototype.appendChild = function (child) {
    if (child && child.className === 'spike') window.__ps.spikes.push(performance.now());
    return origAppend.call(this, child);
  };
  document.addEventListener('DOMContentLoaded', function () {
    var q = new URLSearchParams(location.search);
    var target = q.get('scenario') || 'eth-defi';
    var speed = +(q.get('speed') || '1');
    window.__ps.speed = speed;
    var banner = document.getElementById('banner');
    if (banner) {
      var wasHidden = banner.classList.contains('hidden');
      new MutationObserver(function () {
        var h = banner.classList.contains('hidden');
        if (wasHidden && !h) window.__ps.banners.push(performance.now());
        wasHidden = h;
      }).observe(banner, { attributes: true, attributeFilter: ['class'] });
    }
    setTimeout(function () {
      var card = document.querySelector('[data-open="' + target + '"]');
      if (card) card.click();
      setTimeout(function () {
        var launchBtn = document.querySelector('[data-launch]');
        if (launchBtn) { window.__ps.launchedAt = performance.now(); launchBtn.click(); }
        setTimeout(function () {
          var sb = document.querySelector('[data-speed="' + speed + '"]');
          if (sb) sb.click();
        }, 300);
      }, 300);
    }, 300);
    // Pacing probe: count rAF frames and the worst inter-frame
    // gap. The ride loop clamps dt at 50 ms, so game time tracks
    // wall time iff frame gaps stay under ~50 ms; this probe is
    // what lets a run prove it was not frame-starved.
    window.__ps.frames = { count: 0, maxGap: 0, lastT: 0 };
    var frameLoop = function (t) {
      var f = window.__ps.frames;
      if (f.lastT) { var g = t - f.lastT; if (g > f.maxGap) f.maxGap = g; }
      f.lastT = t; f.count++;
      requestAnimationFrame(frameLoop);
    };
    requestAnimationFrame(frameLoop);
    var sawRideVisible = false;
    var poll = setInterval(function () {
      var ride = document.getElementById('ride');
      if (!ride) return;
      if (window.__ps.launchedAt && !ride.classList.contains('hidden')) sawRideVisible = true;
      if (sawRideVisible && ride.classList.contains('hidden')) {
        window.__ps.doneAt = performance.now();
        clearInterval(poll);
      }
    }, 250);
  });
})();
</` + 'script>';

function httpJson(port, p) {
  return new Promise((res, rej) => {
    http.get({ host: '127.0.0.1', port, path: p }, (r) => {
      let d = '';
      r.on('data', (c) => (d += c));
      r.on('end', () => { try { res(JSON.parse(d)); } catch (e) { rej(e); } });
    }).on('error', rej);
  });
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function connectChrome(speed) {
  const port = 9300 + speed;
  const profile = fs.mkdtempSync(path.join(os.tmpdir(), 'ps-profile-'));
  const chrome = spawn(CHROME, [
    '--headless=new', '--disable-gpu', '--no-sandbox', '--mute-audio',
    '--window-size=412,915', '--force-device-scale-factor=' + DSF,
    '--disable-frame-rate-limit', '--disable-gpu-vsync',
    '--disable-background-timer-throttling', '--disable-renderer-backgrounding',
    '--run-all-compositor-stages-before-draw',
    '--remote-debugging-port=' + port, '--user-data-dir=' + profile,
    'about:blank'
  ], { stdio: 'ignore' });
  let version = null;
  for (let i = 0; i < 50 && !version; i++) {
    try { version = await httpJson(port, '/json/version'); } catch (e) { await sleep(200); }
  }
  if (!version) { chrome.kill('SIGKILL'); throw new Error('chrome devtools did not come up'); }
  const ws = new WebSocket(version.webSocketDebuggerUrl, { perMessageDeflate: false });
  await new Promise((res, rej) => { ws.on('open', res); ws.on('error', rej); });
  let nextId = 1;
  const pending = new Map();
  const send = (method, params, sessionId) => new Promise((resolve) => {
    const id = nextId++;
    pending.set(id, resolve);
    ws.send(JSON.stringify({ id, method, params: params || {}, sessionId }));
  });
  ws.on('message', (raw) => {
    const msg = JSON.parse(raw);
    if (msg.id && pending.has(msg.id)) { pending.get(msg.id)(msg); pending.delete(msg.id); }
    // Screencast frames must be acked or the compositor stops
    // producing them (the screencast itself is what forces steady
    // frame production in headless; frames are discarded).
    if (msg.method === 'Page.screencastFrameReceived' && msg.sessionId) {
      send('Page.screencastFrameAck',
        { sessionId: msg.params.sessionId }, msg.sessionId).catch(() => {});
    }
  });
  return { chrome, profile, ws, send };
}

async function evaluate(send, sid, expression) {
  const ev = await send('Runtime.evaluate', { expression, returnByValue: true }, sid);
  if (!ev.result || !ev.result.result) throw new Error('evaluate failed: ' + JSON.stringify(ev).slice(0, 300));
  if (ev.result.exceptionDetails) {
    const d = ev.result.exceptionDetails;
    throw new Error('page exception: ' + (d.exception && d.exception.description || d.text || '').slice(0, 400));
  }
  return ev.result.result.value;
}

async function runSpeed(speed, fileUrl, plan) {
  const { chrome, profile, ws, send } = await connectChrome(speed);
  try {
    const t = await send('Target.createTarget', { url: fileUrl + '?speed=' + speed });
    const a = await send('Target.attachToTarget', { targetId: t.result.targetId, flatten: true });
    const sid = a.result.sessionId;
    await send('Page.enable', {}, sid);
    await send('Runtime.enable', {}, sid);
    await send('Emulation.setDeviceMetricsOverride',
      { width: 412, height: 915, deviceScaleFactor: DSF, mobile: true }, sid);
    // Force steady compositor frame production (frames discarded,
    // acked in the message handler).
    await send('Page.startScreencast',
      { format: 'png', everyNthFrame: 6, maxWidth: 412, maxHeight: 915 }, sid).catch(() => {});
    // Wait for load + autopilot launch (real time).
    let launched = false;
    for (let i = 0; i < 80 && !launched; i++) {
      const v = await evaluate(send, sid,
        'window.__ps ? !!window.__ps.launchedAt : false').catch(() => false);
      if (v) launched = true; else await sleep(250);
    }
    if (!launched) throw new Error('autopilot did not launch for speed ' + speed);
    // Let the speed click land and the loop settle.
    await sleep(1200);

    await evaluate(send, sid, `(() => {
      window.__ps.winT0 = performance.now();
      window.__ps.frames.count0 = window.__ps.frames.count;
      window.__ps.frames.maxGap = 0;
      return true;
    })()`);
    // Wait out the measurement window (real time).
    const deadline = Date.now() + plan.windowMs + 30000;
    let done = false;
    while (Date.now() < deadline && !done) {
      await sleep(1000);
      const st = await evaluate(send, sid, `(() => ({
        now: performance.now(),
        doneAt: window.__ps.doneAt,
        t0: window.__ps.winT0
      }))()`).catch(() => null);
      if (!st) break;
      if (st.doneAt && st.doneAt >= st.t0) done = true;
      if (st.now - st.t0 >= plan.windowMs) done = true;
    }
    const r = await evaluate(send, sid, 'JSON.stringify(window.__ps)');
    const pnow = await evaluate(send, sid, 'performance.now()');
    const ps = JSON.parse(r);
    return { ps, pnow };
  } finally {
    try { ws.close(); } catch (e) {}
    chrome.kill('SIGKILL');
    fs.rmSync(profile, { recursive: true, force: true });
  }
}

function maxInWindow(ts) {
  let best = 0;
  for (let i = 0; i < ts.length; i++) {
    let n = 0;
    for (let j = i; j < ts.length && ts[j] - ts[i] < 1000; j++) n++;
    if (n > best) best = n;
  }
  return best;
}

(async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'ps-chromium-'));
  const instrumented = html.replace(/<head[^>]*>/i, (m) => m + BRIDGE_AND_AUTOPILOT);
  if (instrumented === html) throw new Error('instrumentation injection failed (no <head>)');
  const file = path.join(dir, 'index.html');
  fs.writeFileSync(file, instrumented);
  const fileUrl = 'file://' + file;

  // Worst-case dataset facts, computed in-page from the bundle's
  // own SCENARIOS (the game's ATH rule, verbatim logic).
  const pre = await connectChrome(0);
  let facts;
  try {
    const t = await pre.send('Target.createTarget', { url: fileUrl + '?speed=64' });
    const a = await pre.send('Target.attachToTarget', { targetId: t.result.targetId, flatten: true });
    const sid = a.result.sessionId;
    await pre.send('Page.enable', {}, sid);
    await pre.send('Runtime.enable', {}, sid);
    let ready = false;
    for (let i = 0; i < 80 && !ready; i++) {
      ready = await evaluate(pre.send, sid,
        "typeof SCENARIOS !== 'undefined'").catch(() => false);
      if (!ready) await sleep(250);
    }
    if (!ready) throw new Error('SCENARIOS never appeared in page');
    const fj = await evaluate(pre.send, sid, `(() => {
      var s = SCENARIOS.filter(function (x) { return x.id === 'eth-defi'; })[0];
      var c = s.candles;
      function dens(W) {
        var ath = new Array(c.length).fill(0), run = c[0][1];
        for (var i = 1; i < c.length; i++) { if (c[i][4] > run) { ath[i] = 1; run = c[i][4]; } }
        var best = -1, bs = 1, cur = 0;
        for (var i = 1; i < c.length; i++) {
          cur += ath[i]; if (i - W >= 1) cur -= ath[i - W];
          if (i >= W && cur > best) { best = cur; bs = i - W + 1; }
        }
        var rb = c[0][1];
        for (var i = 1; i < bs; i++) rb = Math.max(rb, c[i][4]);
        return { start: bs, runningBefore: rb, athInWindow: best };
      }
      return JSON.stringify({ n: c.length, w72: dens(72), w300: dens(300) });
    })()`);
    facts = JSON.parse(fj);
  } finally {
    try { pre.ws.close(); } catch (e) {}
    pre.chrome.kill('SIGKILL');
    fs.rmSync(pre.profile, { recursive: true, force: true });
  }

  // Window plans: full rides at native pacing. Budgets carry
  // margin over the nominal durations (508/122/31/11 s).
  const plans = {
    1: { windowMs: 560000 },
    4: { windowMs: 160000 },
    16: { windowMs: 50000 },
    64: { windowMs: 30000 }
  };
  console.log('bundle: ' + bundlePath);
  console.log('worst case: eth-defi (' + facts.n + ' candles); densest 72-candle window: ' +
    facts.w72.athInWindow + ' ATH from index ' + facts.w72.start +
    '; densest 300-candle window: ' + facts.w300.athInWindow + ' ATH from index ' + facts.w300.start);
  console.log('speed | spikes | banners | window s | avg spikes/s | MAX spikes in any 1s | MAX banners in any 1s');
  for (const speed of [1, 4, 16, 64]) {
    const plan = plans[speed];
    const { ps, pnow } = await runSpeed(speed, fileUrl, plan);
    const t0 = ps.winT0 || ps.launchedAt;
    const t1 = ps.doneAt && ps.doneAt > t0 ? ps.doneAt : pnow;
    const spikes = ps.spikes.filter((t) => t >= t0 && t <= t1).map((t) => t - t0);
    const banners = ps.banners.filter((t) => t >= t0 && t <= t1).map((t) => t - t0);
    const winSec = (t1 - t0) / 1000;
    const framesInWin = (ps.frames ? ps.frames.count - (ps.frames.count0 || 0) : 0);
    const avgFps = winSec > 0 ? framesInWin / winSec : 0;
    const maxGap = ps.frames ? Math.round(ps.frames.maxGap) : -1;
    const finished = !!(ps.doneAt && ps.doneAt > t0);
    const valid = finished && avgFps >= 50 && maxGap < 250;
    console.log(
      speed + '×   | ' + spikes.length + ' | ' + banners.length + ' | ' +
      winSec.toFixed(1) + ' | ' + (spikes.length / winSec).toFixed(2) + ' | ' +
      maxInWindow(spikes) + ' | ' + maxInWindow(banners) +
      '  [' + (finished ? 'full ride' : 'WINDOW ELAPSED — ride unfinished') +
      '; pacing ' + avgFps.toFixed(0) + ' fps, max frame gap ' + maxGap + ' ms; ' +
      (valid ? 'PACING-VALID' : 'PACING-INVALID') + ']'
    );
  }
  fs.rmSync(dir, { recursive: true, force: true });
  console.log('\nMethod: headless Chromium 152 (WebView engine family), 412×915 @ DPR ' + DSF + ',');
  console.log('real time (dt-driven loop: onset rates are renderer-independent), real');
  console.log('bundle + datasets, pass-active fake bridge, sustained worst case');
  console.log('(eth-defi). NOT a physical-device reading and NOT a photosensitivity');
  console.log('clearance. Bar of reference: WCAG 2.3.1 — no more than 3 flashes in');
  console.log('any one-second period.');
  process.exit(0);
})().catch((e) => { console.error('MEASUREMENT ERROR', e.message); process.exit(2); });
