# PATCH_NOTES — money-build-v0.2.0

> **Registry binding (2026-10-29, ARCHIVIST §3):** this file is bound to the estate fault registry `~/workspace/dapp-store-work/KNOWN_FAULT_CLASSES.md` — **Registry v1.0** (launched 2026-10-29), cited by version stamp in every seal. Rows for this shell's ingredients are tracked there under the same IDs — a PATCH_NOTES row and a registry row are never two descriptions of one fault: **KFC-001** sender class (device-proved on this title 2026-10-27; cured v0.2.5; shipped v0.2.8 verified CURED on the binary) · **KFC-002** boundary takeover (PM not yet assessed) · **KFC-003** unguarded WebView (residue OPEN — GameScreen patch rides v0.2.9) · **KFC-004** locked account-level field (FILED + STAFF-CONFIRMED, read-back pending).

## 2026-10-30 — v0.2.10: the Seeker Connect build (Gate 0 step 4) — IN PROGRESS

Branch `feature/v0210-seeker-connect` from the v0.2.8 source pin
(f5bcd16, tag `pin/v0.2.8-source`). Gate 0 verdict CONDITIONS
(C-01…C-31) + supplementary ruling S-1; evidence filed under
`~/workspace/dapp-store-work/paper-moon-rejection/gate0/`.

- **Toolchain (C-01/C-05):** commit 4f435d6 = the U-1 toolchain-only
  cherry-pick (compileSdk 36, AGP 8.9.1, Kotlin 2.2.20; targetSdk
  held 35) — the decomposed differential's middle point.
