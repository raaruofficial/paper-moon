#!/bin/sh
# v029 registration audit gate (Gate 0 C-19; design: ARCHITECT §4,
# assertions A1-A5). Runs on ARTIFACTS from ONE CI run — the build
# record cites that run ID; APK, mapping, AARs and merged manifest
# must all come from it. Local Gradle is dead; this gate never
# builds anything.
#
# Usage:
#   v029_gate.sh --apk <release-candidate.apk> --mapping <mapping.txt> \
#     --aar-dir <dir of resolved .aar> --merged-manifest <file> \
#     --baseline-manifest <v0.2.8 manifest of record> \
#     [--expect-sender-sites 0] \
#     [--classifications <file>] [--a4-evidence <file>] \
#     [--manifest-justifications <file>] [--evidence-out <dir>]
#
# Classifications file (A3): one line per registration call site,
#   <caller-class>#<method> <GUARD-CTOR|GUARD-LAZY|PER-CALL> [evidence-ref]
# PER-CALL lines MUST carry an evidence ref (the A4 arm). UNKNOWN
# or missing lines fail the gate.
#
# A4 evidence file: one "cell <name> <run-ref>" line per required
# cell (see CELLS below); the gate verifies completeness only —
# behaviour is the runtime harness's job.
#
# Exit 1 on any violation.
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APK=""; MAPPING=""; AARDIR=""; MERGED=""; BASELINE=""
EXPECT_SENDER=0; CLASSIF=""; A4=""; JUSTIF=""; EVIDOUT=""
while [ $# -gt 0 ]; do
  case "$1" in
    --apk) APK="$2"; shift 2;;
    --mapping) MAPPING="$2"; shift 2;;
    --aar-dir) AARDIR="$2"; shift 2;;
    --merged-manifest) MERGED="$2"; shift 2;;
    --baseline-manifest) BASELINE="$2"; shift 2;;
    --expect-sender-sites) EXPECT_SENDER="$2"; shift 2;;
    --classifications) CLASSIF="$2"; shift 2;;
    --a4-evidence) A4="$2"; shift 2;;
    --manifest-justifications) JUSTIF="$2"; shift 2;;
    --evidence-out) EVIDOUT="$2"; shift 2;;
    *) echo "v029 gate: unknown arg $1"; exit 2;;
  esac
done
fail=0
say() { echo "v029 $1"; }
bad() { echo "v029 FAIL: $1"; fail=1; }
for f in "$APK" "$MAPPING" "$MERGED" "$BASELINE"; do
  [ -n "$f" ] && [ -f "$f" ] || { echo "v029 FAIL: required artifact missing: $f"; exit 1; }
done
[ -d "$AARDIR" ] || { echo "v029 FAIL: --aar-dir missing: $AARDIR"; exit 1; }
[ -n "$EVIDOUT" ] && mkdir -p "$EVIDOUT"

WORK=$(mktemp -d /tmp/pm-v029-gate.XXXXXX)
trap 'rm -rf "$WORK"' EXIT
(cd "$WORK" && unzip -q "$APK" "classes*.dex")

# ---------------------------------------------------------------- A1 source
# Comment-stripped count of app-owned ActivityResultSender( code sites.
A1_SRC=$(python3 - "$ROOT/app/src/main/java" <<'PYEOF'
import glob, re, sys
total = 0
for path in glob.glob(sys.argv[1] + "/**/*.kt", recursive=True):
    src = open(path, encoding="utf-8", errors="replace").read()
    src = re.sub(r"/\*.*?\*/", "", src, flags=re.S)
    src = re.sub(r"//[^\n]*", "", src)
    total += len(re.findall(r"ActivityResultSender\(", src))
print(total)
PYEOF
)
say "A1 source ActivityResultSender( sites = $A1_SRC (expect $EXPECT_SENDER)"
[ "$A1_SRC" = "$EXPECT_SENDER" ] || bad "A1 source sender constructions != expectation"

# ---------------------------------------------------------------- A1 dex
python3 "$ROOT/verification/dex_registration_sites.py" sender "$WORK" "$MAPPING" > "$WORK/a1.txt" 2>&1
A1_DEX=$(grep -o "sites=[0-9]*" "$WORK/a1.txt" | tail -1 | cut -d= -f2)
say "A1 dex ActivityResultSender.<init> sites = ${A1_DEX:-?} (expect $EXPECT_SENDER)"
[ "$A1_DEX" = "$EXPECT_SENDER" ] || bad "A1 dex sender constructions != expectation"
[ -n "$EVIDOUT" ] && cp "$WORK/a1.txt" "$EVIDOUT/a1_sender_sites.txt"

