#!/usr/bin/env bash
# PAPER MOON v0.2.9 — WAL-002 cure runtime cell (SOLANA RESOURCES BINDING
# 2026-10-29 §2.2 wallet-present recipe, JUDGE Gate 0 C-32).
#
# One cell, two legs, selected by env TARGET:
#   candidate — the CI-built v0.2.9 APK (hash-gated in the workflow):
#               connect must reach the Mock MWA Wallet and COMPLETE
#               (wallet screen shows CONNECTED + the mock's imported-key
#               address). PASS = the cure works with a wallet present.
#   control   — the sealed v0.2.8 APK (hash-gated in the workflow):
#               the same cell must FAIL — connect can never complete
#               (KFC-005: clientlib 2.0.8 throws on the absolute
#               iconUri before any wallet interaction). The expected
#               control outcome is a clean error state (TRY AGAIN +
#               plain failure copy), process alive, no crash, no sheet
#               ever completing. CONFIRMED-EXPECTED-FAILURE = PASS for
#               this leg; a completed connect breaks the diagnosis
#               composition and FAILs the leg loudly.
#
# Environment: PIN-locked AVD (binding §2.2 step 2), Mock MWA Wallet
# built from source at pinned commit d444aff0 with a throwaway key
# generated in-job (never an estate key), API 36.
#
# Driver mechanics adapted from the HAUNT lane's verify-v0113-wallet.sh
# (driver r8 lineage: substantive-dump guard, structural keyguard
# detection, sheet discipline, logcat windowing).
set -u

PKG="com.hindsight.app"
ACT="com.hindsight.app/.MainActivity"
MOCK="com.solana.mwallet"
MOCK_ACT="com.solana.mwallet/.MainActivity"
OUT="${PM_OUT:-pm-v029-runtime-evidence}"
TARGET="${TARGET:-candidate}"
PM_APK="${PM_APK:-}"
MOCK_APK="${MOCK_APK:-}"
EXPECTED_PUBKEY="${EXPECTED_PUBKEY:-}"
DEVICE_PIN="${DEVICE_PIN:-1234}"
TMPD="$(mktemp -d)"
LANE_LOG="$OUT/lane.log"

mkdir -p "$OUT"
: > "$LANE_LOG"
log() { echo "$*" | tee -a "$LANE_LOG"; }

fatal_pm_total="0"; fatal_mock_total="0"; icon_hits_total="0"
lock_pin="unknown"; lock_fp="unknown"; auth_note="not attempted"
cell_result="NOT-RUN"; cell_detail=""

# --- helpers (HAUNT driver lineage) --------------------------------------------
dump_is_substantive() {
  python3 - "$1" <<'PY'
import re, sys
data = open(sys.argv[1], encoding="utf-8", errors="ignore").read()
nodes = re.findall(r"<node[^>]*>", data)
if len(nodes) >= 3:
    sys.exit(0)
for n in nodes:
    if re.search(r'text="[^"]+"', n) or re.search(r'content-desc="[^"]+"', n) or "WebView" in n:
        sys.exit(0)
sys.exit(1)
PY
}

dump_ui() { # $1 = destination file
  local dst="$1" attempt
  for attempt in 1 2 3 4 5 6 7 8; do
    adb shell rm -f /sdcard/window_dump.xml >/dev/null 2>&1 || true
    timeout 40 adb shell uiautomator dump /sdcard/window_dump.xml >/dev/null 2>&1 || true
    adb exec-out cat /sdcard/window_dump.xml > "$dst.tmp" 2>/dev/null || true
    if grep -q "</hierarchy>" "$dst.tmp" 2>/dev/null && dump_is_substantive "$dst.tmp"; then
      mv "$dst.tmp" "$dst"
      return 0
    fi
    sleep 3
  done
  log "dump_ui: FAILED after 8 attempts -> $dst"
  return 1
}

tap_text() { # $1 = hierarchy file, $2 = substring
  local coords
  coords="$(python3 - "$1" "$2" <<'PY'
import re, sys
data = open(sys.argv[1], encoding="utf-8", errors="ignore").read()
for m in re.finditer(r'text="([^"]*' + re.escape(sys.argv[2]) + r'[^"]*)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', data):
    x = (int(m.group(2)) + int(m.group(4))) // 2
    y = (int(m.group(3)) + int(m.group(5))) // 2
    print(f"{x} {y}")
    break
PY
)"
  if [ -n "$coords" ]; then adb shell input tap $coords; return 0; fi
  return 1
}

