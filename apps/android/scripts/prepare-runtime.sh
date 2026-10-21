#!/usr/bin/env bash
# Stage the on-device runtime the APK carries.
#
#   app/src/main/assets/runtime/node            Node 24 built for Android (bionic)
#   app/src/main/assets/runtime/lib/*           the shared libraries it links
#   app/src/main/assets/runtime/payload.zip     the harness installation (pruned)
#   app/src/main/assets/runtime/cacert.pem      root certificates
#
# Sources:
#   * Node for Android (arm64, bionic): the Magisk-Node.js module tree, which
#     publishes the Termux-built binary. Node's own project ships no Android
#     artifact, and nodejs-mobile stops at Node 18 — below the harness floor.
#   * Harness: the published `@deepseek-ai/dsh` npm package, installed here and
#     pruned of packages the phone cannot use (WASM office engine, speech,
#     sharp, node-pty, telemetry SDKs) and of native builds for other systems.
#
# Two payload patches are applied afterwards (see scripts/patch-payload.mjs):
# a JS replacement for the native require-builtin addon, and an android branch
# for the flock addon. Both are documented in README.md.
#
# Environment:
#   NODE_DISTRO_URL   tarball with system/usr/lib/node/{node,lib} layout
#   DSH_VERSION       harness version to install (default: 0.2.1-rc.2)
#   WORK              staging directory (default: .runtime-build)
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
work="${WORK:-$here/.runtime-build}"
dsh_version="${DSH_VERSION:-0.2.1-rc.2}"
node_url="${NODE_DISTRO_URL:-https://codeload.github.com/FaaRamadhann/Magisk-Node.js/tar.gz/6fc0be701c6d64e1afd3bccafd137e70fc85dbae}"

command -v npm >/dev/null || { echo "prepare-runtime: npm is required" >&2; exit 1; }
command -v zip >/dev/null || { echo "prepare-runtime: zip is required" >&2; exit 1; }

mkdir -p "$work"
node_dir="$work/node"

if [ ! -x "$node_dir/usr/lib/node/node" ]; then
  echo "==> fetching Node for Android"
  rm -rf "$node_dir" "$work/node.tar.gz"
  curl -fsSL -o "$work/node.tar.gz" "$node_url"
  mkdir -p "$node_dir"
  tar xzf "$work/node.tar.gz" -C "$node_dir" --strip-components=1
  if [ ! -x "$node_dir/usr/lib/node/node" ]; then
    echo "prepare-runtime: unexpected Node archive layout" >&2
    exit 1
  fi
fi

node_bin="$(readlink -f "$node_dir/usr/lib/node/node")"
version_line="$(strings -a "$node_bin" | grep -E '^v[0-9]+\.[0-9]+\.[0-9]+$' | sort -u | head -1)"
echo "==> Node runtime: ${version_line:-unknown}"

if [ ! -d "$work/payload/node_modules/@deepseek-ai/dsh" ]; then
  echo "==> installing dsh@$dsh_version (runtime dependencies only)"
  rm -rf "$work/payload"
  mkdir -p "$work/payload"
  ( cd "$work/payload" \
    && npm init -y >/dev/null \
    && npm install --omit=dev --ignore-scripts --no-audit --no-fund "@deepseek-ai/dsh@$dsh_version" )
fi

echo "==> pruning packages the phone cannot run"
node "$here/scripts/patch-payload.mjs" prune "$work/payload"

echo "==> applying android patches"
node "$here/scripts/patch-payload.mjs" patch "$work/payload"

echo "==> packing payload.zip"
rm -f "$work/payload.zip"
( cd "$work/payload" && zip -q -r -9 "$work/payload.zip" node_modules package.json )

echo "==> staging app assets"
assets="$here/app/src/main/assets/runtime"
rm -rf "$assets" "$here/app/src/main/jniLibs"
mkdir -p "$assets/lib"
cp "$work/payload.zip" "$assets/payload.zip"
cp "$node_dir/usr/lib/node/cacert.pem" "$assets/cacert.pem"
for lib in libc++_shared.so libcares.so libcrypto.so.3 libicudata.so.78 libicui18n.so.78 \
  libicuuc.so.78 libsqlite3.so libssl.so.3 libz.so.1; do
  cp "$node_dir/usr/lib/node/lib/$lib" "$assets/lib/$lib"
done
cp "$node_bin" "$assets/node"

echo "==> rewriting the ELF runpaths"
# The Termux build points at /data/data/com.termux/files/usr/lib; on the phone
# the runtime lives in app storage, so switch to paths the linker resolves
# relative to each ELF file (bionic expands $ORIGIN from API 24 on).
python3 "$here/scripts/patch-elf-rpath.py" '$ORIGIN/lib' "$assets/node"
for lib in "$assets"/lib/*; do
  # libc++_shared.so only needs bionic system libraries and has no runpath.
  python3 "$here/scripts/patch-elf-rpath.py" '$ORIGIN' "$lib" || echo "    $(basename "$lib") needs no runpath"
done
chmod 0644 "$assets/node" "$assets"/lib/*

du -sh "$assets" | sed 's/^/    /'
echo "prepare-runtime: ready (payload.zip is already compressed; the APK stores it as-is)"
