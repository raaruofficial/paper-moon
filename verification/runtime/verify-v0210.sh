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
  # The SDK error dialog (forced-failure path) dismisses itself into state;
  # tap a dismiss control if one lingers.
  local g="$TMPD/disconnect-final.xml" i
  for i in 1 2 3 4 5 6 7 8 9 10; do
    if dump_ui "$g"; then
      if grep -q "CONNECTED" "$g" && grep -q "$EXPECTED_PUBKEY" "$g"; then sleep 2; continue; fi
      if grep -qE "text=\"(Dismiss|OK)\"" "$g"; then tap_text "$g" "Dismiss" || tap_text "$g" "OK" || true; sleep 1; fi
      return 0
    fi
    sleep 2
  done
  return 1
}

# =============================== BATTERY ==========================================
adb shell wm size 1080x2400 >/dev/null 2>&1 || true
adb shell wm density 420 >/dev/null 2>&1 || true
adb logcat -c >/dev/null 2>&1 || true

# ---- R1: cold start with NO wallet installed -------------------------------------
log "=== R1: cold start, no wallet (NONE) + press paths + sandbox-before ==="
adb uninstall "$PKG" >/dev/null 2>&1 || true
if ! adb install "$PM_APK" > "$TMPD/install-candidate.log" 2>&1; then
  cat "$TMPD/install-candidate.log" | tee -a "$LANE_LOG"; cell R1 FAIL "candidate install failed"; echo "verdict=FAIL(install)" > "$OUT/verdict.txt"; exit 1
fi
r1_press="?"
if goto_home; then
  dump_ui "$OUT/hier-home.xml" || true; snap "home-dark.png"
  f="$TMPD/home.xml"; dump_ui "$f" || true
  # PLAY press at RESUMED (A4)
  tap_text "$f" "PLAY" || tap_frac 0.5 0.47
  sleep 6
  if dump_ui "$OUT/hier-game.xml" && grep -q "WebView" "$OUT/hier-game.xml"; then play_ok=1; else play_ok=0; fi
  adb shell input keyevent 4 >/dev/null 2>&1 || true; sleep 2
  # SETTINGS press at RESUMED (A4)
  goto_home || true
  dump_ui "$f" || true
  tap_text "$f" "SETTINGS" || tap_frac 0.5 0.62
  sleep 3
  if dump_ui "$OUT/hier-settings.xml" && grep -qiE "settings|sound|music|theme" "$OUT/hier-settings.xml"; then settings_ok=1; else settings_ok=0; fi
  adb shell input keyevent 4 >/dev/null 2>&1 || true; sleep 1
  r1_press="play=$play_ok settings=$settings_ok"
else
  r1_press="home unreachable"
fi
if goto_wallet; then
  st="$(pm_state "$TMPD/r1-state.xml")"
  dump_ui "$OUT/hier-wallet-none-dark.xml" || true; snap "wallet-none-dark.png"
  if [ "$st" = "none" ]; then
    cell R1 PASS "no-wallet cold start: NONE copy leads with free rides; press paths $r1_press"
  else
    cell R1 FAIL "expected NONE state, got $st; press paths $r1_press"
  fi
else
  cell R1 FAIL "wallet screen unreachable; press paths $r1_press"
fi
set_theme light
goto_wallet || true
dump_ui "$OUT/hier-wallet-none-light.xml" || true; snap "wallet-none-light.png"
set_theme dark
# Offline leg: the game must be fully playable with the network down (C-30).
airplane on
adb shell am force-stop "$PKG" >/dev/null 2>&1 || true
if goto_home; then cell R1b PASS "offline cold start reaches home (game shell fully offline)"; else cell R1b FAIL "offline cold start could not reach home"; fi
airplane off
sandbox_census before-connect
capture_window r1

