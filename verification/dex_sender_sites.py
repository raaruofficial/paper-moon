#!/usr/bin/env python3
"""List ActivityResultSender.<init> invoke sites in an APK's dex files.

Usage: dex_sender_sites.py <dir-with-classes*.dex> [r8-mapping.txt]

Resolves the sender class name through the R8 mapping when the class
was obfuscated. Uses dexdump (Android SDK build-tools) for
disassembly. PASS (exit 0) iff exactly one construction site exists
in the app's own classes and it is MainActivity.onCreate.
"""
import glob
import os
import re
import subprocess
import sys

TARGET_ORIGINAL = "com.solana.mobilewalletadapter.clientlib.ActivityResultSender"


def find_dexdump():
    sdk = os.environ.get("ANDROID_HOME") or os.path.expanduser("~/workspace/tools/android-sdk")
    cands = sorted(glob.glob(os.path.join(sdk, "build-tools", "*", "dexdump")), reverse=True)
    if not cands:
        print("dexdump not found under", sdk)
        sys.exit(2)
    return cands[0]


def mapped_name(mapping_path):
    """Original class name -> obfuscated name, from R8 mapping.txt."""
    if mapping_path and os.path.exists(mapping_path):
        with open(mapping_path, encoding="utf-8", errors="replace") as fh:
            for line in fh:
                if line.startswith(TARGET_ORIGINAL + " -> "):
                    return line.split("->")[1].strip().rstrip(":")
    return TARGET_ORIGINAL


def main():
    dexdir = sys.argv[1]
    mapping = sys.argv[2] if len(sys.argv) > 2 else None
    obf = mapped_name(mapping)
    descriptor = "L" + obf.replace(".", "/") + ";"
    print(f"v025 dex target: {descriptor} (original {TARGET_ORIGINAL})")
    dexdump = find_dexdump()
    # dexdump is a shell wrapper that execs `java` — make sure a JDK
    # is on PATH for the subprocess or the dump silently comes back
    # empty (this bit the first gate run).
    env = dict(os.environ)
    jdk = os.environ.get("JAVA_HOME") or os.path.expanduser("~/workspace/tools/jdk-17.0.20.1+1")
    env["PATH"] = os.path.join(jdk, "bin") + os.pathsep + env.get("PATH", "")
    sites = []
    for dex in sorted(glob.glob(os.path.join(dexdir, "classes*.dex"))):
        out = subprocess.run([dexdump, "-d", dex], capture_output=True, text=True,
                             errors="replace", env=env).stdout
        if not out:
            print(f"v025 FAIL: dexdump produced no output for {dex}")
            return 1
        cur_class = None
        cur_method = None
        for line in out.splitlines():
            m = re.search(r"Class descriptor\s+: '([^']+)'", line)
            if m:
                cur_class = m.group(1)
                continue
            m = re.search(r"^\s+name\s+: '([^']+)'", line)
            if m:
                cur_method = m.group(1)
                continue
            # dexdump prints the invoke target as
            #   }, Lcom/.../ActivityResultSender;.<init>:(...)V
            # i.e. the class descriptor immediately followed by
            # ".<init>". Match the OWNER precisely — a constructor of
            # some other class that merely takes the sender as a
            # parameter type (its descriptor appearing inside the
            # signature) must not count.
            needle = descriptor + "." + "<init>"
            if "invoke-" in line and needle in line:
                sites.append((os.path.basename(dex), cur_class, cur_method))
    print(f"v025 dex ActivityResultSender.<init> invoke sites = {len(sites)} (must be 1)")
    for s in sites:
        print("v025 dex site:", s)
    ok = (
        len(sites) == 1
        and sites[0][1] is not None
        and "MainActivity" in sites[0][1]
        and sites[0][2] == "onCreate"
    )
    if not ok:
        print("v025 FAIL: dex construction-site audit failed")
        return 1
    print("v025 dex construction audit: single site, MainActivity.onCreate")
    return 0


if __name__ == "__main__":
    sys.exit(main())
