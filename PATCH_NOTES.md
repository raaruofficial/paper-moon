# PATCH_NOTES — money-build-v0.2.0

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
