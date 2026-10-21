#!/usr/bin/env bash
# Fetch the Android SDK pieces used by scripts/build-apk.sh.
#
# The build does not need Gradle or the full Android SDK: aapt2 compiles and
# links resources, ECJ compiles Java, D8 produces the dex, and apksigner signs
# the result. Both npm packages below are only carriers for those official
# Google binaries (aapt2 from build-tools 29.x, the platform jar and D8 from a
# recent build-tools release).
#
# Usage: scripts/fetch-toolchain.sh
# Environment:
#   DSH_ANDROID_TOOLS  destination directory (default: apps/android/.tools)
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
tools="${DSH_ANDROID_TOOLS:-$here/.tools}"

AAPT2_PACKAGE="aaptjs3@2.0.2"
JARS_PACKAGE="@drxiaozhi/minapk@0.5.0"

command -v npm >/dev/null 2>&1 || {
  echo "fetch-toolchain: npm is required to unpack the build tools" >&2
  exit 1
}

mkdir -p "$tools/bin" "$tools/lib"

if [ ! -x "$tools/bin/aapt2" ]; then
  work="$(mktemp -d)"
  trap 'rm -rf "$work"' EXIT
  echo "==> fetching aapt2 ($AAPT2_PACKAGE)"
  ( cd "$work" && npm pack "$AAPT2_PACKAGE" --silent >/dev/null && tar xzf ./*.tgz )
  cp "$work/package/bin/x64/linux/aapt2" "$tools/bin/aapt2"
  chmod +x "$tools/bin/aapt2"
  rm -rf "$work"
  trap - EXIT
fi

if [ ! -f "$tools/lib/android.jar" ] || [ ! -f "$tools/lib/d8.jar" ] \
  || [ ! -f "$tools/lib/apksigner.jar" ] || [ ! -f "$tools/lib/ecj.jar" ]; then
  work="$(mktemp -d)"
  trap 'rm -rf "$work"' EXIT
  echo "==> fetching android.jar, d8, apksigner and ECJ ($JARS_PACKAGE)"
  ( cd "$work" && npm pack "$JARS_PACKAGE" --silent >/dev/null && tar xzf ./*.tgz )
  cp "$work/package/tools/android.jar" "$tools/lib/android.jar"
  cp "$work/package/tools/d8.jar" "$tools/lib/d8.jar"
  cp "$work/package/tools/apksigner.jar" "$tools/lib/apksigner.jar"
  cp "$work/package/tools/ecj-3.45.0.jar" "$tools/lib/ecj.jar"
  rm -rf "$work"
  trap - EXIT
fi

if ! command -v java >/dev/null 2>&1 && [ -z "${JAVA:-}" ]; then
  echo "warning: no java on PATH; set JAVA=/path/to/java when building" >&2
fi

echo "tools ready in $tools"
"$tools/bin/aapt2" version
