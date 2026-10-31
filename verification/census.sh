#!/bin/sh
# Money-build censuses (AC-10's method, run at source level here and
# re-run on the built APK's dex at seal). Every count below must be 0
# unless the line says otherwise. Exit 1 on any violation.
#
# RE-PINNED 2026-10-27 (v0.2.7, LIBRARIAN keeper item 10): three pre-join
# pins encoded assumptions the owner has since ruled against, and are
# re-pinned to the ruled configuration below (each keeps its teeth):
#   - the receiving wallet IS raaru.skr (owner ruling 2026-10-21): the
#     treasury address must occur EXACTLY ONCE in money code, as the
#     CONFIGURED_RECEIVING_WALLET constant in MoneyConfig.kt;
#   - the day-pass price is stated in player copy (OVERHAUL_SPEC v1.1
#     sec. D: price always stated, amount + currency + what it buys):
#     bundle copy may carry the sanctioned $3.50 literal and no other
#     cents-denominated price; the live price element stays bridge-fed
#     (positive check), and native code outside MoneyConfig.kt still
#     carries no price literal at all.
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MAIN="$ROOT/app/src/main"
fail=0
count() { # name, pattern, path...
    name="$1"; pattern="$2"; shift 2
    n=$(grep -r -- "$pattern" "$@" 2>/dev/null | wc -l)
    echo "census $name = $n"
    if [ "$n" -ne 0 ]; then fail=1; grep -rn -- "$pattern" "$@" | head -5; fi
}
count "SIGN TEST MESSAGE (AC-01: must be 0)" "SIGN TEST MESSAGE" "$MAIN"
count "not_implemented stub (AC-03: must be 0)" "not_implemented" "$MAIN"
count "step-2 stub strings (AC-03: must be 0)" "lands in step 2" "$MAIN"
count "bridge signMessage method (AC-01: must be 0)" "fun signMessage" "$MAIN/java/com/hindsight/app/wallet"
count "bridge signTransaction method (AC-03: must be 0)" "fun signTransaction" "$MAIN/java/com/hindsight/app/wallet"
# The one legitimate getSharedPreferences call is the legacy wipe in
# EncryptedPaperMoonStore (opens paper_moon_wallet only to clear it).
n=$(grep -rn -- "getSharedPreferences" "$MAIN/java/com/hindsight/app/wallet" "$MAIN/java/com/hindsight/app/money" 2>/dev/null | grep -v "EncryptedPaperMoonStore.kt" | wc -l)
echo "census plaintext SharedPreferences storage (AC-02: must be 0, wipe excluded) = $n"
if [ "$n" -ne 0 ]; then fail=1; fi
count "bundle self-grant credits++ (AC-08: must be 0)" "state.credits++" "$MAIN/assets/game"
count "bundle self-grant pass=true (AC-08: must be 0)" "state.pass=true" "$MAIN/assets/game"
count "bundle self-grant credits-- (AC-08: must be 0)" "state.credits--" "$MAIN/assets/game"
count "bundle SIMULATE purchase family (AC-09: must be 0)" "SIMULATE COIN INSERT\|SIMULATE SESSION PASS\|SIMULATE LIFETIME" "$MAIN/assets/game"
count "bundle stale prices 25c/$5 pass (AC-09: must be 0)" "25¢ / 1 PLAY\|\$5 / UNLIMITED" "$MAIN/assets/game"
count "bundle FREE RIDE daily kicker (AC-09: must be 0)" "DAILY EDITION · FREE RIDE" "$MAIN/assets/game"
count "bundle FREE daily launch label (AC-09: must be 0)" "FREE · DAILY EDITION" "$MAIN/assets/game"
# Re-pinned (see header): bundle copy may state the sanctioned $3.50 and
# no other cents-denominated price literal.
n=$(grep -v -E '^var (SCENARIOS|CATALYSTS|STORIES|NARRATIVES|RIDE_EXPECTATIONS)=' "$MAIN/assets/game/index.html" 2>/dev/null | grep -o '\$[0-9][0-9,]*\.[0-9][0-9]' | grep -v '^\$3\.50$' | wc -l)
echo "census bundle price literals other than sanctioned \$3.50 (AC-09 re-pinned 2026-10-27: must be 0; data blocks excluded \u2014 historical prices are content, not offers) = $n"
if [ "$n" -ne 0 ]; then fail=1; grep -v -E '^var (SCENARIOS|CATALYSTS|STORIES|NARRATIVES|RIDE_EXPECTATIONS)=' "$MAIN/assets/game/index.html" | grep -o '\$[0-9][0-9,]*\.[0-9][0-9]' | grep -v '^\$3\.50$' | head -5; fi
# Re-pinned (see header): owner-ruled receiving wallet, exactly once,
# only as the MoneyConfig.kt constant.
n=$(grep -rn -- "FRShnxX7vi64gtBoA6JPmH9MBuVqtheEQaxA1D17wKdP" "$MAIN/java/com/hindsight/app/money" 2>/dev/null | wc -l)
loc=$(grep -rln -- "FRShnxX7vi64gtBoA6JPmH9MBuVqtheEQaxA1D17wKdP" "$MAIN/java/com/hindsight/app/money" 2>/dev/null)
echo "census receiving-wallet (treasury) address in money code (owner-ruled 2026-10-21: exactly 1, MoneyConfig.kt only) = $n"
if [ "$n" -ne 1 ] || [ "$loc" != "$MAIN/java/com/hindsight/app/money/MoneyConfig.kt" ]; then fail=1; grep -rn -- "FRShnxX7vi64gtBoA6JPmH9MBuVqtheEQaxA1D17wKdP" "$MAIN/java/com/hindsight/app/money" | head -5; fi
# Positive checks (must be >= 1)
pos() {
    name="$1"; pattern="$2"; shift 2
    n=$(grep -r -- "$pattern" "$@" 2>/dev/null | wc -l)
    echo "census $name = $n (must be >= 1)"
    if [ "$n" -lt 1 ]; then fail=1; fi
}
n=$(grep -rn -- '\$3\.50' "$MAIN/java" "$MAIN/res" 2>/dev/null | grep -v "money/MoneyConfig.kt" | wc -l)
echo "census price literal in native code outside the single source (AC-09, bundle copy governed separately: must be 0) = $n"
if [ "$n" -ne 0 ]; then fail=1; grep -rn -- '\$3\.50' "$MAIN/java" "$MAIN/res" | grep -v "money/MoneyConfig.kt" | head -5; fi
pos "EncryptedSharedPreferences in use (AC-02)" "EncryptedSharedPreferences" "$MAIN/java"
pos "backup exclusion for encrypted store (AC-02)" "paper_moon_secure.xml" "$MAIN/res/xml"
pos "requestPurchase bridge method (AC-04)" "fun requestPurchase" "$MAIN/java/com/hindsight/app/wallet"
pos "bundle live price fed by bridge getter (AC-09 re-pinned)" "usdAnchor" "$MAIN/assets/game"
pos "requestSignIn bridge method (AC-01)" "fun requestSignIn" "$MAIN/java/com/hindsight/app/wallet"

