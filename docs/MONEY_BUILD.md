# PAPER MOON — Money Build (v0.2.0 line)

The real-money successor to the sealed v0.1.3 candidate, built under
`MONEY_BUILD_SPEC.md` (AC-01…AC-11) on the values decided in P6:
day pass (24h unlimited rides, rolling from grant) · $3.50 ·
USDC 1:1 · first ride free survives as the identity-bound rookie
grant. All sales final. The sealed v0.1.3 (tag `64095e5`) is
evidence, not an edit target; this line is `money-build-v0.2.0`.

## ⚠ The rail ships CLOSED — fail-closed receiving wallet

Owner-directed pre-join mitigation: the day-pass rail must fail
closed until a **dedicated PAPER MOON receiving wallet** exists and
is configured. `ReceivingWalletConfig.CONFIGURED_RECEIVING_WALLET`
ships **empty**. While empty:

- no purchase intent can be created (`PurchaseFlow`),
- no purchase sheet can render (`PurchaseSheetData.forDayPass`),
- no transaction can be built (`TransactionBuilder`),
- no settlement can verify (`SettlementVerifier`),

and every attempt is refused with a named, visible message. There is
**no default recipient and no fallback** — in particular the rail
never defaults to the shared/publisher treasury (raaru.skr). Opening
the rail is a one-line change (set the constant to the dedicated
wallet's Base58 address) plus the AC-10 seal re-audit. The gate is
pinned by tests in `verification/MoneyBuildHarness.kt` that fail if
the rail is open without a configured, non-treasury wallet.

## Components (package `com.hindsight.app.money`)

| Component | AC | Role |
|---|---|---|
| `ShellVerifier` | AC-01 | Binding half: builds the sign-in challenge (`PAPER MOON sign-in` + ≥128-bit single-use nonce + issued-at, TTL ≤ 5 min), verifies the wallet's ed25519 signature, establishes the bound identity. Nonce is consumed *before* signature verification — replays and failed attempts spend the challenge. Failure behavior: named `Refusal` reasons; a failed attempt never sets/clears/changes the bound identity. Nonce store is in-memory (`ShellVerifier.InMemoryNonceStore`): a challenge never survives process death, by design — death means sign in again. |
| `Ed25519` | AC-01 | Dependency-free ed25519 *verification* (RFC 8032, cofactorless with small-order rejection), needed because no platform verifier is guaranteed across minSdk 28. Pinned by the RFC 8032 TEST 1 vector and a JDK cross-check in the harness. Verification only — it cannot sign. |
| `EncryptedPaperMoonStore` (wallet/) | AC-02 | `EncryptedSharedPreferences` (Keystore master key) holding the MWA auth token, entitlement cache, purchase receipts, intents, and the rookie ledger. The v0.1.3 plaintext token prefs are wiped on first init, never migrated. G-2 backup/transfer exclusions extended to the new store's files. |
| `TransactionBuilder` | AC-03/04 | Shell-side-only construction of the one purchasable transaction: ATA create-idempotent + SPL `TransferChecked` of exactly the single-source price in USDC to the configured receiving wallet's ATA. **There is no parameter for recipient, amount, mint, or memo** — the page cannot alter them because no code path accepts them. |
| `TxErrors` (`TxErrorClass`, `TxErrorMapper`) | AC-03 | The five failure classes — user-declined · insufficient-funds · wallet-absent · RPC/confirmation failure · user-cancelled-mid-flow. Every internal failure kind maps to exactly one; unknown failures default to RPC/confirmation (the class whose copy never asserts "nothing was charged" unproven). No raw exception crosses the bridge. |
| `PurchaseSheetData` + `PurchaseSheetController` + GameScreen dialog | AC-04 | The native sheet shows item, rail amount, USD anchor + conversion basis, network-fee estimate, and recipient — all from shell-side values. The game requests a purchase by item identifier only. |
| `SettlementVerifier` + `SolanaRpcChainReader` | AC-05 | Settlement half of the Shell Verifier. The transaction signature is the receipt; before any grant the tx is re-verified on-chain — found at commitment `confirmed`, not failed, transfer to the configured recipient ≥ quoted amount of the correct USDC mint (recipient balance-delta from `getTransaction` jsonParsed). Any failure or unreachable RPC = no grant, named pending state, reconcile on next launch. |
| `PurchaseFlow` + `PurchaseCoordinator` + `EntitlementLedger` | AC-05/06 | One idempotency key from intent to confirmation; terminal outcomes are sticky and persisted, so retry / double-tap / process-death replay returns the original outcome — never a second charge or double grant. Grants are exactly-once per key *and* per signature (ledger-enforced). Pending/unknown have named states (`PurchaseState`); reconciliation runs before the launch gate reads entitlement (`GameScreen` LaunchedEffect → `reconcileCoordinator`). |
| `RookieLedger` | AC-07 | The rookie grant (3 rides, first ride free) keyed by bound identity in the encrypted store, claimable exactly once; remaining never increases across relaunch/reinstall-restore; consumption is shell-mediated after the ride exists (AC-08 decrement-after-build). |
| `MoneyConfig` | AC-09 | The single price source (item, $3.50 anchor, 3.50 USDC at 6dp, mint, 24h duration, rookie size) feeding the sheet, the bundle labels (via `getPriceInfo`), and settlement. |
| `SolanaRpcChainReader` | AC-05 | RPC semantics: single endpoint (public mainnet, `RpcConfig.ENDPOINT`), no silent failover to a different trust domain; unreachability = visible pending, never a guess. Offline grace: a pending purchase stays pending indefinitely — never expires into a loss, never grants offline; the durable receipt signature reconciles on the next launch with connectivity. Also the USDC balance preflight for the insufficient-funds class. |

## Bridge protocol 2 (`PaperMoonWalletBridge`)

Removed: `signMessage` (raw page-text signing — sweep MAJOR 1) and
`signTransaction` (the `not_implemented` stub). The page cannot
submit a transaction, supply signing text, or assert entitlement.
Added: `requestSignIn` (shell challenge → wallet sign → verify →
bind), `requestPurchase(itemId only)`, `claimRookie`,
`consumeRookieRide`, and sync getters `getBoundIdentity`,
`getEntitlement`, `getPriceInfo`. Entitlement changes are pushed via
`window.__paperMoonEntitlementChanged`.

## The bundle (AC-08)

`#coinBtn` → `requestPurchase('day-pass')`; `#passBtn` → rookie
claim. `launch()`'s gate reads only the shell mirror refreshed from
`getEntitlement()`; consumption happens via `consumeRookieRide`
after the ride is built. Datasets are byte-identical to v0.1.3
(verified by segment hash — see PATCH_NOTES).

## Verification

- `verification/run_harness.sh` — compiles the pure-logic modules
  with kotlinc and runs the spec PASS tests (65 checks: gate,
  Base58, AC-01/03/04/05/06/07/09 logic). Exit 0 = all pass.
- `verification/census.sh` — source/dex-style censuses (stub strings,
  SIGN TEST MESSAGE, raw signMessage exposure, plaintext prefs,
  bundle price literals / self-grant assignments).
- Android build: `./gradlew :app:assembleDebug` (CI runs the same).

## Not in this build (ruling-gated / flip-gated)

- AC-06 item 3 Terms text and AC-09's listing leg, AC-11: ride the
  flip (prep Part B-6). No merged PM Terms exist yet to amend.
- P2–P5 surfaces (entry/liquidation asymmetry, fill candle, score
  basis, HELD TO comparator): ship as sealed until the owner rules.
- Photosafety P-6 (~28 spike onsets/sec measured): no cure invented
  in this wave; cure-or-waiver is an owner ruling (prep B-5) that
  gates the flip, and AC-10's seal re-audit cannot issue over it.
  **Addendum (fix wave, 2026-10-20):** the ruling landed — cure, no
  waiver (decisions register item 19). A measurement harness now
  stands at `verification/photosafety_measure.js`; its jsdom
  readings (peak 1.10 onsets/sec on the sustained worst case) do
  not reproduce the ~28/sec figure, whose method is not preserved
  in this repo. No cure was attempted on an unreconciled
  measurement; the cure is still owed and re-measurement gates the
  flip.
- AC-10 seal re-audit: runs on the finished build at seal time.
