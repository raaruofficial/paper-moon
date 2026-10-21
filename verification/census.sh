#!/bin/sh
# Money-build censuses (AC-10's method, run at source level here and
# re-run on the built APK's dex at seal). Every count below must be 0
# unless the line says otherwise. Exit 1 on any violation.
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
count "bundle price literal \$3.50 (AC-09: price comes from the bridge getter only)" '\$3\.50' "$MAIN/assets/game"
count "shared treasury address in money code (pre-join gate: must be 0)" "FRShnxX7vi64gtBoA6JPmH9MBuVqtheEQaxA1D17wKdP" "$MAIN/java/com/hindsight/app/money"
# Positive checks (must be >= 1)
pos() {
    name="$1"; pattern="$2"; shift 2
    n=$(grep -r -- "$pattern" "$@" 2>/dev/null | wc -l)
    echo "census $name = $n (must be >= 1)"
    if [ "$n" -lt 1 ]; then fail=1; fi
}
n=$(grep -rn -- '\$3\.50' "$MAIN" 2>/dev/null | grep -v "money/MoneyConfig.kt" | wc -l)
echo "census price literal outside the single source (AC-09: must be 0) = $n"
if [ "$n" -ne 0 ]; then fail=1; grep -rn -- '\$3\.50' "$MAIN" | grep -v "money/MoneyConfig.kt" | head -5; fi
pos "EncryptedSharedPreferences in use (AC-02)" "EncryptedSharedPreferences" "$MAIN/java"
pos "backup exclusion for encrypted store (AC-02)" "paper_moon_secure.xml" "$MAIN/res/xml"
pos "requestPurchase bridge method (AC-04)" "fun requestPurchase" "$MAIN/java/com/hindsight/app/wallet"
pos "requestSignIn bridge method (AC-01)" "fun requestSignIn" "$MAIN/java/com/hindsight/app/wallet"
if [ "$fail" -ne 0 ]; then echo "CENSUS FAILURES PRESENT"; exit 1; fi
echo "all censuses clean"
