#!/bin/sh
# v0.2.5 gate checks (button-crash fix + O-1 leverage removal).
#
# (a) ActivityResultSender construction audit:
#     - source: constructor-call sites in app Kotlin code (comment
#       lines stripped — KDoc/block/line comments document the old
#       anti-pattern and are not code) must be exactly 1, located in
#       MainActivity.kt; direct registerForActivityResult /
#       rememberLauncherForActivityResult calls must be 0.
#     - APK dex: ActivityResultSender.<init> invoke sites in the
#       app's own classes must be exactly 1 (MainActivity.onCreate).
# (b) Leverage scans (source bundle + the bundle packaged in the
#     APK): every pattern must be 0, and the packaged bundle must be
#     byte-identical to the source bundle.
#
# Usage: v025_gate.sh [path-to-release.apk]
# Exit 1 on any violation. The known pre-existing census failure
# (MoneyConfig.kt treasury line) is NOT part of this gate.
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MAIN="$ROOT/app/src/main"
BUNDLE="$MAIN/assets/game/index.html"
APK="${1:-}"
# Absolutize the APK path — the script cds into a temp dir later.
case "$APK" in
  /*) : ;;
  ?*) APK="$(pwd)/$APK" ;;
esac
fail=0
say() { echo "v025 $1"; }
bad() { echo "v025 FAIL: $1"; fail=1; }

# ---- (a) source construction audit ----
# Strip comment lines (// line comments and * KDoc/block lines),
# then find constructor-call syntax.
hits=$(grep -rn "ActivityResultSender(" "$MAIN/java" \
  | grep -vE ":[0-9]+:[[:space:]]*(\*|//)" || true)
n=$(printf '%s' "$hits" | grep -c . || true)
say "source ActivityResultSender( code sites = $n (must be 1, in MainActivity.kt)"
printf '%s\n' "$hits"
if [ "$n" -ne 1 ] || ! printf '%s' "$hits" | grep -q "MainActivity.kt"; then
  bad "construction-site audit failed"
fi
for pat in "registerForActivityResult" "rememberLauncherForActivityResult"; do
  n=$(grep -rn "$pat" "$MAIN/java" | grep -vE ":[0-9]+:[[:space:]]*(\*|//)" | wc -l)
  say "source $pat code sites = $n (must be 0)"
  [ "$n" -ne 0 ] && bad "$pat present in code"
done

# ---- (b) bundle leverage scans (source) ----
scan_bundle() { # file, label
  f="$1"; label="$2"
  for pat in "Leverage · liquidation" "data-cfg=\"lev\"" "SPICY" \
             "c.lev" "l.lev" "slDead" "liquidateIfNeeded" \
             "<span>LEVERAGE</span>" "startRideLoop(deadSl"; do
    n=$(grep -c -F -- "$pat" "$f" || true)
    say "$label pattern [$pat] = $n (must be 0)"
    [ "$n" -ne 0 ] && bad "$label still contains [$pat]"
  done
  n=$(grep -ci "leverag" "$f" || true)
  say "$label pattern /leverag/i = $n (must be 0)"
  [ "$n" -ne 0 ] && bad "$label still contains leverage strings"
}
scan_bundle "$BUNDLE" "source-bundle"

# ---- APK-level scans ----
if [ -n "$APK" ]; then
  TMP=$(mktemp -d /tmp/pm-v025-apk.XXXXXX)
  (cd "$TMP" && unzip -q "$APK" "classes*.dex" "assets/game/index.html")
  scan_bundle "$TMP/assets/game/index.html" "apk-bundle"
  if cmp -s "$TMP/assets/game/index.html" "$BUNDLE"; then
    say "apk bundle byte-identical to source bundle"
  else
    bad "apk bundle differs from source bundle"
  fi
  # (a2) dex construction sites: resolve the (possibly obfuscated)
  # ActivityResultSender class through the R8 mapping, then list
  # every <init> invoke site with its containing class+method.
  MAP="$ROOT/app/build/outputs/mapping/release/mapping.txt"
  DEXPY="$ROOT/verification/dex_sender_sites.py"
  if [ -f "$DEXPY" ]; then
    python3 "$DEXPY" "$TMP" "$MAP" || bad "dex construction-site audit failed"
  else
    bad "dex_sender_sites.py missing"
  fi
  rm -rf "$TMP"
else
  say "no APK given — APK-level scans skipped"
fi

if [ "$fail" -ne 0 ]; then echo "v025 GATE FAILURES PRESENT"; exit 1; fi
echo "v025 gate: all checks clean"