# ---------------------------------------------------------------
# v0.2.10 Seeker Connect censuses (Gate 0). Source-level halves;
# the artifact-level halves (dex/AAR) live in v029_gate.sh and
# the seal record.
# ---------------------------------------------------------------
WALLET_SRC="$MAIN/java/com/hindsight/app/wallet"
# Comment-stripped code census (v025's discipline: doc comments
# legitimately NAME the barred things; only code counts).
count_code() { # name, regex, path...
    name="$1"; pattern="$2"; shift 2
    n=$(python3 - "$pattern" "$@" <<'PYEOF'
import glob, os, re, sys
pattern = sys.argv[1]
total = 0
for path in sys.argv[2:]:
    files = glob.glob(path + "/**/*.kt", recursive=True) if os.path.isdir(path) else [path]
    for f in files:
        src = open(f, encoding="utf-8", errors="replace").read()
        src = re.sub(r"/\*.*?\*/", "", src, flags=re.S)
        src = re.sub(r"//[^\n]*", "", src)
        total += len(re.findall(pattern, src))
print(total)
PYEOF
)
    echo "census $name = $n"
    if [ "$n" -ne 0 ]; then fail=1; fi
}
count_code "stock SharedPreferencesAuthorizationCache in code (C-06 barred: must be 0)" "SharedPreferencesAuthorizationCache" "$MAIN/java"
count_code "in-memory authorization cache in code (C-06 barred: must be 0)" "MemoryAuthorizationCache" "$MAIN/java"
count_code "app-owned ActivityResultSender construction (C-19/A1: must be 0)" "ActivityResultSender\(" "$MAIN/java"
count_code "app-owned registerForActivityResult (C-19/A2: must be 0)" "registerForActivityResult" "$MAIN/java"
count_code "clientlib imports in the migrated files (C-04 ii: must be 0)" "com\.solana\.mobilewalletadapter" \
  "$MAIN/java/com/hindsight/app/MainActivity.kt" \
  "$MAIN/java/com/hindsight/app/ui/WalletScreen.kt" \
  "$MAIN/java/com/hindsight/app/ui/GameScreen.kt" \
  "$WALLET_SRC/PaperMoonWalletBridge.kt" \
  "$WALLET_SRC/ConnectWalletGateway.kt"
