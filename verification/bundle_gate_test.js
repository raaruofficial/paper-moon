/**
 * Bundle gate test (AC-08 PASS conditions), run under jsdom with a
 * fake PaperMoonWallet bridge implementing bridge protocol 2.
 *
 * PASS conditions exercised:
 *  - with shell entitlement absent, no ride past the free grant starts
 *    (launch is blocked and the wallet overlay opens instead);
 *  - claiming through the shell grants the mirror exactly the
 *    shell's count (bundle fabricates nothing);
 *  - with a rookie ride available, a ride starts and consumption is
 *    requested from the shell exactly once, after the ride exists;
 *  - with a verified day pass (shell-side), a ride starts and no
 *    rookie consumption is requested.
 *
 * Run: node verification/bundle_gate_test.js  (requires jsdom —
 * `npm i jsdom` in a scratch dir and NODE_PATH pointing at it, or run
 * from a checkout with jsdom installed; the harness script handles it)
 */
const fs = require('fs');
const path = require('path');
const { JSDOM } = require('jsdom');

const html = fs.readFileSync(
  path.join(__dirname, '..', 'app', 'src', 'main', 'assets', 'game', 'index.html'),
  'utf8'
);

let failures = 0;
let checks = 0;
function check(name, cond) {
  checks++;
  console.log((cond ? 'PASS  ' : 'FAIL  ') + name);
  if (!cond) failures++;
}
const tick = () => new Promise((r) => setTimeout(r, 30));

function makeBridge(state) {
  const calls = { consume: 0, claim: 0, purchase: 0, signIn: 0 };
  const resolve = (win, id, payload) =>
    setTimeout(() => win.__paperMoonWalletResolve(id, JSON.stringify(payload)), 0);
  const bridge = {
    getVersion: () => '2',
    isConnected: () => true,
    getPublicKey: () => state.pubkey,
    getBoundIdentity: () => (state.bound ? state.pubkey : ''),
    getPriceInfo: () =>
      JSON.stringify({
        itemId: 'day-pass', itemDisplay: 'Day Pass — 24h unlimited rides',
        usdAnchor: '$3.50', railAmount: '3.50 USDC',
        conversionBasis: 'USDC ≈ 1:1 with USD', railOpen: state.railOpen
      }),
    getEntitlement: () =>
      JSON.stringify({
        bound: state.bound, passActive: state.passActive,
        passExpiresAtMs: state.passActive ? Date.now() + 3600e3 : 0,
        pending: false, rookieClaimed: state.claimed,
        rookieRemaining: state.remaining
      }),
    connectWallet: (id) => { resolve(bridge._win, id, { ok: true, data: { publicKey: state.pubkey } }); return '{}'; },
    requestSignIn: (id) => {
      calls.signIn++;
      state.bound = true;
      resolve(bridge._win, id, { ok: true, data: { publicKey: state.pubkey } });
      return '{}';
    },
    claimRookie: (id) => {
      calls.claim++;
      if (!state.bound) { resolve(bridge._win, id, { ok: false, error: 'not_bound: sign in' }); return '{}'; }
      if (!state.claimed) { state.claimed = true; state.remaining = 3; }
      resolve(bridge._win, id, { ok: true, data: { rookieRemaining: state.remaining } });
      return '{}';
    },
    consumeRookieRide: (id) => {
      calls.consume++;
      if (state.consumeFails) {
        resolve(bridge._win, id, { ok: false, error: 'consume_failed: the ride could not be recorded' });
        return '{}';
      }
      if (state.remaining > 0) state.remaining--;
      resolve(bridge._win, id, { ok: true, data: { rookieRemaining: state.remaining } });
      return '{}';
    },
    requestPurchase: (id, itemId) => {
      calls.purchase++;
      resolve(bridge._win, id, { ok: false, error: 'rail closed', data: { state: 'rail-closed' } });
      return '{}';
    }
  };
  return { bridge, calls };
}

async function loadPage(state) {
  const { bridge, calls } = makeBridge(state);
  const dom = new JSDOM(html, {
    runScripts: 'dangerously',
    pretendToBeVisual: true,
    url: 'file:///android_asset/game/index.html',
    beforeParse(window) {
      window.PaperMoonWallet = bridge;
      bridge._win = window;
      // Absorb canvas + audio: the gate test does not render.
      window.HTMLCanvasElement.prototype.getContext = function () {
        return new Proxy({}, { get: (t, k) => (k === 'canvas' ? {} : () => {}), set: () => true });
      };
      window.AudioContext = class { createOscillator() { return { connect() {}, start() {}, stop() {}, frequency: {} }; } createGain() { return { connect() {}, gain: {} }; } };
      window.scrollTo = () => {};
    }
  });
  await tick(); await tick();
  return { dom, calls, window: dom.window, document: dom.window.document };
}