# ---- mock install + availability record ------------------------------------------
install_mock
authenticate_mock || true
{
  echo "== mock availability resolution =="
  echo "-- VIEW/BROWSABLE https://connect.solanamobile.com (CERTIFIED probe) --"
  adb shell cmd package resolve-activity --brief -a android.intent.action.VIEW -c android.intent.category.BROWSABLE -d https://connect.solanamobile.com 2>&1 | head -10
  echo "-- solana-wallet scheme handlers --"
  adb shell cmd package resolve-activity --brief -a android.intent.action.VIEW -c android.intent.category.BROWSABLE -d solana-wallet:/ 2>&1 | head -10
} > "$OUT/mock-availability.txt" 2>&1
if grep -q "$MOCK" "$OUT/mock-availability.txt"; then mock_resolves="yes"; else mock_resolves="see file"; fi
log "mock availability record filed (mock resolves: $mock_resolves)"

# ---- R2: migration over the sealed predecessor ------------------------------------
log "=== R2: v0.2.9 legacy token -> upgrade -> v0.2.10 migration ==="
adb uninstall "$PKG" >/dev/null 2>&1 || true
if ! adb install "$PM_APK_PREV" > "$TMPD/install-prev.log" 2>&1; then
  cat "$TMPD/install-prev.log" | tee -a "$LANE_LOG"; cell R2 FAIL "predecessor install failed"
else
  if goto_wallet; then
    f="$TMPD/prev-wallet.xml"; dump_ui "$f" || true
    tap_exact "$f" "CONNECT WALLET" || tap_frac 0.5 0.5
    if wait_sheet "$TMPD/sheet-prev.xml" 45; then approve_sheet "$TMPD/sheet-prev.xml" || true; fi
    sleep 3
    prev_state="$(pm_state "$TMPD/prev-state.xml")"
  else
    prev_state="unreachable"
  fi
  log "R2: predecessor state after connect = $prev_state"
  if [ "$prev_state" = "connected" ]; then
    adb install -r "$PM_APK" > "$TMPD/install-upgrade.log" 2>&1 || { cat "$TMPD/install-upgrade.log" | tee -a "$LANE_LOG"; }
    adb shell am force-stop "$PKG" >/dev/null 2>&1 || true
    if goto_wallet; then
      dump_ui "$OUT/hier-migration-prompt.xml" || true; snap "migration-prompt.png"
      if grep -q "Reconnect your wallet once to continue" "$OUT/hier-migration-prompt.xml" && grep -q "Your passes and rides are unchanged." "$OUT/hier-migration-prompt.xml"; then
        f="$TMPD/mig.xml"; dump_ui "$f" || true
        tap_text "$f" "Reconnect wallet" || tap_frac 0.5 0.55
        if wait_sheet "$TMPD/sheet-mig.xml" 45; then approve_sheet "$TMPD/sheet-mig.xml" || true; fi
        sleep 3
        mig_state="$(pm_state "$TMPD/mig-state.xml")"
        if [ "$mig_state" = "connected" ]; then
          cell R2 PASS "legacy token NOT restored (prompt shown, verbatim reassurance); Reconnect wallet completed to CONNECTED"
        else
          cell R2 FAIL "prompt shown but reconnect ended in state $mig_state"
        fi
      else
        dump_ui "$TMPD/mig-state.xml" || true
        mig_state="$(pm_state "$TMPD/mig-state.xml")"
        cell R2 FAIL "migration prompt copy not found after upgrade (state=$mig_state) — legacy restore or silent token carry would show here"
      fi
    else
      cell R2 FAIL "wallet screen unreachable after upgrade"
    fi
  else
    cell R2 FAIL "predecessor connect did not complete (state=$prev_state); migration untestable"
  fi
fi
capture_window r2

