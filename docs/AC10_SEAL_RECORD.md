# AC-10 SEAL RECORD — PAPER MOON money line (`money-build-v0.2.0`)

**Sealed:** 2026-10-21 (AC-10 sprint, single builder)
**Code head at seal:** `0dc469a` (docs of this record ride on top;
no code changes after the seal head without re-opening this record)
**Baseline:** sealed v0.1.3 @ `64095e5` — untouched by this line.
**CI:** see "Verification totals" — run id recorded there.

## Gate state at seal (the line ships dark)

- `MoneyConfig.SALES_ENABLED = false` (MoneyConfig.kt:108);
  `railOpen = SALES_ENABLED && configuredReceivingWallet != null`
  — configuration is not enablement (bankai #3 MAJOR-1, cured).
- Configured receiving wallet (dedicated, pre-join decision):
  `41FFd2wK8Uoy75TVYDx6bXZzscPH3UihwpuiX8fAtQYe` — sweeps to the
  publisher wallet; keys in sealed custody, restore re-run PASSED
  2026-10-20 (independent derivation matched this address).
- Shared treasury address in money code: **0** (census, this head).
- No purchase can execute on this line as sealed. The flip is
  AC-11, owner-gated, staged in `docs/AC11_FLIP_DELTAS.md`.

## Per-AC verdicts (vs MONEY_BUILD_SPEC PASS conditions)

| AC | Verdict | Evidence at this head |
|---|---|---|
| AC-01 sign-in | **PASS** | Bridge exposes `requestSignIn`, no `signMessage` (census 0 / ≥1). Verifier is cofactorless ed25519 with canonical-encoding + small-order rejection (Ed25519.kt), harness-pinned incl. "rejects small-order pubkey" (14 AC-01 checks in the 87). Sign-in mints nothing, charges nothing. |
| AC-02 storage | **PASS** | EncryptedSharedPreferences in use (census ≥1), plaintext SharedPreferences in wallet/money code = 0 (legacy wipe excluded by design), backup exclusion for the encrypted store present (census ≥1). Identity state is native-only; bundle storage holds none of it. |
| AC-03 purchase construction | **PASS** | No `signTransaction` on the bridge, no `not_implemented` / step-2 stub strings (census 0). Unsigned USDC transfer built natively, blockhash-bound, buyer-signed in Seed Vault (12 harness checks). Money modules are pure logic over injected ports; no Android types. |
| AC-04 settlement | **PASS** | Rebuilt in the fix wave: per-signature journal (ACTIVE/GRANTED/REFUSED, terminal records never overwritten), single durable grant write, outcome-before-journal with launch reconcile, UNKNOWN retained and resolvable, real replay binding, in-flight marker released on refusal (15 crash-injection checks in the harness set; 7 AC-04 checks). |
| AC-05 entitlement | **PASS** | Grants derive only from verified settlement / the signed rookie blob; ledger is the single authority the bundle reads (13 harness checks). |
| AC-06 sheet + Terms element | **PASS, Terms adoption flip-gated** | Purchase sheet renders only bridge-supplied price info (item, USD anchor, rail amount, conversion basis, rail state). Terms text exists at `docs/TERMS_OF_USE.md` (honest settlement basis per P3/P4/P5 rulings); it is a **DRAFT** with `[EFFECTIVE_DATE]` unfilled — adoption is an AC-11 flip act, not a seal act. |
| AC-07 rookie ride | **PASS** | Server-counted, device-mirrored; consume is acknowledged-or-rollback (fix wave FIX 3) — a ride is never silently burned (9 harness checks + bundle gate test). |
| AC-08 bundle gate | **PASS** | Bundle self-grant censuses = 0 (`state.credits++`, `state.pass=true`, `state.credits--`); launch refreshes entitlement from the native ledger; bundle gate test 20/20 at this head. |
| AC-09 copy & price | **PASS** | Single price source (MoneyConfig); `$3.50` literal anywhere else = 0 (census); SIMULATE family and stale prices = 0 (census); daily kickers corrected to `DAILY EDITION · ROOKIE RIDE` / `ROOKIE RIDE · DAILY EDITION` and census-pinned. |

## P-6 photosafety (decision 19: cure in this build, no waiver)

- **Instrument:** `verification/photosafety_chromium.js` —
  headless Chromium 152 (WebView engine family), 412×915 @ DPR 2,
  real time, real bundle + datasets, worst case eth-defi (92 ATH /
  610 candles), full rides at native pacing. Metric: max `.spike`
  onsets in any sliding 1-second window vs **WCAG 2.3.1 (≤3)**.
- **Fidelity limits (stated):** headless-on-server is the upper
  envelope (frame drops only lower onset rates); it is not a
  physical-device reading (no device exists; owner-struck) and
  not a clinical photosensitivity assessment. The bankai record's
  ~28/sec could not be reproduced by any DOM-onset method on this
  code and is reconciled, as labelled inference, with spike
  *triggers* counted over a window (the densest 72-candle window
  holds exactly 28 ATH); its method is unpreserved.
- **Pre-cure:** max onsets/1s = 3 (1×) · **5 (4×) · 4 (16×) · 5
  (64×)** — bar breached at three speeds. Banners ≤1/1s everywhere
  (serialized 2.3 s; not a flash source).
- **Cure** (`0330015`): one rate gate at the `spawnSpike` choke
  point — 500 ms minimum onset interval at all speeds, bursts
  coalesce to the window max, pending spike ride-bound and cleared
  on launch/finish. Datasets untouched.
- **Post-cure:** max onsets/1s = **2 (1×) · 2 (4×) · 3 (16× — at
  the threshold, never over) · 2 (64×)**; onsets emitted 92/91/36/
  22 of 92 ATH. **Bar met at every speed on this instrument.**
  Verdict: **PASS (instrument-proven)**.

## Integrity checks at this head

- Datasets byte-identical to sealed v0.1.3: SCENARIOS..CATALYSTS
  segment 431,633 bytes, sha256 equal at `64095e5` and HEAD;
  whole-bundle diff vs sealed is confined to the money-line hunks
  (wallet chip, rookie state, entitlement refresh, copy, P-6 gate).
- Pre-join separation re-verified: dedicated receiving wallet
  configured; shared treasury address absent from money code.
- Both themes: bundle carries complete light (14 vars) and dark
  (13 overrides; only the size var `--tap` unoverridden) schemes
  under `color-scheme: light dark`; the native shell applies one
  fixed dark Material scheme by design and the purchase sheet
  (Material3 dialog) inherits it — no money surface has a broken
  light variant.
- Terms instrument: DRAFT, `[EFFECTIVE_DATE]` open — flip-gate.

## Verification totals (re-run at this head, not inherited)

- Kotlin harness (`verification/run_harness.sh`): **87 checks,
  0 failures**.
- Censuses (`verification/census.sh`): **all clean, exit 0**.
- Bundle gate test (jsdom): **20 checks, 0 failures**.
- jsdom photosafety continuity: 0.40–1.30 onsets/sec capped
  readings (build-comparison instrument only; Chromium governs).
- Chromium photosafety: table above.
- CI (assembleDebug + APK): run id recorded in PATCH_NOTES'
  AC-10 entry; the sealed-line CI at the fix-wave head was
  39552052995 SUCCESS. APK-dex census re-run: see PATCH_NOTES
  entry (same patterns, run against the CI APK's dex strings).

## What this seal does NOT do

It does not open the rail, adopt the Terms, change any listing,
or authorize a store submission. Those are AC-11 / owner acts.
Known environment limits carried honestly: `typecheck.sh` cannot
run in this VM (its `/tmp` dependency classpath is gone; CI is
the packaging authority) and no physical-device pass exists.
