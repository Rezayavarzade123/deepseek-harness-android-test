#!/usr/bin/env bash
# Build an installable APK without Gradle.
#
#   scripts/fetch-toolchain.sh      # once: aapt2, android.jar, d8, apksigner, ECJ
#   scripts/build-apk.sh            # produces apps/android/dist/dsh-mobile-<version>.apk
#
# Environment:
#   JAVA                 java executable to use (default: java on PATH)
#   DSH_ANDROID_TOOLS    toolchain directory (default: apps/android/.tools)
#   VERSION_CODE         integer version code (default: 21)
#   VERSION_NAME         version string (default: 0.2.1-rc.2-android)
#   DSH_KEYSTORE         signing keystore (default: .tools/signing/dsh-mobile.p12)
#   DSH_STORE_PASS       keystore and key password (default: android)
#   DSH_KEY_ALIAS        key alias (default: dsh-mobile)
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
tools="${DSH_ANDROID_TOOLS:-$here/.tools}"
src="$here/app/src/main"
build="$here/build"
dist="$here/dist"

JAVA_BIN="${JAVA:-java}"
AAPT2="$tools/bin/aapt2"
ANDROID_JAR="$tools/lib/android.jar"
D8_JAR="$tools/lib/d8.jar"
APKSIGNER_JAR="$tools/lib/apksigner.jar"
ECJ_JAR="$tools/lib/ecj.jar"

VERSION_CODE="${VERSION_CODE:-23}"
VERSION_NAME="${VERSION_NAME:-0.2.1-rc.2-android}"
MIN_SDK=26
TARGET_SDK=28

KEYSTORE="${DSH_KEYSTORE:-$tools/signing/dsh-mobile.p12}"
STORE_PASS="${DSH_STORE_PASS:-android}"
KEY_ALIAS="${DSH_KEY_ALIAS:-dsh-mobile}"

if [ ! -f "$here/app/src/main/assets/runtime/payload.zip" ] \
  || [ ! -f "$here/app/src/main/assets/runtime/node" ]; then
  echo "build-apk: the embedded runtime is not staged" >&2
  echo "build-apk: run scripts/prepare-runtime.sh first" >&2
  exit 1
fi

for tool in "$AAPT2" "$ANDROID_JAR" "$D8_JAR" "$APKSIGNER_JAR" "$ECJ_JAR"; do
  if [ ! -e "$tool" ]; then
    echo "build-apk: missing $tool" >&2
    echo "build-apk: run scripts/fetch-toolchain.sh first" >&2
    exit 1
  fi
done

command -v "$JAVA_BIN" >/dev/null 2>&1 || {
  echo "build-apk: java not found (set JAVA=/path/to/java)" >&2
  exit 1
}

rm -rf "$build"
mkdir -p "$build/res" "$build/classes" "$build/dex" "$build/apk" "$build/rjava" "$dist"

echo "==> compiling resources"
"$AAPT2" compile --dir "$src/res" -o "$build/res/resources.zip"

echo "==> linking resources against android.jar"
"$AAPT2" link \
  -o "$build/apk/base.apk" \
  -I "$ANDROID_JAR" \
  --manifest "$src/AndroidManifest.xml" \
  --java "$build/rjava" \
  --min-sdk-version "$MIN_SDK" \
  --target-sdk-version "$TARGET_SDK" \
  --version-code "$VERSION_CODE" \
  --version-name "$VERSION_NAME" \
  --auto-add-overlay \
  "$build/res/resources.zip"

echo "==> compiling java with ECJ"
find "$src/java" "$build/rjava" -name '*.java' -print > "$build/sources.txt"
"$JAVA_BIN" -jar "$ECJ_JAR" \
  -source 8 -target 8 -encoding UTF-8 \
  -bootclasspath "$ANDROID_JAR" \
  -classpath "$ANDROID_JAR" \
  -d "$build/classes" \
  "@$build/sources.txt"

echo "==> dexing with D8"
class_files=()
while IFS= read -r file; do
  class_files+=("$file")
done < <(find "$build/classes" -name '*.class' -print)
"$JAVA_BIN" -cp "$D8_JAR" com.android.tools.r8.D8 \
  --min-api "$MIN_SDK" \
  --lib "$ANDROID_JAR" \
  --output "$build/dex" \
  "${class_files[@]}"