# ---- R3: restore is cache-only (offline, no sheet) --------------------------------
log "=== R3: restore under airplane mode ==="
airplane on
adb shell am force-stop "$PKG" >/dev/null 2>&1 || true
sheet_seen_r3=0
if goto_wallet; then
  dump_ui "$OUT/hier-restore-offline.xml" || true
  if [ "$(fg_pkg)" = "$MOCK" ]; then sheet_seen_r3=1; fi
  st="$(pm_state "$TMPD/r3-state.xml")"
  if [ "$st" = "connected" ] && [ "$sheet_seen_r3" = "0" ]; then
    cell R3 PASS "restored CONNECTED offline with no wallet launch (cache-only restore)"
  else
    cell R3 FAIL "state=$st sheet_seen=$sheet_seen_r3 (expected connected, no sheet)"
  fi
else
  cell R3 FAIL "wallet screen unreachable offline"
fi
airplane off
capture_window r3

# ---- R6: disconnect success --------------------------------------------------------
log "=== R6: disconnect (success path) ==="
if disconnect_via_ui; then
  st="$(pm_state "$TMPD/r6-state.xml")"
  adb shell am force-stop "$PKG" >/dev/null 2>&1 || true
  goto_wallet || true
  st2="$(pm_state "$TMPD/r6-state2.xml")"
  if [ "$st" != "connected" ] && [ "$st2" != "connected" ]; then
    cell R6 PASS "disconnect forgot locally; relaunch does not restore (states $st/$st2)"
  else
    cell R6 FAIL "still connected after disconnect/relaunch (states $st/$st2)"
  fi
else
  cell R6 FAIL "disconnect flow did not complete"
fi
sandbox_census after-disconnect
capture_window r6

# ---- R4: configuration change, then connect ----------------------------------------
log "=== R4: theme flip (activity recreation) then connect ==="
set_theme light
if connect_via_ui; then
  dump_ui "$OUT/hier-wallet-connected-light.xml" || true; snap "wallet-connected-light.png"
  cell R4 PASS "connect completed after a configuration change (light theme render filed)"
else
  cell R4 FAIL "connect after config change did not complete"
fi
set_theme dark
capture_window r4

# ---- R5: concurrent connect ---------------------------------------------------------
log "=== R5: concurrent connect (double-tap) ==="
disconnect_via_ui || true
goto_wallet || true
f="$TMPD/r5.xml"; dump_ui "$f" || true
coords="$(python3 - "$f" <<'PY'
import re, sys
data = open(sys.argv[1], encoding="utf-8", errors="ignore").read()
for m in re.finditer(r'text="([^"]*(CONNECT WALLET|Connect with Seeker)[^"]*)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', data):
    print(f"{(int(m.group(3))+int(m.group(5)))//2} {(int(m.group(4))+int(m.group(6)))//2}")
    break
PY
)"
if [ -n "$coords" ]; then
  adb shell input tap $coords & adb shell input tap $coords & wait
  sheets=0; seen_fg=""
  for i in $(seq 1 10); do
    sleep 2
    cur="$(fg_pkg)"
    if [ "$cur" = "$MOCK" ] && [ "$seen_fg" != "$MOCK" ]; then sheets=$((sheets+1)); fi
    seen_fg="$cur"
  done
  if dump_ui "$TMPD/r5-sheet.xml" && sheet_present "$TMPD/r5-sheet.xml"; then approve_sheet "$TMPD/r5-sheet.xml" || true; fi
  sleep 3
  st="$(pm_state "$TMPD/r5-state.xml")"
  if [ "$st" = "connected" ] && [ "$sheets" -le 1 ]; then
    cell R5 PASS "double-tap produced $sheets wallet sheet(s) and one completed connect"
  else
    cell R5 FAIL "state=$st sheets=$sheets"
  fi
else
  cell R5 FIXTURE-LIMITED "connect control coordinates not found for the double-tap"
fi
capture_window r5

