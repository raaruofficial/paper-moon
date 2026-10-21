# PATCH_NOTES — money-build-v0.2.0

## 2026-10-21 — AC-10 SEAL (sprint close)

Seal record: `docs/AC10_SEAL_RECORD.md` (per-AC verdicts AC-01…
AC-09 all PASS; AC-06 Terms adoption flip-gated by design).
AC-11 staged, NOT applied: `docs/AC11_FLIP_DELTAS.md` (one-line
`SALES_ENABLED` flip + Terms adoption mechanics + listing leg +
flip order with single-line revert).

Audit re-runs at the seal head (`0dc469a`), not inherited from
the fix wave: Kotlin harness **87 checks, 0 failures**; censuses
**all clean (exit 0)**; bundle gate test **20/20**; jsdom
photosafety continuity 0.40–1.30/sec capped readings (secondary
instrument); Chromium photosafety: log-backed tables in the P-6
entry below, including its evidence note withdrawing two
unverifiable delivered tables. Datasets byte-identical to sealed v0.1.3 (segment
hash equal at `64095e5` and HEAD). Pre-join separation
re-verified: dedicated wallet `41FFd2wK…AtQYe` configured,
shared treasury address = 0 in money code, `SALES_ENABLED=false`
— the sealed line cannot take a purchase. Both-themes check:
bundle dual-scheme complete; native sheet inherits the shell's
single fixed dark scheme (no broken light variant exists).
CI: run **39556232138 SUCCESS** at `f12c837` (assembleDebug +
debug APK artifact). APK-dex census (same patterns over the CI
APK's dex strings): SIGN TEST MESSAGE 0, "lands in step 2" 0,
shared treasury address 0, SIMULATE family 0, dedicated receiving
wallet present (1), `$3.50` exactly 1 (the single source);
packaged bundle byte-identical to the repo bundle at HEAD
(sha256 match). Sole `not_implemented` dex hit (1) attributed:
`androidx.emoji2.text.flatbuffer.FlexBuffers$Reference.toString()`
— a bundled AndroidX library literal ("not_implemented:"), not
app code; the source census for app stubs is 0.

## 2026-10-21 — AC-10 sprint: P-6 photosafety method + cure (decision 19 executed)

**Method first.** The bankai record's ~28/sec spike-onset figure
could not be reproduced by the fix wave's jsdom harness
(0.40–1.10/sec). Its method is unpreserved, and code inspection
bounds what any DOM-onset method can read on this bundle (sealed
v0.1.3 spike code is byte-identical to this line's pre-cure code):
candle rates {1:1.2, 4:5, 16:20, 64:60}/sec with a 350 ms
coalesce at ≥16× cap onsets at 1.2 / 5 / ~2.86 / ~2.86 per sec.
One reconciliation datum: the densest 72-candle window of the
worst-case dataset contains exactly **28 ATH** — the quoted
figure is consistent with spike *triggers* counted over a window
(or at 64×, where that window passes in ~1.2 s), not rendered
onsets/sec. Labelled inference, not proof; the original method
stays unpreserved.

**Standing instrument:** `verification/photosafety_chromium.js` —
headless Chromium 152 (WebView engine family), 412×915 @ DPR 2,
real time (the loop's onset rates are dt-driven, so they are
renderer-independent; CDP virtual time was tried and rejected —
in this build it advances timers while starving rAF), real
bundle + datasets, pass-active fake bridge, worst case eth-defi
(93 ATH / 610 candles), FULL rides at native pacing. Metric:
max `.spike` onsets in any sliding 1-second window vs the WCAG
2.3.1 bar (≤3 flashes in any 1 s). Fidelity limits are stated in
the harness header: headless-on-server is the upper envelope a
device can reach (frame drops only lower rates), not a
physical-device reading and not a clinical clearance.

**Baseline (pre-cure):** max onsets in any 1 s =
**2 (1×) · 4 (4×) · 3 (16×) · 3 (64×)** — the 4× breach (4 in one
second) was observed in the captured run log *despite* frame
starvation (below), and the pre-cure code breaches structurally
at 4× regardless (per-candle spawns at 5 candles/sec); banners
≤1 in any 1 s at every speed (serialized at 2.3 s — not a flash
source).

**Cure** (`0330015`, bundle render path only, datasets
untouched): one rate gate at the `spawnSpike` choke point —
`athSpike` enforces a **500 ms minimum onset interval at all
speeds**, bursts coalescing to the highest price in the window
(the pre-cure ≥16× semantics, universalized); the pending spike
is bound to its ride and cleared on launch/finish, so no caller
can bypass the cap and no spike leaks across rides.

**Post-cure (same instrument):** max onsets in any 1 s =
**2 (1×) · 3 (4×) · 3 (16×) · 2 (64×)** — at the threshold at
two speeds, never over. **The bar is met at every speed.**

**Final measurement state (2026-10-21, post-seal runs with the
credit ceiling lifted):** four independent post-cure Chromium
runs were captured to files/logs; max onsets in any 1 s, by run:
DPR 2 starved **2/3/3/2** · DPR 2 screencast **2/2/2/2** · DSF 1
**2/3/3/3** · DSF 1 stall-sized budgets **2/3/3/3** (1×/4×/16×/
64×). Never over the bar in any row of any run. Full ATH
traversal at 1× is proven by onset count (93 appends = all 93
ATH; the dataset's ATH count is **93** under the game's seeding
rule — the "92" quoted earlier in this file came from the fix
wave probe's off-by-one seed and is corrected throughout).
Ride-*completion* sign-off (the finish screen reached inside
the window) was not achievable on this host at any budget tried
(up to 720 s for the 508 s ride): stall gaps of 300–1400 ms —
neighbour-lane CPU contention — each burn game time through the
loop's 50 ms dt clamp, an accumulated ~30% loss that grows with
window length. It is not load-bearing: starvation only lowers
wall-clock onset rates, traversal is proven where it can be
counted, and the construction proof above governs. A quiet-host
full-ride run remains a nice-to-have for the first update cycle,
not a seal gap.

**Evidence note (2026-10-21, post-seal re-verification — read
this before quoting any number above):** two measurement tables
delivered during the sprint (a "full-ride" pre-cure table and a
"full-ride" post-cure table) could NOT be verified against the
runs' captured process logs — the pre-cure run's log holds the
starved table quoted here, and the post-cure run's log holds no
result rows (its buffered output was lost when the outer timeout
killed it mid-ride). Those delivered tables are **withdrawn as
evidence** wherever they appear (including in the 0330015 /
0dc469a commit messages, which stand unrewritten). The figures
quoted in this entry are the log-backed ones: both Chromium runs
were frame-starved on the loaded sprint VM and hit their window
budgets with rides unfinished (starvation biases wall-clock
rates *downward*, so the runs corroborate but do not by
themselves prove). The load-bearing proof is structural and
pacing-independent: the gate enforces a ≥500 ms interval
between appends on the page clock at the single spawn point, so
no 1-second window can contain more than 3 onsets on any
renderer (worst case, with ~1 ms timer earliness: onsets at
0 / 499 / 998 ms). Seal record: `docs/AC10_SEAL_RECORD.md`.

## 2026-10-20/21 — Fix wave (bankai #3 PM findings; AC-10 seal-blocking items)

Branch head after this wave: `4e9c396`. Sealed v0.1.3 untouched.
Each fix landed as its own commit and was verified by re-running
the discriminating test that proved the defect.

- **MAJOR-1 — gate state** (`b425038`). At `aba6b30` `railOpen`
  derived from configuration alone, so configuring the dedicated
  wallet silently opened the rail in code ("ships closed" refuted;
  purchase-capable CI artefact). `railOpen` now requires
  `SALES_ENABLED` (false on this line; flips only in the AC-10
  seal + flip act) AND a configured wallet. Correction to the entry
  below: its claim "It does NOT open sales" was not true of the
  code at `aba6b30`; this fix makes it true. Harness GATE block now
  pins the production defaults (production flow + coordinator
  refuse with the decided wallet configured).
- **MAJOR-2 — settlement-write complex** (`8bbd72b`). Pending
  settlement is a per-signature journal (ACTIVE/GRANTED/REFUSED,
  never overwritten, resolved entries retained; the legacy
  single-slot record migrates in). The grant is a single durable
  write — the receipt is the source of truth and the by-key lookup
  is derived by scan (the two-put index orphan is inexpressible).
  The intent outcome is written before the journal and reconcile
  scans both, so a crash between broadcast and either write
  recovers from the other; UNKNOWN outcomes are retained as
  candidates, never discarded. Replay binds for real (unresolved
  guard + in-flight outcome binding; the old check read the
  just-minted key and could never fire). PENDING_SETTLEMENT
  releases the in-flight slot (was wedged for process life).
  Definitive on-chain refusals resolve to a recorded FAILED +
  journal REFUSED (`PurchaseResult.SettlementRefused`) instead of
  pending forever; NOT_FOUND/RPC stay pending. `PurchaseOutcome`
  carries `buyerIdentity`.
- **MAJOR-3 — rookie consume** (`23344ce`). The bundle started the
  ride loop fire-and-forget after requesting the consume; a failed
  consume delivered a free, unrecorded ride. The built ride now
  waits for the shell's ack — success starts the loop, failure (or
  no shell) rolls the ride back (nothing runs, mirror re-read,
  player told). Decrement-after-build preserved.
- **MAJOR-2/AC-09 — copy** (`c42a45b`). `DAILY EDITION · FREE RIDE`
  kicker and `FREE · DAILY EDITION` launch label replaced with the
  rookie-ride form of record (`DAILY EDITION · ROOKIE RIDE` /
  `ROOKIE RIDE · DAILY EDITION`); census now counts both old
  strings as must-be-0. Datasets untouched.
- **MAJOR-5 (code side) — duplicate guard** (`fa08a88`). A second
  purchase against an active pass is refused
  (`PurchaseResult.DuplicateActivePass`): no charge path entered,
  window not refreshed, attempt durably recorded as a non-delivery
  (`EntitlementLedger.recordNonDelivery` — the Terms §7(b) remedy
  queue). The Terms instrument itself stays DRAFT with
  `[EFFECTIVE_DATE]` unfilled — flip-gate items, not builder items.
- **P-6 photosafety** (`4e9c396`). No cure attempted.
  `verification/photosafety_measure.js` landed (jsdom instrument;
  sustained worst case eth-defi, 93 ATH / 610 candles): 1× 0.40,
  4× 0.90, 16× 1.10, 64× 0.70 spike onsets/sec — which do NOT
  reproduce the ~28/sec figure quoted in the bankai record (its
  method is not preserved in this repo; spike spawns are
  structurally capped — candle rate below 16×, a 350 ms coalesce
  at 16×+ — and jsdom delivers frames slower than a device). The
  item-19 decision stands (cure, no waiver); the flip remains
  gated on a cure re-measured before AC-10/flip.

### Verification state (this wave)
- Harness: **87/87** (`verification/run_harness.sh`) — 64 baseline
  + 3 gate + 15 crash-injection + 5 duplicate-guard checks.
- Census: all clean (`verification/census.sh`), incl. the two new
  AC-09 must-be-0 counts.
- jsdom bundle gate test: **20/20** (`verification/bundle_gate_test.js`)
  — incl. failed-consume rollback (Case 5) and copy (Case 6).
- CI: run **39552052995**, head `4e9c396` — **success**
  (`assembleDebug` + debug APK upload). Full-source typecheck.sh
  could not run in this sandbox (its `/tmp/deps` classpath from the
  original build no longer exists); CI is the packaging authority
  and compiled all sources, including the bridge's new
  `PurchaseResult` branches.

## 2026-10-20 — Receiving wallet configured (TRACE pre-join decision, owner delegation)

- `ReceivingWalletConfig.CONFIGURED_RECEIVING_WALLET` set to the dedicated
  PAPER MOON receiving wallet `41FFd2wK8Uoy75TVYDx6bXZzscPH3UihwpuiX8fAtQYe`
  (created 2026-10-20; custody `~/workspace/receiving-wallets/`, backup
  bundle restore-proven). This is the one-line change the config always
  described. It does NOT open sales: AC-10 seal + flip gates still govern.
- Harness GATE block re-pointed to the decided state: configured wallet
  pinned to the exact dedicated address and never the shared treasury;
  fail-closed mechanics now proven against injected unconfigured/closed
  providers. Harness: 64 checks, 0 failures (`verification/run_harness.sh`).

## 2026-10-20/21 — Money build Part A (AC-01…AC-09 buildable legs)

Line: branch `money-build-v0.2.0` from sealed HEAD `64095e5` (v0.1.3).
Sealed line untouched (no edits to main, no tag moves). Version 0.2.0,
versionCode 5. Datasets byte-identical to v0.1.3 (segment sha256
b35d5f9b7f60652c3f1fc7e7086444259749fa984a504bb11a89db775a90ed21,
512,198 bytes — candle data + story dates untouched).

### Landed
- **AC-01** Shell Verifier (challenge/nonce/TTL/bound identity),
  in-tree Ed25519 verification, bridge raw `signMessage` removed,
  `requestSignIn` added. Docs: `docs/MONEY_BUILD.md`.
- **AC-02** EncryptedSharedPreferences store for token/entitlement/
  receipts/intents/claims; legacy plaintext token prefs wiped, not
  migrated; G-2 backup/transfer exclusions extended to the new store.
- **AC-03** Real MWA `signAndSendTransactions`; stub + stub strings
  gone; five-class taxonomy with total mapping; shell-side-only tx
  construction (no recipient/amount/memo parameter exists).
- **AC-04** Native purchase sheet (GameScreen dialog via
  PurchaseSheetController) with all five fields from shell values;
  bridge `requestPurchase(itemId)` only.
- **AC-05** Settlement verification (recipient/mint/amount/commitment
  re-verified on-chain; fail closed; pending state), receipt =
  signature persisted pre-grant, restore-by-signature at launch.
- **AC-06 (mechanics)** Idempotency key intent→confirmation; sticky
  terminal outcomes; launch reconcile marks stale intents UNKNOWN
  before the gate reads entitlement. Terms element NOT built —
  ruling/paper-gated (prep B-6: merged PM Terms do not exist yet).
- **AC-07** Identity-bound rookie ledger (claim once per bound
  identity, durable, remaining never increases).
- **AC-08** Bundle faucet swap (coinBtn→requestPurchase,
  passBtn→rookie claim, launch gate reads shell mirror only,
  consumption after ride build). JS parses; censuses clean.
- **AC-09 (bundle + sheet legs)** Single price source `MoneyConfig`;
  bundle/shell surfaces render from it (bundle via bridge getter);
  ENT-7 inventoried strings rewritten (25¢ / $5 / SIMULATE family /
  "last this open session" gone). Listing leg ruling/flip-gated.

### Gate (owner-directed pre-join mitigation)
Receiving wallet ships UNCONFIGURED: rail fails closed at intent,
sheet, tx-build, and settlement layers. No default to the shared
treasury anywhere (census: treasury address absent from money code).
Harness gate tests pin this.

### Found & fixed on this line (not in the sealed line)
- **Base58 inverted guard**: v0.1.3's `Base58.encode/decode` read
  the already-converted digit region as zero, so no multi-byte value
  round-tripped ("hello world" → "2j"). Any wallet address/signature
  the shell produced was wrong. Fixed to the canonical form on this
  line only; pinned by harness round-trip + known-vector checks.
  The sealed v0.1.3 APK still contains the defect — it is inert
  there only because the sealed bundle never calls signing and the
  address display is decorative; it would have been live the moment
  the money rail used it.

### Verification state
- Harness: 65/65 checks PASS (`verification/run_harness.sh`).
- Census: all clean (`verification/census.sh`).
- Bundle JS: parses (node), dataset segment hash matches sealed.
- Full-source Kotlin type-check: ALL 27 app sources (including every
  new Android glue file) type-check with ZERO frontend errors against
  android.jar (API 35) + the real dependency jars at the exact
  build.gradle versions (recipe: verification/typecheck.sh). The
  check caught and fixed one real issue: MasterKey requires
  security-crypto 1.1.0-alpha07 (1.0.0 has only the legacy MasterKeys
  API) — the Gradle dependency was bumped accordingly.
- Android assembleDebug: NOT completed in the build sandbox — the
  Gradle daemon starts and the client loses communication with it
  within seconds, on every attempt (foreground, detached,
  memory-capped); plain JVMs and loopback HTTP servers in the same
  sandbox are unaffected, so this reads as a sandbox/Gradle-daemon
  interaction, not a project defect. CI's build-debug job
  (assembleDebug on push/PR) is the packaging authority and has not
  yet run on this branch (branch not pushed). Compose codegen/IR
  lowering is therefore the one unverified layer; all layers above
  it (parsing, resolution, type-checking of every file) are verified.
- jsdom bundle gate test: 13/13 PASS
  (verification/bundle_gate_test.js) — launch blocked at zero
  entitlement, claim via shell sign-in, ride + exactly one post-build
  consumption, day-pass ride with no consumption, purchase routing.

### Open / next builder picks up here
1. If not yet green: `./gradlew :app:assembleDebug` and fix any
   compile errors in the Android glue (money/* logic is harness-
   proven; glue is the unverified layer).
2. jsdom-level bundle gate test (launch blocked at 0 entitlement;
   ride + post-build consumption with a fake bridge).
3. AC-10 seal re-audit on the built APK (dex censuses on real bytes).
4. Ruling-gated: AC-06 Terms element, AC-09 listing leg, AC-11,
   P2–P5 surfaces, photosafety P-6 cure-or-waiver. Rail opening
   (dedicated wallet configuration) is an owner action.
