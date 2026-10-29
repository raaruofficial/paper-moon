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
if [ "$fail" -ne 0 ]; then echo "CENSUS FAILURES PRESENT"; exit 1; fi
echo "all censuses clean"
