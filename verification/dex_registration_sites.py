#!/usr/bin/env python3
"""Registration-site census for the v029 audit (Gate 0 C-19,
ARCHITECT §4) — a generalization of dex_sender_sites.py: the
target set is parameterized instead of one hardcoded class.

Modes:
  sender       A1 dex half: enumerate <init> invoke sites of the
               target class (default: the legacy clientlib
               ActivityResultSender) in an extracted dex dir.
  registration A2/A3 dex pass: enumerate every invoke site of the
               activity-result registration APIs
               (ComponentActivity.registerForActivityResult,
               ActivityResultRegistry.register) with its caller
               class (R8-mapping-resolved to the original name)
               and caller method.
  aar          A3 AAR pass: for each .aar in --aar-dir, pre-filter
               classes.jar entries whose bytes reference
               registerForActivityResult, then javap -c only those
               classes and enumerate their registration call sites.

Output is a census, one SITE line per call site; assertions live
in v029_gate.sh / the build record's classification lines.

Usage:
  dex_registration_sites.py sender <dexdir> [mapping.txt] [--target-class com.x.Y]
  dex_registration_sites.py registration <dexdir> [mapping.txt]
  dex_registration_sites.py aar <aar-dir>
"""
import glob
import os
import re
import subprocess
import sys
import tempfile
import zipfile

DEFAULT_TARGET = "com.solana.mobilewalletadapter.clientlib.ActivityResultSender"

REGISTRATION_NEEDLES = [
    ("Landroidx/activity/ComponentActivity;", "registerForActivityResult"),
    ("Landroidx/activity/result/ActivityResultRegistry;", "register"),
]


def find_dexdump():
    sdk = os.environ.get("ANDROID_HOME") or os.path.expanduser("~/workspace/tools/android-sdk")
    cands = sorted(glob.glob(os.path.join(sdk, "build-tools", "*", "dexdump")), reverse=True)
    if not cands:
        print("dexdump not found under", sdk)
        sys.exit(2)
    return cands[0]


def find_javap():
    jdk = os.environ.get("JAVA_HOME") or os.path.expanduser("~/workspace/tools/jdk-17.0.20.1+1")
    javap = os.path.join(jdk, "bin", "javap")
    return javap if os.path.exists(javap) else "javap"


def subprocess_env():
    env = dict(os.environ)
    jdk = os.environ.get("JAVA_HOME") or os.path.expanduser("~/workspace/tools/jdk-17.0.20.1+1")
    env["PATH"] = os.path.join(jdk, "bin") + os.pathsep + env.get("PATH", "")
    return env


def load_mapping(mapping_path):
    """Return (orig->obf, obf->orig) class-name dicts from mapping.txt."""
    orig_to_obf, obf_to_orig = {}, {}
    if mapping_path and os.path.exists(mapping_path):
        with open(mapping_path, encoding="utf-8", errors="replace") as fh:
            for line in fh:
                if " -> " in line and line.rstrip().endswith(":") and not line.startswith(" "):
                    orig, obf = line.split(" -> ", 1)
                    obf = obf.strip().rstrip(":")
                    orig_to_obf[orig.strip()] = obf
                    obf_to_orig[obf] = orig.strip()
    return orig_to_obf, obf_to_orig


def descriptor(class_name):
    return "L" + class_name.replace(".", "/") + ";"


def original_name(desc, obf_to_orig):
    if desc.startswith("L") and desc.endswith(";"):
        dotted = desc[1:-1].replace("/", ".")
        return obf_to_orig.get(dotted, dotted)
    return desc


