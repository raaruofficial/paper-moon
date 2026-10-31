#!/usr/bin/env bash
# PAPER MOON v0.2.10 — Seeker Connect runtime battery (Gate 0 §7.3 +
# C-13/C-16/C-26/C-27/C-30 device cells; SOLANA RESOURCES BINDING
# 2026-10-29 §2.2 recipe: PIN-locked API 36 AVD, Mock MWA Wallet
# built from source at pinned commit d444aff0, throwaway in-job key).
#
# Cells (each logs CELL <id> <PASS|FAIL|FIXTURE-LIMITED> <detail>):
#   R1  cold start, NO wallet installed: NONE availability copy
#       leads with the free rides; PLAY/WALLET/SETTINGS press paths
#       at RESUMED (A4); sandbox census BEFORE connect (C-16);
#       wallet renders at 412x915 in dark AND light (C-27).
#   R2  migration: sealed v0.2.9 connects (legacy token written),
#       upgrade-install v0.2.10 over it: NOT restored from the
#       legacy token; reconnect prompt shows with the verbatim
#       reassurance line; Reconnect completes (C-10/C-26a).
#   R3  restore: force-stop, airplane mode ON, relaunch -> wallet
#       shows CONNECTED with no sheet and no network (C-13 device
#       half: cache-only restore, zero outbound proof by offline).
#   R4  config change (system theme flip recreates the activity),
#       then connect completes (A4 cell).
#   R5  concurrent connect: double-tap the connect control ->
#       exactly one wallet sheet; connect completes once (A4/C-20).
#   R6  disconnect success: DISCONNECT -> deauthorize sheet
#       approved -> DISCONNECTED; relaunch stays disconnected;
#       sandbox census AFTER disconnect (C-09/C-16).
#   R7  disconnect forced-failure: wallet package disabled ->
#       DISCONNECT still forgets locally; the honest residue note
#       renders verbatim (C-09/C-26c).
#   R8  wallet-present disconnected render (OTHER or CERTIFIED as
#       the fixture resolves) at both themes (C-27); the mock's
#       declared App Link status is recorded.
#   R9  in-game sign-in (best-effort drive): the shell challenge is
#       signed via Connect; a successful bind is itself the
#       cryptographic proof (ShellVerifier binds only on a valid
#       signature over the exact payload from the connected
#       account — a wrong signature extraction cannot verify).
#   R10 SESSION_CLOSED purchase (conditional on a drivable buy
#       path): kill the wallet mid-sign-and-send -> the purchase
#       resolves with the C-23 line; a second purchase attempt is
#       blocked by the guard WITHOUT opening the wallet (C-21).
#   R11 logcat review: no token material, no IllegalStateException,
#       no FATALs across the run (C-14).
#   R12 network window: tcpdump during play + wallet-idle (C-30
#       device half) — destinations filed; fallback = netstats.
#
# Driver mechanics: HAUNT/sibling lineage (substantive-dump guard,
# structural keyguard detection, sheet discipline, logcat windows).
set -u

PKG="com.hindsight.app"
ACT="com.hindsight.app/.MainActivity"
MOCK="com.solana.mwallet"
MOCK_ACT="com.solana.mwallet/.MainActivity"
OUT="${PM_OUT:-pm-v0210-runtime-evidence}"
TARGET="${TARGET:-candidate}"
PM_APK="${PM_APK:-}"
PM_APK_PREV="${PM_APK_PREV:-}"
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
  record_pm_pid
  adb logcat -d > "$OUT/logcat-$1.txt" 2>&1 || true
  scan_logcat "$OUT/logcat-$1.txt"
  adb logcat -c >/dev/null 2>&1 || true
}

# Battery #2 (TEKKADAN R1 dispositions): R11 counts IllegalState-
# Exceptions on the APP'S OWN pids only. Battery #1's global count
# (21) attributed entirely to Gboard / Settings / media-picker
# system processes — zero to this app. The app's pid is recorded
# at every window capture so force-stop/relaunch cycles across
# cells are all covered.
record_pm_pid() {
  local p
  p="$(adb shell pidof "$PKG" 2>/dev/null | tr -d '\r' || true)"
  if [ -n "$p" ]; then echo "$p" >> "$OUT/pm-pids.txt"; fi
}

# --- device record ---------------------------------------------------------------
adb wait-for-device
# Battery #2: root enables the direct sandbox census (C-16) on
# userdebug AVD images; harmless where unavailable.
adb root >/dev/null 2>&1 || true
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
# v0210: installation is DEFERRED into the battery — R1 must run
# with NO wallet installed, and R2 installs the PREVIOUS release
# first. install_mock is called by the battery at the right point.
install_mock() {
  log "install: mock wallet ($MOCK_APK)"
  if ! adb install "$MOCK_APK" > "$TMPD/install-mock.log" 2>&1; then
    cat "$TMPD/install-mock.log" | tee -a "$LANE_LOG"
    log "install: MOCK INSTALL FAILED — no wallet legs can run"
    echo "verdict=FAIL(mock install)" > "$OUT/verdict.txt"
    exit 1
  fi
}

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