tap_exact() { # $1 = hierarchy file, $2 = exact text
  local coords
  coords="$(python3 - "$1" "$2" <<'PY'
import re, sys
data = open(sys.argv[1], encoding="utf-8", errors="ignore").read()
for m in re.finditer(r'<node[^>]*>', data):
    tag = m.group(0)
    t = re.search(r'text="([^"]*)"', tag)
    b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', tag)
    if t and b and t.group(1) == sys.argv[2]:
        x = (int(b.group(1)) + int(b.group(3))) // 2
        y = (int(b.group(2)) + int(b.group(4))) // 2
        print(f"{x} {y}")
        break
PY
)"
  if [ -n "$coords" ]; then adb shell input tap $coords; return 0; fi
  return 1
}

tap_frac() {
  local wh w h x y
  wh="$(adb shell wm size 2>/dev/null | sed -n 's/.*: \([0-9]*\)x\([0-9]*\).*/\1 \2/p' | head -n 1)"
  w="$(printf '%s' "$wh" | awk '{print $1}')"; h="$(printf '%s' "$wh" | awk '{print $2}')"
  [ -n "$w" ] && [ -n "$h" ] || { w=1080; h=2400; }
  x="$(awk -v w="$w" -v f="$1" 'BEGIN{printf "%d", w*f}')"
  y="$(awk -v h="$h" -v f="$2" 'BEGIN{printf "%d", h*f}')"
  adb shell input tap "$x" "$y"
}

pid_alive() { [ -n "$(adb shell pidof "$1" 2>/dev/null | tr -d '\r')" ]; }

fg_pkg() {
  local f
  f="$(adb shell dumpsys window windows 2>/dev/null | sed -n 's/.*mCurrentFocus=Window{[^ ]* [^ ]* \([^/]*\)\/.*/\1/p' | head -n 1 | tr -d '\r')"
  if [ -z "$f" ]; then
    f="$(adb shell dumpsys activity activities 2>/dev/null | sed -n 's/.*topResumedActivity=ActivityRecord{[^ ]* [^ ]* \([^/]*\)\/.*/\1/p' | head -n 1 | tr -d '\r')"
  fi
  printf '%s' "$f"
}

snap() { adb exec-out screencap -p > "$OUT/$1" 2>/dev/null || true; }

dismiss_host_anr() {
  local f="$TMPD/anr-check.xml"
  if dump_ui "$f" && grep -q "isn.t responding" "$f"; then
    if grep -q "Pixel Launcher isn.t responding\|System UI isn.t responding" "$f"; then
      log "anr: host dialog present; tapping Wait"
      tap_text "$f" "Wait" || tap_frac 0.66 0.54
      sleep 2
    fi
  fi
}

wait_for() { # $1 = grep pattern, $2 = timeout secs, $3 = dump file
  local pat="$1" timeout_s="$2" dst="$3" elapsed=0
  while [ "$elapsed" -lt "$timeout_s" ]; do
    if dump_ui "$dst" && grep -qE "$pat" "$dst"; then return 0; fi
    sleep 3; elapsed=$((elapsed + 3))
  done
  dump_ui "$dst" || true
  grep -qE "$pat" "$dst" 2>/dev/null
}

scan_logcat() { # $1 = file
  local f="$1" a b c
  a="$(grep -A3 "FATAL EXCEPTION" "$f" 2>/dev/null | grep -c "Process: $PKG" || true)"
  b="$(grep -A3 "FATAL EXCEPTION" "$f" 2>/dev/null | grep -c "Process: $MOCK" || true)"
  c="$(grep -c "iconRelativeUri" "$f" 2>/dev/null || true)"
  fatal_pm_total=$((fatal_pm_total + ${a:-0}))
  fatal_mock_total=$((fatal_mock_total + ${b:-0}))
  icon_hits_total=$((icon_hits_total + ${c:-0}))
  log "scan: $f pm_fatal=${a:-0} mock_fatal=${b:-0} iconRelativeUri_hits=${c:-0}"
}

capture_window() { # $1 = tag
  adb logcat -d > "$OUT/logcat-$1.txt" 2>&1 || true
  scan_logcat "$OUT/logcat-$1.txt"
  adb logcat -c >/dev/null 2>&1 || true
}

# --- device record ---------------------------------------------------------------
adb wait-for-device
for _ in $(seq 1 60); do
  [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] && break
  sleep 5