def scan_dex(dexdir, mapping, mode, target_class):
    orig_to_obf, obf_to_orig = load_mapping(mapping)
    dexdump = find_dexdump()
    env = subprocess_env()
    sender_desc = descriptor(orig_to_obf.get(target_class, target_class))
    sites = []
    for dex in sorted(glob.glob(os.path.join(dexdir, "classes*.dex"))):
        out = subprocess.run([dexdump, "-d", dex], capture_output=True, text=True,
                             errors="replace", env=env).stdout
        if not out:
            print(f"FAIL: dexdump produced no output for {dex}")
            return None
        cur_class, cur_method = None, None
        for line in out.splitlines():
            m = re.search(r"Class descriptor\s+: '([^']+)'", line)
            if m:
                cur_class = m.group(1)
                continue
            m = re.search(r"^\s+name\s+: '([^']+)'", line)
            if m:
                cur_method = m.group(1)
                continue
            if "invoke-" not in line:
                continue
            caller = original_name(cur_class or "?", obf_to_orig)
            if mode == "sender":
                # Owner-precise needle (inherited rule): the class
                # descriptor immediately followed by ".<init>" — a
                # constructor that merely TAKES the target as a
                # parameter type must not count.
                if sender_desc + ".<init>" in line:
                    sites.append((os.path.basename(dex), caller, cur_method, "sender-init"))
            else:
                for owner, method in REGISTRATION_NEEDLES:
                    if owner + "." + method in line:
                        sites.append((os.path.basename(dex), caller, cur_method,
                                      owner.strip("L;").replace("/", ".") + "." + method))
    return sites


def scan_aars(aar_dir):
    javap = find_javap()
    env = subprocess_env()
    sites = []
    for aar in sorted(glob.glob(os.path.join(aar_dir, "*.aar"))):
        with zipfile.ZipFile(aar) as zf:
            names = zf.namelist()
            if "classes.jar" not in names:
                continue
            jar_bytes = zf.read("classes.jar")
        with tempfile.TemporaryDirectory() as td:
            jar_path = os.path.join(td, "classes.jar")
            with open(jar_path, "wb") as fh:
                fh.write(jar_bytes)
            with zipfile.ZipFile(jar_path) as jf:
                class_names = [n for n in jf.namelist() if n.endswith(".class")]
                hits = []
                for n in class_names:
                    if b"registerForActivityResult" in jf.read(n):
                        hits.append(n)
            for hit in hits:
                fqcn = hit[:-len(".class")].replace("/", ".")
                out = subprocess.run([javap, "-c", "-p", "-cp", jar_path, fqcn],
                                     capture_output=True, text=True,
                                     errors="replace", env=env).stdout
                cur_method = None
                for line in out.splitlines():
                    mm = re.match(r"\s{2}(?:public|private|protected|static|final|\s)*[\w<>\[\].]+\s+(\w+|<init>|<clinit>)\(", line)
                    if mm and "(" in line:
                        cur_method = mm.group(1)
                    if "registerForActivityResult" in line or "ActivityResultRegistry.register" in line:
                        if "Method" in line or "InterfaceMethod" in line:
                            sites.append((os.path.basename(aar), fqcn, cur_method or "?",
                                          "registration"))
    return sites


def main():
    mode = sys.argv[1] if len(sys.argv) > 1 else ""
    if mode in ("sender", "registration"):
        dexdir = sys.argv[2]
        mapping = sys.argv[3] if len(sys.argv) > 3 and not sys.argv[3].startswith("--") else None
        target = DEFAULT_TARGET
        if "--target-class" in sys.argv:
            target = sys.argv[sys.argv.index("--target-class") + 1]
        sites = scan_dex(dexdir, mapping, mode, target)
        if sites is None:
            return 1
        for dex, caller, method, kind in sites:
            print(f"SITE mode={mode} dex={dex} caller={caller} method={method} target={kind}")
        print(f"COUNT mode={mode} sites={len(sites)}")
        return 0
    if mode == "aar":
        sites = scan_aars(sys.argv[2])
        for aar, caller, method, kind in sites:
            print(f"SITE mode=aar aar={aar} caller={caller} method={method} target={kind}")
        print(f"COUNT mode=aar sites={len(sites)}")
        return 0
    print(__doc__)
    return 2


if __name__ == "__main__":
    sys.exit(main())
