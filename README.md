# HINDSIGHT — Android app

**HINDSIGHT** is a crypto time-travel arcade game for the Solana Seeker phone.
Hindsight is 20/20: pick one of 35 historical runs (BTC, ETH, DOGE, SHIB, WIF,
PEPE, TRUMP, BONK, PNUT, and more — plus REKT scenarios like LUNA), get $1,000
in paper money, and ride the real candles with time compression. 25¢ per play,
SIMULATED FUNDS — real historical prices, paper money.

This repo is the **native Android app** (Kotlin + Jetpack Compose): native
splash, home, wallet, and settings screens, with the game rendering inside a
WebView on the game screen — and a `HindsightWallet` JS bridge as the seam
where the native MWA wallet meets the game.

> Status: native scaffold complete (mirrors the VOIDRUNNER app pattern);
> MWA 2.0 connect/session/sign scaffolding, JS bridge, CI. The game itself
> ships as a static export ("Hindsight Seeker" artifact) bundled at
> `app/src/main/assets/game/index.html` — added after the export lands.

## Architecture

```
┌─────────────────────────────────────────────────┐
│  MainActivity (Compose Navigation)              │
│  splash → home → game / wallet / settings       │
├─────────────────────────────────────────────────┤
│  WalletScreen ──► WalletManager ──► MWA 2.0     │  native, no web
│                      │            (Seed Vault)   │
│                      ▼                           │
│  GameScreen: WebView + HindsightWallet bridge   │  game content only
│     window.HindsightWallet.{                    │
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
  detects `window.HindsightWallet` and uses it for wallet identity when
  present; otherwise it runs standalone with its simulated arcade wallet.
  Signing happens in the wallet; the app only ever sees public keys and
  signatures. All money stays simulated until the production cutover.

### JS bridge contract (`window.HindsightWallet`)

| Method | Args | Result |
|---|---|---|
| `getVersion()` | — | `"1"` (sync) |
| `isConnected()` | — | `true`/`false` (sync) |
| `getPublicKey()` | — | Base58 address or `""` (sync) |
| `connectWallet(requestId)` | request id | ack sync; async → `__hindsightWalletResolve(id, json)` |
| `signMessage(requestId, message)` | request id, text | async → `{ ok, data: { signature } }` |
| `signTransaction(requestId, txBase64)` | request id, tx | **stub** — resolves not_implemented until the payments cutover |

Async results arrive as `window.__hindsightWalletResolve(requestId, resultJson)`
where `resultJson` is `{ ok: true, data: {...} }` or `{ ok: false, error: "..." }`.

## Build

### From `git clone` to APK (no Android Studio needed)

CI builds a debug APK on every push to `main` — download it from the
workflow's **Artifacts** (`hindsight-debug-apk`).

> The GitHub App cannot write `.github/workflows/*` — add `android.yml`
> (in this repo at `.github/workflows/android.yml`) manually via the GitHub
> web UI after creating the repo, same as the VOIDRUNNER setup.

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

Then tag: `git tag v0.1.0 && git push origin v0.1.0` → signed APK in
`hindsight-release-apk`.

> Guard the signing keystore like the publisher wallet.

## Repo setup (user steps)

1. Create the public repo `raaruofficial/hindsight` on GitHub (the GitHub App
   gets 403 on `create_repository` — this step is yours).
2. Push this directory: `git remote add origin git@github.com:raaruofficial/hindsight.git && git push -u origin main`
3. Add `.github/workflows/android.yml` via the GitHub web UI (copy from this repo).
4. Add the four signing secrets above.
5. Push → CI builds the debug APK → install on Seeker → wallet-connect test.