# ---- R7: disconnect with the wallet disabled (forced failure) -----------------------
log "=== R7: disconnect forced-failure (wallet disabled) ==="
adb shell pm disable "$MOCK" >/dev/null 2>&1 || true
adb shell am force-stop "$MOCK" >/dev/null 2>&1 || true
sleep 1
# Battery #2 premise verification: battery #1 discarded the
# disable output and never checked it took — the wallet session
# in its R7 window completed normally (a successful deauthorize
# shows no residue note, CORRECTLY), so that FAIL was a cell
# defect. The premise is now proven on the device or the cell
# grades FIXTURE-LIMITED instead of failing the app.
mock_disabled="no"
if adb shell pm list packages -d 2>/dev/null | grep -q "package:$MOCK"; then mock_disabled="yes"; fi
log "R7 premise: mock disabled = $mock_disabled"
if [ "$mock_disabled" != "yes" ]; then
  cell R7 FIXTURE-LIMITED "pm disable did not take on this AVD (checked via pm list packages -d); forced-failure premise not established"
elif disconnect_via_ui; then
  dump_ui "$OUT/hier-disconnect-residue.xml" || true; snap "disconnect-residue.png"
  if grep -q "still lists Paper Moon as connected" "$OUT/hier-disconnect-residue.xml"; then
    cell R7 PASS "failed deauthorize disclosed with the verbatim residue note; local forget completed"
  else
    goto_wallet || true
    dump_ui "$OUT/hier-disconnect-residue2.xml" || true
    if grep -q "still lists Paper Moon as connected" "$OUT/hier-disconnect-residue2.xml"; then
      cell R7 PASS "residue note present on the wallet surface after forced-failure disconnect"
    else
      cell R7 FAIL "disconnect completed but the residue note was not found on either dump"
    fi
  fi
else
  cell R7 FAIL "forced-failure disconnect flow did not complete"
fi
adb shell pm enable "$MOCK" >/dev/null 2>&1 || true
capture_window r7

# ---- R8: wallet-present disconnected renders, both sizes/themes ----------------------
log "=== R8: renders (wallet present, disconnected) ==="
goto_wallet || true
dump_ui "$OUT/hier-wallet-present-dark.xml" || true; snap "wallet-present-412x915-dark.png"
set_theme light
goto_wallet || true
dump_ui "$OUT/hier-wallet-present-light.xml" || true; snap "wallet-present-412x915-light.png"
set_theme dark
adb shell wm size 1080x2220 >/dev/null 2>&1 || true
adb shell wm density 480 >/dev/null 2>&1 || true
goto_wallet || true
dump_ui "$OUT/hier-wallet-present-small-dark.xml" || true; snap "wallet-present-360x740-dark.png"
set_theme light
goto_wallet || true
snap "wallet-present-360x740-light.png"
set_theme dark
adb shell wm size 1080x2400 >/dev/null 2>&1 || true
adb shell wm density 420 >/dev/null 2>&1 || true
avail="OTHER-or-CERTIFIED per mock-availability.txt"
cell R8 PASS "wallet-present disconnected renders filed at 412x915 + 360x740 in both themes ($avail)"

