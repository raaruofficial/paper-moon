# PAPER MOON — Android app

**PAPER MOON** is a crypto time-travel arcade game for the Solana Seeker phone.
Paper money. Real charts. The moon you missed.

Pick one of 35 historical runs (BTC, ETH, DOGE, SHIB, WIF, PEPE, TRUMP, BONK,
PNUT, and more — plus REKT scenarios like LUNA). Every ride stakes a flat
$1,000 paper pot on the real candles, with position sizing (25-100%),
1x/2x/3x leverage, take-profit and stop-loss orders, and time compression.
Three rookie rides per session, then a simulated coin insert (25 cents a
play) or a simulated $5 session pass — this build charges nothing. SIMULATED FUNDS — real historical prices, paper money.

This repo is the **native Android app** (Kotlin + Jetpack Compose): native
splash, home, wallet, and settings screens, with the game rendering inside a
WebView on the game screen — and a `PaperMoonWallet` JS bridge as the seam
where the native MWA wallet meets the game.

> Status: v0.1.1 — the fixed successor to the CLOCK IN build (v0.1.0).
> Native scaffold with MWA 2.0 connect/session/sign, JS bridge, and CI.
> The game ships as a static export bundled at
> `app/src/main/assets/game/index.html` (35 scenarios, fully offline).

## Architecture

```
┌─────────────────────────────────────────────────┐
│  MainActivity (Compose Navigation)              │
│  splash → home → game / wallet / settings       │
├─────────────────────────────────────────────────┤
│  WalletScreen ──► WalletManager ──► MWA 2.0     │  native, no web
│                      │            (Seed Vault)   │
│                      ▼                           │
│  GameScreen: WebView + PaperMoonWallet bridge   │  game content only
│     window.PaperMoonWallet.{                    │
│       connectWallet, signMessage,                │
│       signTransaction, getPublicKey }            │
└─────────────────────────────────────────────────┘
```

- **Native layer** (`app/src/main/java/com/hindsight/app/`): lifecycle-correct
  single activity, Compose Navigation, MWA session management, JS bridge.
- **Game content**: static export bundled under `app/src/main/assets/game/`,
  loaded as `file:///android_asset/game/index.html`. Fully offline — all
  scenario candles, catalysts, newspaper stories, and art ship in the file.
- **Wallet**: official `com.solanamobile:mobile-wallet-adapter-clientlib-ktx:2.0.8`.
  Connect → association intent → Seed Vault approval → session. The game
  detects `window.PaperMoonWallet` and uses it for wallet identity when
  present; otherwise it runs standalone with its simulated arcade wallet.
  Signing happens in the wallet; the app only ever sees public keys and
  signatures. All money stays simulated until the production cutover.

### JS bridge contract (`window.PaperMoonWallet`)

| Method | Args | Result |
|---|---|---|
| `getVersion()` | — | `"1"` (sync) |
| `isConnected()` | — | `true`/`false` (sync) |
| `getPublicKey()` | — | Base58 address or `""` (sync) |
| `connectWallet(requestId)` | request id | ack sync; async → `__paperMoonWalletResolve(id, json)` |
| `signMessage(requestId, message)` | request id, text | async → `{ ok, data: { signature } }` |
| `signTransaction(requestId, txBase64)` | request id, tx | **stub** — resolves not_implemented until the payments cutover |

Async results arrive as `window.__paperMoonWalletResolve(requestId, resultJson)`
where `resultJson` is `{ ok: true, data: {...} }` or `{ ok: false, error: "..." }`.

## Build

### From `git clone` to APK (no Android Studio needed)

CI builds a debug APK on every push to `main` — download it from the
workflow's **Artifacts** (`paper-moon-debug-apk`).

### Local build (Android Studio)

1. Open this directory in Android Studio (it syncs Gradle automatically).
2. Run ▶ on a device or emulator (API 28+; Seeker runs Android 15).
3. Or from a terminal with Gradle installed: `gradle :app:assembleDebug`
   → `app/build/outputs/apk/debug/app-debug.apk`.

Requirements: JDK 17, Android SDK with API 35, Gradle 8.11.1 (CI provisions it).

## Release signing (no secrets in this repo — ever)

Release APKs are signed from GitHub Secrets. Set these in
**Settings → Secrets and variables → Actions**:

| Secret | Contents |
|---|---|
| `KEYSTORE_BASE64` | Release keystore, base64-encoded |
| `KEYSTORE_PASSWORD` | Keystore password |
| `KEY_ALIAS` | Key alias |
| `KEY_PASSWORD` | Key password |

Then tag: `git tag v0.1.1 && git push origin v0.1.1` → signed APK in
`paper-moon-release-apk`.

> Guard the signing keystore like the publisher wallet.

## Contact

X: [@raaruofficial](https://x.com/raaruofficial)