done
{
  echo "sdk=$(adb shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r')"
  echo "release=$(adb shell getprop ro.build.version.release 2>/dev/null | tr -d '\r')"
  echo "fingerprint=$(adb shell getprop ro.build.fingerprint 2>/dev/null | tr -d '\r')"
  echo "model=$(adb shell getprop ro.product.model 2>/dev/null | tr -d '\r')"
  echo "target=$TARGET"
  echo "expected_pubkey=$EXPECTED_PUBKEY"
} > "$OUT/device-info.txt"
cat "$OUT/device-info.txt" | tee -a "$LANE_LOG"

# --- PIN lock + fingerprint enrollment (binding §2.2 step 2, mandatory) ---------
log "security: setting device PIN via locksettings"
adb shell locksettings set-pin "$DEVICE_PIN" >> "$LANE_LOG" 2>&1 || true
if adb shell locksettings verify --old "$DEVICE_PIN" >/dev/null 2>&1; then
  lock_pin="set+verified (PIN $DEVICE_PIN)"
else
  ls_state="$(adb shell dumpsys lock_settings 2>/dev/null | grep -i "credential" | head -n 2 | tr -d '\r')"
  log "security: locksettings verify unsupported; dumpsys lock_settings: $ls_state"
  lock_pin="set (locksettings set-pin issued, PIN $DEVICE_PIN)"
fi
log "security: lock_pin=$lock_pin"
adb shell settings put system screen_off_timeout 2147483647 >/dev/null 2>&1 || true
adb shell settings put secure lock_screen_lock_after_timeout 2147483647 >/dev/null 2>&1 || true
adb shell svc power stayon usb >/dev/null 2>&1 || true
log "security: lock mitigation applied (screen_off_timeout=max, lock_after_timeout=max, stayon usb)"

looks_locked() { # $1 = dump file
  python3 - "$1" <<'PY'
import re, sys
try:
    data = open(sys.argv[1], encoding="utf-8", errors="ignore").read()
except OSError:
    sys.exit(1)
pkgs = set(re.findall(r'package="([^"]+)"', data))
texts = " ".join(re.findall(r'text="([^"]*)"', data))
if "Unlock for all features and data" in texts: sys.exit(0)
if "Swipe up to open" in texts or "Swipe up to unlock" in texts: sys.exit(0)
if pkgs and pkgs <= {"com.android.systemui"}: sys.exit(0)
sys.exit(1)
PY
}

ensure_unlocked() {
  local f="$TMPD/lock.xml" try
  dump_ui "$f" || return 0
  if ! looks_locked "$f" && [ "$(fg_pkg)" != "com.android.systemui" ]; then return 0; fi
  for try in 1 2 3; do
    log "lock: keyguard appears engaged (try $try; fg=$(fg_pkg)); wake + swipe + PIN unlock"
    adb shell input keyevent 224 >/dev/null 2>&1 || true
    sleep 1
    adb shell input keyevent 82 >/dev/null 2>&1 || true
    sleep 1
    adb shell input swipe 540 2200 540 700 300 >/dev/null 2>&1 || true
    sleep 1
    if dump_ui "$f" && looks_locked "$f"; then
      adb shell input text "$DEVICE_PIN" >/dev/null 2>&1 || true
      adb shell input keyevent 66 >/dev/null 2>&1 || true
      sleep 2
    fi
    dump_ui "$f" || return 0
    if ! looks_locked "$f"; then log "lock: unlocked on try $try"; return 0; fi
  done
  log "lock: STILL LOCKED after 3 unlock attempts"
}

enroll_fingerprint() {
  log "security: attempting fingerprint enrollment via Settings"
  adb shell am start -a android.settings.SECURITY_SETTINGS >/dev/null 2>&1 || true
  sleep 3
  local f="$TMPD/fp.xml" i
  for i in 1 2 3 4 5 6; do
    dump_ui "$f" || return 1
    if grep -q "Fingerprint" "$f"; then
      tap_text "$f" "Fingerprint" || return 1
      sleep 2
      break
    fi
    adb shell input swipe 540 1800 540 900 300 >/dev/null 2>&1 || true
    sleep 1
  done
  dump_ui "$f" || return 1
  if grep -q "PIN\|password" "$f" && ! grep -q "Fingerprint" "$f"; then
    adb shell input text "$DEVICE_PIN" >/dev/null 2>&1 || true
    adb shell input keyevent 66 >/dev/null 2>&1 || true
    sleep 2
    dump_ui "$f" || return 1
  fi
  if grep -q "Add a fingerprint\|Fingerprint Unlock\|fingerprint" "$f"; then
    tap_text "$f" "Add a fingerprint" || tap_text "$f" "Fingerprint" || true
    sleep 2
  fi
  local t
  for t in 1 2 3 4 5 6 7 8 9 10 11 12; do
    adb emu finger touch 1 >/dev/null 2>&1 || true
    sleep 1
    if dump_ui "$f" && grep -q "Fingerprint added\|Done\|fingerprint.*added" "$f"; then
      tap_text "$f" "Done" || true
      return 0
    fi
  done
  dump_ui "$f" || true
  grep -q "Fingerprint added" "$f" 2>/dev/null
}
if enroll_fingerprint; then
  lock_fp="enrolled (emulator virtual sensor, id 1)"