- **Dependencies (C-04/S-1):** commit 718d77c — direct
  clientlib-ktx 2.0.8 declaration removed; seeker-connect +
  seeker-connect-compose exactly 0.2.0 in (test exclusions
  re-attached); Compose BOM 2026.06.00 → 2026.06.01 (S-1 cascade).
  Candidate graph gate = diff vs proof run 41148451407 (pending
  the candidate's own proof run).
- **Migration (C-04/C-06/C-09/C-10/C-17/C-20):** commit 5907074 —
  WalletManager/WalletTokenStore deleted; ConnectWalletGateway is
  the one seam (Application-scoped, built in MainActivity.onCreate
  pre-setContent; WeakReference activity provider; wallet calls
  inside the host activity's lifecycleScope); session adapter over
  EncryptedPaperMoonStore (`seeker_connect_auth_v1`); migration
  seam deletes the legacy token unread; disconnect = deauthorize
  attempt → unconditional forget + ShellVerifier.unbind (cures the
  unbind-with-no-call-site gap); sealed error table for all seven
  SDK codes; dialogs at the composition root; WalletScreen per
  C-26. v025_gate.sh deleted in the same commit (C-19).
- **C-21 SESSION_CLOSED machinery (ship-blocker):** commit 68e3157 —
  durable UNRESOLVED_NO_SIGNATURE outcomes in the same unresolved
  guard; intent records carry buyer/blockhash/LVBH/amount/mint/
  recipient/sealed-kind/timestamp; reconciliation = recipient
  signature history + SettlementVerifier VERIFIED + buyer-source
  transfer match, settling through the normal grant path; block-
  height expiry proves non-submission and closes; never
  auto-retried, never closed as failed by the error. C-23 line in
  PurchaseCoordinator.UNRESOLVED_USER_MESSAGE; entitlement JSON
  surfaces paymentUnresolved; 30 s chain-read-only re-check in
  GameScreen. Money freeze (C-22): TransactionBuilder /
  SettlementVerifier / MoneyConfig byte-untouched.
- **Harness:** 143 checks, 0 failures (98 baseline + C-06 ×13,
  C-09 ×3, C-10 ×7, C-21 ×21, C-20 proxy ×1). First full CI proof
  run on the migrated tree: 41151447770 SUCCESS (proof vehicle
  branch proof/v0210-candidate — the proof workflow exists only on
  proof branches; the mirror adds that one file and is never
  merged).

- **Gates run so far (2026-10-30 evening):**
  - Candidate graph gate (S-1): PASS — resolved module sets vs
    proof run 41148451407 differ ONLY by the clientlib-ktx 2.0.8
    subtree leaving with the wrapper (web3/kborsh/salkt/buffer/
    serialization transitives of ktx only); clientlib survives at
    exactly 2.1.1 transitive-only; seeker-connect/-core/-compose
    exactly 0.2.0 (C-31).
  - Manifest delta (C-15): PASS — element multiset IDENTICAL to
    the v0.2.8 baseline (the <queries> block pre-existed via
    clientlib; now contributed by seeker-connect); delta is
    version fields + the INTERNET comment + merger ordering only.
    All hard bars hold.
  - C-04 four-part test: (i) direct declaration removed; (ii) no
    clientlib imports in the migrated files (census); (iii) one
    clientlib version in the filed graph; (iv) release-dex census
    via the run's R8 mapping: clientlib 22 class definitions / 0
    duplicates; seeker-connect 57 / 0; app 133 / 0.
  - C-11 socket census (candidate dex): Socket referenced only
    from com.neovisionaries.ws.client.* (clientlib's local
    association WebSocket client); HttpURLConnection + URL only
    from SolanaRpcChainReader; no okhttp, no ServerSocket, 0
    "nostr" strings, 0 ws(s):// literals; https literals = the
    RPC endpoint, the first-connect base, the frozen identity
    URI (+2 inert library doc strings).
  - Release: tag v0.2.10 (moved once, pre-release, after the
    compile fix below) → run 41152940391 SUCCESS. Candidate APK
    sha256 8181acae6e5a5624dd8179fee84f38628534ffcb670b4c95c423ede8eae6bc39;
    signer c3a2cc1e… (lineage-identical to v0.2.3–v0.2.9);
    versionCode 15 / 0.2.10 / compileSdk 36 / targetSdk 35 in
    badging. Dex byte-identical to the gate-vehicle build of the
    same tree (run 41153389230, dex sha256 4dd56d9c…) — the R8
    mapping pairing for the v029 audit is empirical, not argued.
  - CORRECTION (verification discipline): the §2.3 proof
    workflow's green conclusions were cited early in this build
    as compile evidence; its assemble step pipes through tee
    without pipefail, so a failed compile still concluded
    success. The failure (MainActivity SplashScreen onFinished →
    onDone, my rewrite's error) was caught by the signed release
    build 41152500481 and fixed in 68fb708. The proof runs'
    graph/manifest evidence was unaffected (resolution and
    manifest merging precede compilation; harvest is if:always()).
    From here the assembleRelease.log, not the conclusion, is the
    compile evidence.

- **Open gates:** candidate graph diff (S-1), v029 registration
  audit (C-19 instruments), manifest delta (C-15), sandbox census
  (C-16), network silence (C-30), render matrix (C-27), ProGuard
  re-derivation (C-28), runner pinning (C-29), runtime cells,
  TEKKADAN rounds, seal.

- **TEKKADAN cross-audit R1 (2026-10-30, two read-only scopes;
  every finding verified firsthand before fixing; fixes in
  afadbf4 + 715b481, harness 143 → 170 checks / 0 failures,
  census clean, C-22 freeze re-verified byte-identical):**
  - B-F1 CRITICAL (C-21 reconciler): the expiry close fired on
    an INCONCLUSIVE match scan — an unreadable signature list,
    a non-final verdict, or unreadable transfer details were
    all treated as "no match", so a landed payment could be
    closed FAILED (terminal) past LVBH and the buyer charged
    twice. Expiry now requires a conclusive scan: every
    candidate definitively evaluated, none matched.
  - B-F2 MAJOR (send seam): a CancellationException out of the
    wallet send (host activity destroyed mid-send — the exact
    C-21 ambiguity) escaped WalletSender entirely: no outcome
    recorded, intent stranded at AWAITING_WALLET, in-process
    wedge, and after relaunch covered by NO guard. Fixed at
    three layers: the gateway maps wallet-side cancellation
    (ensureActive distinguishes caller cancellation) and
    unclassified exceptions to Ambiguous; the coordinator maps
    ANY exception escaping the sender to
    UnresolvedNoSignature; the built transaction's context is
    stamped on the intent BEFORE the send, and a stranded
    AWAITING_WALLET converts into the no-signature machinery
    at launch reconciliation.
  - B-F3 MEDIUM (transfer details): greedy delta pairing over
    aggregated balances could fabricate a buyer→recipient
    transfer from colliding equal-amount legs. Extraction is
    now instruction-accurate (parsed outer+inner transfer
    legs, owners via the balance table); the delta fallback
    pairs only forced 1-to-1 groups and returns inconclusive
    (null) on any ambiguity.
  - B-F4 MEDIUM (durability): recordOutcome released the
    in-flight slot and the expiry close freed the buyer even
    when the durable write FAILED. Slot release is now
    conditioned on the write landing; a failed expiry-close
    write keeps the outcome open and blocking.
  - A-F1 MEDIUM (session cache): a failed re-save degraded to
    memory but left the SUPERSEDED envelope durable — the
    next process would have restored the replaced wallet. A
    failed save now evicts the durable key first.
  - A-F2 MEDIUM (lock discipline): the degraded get/set
    paths bypassed SessionStoreLock, racing disconnect's
    clear(). Every cache path now runs under the one lock.
  - A-F3 MEDIUM (connect): a cancelled connect() stranded
    uiState in Working permanently (the authorization
    collector refuses to sync while Working). connect()'s
    finally now restores the pre-connect state whenever the
    surface is still Working.
  - A-F4 LOW–MEDIUM (disconnect): CancellationException was
    swallowed by two runCatching layers and reported as an
    ordinary revoke failure. DisconnectSequence now completes
    the unconditional forget + unbind, then rethrows the
    cancellation; the gateway attempt is cancellation-
    transparent the same way.
  - A-F5 MEDIUM (disconnect truth): deauthorize revoked the
    token snapshotted BEFORE transact, but the SDK's
    establish() silently re-authorizes and may rotate the
    token first — the revoke could target a superseded token
    in either direction. Now the SDK's prescribed form:
    `transact { deauthorize(it.authToken) }`.
  - FREEZE (both scopes): TransactionBuilder /
    SettlementVerifier / MoneyConfig byte-identical to the
    pin — PASS, no findings.
  - **Candidate superseded:** the fixes touch the binary, so
    the first signed candidate (run 41152940391, sha256
    8181acae…) is superseded before sealing; tag v0.2.10 moves
    to the fixed head and the release + runtime battery re-run
    on the new artifact. Nothing was submitted from either.
  - **Runtime battery #1 (run 41153494991, on the superseded
    candidate — fixture intelligence for the re-run):** PASS
    R1/R1b/R2/R3/R6/R4/R5/R8 (no-wallet cold start, offline,
    migration prompt + reconnect, offline cache-only restore,
    disconnect, config-change connect, concurrent connect =
    exactly one sheet, render matrix filed). R7 FAIL is a
    CELL defect: the script never verified `pm disable`
    actually disabled the mock (output discarded); the wallet
    session in the window completed normally, i.e. the
    deauthorize succeeded and no residue note is the CORRECT
    app behavior — the re-run verifies the premise or grades
    FIXTURE-LIMITED. R11 FAIL is a CELL defect: all 21
    IllegalStateExceptions attribute to Gboard / Settings /
    media photopicker system processes, ZERO to
    com.hindsight.app — the re-run counts only the app's own
    pids. R9/R10 FIXTURE-LIMITED (the WebView game exposes no
    uiautomator text — sign-in/buy controls unreachable by
    this driver; harness proof carries the machinery, the
    seal names the gap). R12 FIXTURE-LIMITED (no tcpdump on
    the AVD). C-16 sandbox census was HOLLOW (run-as refuses
    the non-debuggable release APK) — the re-run attempts the
    root path on the AVD; the static census (stock cache
    never constructed) stands meanwhile. C-27 renders
    inspected firsthand: correct and legible at both sizes;
    the app palette is dark by design in both SYSTEM themes
    (dark-only scheme, unchanged from v0.2.8).
  - **Candidate #2:** tag v0.2.10 at aa16921 → release run
    41156067405, artifact 13025146200, APK sha256 c01f5be8…;
    signer lineage identical to the v0.2.3–v0.2.9 line.
    Gate-vehicle run 41156158841 (mirror of aa16921) produced a
    classes.dex byte-identical to candidate #2's — mapping
    pairing PROVEN; graph gate PASS (module sets identical to
    candidate #1, which passed S-1 against proof 41148451407);
    manifest element multiset identical to the v0.2.8 baseline
    (C-15 PASS).
  - **TEKKADAN cross-audit R2 (2026-10-30, read-only re-audit
    of the R1 fix diff 9c2dcab..aa16921):** all nine R1
    findings verified CURED (freeze re-verified byte-identical;
    harness independently re-run 170/0). Three NEW confirmed
    issues, all cured in commit 71f45e8:
    - **B-N1 (MEDIUM, empirically reproduced):** the B-F3
      transfer extractor matched parsed instructions by type
      only — a system-program SOL transfer (type "transfer",
      lamports, no token amount) in the same transaction
      poisoned extraction to null, stalling no-signature
      reconciliation for any bundled payment. FIX:
      scanInstructions now gates on program ==
      spl-token/spl-token-2022 before the type check; non-token
      instructions are skipped entirely.
    - **B-N2 (LOW-MEDIUM, fail-closed wedge):** a stranded
      record converted with a null LVBH (process death between
      the first AWAITING_WALLET write and the pre-send stamp)
      or a null recipient (provider unconfigured at conversion)
      could never resolve — its buyer was blocked forever.
      FIX, three parts: (a) purchase() now SEND-GATES on the
      build-context stamp — if the stamp cannot be made
      durable the purchase refuses BEFORE the wallet is asked
      (nothing submitted), so a sent intent always carries its
      LVBH durably and a null LVBH proves never-sent;
      (b) the reconciler closes a null-LVBH intent on a
      CONCLUSIVE no-match scan (an inconclusive scan still
      leaves it open; a match still wins); (c) launch
      reconciliation re-stamps a null recipient from the shell
      provider before reconciling.
    - **A-N1 (LOW, display-only):** connect()'s cancel-restore
      used the pre-attempt key snapshot — if cancellation
      landed after the SDK's establish() had already written
      the new authorization, the surface showed Disconnected
      for a live connection. FIX: the finally restores from
      connectedPublicKey() (the authorization as it stands).
    - Residuals dispositioned: A-F1's eviction is
      fire-and-forget (EncryptedPaperMoonStore.remove reports
      no failure) — a double fault defeating BOTH the put and
      the remove commit is the only resurrection path left;
      accepted at LOW, single-fault model fully cured.
      PurchaseFlow.beginIntent's unchecked persist is
      neutralized by construction: the send gate means no send
      can occur without a durable stamped record. The
      reconciler's 25-signature scan window is unchanged
      (pre-existing, both audit rounds).
    - Also cured in the same commit (R2-flagged residual):
      PurchaseFlow.reconcileOnLaunch's UNKNOWN marking now
      follows the B-F4 doctrine — a failed marking write
      reports no conversion and keeps the intent's slot.
    - Harness: **182 checks, 0 failures** (170 baseline + 12
      new R2-N1/R2-N2/R2-F4r cases). Census clean; freeze
      re-verified byte-identical.
  - **Candidate #2 superseded before sealing** by the R2 fix
    round; tag v0.2.10 moves to 71f45e8 (+ this record) and the
    release, gate vehicle, runtime battery and mid-session
    cell re-run on the new artifact. Battery #2 (run
    41156597899) and midcell #1 (run 41157023810) ran against
    candidate #2 — their cell verdicts are fixture/cell
    intelligence for the re-runs, not seal evidence.

## 2026-10-27 — v0.2.8: UI-audit fix pass (bundle + version fields) — THE resubmission candidate

One build pass carrying the combined findings of the two standing UI
audits of the v0.2.7 candidate (`V027_UI_AUDIT_DESIGN.md` — Auditor #1,
design/aesthetic: FAIL with 3 majors + evidence major; and
`V027_UI_AUDIT_FUNCTION.md` — Auditor #2, functionality/flows: no
majors, 2 minors) in
`~/workspace/dapp-store-work/paper-moon-rejection/`. Presentation/flow
layer only: **zero `.kt` changes** (27/27 hash-identical to v0.2.7;
only `app/build.gradle.kts` version fields, versionCode 12 → **13**,
versionName 0.2.7 → **0.2.8**), no money logic, and the scenario data
lines (SCENARIOS/CATALYSTS/STORIES) are **byte-identical to the freeze
`cff40e3a…`** (sha256 of each data line re-verified against
`freeze-data-lines.json` after the edits).

- **M1 (A#1) — verdict lesson covered by the ride banner:** the
  results overlay is now raised above the finale banner
  (`#resultsOverlay{z-index:80}` vs `.banner.over` z-75), and
  `showResults()` hides the in-flight banner and any lingering toast
  at the moment results render. The finale drain continues behind the
  overlay (its state machine is untouched — no `evBusy` latch risk)
  and still floats over the Herald keepsake as designed. Verdict word
  → lesson → equity now reads cleanly in every verdict render.
- **M2 (A#1) — ride toast over the pay button:** same `showResults()`
  clear (toast timer cancelled + hidden before the panel renders);
  post-results purchase toasts (grant/pending/error feedback) still
  render above the overlay as intended. The pay-panel render shows
  the full `DAY PASS · $3.50 USDC — RIDE AGAIN NOW` button uncovered.
- **M3 (A#1) — briefing F2 visual gate:** opening a dossier (toggle
  or Daily button) now scrolls the **dossier itself** to the top of
  the viewport (`block:'start'`, `scroll-margin-top:.5rem`) instead of
  centring the catalog row; the briefing hero is capped at 118 px and
  the brief-label rhythm tightened (.9rem → .55rem). The sticky
  LAUNCH bar is unchanged (coordinate-click launch, proven by
  Auditor #2, re-proven in the new QA hit-tests). New renders: at
  360×740 tagline at y168–213, WHERE WERE YOU? at y239–307 fully
  visible, LAUNCH pinned y664–730 and hit-testable; same pass at
  412×915 and in light.
- **M4 (A#1) — complete render evidence:** 57 renders —
  15 screens (quote, intro, onboarding p1+p2, home, briefing,
  briefing-config, briefing-mystery/CLASSIFIED, ride, verdict,
  verdict pay panel, panel-granted, wallet, C7 how-to) ×
  {360×740, 412×915} × {dark, light}, plus the Buffett-parity quote
  card — in `~/workspace/tools/pm-v028-staging/renders/`, every
  screen inspected as an image by the builder; light readability
  confirmed for every screen.
- **m1 (A#1) — brand asset black rectangles:** CSS blend treatment,
  assets untouched — light theme `invert(1)` + `mix-blend-mode:
  multiply`, dark theme `mix-blend-mode: screen` on `.intro-mark`,
  `.intro-wordmark`, `.hero-mark`. No hard black rectangle remains in
  either theme's intro/home renders.
- **m2 (A#1) — Mert kicker:** the `WORDS OF WISDOM` kicker is now
  emitted only on the Buffett-parity card; the Mert card carries its
  `— MERT · HELIUS` attribution only (both parities render-verified).
- **m3 (A#1) — onboarding colour discipline:** the PAPER MONEY `$`
  icon is teal (`var(--cyan)`) via a `paper-kind` class; RIDE ACCESS
  `∞` stays gold (computed colours verified: gold rgb(255,209,102)
  vs teal rgb(0,231,255) in dark).
- **m4 (A#1) — wallet overlay:** title is now `GET MORE RIDES`
  (§D literal); emphasis/order follows state — with a pass active,
  neither card wears the gold `primary-opt` border and ROOKIE RIDES
  returns first (previously the DAY PASS card kept the gold border
  and first position while already active); the price element is
  right-aligned/centred as a clean price (`justify-self:end`).
- **m5 (A#1) — verdict action stack:** `RIDE AGAIN ›` (primary) is
  now first, then OPEN HERALD KEEPSAKE, then CHANGE TIMELINE.
- **F-1 (A#2) — countdown formatter:** `renderEntitlement()` now
  rounds total minutes once and carries (`tm=Math.round(ms/60000)`,
  `h=floor(tm/60)`, `m=tm%60`). Boundary cases verified live against
  the built bundle: 59.9m → `1h 0m`, 119.9m → `2h 0m`, 1439.9m →
  `24h 0m`, 60.4m → `1h 0m`, 61m → `1h 1m` (previously `0h 60m` /
  `1h 60m` / `23h 60m`).
- **F-2 (A#2) — purchase retry:** `#coinBtn` no longer dismisses the
  wallet overlay on initiation; `buyDayPass()` closes the overlay
  only on a granted/pending outcome, so a rail error leaves the
  overlay open with the verbatim error toast and retry is one tap.
  Verified live: error stub → overlay open + toast
  `rail_error: settlement timeout` verbatim → one-tap retry with a
  granted stub succeeds and closes the overlay. All purchase-flow
  semantics (bridge calls, not_bound sign-in path, panel
  `watchPass`) unchanged.
- **Not landed / deferred:** A#1 m6 (ride-screen giant price clips at
  the right edge) — pre-existing, explicitly not charged to the
  overhaul (§E freezes ride-screen design); left untouched rather
  than folded into this pass. A#2 observations O-1…O-8 are
  not defects and were not actioned (O-8 was already dispositioned in
  the v0.2.7 entry).

**Gates (all on the built bundle / staged APK):** money harness
**98 checks, 0 failures**; jsdom bundle gate **20/20**; source census
**all clean**; `v025_gate.sh` **all clean** on the APK (dex
`ActivityResultSender.<init>` sites = **1**, MainActivity.onCreate);
`v026_gate.sh` **all clean** (packaged bundle byte-identical);
`v027_gate.sh` superseded by **`v028_gate.sh`** (same absence/presence
set, wallet title re-pinned to `GET MORE RIDES`, old countdown
formatter + sentence-case title added to the absence set,
`#resultsOverlay{z-index:80}` added to the presence set) — **all
clean** on source + APK, packaged bundle and the 4 ported assets
byte-identical. **Chromium QA (new harness `qa28.js`): 74 checks,
0 failures** — network silence **0 outbound at all 32 stages** incl.
wallet/pass panel (fetch/XHR also 0); opening total **4,929 ms**
≤ 6,000; occlusion proven by `elementFromPoint` hit-tests *and*
visual inspection of the renders (lesson hit-test returns the lesson
element; pay-button hit-test returns the button; briefing
tagline/WHERE hit-tests return the text elements, not the LAUNCH
bar). Scenario data isolation proof re-run: data lines
sha256-identical to the freeze record.

**Build note:** first two `assembleRelease` attempts died on the
known sandbox daemon class — root cause this time: the subagent shell
lacked `JAVA_TOOL_OPTIONS` (the IPv4-stack + truststore flags), so
the daemon handshake over IPv6-mapped loopback was intercepted and
the daemon expired unregistered; relaunch with the flags set
completed — BUILD SUCCESSFUL in 7m 19s (log
`~/workspace/tools/pm-build27.log`). Final APK
`paper-moon-v0.2.8-release.apk` sha256
`1e5506846064288c914f301d37fef3d7da63b8300974b5794bc749b0e16714d8`
(9,480,429 B), versionCode **13** / versionName **0.2.8**, signer cert
SHA-256 `c3a2cc1e…` — identical to v0.2.3–v0.2.7; sidecar `.sha256`
staged alongside in
`~/workspace/dapp-store-work/paper-moon-rejection/`. Final bundle
sha256 `7a0c362e2ccb1484931f03dbb016ac75013f6858e890ae402d86f4ee40605979`
(815,930 B). **v0.2.8 supersedes v0.2.7 as the resubmission
candidate**; v0.2.7 was never submitted.

## 2026-10-27 — v0.2.7: UI overhaul + scrub, one candidate (bundle + version fields)

The resubmission candidate. Built in one pass from the frozen v0.2.6
baseline (bundle sha256 `cff40e3a…`, APK `e7660e4e…`) per
`OVERHAUL_SPEC.md` v1.1 §F steps 1–12 (design source:
`~/workspace/ts-spaces/hindsight/`; full build record in
`RESUBMISSION_CHECKLIST.md`, same folder as the spec). Native shell:
**zero `.kt` changes** — only `app/build.gradle.kts` version fields
(versionCode 11 → **12**, versionName 0.2.6 → **0.2.7**); all 27
shell `.kt` files hash-identical to v0.2.6.

- **What the player now gets (was missing from the shipped game):**
  the designed opening — C0 daily quote card (Buffett/Mert by day
  parity, tap-skip, 2.8 s auto-advance) → C1 intro splash
  (wordmark lines at +300/+1100/+1900 ms, TAP TO ENTER, audio
  unlock) → C2 two-page onboarding **with the PEPE artwork**
  (packaged asset; designed fallback block if it ever fails to
  decode) → C3 home (Moon Fold mark, premise line, "Your first 3
  rides are free.", HOW strip, "?" how-to) → C4 **briefing replaces
  the dossier**: narrative tagline, WHERE WERE YOU?, THE CAST,
  WHAT TO EXPECT, context strip, unchanged position/TP/SL config,
  sticky LAUNCH RIDE with its caption → C5 first-ride goal banner
  (first briefing + first ride only) → C6 verdict **lesson line**
  (deterministic `resultLesson(completed, multiple)`; `completed`
  ⟺ TIME EXPIRED with ≥1 lot still open, captured before the
  auto-sell) + **inline day-pass panel** where the dead-end toast
  used to strand the player (price on the button; the panel
  watches entitlement and becomes RIDE AGAIN when the pass lands)
  → C7 how-to sheet (§D copy + fine print keeping "entry market
  caps are approximate") → C8 wallet overlay rewritten to §D
  (GET MORE RIDES; state-ordered cards; plain status line).
- **Content restored:** narratives + ride expectations for all
  **35/35** shipped scenario ids inlined as bundle data
  (`var NARRATIVES=` / `var RIDE_EXPECTATIONS=`); artwork ported as
  packaged assets — `moon-fold-logo.webp` (163,830 B),
  `paper-moon-wordmark.png` (511,752 B), `buffett-pixel.jpg`
  (144,339 B), `mert-pixel.svg` (94,993 B; 1,510 rects, generated
  offline from the artifact's `buildMertPixels()`, rect-for-rect
  verified), `pepe-frog.jpg` (the artifact's onboarding art).
  Scenario data isolation: SCENARIOS / CATALYSTS / STORIES lines
  **byte-identical to the freeze** (verified byte-for-byte and
  deep-equal) — the scrub's data is untouched; the overhaul is
  presentation + narrative data only.
- **Self-contained (owner directive):** dependency inventory in
  `DEPENDENCY_INVENTORY.md` — the frozen build had no hosted
  dependency to remove (no Supabase/Firebase/analytics anywhere);
  the overhaul added none. The artifact's server-backed systems
  (profile gates, `./actions` posts, passport/stamps) were **not**
  ported — content and presentation only. Shipped bundle contains
  zero http(s) strings, zero fetch/XHR/WebSocket/EventSource;
  instrumented full-flow run counted **0 outbound requests at every
  stage**; the only runtime fetch in the APK is the sanctioned
  Solana RPC read for user-initiated purchase verification.
- **Gates (final candidate):** money harness **98 checks, 0
  failures**; jsdom bundle gate **20/20**; source census **all
  clean** (see re-pin below); `v025_gate.sh` clean (dex
  `ActivityResultSender.<init>` sites **exactly 1**,
  MainActivity.onCreate; leverage strings 0); `v026_gate.sh` clean
  (production-memo patterns 0 in source + APK bundle);
  `v027_gate.sh` clean (overhaul absence/presence set, packaged
  bundle + 4 assets byte-identical); Chromium QA **18 checks, 0
  failures** — opening **4,956 ms ≤ 6,000**, F2 first-screenful
  pass at 360×740, both themes rendered, 17 renders in
  `~/workspace/tools/pm-overhaul-staging/renders/`; apksigner
  verifies, signer SHA-256 `c3a2cc1e…` identical to v0.2.3–v0.2.6.
- **Census re-pin (LIBRARIAN keeper item 10):** three pre-join pins
  in `verification/census.sh` encoded assumptions the owner has
  since ruled against; all three re-pinned 2026-10-27, each keeping
  its teeth (mutation-tested): (i) the "shared treasury address in
  money code (must be 0)" line — the address **is** the configured
  receiving wallet since the owner's 2026-10-21 ruling (re-confirmed
  2026-10-27, window closed by decision) → the pin now requires the
  address **exactly once** in money code, only as
  `CONFIGURED_RECEIVING_WALLET` in `MoneyConfig.kt`; (ii) the bundle
  `$3.50`-literal ban → the §D copy standard states the price in
  player copy; the pin now forbids any *other* cents-denominated
  price literal in bundle copy (data blocks excluded — historical
  prices are content), plus a new positive check that the live price
  element stays bridge-fed (`usdAnchor`); (iii) the
  outside-single-source price check rescoped to native code (bundle
  copy governed by (ii)). `MoneyConfig.kt` itself unchanged.
- **Deviations from spec, recorded:** (1) daily kicker kept as the
  shipped `DAILY EDITION · ROOKIE RIDE` (over §D's abbreviated cell)
  and the LAUNCH caption carried inside the button — both required
  by the money build's AC-09 truth guard (daily copy names the
  rookie ride, never FREE); (2) wallet price element now renders
  `$3.50 USDC` (§D-exact) instead of the shipped concatenation;
  (3) two artifact narrative taglines minimally reworded to keep
  the sealed O-1 zero-leverage-strings gate: PNUT "…mourned a
  squirrel with leverage." → "…with total conviction."; MOODENG
  expectation "futures leverage" → "futures froth". Narrative data
  is otherwise verbatim (35/35, diff set = exactly those two).
- **Build record:** final bundle sha256
  `726fc2ce80c1b01b2eb45c02685d049cc93c12565dfa8e36d804d801ef67aa7b`
  (815,175 B). Final APK `paper-moon-v0.2.7-release.apk` sha256
  `a78b235c3e8864df4113beda8154eab3b50d761dfb32e0b7168f932de28c71b2`
  (9,480,253 B), versionCode **12** / versionName **0.2.7**,
  sidecar `.sha256` verifies. (Build attempts 22–24 were lost to
  sandbox failures — two daemon deaths under load and one VM
  reboot, all before packaging; build25 packaged the pre-tagline-fix
  bundle and was superseded by build26 on the final bundle. No
  candidate was staged from a failed or superseded build.)

## 2026-10-27 — v0.2.6: production-memo scrub (bundle-only change)

Owner found it on device in v0.2.5 (2026-10-27 09:53 PDT): the
ride-launch dossier rendered a **"Coverage:"** block — a raw
data-production memo from the scenario data (e.g. for "The Alpha":
"Mixed: mexc, defillama. entry mcap approx; window start shrunk by
21 bucket(s)… forward-filled 43 candle(s)… window end truncated to
ATH candle…"). Owner directive: "Extensively scrub for these types
of things." Full record: `SCRUB_INVENTORY.md` in
`~/workspace/dapp-store-work/paper-moon-rejection/`.

- **The class:** every one of the 35 scenario objects carried a
  `coverage_note` field — pipeline working notes (gap forward-fills,
  bucket/window bookkeeping, source-mixing and outage notes,
  producer approx flags, raw API endpoints). `renderDossier`
  printed it verbatim; `sourceLabel` *parsed the memo* to derive
  the player-facing SOURCE label.
- **Bundle fix:** `coverage_note` deleted from all 35 scenarios;
  the "Coverage:" paragraph (and its `Verified historical window.`
  fallback) removed from `renderDossier`; each scenario's
  `sources` now carries the explicit venue list (e.g.
  `"mexc,defillama"`) and `sourceLabel` is a direct map/join of
  it — **all 35 SOURCE labels byte-identical to v0.2.5** (table in
  the inventory). The dead `mixed:'Multiple documented venues'`
  fallback is removed. Five internal ticket-label tokens
  (`RV-6c`, `RV5d`, `AC-08`, `AC-10`, `MAJOR-3`) stripped from code
  comments; rationale prose kept. Everything else in the bundle is
  byte-identical to v0.2.5 (changed lines: 56, 64, 79, 82, 110,
  112, 131, 140, 148 — data, label map, label function, dossier,
  comments). New bundle sha256
  `cff40e3a47c7fb9d99b20a197eb6ba2237273284748e0554f272a976e3308bad`.
- **Source of record fixed:** the pipeline
  (`~/workspace/research_notes/hindsight/fetch.py`,
  `fetch_solana.py`, `fetch_wave2.py`) now keeps the memo as the
  internal `_coverage_note` (report-only, never exported) and
  exports `sources` as the explicit venue list; the three pipeline
  JSONs were scrubbed identically (35 fields removed; all other
  data deep-equal). Sibling variants scrubbed the same way:
  hindsight-seeker static space (source + standard build copy)
  and the fullstack variant's two data JSONs + `Scenario` type
  declarations. Frozen staging/audit copies were left
  byte-identical for provenance (classified in the inventory §8).
- **New standing gate:** `verification/v026_gate.sh` — zero
  memo-pattern hits allowed in the source bundle or the bundle
  packaged in the APK (pattern list in the inventory §9).
- **Shell: UNCHANGED.** No Kotlin source file differs from
  v0.2.5 (verified by hash manifest); the only native-side change
  is the version bump (versionCode 11, versionName "0.2.6"), so
  the owner's v0.2.5 press evidence carries over. Gates: money
  harness 98/0, bundle gate 20/20, v025 gate clean, v026 gate
  clean. APK: `paper-moon-v0.2.6-release.apk` (staged in the
  rejection folder; sha256 in its `.sha256` sidecar). NOT
  submitted.

## 2026-10-27 — v0.2.5: button-press crash fix + leverage removal (O-1)

Two changes on top of v0.2.4 (whose rookie-allowance fix is unchanged).
Diagnosis: `~/workspace/dapp-store-work/paper-moon-rejection/BUTTON_CRASH_DIAGNOSIS.md`.

- **Activity-result crash fix (the real "app crash" behind the
  v0.2.3 rejection, coexisting with the v0.2.4 gating dead-end).**
  MWA's `ActivityResultSender` registers an activity-result launcher
  in its constructor; AndroidX throws `IllegalStateException` when
  registration happens at or after STARTED. The app built senders
  during composition — the wallet bridge's property initializer
  (assembled when the game screen composes, after PLAY) and the
  wallet screen's `remember{}` — i.e. always at RESUMED, so every
  PLAY / WALLET press crashed, deterministically, on every device.
  Device-proven by the owner on a Pixel 7 Pro (v0.2.4: title screen
  renders, every button press crashes). **Fix:** the single sender
  is created in `MainActivity.onCreate`, before `setContent`, and
  constructor-injected into `PaperMoonWalletBridge` (property
  initializer deleted) and passed as a parameter to `WalletScreen`
  (`remember{}` construction deleted) via `GameScreen`. New gate
  (`verification/v025_gate.sh` + `dex_sender_sites.py`): source scan
  finds exactly 1 construction site (`MainActivity.kt:45`) and 0
  `registerForActivityResult` calls; the built APK's dex contains
  exactly 1 `ActivityResultSender.<init>` invoke site —
  `MainActivity.onCreate`, immediately after `super.onCreate`.
- **Leverage removal (owner ruling O-1, 2026-10-27 — the standing
  no-leverage rule holds). Bundle change this time.** Removed: the
  "Leverage · liquidation on candle close" selector row
  (1× SAFE / 2× SPICY / 3× DEGEN); the per-lot leverage multiplier
  in `lotValue` (now spot); the leverage-only `liquidateIfNeeded`
  and its call; the score's leverage factor; the 1×-corrected
  held-value comparison; leverage-aware stop-loss disabling
  (`slDead`, "PAST LIQUIDATION" annotations); the results screen's
  LEVERAGE cell; the `lev` config field and the inert ride `liq`
  flag. All rides run at 1×; every removed mechanism was a no-op or
  identity at 1×, so 1× play/scores are unchanged. Bundle scan:
  `/leverag/i` and all selector/mechanic patterns = 0 in source and
  in the packaged bundle. Three historical-copy mentions minimally
  reworded ("LEVERAGE GETS LIQUIDATED" → "THE GREAT LIQUIDATION"
  ×2, "leveraged longs" → "margin longs", "Leveraged futures
  arrived" → "Futures trading arrived"); disclosed retentions: one
  story line naming the historical "liquidation cascade" and the
  editorial DEGEN in a scenario headline (not the tier label).
  README feature line updated. **New bundle sha256:**
  `321daa9542296d67e61131de23304a4f0df95ed5f7559f2f6e28692d244d4e87`
  (782,376 B; supersedes `71f59b0e…` as the hash of record — the
  bundle gate's expectation). Scenario data (35 scenarios: ids,
  candles, dates, market caps) byte-identical apart from the three
  copy rewordings.
- Version: versionCode 9 → 10, versionName 0.2.4 → 0.2.5.
- Verification: money harness **98 checks, 0 failures**; bundle gate
  **20/20** on the new bundle; v0.2.5 gate **all clean**. Census:
  one pre-existing line — "shared treasury address in money code
  (must be 0) = 1" at `MoneyConfig.kt:88`, the owner-ruled raaru.skr
  receiving-wallet constant; a stale pre-join gate line, not in this
  diff and not a regression.
- Build: signed release APK, signer cert SHA-256 identical to
  v0.2.3/v0.2.4 (`c3a2cc1e…4df7`, CN=raaru.skr). First build attempt's
  Gradle client was lost to an exec-session reap at the R8 step;
  relaunched detached, BUILD SUCCESSFUL in 6m 49s (log
  `~/workspace/tools/pm-build13.log`). **Gate status:** emulator
  cold-run still environment-blocked (no KVM on this host); the
  crash fix's device proof is the owner's Pixel re-test of this
  exact APK — PENDING; nothing submitted.

### v0.2.5 amendment — same day (owner scope change + additions, 2026-10-27 08:02–08:04 PDT)

- **Title screen (owner additions):** HomeScreen now shows the
  Paper Moon emblem above the wordmark —
  `Image(painterResource(R.mipmap.ic_launcher_foreground))`, the
  adaptive icon foreground artwork (the launcher/store icon of
  record's own resource). The slogan "Paper money. Real charts.
  The moon you missed." sits directly under the logo/wordmark
  block; verified **exactly once** in the HomeScreen composition
  (it was already the Home subtitle — not duplicated); the
  "3 rookie rides free per wallet · day pass $3.50 USDC / 24h"
  line is unchanged. No other redesign.
- **SETTINGS scope change:** the owner reported SETTINGS crashed
  too on v0.2.4. Full press-path audit + Robolectric probe +
  release-dex inspection (`PRESS_PATH_AUDIT.md` in the rejection
  folder): no shared source/packaging fault exists; the probe
  reproduces PLAY/WALLET crashing with the diagnosed exception
  while SETTINGS passes in the faulted configuration, and all
  three entries pass on this tree (entry trace recorded in the
  checklist). Because the fault could not be pinned from source,
  the brief's fallback was built: **`paper-moon-v0.2.5-DIAGNOSTIC.apk`**
  (sha256 `8ae448c160e2eaa60347cc3a365a7b48ff0a40a173ba6fe5600377e007e1318b`,
  8,645,060 B; versionName `0.2.5-diag`; crash-trap tree at
  `~/workspace/tools/pm-diagnostic/`, probe tree at
  `~/workspace/tools/pm-presspath/`) — one owner install returns
  the stack trace if any press still crashes on device. SETTINGS
  status on the record: unexplained at device layer,
  instrumented, awaiting evidence.
- **Final staged build:** the release was rebuilt with the title
  changes (log `~/workspace/tools/pm-build15.log`, BUILD
  SUCCESSFUL in 6m 8s). `paper-moon-v0.2.5-release.apk`, sha256
  **`d96f8134c1dc476e5b1d30890dd5cb6828d7ec45549d11f1514899d0b32d5d6d`**
  (8,645,056 B), staged at
  `~/workspace/dapp-store-work/paper-moon-rejection/` with sidecar
  verified; the earlier same-day stage (`093dc59f…`) is
  superseded. Full verification re-run on the final build: money
  harness **98/0**, bundle gate **20/20**, v0.2.5 gate **all
  clean** (single dex sender site, MainActivity.onCreate),
  census — the one known `MoneyConfig.kt:88` flag only. Bundle
  sha256 unchanged (`321daa95…`).

## 2026-10-27 — v0.2.4: first-launch playability (dApp Store resubmission fix)

Rejection cure for v0.2.3 reason 1 ("crashed or became unusable
during launch or basic review"; diagnosis:
`~/workspace/dapp-store-work/paper-moon-rejection/CRASH_DIAGNOSIS.md`
— not a crash: a fresh install booted with 0 rides and every ride
gated behind MWA connect → sign-in signature → rookie claim, so a
wallet-less reviewer could play nothing).

- **Device-local rookie allowance (native-only fix; the game bundle
  is byte-identical to v0.2.3).** `RookieLedger` gains a second,
  install-scoped grant in the same encrypted, backup-excluded store,
  keyed by the reserved non-identity `local-install` (contains `-`
  and `l`, both outside Base58 — no wallet key can collide).
  `PaperMoonWalletBridge` ensures the grant when it assembles (the
  one moment guaranteed to precede every game call), and its
  entitlement/consume paths now use the ACTIVE scope: the bound
  identity's grant when bound, the local allowance when not.
  `claimRookie` and `requestPurchase` are unchanged — the identity
  grant stays one-claim-per-wallet, purchases stay bound-only
  (`not_bound` for unbound purchase attempts remains correct).
  Scopes never merge: binding a wallet neither moves, tops up, nor
  clears the local record; local play writes no identity scope,
  receipt, or entitlement. Each scope is one-time, count only moves
  down, uninstall is the only reset of the local scope.
- Version: versionCode 8 → 9, versionName 0.2.3 → 0.2.4.
- Verification: money harness **98 checks, 0 failures** (86
  pre-existing + 12 new AC-07L cold-start checks: fresh state → 3
  rides playable unbound; grant never renews; exhaustion sticks;
  identity claim after local play grants its own full set; unbound
  purchase still refused); bundle gate **20/20** (bundle untouched).
  Census: one pre-existing line — "shared treasury address in money
  code (must be 0) = 1" at `MoneyConfig.kt:88`, the owner-ruled
  raaru.skr receiving-wallet constant; a stale pre-join gate line,
  not in this diff and not a regression.
- Build: signed release APK built locally (the sandbox's Gradle
  daemon/egress faults were root-caused and worked around in the
  build environment only — no project files changed for the
  toolchain; record in the resubmission checklist). Output:
  `paper-moon-v0.2.4-release.apk`, sha256
  `2adec04356d035d47dc5deaa91b3a998253a63742f9e31d3914d92d259067e81`,
  signer cert SHA-256 identical to v0.2.3
  (`c3a2cc1e…4df7`, CN=raaru.skr). Staged with the resubmission
  package at `~/workspace/dapp-store-work/paper-moon-rejection/`
  (checklist + reviewer packet there).
- Untouched by design: pricing, scenarios, verdicts, pass terms,
  receiving wallet, rail logic, and the leverage selector (owner
  ruling O-1 still pending).

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
