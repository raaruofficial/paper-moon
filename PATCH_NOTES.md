# PATCH_NOTES — money-build-v0.2.0

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
  sustained worst case eth-defi, 92 ATH / 610 candles): 1× 0.40,
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