# ------------------------------------------------------- A2 source + A2/A3 dex
A2_SRC=$(python3 - "$ROOT/app/src/main/java" <<'PYEOF'
import glob, re, sys
total = 0
for path in glob.glob(sys.argv[1] + "/**/*.kt", recursive=True):
    src = open(path, encoding="utf-8", errors="replace").read()
    src = re.sub(r"/\*.*?\*/", "", src, flags=re.S)
    src = re.sub(r"//[^\n]*", "", src)
    total += len(re.findall(r"registerForActivityResult|rememberLauncherForActivityResult", src))
print(total)
PYEOF
)
say "A2 source direct-registration sites = $A2_SRC (must be 0)"
[ "$A2_SRC" = "0" ] || bad "A2 app code registers activity results directly"

python3 "$ROOT/verification/dex_registration_sites.py" registration "$WORK" "$MAPPING" > "$WORK/a23.txt" 2>&1
python3 "$ROOT/verification/dex_registration_sites.py" aar "$AARDIR" > "$WORK/a3aar.txt" 2>&1
[ -n "$EVIDOUT" ] && cp "$WORK/a23.txt" "$EVIDOUT/a3_dex_registration_sites.txt" && cp "$WORK/a3aar.txt" "$EVIDOUT/a3_aar_registration_sites.txt"
cat "$WORK/a23.txt" "$WORK/a3aar.txt" | grep "^SITE" > "$WORK/sites.txt" || true
say "A3 registration call sites (dex + AAR): $(wc -l < "$WORK/sites.txt")"

# A2 dex half: no registration site may have an app-package caller.
APP_SITES=$(grep -c "caller=com.hindsight.app" "$WORK/sites.txt" || true)
say "A2 dex registration sites with app callers = $APP_SITES (must be 0)"
[ "$APP_SITES" = "0" ] || bad "A2 app class registers activity results in the release dex"

# A3: every SDK registration site needs a classification line; no UNKNOWN.
if [ -n "$CLASSIF" ] && [ -f "$CLASSIF" ]; then
  while IFS= read -r line; do
    caller=$(echo "$line" | sed -n 's/.*caller=\([^ ]*\).*/\1/p')
    method=$(echo "$line" | sed -n 's/.*method=\([^ ]*\).*/\1/p')
    key="$caller#$method"
    cline=$(grep -F "$key" "$CLASSIF" | head -1 || true)
    if [ -z "$cline" ]; then bad "A3 unclassified registration site: $key"; continue; fi
    case "$cline" in
      *UNKNOWN*) bad "A3 site classified UNKNOWN: $key";;
      *PER-CALL*)
        # PER-CALL arms A4: an evidence ref must ride the line.
        rest=$(echo "$cline" | awk '{print $3}')
        [ -n "$rest" ] || bad "A3 PER-CALL site without an evidence ref: $key";;
    esac
  done < "$WORK/sites.txt"
  say "A3 classifications checked against $CLASSIF"
else
  bad "A3 no classifications file given (--classifications)"
fi

# ---------------------------------------------------------------- A4 evidence
CELLS="press-play-resumed press-wallet-resumed press-settings-resumed connect-after-config-change connect-after-process-death mid-session-activity-destruction concurrent-connect-one-launch zero-illegal-state-exception zero-destroyed-activity-launch"
if [ -n "$A4" ] && [ -f "$A4" ]; then
  for cell in $CELLS; do
    grep -q "cell $cell " "$A4" || bad "A4 missing evidence cell: $cell"
  done
  say "A4 evidence completeness checked against $A4"
else
  bad "A4 no evidence index given (--a4-evidence)"
fi

# ---------------------------------------------------------------- A5 manifest
diff -u "$BASELINE" "$MERGED" > "$WORK/manifest.diff" || true
[ -n "$EVIDOUT" ] && cp "$WORK/manifest.diff" "$EVIDOUT/a5_manifest.diff"
ADDED=$(grep -c "^+[^+]" "$WORK/manifest.diff" || true)
say "A5 manifest delta: $ADDED added lines (diff filed)"
if [ "$ADDED" -gt 0 ]; then
  if [ -n "$JUSTIF" ] && [ -f "$JUSTIF" ]; then
    # Every added android:name value (permission, component, query
    # action/scheme) must be named in the justifications file.
    for name in $(grep "^+[^+]" "$WORK/manifest.diff" | grep -o 'android:name="[^"]*"' | sed 's/android:name="//; s/"//' | sort -u); do
      grep -qF "$name" "$JUSTIF" || bad "A5 added manifest entry without a justification line: $name"
    done
    for tag in $(grep "^+[^+]" "$WORK/manifest.diff" | grep -o '<[a-z][a-z-]*' | sort -u); do
      grep -qF "$tag" "$JUSTIF" || bad "A5 added manifest element without a justification line: $tag"
    done
    say "A5 justifications checked against $JUSTIF"
  else
    bad "A5 manifest delta exists but no justifications file given"
  fi
fi

if [ "$fail" -ne 0 ]; then echo "v029 GATE FAILURES PRESENT"; exit 1; fi
echo "v029 gate: A1-A5 clean"