# --- PM navigation (sibling lineage) ---------------------------------------------
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
  wait_for "CONNECT WALLET|CONNECTED|CONNECTION ERROR|first rides are free|Reconnect your wallet" 25 "$OUT/hier-wallet-entry.xml"
}

pm_state() { # $1 = dump file (fresh dump taken inside)
  if dump_ui "$1"; then
    if grep -q "CONNECTED" "$1" && grep -q "$EXPECTED_PUBKEY" "$1"; then echo "connected"
    elif grep -q "first rides are free" "$1"; then echo "none"
    elif grep -q "CONNECT WALLET\|Connect with Seeker" "$1"; then echo "disconnected"
    elif grep -q "TRY AGAIN" "$1"; then echo "error"
    elif grep -q "Waiting for wallet" "$1"; then echo "working"
    else echo "unknown"; fi
  else echo "nodump"; fi
}

sheet_present() { # $1 = dump file
  local fg
  fg="$(fg_pkg)"
  [ "$fg" = "$MOCK" ] && grep -qE 'text="(Connect|Approve|Cancel|Sign|Reject)"' "$1" 2>/dev/null
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

# --- cell bookkeeping --------------------------------------------------------------
CELLS_FILE="$OUT/cells.txt"
: > "$CELLS_FILE"
cell() { # $1 = id, $2 = PASS|FAIL|FIXTURE-LIMITED, $3 = detail
  echo "CELL $1 $2 $3" | tee -a "$LANE_LOG"
  echo "$1 $2 $3" >> "$CELLS_FILE"
}

sandbox_census() { # $1 = tag
  local dst="$OUT/sandbox-$1.txt"
  {
    echo "== sandbox census $1 $(date -u +%H:%M:%SZ) =="
    if adb shell id 2>/dev/null | grep -q "uid=0"; then
      # Root path (battery #2): battery #1's run-as census was
      # hollow — a release APK is not debuggable, so run-as
      # refused and NOTHING was observed. On a uid=0 AVD the
      # sandbox is read directly (file NAMES only, never
      # contents: the envelope and money records are not logged).
      echo "-- root path (uid=0): shared_prefs listing --"
      adb shell ls -la "/data/data/$PKG/shared_prefs/" 2>&1
      echo "-- stock Connect cache file (must be ABSENT) --"
      adb shell ls "/data/data/$PKG/shared_prefs/SeekerConnectAuthorizationCache.xml" 2>&1
      echo "-- full sandbox file list (names only) --"
      adb shell find "/data/data/$PKG" -type f 2>&1 | head -60
    else
      echo "-- run-as path (release APK is not debuggable; expected to refuse) --"
      adb shell run-as "$PKG" ls -R . 2>&1 | head -80
      echo "-- shared_prefs --"
      adb shell run-as "$PKG" ls -la shared_prefs/ 2>&1
      echo "-- stock Connect cache file (must be ABSENT) --"
      adb shell run-as "$PKG" ls shared_prefs/SeekerConnectAuthorizationCache.xml 2>&1
    fi
  } > "$dst" 2>&1
  log "sandbox census $1 filed"
}

approve_sheet() { # $1 = dump file — approve whatever the mock presents
  local f="$1"
  dump_ui "$f" || return 1
  grep -oE 'text="[^"]+"' "$f" | sort -u | head -14 | tee -a "$LANE_LOG" || true
  tap_exact "$f" "Connect" || tap_exact "$f" "Approve" || tap_exact "$f" "Sign" || \
    tap_text "$f" "Approve" || tap_text "$f" "Connect" || return 1
  sleep 3
  return 0
}


# --- battery helpers ---------------------------------------------------------------
airplane() { # $1 = on|off
  if [ "$1" = "on" ]; then
    adb shell cmd connectivity airplane-mode enable >/dev/null 2>&1 || adb shell settings put global airplane_mode_on 1
  else
    adb shell cmd connectivity airplane-mode disable >/dev/null 2>&1 || adb shell settings put global airplane_mode_on 0
  fi
  sleep 2
}

set_theme() { # $1 = dark|light
  if [ "$1" = "light" ]; then adb shell cmd uimode night no >/dev/null 2>&1 || true
  else adb shell cmd uimode night yes >/dev/null 2>&1 || true; fi
  sleep 2
}

connect_via_ui() { # assumes wallet screen reachable; ends connected or returns 1
  goto_wallet || return 1
  local f="$TMPD/connect.xml"
  dump_ui "$f" || return 1
  if grep -q "CONNECTED" "$f" && grep -q "$EXPECTED_PUBKEY" "$f"; then return 0; fi
  tap_exact "$f" "CONNECT WALLET" || tap_text "$f" "Connect with Seeker" || tap_text "$f" "Reconnect wallet" || return 1
  if wait_sheet "$TMPD/sheet-connect.xml" 45; then
    approve_sheet "$TMPD/sheet-connect.xml" || return 1
  fi
  wait_for "CONNECTED" 30 "$TMPD/connect-final.xml" || return 1
  dump_ui "$TMPD/connect-final.xml" && grep -q "$EXPECTED_PUBKEY" "$TMPD/connect-final.xml"
}

disconnect_via_ui() { # ends disconnected or returns 1; approves a deauthorize sheet if one appears
  goto_wallet || return 1
  local f="$TMPD/disconnect.xml"
  dump_ui "$f" || return 1
  if grep -q "first rides are free\|CONNECT WALLET\|Connect with Seeker" "$f" && ! grep -q "CONNECTED" "$f"; then return 0; fi
  tap_exact "$f" "DISCONNECT" || return 1
  # A deauthorize interaction may present the mock sheet; approve it if so.
  if wait_sheet "$TMPD/sheet-deauth.xml" 18; then
    approve_sheet "$TMPD/sheet-deauth.xml" || true
  fi
  # The SDK error dialog (forced-failure path) presents its own
  # window OVER the wallet surface: a hierarchy dump taken while
  # it is up shows the dialog, not the surface (and any surface
  # copy, e.g. the residue note, is occluded). Dismiss it IN
  # PLACE via its "Close" control — never by navigating, which
  # would reset the surface's remember-scoped state — then wait
  # for the surface itself.
  local g="$TMPD/disconnect-final.xml" i
  for i in 1 2 3 4 5 6 7 8 9 10; do
    if dump_ui "$g"; then
      if grep -q "CONNECTED" "$g" && grep -q "$EXPECTED_PUBKEY" "$g"; then sleep 2; continue; fi
      if grep -qE "text=\"(Dismiss|OK|Close)\"" "$g"; then tap_text "$g" "Dismiss" || tap_text "$g" "OK" || tap_text "$g" "Close" || true; sleep 2; continue; fi
      return 0
    fi
    sleep 2
  done
  return 1
}


# =============================== R7 MICRO-CELL ====================================
# Isolated re-run of battery cell R7 (forced-failure disconnect ->
# C-09 residue note) on the sealed candidate #5 binary, after two
# full-battery attempts wedged on the runner (battery #3, with the
# pre-cure cell, completed the whole suite in 19 min). Same helpers,
# same flow as the cured battery cell; the workflow caps the job at
# 40 minutes and uploads evidence unconditionally.
adb shell wm size 1080x2400 >/dev/null 2>&1 || true
adb shell wm density 420 >/dev/null 2>&1 || true
adb logcat -c >/dev/null 2>&1 || true

log "=== R7 micro-cell: forced-failure disconnect residue note ==="
install_mock
if ! adb install "$PM_APK" > "$TMPD/install-candidate.log" 2>&1; then
  cat "$TMPD/install-candidate.log" | tee -a "$LANE_LOG"
  echo "verdict=FAIL(candidate install)" > "$OUT/verdict.txt"; exit 1
fi
authenticate_mock || true

r7="FAIL"; r7d=""
if connect_via_ui; then
  log "micro: connected; disabling the mock for the forced failure"
  adb shell pm disable "$MOCK" >/dev/null 2>&1 || true
  adb shell am force-stop "$MOCK" >/dev/null 2>&1 || true
  sleep 1
  mock_disabled="no"
  if adb shell pm list packages -d 2>/dev/null | grep -q "package:$MOCK"; then mock_disabled="yes"; fi
  log "R7 premise: mock disabled = $mock_disabled"
  if [ "$mock_disabled" != "yes" ]; then
    r7="FIXTURE-LIMITED"; r7d="pm disable did not take on this AVD; forced-failure premise not established"
  elif disconnect_via_ui; then
    note=""
    for i in 1 2 3 4 5 6; do
      dump_ui "$OUT/hier-disconnect-residue.xml" || true
      if grep -q "still lists Paper Moon as connected" "$OUT/hier-disconnect-residue.xml"; then note="yes"; break; fi
      if grep -qE "text=\"(Close|Dismiss|OK)\"" "$OUT/hier-disconnect-residue.xml"; then
        tap_text "$OUT/hier-disconnect-residue.xml" "Close" || tap_text "$OUT/hier-disconnect-residue.xml" "Dismiss" || tap_text "$OUT/hier-disconnect-residue.xml" "OK" || true
      fi
      sleep 3
    done
    snap "disconnect-residue.png"
    if [ -n "$note" ]; then
      r7="PASS"; r7d="failed deauthorize disclosed with the verbatim residue note on the same surface; local forget completed"
    else
      dump_ui "$OUT/hier-disconnect-residue-final.xml" || true
      r7d="disconnect completed but the residue note was not found on the in-place dumps (dialog dismissed in place; no navigation)"
    fi
  else
    r7d="forced-failure disconnect flow did not complete"
  fi
  adb shell pm enable "$MOCK" >/dev/null 2>&1 || true
else
  r7d="connect_via_ui failed before the disconnect leg"
fi
cell R7 "$r7" "$r7d"
adb shell logcat -d > "$OUT/logcat-r7.txt" 2>/dev/null || true
{
  echo "target=v0.2.10-candidate-r7-microcell"
  cat "$CELLS_FILE"
} > "$OUT/verdict.txt"
log "micro-cell done: R7=$r7"
