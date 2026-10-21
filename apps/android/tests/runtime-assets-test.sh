#!/usr/bin/env bash
# Smoke-check the packaged embedded runtime: the Node ELF, the payload zip and
# the manifest rules the installer depends on. Runs without a device.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
assets="$here/app/src/main/assets/runtime"
checks=0
failures=0

ok() { checks=$((checks + 1)); }
bad() { checks=$((checks + 1)); failures=$((failures + 1)); echo "FAIL $1"; }

if [ ! -f "$assets/node" ] || [ ! -f "$assets/payload.zip" ]; then
  echo "runtime-assets: assets are not staged (run scripts/prepare-runtime.sh); skipping"
  exit 0
fi

# --- Node binary -------------------------------------------------------------
if [ "$(head -c 4 "$assets/node")" = "$(printf '\177ELF')" ]; then ok; else bad "node asset is an ELF binary"; fi
machine="$(od -An -tx1 -j18 -N2 "$assets/node" | tr -d ' \n')"
if [ "$machine" = "b700" ]; then ok; else bad "node asset targets aarch64 (got $machine)"; fi
if [ "$(python3 "$here/scripts/patch-elf-rpath.py" --show "$assets/node" | sed 's/.*: //')" = '$ORIGIN/lib' ]; then
  ok
else
  bad "node asset resolves libraries from \$ORIGIN/lib"
fi

# --- shared libraries --------------------------------------------------------
libs_ok=1
for lib in "$assets"/lib/*; do
  runpath="$(python3 "$here/scripts/patch-elf-rpath.py" --show "$lib" | sed 's/.*: //')"
  # libc++_shared only needs bionic system libraries, so no runpath is correct.
  case "$runpath" in
    '$ORIGIN'|'(no runpath)') ;;
    *) echo "FAIL $(basename "$lib") has runpath $runpath"; libs_ok=0 ;;
  esac
done
if [ "$libs_ok" = 1 ]; then ok; else bad "bundled libraries are relocatable"; fi

for required in libc++_shared.so libcares.so libcrypto.so.3 libicudata.so.78 \
  libicui18n.so.78 libicuuc.so.78 libsqlite3.so libssl.so.3 libz.so.1; do
  if [ -f "$assets/lib/$required" ]; then ok; else bad "bundled $required"; fi
done

# --- payload -----------------------------------------------------------------
if python3 - "$assets/payload.zip" <<'PY'
import sys, zipfile
names = set(zipfile.ZipFile(sys.argv[1]).namelist())
required = [
    'node_modules/@deepseek-ai/dsh/lib/bin.js',
    'node_modules/node-addon-require-builtin/lib/index.js',
    'node_modules/@deepseek-ai/node-addon-system/lib/flock.js',
]
missing = [name for name in required if name not in names]
pruned = [name for name in names if 'dsh-office-to-pdf' in name or '@koromix' in name]
if missing or pruned:
    print('payload problems:', missing, pruned[:3])
    sys.exit(1)
PY
then ok; else bad "payload.zip carries the patched harness and nothing native"; fi

# --- manifest and build rules ------------------------------------------------
manifest="$here/app/src/main/AndroidManifest.xml"
if grep -q 'android:targetSdkVersion="28"' "$manifest"; then ok; else bad "targetSdk 28 (native exec from app storage)"; fi
if grep -q 'android:versionCode="23"' "$manifest"; then ok; else bad "versionCode 23"; fi
if grep -q 'EmbeddedHarness' "$here/app/src/main/java/dev/dsh/harness/MainActivity.java"; then
  ok
else
  bad "MainActivity starts the embedded runtime"
fi
if grep -q 'prepare-runtime.sh' "$here/scripts/build-apk.sh"; then
  ok
else
  bad "build-apk.sh explains how to stage the runtime"
fi

echo "runtime-assets: $checks checks, $failures failures"
[ "$failures" -eq 0 ]
