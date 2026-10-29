#!/bin/sh
# v0.2.6 gate: NO PRODUCTION TEXT in the shipped game bundle.
#
# The 2026-10-27 scrub removed the data-production memo class from the
# bundle (owner found a "Coverage:" pipeline memo rendered as player
# text on device in v0.2.5). This gate fails the build if any
# production-memo pattern appears in the source bundle — and, when an
# APK is given, in the bundle packaged inside the APK (which must be
# byte-identical to the source bundle).
#
# Patterns (the memo class):
#   - the coverage_note data field itself and its "Coverage:" render
#   - pipeline vocabulary: forward-fill(ed), bucket(s), window
#     start/end, shrunk/SHRUNK, truncated to, nothing existed, keyless,
#     BAD-WICK, source endpoint, Mixed: (source-mixing memo lead)
#   - raw data-endpoint strings: api.mexc.com, data-api.binance,
#     klines?symbol
#   - the dead memo-derived fallback label "Multiple documented venues"
#   - internal ticket labels that rode in comments: RV-6c, RV5d,
#     AC-08, AC-10, MAJOR-3
#   - dev placeholders: TODO, FIXME, lorem
#
# Usage: v026_gate.sh [path-to-release.apk]
# Exit 1 on any violation.
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BUNDLE="$ROOT/app/src/main/assets/game/index.html"
APK="${1:-}"
case "$APK" in
  /*) : ;;
  ?*) APK="$(pwd)/$APK" ;;
esac
fail=0
say() { echo "v026 $1"; }
bad() { echo "v026 FAIL: $1"; fail=1; }

scan_bundle() { # file, label
  f="$1"; label="$2"
  for pat in "coverage_note" "Coverage:" "forward-fill" "forward-filled" \
             "bucket(s)" "window start" "window end" "shrunk" "SHRUNK" \
             "truncated to" "nothing existed" "keyless" "BAD-WICK" \
             "source endpoint" "Mixed:" "api.mexc.com" "data-api.binance" \
             "klines?symbol" "Multiple documented venues" \
             "RV-6c" "RV5d" "AC-08" "AC-10" "MAJOR-3" \
             "TODO" "FIXME" "lorem"; do
    n=$(grep -o -F -- "$pat" "$f" | wc -l)
    say "$label pattern [$pat] = $n (must be 0)"
    [ "$n" -ne 0 ] && bad "$label still contains [$pat]"
  done
}

scan_bundle "$BUNDLE" "source-bundle"

if [ -n "$APK" ]; then
  TMP=$(mktemp -d /tmp/pm-v026-apk.XXXXXX)
  (cd "$TMP" && unzip -q "$APK" "assets/game/index.html")
  scan_bundle "$TMP/assets/game/index.html" "apk-bundle"
  if cmp -s "$TMP/assets/game/index.html" "$BUNDLE"; then
    say "apk bundle byte-identical to source bundle"
  else
    bad "apk bundle differs from source bundle"
  fi
  rm -rf "$TMP"
else
  say "no APK given — APK-level scans skipped"
fi

if [ "$fail" -ne 0 ]; then echo "v026 GATE FAILURES PRESENT"; exit 1; fi
echo "v026 gate: all checks clean"