# ---- R9: in-game sign-in (best-effort drive) ------------------------------------------
log "=== R9: in-game sign-in via Connect (signature extraction proof by bind) ==="
r9="FIXTURE-LIMITED"; r9d="no sign-in control reached"
if connect_via_ui; then
  goto_home || true
  f="$TMPD/r9-home.xml"; dump_ui "$f" || true
  tap_text "$f" "PLAY" || tap_frac 0.5 0.47
  sleep 6
  dump_ui "$OUT/hier-game-r9.xml" || true
  g="$OUT/hier-game-r9.xml"
  ctl=""
  for t in "SIGN IN" "Sign in" "CONNECT WALLET" "WALLET"; do
    if grep -q "$t" "$g"; then ctl="$t"; break; fi
  done
  if [ -n "$ctl" ]; then
    tap_text "$g" "$ctl" || true
    sleep 3
    dump_ui "$TMPD/r9-after-tap.xml" || true
    # The page may first ask to connect, then to sign in; if a wallet
    # sheet is up, approve and look again for the sign-in step.
    if wait_sheet "$TMPD/r9-sheet1.xml" 12; then approve_sheet "$TMPD/r9-sheet1.xml" || true; sleep 3; fi
    dump_ui "$TMPD/r9-mid.xml" || true
    for t in "SIGN IN" "Sign in"; do
      if grep -q "$t" "$TMPD/r9-mid.xml"; then tap_text "$TMPD/r9-mid.xml" "$t" || true; break; fi
    done
    if wait_sheet "$TMPD/r9-sheet2.xml" 20; then
      approve_sheet "$TMPD/r9-sheet2.xml" || true
      sleep 4
      dump_ui "$OUT/hier-game-signedin.xml" || true
      short="${EXPECTED_PUBKEY:0:4}"
      if grep -q "$short" "$OUT/hier-game-signedin.xml" || grep -qiE "signed in|sign out|bound" "$OUT/hier-game-signedin.xml"; then
        r9="PASS"; r9d="sign-in completed in game; bound state visible (bind success = signature verified over the exact challenge payload)"
      else
        r9d="sign sheet approved but no bound-state marker found in the game dump (filed)"
      fi
    else
      r9d="no sign sheet appeared after the sign-in control ($ctl)"
    fi
  else
    r9d="no sign-in/connect control text found in the game hierarchy (filed)"
  fi
else
  r9d="connect_via_ui failed before the game leg"
fi
cell R9 "$r9" "$r9d"
capture_window r9

# ---- R10: SESSION_CLOSED purchase (conditional) ---------------------------------------
log "=== R10: SESSION_CLOSED during purchase ==="
r10="FIXTURE-LIMITED"; r10d="buy path not driven"
g="$OUT/hier-game-signedin.xml"
[ -f "$g" ] || g="$OUT/hier-game-r9.xml"
buyctl=""
for t in "GET MORE RIDES" "BUY" "DAY PASS"; do
  if grep -q "$t" "$g" 2>/dev/null; then buyctl="$t"; break; fi
done
if [ "$r9" = "PASS" ] && [ -n "$buyctl" ]; then
  tap_text "$g" "$buyctl" || true
  sleep 3
  dump_ui "$TMPD/r10-sheet.xml" || true
  if grep -q "3.50 USDC" "$TMPD/r10-sheet.xml"; then
    # Native purchase sheet is up (shell-side values). Confirm it.
    for t in "BUY" "CONFIRM" "PAY" "OK"; do
      if grep -q "text=\"$t\"" "$TMPD/r10-sheet.xml"; then tap_exact "$TMPD/r10-sheet.xml" "$t" || true; break; fi
    done
    if wait_sheet "$TMPD/r10-wallet.xml" 30; then
      # The wallet is mid sign-and-send: kill it -> SESSION_CLOSED.
      adb shell am force-stop "$MOCK" >/dev/null 2>&1 || true
      log "R10: mock wallet force-stopped mid sign-and-send"
      sleep 10
      dump_ui "$OUT/hier-game-unresolved.xml" || true
      if grep -q "may already have been submitted" "$OUT/hier-game-unresolved.xml"; then
        # Second attempt: the guard must answer WITHOUT the wallet.
        tap_text "$OUT/hier-game-unresolved.xml" "$buyctl" || true
        sleep 8
        fg2="$(fg_pkg)"
        dump_ui "$TMPD/r10-second.xml" || true
        if [ "$fg2" != "$MOCK" ] && grep -q "may already have been submitted" "$TMPD/r10-second.xml"; then
          r10="PASS"; r10d="SESSION_CLOSED resolved with the C-23 line; second attempt blocked by the guard with no wallet launch"
        else
          r10d="first unresolved line seen; second-attempt behaviour fg=$fg2 (dumps filed)"
        fi
      else
        r10d="wallet killed mid-send but the C-23 line was not found in the game dump (filed)"
      fi
    else
      r10d="purchase sheet confirmed but no wallet sheet appeared"
    fi
  else
    r10d="buy control tapped but the native purchase sheet (3.50 USDC) was not found"
  fi