else
  lock_fp="ENROLLMENT FAILED (see lane log; PIN-secured path still exercised)"
fi
log "security: lock_fp=$lock_fp"
adb shell input keyevent 3 >/dev/null 2>&1 || true
sleep 1

# --- installs + Authenticate (binding §2.2 step 5 order) -------------------------
log "install: mock wallet ($MOCK_APK)"
if ! adb install "$MOCK_APK" > "$TMPD/install-mock.log" 2>&1; then
  cat "$TMPD/install-mock.log" | tee -a "$LANE_LOG"
  log "install: MOCK INSTALL FAILED — no wallet legs can run"
  echo "verdict=FAIL(mock install)" > "$OUT/verdict.txt"
  exit 1
fi

satisfy_auth_prompt() {
  local f="$TMPD/auth.xml" i
  for i in 1 2 3 4 5 6; do
    sleep 2
    dump_ui "$f" || return 1
    if grep -q "Log in to" "$f"; then
      adb emu finger touch 1 >/dev/null 2>&1 || true
      sleep 2
      dump_ui "$f" || return 1
      if grep -q "Log in to" "$f"; then
        tap_text "$f" "Use PIN" || tap_text "$f" "PIN" || true
        sleep 1
        adb shell input text "$DEVICE_PIN" >/dev/null 2>&1 || true
        adb shell input keyevent 66 >/dev/null 2>&1 || true
        sleep 2
      fi
    else
      return 0
    fi
  done
  dump_ui "$f" || true
  ! grep -q "Log in to" "$f"
}

authenticate_mock() {
  log "auth: opening mock wallet to Authenticate (15-min window)"
  ensure_unlocked
  dismiss_host_anr
  adb shell am start -W -n "$MOCK_ACT" >/dev/null 2>&1 || true
  sleep 3
  local f="$TMPD/mock-main.xml"
  dump_ui "$f" || return 1
  if grep -q "isn.t responding" "$f"; then
    log "auth: host ANR over the mock main screen — dismissing and re-dumping"
    dismiss_host_anr
    sleep 2
    dump_ui "$f" || return 1
  fi
  if ! grep -q "AUTHENTICATE\|Authenticate" "$f" && looks_locked "$f"; then
    log "auth: mock launch landed behind the keyguard — unlocking + relaunching"
    ensure_unlocked
    adb shell am start -W -n "$MOCK_ACT" >/dev/null 2>&1 || true
    sleep 3
    dump_ui "$f" || return 1
  fi
  grep -oE 'text="[^"]+"' "$f" | sort -u | head -12 | tee -a "$LANE_LOG" || true
  if grep -q "AUTHENTICATE\|Authenticate" "$f"; then
    tap_exact "$f" "AUTHENTICATE" || tap_exact "$f" "Authenticate" || tap_text "$f" "UTHENTICATE" || return 1
    if satisfy_auth_prompt; then
      auth_note="authenticated (window opened $(date -u +%H:%M:%S)Z)"
      log "auth: $auth_note"
      sleep 1
      dump_ui "$OUT/mock-after-auth.xml" || true
      snap "mock-after-auth.png"
      return 0
    fi
    auth_note="AUTHENTICATE PROMPT NOT SATISFIED"
    log "auth: $auth_note"
    return 1
  fi
  auth_note="Authenticate button not found on mock main screen"
  log "auth: $auth_note"
  dump_ui "$OUT/mock-main-fail.xml" || true
  snap "mock-main-fail.png"
  return 1
}