echo "==> packaging"
cp "$build/apk/base.apk" "$build/unsigned.apk"
( cd "$build/dex" && zip -q -X -u "$build/unsigned.apk" classes.dex )

# Native runtime: the APK ships Node as libnode.so, so the installer extracts it
# into nativeLibraryDir (executable) instead of app storage (blocked by W^X).
assets_src="$here/app/src/main/assets"
if [ -d "$assets_src" ]; then
  ( cd "$here/app/src/main" && zip -q -X -r -9 "$build/unsigned.apk" assets -x 'assets/runtime/payload.zip' )
  # payload.zip is already deflated; storing it saves install time and bytes.
  if [ -f "$assets_src/runtime/payload.zip" ]; then
    ( cd "$here/app/src/main" && zip -q -X -0 "$build/unsigned.apk" assets/runtime/payload.zip )
  fi
fi

if [ -x "$tools/bin/zipalign" ]; then
  echo "==> aligning"
  "$tools/bin/zipalign" -f -p 4 "$build/unsigned.apk" "$build/aligned.apk"
else
  # No zipalign available: the APK is fully installable (all entries are
  # deflated except resources.arsc, which the platform reads either way);
  # alignment only matters for Play delivery and mmap'd native libraries.
  echo "==> skipping zipalign (not installed)"
  cp "$build/unsigned.apk" "$build/aligned.apk"
fi

if [ ! -f "$KEYSTORE" ]; then
  echo "==> creating signing key at $KEYSTORE"
  mkdir -p "$(dirname "$KEYSTORE")"
  keytool_bin="$(dirname "$(command -v "$JAVA_BIN")")/keytool"
  if [ -x "$keytool_bin" ]; then
    "$keytool_bin" -genkeypair \
      -keystore "$KEYSTORE" -storetype PKCS12 \
      -alias "$KEY_ALIAS" -keyalg RSA -keysize 2048 -validity 10000 \
      -storepass "$STORE_PASS" -keypass "$STORE_PASS" \
      -dname "CN=DSH Mobile, O=DeepSeek Harness, C=NL"
  elif command -v keytool >/dev/null 2>&1; then
    keytool -genkeypair \
      -keystore "$KEYSTORE" -storetype PKCS12 \
      -alias "$KEY_ALIAS" -keyalg RSA -keysize 2048 -validity 10000 \
      -storepass "$STORE_PASS" -keypass "$STORE_PASS" \
      -dname "CN=DSH Mobile, O=DeepSeek Harness, C=NL"
  else
    echo "==> creating signing key with openssl"
    work="$(mktemp -d)"
    openssl req -x509 -newkey rsa:2048 -nodes \
      -keyout "$work/key.pem" -out "$work/cert.pem" -days 10000 \
      -subj "/CN=DSH Mobile/O=DeepSeek Harness/C=NL" >/dev/null 2>&1
    openssl pkcs12 -export \
      -name "$KEY_ALIAS" -in "$work/cert.pem" -inkey "$work/key.pem" \
      -out "$KEYSTORE" -passout "pass:$STORE_PASS" >/dev/null 2>&1
    rm -rf "$work"
  fi
fi

echo "==> signing"
out="$dist/dsh-mobile-$VERSION_NAME.apk"
rm -f "$out"
"$JAVA_BIN" -jar "$APKSIGNER_JAR" sign \
  --ks "$KEYSTORE" \
  --ks-key-alias "$KEY_ALIAS" \
  --ks-pass "pass:$STORE_PASS" \
  --key-pass "pass:$STORE_PASS" \
  --v1-signing-enabled false \
  --v2-signing-enabled true \
  --v3-signing-enabled true \
  --v4-signing-enabled false \
  --out "$out" \
  "$build/aligned.apk"

# A stale v4 sidecar from an earlier run would otherwise sit beside the APK.
rm -f "$out.idsig"

echo "==> verifying"
"$JAVA_BIN" -jar "$APKSIGNER_JAR" verify --verbose --print-certs "$out"

bytes="$(wc -c < "$out" | tr -d ' ')"
echo
echo "APK: $out ($bytes bytes)"