count_code "legacy WalletManager / WalletTokenStore in code (C-04: must be 0)" "WalletManager|WalletTokenStore" "$MAIN/java"
count_code "throwable logging in the wallet seam (C-14: must be 0)" "printStackTrace" "$WALLET_SRC"
count "availability probing outside the wallet surface (C-12: WalletScreen only)" "walletAvailability()" \
  "$MAIN/java/com/hindsight/app/MainActivity.kt" "$MAIN/java/com/hindsight/app/PaperMoonApp.kt" \
  "$MAIN/java/com/hindsight/app/ui/GameScreen.kt" "$MAIN/java/com/hindsight/app/ui/HomeScreen.kt" \
  "$WALLET_SRC/PaperMoonWalletBridge.kt"
# C-13/S-8: authorization-state reads at the claim/consume entry
# points are barred — those paths consult the shell's bound
# identity and the ledgers only. (The bridge's connect/sign-in/
# purchase reads of the gateway are legitimate and enumerated in
# the build record; this check is scoped to the two functions.)
n=$(python3 - "$WALLET_SRC/PaperMoonWalletBridge.kt" <<'PYEOF'
import re, sys
src = open(sys.argv[1], encoding="utf-8").read()
total = 0
for fname in ("claimRookie", "consumeRookieRide"):
    m = re.search(r"fun " + fname + r"\b.*?(?=\n    @JavascriptInterface|\n    /\*\*|\Z)", src, flags=re.S)
    body = m.group(0) if m else ""
    total += len(re.findall(r"gateway\.|authorization", body))
print(total)
PYEOF
)
echo "census authorization reads inside claim/consume (C-13/S-8: must be 0) = $n"
if [ "$n" -ne 0 ]; then fail=1; fi
pos "Connect cache key present in the adapter (C-06)" "seeker_connect_auth_v1" "$WALLET_SRC"
pos "gateway restores at init (C-13)" "client.restore()" "$WALLET_SRC"
pos "disconnect unbinds the shell verifier (C-09)" "shellVerifier.unbind()" "$WALLET_SRC"
pos "dialogs mounted in MainActivity composition (C-18)" "SeekerConnectDialogs" "$MAIN/java/com/hindsight/app/MainActivity.kt"
if [ "$fail" -ne 0 ]; then echo "CENSUS FAILURES PRESENT"; exit 1; fi
echo "all censuses clean"
