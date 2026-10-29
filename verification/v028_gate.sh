#!/bin/sh
# v0.2.8 gate: UI overhaul (OVERHAUL_SPEC v1.1 §F step 12) + self-contained
# (owner directive 2026-10-27: nothing hosted).
#
# Fails the build if, in the source bundle — and, when an APK is given, in
# the bundle packaged inside the APK (byte-identical to source):
#   - any v0.2.6 production-memo pattern is present (delegated to
#     v026_gate.sh, run separately; the patterns are repeated here so this
#     gate stands alone);
#   - any DELETED player string survives (out-of-rides toast, shell
#     framing, jargon hero, RUN IT BACK, passport/stamp system vocabulary
#     in chrome copy);
#   - any RESTORED-layer string is missing (opening, onboarding,
#     briefing labels, lesson strings, pay panel, how-to sheet, wallet);
#   - any network surface exists (http(s) strings, fetch/XHR/WebSocket/
#     EventSource) — the self-contained gate;
#   - scenario/story/catalyst data differs from the freeze snapshot
#     (checked by the companion python in the build record; the data
#     lines are compared verbatim there).
#
# Usage: v028_gate.sh [path-to-release.apk]
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
say() { echo "v028 $1"; }
bad() { echo "v028 FAIL: $1"; fail=1; }

scan_absent() { # file, label, pattern
  n=$(grep -o -F -- "$3" "$1" | wc -l)
  say "$2 absent [$3] = $n (must be 0)"
  [ "$n" -ne 0 ] && bad "$2 contains forbidden [$3]"
}
scan_present() { # file, label, pattern
  n=$(grep -o -F -- "$3" "$1" | wc -l)
  say "$2 present [$3] = $n (must be >= 1)"
  [ "$n" -eq 0 ] && bad "$2 missing required [$3]"
}

scan_bundle() { # file, label
  f="$1"; label="$2"
  # deleted / forbidden player strings
  for pat in "No rides left. Claim rookie rides or buy a day pass." \
             "Rides are granted by the shell, never by this page" \
             "THE CHART ALREADY HAPPENED" "One chance to call the top" \
             "RUN IT BACK" "coverage_note" "Coverage:" \
             "Math.round(ms%3600000/60000)" "<h2>Get more rides</h2>"; do
    scan_absent "$f" "$label" "$pat"
  done
  # self-contained: no network surface in the bundle
  for pat in "http://" "https://" "fetch(" "XMLHttpRequest" "WebSocket" "EventSource" "supabase" "firebase"; do
    scan_absent "$f" "$label" "$pat"
  done
  # restored layer presence
  for pat in "WORDS OF WISDOM" "rat poison squared" "zillions" \
             "You were there." "You watched it happen." "You almost bought." \
             "TAP TO ENTER" "SOUND TURNS ON AS YOU ENTER" \
             "Ride the charts of history." "Your first 3 rides are free." \
             "START WITH A FREE RIDE" "PAPER MOON ARCHIVE" "ON THE CHART" \
             "The moon you missed" "Pick it. Ride it. Call the top." \
             "WHERE WERE YOU?" "THE CAST" "WHAT TO EXPECT" \
             "You took the exit. The market gets the last word." \
             "It went to the floor." "did not return the favor" \
             "Holding could not save this one." "Patience kept you aboard." \
             "best trade was the one you didn" "not selling too early" \
             "RIDE AGAIN" "Out of rides." "RIDE AGAIN NOW" "All sales are final." \
             "Buy before the run-up." "GOT IT" "GET MORE RIDES" "No pass active." \
             "Ride the chart. Sell anytime" "entry market caps are approximate" \
             "assets/moon-fold-logo.webp" "assets/paper-moon-wordmark.png" \
             "assets/buffett-pixel.jpg" "assets/mert-pixel.svg" \
             "var NARRATIVES=" "var RIDE_EXPECTATIONS=" "#resultsOverlay{z-index:80}"; do
    scan_present "$f" "$label" "$pat"
  done
}

scan_bundle "$BUNDLE" "source-bundle"

if [ -n "$APK" ]; then
  TMP=$(mktemp -d /tmp/pm-v028-apk.XXXXXX)
  (cd "$TMP" && unzip -q "$APK" "assets/game/index.html" "assets/game/assets/*")
  scan_bundle "$TMP/assets/game/index.html" "apk-bundle"
  if cmp -s "$TMP/assets/game/index.html" "$BUNDLE"; then
    say "apk bundle byte-identical to source bundle"
  else
    bad "apk bundle differs from source bundle"
  fi
  for a in moon-fold-logo.webp paper-moon-wordmark.png buffett-pixel.jpg mert-pixel.svg; do
    if cmp -s "$TMP/assets/game/assets/$a" "$ROOT/app/src/main/assets/game/assets/$a"; then
      say "apk asset $a byte-identical"
    else
      bad "apk asset $a missing or differs"
    fi
  done
  rm -rf "$TMP"
else
  say "no APK given — APK-level scans skipped"
fi

if [ "$fail" -ne 0 ]; then echo "v028 GATE FAILURES PRESENT"; exit 1; fi
echo "v028 gate: all checks clean"