authenticate_mock || true
log "install: Paper Moon APK under test ($PM_APK)"
adb uninstall "$PKG" >/dev/null 2>&1 || true
if ! adb install "$PM_APK" > "$TMPD/install-pm.log" 2>&1; then
  cat "$TMPD/install-pm.log" | tee -a "$LANE_LOG"
  log "install: PM INSTALL FAILED"
  echo "verdict=FAIL(pm install)" > "$OUT/verdict.txt"
  exit 1
fi
adb logcat -c >/dev/null 2>&1 || true

# --- PM navigation -----------------------------------------------------------------
goto_home() {
  local f="$TMPD/home.xml" i
  ensure_unlocked
  adb shell am start -W -n "$ACT" >/dev/null 2>&1 || true
  for i in 1 2 3 4 5 6 7 8; do
    sleep 2
    if dump_ui "$f" && grep -q "PLAY" "$f" && grep -q "WALLET" "$f" && grep -q "SETTINGS" "$f"; then
      return 0
    fi
    dismiss_host_anr
    ensure_unlocked
    if [ "$i" = "4" ]; then
      log "goto_home: relaunching the app mid-loop"
      adb shell am start -W -n "$ACT" >/dev/null 2>&1 || true
    fi
    adb shell input keyevent 4 >/dev/null 2>&1 || true
  done
  dump_ui "$OUT/hier-home-fail.xml" || true
  dump_ui "$f" || true
  grep -q "PLAY" "$f" 2>/dev/null && grep -q "WALLET" "$f" 2>/dev/null
}

goto_wallet() {
  goto_home || return 1
  local f="$TMPD/wallet-nav.xml"
  dump_ui "$f" || return 1
  tap_text "$f" "WALLET" || tap_frac 0.5 0.55
  wait_for "CONNECT WALLET|CONNECTED|Error" 25 "$OUT/hier-wallet-entry.xml"
}

pm_state() { # $1 = dump file (fresh dump taken inside)
  if dump_ui "$1"; then
    if grep -q "CONNECTED" "$1" && grep -q "$EXPECTED_PUBKEY" "$1"; then echo "connected"
    elif grep -q "CONNECT WALLET" "$1"; then echo "disconnected"
    elif grep -q "TRY AGAIN" "$1"; then echo "error"
    elif grep -q "Waiting for wallet" "$1"; then echo "working"
    else echo "unknown"; fi
  else echo "nodump"; fi
}

sheet_present() { # $1 = dump file
  local fg
  fg="$(fg_pkg)"
  [ "$fg" = "$MOCK" ] && grep -qE 'text="(Connect|Approve|Cancel)"' "$1" 2>/dev/null
}

wait_sheet() { # $1 = dump file, $2 = timeout
  local dst="$1" timeout_s="$2" elapsed=0
  while [ "$elapsed" -lt "$timeout_s" ]; do
    if dump_ui "$dst" && sheet_present "$dst"; then return 0; fi
    dismiss_host_anr
    sleep 3; elapsed=$((elapsed + 3))
  done
  dump_ui "$dst" || true
  sheet_present "$dst"
}

# --- THE CELL: wallet-present connect ----------------------------------------------
log "=== connect cell (TARGET=$TARGET) ==="
sheet_seen="no"; connected="no"; final_state="not-run"; err_msg=""

if ! goto_wallet; then
  cell_result="FAIL(could not reach wallet screen)"
else
  pre="$(pm_state "$TMPD/pre.xml")"
  log "cell: pre-state=$pre"
  if [ "$pre" != "disconnected" ]; then
    cell_result="FAIL(pre-state=$pre, expected disconnected on fresh install)"
  else
    adb logcat -c >/dev/null 2>&1 || true
    dump_ui "$TMPD/dis.xml" || true
    if ! tap_text "$TMPD/dis.xml" "CONNECT WALLET"; then
      cell_result="FAIL(no connect control)"
    else
      log "cell: tapped CONNECT WALLET; waiting for the mock sheet (45s)"
      if wait_sheet "$OUT/hier-sheet.xml" 45; then
        sheet_seen="yes"
        snap "shot-sheet.png"
        log "cell: mock sheet present (fg=$(fg_pkg)); identity texts:"
        grep -oE 'text="[^"]+"' "$OUT/hier-sheet.xml" | sort -u | head -20 | tee -a "$LANE_LOG" || true
        if tap_exact "$OUT/hier-sheet.xml" "Connect" || tap_text "$OUT/hier-sheet.xml" "Connect"; then
          sleep 4
          satisfy_auth_prompt || true
        else
          log "cell: no Connect control on the sheet"
        fi
      else
        log "cell: NO mock sheet appeared within 45s (fg=$(fg_pkg))"
        snap "shot-nosheet.png"
      fi
      # settle window: poll state for up to 30s
      elapsed=0
      while [ "$elapsed" -lt 30 ]; do
        final_state="$(pm_state "$TMPD/poll.xml")"
        [ "$final_state" = "connected" ] && break
        [ "$final_state" = "error" ] && break
        sleep 3; elapsed=$((elapsed + 3))
      done
      log "cell: final_state=$final_state sheet_seen=$sheet_seen"
      dump_ui "$OUT/hier-final.xml" || true
      snap "shot-final.png"
      if [ "$final_state" = "connected" ]; then connected="yes"; fi
      if [ "$final_state" = "error" ]; then
        err_msg="$(python3 - "$OUT/hier-final.xml" <<'PY' || true