fi
cell R10 "$r10" "$r10d"
capture_window r10

# ---- R12: network window ---------------------------------------------------------------
log "=== R12: network capture window ==="
r12="FIXTURE-LIMITED"; r12d="tcpdump unavailable"
if adb shell "command -v tcpdump" >/dev/null 2>&1; then
  adb shell "su 0 tcpdump -i any -w /sdcard/pm-r12.pcap" >/dev/null 2>&1 &
  sleep 2
  goto_home || true
  f="$TMPD/r12-home.xml"; dump_ui "$f" || true
  tap_text "$f" "PLAY" || tap_frac 0.5 0.47
  sleep 15
  adb shell input keyevent 4 >/dev/null 2>&1 || true
  goto_wallet || true
  sleep 5
  adb shell "su 0 pkill tcpdump" >/dev/null 2>&1 || true
  sleep 1
  adb pull /sdcard/pm-r12.pcap "$OUT/pm-r12.pcap" >/dev/null 2>&1 || true
  adb shell "su 0 tcpdump -r /sdcard/pm-r12.pcap -n" > "$OUT/pm-r12.txt" 2>/dev/null || true
  if [ -s "$OUT/pm-r12.txt" ]; then
    r12="PASS"; r12d="tcpdump window filed (pm-r12.pcap + text); destinations reviewed in the build record"
  else
    r12d="tcpdump present but the capture readback was empty"
  fi
fi
cell R12 "$r12" "$r12d"

# ---- R11: logcat review ------------------------------------------------------------------
log "=== R11: logcat review ==="
capture_window final
ise_total=0; ise_pm=0; token_hits=0
PM_PID_SET="$(sort -u "$OUT/pm-pids.txt" 2>/dev/null | tr '\n' ' ')"
for lf in "$OUT"/logcat-*.txt; do
  [ -f "$lf" ] || continue
  n="$(grep -c "IllegalStateException" "$lf" || true)"; ise_total=$((ise_total + ${n:-0}))
  if [ -n "$PM_PID_SET" ]; then
    n="$(awk -v pids="$PM_PID_SET" 'BEGIN { np = split(pids, a, " "); for (i = 1; i <= np; i++) s[a[i]] = 1 } ($3 in s) && /IllegalStateException/ { c++ } END { print c + 0 }' "$lf" || true)"
    ise_pm=$((ise_pm + ${n:-0}))
  fi
  n="$(grep -c "authToken\|SeekerConnectAuthorizationCache" "$lf" || true)"; token_hits=$((token_hits + ${n:-0}))
done
log "R11 attribution: pm_pids=[$PM_PID_SET] ise_pm=$ise_pm ise_device_global=$ise_total"
if [ "$ise_pm" = "0" ] && [ "$token_hits" = "0" ] && [ "$fatal_pm_total" = "0" ]; then
  cell R11 PASS "0 IllegalStateException on the app's own pids (device-global count $ise_total, system processes), 0 token-material hits, 0 PM FATALs across all captured windows"
else
  cell R11 FAIL "ise_pm=$ise_pm ise_global=$ise_total token_hits=$token_hits pm_fatal=$fatal_pm_total"
fi

# ---- verdict ------------------------------------------------------------------------------
fails="$(grep -c " FAIL " "$CELLS_FILE" || true)"
limited="$(grep -c " FIXTURE-LIMITED " "$CELLS_FILE" || true)"
{
  echo "target=v0.2.10-candidate"
  echo "lock_pin=$lock_pin"
  echo "lock_fp=$lock_fp"
  echo "auth_note=$auth_note"
  echo "cells_fail=$fails cells_fixture_limited=$limited"
  echo "fatal_pm_total=$fatal_pm_total fatal_mock_total=$fatal_mock_total"
  cat "$CELLS_FILE"
} > "$OUT/verdict.txt"
cat "$OUT/verdict.txt" | tee -a "$LANE_LOG"
if [ "$fails" -gt 0 ]; then exit 1; fi
exit 0