async function launchFirst(document) {
  const card = document.querySelector('[data-open]');
  if (!card) throw new Error('no scenario card rendered');
  card.click();
  await tick();
  const btn = document.querySelector('[data-launch]');
  if (!btn) throw new Error('no launch button rendered');
  btn.click();
}
const rideStarted = (document) => !document.querySelector('#ride').classList.contains('hidden');
const walletOpened = (document) => !document.querySelector('#walletOverlay').classList.contains('hidden');

(async () => {
  // Case 1: no entitlement at all — launch must be blocked.
  {
    const st = { pubkey: 'FakeWallet1111111111111111111111111111111', bound: false, claimed: false, remaining: 0, passActive: false, railOpen: false };
    const { document, calls } = await loadPage(st);
    check('AC-08 mirror starts at 0 rides (no session faucet)',
      document.querySelector('#creditCount').textContent === '0');
    await launchFirst(document);
    await tick();
    check('AC-08 with shell entitlement absent, no ride starts', !rideStarted(document));
    check('AC-08 blocked launch opens the wallet instead', walletOpened(document));
    check('AC-08 blocked launch consumes nothing', calls.consume === 0);
  }
  // Case 2: claim through the shell, then ride, consuming after build.
  {
    const st = { pubkey: 'FakeWallet2222222222222222222222222222222', bound: false, claimed: false, remaining: 0, passActive: false, railOpen: false };
    const { document, calls } = await loadPage(st);
    document.querySelector('#passBtn').click(); // ROOKIE RIDES button
    await tick(); await tick(); await tick();
    check('AC-07/08 claim routes via shell sign-in then claim', calls.signIn >= 1 && calls.claim >= 1);
    check('AC-08 mirror shows exactly the shell-granted count',
      document.querySelector('#creditCount').textContent === '3');
    await launchFirst(document);
    await tick(); await tick();
    check('AC-08 with a rookie ride available, the ride starts', rideStarted(document));
    check('AC-08 consumption requested from the shell exactly once', calls.consume === 1);
    await tick();
    check('AC-08 mirror decrements only via the shell count',
      document.querySelector('#creditCount').textContent === '2');
  }
  // Case 3: verified day pass — ride starts, no rookie consumption.
  {
    const st = { pubkey: 'FakeWallet3333333333333333333333333333333', bound: true, claimed: true, remaining: 0, passActive: true, railOpen: false };
    const { document, calls } = await loadPage(st);
    await launchFirst(document);
    await tick();
    check('AC-08 with a verified grant, the ride can start', rideStarted(document));
    check('AC-08 day-pass ride consumes no rookie ride', calls.consume === 0);
    check('AC-08 pass mirror renders as active pass',
      document.querySelector('#creditLabel').textContent === 'DAY PASS ACTIVE');
  }
  // Case 5 (bankai #3 MAJOR-3): the shell REFUSES the consume —
  // the built ride must roll back: no ride runs, the consume was
  // attempted exactly once, and the mirror still shows the unspent
  // ride. (The fire-and-forget original started the loop before the
  // callback and never read its result.)
  {
    const st = { pubkey: 'FakeWallet5555555555555555555555555555555', bound: true, claimed: true, remaining: 1, passActive: false, railOpen: false, consumeFails: true };
    const { document, calls } = await loadPage(st);
    check('FIX3 mirror starts at the shell-granted count',
      document.querySelector('#creditCount').textContent === '1');
    await launchFirst(document);
    await tick(); await tick(); await tick();
    check('FIX3 failed consume starts no ride (rolled back to home)',
      !rideStarted(document));
    check('FIX3 rollback is surfaced to the player',
      document.querySelector('#toast').textContent.includes('could not be recorded'));
    check('FIX3 consume was attempted exactly once', calls.consume === 1);
    check('FIX3 mirror still shows the unspent rookie ride',
      document.querySelector('#creditCount').textContent === '1');
  }
  // Case 4: day-pass purchase routes to the shell by item id only.
  {
    const st = { pubkey: 'FakeWallet4444444444444444444444444444444', bound: true, claimed: true, remaining: 0, passActive: false, railOpen: true };
    const { document, calls } = await loadPage(st);
    document.querySelector('#coinBtn').click();
    await tick(); await tick();
    check('AC-04/08 coin button routes to shell requestPurchase', calls.purchase === 1);
  }
  console.log(`\n${checks} checks, ${failures} failures`);
  process.exit(failures ? 1 : 0);
})().catch((e) => { console.error('HARNESS ERROR', e); process.exit(2); });
