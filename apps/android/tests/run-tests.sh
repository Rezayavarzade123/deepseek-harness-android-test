#!/usr/bin/env bash
# Compile and run the plain-JVM rules that do not touch android.*.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
tools="${DSH_ANDROID_TOOLS:-$here/.tools}"
JAVA_BIN="${JAVA:-java}"
out="$here/build/tests"

rm -rf "$out"
mkdir -p "$out"
"$JAVA_BIN" -jar "$tools/lib/ecj.jar" -source 8 -target 8 -d "$out" \
  "$here/app/src/main/java/dev/dsh/harness/ServerTarget.java" \
  "$here/tests/ServerTargetTest.java"
"$JAVA_BIN" -cp "$out" ServerTargetTest