import re, sys
texts = re.findall(r'text="([^"]+)"', open(sys.argv[1], encoding="utf-8", errors="ignore").read())
skip = {"Error", "TRY AGAIN", "WALLET", "Not connected"}
cands = [t for t in texts if t not in skip]
print(cands[0] if cands else "")
PY
)"
        log "cell: error-state message verbatim: '$err_msg'"
      fi
      capture_window "cell"
      # iconRelativeUri anywhere in the final hierarchy?
      if grep -q "iconRelativeUri" "$OUT/hier-final.xml" 2>/dev/null; then
        icon_hits_total=$((icon_hits_total + 1))
        log "cell: iconRelativeUri present in final hierarchy dump"
      fi
    fi
  fi
fi

alive="no"; pid_alive "$PKG" && alive="yes"
log "cell: pm process alive=$alive fatal_pm=$fatal_pm_total fatal_mock=$fatal_mock_total icon_hits=$icon_hits_total"

if [ "$cell_result" = "NOT-RUN" ]; then
  if [ "$TARGET" = "candidate" ]; then
    if [ "$connected" = "yes" ] && [ "$fatal_pm_total" = "0" ] && [ "$fatal_mock_total" = "0" ]; then
      cell_result="PASS"
      cell_detail="connect approved in mock sheet; wallet screen CONNECTED shows imported-key address $EXPECTED_PUBKEY; sheet_seen=$sheet_seen; iconRelativeUri hits=$icon_hits_total; 0 FATALs"
    else
      cell_result="FAIL"
      cell_detail="connected=$connected final_state=$final_state sheet_seen=$sheet_seen err='$err_msg' fatal_pm=$fatal_pm_total fatal_mock=$fatal_mock_total icon_hits=$icon_hits_total"
    fi
  else
    if [ "$connected" = "no" ] && [ "$final_state" = "error" ] && [ "$alive" = "yes" ] && [ "$fatal_pm_total" = "0" ]; then
      cell_result="PASS(control confirmed the fault)"
      cell_detail="sealed v0.2.8 could not complete connect: no completed connection, clean Error state ('$err_msg'), process alive, 0 FATALs; sheet_seen=$sheet_seen; iconRelativeUri hits=$icon_hits_total (logcat+hierarchy)"
    elif [ "$connected" = "yes" ]; then
      cell_result="FAIL(control CONNECTED — diagnosis composition broken)"
      cell_detail="the sealed v0.2.8 binary completed a wallet connection; WAL-002 composition must be re-examined"
    else
      cell_result="FAIL(control outcome indeterminate)"
      cell_detail="connected=$connected final_state=$final_state alive=$alive sheet_seen=$sheet_seen err='$err_msg' fatal_pm=$fatal_pm_total icon_hits=$icon_hits_total"
    fi
  fi
fi

{
  echo "target=$TARGET"
  echo "verdict=$cell_result"
  echo "detail=$cell_detail"
  echo "connected=$connected final_state=$final_state sheet_seen=$sheet_seen"
  echo "expected_pubkey=$EXPECTED_PUBKEY"
  echo "fatal_pm=$fatal_pm_total fatal_mock=$fatal_mock_total iconRelativeUri_hits=$icon_hits_total"
  echo "lock_pin=$lock_pin"
  echo "lock_fingerprint=$lock_fp"
  echo "auth=$auth_note"
} > "$OUT/verdict.txt"
cat "$OUT/verdict.txt" | tee -a "$LANE_LOG"

case "$cell_result" in
  PASS*) exit 0 ;;
  *) exit 1 ;;
esac
